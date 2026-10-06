#!/usr/bin/env python3
"""Vanilla slabs as source data for vanilla vertical slabs.

A vanilla vertical slab is a vanilla slab stood on edge, so what it needs from vanilla
is, per slab:

* the three textures its model uses (``top``, ``side``, ``bottom``), from the client's
  slab models;
* the model of its double slab (``double``), from the client's blockstates - what two
  vertical slabs stacked into one cell look like;
* the full block its crafting recipe takes (``source``), from the server's recipes;
* how it mines and sounds (``hardness``, ``resistance``, ``sound``, ``requires_tool``),
  which vanilla sets in code, so it is read off a bootstrapped server
  (``VanillaSlabProperties.java``);
* which tool mines it (``tools``: the ``minecraft:mineable/*`` tags that contain it).

Every texture and model is a vanilla one the client already has, so nothing is
bundled: the generated files just reference ``minecraft:block/...``.

``fixtures/vanilla_slabs_<ver>.json`` holds exactly that, so a build needs none of the
sources at hand. To refresh (needs ``javac``/``java`` on PATH)::

    python3 tools/vanilla_slabs.py <slab-models-dir> <blockstates-dir> <server-dir> <version>

``<server-dir>`` is a Paper server that has run once: it reads
``versions/<ver>/paper-<ver>.jar`` and ``libraries/``.

The waxed copper slabs have no model of their own (vanilla draws them with the unwaxed
models), so they look identical to the unwaxed ones and are deliberately absent.
"""

from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import tempfile
import zipfile

FIXTURES = pathlib.Path(__file__).resolve().parent / "fixtures"
HELPER = pathlib.Path(__file__).resolve().parent / "VanillaSlabProperties.java"
MINEABLE = ("axe", "hoe", "pickaxe", "shovel")


def load(version: str) -> dict[str, dict] | None:
    path = FIXTURES / f"vanilla_slabs_{version}.json"
    return json.loads(path.read_text())["slabs"] if path.is_file() else None


def _source_block(recipe: dict | None) -> str | list[str] | None:
    """What a ``###`` slab recipe takes: one block, or vanilla's list of alternatives.

    Some slabs accept several blocks (sandstone slabs take sandstone or chiseled
    sandstone), and the vertical slab mirrors that exactly. None for no recipe at all
    (petrified oak slab is unobtainable in survival) or any other shape.
    """
    if not recipe or recipe.get("type") != "minecraft:crafting_shaped" \
            or recipe.get("pattern") != ["###"]:
        return None
    values = list(recipe.get("key", {}).values())
    if len(values) != 1:
        return None
    value = values[0]
    if isinstance(value, str) and not value.startswith("#"):
        return value
    if isinstance(value, list) and value and all(
            isinstance(v, str) and not v.startswith("#") for v in value):
        return sorted(value)
    return None


def _tag_members(data: zipfile.ZipFile, tag: str, seen: set[str] | None = None) -> set[str]:
    """Every block in a block tag, nested tags expanded."""
    seen = seen if seen is not None else set()
    if tag in seen:
        return set()
    seen.add(tag)
    path = f"data/minecraft/tags/block/{tag}.json"
    if path not in data.namelist():
        return set()
    members: set[str] = set()
    for value in json.loads(data.read(path))["values"]:
        value = value["id"] if isinstance(value, dict) else value
        if value.startswith("#"):
            members |= _tag_members(data, value[1:].removeprefix("minecraft:"), seen)
        else:
            members.add(value.removeprefix("minecraft:"))
    return members


def _properties(server: pathlib.Path, jar: pathlib.Path) -> dict[str, dict]:
    classpath = ":".join([str(jar), *map(str, sorted((server / "libraries").rglob("*.jar")))])
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["javac", "-cp", classpath, "-d", tmp, str(HELPER)],
                       check=True)
        out = pathlib.Path(tmp) / "properties.json"
        subprocess.run(["java", "-cp", f"{classpath}:{tmp}", "VanillaSlabProperties",
                        str(out)], check=True, capture_output=True)
        return json.loads(out.read_text())


def main(argv: list[str]) -> int:
    if len(argv) != 5:
        print(__doc__)
        return 2
    models, blockstates, server, version = (pathlib.Path(argv[1]), pathlib.Path(argv[2]),
                                            pathlib.Path(argv[3]), argv[4])
    jar = server / "versions" / version / f"paper-{version}.jar"
    data = zipfile.ZipFile(jar)
    properties = _properties(server, jar)
    mineable = {tool: _tag_members(data, f"mineable/{tool}") for tool in MINEABLE}

    slabs: dict[str, dict] = {}
    for path in sorted(models.glob("*_slab.json")):
        model = json.loads(path.read_text())
        textures = model.get("textures", {})
        if model.get("parent") != "minecraft:block/slab" \
                or not {"top", "side", "bottom"} <= set(textures):
            continue
        name = path.stem
        recipe_path = f"data/minecraft/recipe/{name}.json"
        recipe = (json.loads(data.read(recipe_path))
                  if recipe_path in data.namelist() else None)
        variants = json.loads((blockstates / f"{name}.json").read_text())["variants"]
        slabs[name] = {
            "top": textures["top"],
            "side": textures["side"],
            "bottom": textures["bottom"],
            "double": variants["type=double"]["model"],
            "source": _source_block(recipe),
            **properties[name],
            "tools": [f"minecraft:mineable/{tool}" for tool in MINEABLE
                      if name in mineable[tool]],
        }
    FIXTURES.mkdir(exist_ok=True)
    out = FIXTURES / f"vanilla_slabs_{version}.json"
    out.write_text(json.dumps({
        "source": f"{version} client slab models and blockstates + server jar recipes, "
                  f"tags and block properties",
        "slabs": slabs}, indent=1, sort_keys=True) + "\n")
    print(f"wrote {out}: {len(slabs)} slabs, "
          f"{sum(1 for s in slabs.values() if s['source'])} with a ### recipe")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
