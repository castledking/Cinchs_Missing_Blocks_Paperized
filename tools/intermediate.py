#!/usr/bin/env python3
"""The intermediate representation between the mod and CraftEngine.

Three artifacts, deliberately separate, because they answer three different
questions and have three different lifetimes:

    content.json      what Paperized is            canonical, version-independent
    carriers.json     what Paperized can use       canonical candidate pool
    allocation.json   what this server is using    runtime, version-specific

    canonical data
         |
    +----+---------+
    v              v
 generator      Paperized runtime
    |              |
 resource pack   live registry
    |              |
    +------+-------+
           v
   runtime allocation
           |
           v
    CraftEngine

The generator must not bake an allocation into an artifact that is supposed to work
across server versions, so it emits `content` and `carriers` and treats allocation as
a separate step that any consumer can run. The generator runs it too, but only so it
can print a capacity report and render a preview of the CraftEngine YAML - that
rendering is a debugging aid, not the shipped artifact.

An appearance in `content.json` never names a concrete vanilla block. It names a
*carrier requirement*: the family, the properties the carrier must agree with, and
the model to draw. Turning that into a real vanilla state is allocation's job.
"""

from __future__ import annotations

import collections
import dataclasses
import json
import pathlib

import multipart as mp
import vanilla_carriers as vc

FORMAT = 1


# --------------------------------------------------------------------------- #
# content.json - canonical
# --------------------------------------------------------------------------- #

@dataclasses.dataclass
class PropertySpec:
    name: str
    type: str
    default: str
    values: list[str] | None = None
    range: tuple[int, int] | None = None

    def to_json(self) -> dict:
        # `name` is required here: content.json lists properties rather than keying
        # them, so the name is their only identity. The rendered CraftEngine YAML is
        # a different code path that keys by name and does not want the field.
        out: dict = {"name": self.name, "type": self.type, "default": self.default}
        if self.values is not None:
            out["values"] = list(self.values)
        if self.range is not None:
            out["range"] = [self.range[0], self.range[1]]
        return out

    @classmethod
    def from_json(cls, data: dict) -> "PropertySpec":
        return cls(
            name=data["name"], type=data["type"], default=data["default"],
            values=data.get("values"),
            range=tuple(data["range"]) if data.get("range") else None,
        )


@dataclasses.dataclass
class ModelRef:
    path: str
    x: int = 0
    y: int = 0
    z: int = 0
    uvlock: bool = False
    models: list["ModelRef"] | None = None

    def to_json(self) -> dict:
        if self.models is not None:
            return {"models": [m.to_json() for m in self.models]}
        out: dict = {"path": self.path}
        for key in ("x", "y", "z"):
            if getattr(self, key):
                out[key] = getattr(self, key)
        if self.uvlock:
            out["uvlock"] = True
        return out

    @classmethod
    def from_json(cls, data: dict) -> "ModelRef":
        if "models" in data:
            return cls(path="", models=[cls.from_json(m) for m in data["models"]])
        return cls(path=data["path"], x=data.get("x", 0), y=data.get("y", 0),
                   z=data.get("z", 0), uvlock=data.get("uvlock", False))


@dataclasses.dataclass
class Appearance:
    """One drawable state, and the carrier it needs.

    ``carrier`` is a requirement, not an assignment: family plus the property
    values the vanilla carrier must agree with so that collision and sound match
    what we drew. Allocation turns it into a concrete vanilla state.
    """

    key: str
    properties: dict[str, str]
    model: ModelRef
    carrier_family: str | None = None
    carrier_properties: dict[str, str] | None = None
    settings: dict = dataclasses.field(default_factory=dict)
    generated_model: str | None = None

    def to_json(self) -> dict:
        out: dict = {
            "key": self.key,
            "properties": dict(sorted(self.properties.items())),
            "model": self.model.to_json(),
        }
        if self.carrier_family:
            out["carrier"] = {
                "family": self.carrier_family,
                "properties": dict(sorted((self.carrier_properties or {}).items())),
            }
        if self.settings:
            out["settings"] = dict(sorted(self.settings.items()))
        if self.generated_model:
            out["generated_model"] = self.generated_model
        return out

    @classmethod
    def from_json(cls, data: dict) -> "Appearance":
        carrier = data.get("carrier") or {}
        return cls(
            key=data["key"],
            properties=data["properties"],
            model=ModelRef.from_json(data["model"]),
            carrier_family=carrier.get("family"),
            carrier_properties=carrier.get("properties"),
            settings=data.get("settings", {}),
            generated_model=data.get("generated_model"),
        )


@dataclasses.dataclass
class ContentBlock:
    id: str
    family: str
    properties: list[PropertySpec]
    appearances: list[Appearance]
    behaviors: list[dict]
    settings: dict
    group: str
    #: CraftEngine loot for this block, translated from the mod's loot table. None for
    #: blocks that have no mod loot table, such as converted vanilla carriers.
    loot: dict | None = None

    def to_json(self) -> dict:
        out = {
            "family": self.family,
            "group": self.group,
            "properties": [p.to_json() for p in self.properties],
            "appearances": [a.to_json() for a in self.appearances],
            "behaviors": self.behaviors,
            "settings": dict(sorted(self.settings.items())),
        }
        if self.loot is not None:
            out["loot"] = self.loot
        return out

    @classmethod
    def from_json(cls, block_id: str, data: dict) -> "ContentBlock":
        return cls(
            id=block_id,
            family=data["family"],
            group=data.get("group", ""),
            properties=[PropertySpec.from_json(p) for p in data["properties"]],
            appearances=[Appearance.from_json(a) for a in data["appearances"]],
            behaviors=data.get("behaviors", []),
            settings=data.get("settings", {}),
            loot=data.get("loot"),
        )


