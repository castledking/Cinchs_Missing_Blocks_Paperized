#!/usr/bin/env python3
"""Generate the Cinch's Missing Blocks Paperized pack.

Design rules this script enforces, because the user asked for both:

*Deterministic* - every mapping is emitted in sorted order, no timestamps are
baked into any file, and the carrier pool hands out states in a fixed order. Two
runs over the same inputs produce byte-identical output, so the pack can be
diffed in review.

*Fail before writing* - the whole pack is built in memory and validated first.
If carrier allocation overflows, or the mod tree is missing something, the script
exits non-zero having touched nothing on disk. It never leaves a half-written
pack behind.

Usage:
    python3 tools/generate_pack.py --mod <path-to-mod> --out <pack-dir>
    python3 tools/generate_pack.py --check          # validate only, write nothing
"""

from __future__ import annotations

import argparse
import collections
import dataclasses
import itertools
import json
import pathlib
import re
import shutil
import sys
import textwrap

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import intermediate as ir  # noqa: E402
from preparse_claims import PreParseClaims  # noqa: E402
from block_identities import Identities  # noqa: E402
import mod_data  # noqa: E402
import multipart as mp  # noqa: E402
import carrier_eligibility as elig  # noqa: E402
import pack_assets  # noqa: E402
import vanilla_slabs  # noqa: E402
import vanilla_tools  # noqa: E402
from block_mappings import BlockMappings  # noqa: E402
from intermediate import FAMILY_CARRIER_POOL, FAMILY_PROJECTION_PIN  # noqa: E402
import modsource  # noqa: E402
import vanilla_carriers as vc  # noqa: E402

MOD_ID = "cinchsmissingblocks"
PACK_VERSION = "4.4.0"


# Colour names that only exist as terracotta/concrete variants, so the whole
# family can be dropped without leaving orphan recipes behind.
# Buttons and pressure plates have no collision-safe carrier on 26.3 (the plate pool
# offers 0 states: every vanilla button state is reachable, and a plate needs both of
# its `powered` states). polished_deepslate_button and polished_deepslate_pressure_plate
# used to be emitted as "aliases": nether_brick items with a vanilla look-alike model
# and no block behind them, which placed nothing. They now go through the normal
# pipeline, the allocator records them as unsupported, and they are deferred like
# any other block until a carrier or a cmb behaviour exists.

VARIANT_TOKENS = {
    "terracotta": ("terracotta",),
    "concrete": ("_concrete",),
}


class BuildError(RuntimeError):
    """Anything that must stop the build before a single file is written."""


#: Behaviour ids this plugin owns. cmb registers exactly these, so the generator
#: refuses to emit anything else without it being registered there too.
CMB_BEHAVIOURS = frozenset({"cmb:stairs", "cmb:slab", "cmb:wall",
                             "cmb:button", "cmb:pressure_plate"})
#: CraftEngine's own behaviour ids the pack deliberately reuses.
NATIVE_BEHAVIOURS = frozenset({"fence_block"})


def validate_behaviour_namespacing(content: "ir.Content") -> None:
    """Every emitted behaviour id must be a registered cmb id or a native one.

    CraftEngine resolves a behaviour id with Key.ce(type), which keeps an explicit
    namespace but forces a bare id into craftengine:. A missing or misspelled colon
    therefore loads cleanly and then fails per block with an unhelpful unknown-type
    error, so it is caught here instead.
    """
    unknown: dict[str, set[str]] = collections.defaultdict(set)
    for block in content.blocks.values():
        for behaviour in block.behaviors:
            kind = behaviour.get("type") if isinstance(behaviour, dict) else str(behaviour)
            if kind not in CMB_BEHAVIOURS and kind not in NATIVE_BEHAVIOURS:
                unknown[str(kind)].add(block.id)

    if unknown:
        listed = "\n".join(f"  {kind} (e.g. {sorted(ids)[0]})"
                           for kind, ids in sorted(unknown.items()))
        raise BuildError(
            "behaviour id is neither a registered cmb behaviour nor a CraftEngine "
            f"built-in.\n{listed}\n\n"
            f"cmb registers: {', '.join(sorted(CMB_BEHAVIOURS))}\n"
            f"reused natively: {', '.join(sorted(NATIVE_BEHAVIOURS))}\n"
            f"Add it to cmb's CmbBehaviors before emitting it.")


# --------------------------------------------------------------------------- #
# configuration
# --------------------------------------------------------------------------- #

@dataclasses.dataclass
class PackConfig:
    """User-facing toggles. Mirrored into the pack's own config.yml."""

    disable_terracotta_variants: bool = True
    disable_concrete_variants: bool = True
    #: ViaBackwards is installed, so older clients join: keep CMB blocks vanilla has
    #: since added (concrete slabs and stairs on 26.3), which those clients lack
    viabackwards: bool = False
    vertical_slabs: bool = False          # either source enabled; summary/back-compat
    vertical_slabs_cmb: bool = False
    vertical_slabs_vanilla: bool = False
    #: material names without `_vertical`, e.g. `oak`, `andesite_brick`
    vertical_slabs_disabled: tuple[str, ...] = ()
    #: what two slabs in one cell become: "block" (a real block on a CraftEngine
    #: auto-state; Jade names its vanilla carrier) or "furniture" (Jade names it)
    vertical_slabs_doubles: str = "block"
    #: L-shaped full-height corner pieces for vertical slab walls, one per material
    horizontal_stairs_cmb: bool = False
    horizontal_stairs_vanilla: bool = False
    horizontal_stairs_disabled: tuple[str, ...] = ()
    #: deferred CMB stairs/slabs served as furniture instead: True for all of them,
    #: or a tuple of names for just those
    furniture_fallback: bool | tuple[str, ...] = True

    def blocks_enabled(self, variant: str) -> bool:
        return not getattr(self, f"disable_{variant}_variants")


#: What to do when the running server cannot represent a block the project defines.
#:
#: "disable" skips it at registration and reports it, which is what a server owner
#: wants; "fail" refuses to start. Either way the block stays in the project.
UNSUPPORTED_POLICIES = ("disable", "fail")


@dataclasses.dataclass
class StateBudget:
    """Ceiling on CraftEngine internal states, with a reserved tail.

    CraftEngine registers every declared state combination as a real entry in the
    vanilla block registry, so the count is this pack's memory and startup cost.
    The reserved tail is never handed out, so this pack cannot exhaust the pool for
    other packs on the same server.
    """

    max_internal_states: int = 3072
    reserved_states: int = 32

    @property
    def usable(self) -> int:
        return self.max_internal_states - self.reserved_states

    def check(self, used: int) -> None:
        if used > self.usable:
            raise BuildError(
                f"internal state budget exceeded: pack needs {used} states but only "
                f"{self.usable} are usable "
                f"(max-internal-states {self.max_internal_states} "
                f"minus reserved-states {self.reserved_states}).\n"
                f"  Either raise max-internal-states in tools/build-config.yml and "
                f"match it in CraftEngine's block.serverside-blocks, or turn off the "
                f"terracotta/concrete variants.")


def load_build_config(path: pathlib.Path) -> tuple[PackConfig, StateBudget]:
    """Read tools/build-config.yml. A tiny reader keeps this script dependency-free."""
    if not path.is_file():
        return PackConfig(), StateBudget()

    section: dict = {}
    current: str | None = None
    for raw in path.read_text().splitlines():
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        if not line.startswith(" "):
            current = line.split(":")[0].strip()
            section[current] = {}
        elif current and ":" in line:
            key, _, value = line.strip().partition(":")
            section[current][key.strip()] = value.strip()

    def flag(group: str, key: str, fallback: bool) -> bool:
        raw = section.get(group, {}).get(key)
        return fallback if raw is None else raw.lower() == "true"

    def number(group: str, key: str, fallback: int) -> int:
        raw = section.get(group, {}).get(key)
        try:
            return int(raw) if raw is not None else fallback
        except ValueError:
            return fallback

    # Lists need a real YAML reader; the flat reader above only handles key: value.
    import yaml
    raw_all = yaml.safe_load(path.read_text()) or {}
    raw_features = raw_all.get("features") or {}
    if "vanilla-replacements" in raw_all:
        raise BuildError("vanilla-replacements was removed: every CMB stair, slab, wall, "
                         "fence and pane is served as furniture (features.furniture-"
                         "fallback). Delete the vanilla-replacements section.")
    cfg = PackConfig(
        disable_terracotta_variants=flag("variants", "disable-terracotta-variants", True),
        disable_concrete_variants=flag("variants", "disable-concrete-variants", True),
        viabackwards=flag("compatibility", "viabackwards", False),
        **_vertical_slab_config(raw_features.get("vertical-slabs")),
        **_horizontal_stairs_config(raw_features.get("horizontal-stairs")),
        furniture_fallback=_furniture_fallback_config(raw_features.get("furniture-fallback")),
    )
    budget = StateBudget(
        max_internal_states=number("craftengine", "max-internal-states", 3072),
        reserved_states=number("craftengine", "reserved-states", 32),
    )
    return cfg, budget


def _vertical_slab_config(raw) -> dict:
    """``features.vertical-slabs``: a mapping ``{cmb, vanilla, disabled}`` - the same
    schema as the plugin's config.yml - or a plain boolean meaning both sources."""
    cmb, vanilla, disabled = _sources(raw)
    doubles = str(raw.get("doubles", "block")) if isinstance(raw, dict) else "block"
    if doubles not in ("block", "furniture", "both"):
        raise BuildError(f"features.vertical-slabs.doubles must be block or furniture "
                         f"(or both, for the jar's bundled pack), not {doubles!r}")
    return {"vertical_slabs": cmb or vanilla, "vertical_slabs_cmb": cmb,
            "vertical_slabs_vanilla": vanilla, "vertical_slabs_disabled": disabled,
            "vertical_slabs_doubles": doubles}


def _horizontal_stairs_config(raw) -> dict:
    """``features.horizontal-stairs``: the same ``{cmb, vanilla, disabled}`` mapping."""
    cmb, vanilla, disabled = _sources(raw)
    return {"horizontal_stairs_cmb": cmb, "horizontal_stairs_vanilla": vanilla,
            "horizontal_stairs_disabled": disabled}


def _furniture_fallback_config(raw) -> bool | tuple[str, ...]:
    """``features.furniture-fallback``: true/false, or a list of names for just those."""
    if isinstance(raw, list):
        return tuple(str(n) for n in raw)
    if raw is None:
        return True
    if isinstance(raw, bool):
        return raw
    raise BuildError("features.furniture-fallback must be true, false or a list of names")


def _sources(raw) -> tuple[bool, bool, tuple[str, ...]]:
    if isinstance(raw, dict):
        return (bool(raw.get("cmb", False)), bool(raw.get("vanilla", False)),
                tuple(str(n) for n in (raw.get("disabled") or ())))
    return bool(raw), bool(raw), ()


def config_yaml(cfg: PackConfig) -> str:
    """The pack's own config.yml, written for a server owner to edit."""
    return textwrap.dedent(f"""\
        # Cinch's Missing Blocks - Paperized
        #
        # Regenerating this file with different values will not help; edit it in
        # place. `python3 tools/generate_pack.py` writes these values, and the
        # build summary printed by that script is the authoritative record of what
        # a given configuration costs.
        #
        # Blocks are backed by CraftEngine, which gives each block state a vanilla
        # block state as its collision/shape carrier. Vanilla only has so many
        # states per shape, so some families have a hard ceiling. The summary
        # printed by the build reports the totals.

        variants:
          # Terracotta and concrete stairs/slabs/walls are 96 of the 381 blocks and
          # the slab family is the tightest carrier pool in the pack, so both
          # default to off. Turning either on with the other still on will fail the
          # build rather than emit a pack with missing blocks.
          disable-terracotta-variants: {str(cfg.disable_terracotta_variants).lower()}
          disable-concrete-variants: {str(cfg.disable_concrete_variants).lower()}

        features:
          # Vertical slabs as CraftEngine furniture: no carrier states used.
          # cmb: from the served CMB slabs/stairs. vanilla: one per vanilla slab.
          # disabled: material names without `_vertical`, e.g. oak.
          vertical-slabs:
            cmb: {str(cfg.vertical_slabs_cmb).lower()}
            vanilla: {str(cfg.vertical_slabs_vanilla).lower()}
            disabled: [{", ".join(cfg.vertical_slabs_disabled)}]
            # block: a real block (Jade shows its vanilla carrier's name).
            # furniture: Jade shows the slab's name; breaks in one hit.
            doubles: {cfg.vertical_slabs_doubles}
          # Horizontal stairs: a stair laid on its side, the corner piece for
          # vertical slab walls. Furniture too, same sources and disabled list.
          horizontal-stairs:
            cmb: {str(cfg.horizontal_stairs_cmb).lower()}
            vanilla: {str(cfg.horizontal_stairs_vanilla).lower()}
            disabled: [{", ".join(cfg.horizontal_stairs_disabled)}]
        """)


# --------------------------------------------------------------------------- #
# family specs
# --------------------------------------------------------------------------- #

@dataclasses.dataclass
class Property:
    name: str
    type: str
    values: tuple[str, ...]
    default: str
    extra: dict = dataclasses.field(default_factory=dict)

    def to_yaml(self) -> dict:
        out: dict = {"type": self.type, "default": self.default}
        if self.type == "string":
            out["values"] = list(self.values)
        elif self.type == "int":
            out["range"] = f"{self.values[0]}~{self.values[-1]}"
        out.update(self.extra)
        return out


BOOL = ("true", "false")

FAMILY_PROPERTIES: dict[str, list[Property]] = {
    "stairs": [
        Property("facing", "horizontal_direction",
                 ("north", "east", "south", "west"), "north"),
        Property("half", "single_block_half", ("bottom", "top"), "bottom"),
        Property("shape", "stairs_shape",
                 ("straight", "inner_left", "inner_right", "outer_left", "outer_right"),
                 "straight"),
    ],
    "slab": [
        Property("type", "slab_type", ("bottom", "top", "double"), "bottom"),
    ],
    # A vertical slab's state space is only which way it faces. It is not a variant of
    # slab and does not reuse cmb:slab: that behaviour implements type=bottom/top/double
    # and vanilla's lying-slab merge rules, neither of which means anything standing up.
    "vertical_slab": [
        Property("facing", "horizontal_facing",
                 ("north", "south", "east", "west"), "north"),
    ],
    "wall": [
        Property(n, "boolean", BOOL, "false") for n in ("north", "east", "south", "west")
    ],
    "pillar": [
        Property("axis", "axis", ("x", "y", "z"), "y"),
    ],
    "fence": [
        Property(n, "boolean", BOOL, "false") for n in ("north", "east", "south", "west")
    ],
    "pressure_plate": [
        Property("powered", "boolean", BOOL, "false"),
    ],
    "button": [
        Property("face", "face_attached", ("wall", "ceiling"), "wall"),
        Property("facing", "horizontal_direction", ("north", "south"), "north"),
        Property("powered", "boolean", BOOL, "false"),
    ],
    "pane": [
        Property(n, "boolean", BOOL, "false") for n in ("north", "east", "south", "west")
    ],
    "cube": [],
}

# How each family declares its behaviour. CraftEngine's own stairs/slab behaviours
# need properties we deliberately do not declare (shape, waterlogged), so the
# companion plugin supplies them; fence_block is used as-is.
#: The cmb: behaviours the companion plugin actually registers (CmbBehaviors.ALL).
#: test_mapping_eligibility.py checks this against the Java source. A family whose
#: behaviour is not here cannot work at runtime - CraftEngine reports an unknown
#: behaviour type and the block fails to load - so the allocator defers it even when
#: carriers exist, instead of shipping an item for a block that never loads.
IMPLEMENTED_CMB_BEHAVIOURS: frozenset[str] = frozenset({"cmb:stairs", "cmb:slab"})


def unimplemented_families() -> dict[str, str]:
    """Content family -> why it cannot run, for families needing a missing cmb: type."""
    out: dict[str, str] = {}
    for family, behaviours in FAMILY_BEHAVIOURS.items():
        missing = sorted(b["type"] for b in behaviours
                         if b["type"].startswith("cmb:")
                         and b["type"] not in IMPLEMENTED_CMB_BEHAVIOURS)
        if missing:
            out[family] = (f"behaviour {', '.join(missing)} is not implemented by the "
                           f"cmb plugin yet; carriers alone cannot make it work")
    return out


#: Families served only as furniture, never on a carrier state.
FURNITURE_FAMILIES = ("stairs", "slab", "pane")


def replacement_selection(cfg: "PackConfig", content: "ir.Content") -> dict[str, list[str]]:
    """The carrier selection for the stair, slab and pane families: none.

    These were once picked by `vanilla-replacements`, which lent a few freed copper
    stair and slab states to a few chosen blocks (4 stairs, 5 slabs). It is removed:
    those blocks could never be waterlogged (only dry states were safe to lend), and
    furniture now does stairs, slabs, walls, fences and panes with corners,
    connections, waterlogging and hit-to-break. Every block in these families is
    deferred by the allocator and served by features.furniture-fallback instead.
    """
    return {family: [] for family in FURNITURE_FAMILIES}


FAMILY_BEHAVIOURS: dict[str, list[dict]] = {
    "stairs": [{"type": "cmb:stairs"}],
    "slab": [{"type": "cmb:slab"}],
    # Vertical slabs declare facing only, and a facing property is exactly what
    # CraftEngine's own horizontal_facing handling turns into a placement direction, so
    # the built-in stairs-style facing handling covers it with no custom behaviour.
    "vertical_slab": [],
    "wall": [{"type": "cmb:wall", "connectable-tag": "minecraft:impermeable"}],
    "pillar": [],
    "fence": [{"type": "fence_block"}],
    "pressure_plate": [{"type": "cmb:pressure_plate"}],
    "button": [{"type": "cmb:button"}],
    "pane": [{"type": "cmb:wall", "connectable-tag": "minecraft:impermeable",
              "post": False}],
    "cube": [],
}

# Families that share one carrier pool, and the fixed properties used to project a
# declared state onto it. A pressure plate and a button both draw from vanilla
# buttons, so they have to pick disjoint slots or they would collide on the same
# vanilla state.
FAMILY_CARRIER_POOL = {
    "pressure_plate": "plate",
    "button": "plate",
    # Panes deliberately do NOT borrow the wall pool any more. A wall carrier gave
    # tinted_glass_pane wall collision - a 1.5-block post with arms - where a pane
    # needs a thin panel. Panes have their own family; they share only their
    # connection semantics, not their shape.
}
#: The property set a shared pool is keyed by, for families that borrow one.
FAMILY_POOL_PROPERTIES = {
    "plate": [
        Property("face", "face_attached", ("floor", "wall", "ceiling"), "floor"),
        Property("facing", "horizontal_direction", ("north", "south"), "north"),
        Property("powered", "boolean", BOOL, "false"),
    ],
}
FAMILY_PROJECTION_PIN = {
    "pressure_plate": {"face": "floor", "facing": "north"},
    "button": {"facing": "north"},
}

FAMILY_NOTES: dict[str, str] = {
    "stairs": """"""            # Stairs declare facing x half x shape (no waterlogged).
            #
            # The companion plugin's `cmb:stairs` behaviour computes the shape from
            # neighbouring cmb stairs, like vanilla does, so corners form. It has no
            # liquid handling: CraftEngine's own `stairs_block` would dereference a
            # missing waterlogged property when water touches the stair.
            #
            # Carriers are freed copper stair states whose shape matches the declared
            # shape, so corner collision is correct. No stair is given one any more
            # (replacement_selection): they are all furniture.
            """""",
    "wall": """"""            # Walls model each side as a boolean rather than vanilla's
            # none/low/tall enum.
            #
            # Full fidelity would be 162 states per wall, and 108 walls would need
            # 17496 carrier states against a wall pool of 4050. Four booleans is 16
            # states per wall, which fits with room to spare. A connected side is
            # carried by a vanilla `low` side.
            #
            # The cost: a side next to a two-block-tall neighbour renders at low
            # height, and walls never grow to three blocks tall on their own (there
            # is no `up` property). See DESIGN.md section 3.
            """""",
    "pillar": """"""            # Pillars ride full-cube carriers through a 3-value `rotation`
            # property, because a vanilla log has exactly three states (`axis`) and
            # needs all of them, so it can lend nothing. The cost is that collision
            # is a full cube rather than vanilla's 2/16-inset column.
            """""",
}

# Which model variant to pick out of the mod's blockstate for a declared state.
# Stairs and fences and panes are multipart or shape-dependent; `shape=straight`
# is the only corner shape we carry.
def blockstate_selector(family: str) -> dict[str, str] | None:
    """Properties pinned when looking up a state's model. Stairs used to pin
    shape=straight, back when they had no shape property; now every shape picks its
    own corner model from the mod's blockstate."""
    return None


# --------------------------------------------------------------------------- #
# YAML emitting
# --------------------------------------------------------------------------- #

