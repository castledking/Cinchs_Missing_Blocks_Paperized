#!/usr/bin/env python3
"""Parse Cinch's Missing Blocks into a plain-data description of its content.

The mod is almost entirely declarative, so instead of re-implementing it we read
the registration source and the shipped assets and turn them into dicts. Nothing
here writes files; see generate_pack.py for that.

Only the Fabric sources are parsed - the NeoForge tree is a near-verbatim copy
with Mojang mappings and exists purely to satisfy the second loader.
"""

from __future__ import annotations

import collections
import dataclasses
import json
import pathlib
import re

MOD_ID = "cinchsmissingblocks"

# Block class -> the shape family it belongs to. Drives which CraftEngine
# properties, behaviours and carrier states get generated.
CLASS_FAMILY = {
    "StairsBlock": "stairs",
    "SlabBlock": "slab",
    "WallBlock": "wall",
    "FenceBlock": "fence",
    "PillarBlock": "pillar",
    "PressurePlateBlock": "pressure_plate",
    "ButtonBlock": "button",
    "TintedGlassPaneBlock": "pane",
    "SculkInlaidDeepslateBlock": "cube",
    "Block": "cube",
}

FAMILY_ORDER = ["cube", "stairs", "slab", "wall", "pillar", "fence",
                "pressure_plate", "button", "pane"]

# Vanilla block -> sound group, for blocks whose sound group is not spelled out
# in the registration call (only needed for the generated carrier blocks).
COMMENT_RE = re.compile(r"//\s*([A-Za-z][A-Za-z0-9 _()-]*?)\s*Blocks\s*$")


@dataclasses.dataclass
class ModBlock:
    name: str
    family: str
    java_class: str
    base: str | None
    hardness: float | None
    resistance: float | None
    sound_group: str | None
    group: str
    extra_settings: dict = dataclasses.field(default_factory=dict)

    @property
    def id(self) -> str:
        return f"{MOD_ID}:{self.name}"

    @property
    def states(self) -> int:
        """Number of CraftEngine internal states this block will declare."""
        return {
            "cube": 1,
            "stairs": 8,      # facing x half  (no corner shape - see README)
            "slab": 3,        # type
            "wall": 16,       # north/east/south/west booleans (no `up`)
            "pillar": 3,      # axis
            "fence": 16,      # north/east/south/west booleans
            "pressure_plate": 2,
            "button": 4,      # face x powered
            "pane": 16,
        }[self.family]


@dataclasses.dataclass
class ModItem:
    name: str
    texture: str | None

    @property
    def id(self) -> str:
        return f"{MOD_ID}:{self.name}"


def _strip_comments(text: str) -> str:
    """Remove // comments but keep line structure so section headers survive."""
    return "\n".join(line.split("//")[0] if not line.strip().startswith("//") else line
                     for line in text.splitlines())


def _statements(text: str, needle: str):
    """Yield the argument text of each ``needle(`` call, balanced over newlines."""
    for match in re.finditer(re.escape(needle) + r"\s*\(", text):
        start = match.end()
        depth, i = 1, start
        while i < len(text) and depth:
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
            i += 1
        yield text[start:i - 1]


def _settings_call(body: str) -> str:
    """Return the text of the Block.Settings fluent chain.

    A registration looks like
    ``new StairsBlock(Blocks.CALCITE.getDefaultState(), Block.Settings.copy(Blocks.CALCITE)
    .strength(0.75F).sounds(BlockSoundGroup.CALCITE))`` - the hardness and sound
    group live at the *end* of the fluent chain, which continues past the closing
    paren of ``copy(...)``. Rather than trying to balance that chain, take
    everything from ``Block.Settings`` to the end of the constructor arguments;
    there is only ever one such chain per registration.
    """
    idx = body.find("Block.Settings")
    if idx == -1:
        return body
    return body[idx:]


def _float_after(text: str, key: str) -> float | None:
    m = re.search(re.escape(key) + r"\s*\(\s*([0-9.]+)\s*F?\s*(?:,\s*([0-9.]+)\s*F?\s*)?\)", text)
    if not m:
        return None
    if m.group(2) is not None:
        return float(m.group(2))  # strength(hardness, resistance) -> resistance
    return float(m.group(1))


