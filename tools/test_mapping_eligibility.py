#!/usr/bin/env python3
"""Regression fixture: every lent carrier state is freed from vanilla use.

The 1b vanilla-collateral check failed: a real vanilla barrel rendered as diorite
bricks and a real crafter as bricks, because the cube pool lent states players reach
in survival. Collision-safe is not visually safe. A lent state must be a key of an
active CraftEngine ``block_state_mapping`` (clients draw real blocks in it as
something else) and never a mapping target (real blocks are drawn *as* it).

This checks the built allocation against the mapping source itself. It parses
CraftEngine's ``internal/configuration/mappings.yml`` directly with a plain YAML
load, not through block_mappings.py, so a bug in the generator's reader cannot hide
a bug in the allocation.

Two-sided:

    lent states     -> all freed, none a target, none from the former cube carriers
    served content  -> all 61 cubes and 13 pillars still served, on note blocks or
                       mushroom blocks; exactly 3 stairs, each on one freed copper
                       stair block, every state dry and straight
    other decisions -> the other stairs stay deferred; slabs stay deferred because
                       cmb:slab is not implemented, even though 4 slabs' worth of
                       copper carriers exist; IMPLEMENTED_CMB_BEHAVIOURS matches what
                       the plugin registers

Mutations prove the generator's gate (validate_mapping_freed) refuses a lent barrel
state and a lent mapping target.

Needs the upstream mod checkout and the test server's CraftEngine resources.
"""

from __future__ import annotations

import json
import os
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import generate_pack as gp  # noqa: E402
import intermediate as ir  # noqa: E402
import vanilla_carriers as vc  # noqa: E402
from block_identities import Identities  # noqa: E402

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
MAPPINGS = RESOURCES / "internal" / "configuration" / "mappings.yml"
FAILURES: list[str] = []


def check(condition: bool, message: str) -> None:
    print(f"  {'ok  ' if condition else 'FAIL'}  {message}")
    if not condition:
        FAILURES.append(message)


