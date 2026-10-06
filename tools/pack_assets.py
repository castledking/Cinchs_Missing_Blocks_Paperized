#!/usr/bin/env python3
"""Does every model the generated content references actually exist in the pack?

A block appearance or item that names a model the client cannot find renders as the
magenta/black missing texture, and nothing on the server notices: CraftEngine's
validator prints a warning at pack-generation time and carries on. Five fences and
the tinted glass pane pointed at ``cinchsmissingblocks:block/<name>``, which the mod
never ships (its fences and panes are multipart pieces), and because those
appearances bind *vanilla* carrier states, some vanilla fences and walls rendered as
missing texture too.

So every model reference is resolved against what the final pack really contains,
*transitively*: a model's ``parent`` chain must resolve, and every texture it names
must exist. A top-level model that exists but inherits from a missing parent is just
as broken.

The final pack is the generated files, the copied asset trees, and the vanilla
client's own assets. The vanilla side comes from ``fixtures/vanilla_assets_<ver>.json``,
extracted from the client jar, never assumed. To refresh it::

    python3 tools/pack_assets.py <minecraft-client-<ver>.jar> <ver>
"""

from __future__ import annotations

import json
import pathlib
import sys
import zipfile

FIXTURES = pathlib.Path(__file__).resolve().parent / "fixtures"


class VanillaAssets:
    def __init__(self, version: str, models: set[str], textures: set[str]):
        self.version = version
        self.models = models
        self.textures = textures

    @classmethod
    def load(cls, version: str) -> "VanillaAssets | None":
        path = FIXTURES / f"vanilla_assets_{version}.json"
        if not path.is_file():
            return None
        data = json.loads(path.read_text())
        return cls(version, set(data["models"]), set(data["textures"]))


def _split(ref: str) -> tuple[str, str]:
    namespace, _, path = ref.rpartition(":")
    return (namespace or "minecraft"), path


def model_file(ref: str) -> str:
    namespace, path = _split(ref)
    return f"assets/{namespace}/models/{path}.json"


def texture_file(ref: str) -> str:
    namespace, path = _split(ref)
    return f"assets/{namespace}/textures/{path}.png"


class PackView:
    """The final resource pack as a set of paths, plus a way to read JSON in it."""

    def __init__(self, files: dict[str, str], copies: list[tuple[pathlib.Path, str]],
                 vanilla: VanillaAssets | None):
        self.text: dict[str, str] = {}
        self.disk: dict[str, pathlib.Path] = {}
        for rel, body in files.items():
            if rel.startswith("resourcepack/"):
                self.text[rel.removeprefix("resourcepack/")] = body
        for src, dest in copies:
            if not dest.startswith("resourcepack/"):
                continue
            base = dest.removeprefix("resourcepack/")
            if src.is_dir():
                for path in src.rglob("*"):
                    if path.is_file():
                        self.disk[f"{base}/{path.relative_to(src).as_posix()}"] = path
            elif src.is_file():
                self.disk[base] = src
        self.vanilla = vanilla

    def has_model(self, ref: str) -> bool:
        rel = model_file(ref)
        if rel in self.text or rel in self.disk:
            return True
        namespace, path = _split(ref)
        return namespace == "minecraft" and self.vanilla is not None \
            and path in self.vanilla.models

    def has_texture(self, ref: str) -> bool:
        rel = texture_file(ref)
        if rel in self.disk or rel in self.text:
            return True
        namespace, path = _split(ref)
        return namespace == "minecraft" and self.vanilla is not None \
            and path in self.vanilla.textures

    def read_model(self, ref: str) -> dict | None:
        """The JSON of a pack-provided model; None for vanilla (which is trusted)."""
        rel = model_file(ref)
        if rel in self.text:
            return json.loads(self.text[rel])
        if rel in self.disk:
            return json.loads(self.disk[rel].read_text())
        return None


def missing_references(view: PackView, roots: dict[str, str]) -> list[str]:
    """Resolve each ``{model_ref: who_uses_it}`` transitively; describe every gap."""
    problems: list[str] = []
    seen: set[str] = set()

    def walk(ref: str, chain: str) -> None:
        if ref.startswith("builtin/") or ref in seen:
            return
        seen.add(ref)
        if not view.has_model(ref):
            problems.append(f"  {chain}: model {ref} is not in the pack")
            return
        doc = view.read_model(ref)
        if doc is None:
            return  # vanilla model: the client has it, along with its own chain
        for slot, value in sorted((doc.get("textures") or {}).items()):
            if isinstance(value, str) and not value.startswith("#") \
                    and not view.has_texture(value):
                problems.append(f"  {chain} -> {ref}: texture {slot}={value} "
                                f"is not in the pack")
        parent = doc.get("parent")
        if isinstance(parent, str):
            walk(parent, f"{chain} -> {ref}")

    for ref, user in sorted(roots.items()):
        walk(ref, user)
    return problems


def extract(jar: pathlib.Path) -> dict[str, list[str]]:
    models: set[str] = set()
    textures: set[str] = set()
    with zipfile.ZipFile(jar) as zf:
        for name in zf.namelist():
            if name.startswith("assets/minecraft/models/") and name.endswith(".json"):
                models.add(name.removeprefix("assets/minecraft/models/")[:-5])
            elif name.startswith("assets/minecraft/textures/") and name.endswith(".png"):
                textures.add(name.removeprefix("assets/minecraft/textures/")[:-4])
    return {"models": sorted(models), "textures": sorted(textures)}


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__)
        return 2
    jar, version = pathlib.Path(argv[1]), argv[2]
    data = {"source": f"assets/minecraft of the {version} client jar ({jar.name})",
            **extract(jar)}
    FIXTURES.mkdir(exist_ok=True)
    out = FIXTURES / f"vanilla_assets_{version}.json"
    out.write_text(json.dumps(data, indent=0, sort_keys=True) + "\n")
    print(f"wrote {out}: {len(data['models'])} models, {len(data['textures'])} textures")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