@dataclasses.dataclass
class Content:
    format: int
    mod: str
    mod_version: str
    blocks: dict[str, ContentBlock]
    items: dict[str, dict]
    categories: list[dict]
    features: dict
    assets: list[str]

    def to_json(self) -> dict:
        return {
            "format": self.format,
            "mod": self.mod,
            "mod_version": self.mod_version,
            "note": (
                "Canonical content definition. Version-independent: a block listed "
                "here exists in Paperized even when a given server cannot enable it. "
                "See allocation.json for what a particular server actually uses."
            ),
            "blocks": {k: v.to_json() for k, v in sorted(self.blocks.items())},
            "items": dict(sorted(self.items.items())),
            "categories": self.categories,
            "features": dict(sorted(self.features.items())),
            "assets": sorted(self.assets),
        }

    @classmethod
    def from_json(cls, data: dict) -> "Content":
        return cls(
            format=data["format"],
            mod=data["mod"],
            mod_version=data["mod_version"],
            blocks={k: ContentBlock.from_json(k, v)
                    for k, v in data["blocks"].items()},
            items=data.get("items", {}),
            categories=data.get("categories", []),
            features=data.get("features", {}),
            assets=data.get("assets", []),
        )


# --------------------------------------------------------------------------- #
# carriers.json - canonical
# --------------------------------------------------------------------------- #

def carriers_to_json() -> dict:
    """The complete candidate pool, with no version filter applied.

    Deliberately carries no `since` field that anything branches on. Availability
    is answered by asking a live block registry, which is exact; a version table
    would only ever be a guess that needs maintaining.
    """
    families = {}
    for name, spec in vc.FAMILIES.items():
        families[name] = {
            "properties_all": [
                {"name": n, "values": list(v)} for n, v in spec.all_properties
            ],
            "properties_reserved": [
                {"name": n, "values": list(v)} for n, v in spec.reserve_props
            ],
            "value_aliases": {k: dict(sorted(v.items()))
                              for k, v in sorted(spec.value_aliases.items())},
            "prefer": dict(sorted(spec.prefer.items())),
            "states_per_carrier": spec.states_per_vanilla_block,
            "states_per_block": spec.states_per_custom_block,
            "candidates": [f"minecraft:{b}" for b in spec.blocks],
        }
    return {
        "format": FORMAT,
        "note": (
            "Canonical candidate pool. A candidate is a vanilla block whose states "
            "can act as the collision/shape carrier for a Paperized block. Nothing "
            "here is filtered by version; the runtime intersects this with the "
            "server's own block registry."
        ),
        "verified_against": str(vc.VERIFIED_AGAINST),
        "families": families,
        # Candidates only: which of their states are lendable depends on the
        # block_state_mappings active on the server (block_mappings.py).
        "cubes": [f"minecraft:{c}" for c in vc.SOLID_CARRIERS],
        "cube_carriers_needed_per_state": vc.PILLAR_ROTATIONS,
    }


# --------------------------------------------------------------------------- #
# allocation.json - runtime
# --------------------------------------------------------------------------- #

@dataclasses.dataclass
class Allocation:
    format: int
    mc_version: str
    registry: list[str]
    assigned: dict[str, list[str]]
    converted: dict[str, list[str]]
    unsupported: dict[str, str]
    policy: str
    families: dict[str, dict]
    externally_claimed: list[str] = dataclasses.field(default_factory=list)
    claimed_by: dict[str, str] = dataclasses.field(default_factory=dict)
    #: family -> reason, for families whose content is canonical but whose carrier
    #: requirement cannot be met on this version.
    unsupported_families: dict[str, str] = dataclasses.field(default_factory=dict)
    #: family -> the block ids deferred, so a report can name them
    unsupported_block_ids: dict[str, list[str]] = dataclasses.field(default_factory=dict)

    def converted_block_ids(self) -> set[str]:
        """Every vanilla block that must be registered as a CraftEngine block."""
        out: set[str] = set()
        for names in self.converted.values():
            out.update(names)
        return out

    def to_json(self) -> dict:
        return {
            "format": self.format,
            "mc_version": self.mc_version,
            "policy": self.policy,
            "note": (
                "Runtime-specific. Which vanilla carrier state backs each Paperized "
                "block state on this particular server. Regenerated whenever the "
                "server version or the enabled feature set changes."
            ),
            "registry": sorted(self.registry),
            "assigned": {k: list(v) for k, v in sorted(self.assigned.items())},
            "converted": {k: sorted(v) for k, v in sorted(self.converted.items())},
            "unsupported": dict(sorted(self.unsupported.items())),
            "families": self.families,
            "externally_claimed": list(self.externally_claimed),
            "claimed_by": dict(sorted(self.claimed_by.items())),
            "unsupported_families": dict(sorted(self.unsupported_families.items())),
            "unsupported_block_ids": {k: sorted(v) for k, v
                                      in sorted(self.unsupported_block_ids.items())},
        }

    @classmethod
    def from_json(cls, data: dict) -> "Allocation":
        return cls(
            format=data["format"],
            mc_version=data["mc_version"],
            registry=data.get("registry", []),
            assigned=data.get("assigned", {}),
            converted=data.get("converted", {}),
            unsupported=data.get("unsupported", {}),
            policy=data.get("policy", "disable"),
            families=data.get("families", {}),
        )