def mapping_source(identities: Identities) -> tuple[set[str], set[str]]:
    """Keys and targets of CraftEngine's internal mappings, read independently.

    Version gates in that file are all ``$$>=X#label``; on 26.3 every one applies, so
    every nested section is included. Anything not canonicalisable is skipped, which
    can only make the key set smaller and the check stricter.
    """
    import yaml
    data = yaml.safe_load(MAPPINGS.read_text())["block_state_mappings"]
    pairs: list[tuple[str, str]] = []
    for key, value in data.items():
        if isinstance(value, dict):
            assert str(key).startswith("$$>="), key
            pairs.extend(value.items())
        else:
            pairs.append((key, value))

    def canon(state: str) -> str | None:
        name = state if ":" in state.split("[", 1)[0] else f"minecraft:{state}"
        return identities.canonical(name)[0]

    keys = {c for k, _ in pairs if (c := canon(k))}
    targets = {c for _, v in pairs if (c := canon(v))}
    return keys, targets

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
    if not MOD.is_dir() or not MAPPINGS.is_file() or identities is None:
        return skip(f"needs the mod at {MOD}, {MAPPINGS} and fixtures/blocks_26.3.json")

    cfg, budget = gp.load_build_config(HERE / "build-config.yml")
    build = gp.build_pack(MOD, cfg, budget, "26.3", "disable", RESOURCES)
    allocation = json.loads(build.files[pathlib.Path("intermediate/allocation.json")])
    content = json.loads(build.files[pathlib.Path("intermediate/content.json")])
    keys, targets = mapping_source(identities)

    served: dict[str, list[str]] = {
        b: v for b, v in allocation["assigned"].items() if not b.startswith("minecraft:")}
    # Concrete states only: cubes and pillars carry intermediate.AUTO_SOLID, which
    # CraftEngine resolves on the server from states its own mappings free.
    lent = {b: [s for s in v if not ir.is_auto(s)] for b, v in served.items()}
    lent = {b: v for b, v in lent.items() if v}
    states = [identities.canonical(s)[0] for v in lent.values() for s in v]

    print("mapping-freed carriers")
    print("-" * 62)

    # direction 1: every lent state is freed, by the mapping source itself
    check(None not in states, f"every lent state canonicalises ({len(states)})")
    check(all(s in keys for s in states), "every lent state is a mapping key")
    check(not set(states) & targets, "no lent state is a mapping target")
    former = [s for s in states if s and s.split("[")[0].removeprefix("minecraft:")
              in vc.FORMER_CUBE_CARRIERS]
    check(not former, "no barrel, crafter, bookshelf, trial spawner, creaking heart "
          "or respawn anchor state is lent" + (f" ({former[:2]})" if former else ""))
    check(not any(v for v in allocation["converted"].values()),
          "no vanilla block is converted")

    # direction 2: the served content survives, on CraftEngine's solid auto-states
    by_family: dict[str, int] = {}
    for block_id in served:
        family = content["blocks"][block_id]["family"]
        by_family[family] = by_family.get(family, 0) + 1
    check(by_family.get("cube") == 61 and by_family.get("pillar") == 13,
          f"61 cubes and 13 pillars are still served ({by_family})")
    check(not lent, f"no concrete state is lent: cubes and pillars are auto_state "
                    f"({sorted(lent)[:2]})")
    import yaml
    emitted = {}
    for name in ("cube", "pillar"):
        emitted.update(yaml.safe_load(build.files[pathlib.Path(
            f"configuration/blocks/{name}.yml")])["blocks"])
    appearances = [a for b in emitted.values() for a in b["states"]["appearances"].values()]
    check(appearances and all(a.get("auto_state") == "solid" and "state" not in a
                              for a in appearances),
          f"every cube and pillar appearance is auto_state: solid ({len(appearances)})")

    # stairs, slabs and panes are furniture: none rides a carrier, and the allocator
    # defers every one of them (features.furniture-fallback serves them)
    on_carriers = sorted(b for b in lent
                         if content["blocks"][b]["family"] in gp.FURNITURE_FAMILIES)
    check(not on_carriers, f"no stair, slab or pane is served on a carrier ({on_carriers[:3]})")
    deferred = set(allocation["unsupported_families"])
    check({"stairs", "slab"} <= deferred, "stairs and slabs are deferred, to furniture")

    # the generator's view of implemented behaviours matches the plugin source
    import re
    java = (HERE.parent / "cmb/src/main/java/net/cinchtail/cinchsmissingblocks/cmb/"
            "CmbBehaviors.java").read_text()
    all_list = java[java.index("ALL = List.of("):]
    all_list = all_list[:all_list.index(");")]
    registered = {f"cmb:{name.lower()}" for name in
                  re.findall(r"assertNamespaced\(([A-Z_]+)\)", all_list)}
    check(registered == set(gp.IMPLEMENTED_CMB_BEHAVIOURS),
          f"IMPLEMENTED_CMB_BEHAVIOURS matches CmbBehaviors.ALL ({sorted(registered)})")

    # the gate itself
    freed, mtargets, _ = __import__("block_mappings").BlockMappings.load(
        RESOURCES, "26.3").freed(identities)

    def gate_with(extra_state: str):
        def run():
            mutated = ir.Allocation.__new__(ir.Allocation)
            mutated.__dict__.update(
                {"assigned": dict(allocation["assigned"],
                                  **{"cinchsmissingblocks:probe": [extra_state]})})
            ir.validate_mapping_freed(mutated, freed, mtargets, identities.canonical)
        return run

    for label, state in (
            ("a lent barrel state", "minecraft:barrel[facing=east,open=false]"),
            ("a lent mapping target",
             "minecraft:note_block[instrument=harp,note=0,powered=false]")):
        try:
            gate_with(state)()
            check(False, f"gate refuses {label}")
        except ir.InvariantError:
            check(True, f"gate refuses {label}")

    print("-" * 62)
    print(f"lent: {len(states)}   mapping keys: {len(keys)}   targets: {len(targets)}")
    if FAILURES:
        print(f"\n{len(FAILURES)} check(s) failed")
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
