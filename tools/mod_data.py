#!/usr/bin/env python3
"""Translate the mod's data pack (tags, loot tables, recipes) into CraftEngine config.

The mod ships these as a vanilla data pack keyed by ``cinchsmissingblocks:*``. On a
Paper server those ids do not exist: CraftEngine registers its blocks as
``craftengine:custom_N`` and its items on vanilla base materials. A copied data pack
therefore cannot work - recipes fail to resolve, and a tag naming an unknown id fails
to load as a whole, taking vanilla members of ``minecraft:mineable/pickaxe`` with it.

So the mod's data is read as *source* and re-expressed natively:

    data/minecraft/tags/block/**   -> block ``settings.tags``
    data/minecraft/tags/item/**    -> item ``settings.tags``
    data/<mod>/loot_table/blocks/  -> block ``loot:``
    data/<mod>/recipe/             -> ``recipes:``

Translation is by recognised shape. A loot table or recipe this module does not
understand is a build failure, not a silent omission: a block that quietly drops
nothing is exactly the kind of defect nobody notices until a player does.
"""

from __future__ import annotations

import json
import pathlib

MOD_ID = "cinchsmissingblocks"
SILK_TOUCH = "minecraft:silk_touch>=1"


class UnsupportedData(RuntimeError):
    """A loot table or recipe whose shape has no CraftEngine translation here."""


class ModData:
    def __init__(self, data_root: pathlib.Path):
        self.root = data_root

    # -- tags ------------------------------------------------------------------

    def tags(self, kind: str) -> dict[str, list[str]]:
        """``{member_id: [tag, ...]}`` for ``kind`` in ("block", "item")."""
        out: dict[str, set[str]] = {}
        for namespace_dir in sorted(p for p in self.root.iterdir() if p.is_dir()):
            base = namespace_dir / "tags" / kind
            if not base.is_dir():
                continue
            for path in sorted(base.rglob("*.json")):
                tag = (f"{namespace_dir.name}:"
                       + path.relative_to(base).with_suffix("").as_posix())
                for value in json.loads(path.read_text()).get("values", []):
                    member = value["id"] if isinstance(value, dict) else value
                    if not member.startswith("#"):
                        out.setdefault(member, set()).add(tag)
        return {k: sorted(v) for k, v in out.items()}

    # -- loot ------------------------------------------------------------------

    def loot(self, block_id: str) -> dict | None:
        """CraftEngine ``loot:`` for a mod block, or None if it has no loot table."""
        name = block_id.split(":", 1)[1]
        path = self.root / MOD_ID / "loot_table" / "blocks" / f"{name}.json"
        if not path.is_file():
            return None
        return translate_loot(block_id, json.loads(path.read_text()))

    # -- recipes ---------------------------------------------------------------

    def recipes(self) -> dict[str, dict]:
        """``{recipe_id: vanilla_json}`` for the mod's own recipes."""
        base = self.root / MOD_ID / "recipe"
        return {f"{MOD_ID}:{p.stem}": json.loads(p.read_text())
                for p in sorted(base.glob("*.json"))}

    def vanilla_overrides(self) -> list[str]:
        """Recipes the mod ships under ``minecraft:`` to replace vanilla ones."""
        base = self.root / "minecraft" / "recipe"
        return [f"minecraft:{p.stem}" for p in sorted(base.glob("*.json"))]


def _silk_touch(condition: dict) -> bool:
    if condition.get("condition") != "minecraft:match_tool":
        return False
    enchants = (condition.get("predicate", {}).get("predicates", {})
                .get("minecraft:enchantments", []))
    return any(e.get("enchantments") == "minecraft:silk_touch"
               and e.get("levels", {}).get("min") == 1 for e in enchants)


def _is_slab_count(functions: list[dict], block_id: str) -> bool:
    if [f.get("function") for f in functions] != ["minecraft:set_count",
                                                  "minecraft:explosion_decay"]:
        return False
    count = functions[0]
    conditions = count.get("conditions", [])
    return (count.get("count") == 2.0 and len(conditions) == 1
            and conditions[0].get("condition") == "minecraft:block_state_property"
            and conditions[0].get("block") == block_id
            and conditions[0].get("properties") == {"type": "double"})


def translate_loot(block_id: str, table: dict) -> dict:
    """Map one vanilla block loot table onto a CraftEngine loot definition."""
    pools = table.get("pools", [])
    if len(pools) != 1 or len(pools[0].get("entries", [])) != 1:
        raise UnsupportedData(f"{block_id}: loot table is not one pool with one entry")
    pool = pools[0]
    entry = pool["entries"][0]
    conditions = [c.get("condition") for c in pool.get("conditions", [])]
    functions = entry.get("functions", [])

    if entry.get("type") == "minecraft:item" and entry.get("name") == block_id:
        # Drops itself if it survives the explosion: CraftEngine's own template is
        # exactly this, including the survives_explosion pool condition.
        if conditions == ["minecraft:survives_explosion"] and not functions:
            return {"template": "default:loot_table/self"}
        # Slabs: two when the state is type=double, with explosion decay.
        if not conditions and _is_slab_count(functions, block_id):
            return {"template": "default:loot_table/slab"}
        # Only with Silk Touch, nothing otherwise.
        if (len(pool.get("conditions", [])) == 1 and _silk_touch(pool["conditions"][0])
                and not functions):
            return {"template": "default:loot_table/silk_touch",
                    "arguments": {"item": block_id}}

    # Alternatives: the first child whose conditions pass wins. The snow bricks use
    # this for "Silk Touch drops the block, otherwise snowballs", and the snow brick
    # slab adds the double-slab case on both sides. Translated condition by
    # condition and function by function, so any combination of the vocabulary
    # below works and anything outside it is refused.
    if entry.get("type") == "minecraft:alternatives" and not conditions:
        children = [_entry(block_id, child) for child in entry.get("children", [])]
        return {"pools": [{"rolls": 1, "entries": [
            {"type": "alternatives", "children": children}]}]}

    raise UnsupportedData(f"{block_id}: loot table shape has no translation")