class InvariantError(RuntimeError):
    """A structural rule about the pack was violated. Always a build failure."""


class SelectionError(InvariantError):
    """The build config asks for something the safe carriers cannot provide."""


def scan_claimed_states(ce_resources: pathlib.Path,
                        exclude_pack: str | None = None,
                        cache: pathlib.Path | None = None) -> dict[str, str]:
    """Vanilla states other CraftEngine packs already bind.

    CraftEngine treats a vanilla block state as a single-bind visual resource, so a
    state another pack holds is unavailable even though it exists. Ownership cannot be
    asked for at the moment we need it: at plugin load time CraftEngine has parsed no
    pack, and ``blockManager().blockOverrides()`` is only populated once it has. So the
    pre-parse answer comes from two sources on disk:

      configuration - an explicit ``state:`` in an enabled pack's YAML
      auto_state     - CraftEngine's own persisted auto_state assignments

    Both are kept distinct in the returned map so a compatibility failure names where
    the claim came from.

    Returns ``{state: "pack (source)"}``.
    """
    owners: dict[str, str] = {}
    if ce_resources.is_dir():
        import yaml
        for pack in sorted(p for p in ce_resources.iterdir() if p.is_dir()):
            if pack.name == exclude_pack:
                continue
            if not _pack_enabled(pack):
                continue
            for path in sorted((pack / "configuration").rglob("*.yml")):
                try:
                    data = yaml.safe_load(path.read_text())
                except Exception:
                    # A pack we cannot parse is not a reason to guess; treat it as
                    # unclaimed here and let the post-parse verifier catch the gap.
                    continue
                for state in _explicit_states(data):
                    owners.setdefault(state, f"{pack.name} (configuration)")

    if cache and cache.is_file():
        try:
            cached = json.loads(cache.read_text())
        except json.JSONDecodeError:
            cached = {}
        for appearance, state in sorted(cached.items()):
            if isinstance(state, str) and state.startswith("minecraft:"):
                owners.setdefault(state, f"{appearance.split('[')[0]} (auto_state cache)")
    return owners


def _pack_enabled(pack: pathlib.Path) -> bool:
    meta = pack / "pack.yml"
    if not meta.is_file():
        return True
    try:
        import yaml
        data = yaml.safe_load(meta.read_text()) or {}
    except Exception:
        return True
    return bool(data.get("enable", True))


def _explicit_states(node) -> list[str]:
    """Every ``state:`` string in a parsed configuration document."""
    found: list[str] = []
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "state" and isinstance(value, str) and value.startswith("minecraft:"):
                found.append(value)
            else:
                found.extend(_explicit_states(value))
    elif isinstance(node, list):
        for item in node:
            found.extend(_explicit_states(item))
    return found


#: The carrier of a cube or pillar appearance: CraftEngine's `auto_state: solid`, which
#: picks a free note block or mushroom block state on the server it loads on. Never a
#: concrete state - see allocate(auto_cubes).
AUTO_SOLID = "auto:solid"


def is_auto(state: str) -> bool:
    return state.startswith("auto:")


