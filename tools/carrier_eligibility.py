#!/usr/bin/env python3
"""Gate: borrowing a vanilla carrier must not damage vanilla states we did not take.

The failure this exists to prevent:

    vanilla blockstate      multipart blockstate, renders 32 states
    CraftEngine override   strips multipart, writes only our variants
    unconverted state      no model at all -> magenta in game

CraftEngine's override generator removes ``multipart`` from any blockstate it
touches (``AbstractPackManager.generateBlockOverrides``) and then merges its own
entries into ``variants``. For a block whose vanilla blockstate is variants-only
that is harmless, because the original variants survive the merge. For a
multipart blockstate it is destructive: every state vanilla used to render, and
that we did not allocate, is left with no model.

So eligibility is a property of the *shape* of the vanilla blockstate, not
something to patch per family:

    A carrier is eligible only if modifying its blockstate cannot destroy
    unrelated vanilla rendering.

Checked against vanilla's own definition, never against our generated output -
otherwise the check would be validating the damage against itself.
"""

from __future__ import annotations

import collections
import dataclasses
import json
import pathlib


@dataclasses.dataclass
class CarrierDamage:
    block: str
    family: str
    vanilla_states: int
    multipart: bool
    states_we_convert: int
    states_destroyed: int

    @property
    def destroys_vanilla(self) -> bool:
        return self.multipart and self.states_destroyed > 0


def vanilla_blockstate(root: pathlib.Path, block: str) -> dict | None:
    """Vanilla's own blockstate document for a block id."""
    for base in (root / "blockstates", root / f"{root.name}" / "blockstates"):
        path = base / f"{block}.json"
        if path.is_file():
            return json.loads(path.read_text())
    return None


def assess(carriers: dict[str, list[str]], state_counts: dict[str, int],
           converted: set[str], blockstates: pathlib.Path,
           declared: dict[str, int] | None = None) -> list[CarrierDamage]:
    """For each borrowed carrier block, how much vanilla rendering do we destroy?

    ``carriers``      family -> vanilla block ids we borrow from
    ``state_counts``  block -> registry state count, from the identity fixture
    ``converted``     block ids we turn into CraftEngine blocks
    ``blockstates``   root of the vanilla blockstate cache
    """
    out: list[CarrierDamage] = []
    for family, blocks in sorted(carriers.items()):
        for block in sorted(blocks):
            total = state_counts.get(block)
            document = vanilla_blockstate(blockstates, block)
            multipart = bool(document and "multipart" in document)
            # What the converted carrier actually declares - the states it keeps to
            # render as itself. Everything else it used to render is lost when
            # multipart is stripped.
            ours = (declared or {}).get(block, 0)
            out.append(CarrierDamage(
                block=block, family=family,
                vanilla_states=total if total is not None else 0,
                multipart=multipart,
                states_we_convert=ours,
                states_destroyed=max(0, total - ours) if (multipart and total) else 0,
            ))
    return out


def variants_keys(document: dict, prop: str) -> list[str]:
    """Distinct values a property takes across a blockstate's own parts."""
    seen: set[str] = set()
    for key in (document.get("variants") or {}):
        for pair in (key.split(",") if key else []):
            name, _, value = pair.partition("=")
            if name == prop:
                seen.add(value)
    for part in document.get("multipart") or []:
        value = (part.get("when") or {}).get(prop)
        if isinstance(value, str):
            seen.add(value)
    return sorted(seen)


def summarise(damage: list[CarrierDamage]) -> dict:
    """Per-family accounting for the decision."""
    by_family: dict[str, dict] = {}
    for row in damage:
        entry = by_family.setdefault(row.family, {
            "carriers_borrowed": 0,
            "multipart_carriers": 0,
            "vanilla_states_total": 0,
            "states_we_convert": 0,
            "states_destroyed": 0,
            "destroying_blocks": [],
        })
        entry["carriers_borrowed"] += 1
        entry["multipart_carriers"] += int(row.multipart)
        entry["vanilla_states_total"] += row.vanilla_states
        entry["states_we_convert"] += row.states_we_convert
        entry["states_destroyed"] += row.states_destroyed
        if row.destroys_vanilla:
            entry["destroying_blocks"].append(row.block)
    for entry in by_family.values():
        entry["destroying_blocks"] = sorted(entry["destroying_blocks"])
    return by_family


def format_report(by_family: dict, states_used: dict[str, int]) -> str:
    rule = "\u2500" * 78
    lines = ["", "Carrier eligibility - multipart damage", rule]
    lines.append(f"{'family':<10}{'borrow':>7}{'mp':>5}{'vanilla':>9}"
                 f"{'ours':>7}{'destroyed':>10}{'in use':>8}")
    for family, entry in sorted(by_family.items()):
        lines.append(f"{family:<10}{entry['carriers_borrowed']:>7}"
                     f"{entry['multipart_carriers']:>5}"
                     f"{entry['vanilla_states_total']:>9}"
                     f"{entry['states_we_convert']:>7}"
                     f"{entry['states_destroyed']:>10}"
                     f"{states_used.get(family, 0):>8}")
    lines.append(rule)
    return "\n".join(lines)