def _plain_ok(text: str) -> bool:
    """Whether ``text`` survives as a plain (unquoted) YAML string.

    Decided by the parser itself rather than a hand-kept character list. The list
    missed a leading ``#``: a shaped recipe's ``#: "minecraft:stone"`` key is a YAML
    comment, so the whole ingredient map silently vanished. YAML 1.1 words such as
    ``on``/``yes``/``null``, which CraftEngine's SnakeYAML reads as booleans or null,
    are caught the same way.
    """
    import yaml
    if text == "" or text.strip() != text or any(ch in text for ch in "#:{}[],&*?|<>!%@`'\"\n"):
        return False
    try:
        return yaml.safe_load(f"k: {text}") == {"k": text}
    except yaml.YAMLError:
        return False


def _slug(text: str) -> str:
    """A lang-key-safe slug: lowercase, underscores, no stray punctuation."""
    return re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")


def _key(name) -> str:
    """Render a mapping key, quoted unless it is unambiguously a plain string.

    A block with no declared properties has a single state whose variant key is
    empty, and a bare empty key is not valid YAML - the whole file fails to parse
    and CraftEngine discards it.
    """
    text = str(name)
    return text if _plain_ok(text) else json.dumps(text)


def _scalar(value) -> str:
    if value is True:
        return "true"
    if value is False:
        return "false"
    if value is None:
        return "null"
    if isinstance(value, (int, float)):
        return str(value)
    text = str(value)
    return text if _plain_ok(text) else json.dumps(text)


def yaml_lines(node, indent: int = 0) -> list[str]:
    """Minimal deterministic YAML writer (block style, insertion order kept)."""
    pad = "  " * indent
    out: list[str] = []
    if isinstance(node, dict):
        for key, value in node.items():
            if isinstance(value, (dict, list)) and value:
                out.append(f"{pad}{_key(key)}:")
                out.extend(yaml_lines(value, indent + 1))
            elif isinstance(value, (dict, list)):
                out.append(f"{pad}{_key(key)}: {'{}' if isinstance(value, dict) else '[]'}")
            else:
                out.append(f"{pad}{_key(key)}: {_scalar(value)}")
    elif isinstance(node, list):
        for item in node:
            if isinstance(item, dict):
                body = yaml_lines(item, indent + 1)
                if body:
                    out.append(f"{pad}- {body[0].strip()}")
                    out.extend(body[1:])
            else:
                out.append(f"{pad}- {_scalar(item)}")
    return out


def to_yaml(node, header: str | None = None) -> str:
    body = "\n".join(yaml_lines(node))
    text = (f"{header}\n{body}\n" if header else body) + "\n"
    # Round-trip guard: what CraftEngine will parse must be exactly what was meant.
    # A writer bug otherwise surfaces only as a live-server "issue", or not at all.
    import yaml
    intended = json.loads(json.dumps(node))
    parsed = yaml.safe_load(text)
    if parsed != intended:
        raise BuildError("YAML writer round-trip mismatch: the emitted text does not "
                         "parse back to the intended structure" + _first_difference(
                             intended, parsed))
    return text


def _first_difference(want, got, path: str = "") -> str:
    if isinstance(want, dict) and isinstance(got, dict):
        for key in want:
            if key not in got:
                return f" (missing {path}/{key})"
            found = _first_difference(want[key], got[key], f"{path}/{key}")
            if found:
                return found
        extra = [k for k in got if k not in want]
        return f" (unexpected {path}/{extra[0]})" if extra else ""
    if isinstance(want, list) and isinstance(got, list):
        if len(want) != len(got):
            return f" ({path}: {len(want)} items intended, {len(got)} parsed)"
        for i, (a, b) in enumerate(zip(want, got)):
            found = _first_difference(a, b, f"{path}[{i}]")
            if found:
                return found
        return ""
    return "" if want == got else f" ({path}: intended {want!r}, parsed {got!r})"


def block_id(namespace: str, name: str) -> str:
    return f"{namespace}:{name}" if ":" not in name else name


def write_json(path: pathlib.Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=False, ensure_ascii=False) + "\n")


