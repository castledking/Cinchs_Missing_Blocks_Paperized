#!/usr/bin/env python3
"""Regression fixture: a deferred block must not half-exist.

The allocator defers whole blocks when a family runs out of collision-safe carriers
(nine stairs and two slabs on 26.3). Content stays canonical, so the block is still in
content.json - but nothing CraftEngine or a player can reach may name it. Before this
fixture, a deferred stair still had an item whose ``block_item`` pointed at a block
that was never built, and a creative-category entry. That is how the layer 1a matrix
spent four runs probing calcite_stairs.

Two-sided on purpose:

    deferred block -> absent from items.yml, categories.yml, and every other
                      generated configuration file
    served block   -> still has its item and its category entry

The second direction is what catches an over-broad filter, e.g. one that dropped a
whole category or every block of a family that had any deferral.

The deferred set is read from the allocation that ran, never recomputed here, so this
cannot drift from the capacity pass. It also mutates a built pack to prove the
generator's own gate (validate_no_deferred_references) refuses a leak.

Needs the upstream mod checkout and the test server's CraftEngine resources, like the
generator itself. Run directly, or via pytest.
"""

from __future__ import annotations

import os
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import generate_pack as gp  # noqa: E402
import intermediate as ir  # noqa: E402

# The upstream mod checkout, wherever this tree keeps it. Preference order:
#   1. $CMB_MOD, for pointing at a checkout explicitly
#   2. upstream/ in this repo -- what CI fetches into, and the pinned fallback's home
#   3. build/upstream -- what ./gradlew build fetches into
#
# It used to be one hardcoded absolute path, which existed on exactly one machine. Every
# fixture that reads the mod skipped everywhere else, quietly, including in CI.
def _find_mod() -> pathlib.Path:
    env = os.environ.get("CMB_MOD")
    if env:
        return pathlib.Path(env)
    for candidate in (HERE.parent / "upstream", HERE.parent / "build/upstream"):
        if candidate.is_dir() and any(candidate.glob("src/main/resources")):
            return candidate
    return pathlib.Path("/mnt/storage/devops/Cinchs_Missing_Blocks")


MOD = _find_mod()
RESOURCES = HERE.parent / "server" / "plugins" / "CraftEngine" / "resources"
FAILURES: list[str] = []


def check(condition: bool, message: str) -> None:
    print(f"  {'ok  ' if condition else 'FAIL'}  {message}")
    if not condition:
        FAILURES.append(message)

def skip(reason: str) -> int:
    """Announce a skipped prerequisite and pass.

    These fixtures read a real CraftEngine install: its resources, its state cache, and the
    upstream mod. None of that exists in a fresh clone, so a hard failure here would mean
    CI is permanently red for a reason that is not a defect.

    Exiting 0 is only acceptable because it is *loud*. CI counts the SKIP lines and prints
    them, so a suite that quietly stops testing anything is visible rather than green --
    which is the same reason the generator refuses to guess rather than emit an empty
    geometry.
    """
    print(f"SKIP {pathlib.Path(__file__).name}: {reason}")
    print("     this fixture needs a Paper server with CraftEngine installed at server/")
    return 0