def allocate(content: Content, mc_version: str,
             registry: set[str] | None = None,
             policy: str = "disable",
             claimed: set[str] | None = None,
             freed: set[str] | None = None,
             canonical=None,
             unimplemented: dict[str, str] | None = None,
             selection: dict[str, list[str]] | None = None,
             auto_cubes: bool = True) -> Allocation:
    """Turn carrier *requirements* into concrete vanilla states.

    ``registry`` is the set of ``minecraft:*`` ids the target server actually has.
    Pass None to plan against the whole canonical database, which is what the
    generator does for its capacity report.

    ``freed`` is the canonical set of states a block_state_mapping frees from vanilla
    use (block_mappings.py). Full-cube carriers are drawn only from it; without it the
    cube pool is empty and cubes and pillars are deferred rather than served on
    states real vanilla blocks can show. The shape-family pools use the same set.

    ``unimplemented`` maps a content family to why it cannot work at runtime (its
    ``cmb:`` behaviour is not registered by the plugin). Such a family is deferred
    whatever its carrier capacity, so a carrier becoming available cannot ship a
    block that fails to load and leaves an item behind.

    ``selection`` maps a content family to the ordered block ids allowed to consume
    that family's scarce safe carriers (generate_pack.replacement_selection: none for
    stairs, slabs and panes, which are furniture).
    Only those are served, in that order; every other block of the family is
    deferred as "not selected". Asking for more than the safe carriers can serve is
    a SelectionError, never a silent cut.

    ``auto_cubes`` (the default) gives every cube and pillar appearance AUTO_SOLID
    instead of a state from the cube pool, and never defers them for capacity.
    CraftEngine then picks the state at load time, on the server the pack lands on,
    from the states its block_state_mappings free - avoiding every state another pack
    binds there. That is what lets one pack, built once, be correct on any server:
    the cube pool needed that server's claims at build time.
    """
    local = {n.removeprefix("minecraft:") for n in registry} if registry else None

    pools = {name: vc.CarrierPool.build(spec, local, claimed, freed, canonical)
             for name, spec in vc.FAMILIES.items()}
    cube_pool = vc.CubeCarrierPool.build(local, claimed, freed, canonical)

    # Snapshot the pools once, before any state is handed out. The capacity
    # calculation and the family report must read the same numbers: reading
    # pool.available after assignment starts gives a smaller figure and makes a
    # family look far more constrained than it is.
    def _capacity(pool) -> int:
        """Lendable capacity before anything is assigned."""
        if hasattr(pool, "capacity_states"):
            return pool.capacity_states
        return sum(len(v) for v in pool.lendable.values())

    pool_spare = _capacity
    snapshot: dict[str, int] = {name: _capacity(pool) for name, pool in pools.items()}
    snapshot["cube"] = _capacity(cube_pool)

    assigned: dict[str, list[str]] = collections.defaultdict(list)
    converted: dict[str, list[str]] = collections.defaultdict(list)
    #: block id -> reason a *specific* block could not be placed (carrier exhausted)
    unsupported: dict[str, str] = {}
    #: family -> reason, for a family deferred as a whole
    unsupported_families: dict[str, str] = {}
    #: family -> the specific block ids deferred as part of that family
    unsupported_block_ids: dict[str, list[str]] = {}

    # Demand is decided per family before anything is handed out, so a family that
    # cannot be served is deferred whole rather than partially. Six arbitrary cubes
    # vanishing so thirteen pillars survive is a far stranger compatibility boundary
    # than deferring one coherent family, and a deferred family stays in the
    # canonical content database and returns automatically on a version with more
    # capacity.
    disabled_families: set[str] = set()

    def defer(family: str, pool, ids: list, per_block: int, reason: str,
              capacity: int | None = None) -> None:
        if capacity is None:
            capacity = pool_spare(pool)
        limit = capacity // per_block
        excess = len(ids) - limit
        if excess <= 0:
            return
        # Defer exactly `excess` blocks. Slicing from `excess` to the end would
        # keep only the first `excess` and defer the other len(ids) - excess, which
        # silently retained a handful of blocks and starved every family.
        deferred = sorted(ids)[:excess]
        keep = set(ids) - set(deferred)
        for block_id in ids:
            if block_id not in keep:
                unsupported[block_id] = f"{family} family deferred: {reason}"
        disabled_families.add(family)
        unsupported_families[family] = reason
        unsupported_block_ids[family] = deferred

    cube_members = [b.id for b in content.blocks.values()
                    if b.family in ("cube", "pillar")]
    cube_demand = sum(vc.PILLAR_ROTATIONS if b.rsplit(":", 1)[-1].endswith("_pillar")
                      else 1 for b in cube_members)
    cube_capacity = snapshot["cube"]
    if not auto_cubes and cube_capacity < cube_demand:
        # Cubes need one state each and are served first, so pillars get only what
        # the cubes leave. Sizing the deferral against the whole pool found room for
        # every pillar, deferred none, and then cubes failed one by one with "out of
        # full-cube carriers" - the partial failure this is meant to prevent.
        pillar_ids = [b.id for b in content.blocks.values() if b.family == "pillar"]
        plain_cubes = cube_demand - vc.PILLAR_ROTATIONS * len(pillar_ids)
        defer("pillar", cube_pool, pillar_ids, vc.PILLAR_ROTATIONS,
              f"carrier capacity: the cube pool offers {cube_capacity} lendable "
              f"full-cube states on this version; the cube and pillar families need "
              f"{cube_demand} ({plain_cubes} cubes plus {len(pillar_ids)} pillars x "
              f"{vc.PILLAR_ROTATIONS} rotations)",
              capacity=max(cube_capacity - plain_cubes, 0))

    # The carrier selection decides which blocks get the scarce safe carriers. Checked against the capacity the pools actually offer, after
    # other packs' claims, so a config written for one server cannot silently
    # serve fewer blocks on another.
    for family, chosen in sorted((selection or {}).items()):
        pool_name = FAMILY_CARRIER_POOL.get(family, family)
        pool = pools.get(pool_name)
        if pool is None:
            raise SelectionError(f"vanilla-replacements: '{family}' has no carrier pool")
        per_block = pool.family.states_per_custom_block
        capacity = snapshot.get(pool_name, 0) // per_block
        if len(chosen) > capacity:
            claimed_by = sorted({s.split("[", 1)[0] for s in pool.externally_claimed})
            raise SelectionError(
                f"vanilla-replacements.{family} lists {len(chosen)} block(s), but only "
                f"{capacity} can be served on this server: {snapshot.get(pool_name, 0)} "
                f"safe carrier states / {per_block} per block"
                + (f". Carriers claimed by another pack: {', '.join(claimed_by)}"
                   if claimed_by else "")
                + ". Remove entries, or free the claimed carriers.")
        unselected = sorted(b.id for b in content.blocks.values()
                            if b.family == family and b.id not in chosen)
        reason = ("served as furniture (features.furniture-fallback), never on a "
                  "carrier state")
        for block_id in unselected:
            unsupported[block_id] = reason
        disabled_families.add(family)
        unsupported_families[family] = reason
        unsupported_block_ids[family] = unselected

    # A family that has carriers but no runtime behaviour (cmb:slab is not registered
    # by the plugin) cannot ship any block: each would fail to load and leave its item
    # behind. Defer the whole family for that reason, before capacity deferral can
    # serve the few blocks that fit. A family with no safe carriers at all keeps the
    # more fundamental carrier reason, reported below.
    for family, reason in sorted((unimplemented or {}).items()):
        if snapshot.get(FAMILY_CARRIER_POOL.get(family, family), 0) == 0:
            continue
        ids = [b.id for b in content.blocks.values() if b.family == family]
        if ids:
            defer(family, None, ids, 1, reason, capacity=0)

    for name, pool in pools.items():
        members = [b.id for b in content.blocks.values()
                   if b.family == name and name not in disabled_families]
        if not members:
            continue
        spec = pool.family
        per_block = spec.states_per_custom_block
        capacity = snapshot[name]
        limit = capacity // per_block
        if len(members) <= limit:
            continue
        constraints = ", ".join(f"{k}={'|'.join(v)}"
                                for k, v in sorted(spec.required_carrier_properties.items()))
        defer(name, pool, members, per_block,
              f"collision-safe carrier capacity: {len(members)} block(s) need "
              f"{per_block} state(s) each with the projection "
              f"({' x '.join(p[0] for p in spec.used_properties)})"
              + (f" and carrier constraint {constraints}" if constraints else "")
              + f"; only {limit} carrier(s) on this version are collision-safe "
              f"({capacity} collision-safe states / {per_block} per block). "
              f"{len(members) - limit} deferred rather than served with a "
              f"geometrically incompatible carrier.")

    def pool_for(family: str) -> vc.CarrierPool | None:
        return pools.get(FAMILY_CARRIER_POOL.get(family, family))

    cube_cursor = 0
    cubes_used: list[str] = []

    def next_cube() -> str | None:
        if auto_cubes:
            return AUTO_SOLID
        carrier = cube_pool.take()
        if carrier is not None:
            cubes_used.append(carrier)
        return carrier

    deferred_ids = {bid for ids in unsupported_block_ids.values() for bid in ids}

    # Allocation is transactional per block. A block that needs eight states and can
    # only be given seven must not leave seven consumed, or the pool's real state
    # diverges from what is reported and every later block in the family is starved.
    # The cube pool hands states out by popping them, so its lendable lists and
    # cubes_used are part of the snapshot too; restoring only the counters leaked
    # every state a failed block had drawn.
    def pool_state():
        return {name: (dict(pool.used), set(pool.touched)) for name, pool in pools.items()}, \
               (dict(cube_pool.used), list(cube_pool.touched),
                {b: list(v) for b, v in cube_pool.lendable.items()}, len(cubes_used))

    def restore(snapshot):
        pool_part, cube_part = snapshot
        for name, pool in pools.items():
            used, touched = pool_part[name]
            pool.used = dict(used)
            pool.touched = set(touched)
        cube_pool.used = dict(cube_part[0])
        cube_pool.touched = list(cube_part[1])
        cube_pool.lendable = {b: list(v) for b, v in cube_part[2].items()}
        del cubes_used[cube_part[3]:]

    # Selected blocks are assigned in config order, so the first entry gets the first
    # carrier; everything else keeps the stable id order.
    order = {block_id: i for chosen in (selection or {}).values()
             for i, block_id in enumerate(chosen)}
    for block_id, block in sorted(content.blocks.items(),
                                  key=lambda kv: (order.get(kv[0], len(order)), kv[0])):
        if block_id in deferred_ids:
            # Canonical content is left intact; this block is simply not enabled on
            # this version because its family ran out of collision-safe carriers.
            continue
        if block.family in disabled_families and block_id not in content.blocks:
            continue
        pin = FAMILY_PROJECTION_PIN.get(block.family, {})
        pool = pool_for(block.family)
        resolved: list[str] = []
        saved = pool_state()
        for appearance in block.appearances:
            if appearance.carrier_family is None:
                # Cube and pillar blocks are carried by full cubes. One carrier per
                # appearance: a pillar's three appearances already are its three
                # rotations, so it does not need three carriers each.
                carrier = next_cube()
                if carrier is None:
                    unsupported[block_id] = "out of full-cube carriers"
                    break
                resolved.append(carrier)
                continue

            want = dict(appearance.carrier_properties or {})
            want.update(pin)
            pool = pools.get(appearance.carrier_family)
            if pool is None:
                # A family with no pool of its own (pillars) rides full cubes.
                carrier = next_cube()
                if carrier is None:
                    unsupported[block_id] = "out of full-cube carriers"
                    break
                resolved.append(carrier)
                continue
            want = {k: v for k, v in want.items()
                    if k in {n for n, _ in pool.family.reserve_props}}
            if appearance.carrier_family == "wall":
                state = pool.vanilla_state_nearest(want)
            else:
                state = pool.vanilla_state(want)
            if state is None:
                unsupported[block_id] = (
                    f"no carrier for [{','.join(f'{k}={v}' for k, v in sorted(want.items()))}]")
                break
            resolved.append(state)
        else:
            assigned[block_id] = resolved
            continue
        # A projection ran dry partway through this block. Return everything it drew
        # before recording the failure, otherwise the pool ends up holding states
        # that no block owns, and every later block in the family is starved.
        restore(saved)
        assigned.pop(block_id, None)

    # Second pass: every vanilla block whose states we allocated has to be a
    # CraftEngine block itself, and it claims the states that were held back for it
    # during the first pass. Without this a player placing the real vanilla block
    # would have it rendered as Paperized content.
    for name, pool in sorted(pools.items()):
        taken: list[str] = []
        for vanilla in pool.converted:
            for entry in pool.reserved_for.get(vanilla, []):
                taken.append(f"{entry[0] and 'minecraft:' + entry[0]}[{entry[1]}]")
        converted[name] = [f"minecraft:{b}" for b in pool.converted]
        for vanilla in pool.converted:
            assigned[f"minecraft:{vanilla}"] = [
                f"minecraft:{e[0]}[{e[1]}]" for e in pool.reserved_for.get(vanilla, [])
            ]

    # Full-cube carriers have no properties, so their whole registry entry is the
    # single state they need. They are carriers like any other and have to be
    # converted too.
    if cubes_used:
        # Only a cube carrier whose own state was left untouched can be converted.
        # A block that already lent its reserved state cannot render as itself.
        convertible = set(cube_pool.convertible())
        # Compare on the block id: cubes_used entries are full state strings such as
        # "minecraft:barrel[facing=0,open=1]", while convertible() lists block ids.
        used = sorted({c.split("[", 1)[0] for c in cubes_used} & convertible)
        converted["cube"] = used
        for carrier in used:
            assigned[carrier] = [carrier]

    externally_claimed = sorted({s for p in [*pools.values(), cube_pool]
                                 for s in p.externally_claimed})
    families = {}
    for name, pool in sorted(pools.items()):
        spec = pool.family
        per_block = spec.states_per_custom_block
        available = len(pool.candidates)
        # Capacity must come from the *filtered* pool, not the raw family. A state
        # that exists and is unique is still unusable if it is not collision-safe:
        # stairs reject every shape except straight, so the 3,584 corner states are
        # capacity that must not be counted.
        capacity = snapshot.get(name, 0)
        states_per_carrier = (spec.states_per_vanilla_block
                              - spec.states_per_custom_block)
        families[name] = {
            "carriers_available": available,
            "carriers_in_canonical_db": len(spec.blocks),
            "states_per_carrier": spec.states_per_vanilla_block,
            "states_per_block": per_block,
            "collision_safe_capacity_blocks": capacity // per_block,
            "rejected_not_collision_safe": len(pool.rejected),
            "capacity_states": capacity,
            "assigned_states": pool.assigned_states,
            "available_states": pool.available_states,
            "reserved_states": pool.reserved_states,
            "insufficient": capacity // per_block < sum(
                1 for b in content.blocks.values()
                if b.family == name or FAMILY_CARRIER_POOL.get(b.family) == name),
        }

    return Allocation(FORMAT, mc_version, sorted(local) if local else [],
                      dict(assigned), dict(converted), unsupported, policy, families,
                      externally_claimed,
                      {s: (claimed or {}).get(s, "unknown") for s in externally_claimed}
                      if isinstance(claimed, dict) else {},
                      unsupported_families,
                      {f: sorted(v) for f, v in unsupported_block_ids.items()})