def write_text(path: pathlib.Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


# --------------------------------------------------------------------------- #
# model resolution
# --------------------------------------------------------------------------- #

def load_blockstate(assets: pathlib.Path, name: str) -> dict | None:
    path = assets / MOD_ID / "blockstates" / f"{name}.json"
    if not path.is_file():
        return None
    return json.loads(path.read_text())


def pick_variant(blockstate: dict, selector: dict[str, str]) -> dict | None:
    """Choose one entry out of a vanilla-style `variants` blockstate."""
    if "variants" not in blockstate:
        return None
    best = None
    for key, value in blockstate["variants"].items():
        state = dict(part.split("=", 1) for part in key.split(",")) if key else {}
        if all(state.get(k) == v for k, v in selector.items()):
            # Prefer the variant that constrains the fewest extra properties, so a
            # shape-dependent blockstate resolves to its straight case.
            score = len(state)
            if best is None or score < best[0]:
                best = (score, value)
    return best[1] if best else None


def variant_node(entry) -> dict:
    """Turn a blockstate variant entry into CraftEngine's model form.

    A variant value may be a list, in which case the client picks one at random -
    the sculk-inlaid-deepslate blockstate uses that to alternate mirrored models.
    The intermediate form keeps the list under `models:`; emission unwraps it, since
    CraftEngine expects the list directly under `model:`.
    """
    if isinstance(entry, list):
        return {"models": [variant_node(e) for e in entry]}
    out: dict = {"path": entry["model"]}
    out: dict = {"path": entry["model"]}
    for key in ("x", "y", "z"):
        if entry.get(key):
            out[key] = entry[key]
    if entry.get("uvlock"):
        out["uvlock"] = True
    return out


def load_wall_model(assets: pathlib.Path, name: str) -> dict | None:
    """Find a wall part model in either the mod's namespace or a vanilla override."""
    for root in (assets / MOD_ID, assets / "minecraft"):
        path = root / "models" / "block" / f"{name}.json"
        if path.is_file():
            return json.loads(path.read_text())
    return None


def wall_parts(assets: pathlib.Path, name: str) -> tuple[list, dict]:
    """Build the (post, sides) part lists for one wall block.

    Each part is (template id, texture slots, y rotation). The mod ships one model
    per part - `<wall>_post`, `<wall>_side`, `<wall>_side_tall` - and each is a thin
    child of a wall template. Sandstone-family walls instead parent the mod's
    two-texture templates, which is why the texture slots are carried through
    rather than collapsed to a single `wall` texture.
    """
    def part(suffix: str, rotation: int):
        model = load_wall_model(assets, f"{name}{suffix}")
        if model is None:
            return None
        textures = dict(model.get("textures", {}))
        textures.setdefault("particle", textures.get("wall", textures.get("side")))
        return model["parent"].split("/")[-1], textures, rotation

    post = part("_post", 0)
    if post is None:
        raise BuildError(f"{name}: no wall post model found")
    sides = {}
    for side, rotation in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        built = part("_side", rotation)
        if built:
            sides[side] = built
    return post, sides


def load_model(assets: pathlib.Path, name: str) -> dict | None:
    path = assets / MOD_ID / "models" / "block" / f"{name}.json"
    if not path.is_file():
        return None
    return json.loads(path.read_text())


def flatten_wall(parts: list[tuple[str, dict, int]], vanilla: dict,
                 assets: pathlib.Path) -> dict:
    """Bake a wall's applicable parts into one model.

    Vanilla walls are a 9-part `multipart` blockstate: a post, four low sides and
    four tall sides. CraftEngine binds exactly one model per carrier state, and a
    carrier state a vanilla wall could also match would draw the vanilla parts as
    well, so the parts that apply to a given connection pattern have to be merged
    into a single model.

    ``parts`` is a list of (template id, texture slots, y rotation). Texture slots
    resolve the `#name` references the templates use, which is what lets the
    sandstone walls keep their separate top and side textures.
    """
    elements: list[dict] = []
    particle = None

    for template_id, textures, rotation in parts:
        template = vanilla.get(template_id) or load_wall_model(assets, template_id)
        if template is None:
            raise BuildError(f"wall template '{template_id}' not found")
        particle = particle or textures.get("particle")
        for element in template.get("elements", []):
            element = rotate_element(json.loads(json.dumps(element)), rotation)
            for face in element.get("faces", {}).values():
                slot = face.get("texture", "")
                if slot.startswith("#"):
                    slot = slot[1:]
                    if slot not in textures:
                        raise BuildError(
                            f"template {template_id} references #{slot} but only "
                            f"{sorted(textures)} are bound")
                    face["texture"] = textures[slot]
            element.pop("__comment", None)
            elements.append(element)

    out: dict = {"elements": elements}
    if particle:
        out = {"textures": {"particle": particle}, **out}
    return out


def rotate_element(element: dict, rotation: int) -> dict:
    """Rotate a model element's coordinates about the block centre by 0/90/180/270."""
    if rotation == 0:
        return element

    def rot(coords: list[int]) -> list[int]:
        """Minecraft's y-rotation, clockwise seen from above.

        The template wall_side is the north-facing arm, so rotating it by 90 has to
        land it against +x (east), which is what the mod's blockstate asks for with
        `"y": 90` on the east entry.
        """
        x, y, z = coords
        if rotation == 90:
            x, z = 16 - z, x
        elif rotation == 180:
            x, z = 16 - x, 16 - z
        elif rotation == 270:
            x, z = z, 16 - x
        return [x, y, z]

    out = dict(element)
    if "from" in out and "to" in out:
        # Rotating each corner independently can leave from > to, because a quarter
        # turn swaps which corner is the lower one. Normalise so the box stays valid.
        a, b = rot(out["from"]), rot(out["to"])
        out["from"] = [min(a[i], b[i]) for i in range(3)]
        out["to"] = [max(a[i], b[i]) for i in range(3)]
    faces = {}
    for face, data in element.get("faces", {}).items():
        data = dict(data)
        if "uv" in data:
            data["uv"] = rot(data["uv"])
        order = {"north": "east", "east": "south", "south": "west", "west": "north",
                 "up": "up", "down": "down"}
        for _ in range(rotation // 90):
            face = order[face]
        faces[face] = data
    if faces:
        out["faces"] = faces
    return out


# --------------------------------------------------------------------------- #
# planning
# --------------------------------------------------------------------------- #

@dataclasses.dataclass
class FamilyBlock:
    """One block resolved down to everything needed to emit it."""

    block: modsource.ModBlock
    declared: list[dict[str, str]]
    appearances: list[dict]
    variants: list[dict]


def combinations(properties: list[Property]) -> list[dict[str, str]]:
    import itertools
    names = [prop.name for prop in properties]
    return [dict(zip(names, combo))
            for combo in itertools.product(*[p.values for p in properties])]


def resolve_mod_block(block: modsource.ModBlock, assets: pathlib.Path,
                      pool: vc.CarrierPool | None, cube_carrier: str | None,
                      templates: dict,
                      generated_models: dict[str, dict] | None = None) -> FamilyBlock:
    """Turn a parsed mod block into declared states plus their carriers."""
    family = block.family
    properties = FAMILY_PROPERTIES[family]
    selector = blockstate_selector(family)
    blockstate = load_blockstate(assets, block.name)

    declared = combinations(properties)
    appearances: list[dict] = []
    variants: list[dict] = []

    if family == "wall":
        # Walls get generated flattened models rather than a blockstate lookup.
        wall_post, wall_sides = wall_parts(assets, block.name)

    pin = FAMILY_PROJECTION_PIN.get(family, {})
    pool_props = (FAMILY_POOL_PROPERTIES.get(FAMILY_CARRIER_POOL.get(family, family))
                  or FAMILY_PROPERTIES.get(family, []))

    for state in declared:
        name = "_".join(f"{k}{v}" for k, v in sorted(state.items()))
        carrier_state = None
        if pool is not None and pool_props:
            lookup = {prop.name: state.get(prop.name, prop.default) for prop in pool_props}
            lookup.update(pin)
            lookup = {k: v for k, v in lookup.items() if v is not None}
            # Walls and panes share the wall carrier pool and fall back to the
            # closest over-connected carrier; see
            # CarrierPool.vanilla_state_nearest for why that is unavoidable.
            carrier_state = (pool.vanilla_state_nearest(lookup)
                             if family == "wall" else pool.vanilla_state(lookup))

        if family == "wall":
            model_path = f"{MOD_ID}:block/{block.name}_flat_{name}"
            if generated_models is not None:
                chosen = [wall_post]
                for side in ("north", "east", "south", "west"):
                    if state.get(side) == "true" and side in wall_sides:
                        chosen.append(wall_sides[side])
                generated_models[f"{block.name}_flat_{name}"] = flatten_wall(
                    chosen, templates, assets)
            appearance = {
                "state": carrier_state,
                "model": {"path": model_path},
            }
        elif family == "pillar":
            carrier = cube_carrier
            entry = pick_variant(blockstate or {}, state) or {"model": f"{MOD_ID}:block/{block.name}"}
            appearance = {"state": carrier, "model": variant_node(entry)}
        elif family == "cube":
            entry = pick_variant(blockstate or {}, {}) or {"model": f"{MOD_ID}:block/{block.name}"}
            appearance = {"state": cube_carrier, "model": variant_node(entry)}
        else:
            entry = pick_variant(blockstate or {}, {**(selector or {}), **state})
            if entry is None:
                entry = {"model": f"{MOD_ID}:block/{block.name}"}
            appearance = {
                "state": carrier_state if pool is not None else None,
                "model": variant_node(entry),
            }

        if appearance["state"] is None:
            raise BuildError(
                f"{block.id}: no carrier state left for "
                f"[{','.join(f'{k}={v}' for k, v in sorted(state.items()))}]")
        appearances.append({"name": name, **appearance})
        variant: dict = {
            "key": ",".join(f"{p.name}={state[p.name]}" for p in properties),
            "appearance": name,
        }
        # A waterlogged carrier state would otherwise hand the block a fluid. The
        # pool runs out of dry states long before it runs out of shapes, so clear it
        # rather than lose the block.
        if "waterlogged=true" in str(appearance["state"]):
            variant["settings"] = {"fluid_state": "none"}
        variants.append(variant)

    return FamilyBlock(block, declared, appearances, variants)


def variant_settings(block: modsource.ModBlock, assets: pathlib.Path,
                     requires_tool: set[str] | None = None) -> dict:
    settings: dict = {"item": block.id}
    if block.hardness is not None:
        settings["hardness"] = block.hardness
    if block.resistance is not None and block.resistance != block.hardness:
        settings["resistance"] = block.resistance
    # The mod's blocks copy a vanilla block's settings, tool requirement included:
    # calcite bricks need a pickaxe because calcite does. Without this every CMB block
    # dropped to a bare hand and mined at hand-on-anything speed. Both keys, as for the
    # vanilla doubles: with no correct_tools list CraftEngine judges the tool only
    # through its vanilla tool component (BlockStateUtils.isCorrectTool).
    if requires_tool and block.base and block.base.lower() in requires_tool:
        settings["require_correct_tools"] = True
        settings["respect_tool_component"] = True
    # Tags come from the mod's own tag files (see build_pack). The guess that used
    # to live here gave the snow brick wall, slab and stairs pickaxe instead of the
    # shovel the mod declares.
    if block.extra_settings.get("push_reaction"):
        settings["push_reaction"] = block.extra_settings["push_reaction"]
    if block.family == "pane":
        settings["can_occlude"] = False
        settings["is_suffocating"] = False
        settings["is_view_blocking"] = False
    return settings


# --------------------------------------------------------------------------- #
# emission
# --------------------------------------------------------------------------- #

@dataclasses.dataclass
class Build:
    """Everything the build produces, held in memory until validation passes."""

    files: dict[pathlib.Path, str] = dataclasses.field(default_factory=dict)
    generated: dict[str, dict] = dataclasses.field(default_factory=dict)
    copies: list[tuple[pathlib.Path, pathlib.Path]] = dataclasses.field(default_factory=list)
    #: destination-relative directories to skip when applying a copy, for trees the
    #: generator also writes itself
    copy_ignores: dict[pathlib.Path, set[str]] = dataclasses.field(default_factory=dict)
    stats: dict = dataclasses.field(default_factory=dict)

    def text(self, rel: str, body: str) -> None:
        self.files[pathlib.Path(rel)] = body

    def json(self, rel: str, data) -> None:
        self.text(rel, json.dumps(data, indent=2, ensure_ascii=False) + "\n")


def source_texture(block: "ir.ContentBlock", assets: pathlib.Path) -> str:
    """The texture a served block is actually drawn with.

    Read out of the block model the mod ships, not derived from the block id. The two
    differ and the difference is not guessable: ``andesite_brick_slab`` is textured
    from ``andesite_bricks`` and ``mossy_brick_stairs`` from ``bricks``, because a
    slab or a stair is drawn with its parent material. A name-based guess points at a
    texture that was never emitted and the pack gate rejects the build.

    The mod's models are copied into the pack verbatim rather than generated here, so
    this reads them off disk. Slots that are relative (``#side``) and vanilla parents
    are skipped; a block modelled entirely that way is a build error rather than a
    silent guess.
    """
    models_root = assets
    for appearance in block.appearances:
        path = appearance.model.path
        if not path or ":" not in path:
            continue
        # A model reference is `namespace:block/name`, which is
        # assets/<namespace>/models/block/name.json. The namespace is the assets
        # subdirectory, not part of the path after `models/`.
        namespace, _, rest = path.partition(":")
        model_file = models_root / namespace / "models" / f"{rest}.json"
        if not model_file.is_file():
            continue
        try:
            model = json.loads(model_file.read_text())
        except (OSError, json.JSONDecodeError) as exc:
            raise BuildError(f"{block.id}: cannot read {model_file}: {exc}") from exc
        for value in (model.get("textures") or {}).values():
            if isinstance(value, str) and value and not value.startswith("#"):
                return value
    raise BuildError(
        f"{block.id} has no texture to build a vertical slab from. A vertical slab is "
        "the same block stood on edge, so it reuses the block's own texture; this one "
        "is modelled entirely from texture slots or vanilla parents.")


def vertical_slab_source(base: str, mod_recipes: dict[str, dict],
                         allocation: ir.Allocation) -> tuple[str | None, str]:
    """The full block a vertical slab of ``base`` is crafted from, or why it is unclear.

    Taken from the mod's own data rather than guessed from names (``andesite_brick`` ->
    ``andesite_bricks``, but ``polished_calcite`` -> ``polished_calcite``): the mod
    crafts the material's slab and stairs from exactly one block, and that block is the
    vertical slab's ingredient too. Clean means the slab and stairs recipes that exist
    agree on a single block, and that block is something this build serves (or a
    vanilla item).
    """
    sources: dict[str, set[str]] = {}
    for family in ("slab", "stairs"):
        recipe = mod_recipes.get(f"{MOD_ID}:{base}_{family}")
        if not recipe or recipe.get("type") != "minecraft:crafting_shaped":
            continue
        items: set[str] = set()
        for value in recipe.get("key", {}).values():
            for entry in (value if isinstance(value, list) else [value]):
                items.add(entry["item"] if isinstance(entry, dict) else str(entry))
        sources[family] = items
    found = set().union(*sources.values()) if sources else set()
    if not sources:
        # The terracotta and concrete variants ship no recipes in the mod at all; their
        # material is the vanilla block of the same name (variant_recipes adds theirs).
        if base == "terracotta" or base.endswith(("_terracotta", "_concrete")):
            return f"minecraft:{base}", ""
        return None, f"{base}: the mod has no shaped slab or stairs recipe to take it from"
    if len(found) != 1:
        return None, (f"{base}: the slab/stairs recipes use "
                      f"{', '.join(sorted(found))}, not one block")
    block = next(iter(found))
    if block.startswith(f"{MOD_ID}:") and block not in allocation.assigned:
        return None, f"{base}: its block {block} is not served on this server"
    return block, ""


def render_vertical_slabs(content: ir.Content, allocation: ir.Allocation,
                          cfg: "Config",
                          generated_models: dict[str, dict],
                          assets: pathlib.Path,
                          mod_recipes: dict[str, dict],
                          mc_version: str,
                          fallback: set[str] = frozenset(),
                          mining: dict | None = None) -> tuple[dict, dict, dict, dict, dict]:
    """Vertical slabs as CraftEngine furniture: display entity plus a thin collider.

    Returns (furniture, items, item_models, recipes, doubles). Empty when nothing is
    derivable, so a server that enables the feature but serves no slabs gets no
    empty file.

    A vertical slab is a standing half-block plane. Nothing vanilla has that
    collision except a bell (measured: `/cmbvshape`, see DESIGN.md 6.1), and a bell
    caps the family at four states that have to be stolen from real bells. So the
    geometry is a display entity instead and the collision is CraftEngine's own
    shulker hitbox.

    Only slabs and stairs the build already serves become vertical slabs, and only
    **one per base material**. A vertical slab is the enabled block stood on edge,
    so `calcite_brick_slab` and `calcite_brick_stairs` are the same plate drawn from
    the same texture: emitting both produced two indistinguishable items in the
    browser and two ids competing for one name. Sorted iteration decides which
    block wins, so the choice is deterministic rather than config-dependent.

    The variant is named `wall`, not `north`/`east`/`south`/`west`. CraftEngine
    resolves a placement variant by *anchor type*, from the face that was clicked
    (`FurnitureItemBehavior`: EAST/WEST/NORTH/SOUTH -> WALL, UP -> GROUND,
    DOWN -> CEILING), and fails the interaction outright when that variant does not
    exist. So four compass-named variants made the item unplaceable on every face.
    One `wall` variant plus a rule is both correct and less to get wrong.

    Facing is handled by the rule (`rotation: four`) and the yaw CraftEngine derives
    from the clicked face, so the model needs no baked-in facing and one model
    serves every side.
    """
    def definitions(vslab_name: str, vslab_id: str, display: str,
                    sound_group: str) -> None:
        """Furniture + item for one vertical slab; shared by CMB and vanilla."""
        element = {
            "item": vslab_id,
            "display_transform": "none",
            "billboard": "fixed",
            "position": "0,0,0.5",
            # 180 degrees: the model's plate spans z 0..8, so without this the display
            # entity presents its narrow 8-wide edge to the wall instead of the 16-wide
            # face, and the texture reads as stretched vertical bands.
            "rotation": 180,
            "translation": "0,0,0",
            "shadow_radius": 0,
            "shadow_strength": 0.3,
        }

        def shulker_box(position: str) -> dict:
            return {
                "position": position,
                "type": "shulker",
                "peek": 0,
                "scale": 0.5,
                "blocks_building": True,
                "interactive": True,
                "interaction_entity": True,
            }

        # The wall anchor sits at the wall face, and the ground anchor at the cell
        # centre, so the two need different y signs: on a wall +y runs down the face,
        # on the ground it runs up. x and z are shared.
        wall_hitboxes = [shulker_box(p) for p in (
            "0.25,0.0,0.25", "-0.25,0.0,0.25",
            "0.25,-0.5,0.25", "-0.25,-0.5,0.25",
        )]
        # Ground is the wall layout turned 180 degrees about the cell centre, so its
        # boxes sit in the other half: z offset -0.25 rather than +0.25 (x is
        # symmetric). That keeps the collision under the model when the model's
        # rotation below is 180.
        ground_hitboxes = [shulker_box(p) for p in (
            "0.25,0.0,-0.25", "-0.25,0.0,-0.25",
            "0.25,0.5,-0.25", "-0.25,0.5,-0.25",
        )]

        furniture[vslab_id] = {
            "data": {"item_name": f"<!i><white>{display}"},
            "model": {"path": f"{MOD_ID}:item/{vslab_name}"},
            "settings": {
                "item": vslab_id,
                "hit_times": 1,
                "sounds": {
                    "break": f"minecraft:block.{sound_group}.break",
                    "place": f"minecraft:block.{sound_group}.place",
                    "hit": f"minecraft:block.{sound_group}.hit",
                },
            },
            "variants": {
                # Ground: the plate stands on the floor, back side away from the
                # player (measured in game: at rotation 0 it faced the player).
                #
                # position y 0.5: a display entity draws its model centred on its
                # position, and the ground anchor is the cell's floor, so at y 0 half
                # the plate was underground. The wall anchor is at mid-height, which
                # is why the wall element needs no lift.
                #
                # rotation 180 turns the model; the hitboxes above are turned with it.
                "ground": {
                    "elements": [dict(element, position="0,0.5,0", rotation=180)],
                    "hitboxes": ground_hitboxes,
                    "loot_spawn_offset": "0,0,0",
                },
                "wall": {
                    "elements": [element],
                    # Anchor box, hand-placed to fit the plate. The grid below is tiled
                    # from this one, so if the plate's geometry ever changes this is the
                    # only number that has to be re-measured.
                    #
                    # A shulker hitbox is scaled by the generic `scale` attribute, so
                    # `scale: 0.5` is a cube with no per-axis control - one box can never
                    # be the 1 x 1 x 0.5 plate on its own. Tiling four of them is what
                    # fills it.
                    #
                    # Span arithmetic, from ShulkerFurnitureHitboxConfig.createAABB. With
                    # `peek: 0` the peek terms drop out and the box is NOT centred on its
                    # offset - y starts at the offset and extends `scale`:
                    #
                    #   x: offset.x + [-scale/2, +scale/2]
                    #   y: offset.y + [0,          +scale]
                    #   z: -offset.z + [-scale/2, +scale/2]
                    #
                    # so at scale 0.5 each box spans 0.5 per side.
                    #
                    # `blocks_building` must stay in bounds: a box reaching outside the
                    # cell with this true makes CraftEngine refuse the placement outright
                    # rather than place it with clipped collision.
                    "hitboxes": wall_hitboxes,
                    "loot_spawn_offset": "0.5,0,0",
                },
            },
            "loot": {"template": "default:loot_table/furniture",
                     "arguments": {"item": vslab_id}},
        }

        items[vslab_id] = {
            "material": "nether_brick",
            "model": {"path": f"{MOD_ID}:item/{vslab_name}"},
            "data": {"item_name": f"<!i><white>{display}"},
            "behavior": {
                "type": "furniture_item",
                "furniture": vslab_id,
                # Required for placement to resolve at all: without a `wall` rule the
                # anchor lookup in FurnitureItemBehavior still finds the variant, but
                # `rotation: four` is what lets the yaw follow the clicked face.
                "rules": {
                    "wall": {"rotation": "four", "alignment": "center"},
                    "ground": {"rotation": "four", "alignment": "center"},
                },
            },
        }


    def horizontal_stairs(base: str, textures, source, sound_group: str) -> None:
        """A stair laid on its side, for corners of vertical slab walls.

        A stair's L profile, turned so the L is in plan view and runs the full height:
        a vertical slab plus one more quarter column, three of the cell's four
        quadrants. Two vertical slab walls meeting at an inside corner close on it.

        Only a `ground` variant is ever placed. The plugin
        (VerticalSlabListener.onAttemptPlace) cancels CraftEngine's own placement and
        places it at the cell's floor centre, with a yaw chosen from the clicked face,
        which half of it was hit, and any vertical slab walls next to it. The `wall`
        and `ceiling` variants exist only because CraftEngine refuses a click on a
        face whose anchor has no variant, before the plugin ever hears of it.

        The geometry is authored once, in hitbox config space: the plate at z -0.25
        (as the vertical slab's ground variant) and the extra quadrant at
        x +0.25, z +0.25. The model mirrors that (horizontal_stairs_model), and the
        plugin's yaw table follows from CraftEngine's rotateHitboxOffset.
        """
        name = f"{base}_horizontal_stairs"
        stairs_id = f"{MOD_ID}:{name}"
        display = display_name(f"{base.replace('_', ' ').title()} Horizontal Stairs")
        element = {
            "item": stairs_id, "display_transform": "none", "billboard": "fixed",
            "position": "0,0.5,0", "rotation": 180, "translation": "0,0,0",
            "shadow_radius": 0, "shadow_strength": 0.3,
        }

        def box(position: str, blocks_building: bool) -> dict:
            return {"position": position, "type": "shulker", "peek": 0, "scale": 0.5,
                    "blocks_building": blocks_building, "interactive": True,
                    "interaction_entity": True}

        quarters = ("0.25,{y},-0.25", "-0.25,{y},-0.25", "0.25,{y},0.25")
        hitboxes = [box(q.format(y=y), True) for q in quarters for y in ("0.0", "0.5")]
        # The wall and ceiling variants are never placed, but CraftEngine collision-
        # tests the clicked variant before the plugin's event fires. Their anchors sit
        # on the clicked face, so these boxes would overlap the clicked block and fail
        # every click. Non-blocking boxes are skipped by that test; the plugin checks
        # the real target cell itself.
        unplaced = [box(q.format(y=y), False) for q in quarters for y in ("0.0", "0.5")]
        furniture[stairs_id] = {
            "data": {"item_name": f"<!i><white>{display}"},
            "model": {"path": f"{MOD_ID}:item/{name}"},
            "settings": {
                "item": stairs_id,
                "hit_times": 1,
                "sounds": {event: f"minecraft:block.{sound_group}.{event}"
                           for event in ("break", "place", "hit")},
            },
            "variants": {
                "ground": {"elements": [element], "hitboxes": hitboxes,
                           "loot_spawn_offset": "0,0,0"},
                "wall": {"elements": [element], "hitboxes": unplaced,
                         "loot_spawn_offset": "0,0,0"},
                "ceiling": {"elements": [element], "hitboxes": unplaced,
                            "loot_spawn_offset": "0,0,0"},
            },
            "loot": {"template": "default:loot_table/furniture",
                     "arguments": {"item": stairs_id}},
        }
        items[stairs_id] = {
            "material": "nether_brick",
            "model": {"path": f"{MOD_ID}:item/{name}"},
            "data": {"item_name": f"<!i><white>{display}"},
            "behavior": {
                "type": "furniture_item",
                "furniture": stairs_id,
                "rules": {
                    "wall": {"rotation": "four", "alignment": "center"},
                    "ground": {"rotation": "four", "alignment": "center"},
                    "ceiling": {"rotation": "four", "alignment": "center"},
                },
            },
        }
        generated_models[name] = horizontal_stairs_model(textures)
        vslab_item_models[name] = {"parent": f"{MOD_ID}:block/{name}"}
        # Vanilla's stair recipe upside down, from the full block: six make four, the
        # same yield as stairs. Upside down so it never collides with the stair
        # recipe (a shaped recipe matches its mirror image, not its flip).
        if source:
            recipes[stairs_id] = {
                "type": "shaped",
                "category": "building",
                "pattern": ["###", "## ", "#  "],
                "ingredients": {"#": source},
                "result": {"id": stairs_id, "count": 4},
            }

    def double_block(vslab_id: str, model: str, settings: dict, sound_group: str) -> None:
        """Two vertical slabs of one material in one cell: the full block, dropping two.

        The plugin (VerticalSlabListener) turns a vertical slab into this when a
        player right-clicks it with the same vertical slab. It is a real block,
        not furniture, so collision, water, pistons and explosions are vanilla's.

        `auto_state: solid` rather than a state from our allocator: CraftEngine only
        offers states a block_state_mapping already hides (note blocks, mushroom
        blocks), so it never shows on a real vanilla block, and keeping these out of
        the allocator leaves the scarce carrier budget untouched. Each costs one
        CraftEngine internal state. No item: it is only ever made from two slabs, and
        breaking it gives those two back, like a vanilla double slab.
        """
        if cfg.vertical_slabs_doubles in ("furniture", "both"):
            entry, more_items, more_models = double_furniture(
                vslab_id, furniture[vslab_id]["data"]["item_name"].removeprefix("<!i><white>"),
                model, sound_group)
            furniture[f"{vslab_id}_double"] = entry
            items.update(more_items)
            vslab_item_models.update(more_models)
            if cfg.vertical_slabs_doubles == "furniture":
                return
        doubles[f"{vslab_id}_double"] = {
            "states": {
                "properties": {},
                "appearances": {"double": {"auto_state": "solid",
                                           "model": {"path": model}}},
                "variants": {"": {"appearance": "double"}},
            },
            "settings": {
                **settings,
                "sounds": {event: f"minecraft:block.{sound_group}.{event}"
                           for event in ("break", "fall", "hit", "place", "step")},
            },
            "loot": {"pools": [{
                "rolls": 1,
                "entries": [{
                    "type": "item",
                    "item": vslab_id,
                    "functions": [{"type": "set_count", "count": 2, "add": False},
                                  {"type": "explosion_decay"}],
                }],
            }]},
        }

    vslab_disabled = set(cfg.vertical_slabs_disabled)
    stairs_disabled = set(cfg.horizontal_stairs_disabled)
    furniture: dict = {}
    items: dict = {}
    vslab_item_models: dict[str, dict] = {}
    recipes: dict = {}
    doubles: dict = {}

    # --- CMB materials -------------------------------------------------------
    # One per material: `calcite_brick_slab` and `calcite_brick_stairs` are the same
    # material, so they give one vertical slab and one horizontal stair, not two.
    seen_base: set[str] = set()
    unmapped: list[str] = []
    # A few CMB materials are vanilla materials with a vanilla slab (the mod adds
    # cut_red_sandstone_stairs and smooth_stone_stairs). When the vanilla source is on,
    # it makes those pieces, from vanilla's exact textures and double-slab model.
    vanilla_bases = {slab.removesuffix("_slab") for slab in (vanilla_slabs.load(mc_version) or {})}
    cmb_on = cfg.vertical_slabs_cmb or cfg.horizontal_stairs_cmb
    for block_id in (sorted(content.blocks) if cmb_on else []):
        block = content.blocks[block_id]
        if block.family not in ("slab", "stairs"):
            continue
        # Served as a block, or as furniture by furniture-fallback: either way the
        # material is on this server, so its vertical slab and stair are too.
        if block_id.startswith("minecraft:") or (block_id not in allocation.assigned
                                                 and block_id not in fallback):
            continue

        name = block_id.split(":")[-1]
        base = name
        for suffix in ("_slab", "_stairs"):
            if base.endswith(suffix):
                base = base[: -len(suffix)]
                break
        if base in seen_base:
            # Already emitted for the sibling family: same material, same pieces.
            continue
        seen_base.add(base)
        want_slab = (cfg.vertical_slabs_cmb and base not in vslab_disabled
                     and not (cfg.vertical_slabs_vanilla and base in vanilla_bases))
        want_stairs = (cfg.horizontal_stairs_cmb and base not in stairs_disabled
                       and not (cfg.horizontal_stairs_vanilla and base in vanilla_bases))
        if not (want_slab or want_stairs):
            continue

        # Both pieces are crafted from the material's full block. It comes from the
        # mod's own slab and stairs recipes (vertical_slab_source); one that does not
        # map cleanly stops the build rather than guessing.
        source_block, problem = vertical_slab_source(base, mod_recipes, allocation)
        if source_block is None:
            unmapped.append(problem)
            continue
        texture = source_texture(block, assets)

        if want_slab:
            vslab_name = f"{base}_vertical"
            vslab_id = f"{MOD_ID}:{vslab_name}"
            display = display_name(f"{base.replace('_', ' ').title()} Vertical Slab")

            # The plate is authored standing up in the model (see vertical_slab_model),
            # so the display entity only has to push it off the wall it was placed
            # against and let the rule's yaw aim it.
            definitions(vslab_name, vslab_id, display, "stone")

            generated_models[vslab_name] = vertical_slab_model(texture)
            # The furniture item's held model. A separate file, because the gate
            # resolves every `model:` reference and a display entity needs the
            # block-space model while a held item needs one under models/item/.
            vslab_item_models[vslab_name] = {
                "parent": f"{MOD_ID}:block/{vslab_name}",
            }

            # Three of the full block stacked, yielding six - vanilla's
            # three-blocks-to-six-slabs, stood up. Vertical only: a horizontal three
            # does not describe a standing plate, and offering it just puts a second
            # way to craft the same item into the recipe book. A one-wide three-tall
            # pattern matches in any of the three columns.
            recipes[vslab_id] = {
                "type": "shaped",
                "category": "building",
                "pattern": ["#", "#", "#"],
                "ingredients": {"#": source_block},
                "result": {"id": vslab_id, "count": 6},
            }

            # Doubled, it is that same full block: drawn with its model, mined like it.
            # A vanilla full block (calcite_stairs is made from minecraft:calcite) is
            # drawn with vanilla's model and mined like the CMB piece itself.
            source = content.blocks.get(source_block)
            if source is not None and source.appearances:
                double_block(vslab_id, source.appearances[0].model.path,
                             {k: source.settings[k]
                              for k in ("hardness", "resistance", "tags")
                              if k in source.settings},
                             "stone")
            elif source_block.startswith("minecraft:"):
                settings = {k: block.settings[k] for k in ("hardness", "resistance")
                            if k in block.settings}
                if "hardness" in settings:
                    settings.setdefault("resistance", settings["hardness"])
                mining_tags = [t for t in block.settings.get("tags", [])
                               if t.startswith("minecraft:mineable/")]
                if mining_tags:
                    settings["tags"] = mining_tags
                double_block(vslab_id, source_block.replace(":", ":block/", 1),
                             settings, "stone")
        if want_stairs:
            horizontal_stairs(base, texture, source_block, "stone")
        if mining is not None:
            for piece in (f"{base}_vertical", f"{base}_vertical_double",
                          f"{base}_horizontal_stairs"):
                mining[f"{MOD_ID}:{piece}"] = cmb_mining(block)

    # --- vanilla materials ---------------------------------------------------
    # Every vanilla slab's material. Costs nothing scarce: furniture uses no carrier
    # and no CraftEngine internal state, and every texture is a vanilla one the client
    # already has (the models only reference minecraft:block/...). Source data - each
    # slab's textures, recipe block, double model and mining properties - comes from
    # fixtures/vanilla_slabs_<ver>.json (tools/vanilla_slabs.py).
    vanilla_on = cfg.vertical_slabs_vanilla or cfg.horizontal_stairs_vanilla
    if vanilla_on:
        vanilla = vanilla_slabs.load(mc_version)
        if vanilla is None:
            raise BuildError(f"vertical-slabs.vanilla or horizontal-stairs.vanilla is "
                             f"on, but there is no fixtures/vanilla_slabs_{mc_version}"
                             f".json for this version")
        def look(data: dict) -> tuple[str, str, str]:
            return data["top"], data["side"], data["bottom"]

        craftable_looks = {look(d) for d in vanilla.values() if d["source"]}
        for slab, data in sorted(vanilla.items()):
            base = slab.removesuffix("_slab")
            if not data["source"] and look(data) in craftable_looks:
                # Uncraftable and indistinguishable from a craftable one (petrified
                # oak is oak): a second, unobtainable copy of the same item adds
                # nothing but clutter in the item browser.
                continue
            textures = {"top": data["top"], "side": data["side"], "bottom": data["bottom"]}
            if cfg.vertical_slabs_vanilla and base not in vslab_disabled:
                vslab_name = f"{base}_vertical"
                vslab_id = f"{MOD_ID}:{vslab_name}"
                if vslab_id in furniture:
                    raise BuildError(f"vertical slab {vslab_id} would be made twice: "
                                     f"from a CMB material and from vanilla {slab}")
                display = display_name(f"{base.replace('_', ' ').title()} Vertical Slab")
                definitions(vslab_name, vslab_id, display, data["sound"])
                generated_models[vslab_name] = vertical_slab_model(textures)
                vslab_item_models[vslab_name] = {"parent": f"{MOD_ID}:block/{vslab_name}"}
                # Mirrors vanilla's own slab recipe, alternatives included (sandstone
                # slabs take sandstone or chiseled sandstone). No recipe where vanilla
                # has none.
                if data["source"]:
                    recipes[vslab_id] = {
                        "type": "shaped",
                        "category": "building",
                        "pattern": ["#", "#", "#"],
                        "ingredients": {"#": data["source"]},
                        "result": {"id": vslab_id, "count": 6},
                    }
                # Doubled: vanilla's own double slab model (oak -> oak_planks, smooth
                # stone -> its seamed double), mined exactly like the vanilla slab.
                settings: dict = {"hardness": data["hardness"],
                                  "resistance": data["resistance"]}
                if data["tools"]:
                    settings["tags"] = data["tools"]
                if data["requires_tool"]:
                    # Both, or nothing ever drops: with no `correct_tools` list,
                    # CraftEngine accepts a tool only through its vanilla tool
                    # component, and only when respect_tool_component is on
                    # (BlockStateUtils.isCorrectTool). The component then judges by
                    # the mineable tag above, as vanilla does.
                    settings["require_correct_tools"] = True
                    settings["respect_tool_component"] = True
                double_block(vslab_id, data["double"], settings, data["sound"])
            if mining is not None:
                # Vanilla's own slab: hardness, tool and correct-tool rule exactly.
                for piece in (f"{base}_vertical", f"{base}_vertical_double",
                              f"{base}_horizontal_stairs"):
                    mining[f"{MOD_ID}:{piece}"] = {"reference": f"minecraft:{slab}",
                                                   "hardness": data["hardness"]}
            if cfg.horizontal_stairs_vanilla and base not in stairs_disabled:
                if f"{MOD_ID}:{base}_horizontal_stairs" in furniture:
                    raise BuildError(f"horizontal stairs for {base} would be made twice: "
                                     f"from a CMB material and from vanilla {slab}")
                horizontal_stairs(base, textures, data["source"], data["sound"])
            # Wooden pieces burn for half a plank (150 ticks), as a vanilla wooden
            # slab does. Crimson and warped are nether wood, which doesn't burn.
            if data["sound"] in WOODEN_SOUNDS:
                for piece in (f"{base}_vertical", f"{base}_horizontal_stairs"):
                    if f"{MOD_ID}:{piece}" in items:
                        items[f"{MOD_ID}:{piece}"].setdefault("settings", {})["fuel_time"] = 150

    # A disabled name neither source could ever offer is a typo; say so instead of
    # silently keeping the piece it was meant to remove.
    offered = {block_id.split(":")[-1].removesuffix("_slab").removesuffix("_stairs")
               for block_id, block in content.blocks.items()
               if block.family in ("slab", "stairs")}
    offered |= {slab.removesuffix("_slab")
                for slab in (vanilla_slabs.load(mc_version) or {})}
    for key, names in (("vertical-slabs", vslab_disabled),
                       ("horizontal-stairs", stairs_disabled)):
        unknown = sorted(names - offered)
        if unknown:
            raise BuildError(f"features.{key}.disabled names materials that have no "
                             f"slab or stairs: " + ", ".join(unknown))

    if unmapped:
        raise BuildError("vertical slab / horizontal stair recipes: these materials do "
                         "not map cleanly to one full block:\n  " + "\n  ".join(unmapped))
    return furniture, items, vslab_item_models, recipes, doubles


#: Vanilla sound groups of the wood that burns as fuel (not nether_wood).
WOODEN_SOUNDS = ("wood", "cherry_wood", "bamboo_wood")

#: A CMB block's mining tag -> a vanilla block mined the same way, for tool speed and
#: the correct-tool rule. Hardness comes from the CMB block itself.
MINING_REFERENCES = {
    "minecraft:mineable/pickaxe": "minecraft:stone",
    "minecraft:mineable/axe": "minecraft:oak_planks",
    "minecraft:mineable/shovel": "minecraft:dirt",
    "minecraft:mineable/hoe": "minecraft:hay_block",
}


def cmb_mining(block: "ir.ContentBlock") -> dict:
    """How a CMB material's furniture pieces mine: a reference block and hardness."""
    if "glass" in block.id:
        # Glass breaks fast by hand and needs no tool; tagged nothing, it would fall to
        # the stone default below.
        return {"reference": "minecraft:glass_pane", "hardness": block.settings.get("hardness", 0.3)}
    reference = next((MINING_REFERENCES[t] for t in block.settings.get("tags", [])
                      if t in MINING_REFERENCES), "minecraft:stone")
    return {"reference": reference, "hardness": block.settings.get("hardness", 1.5)}


def double_furniture(piece_id: str, display_name: str, model: str,
                     sound_group: str) -> tuple[dict, dict, dict]:
    """Two slabs in one cell as furniture: `vertical-slabs.doubles: furniture`.

    The alternative to the auto-state block. A block is the better block - mining
    time and cracks, pistons, light - but a vanilla client only knows the vanilla
    state it is drawn on, so Jade names a mushroom stem. Furniture is named by the
    item its display shows, so Jade names this after the slab, the way vanilla's
    double slab is named after its slab.

    Returns (furniture entry, items, item models) for `<piece>_double`. A full cube
    of eight octant colliders; never placed by an item, only by the plugin.
    """
    name = piece_id.split(":")[-1]
    display = f"{name}_double__full"
    display_id = f"{MOD_ID}:{display}"
    boxes = [{"position": f"{x},{y},{z}", "type": "shulker", "peek": 0, "scale": 0.5,
              "blocks_building": True, "interactive": True, "interaction_entity": True}
             for x in (-0.25, 0.25) for y in (0.0, 0.5) for z in (-0.25, 0.25)]
    entry = {
        "data": {"item_name": f"<!i><white>{display_name}"},
        "model": {"path": f"{MOD_ID}:item/{display}"},
        "settings": {
            # Pick-block gives the slab, as it does on a vanilla double slab.
            "item": piece_id,
            "hit_times": 1,
            "sounds": {event: f"minecraft:block.{sound_group}.{event}"
                       for event in ("break", "place", "hit")},
        },
        "variants": {"ground": {
            "elements": [{
                "item": display_id, "display_transform": "none", "billboard": "fixed",
                "position": "0,0.5,0", "rotation": 180, "translation": "0,0,0",
                "shadow_radius": 0, "shadow_strength": 0.3,
            }],
            "hitboxes": boxes,
            "loot_spawn_offset": "0,0.5,0",
        }},
        "loot": {"pools": [{"rolls": 1, "entries": [{
            "type": "item", "item": piece_id,
            "functions": [{"type": "set_count", "count": 2, "add": False}],
        }]}]},
    }
    items = {display_id: {"material": "nether_brick",
                          "model": {"path": f"{MOD_ID}:item/{display}"},
                          "data": {"item_name": f"<!i><white>{display_name}"}}}
    return entry, items, {display: {"parent": model}}


def display_name(text: str) -> str:
    """A player-facing name as this port spells it: the mod's blue nether bricks are
    warped nether bricks here. Display only - ids stay blue_nether_*, so worlds that
    already hold them keep them."""
    return text.replace("Blue Nether", "Warped Nether").replace("blue nether", "warped nether")


def replaced_recipe(recipe_id: str) -> str | None:
    """Why this port leaves out one of the mod's recipes, or None to keep it."""
    return REPLACED_RECIPES.get(recipe_id)


#: Mod recipes this port replaces, and why. The reason shows in the skipped list.
REPLACED_RECIPES = {
    f"{MOD_ID}:blue_nether_brick": "replaced: 2 nether sprouts + 2 nether bricks give 2 "
                                   "warped nether bricks, not 4 (nether_brick_recipes)",
    f"{MOD_ID}:red_nether_brick": "replaced: crimson roots, not nether wart, turn nether "
                                  "bricks red (nether_brick_recipes)",
}


def nether_brick_recipes() -> dict:
    """The coloured nether brick items, as this port makes them.

    Nether brick comes from smelting netherrack (vanilla). It is turned warped with
    nether sprouts or red with crimson roots - two plants and two bricks give two
    coloured bricks, so bricks are converted, never multiplied (the mod's recipes gave
    four) - or smelted straight from the matching nylium. That makes every nether
    brick variant obtainable from a nether visit.
    """
    def dye(plant: str, result: str) -> dict:
        return {"type": "shaped", "category": "building", "pattern": ["AT", "TA"],
                "ingredients": {"A": plant, "T": "minecraft:nether_brick"},
                "result": {"id": result, "count": 2}}

    def smelt(nylium: str, result: str) -> dict:
        return {"type": "smelting", "category": "misc", "experience": 0.1, "time": 200,
                "ingredient": nylium, "result": {"id": result, "count": 1}}

    warped, red = f"{MOD_ID}:blue_nether_brick", f"{MOD_ID}:red_nether_brick"
    return {
        f"{MOD_ID}:blue_nether_brick_from_sprouts": dye("minecraft:nether_sprouts", warped),
        f"{MOD_ID}:blue_nether_brick_from_smelting": smelt("minecraft:warped_nylium", warped),
        f"{MOD_ID}:red_nether_brick_from_roots": dye("minecraft:crimson_roots", red),
        f"{MOD_ID}:red_nether_brick_from_smelting": smelt("minecraft:crimson_nylium", red),
    }


#: The warped nether wart costs no CraftEngine internal state: it is furniture.
WART_STATES = 0


def render_warped_nether_wart() -> tuple[dict, dict, dict, dict]:
    """The warped nether wart: this port's own addition, not in the upstream mod.

    Returns (furniture, items, block models, recipes). Furniture, as Allium Harvest
    draws its crops: an item display per growth stage and a walk-through interaction
    hitbox, so it needs no block state at all - no tripwire or sugar cane carrier.
    The plugin (NetherWartCrops) plants it on soul sand, grows it at vanilla nether
    wart's average rate, pops it when its soil goes, and drops 1, or 2-4 (+fortune)
    when ripe. Four ages drawn as three stages, as vanilla. Nine make vanilla's
    warped_wart_block. Coloured nether brick recipes are in nether_brick_recipes.

    Textures are the recoloured vanilla set in warped_netherwart_art/warped
    (tools/recolor_wart.py).
    """
    wart_id = f"{MOD_ID}:warped_nether_wart"
    stages = {0: "stage0", 1: "stage1", 2: "stage1", 3: "stage2"}
    # Vanilla nether wart's outline grows with it: 4, 8, 12 and 14 pixels tall.
    heights = {0: 0.25, 1: 0.5, 2: 0.75, 3: 0.875}
    models = {f"warped_nether_wart_{stage}": {
        "parent": "minecraft:block/crop",
        "textures": {"crop": f"{MOD_ID}:block/warped_nether_wart_{stage}"},
    } for stage in set(stages.values())}
    items: dict = {}
    for stage in sorted(set(stages.values())):
        items[f"{MOD_ID}:warped_nether_wart__{stage}"] = {
            "material": "nether_brick",
            "model": {"path": f"{MOD_ID}:block/warped_nether_wart_{stage}"},
            "data": {"item_name": "<!i><white>Warped Nether Wart"},
        }

    def variant(age: int, blocks: bool) -> dict:
        return {
            "elements": [{"item": f"{MOD_ID}:warped_nether_wart__{stages[age]}",
                          "position": "0,0.5,0", "rotation": 180, "shadow_strength": 0}],
            "hitboxes": [{"type": "interaction", "position": "0,0,0", "width": 0.875,
                          "height": heights[age], "blocks_building": blocks}],
            "loot_spawn_offset": "0,0.25,0",
        }

    furniture = {wart_id: {
        "data": {"item_name": "<!i><white>Warped Nether Wart"},
        "model": {"path": f"{MOD_ID}:item/warped_nether_wart"},
        "settings": {
            "item": wart_id,
            "hit_times": 1,
            "sounds": {"break": "minecraft:block.nether_wart.break",
                       "place": "minecraft:item.nether_wart.plant",
                       "hit": "minecraft:block.stone.hit"},
        },
        # `ground` is only what CraftEngine needs to accept a click on a block's top;
        # the plugin cancels that and places age_0 itself. No wall or ceiling: a wart
        # is only ever planted on top of soul sand.
        "variants": {"ground": variant(0, False),
                     **{f"age_{age}": variant(age, True) for age in range(4)}},
    }}
    items[wart_id] = {
        "material": "nether_brick",
        "model": {"path": f"{MOD_ID}:item/warped_nether_wart"},
        "data": {"item_name": "<!i><white>Warped Nether Wart"},
        "behavior": {"type": "furniture_item", "furniture": wart_id,
                     "rules": {"ground": {"rotation": "four", "alignment": "center"}}},
    }
    recipes = {
        f"{MOD_ID}:warped_wart_block": {
            "type": "shaped",
            "category": "building",
            "pattern": ["###", "###", "###"],
            "ingredients": {"#": wart_id},
            "result": {"id": "minecraft:warped_wart_block", "count": 1},
        },
    }
    return furniture, items, models, recipes


#: Vanilla's own patterns for the families, for blocks the mod ships no recipe for.
VARIANT_PATTERNS = {
    "slab": (["###"], 6),
    "stairs": (["#  ", "## ", "###"], 4),
    "wall": (["###", "###"], 6),
}


def variant_recipes(content: ir.Content, mod_recipes: dict[str, dict],
                    served: set[str]) -> dict:
    """Recipes for the terracotta and concrete slabs, stairs and walls.

    The mod defines them but ships no recipe, so even upstream they cannot be made.
    These use vanilla's pattern for the family from the vanilla block of the same
    colour - black_terracotta_slab from minecraft:black_terracotta - and only for
    blocks this build serves (as blocks or furniture).
    """
    recipes: dict = {}
    for block_id in sorted(served):
        block = content.blocks.get(block_id)
        if block is None or block.family not in VARIANT_PATTERNS or block_id in mod_recipes:
            continue
        name = block_id.split(":")[-1]
        base = name.removesuffix(f"_{block.family}")
        if block.family == "slab":
            base = name.removesuffix("_slab")
        if not (base == "terracotta" or base.endswith(("_terracotta", "_concrete"))):
            continue
        pattern, count = VARIANT_PATTERNS[block.family]
        recipes[block_id] = {
            "type": "shaped",
            "category": "building",
            "pattern": pattern,
            "ingredients": {"#": f"minecraft:{base}"},
            "result": {"id": block_id, "count": count},
        }
    return recipes


def furniture_fallback_ids(cfg: "Config", content: ir.Content, deferred: set[str]) -> set[str]:
    """The canonical ids `features.furniture-fallback` serves as furniture.

    `true` is every CMB stair, slab, wall, fence and pane the allocator deferred -
    which is all of them, as none gets a carrier. A list names just those, and only
    one that is actually deferred qualifies.
    """
    if cfg.furniture_fallback is True:
        return {block_id for block_id in deferred
                if block_id.startswith(f"{MOD_ID}:")
                and content.blocks[block_id].family in FALLBACK_FAMILIES}
    ids: set[str] = set()
    problems: list[str] = []
    for name in cfg.furniture_fallback or ():
        block_id = name if ":" in name else f"{MOD_ID}:{name}"
        block = content.blocks.get(block_id)
        if block is None or block.family not in FALLBACK_FAMILIES:
            problems.append(f"{name}: not a Cinch's Missing Blocks slab, stairs, wall or "
                            f"fence")
        elif block_id not in deferred:
            problems.append(f"{name}: already served as a block on this server")
        else:
            ids.add(block_id)
    if problems:
        raise BuildError("features.furniture-fallback:\n  " + "\n  ".join(problems))
    return ids


#: Families furniture-fallback can serve as furniture.
FALLBACK_FAMILIES = ("slab", "stairs", "wall", "fence", "pane")

#: Collider box size and layer heights. A wall is a 0.5-wide post and arms, 1.5 tall
#: like vanilla's collision; its arms come out 0.5 wide for vanilla's 0.375, a little
#: too solid but never passable (the carrier walls accept the same). A fence is 0.375
#: boxes, four layers to 1.5.
WALL_FENCE_BOXES = {"wall": (0.5, (0.0, 0.5, 1.0)), "fence": (0.375, (0.0, 0.375, 0.75, 1.125)),
                    # A pane is 1.0 tall; three overlapping 0.375 layers reach it exactly.
                    # Thicker than vanilla's 2 pixels - a thinner box leaves a gap between
                    # arm and post - but never passable.
                    "pane": (0.375, (0.0, 0.3125, 0.625))}


def wall_fence_shapes(family: str, post: str, side: str, element,
                      side_tall: str | None = None, pane: dict | None = None) -> dict:
    """Every connection state of a furniture wall or fence: variant -> (elements, boxes).

    Variants are named n?e?s?w? - 0 not connected, 1 connected, and for a wall 2 for a
    tall side (full height, when the block above covers it, as vanilla's WallSide.TALL)
    - plus p? for a wall's post, which only a straight run can drop. Tall and low
    collide the same (1.5, like vanilla's wall collision); only the model differs. The
    plugin picks the variant (VerticalSlabListener.connections). The elements are
    the mod's own post and side models, the side turned onto each connected side the
    way vanilla's blockstate turns it (+90 is north to east). Boxes are placed in
    hitbox config space at yaw 0: a model offset (mx, mz) from the cell centre is
    config (-mx, mz).

    Boxes reaching above the cell do not block building, so a block can still go on
    top - and a box outside the cell that did block building would make CraftEngine
    refuse the placement.
    """
    size, layers = WALL_FENCE_BOXES[family]
    arm = 0.5 - size / 2
    offsets = {"n": (0.0, -arm), "e": (-arm, 0.0), "s": (0.0, arm), "w": (arm, 0.0)}
    turns = {"n": 0, "e": 90, "s": 180, "w": 270}

    def boxes(x: float, z: float) -> list[dict]:
        return [{"position": f"{x},{y},{z}", "type": "shulker", "peek": 0, "scale": size,
                 "blocks_building": y + size <= 1.0 + 1e-6, "interactive": True,
                 # The shulker takes clicks itself; an interaction entity per box only
                 # where a player normally aims, to keep a crossing under 30 entities.
                 "interaction_entity": y < 1.0}
                for y in layers]

    heights = (0, 1, 2) if family == "wall" else (0, 1)
    shapes: dict = {}
    for combo in itertools.product(heights, repeat=4):
        height = dict(zip("nesw", combo))
        sides = [d for d in "nesw" if height[d]]
        straight = sorted(sides) in (["n", "s"], ["e", "w"])
        for has_post in ((True, False) if family == "wall" and straight else (True,)):
            name = "".join(f"{d}{height[d]}" for d in "nesw")
            if family == "wall":
                name += f"p{int(has_post)}"
            if family == "pane":
                # Vanilla's pane multipart: a side or a "noside" model on every side,
                # and the south/west sides use the _alt models.
                parts = {"n": ("side", 0, "noside", 0), "e": ("side", 90, "noside_alt", 0),
                         "s": ("side_alt", 0, "noside_alt", 90),
                         "w": ("side_alt", 90, "noside", 270)}
                elements = [element(post)] + [
                    element(pane[parts[d][0]], parts[d][1]) if height[d]
                    else element(pane[parts[d][2]], parts[d][3]) for d in "nesw"]
            else:
                elements = ([element(post)] if has_post else []) + \
                           [element(side_tall if height[d] == 2 else side, turns[d]) for d in sides]
            hitboxes = (boxes(0.0, 0.0) if has_post else []) + \
                       [b for d in sides for b in boxes(*offsets[d])]
            shapes[name] = (elements, hitboxes)
    return shapes


#: The furniture stair's shapes, in model space with the tall side at model north
#: (z 0..8) - facing north at yaw 0. Each is the quadrants its step layer covers;
#: the other layer is always full. "Left" is counter-clockwise of facing, as vanilla.
STAIR_STEPS: dict[str, tuple[str, ...]] = {
    "straight": ("nw", "ne"),
    "outer_left": ("nw",),
    "outer_right": ("ne",),
    "inner_left": ("nw", "ne", "sw"),
    "inner_right": ("nw", "ne", "se"),
}
#: quadrant -> (x0, z0) in model space
QUADRANTS = {"nw": (0, 0), "ne": (8, 0), "sw": (0, 8), "se": (8, 8)}


def render_furniture_fallback(content: ir.Content, allocation: ir.Allocation,
                              fallback: set[str], generated_models: dict[str, dict],
                              assets: pathlib.Path,
                              mod_recipes: dict[str, dict],
                              item_tags: dict[str, list[str]],
                              doubles_as: str = "block",
                              mining: dict | None = None) -> tuple[dict, dict, dict, dict]:
    """Deferred CMB stairs and slabs as furniture: the furniture test.

    Returns (furniture, items, item_models, doubles). Each keeps its canonical id as a
    furniture item. The plugin (VerticalSlabListener) places them - cancelling
    CraftEngine's own placement - and picks the variant: a slab's half, a stair's
    half and shape, with facing as the yaw.

    One variant per shape, and each variant shows its own hidden display item,
    because a furniture element draws an item and an item has one model. The
    `ground`, `wall` and `ceiling` variants are never placed: they exist because
    CraftEngine refuses a click on a face whose anchor has no variant, and their boxes
    are non-blocking so CraftEngine's pre-placement collision test does not run
    against the clicked block.

    Geometry is authored once in model space and turned into both the display model
    and the colliders. A model quadrant centred at (mx, mz) from the cell centre is
    hitbox config (-mx, mz) - the mapping the horizontal stair established in game.
    """
    furniture: dict = {}
    items: dict = {}
    item_models: dict = {}
    doubles: dict = {}
    templates: dict = {}

    def octant_box(quadrant: str, upper: bool, blocks_building: bool) -> dict:
        x0, z0 = QUADRANTS[quadrant]
        mx, mz = (x0 + 4) / 16 - 0.5, (z0 + 4) / 16 - 0.5
        return {"position": f"{-mx},{0.5 if upper else 0.0},{mz}", "type": "shulker",
                "peek": 0, "scale": 0.5, "blocks_building": blocks_building,
                "interactive": True, "interaction_entity": True}

    def cuboids_model(texture: str, cuboids: list[tuple[list[int], list[int]]]) -> dict:
        return {
            "parent": "minecraft:block/block",
            "textures": {"particle": texture, "all": texture},
            "elements": [{"from": a, "to": b, "faces": {
                side: {"texture": "#all"}
                for side in ("north", "south", "east", "west", "up", "down")}}
                for a, b in cuboids],
        }

    for block_id in sorted(fallback):
        block = content.blocks[block_id]
        name = block_id.split(":")[-1]
        # Walls and fences are drawn with the mod's own post and side models.
        texture = (source_texture(block, assets) if block.family in ("slab", "stairs")
                   else None)
        if mining is not None:
            mining[block_id] = mining[f"{block_id}_double"] = cmb_mining(block)
        # Each variant is drawn from a few shared pieces rather than a model of its
        # own: the client mod lists every CraftEngine item in creative, and a display
        # item per shape put ~760 of them there. A slab is its own item (the mod's
        # bottom slab model) or a top-half item; a stair is a half slab plus one to
        # three quarter blocks, one quarter model turned onto each corner.
        #
        # A numeric element `rotation` is a yaw turn in the same sense as the
        # furniture's own yaw (CraftEngine reads n as a Y rotation of -n degrees, as a
        # display entity applies its yaw), so on top of the base 180, +90 carries model
        # north to east: NW -> NE -> SE -> SW.
        def display_item(suffix: str, model: dict | str) -> str:
            display = f"{name}__{suffix}"
            display_id = f"{MOD_ID}:{display}"
            if isinstance(model, dict):
                generated_models[display] = model
                model = f"{MOD_ID}:block/{display}"
            item_models[display] = {"parent": model}
            # Named after the piece: a client reads furniture's name off the item its
            # display entity shows (Jade does), and this hidden item is that item.
            items[display_id] = {
                "material": "nether_brick",
                "model": {"path": f"{MOD_ID}:item/{display}"},
                "data": {"item_name": f"<!i><white><lang:block.{MOD_ID}.{name}>"},
            }
            return display_id

        def element(item_id: str, turn: int = 0) -> dict:
            return {"item": item_id, "display_transform": "none", "billboard": "fixed",
                    "position": "0,0.5,0", "rotation": (180 + turn) % 360, "translation": "0,0,0",
                    "shadow_radius": 0, "shadow_strength": 0.3}

        # This piece's display items, by role. The variants themselves are written
        # once per family as a CraftEngine template with these as ${arguments}: every
        # wall shares the same 92 shapes and boxes, and repeating them per material
        # made the furniture files 15 MB.
        if block.family == "slab":
            # The mod's own top-slab model where it ships one, so a slab with separate
            # top and side textures keeps them.
            top_model = assets / MOD_ID / "models" / "block" / f"{name}_top.json"
            args = {"bottom": block_id,
                    "top": display_item("top", f"{MOD_ID}:block/{name}_top" if top_model.is_file()
                                        else cuboids_model(texture, [([0, 8, 0], [16, 16, 16])]))}
        elif block.family in ("wall", "fence", "pane"):
            args = {"post": display_item("post", f"{MOD_ID}:block/{name}_post"),
                    "side": display_item("side", f"{MOD_ID}:block/{name}_side")}
            if block.family == "wall":
                args["side_tall"] = display_item("side_tall", f"{MOD_ID}:block/{name}_side_tall")
            if block.family == "pane":
                for role in ("side_alt", "noside", "noside_alt"):
                    args[role] = display_item(role, f"{MOD_ID}:block/{name}_{role}")
        else:
            args = {
                "lower": display_item("lower", cuboids_model(texture, [([0, 0, 0], [16, 8, 16])])),
                "upper": display_item("upper", cuboids_model(texture, [([0, 8, 0], [16, 16, 16])])),
                "quarter_lower": display_item("quarter_lower",
                                              cuboids_model(texture, [([0, 0, 0], [8, 8, 8])])),
                "quarter_upper": display_item("quarter_upper",
                                              cuboids_model(texture, [([0, 8, 0], [8, 16, 8])])),
            }
        template_id = f"{MOD_ID}:fallback/{block.family}"
        if template_id not in templates:
            templates[template_id] = fallback_variants(block.family, element, octant_box)
        sound = "glass" if "glass" in name else "stone"
        furniture[block_id] = {
            "data": {"item_name": f"<!i><white><lang:block.{MOD_ID}.{name}>"},
            "model": {"path": f"{MOD_ID}:item/{name}"},
            "settings": {
                "item": block_id,
                "hit_times": 1,
                "sounds": {event: f"minecraft:block.{sound}.{event}"
                           for event in ("break", "place", "hit")},
            },
            "variants": {"template": template_id, "arguments": args},
            "loot": {"template": "default:loot_table/furniture",
                     "arguments": {"item": block_id}},
        }
        items[block_id] = {
            "material": "nether_brick",
            "model": {"path": f"{MOD_ID}:item/{name}"},
            "data": {"item_name": f"<!i><white><lang:block.{MOD_ID}.{name}>"},
            "behavior": {
                "type": "furniture_item",
                "furniture": block_id,
                "rules": {anchor: {"rotation": "four", "alignment": "center"}
                          for anchor in ("ground", "wall", "ceiling")},
            },
        }
        # The mod's item tags (minecraft:slabs, minecraft:stairs) still apply: they
        # are what other recipes and vanilla mechanics key off.
        if item_tags.get(block_id):
            items[block_id]["settings"] = {"tags": item_tags[block_id]}

        # A slab doubles into its full block, like a vanilla double slab: that block's
        # look, the slab's own mining settings, and it drops the two slabs back.
        if block.family == "slab":
            base = name.removesuffix("_slab")
            source_block, problem = vertical_slab_source(base, mod_recipes, allocation)
            if source_block is None:
                raise BuildError(f"furniture-fallback {name}: {problem}")
            source = content.blocks.get(source_block)
            model = (source.appearances[0].model.path if source is not None
                     else source_block.replace(":", ":block/", 1))
            if doubles_as in ("furniture", "both"):
                entry, more_items, more_models = double_furniture(
                    block_id, f"<lang:block.{MOD_ID}.{name}>", model, "stone")
                furniture[f"{block_id}_double"] = entry
                items.update(more_items)
                item_models.update(more_models)
                if doubles_as == "furniture":
                    continue
            doubles[f"{block_id}_double"] = {
                "states": {"properties": {},
                           "appearances": {"double": {"auto_state": "solid",
                                                      "model": {"path": model}}},
                           "variants": {"": {"appearance": "double"}}},
                "settings": {
                    **{k: block.settings[k] for k in ("hardness", "resistance")
                       if k in block.settings},
                    # variant_settings leaves resistance out when it equals hardness.
                    **({"resistance": block.settings["hardness"]}
                       if "hardness" in block.settings and "resistance" not in block.settings
                       else {}),
                    # Mining tags only: a full block is not in #slabs.
                    **({"tags": tags} if (tags := [t for t in block.settings.get("tags", [])
                                                   if t.startswith("minecraft:mineable/")])
                       else {}),
                    "sounds": {event: f"minecraft:block.stone.{event}"
                               for event in ("break", "fall", "hit", "place", "step")},
                },
                "loot": {"pools": [{"rolls": 1, "entries": [{
                    "type": "item", "item": block_id,
                    "functions": [{"type": "set_count", "count": 2, "add": False},
                                  {"type": "explosion_decay"}],
                }]}]},
            }
    return furniture, items, item_models, doubles, templates


def fallback_variants(family: str, element, octant_box) -> dict:
    """Every variant of a furniture-fallback family, with ${role} for its display items.

    The family's CraftEngine template: each piece passes its own display items as
    arguments (render_furniture_fallback).
    """
    ph = lambda role: "${" + role + "}"  # noqa: E731
    shapes: dict[str, tuple[list, list]] = {}
    if family == "slab":
        shapes["bottom"] = ([element(ph("bottom"))], [(q, False) for q in QUADRANTS])
        shapes["top"] = ([element(ph("top"))], [(q, True) for q in QUADRANTS])
    elif family in ("wall", "fence", "pane"):
        shapes = wall_fence_shapes(
            family, ph("post"), ph("side"), element,
            ph("side_tall") if family == "wall" else None,
            {r: ph(r) for r in ("side", "side_alt", "noside", "noside_alt")}
            if family == "pane" else None)
    else:
        turns = {"nw": 0, "ne": 90, "se": 180, "sw": 270}
        for half in ("bottom", "top"):
            full, step = ("lower", "upper") if half == "bottom" else ("upper", "lower")
            for shape, quadrants in STAIR_STEPS.items():
                elements = [element(ph(full))]
                elements += [element(ph(f"quarter_{step}"), turns[q]) for q in quadrants]
                octants = [(q, half == "top") for q in QUADRANTS]
                octants += [(q, half == "bottom") for q in quadrants]
                shapes[f"{half}_{shape}"] = (elements, octants)

    variants: dict = {}
    for variant, (elements, octants) in shapes.items():
        variants[variant] = {
            "elements": elements,
            # A wall or fence brings its own boxes; a slab or stair is octants.
            "hitboxes": (octants if octants and isinstance(octants[0], dict)
                         else [octant_box(q, upper, True) for q, upper in octants]),
            "loot_spawn_offset": "0,0.5,0",
        }
    first = next(iter(variants.values()))
    for anchor in ("ground", "wall", "ceiling"):
        variants[anchor] = {
            "elements": first["elements"],
            "hitboxes": [dict(box, blocks_building=False) for box in first["hitboxes"]],
            "loot_spawn_offset": "0,0.5,0",
        }
    return compact_furniture({"variants": variants})["variants"]


def horizontal_stairs_model(texture) -> dict:
    """The horizontal stair: a vertical slab plate plus one quarter column, full height.

    Model space matches the vertical slab's: the plate is z 0..8. The quarter column
    is x 0..8, z 8..16, which with the furniture element's 180-degree turn lands on
    hitbox config (+0.25, +0.25), where generate_pack puts the third collider. No
    `uv`: Minecraft derives each face's UV from the element's own position, so the
    texture runs continuously across the two elements as it does across a block.
    """
    faces = texture if isinstance(texture, dict) else {
        "top": texture, "side": texture, "bottom": texture}

    def element(start: list[int], end: list[int]) -> dict:
        return {"from": start, "to": end, "faces": {
            **{side: {"texture": "#side"} for side in ("north", "south", "east", "west")},
            "up": {"texture": "#top"},
            "down": {"texture": "#bottom"},
        }}

    return {
        "parent": "minecraft:block/block",
        "textures": {"particle": faces["side"], "top": faces["top"],
                     "side": faces["side"], "bottom": faces["bottom"]},
        "elements": [element([0, 0, 0], [16, 16, 8]), element([0, 0, 8], [8, 16, 16])],
    }


def vertical_slab_model(texture) -> dict:
    """A standing slab plate, for the mod's ``cinchs_vertical`` shape.

    The mod's north-facing shape is ``createCuboidShape(0,0,0,16,16,8)``: a plate
    on the north edge, full height, half a block deep, with the two side faces and
    the top face visible. That is reproduced here as real geometry because
    ``minecraft:block/slab`` is a lying slab and a display entity draws exactly what
    the model says.

    Facing is not baked into the model either. There is one ``wall`` variant and
    CraftEngine rotates the display entity by the yaw it derives from the clicked
    face (see ``FurnitureItemBehavior``), so a single ``_vertical`` model per block
    serves every side.

    ``texture`` is the source block's own texture, read off the appearance it is
    built from rather than guessed from the id: a stair is textured from
    ``andesite_bricks``, not from ``andesite_brick_stairs``, so deriving it from the
    name would point at a texture that does not exist.
    """
    # A vanilla slab has its own top/side/bottom (sandstone, smooth stone); the plate
    # shows `side` on its faces and ends, `top` on top and `bottom` underneath. A
    # single texture means all three.
    faces = texture if isinstance(texture, dict) else {
        "top": texture, "side": texture, "bottom": texture}
    model = {
        "parent": "minecraft:block/block",
        "textures": {"particle": faces["side"], "top": faces["top"],
                     "side": faces["side"], "bottom": faces["bottom"]},
        "elements": [{
            # from/to in 0-16 model space. z 0..8 is the north half, standing up.
            "from": [0, 0, 0],
            "to": [16, 16, 8],
            "faces": {
                # A standing slab shows its two faces, its top and its two ends; the
                # bottom is against the block below it and is never visible.
                "north": {"uv": [0, 0, 16, 16], "texture": "#side"},
                "south": {"uv": [0, 0, 16, 16], "texture": "#side"},
                "east": {"uv": [0, 0, 8, 16], "texture": "#side"},
                "west": {"uv": [8, 0, 16, 16], "texture": "#side"},
                "up": {"uv": [0, 0, 16, 8], "texture": "#top"},
                # Without this the underside is simply absent from the model, so looking
                # up at a floor-standing plate shows straight through it to the sky. Same
                # 16x8 footprint as `up`: the element is 16 wide by 8 deep in the
                # horizontal plane.
                "down": {"uv": [0, 0, 16, 8], "texture": "#bottom"},
            },
        }],
    }
    return model


def render_block_file(family: str, content: ir.Content, allocation: ir.Allocation,
                      header: str | None = None,
                      build_generated: dict[str, dict] | None = None) -> str:
    """Render the CraftEngine YAML for one family, from content + allocation.

    Deliberately a pure function of the two artifacts, so the runtime can run the
    exact same code against a different allocation.
    """
    build_generated = build_generated if build_generated is not None else {}
    blocks: dict = {}
    for block_id, block in sorted(content.blocks.items()):
        if block.family != family:
            continue
        states = allocation.assigned.get(block_id)
        if states is None:
            continue
        appearances, variants = {}, {}
        for index, appearance in enumerate(block.appearances):
            names = "_".join(f"{k}{v}" for k, v in sorted(appearance.properties.items()))
            vanilla = block_id.split(":")[-1] if block_id.startswith("minecraft:") else None
            key = f"{vanilla or family}_{names}"
            baked_path = appearance.settings.get("baked_model_path")
            if baked_path:
                model_json = {"path": f"{MOD_ID}:block/{baked_path}"}
            else:
                model_json = appearance.model.to_json()
                if "models" in model_json:
                    # CraftEngine reads a random-variant model as a list directly
                    # under `model:` (parseBlockModel); a nested `models:` key is
                    # read as a single model with no path, and the block is dropped.
                    model_json = model_json["models"]
            # Cubes and pillars: CraftEngine picks the state on the server it loads on
            # (intermediate.AUTO_SOLID).
            appearances[key] = ({"auto_state": "solid", "model": model_json}
                                if ir.is_auto(states[index])
                                else {"state": states[index], "model": model_json})
            node: dict = {"appearance": key}
            if "waterlogged=true" in str(states[index]):
                node["settings"] = {"fluid_state": "none"}
            variants[appearance.key] = node
        entry: dict = {"states": {
            "properties": {p.name: p.to_json() for p in block.properties},
            "appearances": appearances,
            "variants": variants,
        }}
        if block.behaviors:
            entry["behaviors"] = block.behaviors
        settings = {k: v for k, v in block.settings.items()
                    if k not in ("converted_vanilla", "rotation_states")}
        entry["settings"] = settings
        if block.loot is not None:
            entry["loot"] = block.loot
        blocks[block_id] = entry
    return to_yaml({"blocks": blocks}, header)


def family_header(family: str) -> str | None:
    return FAMILY_HEADERS.get(family)


FAMILY_HEADERS: dict[str, str] = {}


def family_file(name: str, resolved: list[FamilyBlock], cfg: PackConfig) -> str:
    """Render one YAML file holding every block of a single family."""
    header = None
    if name == "stairs":
        header = textwrap.dedent("""\
            # Stairs deliberately omit CraftEngine's `shape` property.
            #
            # CraftEngine's `stairs_block` behaviour requires `shape`, and declaring
            # it costs 5x the states (facing 4 x half 2 x shape 5 = 40 per stair
            # instead of 8). 97 stairs at 40 states is 3880 carrier states, which
            # does not fit the stair pool. So `shape` is dropped and the companion
            # plugin's `cmb:stairs` behaviour supplies placement and outline instead.
            #
            # The cost: stair corners render as straight stairs. Collision is
            # correct. See DESIGN.md section 3.
            """)
    elif name == "wall":
        header = textwrap.dedent("""\
            # Walls model each side as a boolean rather than vanilla's
            # none/low/tall enum.
            #
            # Full fidelity would be 162 states per wall, and 108 walls would need
            # 17496 carrier states against a wall pool of 4050. Four booleans is 16
            # states per wall, which fits with room to spare. A connected side is
            # carried by a vanilla `low` side.
            #
            # The cost: a side next to a two-block-tall neighbour renders at low
            # height, and walls never grow to three blocks tall on their own (there
            # is no `up` property). See DESIGN.md section 3.
            """)
    elif name == "pillar":
        header = textwrap.dedent("""\
            # Pillars ride full-cube carriers through a 3-value `rotation`
            # property, because a vanilla log has exactly three states (`axis`) and
            # needs all of them, so it can lend nothing. The cost is that collision
            # is a full cube rather than vanilla's 2/16-inset column.
            """)

    blocks: dict = {}
    for entry in resolved:
        block = entry.block
        node: dict = {"settings": variant_settings(block, pathlib.Path())}
        behaviours = FAMILY_BEHAVIOURS.get(block.family) or []
        if behaviours:
            node["behaviors"] = behaviours
        node["states"] = {
            "properties": {p.name: p.to_yaml() for p in
                           FAMILY_PROPERTIES[block.family]},
            "appearances": {a["name"]: {"state": a["state"], "model": a["model"]}
                            for a in entry.appearances},
            "variants": {
                v["key"]: ({"appearance": v["appearance"], "settings": v["settings"]}
                           if "settings" in v else {"appearance": v["appearance"]})
                for v in entry.variants
            },
        }
        blocks[block.id] = node

    return to_yaml({"blocks": blocks}, header)


def build_content(mod_root: pathlib.Path, cfg: PackConfig,
                  mc_version: str, policy: str,
                  ce_resources: pathlib.Path | None = None) -> tuple[ir.Content, ir.Allocation, dict]:
    """Produce the canonical content definition and a preview allocation.

    The content definition is version-independent: every block the mod defines is
    present, including ones the preview allocation cannot represent. Allocation is
    a separate product so nothing version-specific is baked into content.json.
    """
    assets = mod_root / "common/src/main/resources/assets"
    templates_dir = pathlib.Path(__file__).resolve().parent / "vanilla_templates"
    templates = {}
    for name in ("template_wall_post", "template_wall_side", "template_wall_side_tall"):
        path = templates_dir / f"{name}.json"
        if not path.is_file():
            raise BuildError(f"missing wall template {path}")
        templates[name] = json.loads(path.read_text())

    parsed = modsource.parse_blocks(mod_root)

    # Vanilla has since added some of the mod's blocks under the same name - 26.3 has
    # concrete slabs and stairs. A CMB copy is then a duplicate, unless ViaBackwards
    # lets older clients in, which lack the vanilla one.
    vanilla_assets = pathlib.Path(__file__).resolve().parent / "fixtures" / \
        f"vanilla_assets_{mc_version}.json"
    vanilla_models = (set(json.loads(vanilla_assets.read_text())["models"])
                      if vanilla_assets.is_file() and not cfg.viabackwards else set())
    requires_tool = vanilla_tools.load(mc_version)

    def enabled(block: modsource.ModBlock) -> bool:
        for variant, tokens in VARIANT_TOKENS.items():
            if not cfg.blocks_enabled(variant) and any(t in block.name for t in tokens):
                return False
        return f"block/{block.name}" not in vanilla_models

    blocks: dict[str, ir.ContentBlock] = {}
    generated_models: dict[str, dict] = {}
    pool_names = {FAMILY_CARRIER_POOL.get(b.family, b.family) for b in parsed}
    pools = {fam: vc.CarrierPool.build(vc.FAMILIES[fam])
             for fam in sorted(pool_names & set(vc.FAMILIES))}

    for block in parsed:
        if not enabled(block):
            continue
        family = block.family
        properties = FAMILY_PROPERTIES[family]
        pin = FAMILY_PROJECTION_PIN.get(family, {})
        pool_spec = vc.FAMILIES.get(FAMILY_CARRIER_POOL.get(family, family))
        pool_props = (FAMILY_POOL_PROPERTIES.get(FAMILY_CARRIER_POOL.get(family, family))
                      or properties)
        blockstate = load_blockstate(assets, block.name)
        selector = blockstate_selector(family)

        wall_post, wall_sides = (wall_parts(assets, block.name)
                                 if family == "wall" else (None, {}))

        appearances: list[ir.Appearance] = []
        for state in combinations(properties):
            key = ",".join(f"{prop.name}={state[prop.name]}" for prop in properties)
            name = "_".join(f"{k}{v}" for k, v in sorted(state.items()))

            requirement: dict[str, str] | None = None
            if pool_spec is not None:
                want = {prop.name: state.get(prop.name, prop.default) for prop in pool_props}
                want.update(pin)
                reserved = {n for n, _ in pool_spec.reserve_props}
                requirement = {k: v for k, v in want.items()
                               if k in reserved and v is not None}
            else:
                requirement = None

            if family == "wall":
                model_path = f"{MOD_ID}:block/{block.name}_flat_{name}"
                chosen = [wall_post]
                for side in ("north", "east", "south", "west"):
                    if state.get(side) == "true" and side in wall_sides:
                        chosen.append(wall_sides[side])
                generated_models[f"{block.name}_flat_{name}"] = flatten_wall(
                    chosen, templates, assets)
                model = ir.ModelRef(path=model_path)
            elif family in ("cube", "pillar"):
                model = ir.ModelRef.from_json(
                    variant_node(pick_variant(blockstate or {}, state)
                                 or {"model": f"{MOD_ID}:block/{block.name}"}))
                requirement = None
            elif blockstate and "multipart" in blockstate and "variants" not in blockstate:
                # Fences and panes ship as multipart pieces (_post, _side), never as
                # one block/<name> model, so falling back to block/<name> pointed at a
                # model that does not exist - and because these appearances bind
                # *vanilla* fence and wall carrier states, some vanilla fences and
                # walls rendered as missing texture too. Bake the applicable parts
                # into a single model, as walls already do.
                baked_name = f"{block.name}_flat_{name}"
                try:
                    generated_models[baked_name] = mp.flatten(
                        # multipart's loader prefixes "assets/", so it wants the
                        # directory that *contains* assets/, not assets/ itself.
                        assets.parent, blockstate, state,
                        # Fence and pane templates live in vanilla, not the mod.
                        extra_roots=(templates_dir / mc_version, templates_dir))
                except mp.ResolutionError as exc:
                    raise BuildError(f"{block.id} {state}: {exc}") from exc
                model = ir.ModelRef(path=f"{MOD_ID}:block/{baked_name}")
            else:
                entry = pick_variant(blockstate or {}, {**(selector or {}), **state})
                if entry is None:
                    raise BuildError(f"{block.id}: no blockstate variant for {state}")
                model = ir.ModelRef.from_json(variant_node(entry))

            settings: dict = {}
            if requirement is None and family in ("cube", "pillar"):
                settings["rotation_states"] = vc.PILLAR_ROTATIONS if family == "pillar" else 1
            else:
                # Dry carriers are preferred; when the pool runs out of them the
                # carrier is waterlogged, which would otherwise hand the block a
                # fluid. Clear it rather than lose the block.
                pass

            appearances.append(ir.Appearance(
                key=key, properties=dict(sorted(state.items())), model=model,
                carrier_family=(FAMILY_CARRIER_POOL.get(family, family)
                                if requirement is not None else None),
                carrier_properties=requirement, settings=settings))

        blocks[block.id] = ir.ContentBlock(
            id=block.id, family=family,
            properties=[ir.PropertySpec(
                name=prop.name, type=prop.type, default=prop.default,
                values=list(prop.values) if prop.type == "string" else None,
                range=(int(prop.values[0]), int(prop.values[-1]))
                if prop.type == "int" else None) for prop in properties],
            appearances=appearances,
            behaviors=FAMILY_BEHAVIOURS.get(family) or [],
            settings=variant_settings(block, assets, requires_tool), group=block.group)

    content = ir.Content(
        format=ir.FORMAT, mod=MOD_ID, mod_version=PACK_VERSION, blocks=blocks,
        items={}, categories=[], features={
            "vertical_slabs": cfg.vertical_slabs,
            "vertical_slabs_cmb": cfg.vertical_slabs_cmb,
            "vertical_slabs_vanilla": cfg.vertical_slabs_vanilla,
            "horizontal_stairs_cmb": cfg.horizontal_stairs_cmb,
            "horizontal_stairs_vanilla": cfg.horizontal_stairs_vanilla,
            "disable_terracotta_variants": cfg.disable_terracotta_variants,
            "disable_concrete_variants": cfg.disable_concrete_variants,
        }, assets=[])

    # Plan against what this version actually has, which is the whole point of
    # splitting the canonical database from availability: cmb does the same thing at
    # startup against the live block registry instead of this cache.
    # Prefer the version-specific snapshot when we have one; fall back to the 1.x
    # mirror cache. This mirrors what cmb does at runtime, except the runtime asks a
    # live registry instead of a downloaded snapshot.
    templates = pathlib.Path(__file__).resolve().parent / "vanilla_templates"
    for candidate in (templates / mc_version / "blockstates", templates / "blockstates"):
        if candidate.is_dir():
            cache = candidate
            break
    else:
        cache = None
    known = ({f"minecraft:{p.stem}" for p in cache.glob("*.json")}
             if cache else None)
    # States other CraftEngine packs already bind. Ownership has to come from disk:
    # at load time CraftEngine has parsed nothing yet, so its own override map is
    # still empty. See ir.scan_claimed_states.
    claims = (PreParseClaims(
        ce_resources, exclude_pack=MOD_ID,
        cache=(ce_resources.parent / "cache" / "visual_block_states.json"))
        if ce_resources else None)
    claimed_map = claims.collect() if claims else {}
    if claims and claims.unresolved:
        # Never silently treat an unresolvable expression as free: a false negative
        # here is two blocks sharing one carrier state.
        for entry in claims.unresolved[:5]:
            print(f"  unresolved claim: {entry}", file=sys.stderr)
    # States CraftEngine's block_state_mappings free from vanilla use. Only these may
    # be lent: anything else is a state real vanilla blocks can show, and they would
    # then draw our model (a real barrel drew as diorite bricks). No mapping data or
    # no identity data means nothing is provably freed, so cubes are deferred.
    identities = Identities.load(mc_version)
    mappings = BlockMappings.load(ce_resources, mc_version)
    freed, targets, unparseable = mappings.freed(identities)
    if unparseable:
        raise BuildError(
            "block_state_mappings that cannot be parsed for a block we may lend from; "
            "an unparseable target could be a state we would otherwise lend:\n  "
            + "\n  ".join(unparseable[:10]))
    canonical = identities.canonical if identities is not None else None
    selection = replacement_selection(cfg, content)
    allocation = ir.allocate(content, mc_version, known, policy, claimed_map,
                             freed, canonical, unimplemented_families(), selection)

    # (3) Every vanilla block whose states we allocated becomes a CraftEngine block.
    vanilla_dir = cache.parent if cache else templates
    converted = ir.converted_vanilla_blocks(content, allocation, vanilla_dir)
    for block in converted:
        content.blocks[block.id] = block

    if policy == "fail" and allocation.unsupported:
        listed = "\n".join(f"  {k}: {v}" for k, v in sorted(allocation.unsupported.items()))
        raise BuildError(
            f"{len(allocation.unsupported)} block(s) cannot be represented on "
            f"Minecraft {mc_version} and compatibility.unsupported-content is "
            f"'fail':\n{listed}")

    validate_behaviour_namespacing(content)
    uniqueness = ir.validate_state_uniqueness(allocation, identities, claimed_map)
    carrier_report = ir.validate_carrier_invariant(content, allocation, freed, canonical)
    carrier_report["state_uniqueness"] = uniqueness
    carrier_report["mapping_freed"] = ir.validate_mapping_freed(
        allocation, freed, targets, canonical)
    carrier_report["mapping_freed"]["mappings"] = len(mappings.pairs)
    return content, allocation, generated_models, carrier_report, templates


def build_pack(mod_root: pathlib.Path, cfg: PackConfig,
               budget: StateBudget | None = None,
               mc_version: str | None = None,
               policy: str = "disable",
               ce_resources: pathlib.Path | None = None) -> Build:
    """Plan the entire pack. Raises BuildError before touching the filesystem."""
    budget = budget or StateBudget()
    mc_version = mc_version or str(vc.VERIFIED_AGAINST)

    content, allocation, generated_models, carrier_report, _templates = build_content(
        mod_root, cfg, mc_version, policy, ce_resources)
    assets = mod_root / "common/src/main/resources/assets"
    data = mod_root / "common/src/main/resources/data"

    # The mod's data pack is source, not output: its ids are not registered on a
    # Paper server, so it is re-expressed as CraftEngine config (see mod_data). A
    # loot table or recipe with no translation stops the build here.
    source = mod_data.ModData(data)
    block_tags = source.tags("block")
    item_tags = source.tags("item")
    try:
        for block_id, block in content.blocks.items():
            if not block_id.startswith(f"{MOD_ID}:"):
                continue
            tags = block_tags.get(block_id)
            if tags:
                block.settings["tags"] = tags
            else:
                block.settings.pop("tags", None)
            block.loot = source.loot(block_id)
    except mod_data.UnsupportedData as exc:
        raise BuildError(f"mod data has no CraftEngine translation: {exc}") from exc

    # Content is canonical and keeps every block, including the ones the allocator
    # deferred on this version. Nothing a player or CraftEngine can reach may name a
    # deferred block: an item whose block_item points at a block that was never
    # built, or a category entry for it, makes it half-exist. Read the deferred set
    # off the allocation that ran rather than recomputing capacity here.
    deferred = set(allocation.unsupported)
    # Deferred stairs and slabs the furniture test serves as furniture instead. They
    # are served, just not as blocks: their canonical id is a furniture item, so the
    # mod's recipes and categories for them stay valid, and no block_item is made.
    fallback = furniture_fallback_ids(cfg, content, deferred)
    deferred -= fallback

    # --- items ---------------------------------------------------------------
    items: dict = {}
    for block_id in sorted(content.blocks):
        if block_id.startswith("minecraft:") or block_id in deferred or block_id in fallback:
            continue
        name = block_id.split(":")[-1]
        items[block_id] = {
            "material": "nether_brick",
            "model": {"path": f"{MOD_ID}:item/{name}"},
            "data": {"item_name": f"<!i><white><lang:block.{MOD_ID}.{name}>"},
            "behavior": {"type": "block_item", "block": block_id},
        }
        if item_tags.get(block_id):
            items[block_id]["settings"] = {"tags": item_tags[block_id]}

    content.items = items

    standalone = modsource.parse_items(mod_root)
    for item in standalone:
        items[item.id] = {
            "material": "nether_brick",
            "model": {"path": f"{MOD_ID}:item/{item.name}"},
            "data": {"item_name": f"<!i><white><lang:item.{MOD_ID}.{item.name}>"},
        }

    # --- build the in-memory file set ---------------------------------------
    build = Build()

    # The three intermediate artifacts. content.json and carriers.json are canonical
    # and version-independent; allocation.json is specific to this target version.
    # cmb consumes the first two and produces the third itself at server startup.
    build.json("intermediate/content.json", content.to_json())
    build.json("intermediate/carriers.json", ir.carriers_to_json())
    build.json("intermediate/allocation.json", allocation.to_json())

    build.text("pack.yml", to_yaml({
        "author": "Cinchtail",
        "version": PACK_VERSION,
        "description": "Cinch's Missing Blocks for Paper, via CraftEngine",
        "namespace": MOD_ID,
        "enable": True,
    }))
    build.text("configuration/config.yml", config_yaml(cfg))

    for family in ("cube", "stairs", "slab", "wall", "pillar", "fence",
                   "pressure_plate", "button", "pane"):
        # Only families with something served: a family whose every block is
        # deferred (buttons, pressure plates) would otherwise emit `blocks: {}`.
        if any(b.family == family and b.id in allocation.assigned
               for b in content.blocks.values()):
            build.text(f"configuration/blocks/{family}.yml",
                       render_block_file(family, content, allocation,
                                         FAMILY_NOTES.get(family),
                                         build.generated.get("carrier_models", {})))

    # --- vertical slabs ------------------------------------------------------
    # Before the generated models are written: a vertical slab contributes its own
    # standing model to `generated_models`, and this loop is the only writer for that
    # dict. Doing it afterwards silently dropped every one of them, which still
    # produced a pack - just one whose furniture pointed at models that were absent.
    vslab_recipes: dict = {}
    # Furniture id -> how it mines, for the plugin's hit-to-break (VerticalSlabListener):
    # a vanilla reference block for tool speed and the correct-tool rule, and the
    # hardness. Read from the deployed pack's intermediate/ folder, which CraftEngine
    # does not load.
    mining: dict[str, dict] = {}
    vslab_count = 0
    hstairs_count = 0
    vslab_doubles: dict = {}
    if (cfg.vertical_slabs or cfg.horizontal_stairs_cmb
            or cfg.horizontal_stairs_vanilla):
        (furniture, vslab_items, vslab_item_models, vslab_recipes,
         vslab_doubles) = render_vertical_slabs(
            content, allocation, cfg, generated_models, assets,
            mod_data.ModData(assets.parent / "data").recipes(), mc_version, fallback,
            mining)
        vslab_count = sum(1 for key in furniture if key.endswith("_vertical"))
        hstairs_count = sum(1 for key in furniture if key.endswith("_horizontal_stairs"))
        if furniture:
            # Top-level key is `furniture:`, not `items:`. CraftEngine parses this
            # file with the furniture parser, so an `items:` block here is not read
            # as furniture definitions at all - the items exist and render in the
            # browser, but nothing is registered to place, and the right-click
            # silently does nothing.
            write_chunked(build, "configuration/furniture/vertical_slab", "furniture",
                          furniture)
            for name, model in sorted(vslab_item_models.items()):
                build.json(f"resourcepack/assets/{MOD_ID}/models/item/{name}.json",
                           model)
            # A furniture item places the furniture, so it is a separate item from the
            # lying slab of the same name and cannot reuse the block_item entry.
            for item_id, entry in vslab_items.items():
                items[item_id] = entry
        if vslab_doubles:
            build.text("configuration/blocks/vertical_slab_double.yml",
                       to_yaml({"blocks": dict(sorted(vslab_doubles.items()))}))

    # --- furniture test: deferred stairs/slabs as furniture ------------------
    fallback_doubles: dict = {}
    if fallback:
        fb_furniture, fb_items, fb_item_models, fallback_doubles, fb_templates = render_furniture_fallback(
            content, allocation, fallback, generated_models, assets,
            mod_data.ModData(assets.parent / "data").recipes(), item_tags,
            cfg.vertical_slabs_doubles, mining)
        build.text("configuration/templates/fallback_variants.yml",
                   to_yaml({"templates": dict(sorted(fb_templates.items()))}))
        write_chunked(build, "configuration/furniture/fallback", "furniture", fb_furniture)
        if fallback_doubles:
            build.text("configuration/blocks/fallback_double.yml",
                       to_yaml({"blocks": dict(sorted(fallback_doubles.items()))}))
        for name, model in sorted(fb_item_models.items()):
            build.json(f"resourcepack/assets/{MOD_ID}/models/item/{name}.json", model)
        items.update(fb_items)

    # --- warped nether wart --------------------------------------------------
    wart_furniture, wart_items, wart_models, wart_recipes = render_warped_nether_wart()
    build.text("configuration/furniture/warped_nether_wart.yml",
               to_yaml({"furniture": {k: compact_furniture(v) for k, v in wart_furniture.items()}}))
    items.update(wart_items)
    generated_models.update(wart_models)
    # A crop breaks in one hit, as vanilla nether wart does.
    mining[f"{MOD_ID}:warped_nether_wart"] = {"reference": "minecraft:nether_wart",
                                              "hardness": 0.0}

    if mining:
        build.json("intermediate/mining.json", dict(sorted(mining.items())))
    build.json(f"resourcepack/assets/{MOD_ID}/models/item/warped_nether_wart.json",
               {"parent": "minecraft:item/generated",
                "textures": {"layer0": f"{MOD_ID}:block/warped_nether_wart"}})


    build.generated["carrier_models"] = {}
    for name, model in sorted(generated_models.items()):
        build.json(f"resourcepack/assets/{MOD_ID}/models/block/{name}.json", model)

    for block_id, block in sorted(content.blocks.items()):
        for appearance in block.appearances:
            baked = appearance.settings.get("baked_model")
            if baked:
                # Must land where the model's `path` points: assets/<ns>/models/block/
                build.json(f"resourcepack/assets/{MOD_ID}/models/block/"
                           + appearance.settings["baked_model_path"] + ".json", baked)

    build.text("configuration/items.yml",
               to_yaml({"items": dict(sorted(items.items()))}))

    # --- creative categories, in the mod's own tab order ---------------------
    order = modsource.parse_item_group_order(mod_root)
    rank = {name: i for i, name in enumerate(order)}
    groups: dict[str, list[str]] = collections.OrderedDict()
    for block_id, block in sorted(content.blocks.items()):
        if (block_id.startswith("minecraft:") or block.settings.get("converted_vanilla")
                or block_id in deferred):
            continue
        groups.setdefault(block.group or "General", []).append(block_id)
    categories = {}
    category_names: dict[str, str] = {}
    for i, (group, ids) in enumerate(sorted(groups.items(), key=lambda kv: min(
            rank.get(i.split(":")[-1], 10_000) for i in kv[1]))):
        slug = _slug(group)
        categories[f"{MOD_ID}:{slug}"] = {
            "priority": i,
            "name": f"<!i><white><l10n:category.{MOD_ID}.{slug}>",
            "icon": ids[0],
            "list": sorted(ids, key=lambda b: rank.get(b.split(":")[-1], 10_000)),
        }
        category_names[group] = slug
    # Categories for the furniture pieces. Without one, a piece
    # shows nowhere in CraftEngine's item browser. The furniture-fallback stairs and
    # slabs are content blocks and already sit in their material's category; display
    # items (`__`) are never listed.
    for suffix, slug, title in (("_vertical", "vertical_slabs", "Vertical Slabs"),
                                ("_horizontal_stairs", "horizontal_stairs", "Horizontal Stairs")):
        members = sorted(i for i in items if i.endswith(suffix) and "__" not in i)
        if members:
            categories[f"{MOD_ID}:{slug}"] = {
                "priority": len(categories),
                "name": f"<!i><white>{title}",
                "icon": members[0],
                "list": members,
            }
    build.text("configuration/categories.yml", to_yaml({"categories": categories}))

    # --- language ------------------------------------------------------------
    lang = modsource.lang_keys(mod_root)

    # A category that names a lang key nobody defines renders as the raw key - the
    # CraftEngine item browser showed "category.cinchsmissingblocks.endstone_blocks"
    # instead of "Endstone Blocks". The mod has no category translations because it
    # used a single creative tab, so emit them from the group names we already have.
    for group, slug in sorted(category_names.items()):
        # Not added to the asset lang file on purpose - see translations.yml below.
        lang.pop(f"category.{MOD_ID}.{slug}", None)
    lang = {k: display_name(v) for k, v in sorted(lang.items()) if v}
    build.json("resourcepack/assets/cinchsmissingblocks/lang/en_us.json", lang)

    # CraftEngine has two separate localization systems and they are not
    # interchangeable:
    #
    #   lang:         resource-pack / Minecraft translation keys
    #   translations: CraftEngine's own l10n, which is what <l10n:key> resolves
    #                 against, per the player's client locale
    #
    # Category names are consumed only by CraftEngine's item browser, so
    # translations: is authoritative for them and the asset lang file must not carry
    # them. Feeding them into a `lang#categories:` block instead - which is what
    # CraftEngine's own bundled *items* use - loads fine ("Loaded lang in 6.30ms (652)")
    # and still renders the raw key, because the message only proves the lang
    # configuration parsed.
    #
    # `en` rather than `en_us`: CraftEngine falls back en_us -> en -> English, and a
    # category name is not US-specific.
    build.text("configuration/translations.yml", to_yaml({
        "translations": {"en": {
            f"category.{MOD_ID}.{slug}": display_name(group)
            for group, slug in sorted(category_names.items())
        }}
    }))

    # --- recipes -------------------------------------------------------------
    # A recipe is emitted only if every Paperized item it names - result or
    # ingredient - is an item this build actually emits. That drops recipes for
    # deferred and variant-disabled blocks, and recipes that consume them.
    recipes: dict[str, dict] = {}
    skipped_recipes: dict[str, str] = {}
    for recipe_id, vanilla in source.recipes().items():
        if replaced_recipe(recipe_id):
            skipped_recipes[recipe_id] = replaced_recipe(recipe_id)
            continue
        try:
            recipe = mod_data.translate_recipe(recipe_id, vanilla)
        except mod_data.UnsupportedData as exc:
            raise BuildError(f"mod data has no CraftEngine translation: {exc}") from exc
        missing = sorted(i for i in mod_data.recipe_item_ids(recipe)
                         if i.startswith(f"{MOD_ID}:") and i not in items)
        if missing:
            skipped_recipes[recipe_id] = f"names {', '.join(missing)}, not emitted"
            continue
        recipes[recipe_id] = recipe
    # Vertical slab recipes are built inside render_vertical_slabs, alongside the
    # furniture they belong to, so that the deduplication that keeps one vertical
    # slab per material also decides which source block the recipe consumes. Merging
    # them here instead would mean re-deriving that set, and the two would drift.
    recipes.update(vslab_recipes)
    recipes.update(wart_recipes)
    recipes.update(nether_brick_recipes())
    recipes.update(variant_recipes(content, source.recipes(),
                                   {i for i in items if i in content.blocks}))
    build.text("configuration/recipes.yml", to_yaml({"recipes": recipes}))

    # --- asset copies --------------------------------------------------------
    # No data pack: the mod's data/ tree is translated above instead of copied.
    # Copy the mod's assets verbatim, minus the directories this generator owns.
    # lang/ in particular: the mod ships only block/item/itemgroup keys, and copying
    # it back over the generated file was silently discarding the category
    # translations that make the CraftEngine item browser readable.
    build.copies.append((assets / MOD_ID, pathlib.Path(
        f"resourcepack/assets/{MOD_ID}")))
    build.copy_ignores[pathlib.Path(f"resourcepack/assets/{MOD_ID}")] = {"lang"}
    minecraft_models = assets / "minecraft"
    if minecraft_models.is_dir():
        build.copies.append((minecraft_models, pathlib.Path("resourcepack/assets/minecraft")))

    art = pathlib.Path(__file__).resolve().parent.parent / "warped_netherwart_art" / "warped"
    if not art.is_dir():
        raise BuildError(f"warped nether wart art missing at {art}")
    for png in sorted(art.glob("*.png")):
        build.copies.append((png, pathlib.Path(
            f"resourcepack/assets/{MOD_ID}/textures/block/{png.name}")))

    parsed = modsource.parse_blocks(mod_root)
    enabled_blocks = [b for b in parsed if b.id in content.blocks]
    # Read carrier usage off the allocation that actually ran, not a fresh pool.
    pools = {name: vc.CarrierPool.build(spec) for name, spec in vc.FAMILIES.items()}
    for name, pool in pools.items():
        cap = allocation.families.get(name, {})
        pool.used = {k: 0 for k in pool.available}
        pool._reported_used = cap.get("states_used", 0)
    build.stats = collect_stats(mod_root, parsed, enabled_blocks,
                                len(parsed) - len(enabled_blocks), {}, pools,
                                items, cfg, allocation=allocation)
    # Plus one per doubled vertical slab: an auto_state block outside the allocator,
    # but still a CraftEngine internal state.
    # Only blocks emitted as CraftEngine blocks cost internal states: a deferred block
    # is not registered, and one served as furniture is an entity. Counting every
    # canonical block made the terracotta and concrete variants look 1,300 states over
    # budget when furniture-fallback serves them.
    build.stats["ce_internal_states"] = sum(
        len(block.appearances) for block_id, block in content.blocks.items()
        if block_id in allocation.assigned) + len(vslab_doubles) + len(fallback_doubles) \
        + WART_STATES
    build.stats["budget"] = dataclasses.asdict(budget)
    build.stats["budget_usable"] = budget.usable
    build.stats["mc_version"] = mc_version
    build.stats["vertical_slabs"] = vslab_count
    build.stats["horizontal_stairs"] = hstairs_count
    build.stats["furniture_fallback"] = sorted(fallback)
    build.stats["policy"] = policy
    build.stats["unsupported"] = allocation.unsupported
    build.stats["family_capacity"] = allocation.families
    build.stats["externally_claimed"] = allocation.externally_claimed
    build.stats["claimed_by"] = allocation.claimed_by
    build.stats["unsupported_families"] = allocation.unsupported_families
    build.stats["unsupported_block_ids"] = allocation.unsupported_block_ids
    if ce_resources:
        report_claims = PreParseClaims(
            ce_resources, exclude_pack=MOD_ID,
            cache=ce_resources.parent / "cache" / "visual_block_states.json")
        collected = report_claims.collect()
        build.stats["preparse_claims"] = len(collected)
        build.stats["preparse_unresolved"] = len(report_claims.unresolved)
        build.stats["preparse_by_source"] = dict(collections.Counter(
            owner.rsplit("(", 1)[-1].rstrip(")") for owner in collected.values()))
    build.stats["carrier_invariant"] = carrier_report
    build.stats["recipes"] = len(recipes)
    build.stats["recipes_skipped"] = len(skipped_recipes)
    build.stats["recipe_overrides"] = 0
    build.stats["recipe_overrides_not_ported"] = source.vanilla_overrides()
    build.stats["loot_tables"] = sum(
        1 for b in content.blocks.values()
        if b.loot is not None and b.id not in deferred)

    # The whole pack is built and every count is known by this point, so this is
    # the last chance to refuse it. Nothing has touched the filesystem yet.
    budget.check(build.stats["ce_internal_states"])
    build.stats["yaml_sizes"] = validate_yaml_sizes(build)
    build.json("intermediate/pieces.json", piece_manifest(build, content, mc_version))
    build.stats["deferred_references"] = validate_no_deferred_references(
        build, deferred)
    build.stats["native_functionality"] = validate_native_functionality(
        build, block_tags, item_tags)
    build.stats["block_schema"] = validate_block_schema(build)
    build.stats["l10n_references"] = validate_l10n_references(build)
    build.stats["model_references"] = validate_model_references(build, mc_version)
    build.stats["carrier_eligibility"] = check_carrier_eligibility(
        build, mc_version, allocation)
    return build


#: Carrier families whose vanilla blockstates are variants-only, so overwriting them
#: cannot destroy unrelated vanilla rendering. Anything else needs an explicit
#: opt-in, because the damage lands on vanilla content.
SAFE_CARRIER_FAMILIES = ("stairs", "slab", "plate")


def check_carrier_eligibility(build: "Build", mc_version: str,
                              allocation: ir.Allocation) -> dict:
    """Fail when borrowing a carrier would strip vanilla rendering it does not own.

    CraftEngine removes ``multipart`` from any blockstate it writes into and merges
    its own entries into ``variants``. On a variants-only blockstate that is
    harmless - the original variants survive the merge. On a multipart blockstate
    it is destructive: every state vanilla used to render, and that we did not
    allocate, is left with no model and draws magenta in game.

    Checked against vanilla's own blockstate, never our generated output, so the
    check cannot validate the damage against itself.
    """
    import json as _json
    blockstates = (pathlib.Path(__file__).resolve().parent
                   / "vanilla_templates" / mc_version)
    fixture_path = (pathlib.Path(__file__).resolve().parent
                    / "fixtures" / f"blocks_{mc_version}.json")
    if not fixture_path.is_file() or not blockstates.is_dir():
        return {"status": "SKIPPED", "reason": "no vanilla reference snapshot"}
    fixture = _json.loads(fixture_path.read_text())["blocks"]
    counts = {k: v["states"] for k, v in fixture.items()}

    # Assess every family the allocation actually draws from, not just the ones we
    # believe are safe - otherwise the gate silently skips the ones it should catch.
    # Families already marked ineligible lend nothing and are simply not carriers.
    # They are reported separately rather than as damage.
    in_use = sorted({n for n in vc.FAMILIES
                     if n in allocation.families and vc.FAMILIES[n].merge_safe})
    rejected = sorted({n for n in vc.FAMILIES if not vc.FAMILIES[n].merge_safe})
    pools = {n: vc.CarrierPool.build(vc.FAMILIES[n], claimed=set()) for n in in_use}
    carriers: dict[str, list[str]] = {n: list(vc.FAMILIES[n].blocks)
                                      for n in pools}
    converted: set[str] = set()
    declared: dict[str, int] = {}
    for pool in pools.values():
        per = pool.family.states_per_custom_block
        for block in pool.converted:
            converted.add(block)
            declared[block] = per

    damage = elig.assess(carriers, counts, converted, blockstates, declared)
    summary = elig.summarise(damage)
    report = {"families": summary,
              "total_destroyed": sum(e["states_destroyed"]
                                     for e in summary.values()),
              "safe_families": in_use,
              "rejected_families": rejected,
              "status": "PASS"}

    unsafe = {f: e for f, e in summary.items()
              if e["states_destroyed"] and f not in SAFE_CARRIER_FAMILIES}
    if unsafe:
        listed = "\n".join(
            f"    {f:<8} {e['multipart_carriers']}/{e['carriers_borrowed']} "
            f"carriers are multipart, destroying {e['states_destroyed']} vanilla states"
            for f, e in sorted(unsafe.items()))
        raise BuildError(
            "carrier eligibility - multipart damage\n"
            + "\u2500" * 78 + "\n"
            + listed + "\n"
            + f"\n  {report['total_destroyed']} vanilla block states would lose their "
              f"rendering.\n\n"
            + "  Borrowing these is not safe: CraftEngine strips multipart from any\n"
            + "  blockstate it writes, so every state we did not allocate is left\n"
            + "  with no model and draws magenta in game. A carrier is only\n"
            + "  eligible if modifying its blockstate cannot destroy unrelated\n"
            + "  vanilla rendering.\n\n"
            + f"  Safe families: {', '.join(SAFE_CARRIER_FAMILIES)}\n"
            + "  Drop the unsafe families from the carrier database, or convert\n"
            + "  every state of each carrier (which turns the carrier into a global\n"
            + "  override of a vanilla block - exactly the collateral damage this\n"
            + "  project exists to avoid).")
    return report


def validate_l10n_references(build: "Build") -> dict:
    """Every <l10n:key> the emitted configuration references must be declared.

    CraftEngine renders an undeclared key literally, which looks like a missing
    translation and is not obviously a generator bug - the item browser showed
    "category.cinchsmissingblocks.stone_blocks" with no server-side complaint at all.
    Declaring the keys in the wrong localization system produces exactly the same
    symptom, so the check is on the pair (reference, declaration) rather than on
    whether some lang file mentions the key at all.
    """
    import re as _re
    import yaml as _yaml
    declared: set[str] = set()
    path = pathlib.Path("configuration/translations.yml").as_posix()
    for candidate, body in build.files.items():
        if candidate.as_posix().endswith("translations.yml"):
            data = _yaml.safe_load(body) or {}
            for locales in (data.get("translations") or {}).values():
                # An empty value renders as a blank label, which is as broken as a
                # missing one, so treat it as undeclared.
                declared.update(k for k, v in (locales or {}).items()
                                if str(v or "").strip())

    referenced: dict[str, set[str]] = {}
    for candidate, body in sorted(build.files.items()):
        name = candidate.as_posix()
        if not name.startswith("configuration/"):
            continue
        try:
            data = _yaml.safe_load(body)
        except Exception:
            continue
        for found in _re.findall(r"l10n:([A-Za-z0-9_.-]+)", str(data)):
            referenced.setdefault(found, set()).add(name)

    missing = sorted(set(referenced) - declared)
    report = {"referenced": len(referenced), "declared": len(declared),
              "missing": len(missing)}
    if missing:
        listed = "\n".join(f"    {k}  (referenced by {sorted(referenced[k])[:2]})"
                           for k in missing[:10])
        raise BuildError(
            "l10n references with no translations entry\n"
            + "\u2500" * 78 + "\n"
            + listed + "\n"
            + f"\n  {len(missing)} key(s) would render literally in the client.\n"
            + "  Declare them under translations: in configuration/translations.yml;\n"
            + "  a lang asset file or a lang#section: block does not feed <l10n:...>.")
    report["status"] = "PASS"
    return report


def validate_model_references(build: "Build", mc_version: str) -> dict:
    """Every model the emitted blocks and items reference must exist in the final pack.

    Resolved transitively (parent chains and textures) against the generated files,
    the copied asset trees and the vanilla client's assets. Without this, a missing
    model is only a CraftEngine validator warning at pack generation and a magenta
    block in game.
    """
    import yaml
    files = {path.as_posix(): body for path, body in build.files.items()}
    roots: dict[str, str] = {}

    def add(node, user: str) -> None:
        if isinstance(node, list):
            for value in node:
                add(value, user)
        elif isinstance(node, dict) and isinstance(node.get("path"), str):
            roots.setdefault(node["path"], user)

    for rel, body in sorted(files.items()):
        if rel.startswith("configuration/blocks/"):
            for block_id, entry in (yaml.safe_load(body).get("blocks") or {}).items():
                for name, appearance in ((entry.get("states") or {})
                                         .get("appearances") or {}).items():
                    add(appearance.get("model"), f"{block_id} [{name}]")
    for item_id, item in (yaml.safe_load(files["configuration/items.yml"])["items"]
                          or {}).items():
        add(item.get("model"), f"item {item_id}")

    vanilla = pack_assets.VanillaAssets.load(mc_version)
    view = pack_assets.PackView(
        files, [(src, dest.as_posix()) for src, dest in build.copies], vanilla)
    problems = pack_assets.missing_references(view, roots)
    if problems:
        raise ir.InvariantError(
            f"model references\n{'-' * 46}\nReferenced models: {len(roots)}\n"
            f"Problems: {len(problems)}\n\nFAIL\n\n"
            + "\n".join(problems[:10]) + ("\n  ..." if len(problems) > 10 else ""))
    return {"models": len(roots), "status": "PASS",
            "vanilla": (f"client assets {vanilla.version}" if vanilla
                        else "unverified (no vanilla asset fixture)")}


def validate_block_schema(build: "Build") -> dict:
    """Every emitted block's variants must address its own declared state space.

    A variant key may name only properties the block declares, with declared values,
    and must point at an appearance that exists. Converted vanilla carriers once
    declared one-value properties holding a Python tuple's repr and keyed their
    variants by full vanilla state strings. CraftEngine logged nothing, because the
    appearances still bound their states, but the block's state machine was nonsense.
    """
    import yaml
    problems: list[str] = []
    checked = 0
    for path, body in sorted(build.files.items()):
        rel = path.as_posix()
        if not rel.startswith("configuration/blocks/"):
            continue
        for block_id, entry in (yaml.safe_load(body).get("blocks") or {}).items():
            states = entry.get("states") or {}
            declared = {name: {str(v) for v in spec.get("values", [])}
                        for name, spec in (states.get("properties") or {}).items()}
            appearances = states.get("appearances") or {}
            for key, variant in (states.get("variants") or {}).items():
                checked += 1
                pairs = [part.split("=", 1) for part in key.split(",") if part]
                for pair in pairs:
                    if len(pair) != 2 or pair[0] not in declared:
                        problems.append(f"  {block_id}: variant '{key}' names undeclared "
                                        f"property '{pair[0]}'")
                    elif declared[pair[0]] and pair[1] not in declared[pair[0]]:
                        problems.append(f"  {block_id}: variant '{key}' uses undeclared "
                                        f"value {pair[0]}={pair[1]}")
                if variant.get("appearance") not in appearances:
                    problems.append(f"  {block_id}: variant '{key}' points at missing "
                                    f"appearance {variant.get('appearance')}")
            for name, spec in (states.get("properties") or {}).items():
                values = [str(v) for v in spec.get("values", [])]
                if values and str(spec.get("default")) not in values:
                    problems.append(f"  {block_id}: property {name} default "
                                    f"{spec.get('default')!r} is not one of its values")
    if problems:
        raise ir.InvariantError(
            f"block schema\n{'-' * 46}\nProblems: {len(problems)}\n\nFAIL\n\n"
            + "\n".join(problems[:10]) + ("\n  ..." if len(problems) > 10 else ""))
    return {"variants": checked, "status": "PASS"}


def validate_native_functionality(build: "Build", block_tags: dict[str, list[str]],
                                  item_tags: dict[str, list[str]]) -> dict:
    """The CraftEngine functionality that replaced the mod's data pack, checked as emitted.

    Reads the final configuration files, not the generator's intermediate lists, and
    compares them with external truth: the items actually emitted (which only exist
    for served blocks) and the mod's own tag files. The failures this exists for:

    - no data pack is emitted at all - it could never resolve cinchsmissingblocks:*
      ids, and a tag naming an unknown id would break the vanilla tag with it;
    - every emitted Paperized block has loot, so none silently drops nothing;
    - loot and recipes name only Paperized items that are emitted;
    - blocks and items carry every tag the mod declares for them.
    """
    import yaml
    files = {path.as_posix(): body for path, body in build.files.items()}
    problems: list[str] = []

    stray = sorted(rel for rel in files
                   if rel.startswith("datapack/") or rel.endswith("pack.mcmeta"))
    stray += sorted(dest.as_posix() for _, dest in build.copies
                    if not dest.as_posix().startswith("resourcepack/"))
    problems += [f"  {rel}: data pack output is not allowed" for rel in stray]

    items = yaml.safe_load(files["configuration/items.yml"])["items"]
    recipes = yaml.safe_load(files["configuration/recipes.yml"])["recipes"] or {}
    blocks: dict = {}
    for rel, body in sorted(files.items()):
        if rel.startswith("configuration/blocks/"):
            blocks.update(yaml.safe_load(body).get("blocks") or {})

    def emitted(item: str) -> bool:
        return not item.startswith(f"{MOD_ID}:") or item in items

    def loot_items(block_id: str, node) -> set[str]:
        found: set[str] = set()
        if isinstance(node, dict):
            if node.get("template") in ("default:loot_table/self",
                                        "default:loot_table/slab"):
                found.add(block_id)
            for key, value in node.items():
                if key == "item" and isinstance(value, str):
                    found.add(value)
                else:
                    found |= loot_items(block_id, value)
        elif isinstance(node, list):
            for value in node:
                found |= loot_items(block_id, value)
        return found

    with_loot = 0
    for block_id, entry in sorted(blocks.items()):
        if not block_id.startswith(f"{MOD_ID}:"):
            continue
        loot = entry.get("loot")
        if loot is None:
            problems.append(f"  {block_id}: no loot, so it drops nothing")
        else:
            with_loot += 1
            problems += [f"  {block_id}: loot drops {item}, which is not emitted"
                         for item in sorted(loot_items(block_id, loot))
                         if not emitted(item)]
        have = set(entry.get("settings", {}).get("tags", []))
        missing = sorted(set(block_tags.get(block_id, [])) - have)
        if missing:
            problems.append(f"  {block_id}: block tags missing {missing}")

    # An item named after a canonical block must place that block, and the block
    # must be emitted. Anything else looks like the block and does nothing - the
    # alias items for the deepslate button and pressure plate were exactly that.
    canonical = set(yaml.safe_load(files["intermediate/content.json"])["blocks"])
    for item_id, item in sorted(items.items()):
        if item_id in canonical:
            behavior = item.get("behavior") or {}
            if behavior.get("type") == "furniture_item" and behavior.get("furniture") == item_id:
                # The furniture test: a deferred stair or slab served as furniture
                # under its own id. Placing it places that furniture, not a block.
                continue
            target = behavior.get("block")
            if behavior.get("type") != "block_item" or target != item_id:
                problems.append(f"  {item_id}: item is named after a block but does not "
                                f"place it")
            elif target not in blocks:
                problems.append(f"  {item_id}: places {target}, which is not emitted")

    for item_id, item in sorted(items.items()):
        have = set((item.get("settings") or {}).get("tags", []))
        missing = sorted(set(item_tags.get(item_id, [])) - have)
        if missing:
            problems.append(f"  {item_id}: item tags missing {missing}")

    for recipe_id, recipe in sorted(recipes.items()):
        problems += [f"  {recipe_id}: names {item}, which is not emitted"
                     for item in sorted(mod_data.recipe_item_ids(recipe))
                     if not emitted(item)]

    if problems:
        raise ir.InvariantError(
            f"native functionality\n{'-' * 46}\nProblems: {len(problems)}\n\nFAIL\n\n"
            + "\n".join(problems[:10]) + ("\n  ..." if len(problems) > 10 else ""))
    return {"blocks_with_loot": with_loot, "recipes": len(recipes),
            "items": len(items), "status": "PASS"}


#: CraftEngine reads its YAML with snakeyaml-engine, which refuses a document over this
#: many code points (YamlEngineException "exceeds the limit"). A file past it is
#: dropped whole: every definition in it is missing, with one error at load.
CRAFTENGINE_YAML_LIMIT = 3_145_728
#: Chunk size for the large generated files, well under the limit.
YAML_CHUNK = 1_000_000


def piece_manifest(build: "Build", content: ir.Content, mc_version: str) -> dict:
    """Which config switches every emitted id depends on: intermediate/pieces.json.

    The jar bundles one pack built with everything on (tools/build-config.release.yml),
    and the plugin's installer removes what a server's config.yml turns off. This is
    how it knows what to remove: per section (items, blocks, furniture), each id maps
    to switches that must all be on for it to stay. A switch is one of

        vertical-slabs.cmb / .vanilla, horizontal-stairs.cmb / .vanilla
        vertical-slabs.material:<m> / horizontal-stairs.material:<m>  (m not disabled)
        furniture-fallback, block:<name>  (name not in disabled-blocks)
        terracotta, concrete              (the variant is not disabled)
        viabackwards                      (a CMB block vanilla has since added)
        doubles.block / doubles.furniture (the chosen form of doubles)

    and "a|b" means either. Read off the final configuration files, so it covers
    exactly what was emitted.
    """
    import yaml
    files = {rel.as_posix(): body for rel, body in build.files.items()}
    sections: dict[str, dict] = {"items": {}, "blocks": {}, "furniture": {}}
    for rel, body in files.items():
        if rel.startswith("configuration/") and rel.endswith(".yml"):
            data = yaml.safe_load(body) or {}
            for section in sections:
                sections[section].update({k: None for k in (data.get(section) or {})})
    cmb_bases = {b.split(":")[1].removesuffix("_slab").removesuffix("_stairs")
                 for b, blk in content.blocks.items() if blk.family in ("slab", "stairs")}
    vanilla_bases = {s.removesuffix("_slab") for s in (vanilla_slabs.load(mc_version) or {})}
    assets = pathlib.Path(__file__).resolve().parent / "fixtures" / f"vanilla_assets_{mc_version}.json"
    vanilla_models = set(json.loads(assets.read_text())["models"]) if assets.is_file() else set()

    def piece(feature: str, base: str) -> list[str]:
        sources = []
        if base in vanilla_bases:
            sources.append(f"{feature}.vanilla")
        if base in cmb_bases:
            sources.append(f"{feature}.cmb")
        flags = ["|".join(sources), f"{feature}.material:{base}"]
        if sources == [f"{feature}.cmb"]:
            # A CMB material's stairs and slabs exist only as furniture, and a
            # terracotta or concrete material only while that variant is on. (A
            # material vanilla also has comes from the vanilla slab instead.)
            flags.append("furniture-fallback")
            flags += [variant for variant, tokens in VARIANT_TOKENS.items()
                      if any(t in base for t in tokens)]
        return flags

    def flags_for(block_id: str, section: str) -> list[str]:
        name = block_id.split(":", 1)[1]
        if "__" in name:
            return flags_for(f"{MOD_ID}:{name.split('__')[0]}", "furniture")
        if name.endswith("_double"):
            form = "doubles.block" if section == "blocks" else "doubles.furniture"
            return flags_for(f"{MOD_ID}:{name.removesuffix('_double')}", "furniture") + [form]
        if name.endswith("_vertical"):
            return piece("vertical-slabs", name.removesuffix("_vertical"))
        if name.endswith("_horizontal_stairs"):
            return piece("horizontal-stairs", name.removesuffix("_horizontal_stairs"))
        block = content.blocks.get(block_id)
        if block is None:
            return []
        flags = [f"block:{name}"]
        if "terracotta" in name:
            flags.append("terracotta")
        if "_concrete" in name:
            flags.append("concrete")
        if f"block/{name}" in vanilla_models:
            flags.append("viabackwards")
        if block.family in FALLBACK_FAMILIES:
            flags.append("furniture-fallback")
        return flags

    return {section: {i: flags_for(i, section) for i in sorted(ids) if i.startswith(f"{MOD_ID}:")}
            for section, ids in sections.items()}


def write_chunked(build: "Build", base: str, key: str, entries: dict) -> None:
    """Write `{key: entries}` as base.yml, or base/part_NN.yml when it is large.

    CraftEngine loads every .yml under configuration/, subfolders included, so the
    split costs nothing; it exists because of CRAFTENGINE_YAML_LIMIT - furniture-fallback
    with walls and fences was 6.4 million characters in one file.
    """
    if key == "furniture":
        entries = {name: compact_furniture(entry) for name, entry in entries.items()}
    whole = to_yaml({key: dict(sorted(entries.items()))})
    if len(whole) < YAML_CHUNK:
        build.text(f"{base}.yml", whole)
        return
    part, size, index = {}, 0, 0
    for name, entry in sorted(entries.items()):
        piece = len(to_yaml({name: entry}))
        if part and size + piece > YAML_CHUNK:
            build.text(f"{base}/part_{index:02d}.yml", to_yaml({key: part}))
            part, size, index = {}, 0, index + 1
        part[name] = entry
        size += piece
    if part:
        build.text(f"{base}/part_{index:02d}.yml", to_yaml({key: part}))


#: CraftEngine's own defaults for a shulker hitbox and an item-display element
#: (ShulkerFurnitureHitboxConfig, ItemDisplayFurnitureElementConfig). Leaving them out
#: changes nothing CraftEngine builds; it keeps the furniture files a fraction of the
#: size - a wall has 89 shapes of up to 15 boxes each.
HITBOX_DEFAULTS = {"peek": 0, "interactive": True, "blocks_building": True,
                   "interaction_entity": True}
ELEMENT_DEFAULTS = {"display_transform": "none", "billboard": "fixed",
                    "translation": "0,0,0", "shadow_radius": 0}


def compact_furniture(entry: dict) -> dict:
    """A furniture definition without the keys that only restate CraftEngine's defaults."""
    def strip(node: dict, defaults: dict) -> dict:
        return {k: v for k, v in node.items() if k not in defaults or defaults[k] != v}
    if "template" in entry.get("variants", {}):
        return entry
    variants = {}
    for name, variant in entry.get("variants", {}).items():
        variants[name] = {
            **variant,
            "elements": [strip(e, ELEMENT_DEFAULTS) for e in variant.get("elements", [])],
            "hitboxes": [strip(h, HITBOX_DEFAULTS) for h in variant.get("hitboxes", [])],
        }
    return {**entry, "variants": variants} if variants else entry


def validate_yaml_sizes(build: "Build") -> dict:
    """No generated YAML may reach CraftEngine's per-document limit."""
    largest = 0
    too_big: list[str] = []
    for rel, body in sorted(build.files.items()):
        if rel.suffix != ".yml":
            continue
        largest = max(largest, len(body))
        if len(body) >= CRAFTENGINE_YAML_LIMIT:
            too_big.append(f"  {rel.as_posix()}: {len(body):,} characters")
    if too_big:
        raise ir.InvariantError(
            f"YAML too large for CraftEngine\n{'-' * 46}\n"
            f"Limit: {CRAFTENGINE_YAML_LIMIT:,} code points per file\n\n"
            + "\n".join(too_big) + "\n\nFAIL\n\nCraftEngine drops a file past its limit "
            "whole. Write it with write_chunked.")
    return {"largest": largest, "limit": CRAFTENGINE_YAML_LIMIT, "status": "PASS"}


def validate_no_deferred_references(build: "Build", deferred: set[str]) -> dict:
    """No generated CraftEngine configuration may name a deferred block.

    Scans every emitted ``configuration/*.yml`` structurally - each mapping key and
    each string value - rather than checking items and categories by hand, so a
    surface added later is covered without anyone remembering to add it here.
    """
    import yaml
    leaks: list[str] = []
    scanned = 0

    def walk(node, path: str, rel: str) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                if key in deferred:
                    leaks.append(f"  {rel}: {path}/{key} (key)")
                walk(value, f"{path}/{key}", rel)
        elif isinstance(node, list):
            for i, value in enumerate(node):
                walk(value, f"{path}[{i}]", rel)
        elif isinstance(node, str) and node in deferred:
            leaks.append(f"  {rel}: {path} = {node}")

    for path, body in sorted(build.files.items()):
        rel = path.as_posix()
        if rel.startswith("configuration/") and rel.endswith(".yml"):
            scanned += 1
            walk(yaml.safe_load(body), "", rel)
    if leaks:
        raise ir.InvariantError(
            f"deferred blocks referenced by generated configuration\n{'-' * 46}\n"
            f"Deferred blocks:  {len(deferred)}\nReferences:       {len(leaks)}\n\n"
            "FAIL\n\nA deferred block was never built, so nothing may point at it.\n"
            + "\n".join(leaks[:8]) + ("\n  ..." if len(leaks) > 8 else ""))
    return {"deferred": len(deferred), "files_scanned": scanned, "status": "PASS"}


def family_capacity(pools: dict[str, vc.CarrierPool]) -> dict[str, dict]:
    """Per-family required-vs-available, for the version report."""
    out: dict[str, dict] = {}
    for name, pool in sorted(pools.items()):
        spec = pool.family
        per_block = spec.states_per_custom_block
        spare = spec.capacity - len(spec.blocks) * per_block
        out[name] = {
            "carriers_available": len(spec.blocks),
            "states_per_carrier": spec.states_per_vanilla_block,
            "capacity_blocks": spare // per_block,
            "states_used": pool.states_used,
            "states_free": pool.states_free,
        }
    return out


def collect_stats(mod_root, parsed, blocks, dropped, resolved, pools, items, cfg,
                  allocation=None) -> dict:
    assets = mod_root / "common/src/main/resources/assets"
    data = mod_root / "common/src/main/resources/data"

    def count(root: pathlib.Path, pattern: str) -> int:
        return len(list(root.glob(pattern))) if root.is_dir() else 0

    by_family = collections.Counter(b.family for b in blocks)
    return {
        "mod_blocks_total": len(parsed),
        "mod_blocks_enabled": len(blocks),
        "mod_blocks_dropped": dropped,
        "families": dict(sorted(by_family.items())),
        # Count what was actually emitted rather than trusting the per-family
        # constants, so the number in the summary is the number in the YAML. Each
        # declared state is one CraftEngine internal state, because CraftEngine
        # registers the whole Cartesian product of a block's properties.
        "ce_internal_states": sum(
            len(entry.declared) for entries in resolved.values() for entry in entries),
        "ce_vanilla_converted": len(allocation.converted_block_ids()),
        "carrier_used": sum(cap.get("assigned_states", 0)
                            for cap in allocation.families.values()),
        "carrier_free": sum(cap.get("available_states", 0)
                            for cap in allocation.families.values()),
        "carrier_pool": sum(cap["carriers_available"] * cap["states_per_carrier"]
                            for cap in allocation.families.values()),
        "items": len(items),
        "recipes": count(data / MOD_ID / "recipe", "**/*.json"),
        "recipe_overrides": count(data / "minecraft" / "recipe", "**/*.json"),
        "loot_tables": count(data / MOD_ID / "loot_table", "**/*.json"),
        "blockstates": count(assets / MOD_ID / "blockstates", "*.json"),
        "models": count(assets / MOD_ID / "models", "**/*.json"),
        "textures": count(assets / MOD_ID / "textures", "**/*.png"),
        "config": dataclasses.asdict(cfg),
    }


# --------------------------------------------------------------------------- #
# summary
# --------------------------------------------------------------------------- #

RULE = "\u2500" * 64


def render_summary(build: Build) -> str:
    s = build.stats
    cfg = s["config"]
    families = s["families"]
    other = (families.get("pressure_plate", 0) + families.get("button", 0)
             + families.get("pane", 0))
    ce_blocks = s["mod_blocks_enabled"] + s["ce_vanilla_converted"]

    def furniture_row(key: str) -> str:
        cmb, vanilla = cfg[f"{key}_cmb"], cfg[f"{key}_vanilla"]
        if not (cmb or vanilla):
            return onoff(False)
        return (f"{onoff(True)} ({s.get(key, 0)}: CMB {'on' if cmb else 'off'}, "
                f"vanilla {'on' if vanilla else 'off'})")

    def onoff(enabled: bool) -> str:
        return "ON" if enabled else "OFF"

    rows = [
        ("Custom blocks:", f"{s['mod_blocks_total']}"),
        ("CE blocks:", f"{ce_blocks}"),
        ("CraftEngine internal state budget", ""),
        ("  Used:", f"{s['ce_internal_states']}"),
        ("  Reserved:", f"{s['budget']['reserved_states']}"),
        ("  Configured:", f"{s['budget']['max_internal_states']}"),
        ("  Free:", f"{s['budget']['max_internal_states'] - s['ce_internal_states'] - s['budget']['reserved_states']}"),
        ("  Status:", "PASS"
         if s['ce_internal_states'] + s['budget']['reserved_states']
         <= s['budget']['max_internal_states'] else "FAILED"),
        ("Carrier states used:", f"{s['carrier_used']}"),
        ("Carrier states free:", f"{s['carrier_free']}"),
        ("", ""),
        ("Stairs:", f"{families.get('stairs', 0)}"),
        ("Walls:", f"{families.get('wall', 0)}"),
        ("Slabs:", f"{families.get('slab', 0)}"),
        ("Cubes:", f"{families.get('cube', 0)}"),
        ("Pillars:", f"{families.get('pillar', 0)}"),
        ("Fences:", f"{families.get('fence', 0)}"),
        ("Other:", f"{other}"),
        ("", ""),
        ("Recipes:", f"{s['recipes'] + s['recipe_overrides']}"),
        ("Loot tables:", f"{s['loot_tables']}"),
        ("Models:", f"{s['models']}"),
        ("", ""),
        ("Variants:", ""),
        ("  Terracotta:", onoff(not cfg["disable_terracotta_variants"])),
        ("  Concrete:", onoff(not cfg["disable_concrete_variants"])),
        ("", ""),
        ("Vertical Slabs:", furniture_row("vertical_slabs")),
        ("Horizontal Stairs:", furniture_row("horizontal_stairs")),
    ]

    width = max(len(label) for label, _ in rows) + 2
    lines = ["", "Build summary", RULE]
    for label, value in rows:
        lines.append(f"{label:<{width}}{value}".rstrip() if label else "")
    lines.append(RULE)
    if s["mod_blocks_dropped"]:
        lines.append(f"{s['mod_blocks_dropped']} block(s) disabled by variant config")

    unsupported = s.get("unsupported", {})
    families = s.get("unsupported_families", {})
    deferred_ids = s.get("unsupported_block_ids", {})
    if unsupported or families:
        lines.append("")
        lines.append(f"Disabled on Minecraft {s['mc_version']} "
                     f"(policy: {s['policy']})")
        # Every block gets exactly one reason, taken from the structures that
        # recorded it. Nothing is inferred from a block's name.
        deferred_all = {bid for ids in deferred_ids.values() for bid in ids}
        for family, reason in sorted(families.items()):
            ids = deferred_ids.get(family, [])
            missing_behaviour = reason.startswith("behaviour ")
            label = ("Behaviour not implemented" if missing_behaviour
                     else "Insufficient carrier capacity")
            lines.append(f"  {label}: {len(ids)} block(s) ({family} family)")
            for name in ids[:3]:
                lines.append(f"    {name}")
            if len(ids) > 3:
                lines.append(f"    ... and {len(ids) - 3} more")
            for line in textwrap.wrap(reason, 72):
                lines.append(f"      {line}")
            if missing_behaviour:
                lines.append("      Content stays canonical; re-enabled when the cmb plugin")
                lines.append("      implements the behaviour.")
            else:
                lines.append("      Content stays canonical; re-enabled automatically if a")
                lines.append("      future version offers collision-safe carriers.")
        exhausted = sorted(set(unsupported) - deferred_all)
        if exhausted:
            lines.append(f"  No carrier state available: {len(exhausted)} block(s)")
            for name in exhausted[:3]:
                lines.append(f"    {name}")
            if len(exhausted) > 3:
                lines.append(f"    ... and {len(exhausted) - 3} more")

    inv = s.get("carrier_invariant", {})
    if inv:
        lines.append("")
        lines.append("Carrier validation")
        lines.append(RULE)
        lines.append(f"Allocated carrier blocks: {inv['allocated_carrier_blocks']}")
        lines.append(f"Converted carrier blocks: {inv['converted_carrier_blocks']}")
        lines.append(f"Missing:                    {inv['missing']}")
        lines.append("")
        lines.append(f"Assigned states:            {inv['state_uniqueness']['assigned_states']}")
        lines.append(f"Distinct states:            {inv['state_uniqueness']['distinct_states']}")
        lines.append(f"Duplicated:                 {inv['state_uniqueness']['duplicates']}")
        lines.append(f"Unparseable:                {inv['state_uniqueness'].get('invalid', 0)}")
        lines.append(f"Foreign-owned:              {inv['state_uniqueness'].get('foreign', 0)}")
        lines.append(f"Compared by:                {inv['state_uniqueness'].get('identity', '?')}")
        lines.append("")
        lines.append(inv.get("status", "?"))
        lines.append(RULE)

    claimed = s.get("externally_claimed", [])
    if claimed:
        lines.append("")
        lines.append(f"Carrier allocation - Minecraft {s['mc_version']}")
        lines.append(RULE)
        lines.append(f"{'Already claimed by another pack:':<34}{len(claimed)}")
        owners = s.get("claimed_by", {})
        for state in claimed[:6]:
            lines.append(f"  {state}  <- {owners.get(state, '?')}")
        if len(claimed) > 6:
            lines.append(f"  ... and {len(claimed) - 6} more")

    lines.append("")
    lines.append(f"Carrier capacity on Minecraft {s['mc_version']}")
    lines.append(RULE)
    lines.append(f"{'family':<10}{'carriers':>10}{'safe':>10}{'capacity':>10}"
                 f"{'assigned':>10}{'avail':>8}{'rejected':>10}")
    for name, cap in s.get("family_capacity", {}).items():
        canonical = cap.get("carriers_in_canonical_db")
        shown = f"{cap['carriers_available']}"
        if canonical and canonical != cap["carriers_available"]:
            shown += f" (of {canonical})"
        capacity = cap.get("collision_safe_capacity_blocks", 0)
        rejected = cap.get("rejected_not_collision_safe", 0)
        lines.append(f"{name:<10}{shown:>10}{capacity:>10}"
                     f"{cap.get('capacity_states', 0):>8}"
                     f"{cap.get('assigned_states', 0):>8}"
                     f"{cap.get('available_states', 0):>8}"
                     + (f"{rejected:>8}" if rejected else ""))
    lines.append(RULE)
    lines.append("")
    return "\n".join(lines)


def startup_banner(version: str, summary: str) -> str:
    """The `${startup}` payload for the companion plugin's startups.txt.

    Mirrors GriefPrevention3D's approach: startups.txt holds the ASCII art and
    colour codes, and this returns the locale-independent detail block that gets
    substituted into the `${startup}` placeholder.
    """
    cfg = json.loads(json.dumps(summary))["config"]
    onoff = {True: "&aON&r", False: "&cOFF&r"}
    rows = [
        ("Terracotta variants", onoff[cfg["disable_terracotta_variants"]]),
        ("Concrete variants", onoff[cfg["disable_concrete_variants"]]),
        ("Vertical slabs", onoff[cfg["vertical_slabs"]]),
        ("Horizontal stairs", onoff[cfg["horizontal_stairs_cmb"] or cfg["horizontal_stairs_vanilla"]]),
        ("", ""),
        ("Blocks", f"&f{summary['mod_blocks_enabled']}&7/{summary['mod_blocks_total']}"),
        ("CraftEngine blocks", f"&f{summary['mod_blocks_enabled'] + summary['ce_vanilla_converted']}"),
        ("Internal states", f"&f{summary['ce_internal_states']}"),
        ("Recipes", f"&f{summary['recipes']}"),
        ("Loot tables", f"&f{summary['loot_tables']}"),
    ]
    width = max(len(label) for label, _ in rows)
    out = []
    for label, value in rows:
        out.append(f"&7{label:<{width}}  {value}" if label else "")
    return "&8" + ("\n" + "&8").join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mod", type=pathlib.Path, required=True)
    parser.add_argument("--out", type=pathlib.Path, required=True,
                        help="destination pack directory")
    parser.add_argument("--check", action="store_true",
                        help="validate and print the summary, write nothing")
    # Every toggle defaults to None so that tools/build-config.yml decides unless it
    # is explicitly overridden on the command line.
    parser.add_argument("--terracotta", choices=["on", "off"], default=None)
    parser.add_argument("--concrete", choices=["on", "off"], default=None)
    parser.add_argument("--vertical-slabs", choices=["on", "off"], default=None)
    parser.add_argument("--horizontal-stairs", choices=["on", "off"], default=None)
    parser.add_argument("--max-internal-states", type=int, default=None,
                        help="override craftengine.max-internal-states")
    parser.add_argument("--reserved-states", type=int, default=None,
                        help="override craftengine.reserved-states")
    parser.add_argument("--mc-version", default=str(vc.VERIFIED_AGAINST),
                        help="target Minecraft version for the allocation report; "
                             "the canonical carrier database itself is not filtered")
    parser.add_argument("--unsupported-content", choices=UNSUPPORTED_POLICIES,
                        default="disable",
                        help="what to do when a block cannot be represented on the "
                             "target version: disable it, or fail the build")
    parser.add_argument("--ce-resources", type=pathlib.Path,
                        help="CraftEngine resources directory; other packs' already-bound "
                             "carrier states are read from it and excluded")
    parser.add_argument("--build-config", type=pathlib.Path,
                        default=pathlib.Path(__file__).resolve().parent / "build-config.yml")
    args = parser.parse_args()

    cfg, budget = load_build_config(args.build_config)

    def override(value, current, invert=False):
        if value is None:
            return current
        return (value == "off") if invert else (value == "on")

    # Only the command-line overrides change; every other setting carries over from the
    # build config. Rebuilding the config field by field dropped any field it forgot
    # (viabackwards was silently false from the command line).
    overrides: dict = {
        "disable_terracotta_variants": override(args.terracotta,
                                                cfg.disable_terracotta_variants, invert=True),
        "disable_concrete_variants": override(args.concrete,
                                              cfg.disable_concrete_variants, invert=True),
    }
    if args.vertical_slabs is not None:
        on = args.vertical_slabs == "on"
        overrides.update(vertical_slabs=on, vertical_slabs_cmb=on, vertical_slabs_vanilla=on)
    if args.horizontal_stairs is not None:
        on = args.horizontal_stairs == "on"
        overrides.update(horizontal_stairs_cmb=on, horizontal_stairs_vanilla=on)
    cfg = dataclasses.replace(cfg, **overrides)
    if args.max_internal_states is not None:
        budget.max_internal_states = args.max_internal_states
    if args.reserved_states is not None:
        budget.reserved_states = args.reserved_states

    try:
        build = build_pack(args.mod, cfg, budget, args.mc_version,
                           args.unsupported_content,
                           args.ce_resources or None)
    except (BuildError, ir.InvariantError) as exc:
        print(f"\nBUILD FAILED\n{RULE}\n{exc}\n{RULE}", file=sys.stderr)
        print("\nNothing was written.", file=sys.stderr)
        return 1

    print(render_summary(build))

    if args.check:
        print("--check: nothing written.")
        return 0

    # Validation passed; only now do we touch the filesystem.
    if args.out.exists():
        shutil.rmtree(args.out)

    for rel, body in sorted(build.files.items()):
        write_text(args.out / rel, body)
    for src, rel in sorted(build.copies, key=lambda pair: str(pair[1])):
        dest = args.out / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        if src.is_dir():
            ignore = shutil.ignore_patterns(
                *(build.copy_ignores.get(rel, set()) or ()))
            shutil.copytree(src, dest, dirs_exist_ok=True, ignore=ignore)
        else:
            shutil.copy2(src, dest)

    manifest = {
        "pack": PACK_VERSION,
        "mod": str(args.mod),
        "summary": build.stats,
    }
    write_json(args.out / "manifest.json", manifest)

    print(f"\nWrote {len(build.files)} file(s) and {len(build.copies)} asset tree(s)")
    print(f"  pack:     {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