def main() -> int:
    if not MOD.is_dir() or not RESOURCES.is_dir():
        return skip(f"needs the mod at {MOD} and CraftEngine resources at {RESOURCES}")
    import yaml

    cfg, budget = gp.load_build_config(HERE / "build-config.yml")
    build = gp.build_pack(MOD, cfg, budget, "26.3", "disable", RESOURCES)
    allocation = yaml.safe_load(build.files[pathlib.Path("intermediate/allocation.json")])
    content = yaml.safe_load(build.files[pathlib.Path("intermediate/content.json")])
    deferred = set(allocation["unsupported"])
    # The furniture test serves these deferred stairs/slabs as furniture under their
    # own ids, so they are served, not deferred (features.furniture-fallback).
    fallback = set(build.stats.get("furniture_fallback", []))
    deferred -= fallback
    served = {b for b in content["blocks"]
              if b.startswith(f"{gp.MOD_ID}:") and b not in deferred}

    print("deferred block outputs")
    print("-" * 62)
    check(len(deferred) > 0,
          f"this version defers something, so the test is not vacuous ({len(deferred)})")

    items = yaml.safe_load(build.files[pathlib.Path("configuration/items.yml")])["items"]
    categories = yaml.safe_load(build.files[pathlib.Path("configuration/categories.yml")])["categories"]
    listed = {i for c in categories.values() for i in c["list"]}
    icons = {c["icon"] for c in categories.values()}
    item_blocks = {v.get("behavior", {}).get("block") for v in items.values()}

    # furniture test: served as furniture, never as a block
    for block_id in sorted(fallback):
        behavior = items.get(block_id, {}).get("behavior", {})
        check(behavior.get("type") == "furniture_item" and behavior.get("furniture") == block_id,
              f"{block_id} (furniture-fallback) is a furniture item, not a block_item")

    # direction 1: deferred blocks are gone
    check(not deferred & set(items), "no deferred block has an item")
    check(not deferred & item_blocks, "no block_item points at a deferred block")
    check(not deferred & listed, "no category lists a deferred block")
    check(not deferred & icons, "no category uses a deferred block as its icon")
    gate = build.stats.get("deferred_references", {})
    check(gate.get("status") == "PASS" and gate.get("files_scanned", 0) > 0,
          f"generator gate scanned {gate.get('files_scanned', 0)} configuration file(s)")

    # direction 2: served blocks are untouched
    missing_items = sorted(served - set(items))
    check(not missing_items,
          f"every served block keeps its item ({len(served)} served"
          + (f"; missing {missing_items[:3]}" if missing_items else "") + ")")
    grouped = {b for b in served
               if not content["blocks"][b].get("settings", {}).get("converted_vanilla")}
    missing_listed = sorted(grouped - listed)
    check(not missing_listed,
          "every served block keeps its category entry"
          + (f" (missing {missing_listed[:3]})" if missing_listed else ""))

    # the gate itself: re-introduce one leak and it must refuse the pack
    victim = sorted(deferred)[0]
    categories_copy = dict(categories)
    first = next(iter(categories_copy))
    categories_copy[first] = dict(categories_copy[first],
                                  list=categories_copy[first]["list"] + [victim])
    build.text("configuration/categories.yml", gp.to_yaml(
        {"categories": categories_copy}))
    try:
        gp.validate_no_deferred_references(build, deferred)
        check(False, f"gate refuses a re-introduced reference to {victim}")
    except ir.InvariantError:
        check(True, f"gate refuses a re-introduced reference to {victim}")

    print("-" * 62)
    print(f"deferred: {len(deferred)}   served: {len(served)}   items: {len(items)}")

    # features.furniture-fallback: true - every deferred stair, slab, wall and fence becomes a
    # furniture item, and nothing else changes: the same blocks stay deferred.
    print()
    print("furniture-fallback: true")
    print("-" * 62)
    import dataclasses
    fb_build = gp.build_pack(MOD, dataclasses.replace(cfg, furniture_fallback=True),
                             budget, "26.3", "disable", RESOURCES)
    fb_items = yaml.safe_load(fb_build.files[pathlib.Path("configuration/items.yml")])["items"]
    fallback = set(fb_build.stats["furniture_fallback"])
    eligible = {b for b in set(allocation["unsupported"])
                if content["blocks"][b]["family"] in gp.FALLBACK_FAMILIES}
    check(fallback == eligible,
          f"every deferred stair, slab, wall and fence is served as furniture ({len(fallback)})")
    check(all(fb_items.get(b, {}).get("behavior", {}).get("type") == "furniture_item"
              for b in fallback), "each is a furniture item, never a block_item")
    check(fb_build.stats.get("deferred_references", {}).get("status") == "PASS",
          "the deferred-reference gate passes with the rest still deferred")
    if FAILURES:
        print(f"\n{len(FAILURES)} check(s) failed")
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