def _condition(block_id: str, condition: dict) -> dict:
    kind = condition.get("condition")
    if _silk_touch(condition):
        return {"type": "enchantment", "predicate": SILK_TOUCH}
    if kind == "minecraft:inverted":
        return {"type": "inverted", "term": _condition(block_id, condition["term"])}
    if kind == "minecraft:survives_explosion":
        return {"type": "survives_explosion"}
    if (kind == "minecraft:block_state_property" and condition.get("block") == block_id
            and isinstance(condition.get("properties"), dict)):
        return {"type": "match_block_property",
                "properties": dict(sorted(condition["properties"].items()))}
    raise UnsupportedData(f"{block_id}: loot condition {condition!r} has no translation")


def _function(block_id: str, function: dict) -> dict:
    kind = function.get("function")
    if kind == "minecraft:explosion_decay" and not function.get("conditions"):
        return {"type": "explosion_decay"}
    if kind == "minecraft:set_count" and isinstance(function.get("count"), (int, float)):
        out: dict = {"type": "set_count", "add": bool(function.get("add", False)),
                     "count": int(function["count"])}
        if function.get("conditions"):
            out["conditions"] = [_condition(block_id, c) for c in function["conditions"]]
        return out
    raise UnsupportedData(f"{block_id}: loot function {function!r} has no translation")


def _entry(block_id: str, entry: dict) -> dict:
    if entry.get("type") != "minecraft:item" or "name" not in entry:
        raise UnsupportedData(f"{block_id}: loot entry {entry.get('type')} has no translation")
    out: dict = {"type": "item", "item": entry["name"]}
    if entry.get("conditions"):
        out["conditions"] = [_condition(block_id, c) for c in entry["conditions"]]
    if entry.get("functions"):
        out["functions"] = [_function(block_id, f) for f in entry["functions"]]
    return out


RECIPE_TYPES = {
    "minecraft:crafting_shaped": "shaped",
    "minecraft:crafting_shapeless": "shapeless",
    "minecraft:stonecutting": "stonecutting",
    "minecraft:smelting": "smelting",
}


def _ingredient(value) -> str | list[str]:
    """``{"item": x}`` -> ``x``; a list of those -> a list of alternatives."""
    if isinstance(value, dict) and set(value) == {"item"}:
        return value["item"]
    if isinstance(value, str) and not value.startswith("#"):
        return value
    if isinstance(value, list) and value:
        flat = [_ingredient(v) for v in value]
        if all(isinstance(v, str) for v in flat):
            return flat[0] if len(flat) == 1 else flat
    raise UnsupportedData(f"ingredient {value!r} has no translation")


def translate_recipe(recipe_id: str, recipe: dict) -> dict:
    """Map one vanilla recipe onto CraftEngine's recipe format."""
    kind = RECIPE_TYPES.get(recipe.get("type"))
    if kind is None:
        raise UnsupportedData(f"{recipe_id}: recipe type {recipe.get('type')} unsupported")
    result = recipe["result"]
    out: dict = {"type": kind}
    for key in ("category", "group"):
        if recipe.get(key):
            out[key] = recipe[key]
    if kind == "shaped":
        out["pattern"] = list(recipe["pattern"])
        out["ingredients"] = {k: _ingredient(v) for k, v in sorted(recipe["key"].items())}
    elif kind == "shapeless":
        out["ingredients"] = [_ingredient(v) for v in recipe["ingredients"]]
    elif kind == "stonecutting":
        out["ingredient"] = _ingredient(recipe["ingredient"])
    elif kind == "smelting":
        out["ingredient"] = _ingredient(recipe["ingredient"])
        out["time"] = int(recipe.get("cookingtime", 200))
        out["experience"] = float(recipe.get("experience", 0.0))
    out["result"] = {"id": result["id"], "count": int(result.get("count", 1))}
    return out


def recipe_item_ids(recipe: dict) -> set[str]:
    """Every item id a translated recipe names, result included."""
    ids = {recipe["result"]["id"]}

    def add(value):
        if isinstance(value, str):
            ids.add(value)
        elif isinstance(value, list):
            for v in value:
                add(v)

    add(recipe.get("ingredient"))
    ingredients = recipe.get("ingredients")
    if isinstance(ingredients, dict):
        for v in ingredients.values():
            add(v)
    else:
        add(ingredients)
    return ids