def converted_vanilla_blocks(content: Content, allocation: Allocation,
                              vanilla_blockstates: pathlib.Path) -> list[ContentBlock]:
    """Build content definitions for the vanilla blocks we borrowed states from.

    A vanilla block whose states are allocated to Paperized has to become a
    CraftEngine block itself, otherwise a player placing the real thing lands in a
    state we own and it gets drawn as Paperized content. Its appearances reuse the
    states that were held back for it, and its models come from the vanilla
    blockstate so it keeps looking exactly like itself.
    """
    out: list[ContentBlock] = []
    for family, names in sorted(allocation.converted.items()):
        spec = vc.FAMILIES.get(family)
        for vanilla_id in names:
            name = vanilla_id.removeprefix("minecraft:")
            states = allocation.assigned.get(vanilla_id) or []
            if not states:
                raise InvariantError(
                    f"{vanilla_id} was drawn from the carrier pool but no states were "
                    f"reserved for it, so it cannot be emitted as a CraftEngine block")
            raw = mp.load_blockstate(vanilla_blockstates, name)
            if raw is None:
                raise InvariantError(
                    f"{vanilla_id} has states allocated to Paperized but its vanilla "
                    f"blockstate is not available at {vanilla_blockstates / (name + '.json')}, "
                    f"so it cannot be emitted as a CraftEngine block that looks like itself")
            # Colons are legal in an id but not in a resource-pack path.
            safe = vanilla_id.replace(":", "_")
            appearances: list[Appearance] = []
            for state in states:
                if "[" not in state:
                    # Full-cube carriers are never converted any more: they come
                    # from mapping-freed states, and the mapping protects the real
                    # block. A bare id reaching here is an allocator defect.
                    raise InvariantError(
                        f"{vanilla_id}: a propertyless converted carrier was "
                        f"requested, but full-cube carriers are not converted")

                props = dict(part.split("=", 1) for part in
                             state.split("[", 1)[1].rstrip("]").split(","))
                try:
                    # Vanilla walls and fences are multipart; bake every applicable
                    # part into one model so the converted carrier keeps looking
                    # exactly like the vanilla block in every state it can be in.
                    model = mp.flatten(vanilla_blockstates, raw, props)
                except mp.ResolutionError as exc:
                    raise InvariantError(
                        f"{vanilla_id} {props}: {exc}") from exc
                # The variant key names only the properties this block declares.
                # Every reserved state has the undeclared ones at their defaults,
                # so the declared ones identify it uniquely. Keying by the full
                # vanilla state string named properties the block never declared.
                held = [n for n, _ in spec.reserve_props] if spec else []
                appearances.append(Appearance(
                    key=",".join(f"{n}={props[n]}" for n in held),
                    properties=props,
                    model=ModelRef(path=f"minecraft:block/{name}"),
                    settings={"baked_model": model,
                              "baked_model_path":
                                  f"__carrier/{safe}_{len(appearances)}"}))
            out.append(ContentBlock(
                id=vanilla_id, family=family,
                # The converted carrier is the *vanilla* block, so it declares the
                # vanilla vocabulary (all_properties), not the Paperized projection:
                # a Paperized wall side is a boolean, a vanilla one none/low/tall.
                # The previous code declared reserve_props' value tuple itself as
                # the default and only value - "('north', 'east', 'south', 'west')".
                properties=([PropertySpec(name=n, type="string",
                                           default=dict(spec.all_properties)[n][0],
                                           values=list(dict(spec.all_properties)[n]))
                             for n, _ in spec.reserve_props] if spec else []),
                appearances=appearances,
                behaviors=[], settings={"converted_vanilla": True},
                group="Converted Vanilla Carriers"))
    return out


