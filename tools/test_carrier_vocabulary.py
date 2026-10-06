#!/usr/bin/env python3
"""Regression fixture for the full-cube carrier pool.

A full-cube carrier state has to be a real Minecraft state, and it has to be one
that no real vanilla block can show. The pool's history is a list of ways that went
wrong:

* integer placeholders (``barrel[facing=0,open=1]``) that are not states at all;
* a hand-typed vocabulary with a property that does not exist
  (``chiseled_bookshelf[occupied=...]``);
* lending a block's default state while its converted carrier bound it too;
* lending states players reach in survival: a real barrel facing east drew as
  diorite bricks, a real crafter as bricks.

So the pool now lends only from CraftEngine's ``solid`` group (note blocks and the
mushroom blocks), and only states a ``block_state_mapping`` frees. This checks, against
Mojang's block report (fixtures/blocks_26.3.json) and the mappings on disk, never
against the pool's own idea of itself:

* every lendable state is a real state of its block;
* every lendable state is freed, and none is a mapping target;
* no former cube carrier, and no non-full-cube block, is lendable;
* with no mapping data the pool is empty, never a guess.

Run directly, or via pytest.
"""

from __future__ import annotations

import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import vanilla_carriers as vc  # noqa: E402
from block_identities import REFERENCE_ONLY, Identities  # noqa: E402
from block_mappings import BlockMappings  # noqa: E402

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
    identities = Identities.load("26.3")
    if identities is None or not RESOURCES.is_dir():
        return skip("needs fixtures/blocks_26.3.json and CraftEngine resources")
    freed, targets, _ = BlockMappings.load(RESOURCES, "26.3").freed(identities)
    pool = vc.CubeCarrierPool.build(None, None, freed, identities.canonical)
    lent = [f"minecraft:{b}[{s}]" for b, states in pool.lendable.items() for s in states]

    print("full-cube carrier pool")
    print("-" * 62)

    # 1. The solid carriers exist and are real full cubes in the report.
    for block in vc.SOLID_CARRIERS:
        check(block in identities.blocks, f"{block} is in the 26.3 block report")

    # 2. Every lendable state is a real state, already in canonical form.
    bad = [s for s in lent if identities.canonical(s)[0] != s]
    check(not bad, f"every lendable state is a real canonical state ({len(lent)})"
          + (f"; offenders {bad[:3]}" if bad else ""))
    check(len(lent) == len(set(lent)), "no lendable state is listed twice")

    # 3. Freed, and never a target.
    check(lent and all(s in freed for s in lent),
          "every lendable state is freed by a block_state_mapping")
    check(not set(lent) & targets,
          "no lendable state is a mapping target (real blocks are drawn as those)")
    harp = "minecraft:note_block[instrument=harp,note=0,powered=false]"
    check(harp in targets and harp not in lent,
          "the note block's own display state is a target and is not lendable")

    # 4. The blocks that caused vanilla collateral are gone for good.
    for block in vc.FORMER_CUBE_CARRIERS + REFERENCE_ONLY:
        check(block not in pool.lendable,
              f"{block} lends nothing")

    # 5. No mapping data, no capacity: never a guess.
    empty = vc.CubeCarrierPool.build(None, None, None, identities.canonical)
    check(empty.capacity() == 0, "without mapping data the pool is empty")
    check(pool.convertible() == [], "nothing is converted; the mappings protect real blocks")

    print("-" * 62)
    print(f"lendable: {pool.capacity()}   freed in scope: {len(freed)}   "
          f"targets: {len(targets)}")
    if FAILURES:
        print(f"\n{len(FAILURES)} check(s) failed")
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