def parse_blocks(mod_root: pathlib.Path) -> list[ModBlock]:
    src = (mod_root / "fabric/src/main/java/net/cinchtail/cinchsmissingblocks/block/ModBlocks.java").read_text()

    # Section headers drive the `group` field, used only for YAML comments.
    group = "General"
    blocks: list[ModBlock] = []
    seen: set[str] = set()

    # Statements are extracted from the comment-stripped text but groups are read
    # from the original so header positions stay meaningful.
    pos_groups: list[tuple[int, str]] = []
    for m in re.finditer(r"^\s*//\s*(.+?)\s*$", src, re.M):
        header = m.group(1)
        cm = COMMENT_RE.match(header)
        pos_groups.append((m.start(), cm.group(1).strip() if cm else header.strip()))

    for stmt in _statements(src, "registerBlock"):
        # stmt is `name,\n new <Class>(...)`
        parts = stmt.split(",", 1)
        if len(parts) != 2:
            continue
        name = parts[0].strip().strip('"')
        if not name or name in seen:
            continue
        body = parts[1]

        cm = re.search(r"new\s+([A-Za-z0-9_.]+)\s*\(", body)
        if not cm:
            continue
        java_class = cm.group(1).split(".")[-1]
        family = CLASS_FAMILY.get(java_class)
        if family is None:
            continue

        base_m = re.search(r"Blocks\.([A-Z0-9_]+)\.getDefaultState\(\)", body) \
            or re.search(r"Block\.Settings\.copy(?:Shallow)?\(Blocks\.([A-Z0-9_]+)\)", body)
        base = base_m.group(1).lower() if base_m else None

        settings = _settings_call(body)
        hardness = resistance = None
        sm = re.search(r"\.strength\(\s*([0-9.]+)[fF]?\s*(?:,\s*([0-9.]+)[fF]?\s*)?\)", settings)
        if sm:
            hardness = float(sm.group(1))
            resistance = float(sm.group(2)) if sm.group(2) is not None else hardness
        elif base:
            hardness = resistance = 2.0

        sound_m = re.search(r"BlockSoundGroup\.([A-Z0-9_]+)", settings)
        sound_group = sound_m.group(1).lower() if sound_m else None

        start = src.find(f'"{name}"')
        group = "General"
        for pos, header in pos_groups:
            if start != -1 and pos < start:
                group = header

        extra: dict = {}
        if "nonOpaque" in settings or "nonOpaque()" in settings:
            extra["transparent"] = True
        if re.search(r"\.pistonBehavior\(PistonBehavior\.(\w+)\)", settings):
            extra["push_reaction"] = re.search(r"\.pistonBehavior\(PistonBehavior\.(\w+)\)", settings).group(1).lower()

        blocks.append(ModBlock(name, family, java_class, base, hardness, resistance,
                               sound_group, group, extra))
        seen.add(name)

    return blocks


def parse_items(mod_root: pathlib.Path) -> list[ModItem]:
    path = mod_root / "fabric/src/main/java/net/cinchtail/cinchsmissingblocks/item/ModItems.java"
    if not path.is_file():
        return []
    src = path.read_text()
    items: list[ModItem] = []
    for stmt in _statements(src, "registerItem"):
        parts = stmt.split(",", 1)
        if len(parts) != 2:
            continue
        name = parts[0].strip().strip('"')
        if not re.fullmatch(r"[a-z0-9_]+", name):
            continue
        # Standalone items have no texture reference in the registration call;
        # the asset tree is authoritative for the file name.
        tex = re.search(r"textures/item/([a-z0-9_]+)", stmt)
        items.append(ModItem(name, tex.group(1) if tex else name))
    return items


def parse_item_group_order(mod_root: pathlib.Path) -> list[str]:
    """Creative-tab order, so the generated categories match the mod's ordering."""
    path = mod_root / "fabric/src/main/java/net/cinchtail/cinchsmissingblocks/item/ModItemGroups.java"
    if not path.is_file():
        return []
    src = path.read_text()
    order: list[str] = []
    for m in re.finditer(r"ModBlocks\.([A-Z0-9_]+)|ModItems\.([A-Z0-9_]+)", src):
        order.append((m.group(1) or m.group(2)).lower())
    return order


def parse_configs(mod_root: pathlib.Path) -> dict[str, str]:
    """Read the mod's boolean config keys and their literal defaults."""
    path = mod_root / "fabric/src/main/java/net/cinchtail/cinchsmissingblocks/config/ModConfigs.java"
    defaults: dict[str, str] = {}
    if not path.is_file():
        return defaults
    src = path.read_text()
    for m in re.finditer(r'"(enable\w+|[a-zA-Z]*DefaultEnabled)"\s*,\s*(true|false)', src):
        defaults[m.group(1)] = m.group(2)
    for m in re.finditer(r'(?:get|read)(\w*Boolean)\(\s*"([a-zA-Z]+)"\s*,\s*(true|false)\s*\)', src):
        defaults[m.group(2)] = m.group(3)
    return defaults


def load_asset_index(mod_root: pathlib.Path) -> dict:
    """Inventory the shipped asset tree, which is reused almost verbatim."""
    assets = mod_root / "common/src/main/resources/assets"
    index: dict[str, list[str]] = collections.defaultdict(list)
    for path in sorted(assets.rglob("*")):
        if path.is_file():
            rel = path.relative_to(assets).as_posix()
            index[rel.split("/")[0] + "/" + rel.split("/")[1] if rel.count("/") > 1 else rel].append(rel)
    return dict(index)


def load_data_tree(mod_root: pathlib.Path) -> pathlib.Path:
    return mod_root / "common/src/main/resources/data"


def lang_keys(mod_root: pathlib.Path, locale: str = "en_us") -> dict[str, str]:
    path = mod_root / f"common/src/main/resources/assets/{MOD_ID}/lang/{locale}.json"
    if not path.is_file():
        return {}
    return json.loads(path.read_text())


def main() -> int:
    import argparse

    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--mod", type=pathlib.Path, required=True)
    ap.add_argument("--summary", action="store_true")
    args = ap.parse_args()

    blocks = parse_blocks(args.mod)
    counts = collections.Counter(b.family for b in blocks)
    if args.summary:
        print(f"blocks: {len(blocks)}")
        for fam in FAMILY_ORDER:
            if counts[fam]:
                print(f"  {fam:<16}{counts[fam]:>5}  {counts[fam] * next(b.states for b in blocks if b.family == fam):>6} states")
        print(f"  {'TOTAL':<16}{len(blocks):>5}  {sum(b.states for b in blocks):>6} states")
        print(f"items:  {len(parse_items(args.mod))}")
        print(f"groups: {sorted({b.group for b in blocks})}")
    else:
        print(json.dumps([dataclasses.asdict(b) for b in blocks], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
