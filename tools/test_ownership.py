#!/usr/bin/env python3
"""Regression fixture for pre-parse ownership.

Two-sided on purpose:

    claimed state   -> never allocated
    unclaimed state -> still allocated when capacity calls for it

Checking only the first would pass just as happily if the claim filter were
over-broad - for instance if it excluded a whole block instead of the states another
pack actually holds. The second direction is what catches that: a slab pool that
still fills proves `petrified_oak_slab`'s six claimed states were removed without
costing the neighbouring eligible ones.

Run directly, or via pytest.
"""

from __future__ import annotations

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import vanilla_carriers as vc  # noqa: E402
from preparse_claims import PreParseClaims  # noqa: E402

FAILURES: list[str] = []
HERE = pathlib.Path(__file__).resolve().parent
SERVER = HERE.parent / "server" / "plugins" / "CraftEngine"


def check(condition: bool, message: str) -> None:
    print(f"  {'ok  ' if condition else 'FAIL'}  {message}")
    if not condition:
        FAILURES.append(message)


def enabled_view(resources: pathlib.Path, pack_name: str) -> pathlib.Path:
    """A temporary copy of ``resources`` made of symlinks, with ``pack_name`` enabled."""
    import tempfile
    view = pathlib.Path(tempfile.mkdtemp(prefix="cmb-claims-"))
    for pack in resources.iterdir():
        if pack.name != pack_name:
            (view / pack.name).symlink_to(pack)
            continue
        (view / pack.name).mkdir()
        for child in pack.iterdir():
            if child.name != "pack.yml":
                (view / pack.name / child.name).symlink_to(child)
        meta = (pack / "pack.yml").read_text() if (pack / "pack.yml").is_file() else ""
        lines = [l for l in meta.splitlines() if not l.startswith("enable:")]
        (view / pack.name / "pack.yml").write_text("\n".join(lines + ["enable: true"]) + "\n")
    return view

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
    resources = SERVER / "resources"
    if not resources.is_dir():
        return skip(f"no CraftEngine resources at {resources}")
    # This fixture tests the claim-discovery mechanism against default_assets' real
    # content. Whether a given server has that pack enabled is a deployment choice
    # (the Paperized test server disables it to free cut_copper_stairs and
    # petrified_oak_slab), so read the packs through a view where it is enabled.
    resources = enabled_view(resources, "default_assets")

    claims = PreParseClaims(
        resources, exclude_pack="cinchsmissingblocks",
        cache=SERVER / "cache" / "visual_block_states.json")
    claimed = claims.collect()

    print("pre-parse ownership")
    print("-" * 62)

    # --- the three sources all contribute ------------------------------------
    explicit = claims.explicit_states()
    cached = claims.auto_state_cache()
    templated = claims.template_factory_claims()
    check(len(explicit) > 0, f"explicit state: claims found ({len(explicit)})")
    # Whether this server's cache holds anything depends on its runtime history (a
    # cold boot, or no pack using auto_state, leaves none), so the reader is tested
    # against a synthetic cache instead of whatever happens to be on disk.
    import json, tempfile
    synthetic_cache = pathlib.Path(tempfile.mkdtemp(prefix="cmb-cache-")) / "c.json"
    synthetic_cache.write_text(json.dumps({
        "default:probe[facing=north]": "minecraft:note_block[instrument=bell,note=7,powered=false]",
        "default:probe[facing=south]": "minecraft:mushroom_stem[down=false,east=true,north=true,south=true,up=true,west=true]",
    }))
    read = PreParseClaims(resources, exclude_pack="cinchsmissingblocks",
                          cache=synthetic_cache).auto_state_cache()
    check(sorted(c.state for c in read) == sorted([
              "minecraft:note_block[instrument=bell,note=7,powered=false]",
              "minecraft:mushroom_stem[down=false,east=true,north=true,south=true,up=true,west=true]"]),
          f"auto_state cache: the reader returns exactly the cached claims "
          f"({len(read)}; this server's cache holds {len(cached)})")
    check(len(templated) > 0, f"template factory: claims found ({len(templated)})")

    # --- the mechanism that started all this ---------------------------------
    # CraftEngine's own comment: "As there is a shortage of available states for
    # slabs and stairs, an independent factory is set up to generate them".
    petrified = sorted(s for s in claimed if "petrified_oak_slab" in s)
    check(len(petrified) == 6,
          f"petrified_oak_slab is fully claimed ({len(petrified)}/6 states)")
    check(any("waterlogged=true" in s for s in petrified),
          "the waterlogged=true states are claimed, which is where the collision was")
    copper = [s for s in claimed if "cut_copper_stairs" in s]
    check(len(copper) == 80,
          f"cut_copper_stairs is fully claimed ({len(copper)}/80 states)")
    check(not claims.unresolved,
          f"no unresolvable expressions left ({len(claims.unresolved)})")

    # Shape-family pools lend only mapping-freed, dry states, so ownership is
    # checked on those pools, built exactly as the allocator builds them.
    from block_identities import Identities
    from block_mappings import BlockMappings
    identities = Identities.load("26.3")
    freed, _, _ = BlockMappings.load(resources, "26.3").freed(identities)
    canon_claims = {identities.canonical(s)[0] for s in claimed} - {None}

    def build(family, with_claims):
        return vc.CarrierPool.build(family, None, set(claimed) if with_claims else None,
                                    freed, identities.canonical)

    # --- direction 1: a claimed state is never handed out --------------------
    leaked = []
    for name, family in vc.FAMILIES.items():
        pool = build(family, True)
        for bucket in pool.available.values():
            for block, combo in bucket:
                if identities.canonical(f"minecraft:{block}[{combo}]")[0] in canon_claims:
                    leaked.append(f"{name}: {block}[{combo}]")
    check(not leaked, "no claimed state survives in any carrier pool"
          + (f" ({leaked[:2]})" if leaked else ""))

    # --- direction 2: only what was claimed is lost ---------------------------
    # The probe family is derived from what actually has capacity (it used to be
    # named, and quietly stopped asserting when that family lost its carriers).
    # On 26.3 that is stairs: default_assets claims all of cut_copper_stairs, so
    # exactly its freed, dry, straight states - eight - must disappear and the other
    # three copper stairs must stay.
    probe = next((f for f in vc.FAMILIES.values() if build(f, False).capacity_states), None)
    check(probe is not None, "a carrier family with capacity exists to probe")
    if probe is not None:
        before = build(probe, False)
        after = build(probe, True)
        lost = before.capacity_states - after.capacity_states
        required = probe.required_carrier_properties
        expect = 0
        for state in canon_claims:
            block = state.split("[", 1)[0].removeprefix("minecraft:")
            props = dict(p.split("=", 1) for p in state.split("[", 1)[1][:-1].split(","))
            if (block in probe.blocks and state in freed
                    and props.get("waterlogged") != "true"
                    and all(props.get(k) in v for k, v in required.items())):
                expect += 1
        check(lost == expect and lost > 0,
              f"{probe.name} pool lost exactly the lendable claimed states "
              f"({lost}, expected {expect})")
        check(after.capacity_states > 0,
              f"{probe.name} pool still holds unclaimed capacity "
              f"({after.capacity_states} states)")

    cold_start_cube_ownership(resources)

    print("-" * 62)
    print(f"claims: {len(claimed)}   unresolved: {len(claims.unresolved)}")
    if FAILURES:
        print(f"\n{len(FAILURES)} check(s) failed")
        return 1
    print("all checks passed")
    return 0


