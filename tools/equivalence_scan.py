#!/usr/bin/env python3
"""Research: which vanilla states could be *manufactured* into freed carriers?

A ``block_state_mapping`` A -> B frees A without vanilla collateral only if a client
cannot tell A from B. This scans every vanilla block state for partners it is
visually indistinguishable from, and reports, per deferred family, how many
carrier states that could recover.

Two states are treated as interchangeable only if all of this holds:

* different blocks with **identical property maps** - so the client's shape logic
  sees the same inputs - and the same shape family (`_stairs`, `_slab`, ...);
* the client's blockstate resolves both to the same models, with the same rotation,
  uvlock and weights, compared as fully resolved geometry plus textures (parent
  chains followed), not by model name;
* **no face is tinted** (tint comes from the block, not the model);
* **every texture is fully opaque** (render layer comes from the block too).

Same-block pairs (stair corners that coincide by symmetry, trapdoor ``powered``) are
deliberately excluded: proving their collision is identical needs the server.

Collision is never proven here, only constrained. Anything this recovers has to be
confirmed live (place both states, compare collision boxes) before the allocator may
use it.

Usage::

    python3 tools/equivalence_scan.py <client.jar> <blocks.json report> \\
        <CraftEngine resources dir> [--json out.json]
"""

from __future__ import annotations

import collections
import io
import json
import pathlib
import sys
import zipfile

FAMILIES = {
    # family: (name suffix, extra state filter, projection properties)
    "slab": ("_slab", {"waterlogged": "false"}, ("type",)),
    "stairs": ("_stairs", {"waterlogged": "false", "shape": "straight"}, ("facing", "half")),
    "fence": ("_fence", {"waterlogged": "false"}, ("east", "north", "south", "west")),
    "pane": ("_pane", {"waterlogged": "false"}, ("east", "north", "south", "west")),
    "wall": ("_wall", {"waterlogged": "false"}, ("east", "north", "south", "up", "west")),
}


class Client:
    def __init__(self, jar: pathlib.Path):
        self.zip = zipfile.ZipFile(jar)
        self.names = set(self.zip.namelist())
        self._models: dict[str, dict | None] = {}
        self._opaque: dict[str, bool] = {}

    def blockstate(self, block: str) -> dict | None:
        path = f"assets/minecraft/blockstates/{block}.json"
        return json.loads(self.zip.read(path)) if path in self.names else None

    def _model(self, ref: str) -> dict | None:
        ns, _, path = ref.rpartition(":")
        key = f"assets/{ns or 'minecraft'}/models/{path}.json"
        if key not in self._models:
            self._models[key] = json.loads(self.zip.read(key)) if key in self.names else None
        return self._models[key]

    def resolve(self, ref: str, bindings: dict | None = None, depth: int = 0) -> dict:
        """Elements with every #texture substituted, following the parent chain."""
        doc = self._model(ref) or {}
        textures = dict(doc.get("textures") or {})
        if bindings:
            textures = {**textures, **bindings}
        parent = doc.get("parent")
        resolved_parent = (self.resolve(parent, textures, depth + 1)
                           if parent and not parent.startswith("builtin/") and depth < 16
                           else {"elements": None, "textures": textures})
        elements = doc.get("elements")
        if elements is None:
            elements = resolved_parent["elements"]
        merged = {**resolved_parent["textures"], **textures}

        def sub(ref_: str, seen=0) -> str:
            while isinstance(ref_, str) and ref_.startswith("#") and seen < 16:
                ref_ = merged.get(ref_[1:], ref_)
                seen += 1
            return ref_
        out = []
        for element in elements or []:
            element = json.loads(json.dumps(element))
            for face in (element.get("faces") or {}).values():
                if "texture" in face:
                    face["texture"] = sub(face["texture"])
            out.append(element)
        return {"elements": out if elements is not None else None,
                "textures": {k: sub(v) for k, v in merged.items()}}

    def opaque(self, texture) -> bool:
        # 26.3 allows object-form textures: {"sprite": ..., "force_translucent": ...}.
        # Forced translucency is a render-layer decision, so not comparable.
        if isinstance(texture, dict):
            if texture.get("force_translucent"):
                return False
            texture = texture.get("sprite", "")
        if not isinstance(texture, str):
            return False
        if texture not in self._opaque:
            ns, _, path = texture.rpartition(":")
            key = f"assets/{ns or 'minecraft'}/textures/{path}.png"
            ok = False
            if key in self.names:
                try:
                    from PIL import Image
                    image = Image.open(io.BytesIO(self.zip.read(key))).convert("RGBA")
                    ok = image.getextrema()[3][0] == 255
                except Exception:
                    ok = False
            self._opaque[texture] = ok
        return self._opaque[texture]


def applied(blockstate: dict, state: dict[str, str]) -> list[dict] | None:
    """The model entries a client applies for one state, or None if none match."""
    if "variants" in blockstate:
        for key, entry in blockstate["variants"].items():
            want = dict(p.split("=", 1) for p in key.split(",")) if key else {}
            if all(state.get(k) == v for k, v in want.items()):
                return entry if isinstance(entry, list) else [entry]
        return None
    parts = []
    for part in blockstate.get("multipart", []):
        if _when(part.get("when"), state):
            apply = part["apply"]
            parts.extend(apply if isinstance(apply, list) else [apply])
    return parts


def _when(cond, state) -> bool:
    if cond is None:
        return True
    if "OR" in cond:
        return any(_when(c, state) for c in cond["OR"])
    if "AND" in cond:
        return all(_when(c, state) for c in cond["AND"])
    return all(state.get(k) in str(v).split("|") for k, v in cond.items())


