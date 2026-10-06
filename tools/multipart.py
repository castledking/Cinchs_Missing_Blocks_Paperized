#!/usr/bin/env python3
"""Resolve and flatten Minecraft models and multipart blockstates.

CraftEngine binds exactly one model per block state, but a lot of vanilla geometry
is a multipart blockstate: a wall is a post plus up to four arms, a fence is a post
plus arms plus a gate-side variant. Those parts have to be merged into one model,
which is what this module does.

It is deliberately generic rather than wall-specific. The same code path handles:

* the mod's own walls, whose parts are thin children of vanilla wall templates;
* vanilla carrier walls and fences, whose parts come from the vanilla blockstate we
  cached with tools/fetch_vanilla.py.

The source multipart definition stays the authority. Walls and fences are *not*
assumed to share a topology - a fence's post sits at a different height and its arms
differ, and the parts are read from the blockstate rather than assumed.

    vanilla blockstate
        |
        +-- multipart
              |
              +-- post
              +-- north
              +-- east
              +-- south
              +-- west
                    |
                    v
              resolve + flatten
                    |
                    v
             ordinary variants
"""

from __future__ import annotations

import dataclasses
import json
import pathlib


class ResolutionError(RuntimeError):
    """A model or blockstate could not be resolved."""


# --------------------------------------------------------------------------- #
# model documents
# --------------------------------------------------------------------------- #

@dataclasses.dataclass
class ModelDoc:
    parent: str | None
    textures: dict[str, str]
    elements: list[dict] | None
    faces: dict | None


def load_model(root: pathlib.Path, name: str) -> ModelDoc | None:
    """Load one model document, following the same search order as a resource pack.

    Namespaced ids are looked for in their own namespace first, then in ours, so
    both ``minecraft:block/cobblestone_wall_post`` and a mod-namespaced override of
    the same name resolve the way they would in game.

    Both a real resource pack layout (``assets/<ns>/models/block/...``) and the flat
    cache that tools/fetch_vanilla.py writes are accepted, so the same code resolves
    the mod's assets and the vanilla reference data.
    """
    namespace = name.split(":")[0] if ":" in name else "minecraft"
    short = name.split(":")[-1].split("/")[-1]
    # ``root`` may be an ordered tuple of roots: a mod model's parent chain runs from
    # the mod's own assets into vanilla's (a mod fence post inherits
    # minecraft:block/fence_post), so one root cannot resolve it.
    roots = root if isinstance(root, (tuple, list)) else (root,)
    candidates = [r / base for r in roots
                  for base in (f"assets/{namespace}/models/block/{short}.json",
                               f"assets/minecraft/models/block/{short}.json",
                               f"models/block/{short}.json",
                               f"models/{short}.json")]
    for path in candidates:
        if path.is_file():
            data = json.loads(path.read_text())
            return ModelDoc(
                parent=data.get("parent"),
                textures=data.get("textures") or {},
                elements=data.get("elements"),
                faces=data.get("faces"),
            )
    return None


def _short(name: str) -> str:
    return name.split(":")[-1].split("/")[-1]


def load_any(roots, name: str):
    """First match for a model across a search order, or None."""
    for root in roots:
        found = load_model(root, name)
        if found is not None:
            return found
    return None


def resolve(name: str, root: pathlib.Path, bindings: dict[str, str] | None = None,
            _seen: frozenset[str] = frozenset(),
            extra_roots: tuple[pathlib.Path, ...] = ()) -> dict:
    """Flatten a model inheritance chain into textures plus concrete elements.

    Vanilla's template models declare geometry and reference textures as ``#slot``;
    the child supplies the actual texture. Walking the chain and threading the
    bindings through is what turns that into something standalone.
    """
    bindings = bindings or {}
    if name in _seen:
        raise ResolutionError(f"cyclic model parent chain at {name}")
    # The parent chain has to search the same roots as the model itself: a fence post
    # lives in the mod's namespace while the template it inherits from lives in
    # vanilla's.
    roots = (*extra_roots, root)
    doc = load_any(roots, name)
    if doc is None:
        raise ResolutionError(f"model not found: {name}")

    # 26.3 allows a texture to be either a plain sprite id or an object carrying the
    # sprite plus flags, e.g. {"sprite": "minecraft:block/glass",
    # "force_translucent": true}. Normalise so everything downstream sees a sprite,
    # but remember that an object form was used so the flags survive.
    def _sprite(value):
        if isinstance(value, dict):
            return value.get("sprite", "")
        return value or ""

    local: dict[str, str] = {}
    for slot, ref in doc.textures.items():
        if isinstance(ref, str) and ref.startswith("#"):
            local[slot] = ""
        else:
            local[slot] = _sprite(ref)

    parent_resolved = {"textures": {}, "elements": [], "faces": None}
    if doc.parent:
        parent_resolved = resolve(doc.parent, root,
                                  {**bindings, **local},
                                  _seen | {name}, extra_roots)

    textures = dict(parent_resolved["textures"])
    for slot, ref in doc.textures.items():
        if isinstance(ref, str) and ref.startswith("#"):
            target = ref[1:]
            textures[slot] = (parent_resolved["textures"].get(target)
                              or bindings.get(target) or ref)
        else:
            textures[slot] = _sprite(ref)
    for slot, value in local.items():
        textures.setdefault(slot, value or bindings.get(slot, ""))

    elements = doc.elements if doc.elements is not None else parent_resolved["elements"]
    return {
        "textures": textures,
        "elements": json.loads(json.dumps(elements)),
        "faces": doc.faces if doc.faces is not None else parent_resolved["faces"],
    }


# --------------------------------------------------------------------------- #
# rotation
# --------------------------------------------------------------------------- #

