#!/usr/bin/env python3
"""Which vanilla block states CraftEngine has freed from vanilla use.

A ``block_state_mappings`` entry ``A: B`` tells every client to draw vanilla state A
as state B. A real vanilla block in state A therefore never *looks* like A, which is
the only thing that makes A safe to lend to a Paperized block: the model we bind to A
is then seen only where we put it.

Lending a state that nothing frees is vanilla collateral. The cube pool used to lend
barrel, crafter, bookshelf, trial spawner, creaking heart and respawn anchor states
that players reach in normal play, so a real barrel facing east was drawn as diorite
bricks. Collision-safe is not visually safe.

The rule this module supports, enforced by the allocator and by a build gate:

    lendable  <=>  the state is a mapping key  AND  never a mapping target

A target is excluded even if it is also a key: every block mapped onto it is drawn as
it, so binding a model there would change how those real blocks look.

Mappings come from every enabled CraftEngine pack (CraftEngine's own ``internal``
pack ships thousands), read from disk before any pack is parsed, the same way
``preparse_claims`` reads claims. Version-gated sections (``$$>=1.21.4#label:``)
apply only when the target version satisfies the gate. Both sides are canonicalised
against Mojang's block report, so a partial or reordered state cannot slip past.
"""

from __future__ import annotations

import pathlib
import re

GATE = re.compile(r"^\$\$(>=|<=|>|<|=)?([0-9][0-9.]*)(?:#.*)?$")


def _version(text: str) -> tuple[int, ...]:
    return tuple(int(part) for part in text.split(".") if part.isdigit())


def gate_applies(key: str, mc_version: str) -> bool | None:
    """True/False for a ``$$`` version gate, None if ``key`` is not a gate."""
    match = GATE.match(key)
    if not match:
        return None
    op, version = match.group(1) or "=", _version(match.group(2))
    target = _version(mc_version)
    return {">=": target >= version, "<=": target <= version, ">": target > version,
            "<": target < version, "=": target == version}[op]


class BlockMappings:
    def __init__(self, pairs: list[tuple[str, str, str]]):
        #: (before, after, source) as written, namespaced
        self.pairs = pairs

    @classmethod
    def load(cls, ce_resources: pathlib.Path | None, mc_version: str,
             extra: dict[str, str] | None = None) -> "BlockMappings":
        """Every active mapping in every enabled pack, plus ``extra`` (ours)."""
        import yaml
        pairs: list[tuple[str, str, str]] = []

        def walk(section: dict, source: str) -> None:
            for key, value in section.items():
                applies = gate_applies(str(key), mc_version)
                if applies is not None:
                    if applies and isinstance(value, dict):
                        walk(value, source)
                    continue
                if isinstance(value, str):
                    pairs.append((_ns(str(key)), _ns(value), source))

        if ce_resources is not None and ce_resources.is_dir():
            for pack in sorted(p for p in ce_resources.iterdir() if p.is_dir()):
                if not _pack_enabled(pack):
                    continue
                for path in sorted((pack / "configuration").rglob("*.yml")):
                    try:
                        data = yaml.safe_load(path.read_text())
                    except Exception:
                        continue
                    if isinstance(data, dict) and isinstance(
                            data.get("block_state_mappings"), dict):
                        walk(data["block_state_mappings"],
                             f"{pack.name}/{path.relative_to(pack).as_posix()}")
        for before, after in sorted((extra or {}).items()):
            pairs.append((_ns(before), _ns(after), "cinchsmissingblocks (generated)"))
        return cls(pairs)

    def freed(self, identities) -> tuple[set[str], set[str], list[str]]:
        """``(freed, targets, unparseable)`` as canonical state strings.

        ``identities`` canonicalises a state the way BlockStateParser would. Only
        blocks it has identity data for are in scope (the fixture covers every
        carrier candidate); mappings for other blocks - doors, leaves, trapdoors -
        cannot free anything we would lend. An in-scope side that does not parse is
        reported, never assumed freed. Without identities nothing is freed.
        """
        keys: set[str] = set()
        targets: set[str] = set()
        unparseable: list[str] = []
        if identities is None:
            return set(), set(), []

        def in_scope(state: str) -> bool:
            return state.split("[", 1)[0].removeprefix("minecraft:") in identities.blocks

        for before, after, source in self.pairs:
            if in_scope(after):
                canon, _ = identities.canonical(after)
                if canon is None:
                    unparseable.append(f"{source}: target {after}")
                else:
                    targets.add(canon)
            if in_scope(before):
                canon, _ = identities.canonical(before)
                if canon is None:
                    unparseable.append(f"{source}: {before}")
                else:
                    keys.add(canon)
        return keys - targets, targets, unparseable


def _ns(state: str) -> str:
    return state if ":" in state.split("[", 1)[0] else f"minecraft:{state}"


def _pack_enabled(pack: pathlib.Path) -> bool:
    meta = pack / "pack.yml"
    if not meta.is_file():
        return True
    try:
        import yaml
        return bool((yaml.safe_load(meta.read_text()) or {}).get("enable", True))
    except Exception:
        return True