def _read_vanilla_blockstate(root: pathlib.Path, name: str) -> dict | None:
    path = root / f"{name}.json"
    if not path.is_file():
        return None
    return json.loads(path.read_text())


def _pick(raw: dict, props: dict[str, str]) -> dict | None:
    """Find the vanilla model for one state, tolerating multipart blockstates."""
    variants = raw.get("variants")
    if variants:
        for key, entry in variants.items():
            state = dict(part.split("=", 1) for part in key.split(",")) if key else {}
            if all(state.get(k) == v for k, v in props.items() if k in state):
                return entry if isinstance(entry, dict) else entry[0]
    for part in raw.get("multipart", []):
        when = part.get("when", {})
        if all(when.get(k) == v for k, v in props.items() if k in when):
            apply = part.get("apply")
            if isinstance(apply, dict):
                return apply
            if isinstance(apply, list) and apply:
                return apply[0]
    return None


def ir_appearance(key: str, props: dict[str, str], entry: dict) -> Appearance:
    model = entry["model"] if isinstance(entry, dict) else entry
    ref = ModelRef(path=model)
    if isinstance(entry, dict):
        for axis in ("x", "y", "z"):
            if entry.get(axis):
                setattr(ref, axis, entry[axis])
        ref.uvlock = bool(entry.get("uvlock"))
    return Appearance(key=key, properties=props, model=ref)