def signature(client: Client, entries: list[dict]) -> tuple[str, bool] | None:
    """Canonical visual identity, and whether it is safe to compare across blocks."""
    rendered = []
    safe = True
    for entry in entries:
        model = client.resolve(entry["model"])
        for element in model["elements"] or []:
            for face in (element.get("faces") or {}).values():
                if "tintindex" in face:
                    safe = False
                if not client.opaque(face.get("texture", "")):
                    safe = False
        rendered.append({"model": model["elements"], "x": entry.get("x", 0),
                         "y": entry.get("y", 0), "uvlock": entry.get("uvlock", False),
                         "weight": entry.get("weight", 1)})
    rendered.sort(key=lambda r: json.dumps(r, sort_keys=True))
    return json.dumps(rendered, sort_keys=True), safe


def main(argv: list[str]) -> int:
    if len(argv) < 4:
        print(__doc__)
        return 2
    client = Client(pathlib.Path(argv[1]))
    report = json.loads(pathlib.Path(argv[2]).read_text())
    resources = pathlib.Path(argv[3])
    out_json = pathlib.Path(argv[argv.index("--json") + 1]) if "--json" in argv else None

    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    from block_identities import Identities
    from block_mappings import BlockMappings
    from preparse_claims import PreParseClaims

    # Identity for every vanilla block, straight from the report.
    blocks = {}
    for block_id, entry in report.items():
        name = block_id.removeprefix("minecraft:")
        default = next(s for s in entry["states"] if s.get("default"))
        blocks[name] = {"properties": {k: sorted(v) for k, v in entry.get("properties", {}).items()},
                        "default": default.get("properties", {}), "states": len(entry["states"])}
    identities = Identities("26.3", blocks)
    mappings = BlockMappings.load(resources, "26.3")
    ce_freed, ce_targets, _ = mappings.freed(identities)
    claims = PreParseClaims(resources, exclude_pack="cinchsmissingblocks", cache=None).collect()
    claimed = {identities.canonical(s)[0] for s in claims} - {None}

    # Group every state of every candidate block by (family, properties, look).
    classes: dict[tuple, list[str]] = collections.defaultdict(list)
    unsafe_skipped = collections.Counter()
    for family, (suffix, _, _) in FAMILIES.items():
        for name, entry in sorted(report.items()):
            short = name.removeprefix("minecraft:")
            if not short.endswith(suffix) or (family == "fence" and short.endswith("_gate")):
                continue
            bs = client.blockstate(short)
            if bs is None:
                continue
            for state in entry["states"]:
                props = state.get("properties", {})
                entries = applied(bs, props)
                if not entries:
                    continue
                sig, safe = signature(client, entries)
                if not safe:
                    unsafe_skipped[family] += 1
                    continue
                canon = identities.canonical(
                    f"minecraft:{short}[" + ",".join(f"{k}={v}" for k, v in props.items()) + "]")[0]
                classes[(family, json.dumps(props, sort_keys=True), sig)].append(canon)

    result: dict[str, dict] = {}
    for family, (suffix, filt, projection) in FAMILIES.items():
        available = []          # usable now or after adding a lossless mapping
        already = 0
        manufactured = 0
        groups = 0
        for (fam, props_json, _), members in classes.items():
            if fam != family:
                continue
            props = json.loads(props_json)
            if any(props.get(k) != v for k, v in filt.items()):
                continue
            members = sorted(set(members))
            if len(members) < 2:
                # Nothing visually identical to map onto; only CraftEngine's own
                # mappings could have freed it.
                available += [m for m in members if m in ce_freed and m not in claimed]
                already += sum(1 for m in members if m in ce_freed and m not in claimed)
                continue
            groups += 1
            # Keep one member as the display state (a CE target if there is one);
            # every other unclaimed, non-target member can be mapped onto it.
            keep = next((m for m in members if m in ce_targets), members[-1])
            for m in members:
                if m == keep or m in claimed or m in ce_targets:
                    continue
                available.append(m)
                if m in ce_freed:
                    already += 1
                else:
                    manufactured += 1
        buckets = collections.Counter()
        for state in available:
            props = dict(p.split("=", 1) for p in state.split("[", 1)[1][:-1].split(","))
            buckets[",".join(f"{k}={props[k]}" for k in projection)] += 1
        capacity = min(buckets.values()) if buckets and len(buckets) == _keys(projection, available, blocks) else 0
        result[family] = {
            "identical_groups": groups,
            "available_states": len(available),
            "already_freed_by_craftengine": already,
            "manufacturable_with_new_mappings": manufactured,
            "projection_buckets": dict(sorted(buckets.items())),
            "carrier_capacity_blocks": capacity,
            "skipped_tinted_or_translucent": unsafe_skipped[family],
            "carrier_blocks": sorted({s.split("[")[0] for s in available}),
        }

    for family, r in result.items():
        print(f"{family:7} groups={r['identical_groups']:4} available={r['available_states']:4} "
              f"(CE-freed {r['already_freed_by_craftengine']}, new {r['manufacturable_with_new_mappings']}) "
              f"capacity={r['carrier_capacity_blocks']:3} blocks  skipped(tint/alpha)="
              f"{r['skipped_tinted_or_translucent']}")
        print(f"        from: {r['carrier_blocks']}")
    if out_json:
        out_json.write_text(json.dumps(result, indent=1, sort_keys=True) + "\n")
    return 0


def _keys(projection, available, blocks) -> int:
    """How many distinct projection buckets a full carrier needs."""
    if not available:
        return -1
    block = available[0].split("[")[0].removeprefix("minecraft:")
    total = 1
    for k in projection:
        total *= len(blocks[block]["properties"][k])
    return total


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
