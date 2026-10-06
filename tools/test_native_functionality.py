#!/usr/bin/env python3
"""Regression fixture: the mod's data pack is replaced by native CraftEngine config.

The mod ships tags, loot tables and recipes as a vanilla data pack keyed by
``cinchsmissingblocks:*``. Those ids are not registered on a Paper server, so the
copied data pack could never resolve - and it was never even a valid data pack (its
pack.mcmeta landed inside the CraftEngine pack). This checks the replacement as
emitted, in both directions:

    served block   -> has loot, carries every tag the mod declares, keeps every
                      recipe whose Paperized items are all emitted
    deferred block -> no recipe names it, and it has no block entry to drop from
    nothing        -> no data pack output at all

Spot checks pin the cases that were wrong before: the snow brick wall/slab/stairs
were tagged pickaxe instead of shovel, and a shaped recipe's `#` key was written as
a YAML comment. Mutations prove the generator's gates refuse each failure.

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
import fixture_support  # noqa: E402
from fixture_support import NoProbeError, deferred_ids  # noqa: E402
import mod_data  # noqa: E402

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


def refuses(gate, message: str) -> None:
    try:
        gate()
        check(False, message)
    except (ir.InvariantError, gp.BuildError, mod_data.UnsupportedData):
        check(True, message)

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
    files = {p.as_posix(): body for p, body in build.files.items()}
    allocation = yaml.safe_load(files["intermediate/allocation.json"])
    deferred = set(allocation["unsupported"])
    # The furniture test serves these deferred stairs/slabs as furniture under their
    # own ids, so they are served, not deferred (features.furniture-fallback).
    fallback = set(build.stats.get("furniture_fallback", []))
    deferred -= fallback
    items = yaml.safe_load(files["configuration/items.yml"])["items"]
    recipes = yaml.safe_load(files["configuration/recipes.yml"])["recipes"]
    blocks: dict = {}
    for rel, body in files.items():
        if rel.startswith("configuration/blocks/"):
            blocks.update(yaml.safe_load(body)["blocks"])
    served = {b for b in blocks if b.startswith(f"{gp.MOD_ID}:")}
    # Probes come from what allocation actually served. Hardcoding an id means a
    # family disappearing from the allocation silently stops this fixture asserting
    # anything, which has happened three times.
    content_doc = json.loads(files["intermediate/content.json"])
    content_doc["allocation"] = allocation
    def probe_block(family, contains=None):
        # Served-but-unprobeable is a failure. Deliberately deferred is a skip:
        # the product decision was correct, the assertion just has no subject.
        if fixture_support.is_deferred(content_doc, family):
            print(f"  skip  {family} is deferred, assertion has no subject")
            return None
        try:
            return fixture_support.probe(content_doc, family, contains)
        except fixture_support.NoProbeError as exc:
            print(f"  FAIL  {exc}")
            FAILURES.append(str(exc))
            return None
    source = mod_data.ModData(MOD / "common/src/main/resources/data")
    block_tags = source.tags("block")

    print("native functionality (replaces the mod data pack)")
    print("-" * 62)

    # --- no data pack --------------------------------------------------------
    check(not [r for r in files if r.startswith("datapack/") or r.endswith("pack.mcmeta")],
          "no data pack files are emitted")
    check(all(d.as_posix().startswith("resourcepack/") for _, d in build.copies),
          "every copied tree is resource pack assets")

    # --- loot: every served block, none for deferred -------------------------
    no_loot = sorted(b for b in served if "loot" not in blocks[b])
    check(not no_loot, f"every served block has loot ({len(served)})"
          + (f"; missing {no_loot[:3]}" if no_loot else ""))
    check(not deferred & set(blocks), "no deferred block has a block entry to drop from")
    slab_id = probe_block("slab")
    slab = (blocks.get(slab_id) or {}).get("loot") if slab_id else None
    check(slab_id is None or slab == {"template": "default:loot_table/slab"},
          "slabs use CraftEngine's double-drop slab template")
    sculk_id = probe_block("cube", "sculk_inlaid")
    sculk = (blocks.get(sculk_id) or {}).get("loot", {}) if sculk_id else {}
    check(sculk_id is None
          or (sculk.get("template") == "default:loot_table/silk_touch"
              and sculk.get("arguments", {}).get("item") == sculk_id),
          "sculk inlaid deepslate drops only with Silk Touch")
    snow_id = probe_block("cube", "snow_bricks")
    snow = (blocks.get(snow_id) or {}).get("loot", {}) if snow_id else {}
    children = (snow.get("pools") or [{}])[0].get("entries", [{}])[0].get("children", [])
    check(snow_id is None or [c.get("item") for c in children]
          == [snow_id, "minecraft:snowball"],
          "snow bricks: Silk Touch drops the block, otherwise snowballs")

    # --- tags: every tag the mod declares, from the mod's own files ----------
    missing_tags = sorted(b for b in served
                          if set(block_tags.get(b, []))
                          - set(blocks[b].get("settings", {}).get("tags", [])))
    check(not missing_tags, "every served block carries every tag the mod declares"
          + (f" (missing on {missing_tags[:3]})" if missing_tags else ""))
    stair_id = probe_block("stairs")
    stairs = (blocks.get(stair_id) or {}).get("settings", {}).get("tags", []) if stair_id else []
    check(stair_id is None
          or ("minecraft:stairs" in stairs and "minecraft:mineable/pickaxe" in stairs),
          f"the {stair_id} block is tagged stairs + mineable/pickaxe ({stairs})")
    snowy = [b for b in served if "snow_brick" in b]
    check(snowy and all("minecraft:mineable/shovel" in blocks[b]["settings"]["tags"]
                        and "minecraft:mineable/pickaxe" not in blocks[b]["settings"]["tags"]
                        for b in snowy),
          f"snow brick blocks are shovel-mineable, not pickaxe ({len(snowy)})")
    item = items.get(stair_id) if stair_id else None
    check(stair_id is None
          or "minecraft:stairs" in ((item or {}).get("settings") or {}).get("tags", []),
          f"the {stair_id} item carries the stairs item tag")

    # --- recipes: emitted iff every Paperized item they name is emitted ------
    expected, wrong = 0, []
    for recipe_id, vanilla in source.recipes().items():
        recipe = mod_data.translate_recipe(recipe_id, vanilla)
        names = {i for i in mod_data.recipe_item_ids(recipe) if i.startswith(f"{gp.MOD_ID}:")}
        # A recipe the port replaces on purpose (gp.replaced_recipe) is never emitted.
        should = names <= set(items) and not gp.replaced_recipe(recipe_id)
        expected += should
        if should != (recipe_id in recipes):
            wrong.append(recipe_id)
    check(not wrong, f"a recipe is emitted exactly when its Paperized items are "
          f"({expected} expected, {len(recipes)} emitted)" + (f"; wrong {wrong[:3]}" if wrong else ""))
    check(recipes.get(f"{gp.MOD_ID}:blue_nether_brick_from_smelting", {}).get("ingredient")
          == "minecraft:warped_nylium"
          and recipes.get(f"{gp.MOD_ID}:red_nether_brick_from_smelting", {}).get("ingredient")
          == "minecraft:crimson_nylium",
          "warped and red nether brick smelt from their nylium")
    check(all(recipes.get(f"{gp.MOD_ID}:{r}", {}).get("result", {}).get("count") == 2
              for r in ("blue_nether_brick_from_sprouts", "red_nether_brick_from_roots")),
          "sprouts / roots + nether brick convert 2 bricks into 2, never multiply")
    named = {i for r in recipes.values() for i in mod_data.recipe_item_ids(r)}
    check(not deferred & named, "no emitted recipe names a deferred block")
    # Derive the probe from a *served* block: this used to name a wall, which is
    # now deferred, so the assertion silently stopped testing anything.
    probe = next((rid for rid, r in sorted(recipes.items())
                  if "#" in (r.get("ingredients") or {})), None)
    check(probe is not None and "#" in (recipes[probe].get("ingredients") or {}),
          f"a shaped recipe's '#' key survives emission (it was a YAML comment)"
          + (f" [probe {probe}]" if probe else " - none emitted"))

    # --- the gates refuse each failure ---------------------------------------
    def without_loot():
        # Derive the probe: any served block that carries loot, in whichever family
        # file the build actually emitted.
        for target in sorted(r for r in files if r.startswith("configuration/blocks/")):
            data = yaml.safe_load(files[target])
            for bid in sorted(data.get("blocks", {})):
                if "loot" not in data["blocks"][bid]:
                    continue
                data["blocks"][bid].pop("loot", None)
                mutated = gp.Build(files=dict(build.files), copies=list(build.copies))
                mutated.text(target, gp.to_yaml(data))
                gp.validate_native_functionality(
                    mutated, block_tags, source.tags("item"))
                return
        raise NoProbeError(
            "no served block with loot available for fixture")
    refuses(without_loot, "gate refuses a served block with no loot")

    def with_datapack():
        mutated = gp.Build(files=dict(build.files), copies=list(build.copies))
        mutated.json("datapack/pack.mcmeta", {"pack": {}})
        gp.validate_native_functionality(mutated, block_tags, source.tags("item"))
    refuses(with_datapack, "gate refuses any data pack output")

    def with_alias_item():
        # The old alias shape: an item named after a canonical block, placing nothing.
        data = yaml.safe_load(files["configuration/items.yml"])
        data["items"][f"{gp.MOD_ID}:polished_deepslate_button"] = {
            "material": "nether_brick",
            "model": {"path": "minecraft:item/polished_blackstone_button"}}
        mutated = gp.Build(files=dict(build.files), copies=list(build.copies))
        mutated.text("configuration/items.yml", gp.to_yaml(data))
        gp.validate_native_functionality(mutated, block_tags, source.tags("item"))
    refuses(with_alias_item, "gate refuses an item named after a block that places nothing")
    check(f"{gp.MOD_ID}:polished_deepslate_button" in deferred
          and f"{gp.MOD_ID}:polished_deepslate_pressure_plate" in deferred,
          "the deepslate button and pressure plate are deferred, not aliased")

    refuses(lambda: mod_data.translate_loot(
        f"{gp.MOD_ID}:x", {"pools": [{"entries": [{"type": "minecraft:tag"}]}]}),
        "an unknown loot shape is refused, not silently dropped")
    refuses(lambda: gp.to_yaml({"k": ("a", "b")}),
            "the YAML round-trip guard refuses output that does not parse back")
    check(yaml.safe_load(gp.to_yaml({"#": "x", "on": "yes"})) == {"#": "x", "on": "yes"},
          "keys and values that YAML would misread are quoted")

    print("-" * 62)
    print(f"served: {len(served)}   recipes: {len(recipes)}   items: {len(items)}")
    if FAILURES:
        print(f"\n{len(FAILURES)} check(s) failed")
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