def validate_state_uniqueness(allocation: Allocation, identities=None,
                              claimed: dict[str, str] | None = None) -> dict:
    """No two appearances may share one carrier state.

    The block-level invariant above catches a carrier that was never converted. This
    catches the quieter version: the same carrier handed to two different blocks. It
    is what the live server surfaced for full cubes - a cube carrier has exactly one
    state, so a mod cube and the converted carrier block both claiming it means one of
    them cannot render.

    With ``identities`` (a ``block_identities.Identities`` for this version) states
    are compared as the *parser* sees them, not as strings. ``minecraft:barrel`` and
    ``minecraft:barrel[facing=north,open=false]`` are one state, and comparing
    strings let the converted barrel and a lent pillar state bind it twice. A state
    naming a property or value the block does not have is a failure too, since
    CraftEngine cannot parse it. With ``claimed`` it also refuses any state another
    pack binds, compared the same way.
    """
    owners: dict[str, list[str]] = {}
    invalid: list[str] = []
    for block_id, states in sorted(allocation.assigned.items()):
        for state in states:
            if is_auto(state):
                continue
            key = state
            if identities is not None:
                key, reason = identities.canonical(state)
                if key is None:
                    invalid.append(f"  {state}  ({block_id}): {reason}")
                    continue
            owners.setdefault(key, []).append(block_id)

    duplicates = {state: ids for state, ids in owners.items() if len(ids) > 1}
    foreign: dict[str, str] = {}
    if claimed:
        for state, owner in claimed.items():
            key = identities.canonical(state)[0] if identities is not None else state
            if key in owners:
                foreign[key] = owner
    report = {"assigned_states": sum(len(v) for v in allocation.assigned.values()),
              "distinct_states": len(owners),
              "duplicates": len(duplicates),
              "invalid": len(invalid),
              "foreign": len(foreign),
              "identity": (f"block report {identities.version}" if identities is not None
                           else "string comparison only (no block report for this "
                                "version)")}
    problems: list[str] = []
    if invalid:
        problems.append("States CraftEngine cannot parse:\n" + "\n".join(invalid[:6])
                        + ("\n  ..." if len(invalid) > 6 else ""))
    if duplicates:
        problems.append("One vanilla state cannot back two different models.\n"
                        + "\n".join(f"  {state}\n    claimed by: {', '.join(ids)}"
                                    for state, ids in list(duplicates.items())[:6])
                        + ("\n  ..." if len(duplicates) > 6 else ""))
    if foreign:
        problems.append("States another CraftEngine pack already binds:\n"
                        + "\n".join(f"  {state}  <- {owner}  (used by "
                                    f"{', '.join(owners[state])})"
                                    for state, owner in list(foreign.items())[:6])
                        + ("\n  ..." if len(foreign) > 6 else ""))
    if problems:
        raise InvariantError(
            f"carrier state uniqueness\n{'-' * 46}\n"
            f"Assigned states:  {report['assigned_states']}\n"
            f"Distinct states:  {report['distinct_states']}\n"
            f"Duplicated:       {len(duplicates)}\n"
            f"Unparseable:      {len(invalid)}\n"
            f"Foreign-owned:    {len(foreign)}\n\nFAIL\n\n"
            + "\n\n".join(problems))
    report["status"] = "PASS"
    return report