def cold_start_cube_ownership(resources: pathlib.Path) -> None:
    """Cube carriers respect other packs' claims, on a cold cache, in every spelling.

    The original first-boot failure: blackstone_brick_pillar was given
    minecraft:anvil[facing=north], which default_assets' netherite_anvil binds. That
    state is declared explicitly, and CraftEngine serves auto_state requests only after
    every explicit state is marked used, so the cache was never the problem; the cube
    pool simply ignored claims. It now lends mapping-freed solid states, and claims
    must still remove exactly what they name, however they are written.
    """
    from block_identities import Identities
    from block_mappings import BlockMappings
    print()
    print("cold start: cube carrier ownership (no auto_state cache)")
    print("-" * 62)
    cold = PreParseClaims(resources, exclude_pack="cinchsmissingblocks", cache=None)
    claimed = cold.collect()
    anvil = "minecraft:anvil[facing=north]"
    check(anvil in claimed and "auto_state" not in claimed[anvil],
          f"{anvil} is claimed on a cold cache, from configuration "
          f"({claimed.get(anvil, 'unclaimed')})")
    check("anvil" not in vc.SOLID_CARRIERS, "anvil is not a cube carrier (not a full cube)")

    identities = Identities.load("26.3")
    freed, _, _ = BlockMappings.load(resources, "26.3").freed(identities)
    baseline = vc.CubeCarrierPool.build(None, None, freed, identities.canonical)
    note = baseline.lendable.get("note_block", [])
    mush = baseline.lendable.get("mushroom_stem", [])
    check(len(note) > 3 and mush, "the solid pool has note block and mushroom capacity")

    # Claims in each spelling the parser accepts: full, partial (rest defaulted) and
    # reordered. A string comparison would miss all but the first.
    full = f"minecraft:note_block[{note[0]}]"
    partial_props = dict(p.split("=") for p in note[1].split(","))
    partial = (f"minecraft:note_block[instrument={partial_props['instrument']},"
               f"note={partial_props['note']}]")
    reordered_props = dict(p.split("=") for p in mush[0].split(","))
    reordered = "minecraft:mushroom_stem[" + ",".join(
        f"{k}={v}" for k, v in sorted(reordered_props.items(), reverse=True)) + "]"
    synthetic = dict(claimed)
    synthetic.update({full: "fixture (full)", partial: "fixture (partial)",
                      reordered: "fixture (reordered)"})
    pool = vc.CubeCarrierPool.build(None, synthetic, freed, identities.canonical)

    # direction 1: a claimed state is never lent, however it is spelled
    check(note[0] not in pool.lendable["note_block"], "a full-form claim is not lent")
    expected_partial = identities.canonical(partial)[0]
    check(expected_partial is None or expected_partial.split("[", 1)[1][:-1]
          not in pool.lendable["note_block"],
          "a partial claim (powered defaulted) removes the state it names")
    check(mush[0] not in pool.lendable["mushroom_stem"],
          "a reordered claim is not lent")
    lent = {f"minecraft:{b}[{s}]" for b, states in pool.lendable.items() for s in states}
    check(not lent & set(pool.externally_claimed),
          f"no claimed state is lendable ({len(pool.externally_claimed)} skipped)")

    # direction 2: only what was claimed is lost
    lost = baseline.capacity() - pool.capacity()
    check(lost == len(set(pool.externally_claimed)),
          f"the pool lost exactly the claimed states ({lost})")


if __name__ == "__main__":
    raise SystemExit(main())