_FACE_CYCLE = {"north": "east", "east": "south", "south": "west", "west": "north"}


def rotate_element(element: dict, x: int = 0, y: int = 0) -> dict:
    """Apply Minecraft model rotations to one element.

    Only multiples of 90 are legal in a model file and that is all vanilla uses for
    block shapes, so this deliberately does not attempt arbitrary angles.
    """
    element = json.loads(json.dumps(element))

    def rot(point: list[int]) -> list[int]:
        x0, y0, z0 = point
        for _ in range((y // 90) % 4):
            # Clockwise seen from above: +z becomes +x.
            x0, z0 = 16 - z0, x0
        for _ in range((x // 90) % 4):
            # Pitch forward: +z becomes +y.
            y0, z0 = z0, 16 - y0
        return [x0, y0, z0]

    if x or y:
        a, b = rot(element["from"]), rot(element["to"])
        element["from"] = [min(a[i], b[i]) for i in range(3)]
        element["to"] = [max(a[i], b[i]) for i in range(3)]

        if x:
            faces = {}
            for face, data in element.get("faces", {}).items():
                data = dict(data)
                if "uv" in data:
                    u0, u1, v0, v1 = data["uv"]
                    data["uv"] = [u0, 16 - v1, u1, 16 - v0]
                if (x // 90) % 2:
                    face = {"down": "up", "up": "down"}.get(face, face)
                faces[face] = data
            element["faces"] = faces

        if y:
            faces = {}
            for face, data in element.get("faces", {}).items():
                if face in _FACE_CYCLE:
                    for _ in range((y // 90) % 4):
                        face = _FACE_CYCLE[face]
                faces[face] = data
            element["faces"] = faces

    element.pop("__comment", None)
    return element


# --------------------------------------------------------------------------- #
# blockstates
# --------------------------------------------------------------------------- #

def load_blockstate(root: pathlib.Path, name: str) -> dict | None:
    namespace = name.split(":")[0] if ":" in name else "minecraft"
    short = _short(name)
    for base in (f"assets/{namespace}/blockstates/{short}.json",
                 f"assets/minecraft/blockstates/{short}.json",
                 f"blockstates/{short}.json"):
        path = root / base
        if path.is_file():
            return json.loads(path.read_text())
    return None


def _entry_applies(when, state: dict[str, str]) -> bool:
    """Whether a multipart selector matches, honouring OR/AND groups.

    ``when`` may be absent or explicitly null, both of which mean "always" - 26.3's
    fence blockstate uses ``"when": null`` for its post part where 1.21.1 had no
    unconditional part at all.
    """
    if not when:
        return True
    for key, value in when.items():
        if key == "OR":
            if not any(_entry_applies(sub, state) for sub in value):
                return False
        elif key == "AND":
            if not all(_entry_applies(sub, state) for sub in value):
                return False
        elif state.get(key) != value:
            return False
    return True


def flatten(root: pathlib.Path, blockstate: dict, state: dict[str, str],
            extra_roots: tuple[pathlib.Path, ...] = (),
            include_post: bool = True) -> dict:
    """Bake every part that applies to one blockstate into a single model.

    ``include_post`` compensates for something odd in the vanilla wall blockstate:
    its only post part is guarded by ``{"when": {"up": "true"}}``, so a short wall
    would resolve to arms with no post between them. Vanilla renders a post in every
    configuration, so when a blockstate declares a post part we always draw it and
    treat the guard as covering the tall variant only. The mod's own wall
    blockstates have the same shape, so this keeps carriers and mod walls identical.
    """
    elements: list[dict] = []
    particles: list[str] = []
    textures: dict[str, str] = {}
    added_models: list[str] = []

    def add(entry: dict) -> None:
        if isinstance(entry, list):
            for item in entry:
                add(item)
            return
        added_models.append(entry["model"].split("/")[-1])
        # Search the extra roots first and fall back to the primary one. resolve()
        # raises rather than returning None, so trying the primary root first would
        # abort before a fence template that only exists in the vanilla cache was
        # ever considered.
        if load_any((*extra_roots, root), entry["model"]) is None:
            raise ResolutionError(f"model not found: {entry['model']}")
        resolved = resolve(entry["model"], root, extra_roots=extra_roots)
        bound = resolved["textures"]
        for slot, value in bound.items():
            if slot == "particle" and value:
                particles.append(value)
        for element in resolved["elements"]:
            element = rotate_element(element, entry.get("x", 0), entry.get("y", 0))
            for face in element.get("faces", {}).values():
                ref = face.get("texture", "")
                if ref.startswith("#"):
                    slot = ref[1:]
                    face["texture"] = bound.get(slot) or textures.get(slot) or ref
            elements.append(element)

    variants = blockstate.get("variants")
    matched_variant = False
    if variants:
        for key, entry in variants.items():
            candidate = (dict(part.split("=", 1) for part in key.split(","))
                         if key else {})
            if all(candidate.get(k) == v for k, v in state.items() if k in candidate):
                add(entry)
                matched_variant = True
                break

    for part in blockstate.get("multipart", []):
        if _entry_applies(part.get("when", {}), state):
            add(part["apply"])

    if include_post and not any(m.endswith("_post") for m in added_models):
        for part in blockstate.get("multipart", []):
            model = part["apply"]["model"] if isinstance(part["apply"], dict) \
                else part["apply"][0]["model"]
            if model.endswith("_post"):
                add(part["apply"])
                break

    if not elements:
        raise ResolutionError(
            f"no model resolved for state {state} "
            f"(variants matched: {matched_variant})")

    out: dict = {"elements": elements}
    particle = particles[0] if particles else next(iter(textures.values()), None)
    if particle:
        out = {"textures": {"particle": particle}, **out}
    return out