def validate_carrier_invariant(content: Content, allocation: Allocation,
                               freed: set[str] | None = None,
                               canonical=None) -> dict:
    """Every vanilla block whose states we allocated must become a CE block.

    This is the failure that lets a real vanilla block land in a state we own and
    get drawn as a Paperized block. It has to be a build failure, not a warning.

    Exempt: a carrier block whose every allocated state is freed by a
    block_state_mapping. The mapping already draws real blocks in those states as
    something else, so there is nothing for a conversion to protect.
    """
    allocated: set[str] = set()
    for states in allocation.converted.values():
        allocated.update(states)
    by_block: dict[str, list[str]] = {}
    for states in allocation.assigned.values():
        for state in states:
            if not is_auto(state):
                by_block.setdefault(state.split("[", 1)[0], []).append(state)
    for block, states in by_block.items():
        if freed and canonical is not None and all(
                canonical(s)[0] in freed for s in states):
            continue
        allocated.add(block)

    # A converted carrier only counts if it actually has appearances; an empty
    # block would otherwise satisfy the invariant without rendering anything.
    converted = {b for b, blk in content.blocks.items()
                 if b.startswith("minecraft:") and blk.appearances}
    empty = sorted(b for b, blk in content.blocks.items()
                   if b.startswith("minecraft:") and not blk.appearances)
    if empty:
        raise InvariantError(
            f"carrier invariant\n{'-' * 40}\n"
            f"Converted carrier blocks: {len(converted)}\n"
            f"Empty carrier blocks:      {len(empty)}\n\nFAIL\n\n"
            f"These are registered as CraftEngine blocks but render nothing:\n  "
            + "\n  ".join(empty[:20]))
    missing = sorted(allocated - converted)

    report = {
        "allocated_carrier_blocks": len(allocated),
        "converted_carrier_blocks": len(converted),
        "missing": len(missing),
    }
    if missing:
        raise InvariantError(
            "Carrier validation\n"
            + "-" * 40 + "\n"
            + f"Allocated carrier blocks: {len(allocated)}\n"
            + f"Converted carrier blocks: {len(converted)}\n"
            + f"Missing:                    {len(missing)}\n\n"
            + "FAIL\n\n"
            + "These vanilla blocks have states allocated to Paperized but are not\n"
            + "themselves registered as CraftEngine blocks, so a player placing them\n"
            + "would get them rendered as Paperized content:\n  "
            + "\n  ".join(missing[:20])
            + ("\n  ..." if len(missing) > 20 else ""))
    report["status"] = "PASS"
    return report


def validate_mapping_freed(allocation: Allocation, freed: set[str],
                           targets: set[str], canonical) -> dict:
    """Every state lent to a Paperized block must be freed by a block_state_mapping.

    Collision-safe is not visually safe: a lent state that real vanilla blocks can be
    in makes those blocks render as Paperized content (a real barrel facing east drew
    as diorite bricks). The state must be a mapping key, so clients always draw real
    blocks in it as something else, and never a mapping target, which real blocks are
    drawn *as*.

    Applies to every family, so a future change cannot reopen stairs, slabs or walls
    on states that nothing frees. A converted vanilla carrier's own states render
    the vanilla block and are not lent, so they are exempt.
    """
    lent = 0
    problems: list[str] = []
    for block_id, states in sorted(allocation.assigned.items()):
        if block_id.startswith("minecraft:"):
            continue
        for state in states:
            if is_auto(state):
                # CraftEngine draws it from states its own mappings free.
                continue
            lent += 1
            key = canonical(state)[0] if canonical is not None else None
            if key is None:
                problems.append(f"  {state}  ({block_id}): cannot be canonicalised, "
                                f"so it cannot be proven freed")
            elif key in targets:
                problems.append(f"  {key}  ({block_id}): is a mapping target - real "
                                f"blocks are drawn as this state")
            elif key not in freed:
                problems.append(f"  {key}  ({block_id}): no block_state_mapping frees it, "
                                f"so real vanilla blocks in this state would draw our model")
    report = {"lent_states": lent, "freed_available": len(freed), "problems": len(problems)}
    if problems:
        raise InvariantError(
            f"mapping-freed carriers\n{'-' * 46}\nLent states: {lent}\n"
            f"Not freed:   {len(problems)}\n\nFAIL\n\n" + "\n".join(problems[:10])
            + ("\n  ..." if len(problems) > 10 else ""))
    report["status"] = "PASS"
    return report


def write_json(path: pathlib.Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")


def read_json(path: pathlib.Path) -> dict:
    return json.loads(path.read_text())


#: Families that share one carrier pool, and the fixed properties used to project a
#: declared state onto it. A pressure plate and a button both draw from vanilla
#: buttons, so they have to pick disjoint slots or they would collide on the same
#: vanilla state.
FAMILY_CARRIER_POOL = {
    "pressure_plate": "plate",
    "button": "plate",

}

FAMILY_PROJECTION_PIN = {
    "pressure_plate": {"face": "floor", "facing": "north"},
    "button": {"facing": "north"},
}
