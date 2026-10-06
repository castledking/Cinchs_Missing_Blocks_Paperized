#!/usr/bin/env python3
"""Fetch the vanilla reference data the generator needs, into a local cache.

Two things are needed:

* ``blockstates/<block>.json`` - to resolve a vanilla block's own model for a state,
  which is what lets a converted carrier keep looking like itself.
* ``models/block/<model>.json`` - every model a cached blockstate references,
  transitively, so multipart parts can be resolved and baked.

The cache is derived data: it is mechanically re-fetchable and not tracked in git.
Re-run this after changing the carrier database or adding a Minecraft version.

    python3 tools/fetch_vanilla.py --version 1.21.1
    python3 tools/fetch_vanilla.py --verify       # report gaps, fetch nothing
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import pathlib
import sys
import urllib.error
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import vanilla_carriers as vc  # noqa: E402

CACHE = pathlib.Path(__file__).resolve().parent / "vanilla_templates"
MIRROR = "https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/{version}/assets/minecraft"
TIMEOUT = 30

# Minecraft moved to calendar versioning at 26.x and the community asset mirror only
# tracks the 1.x scheme. For a 26.x target the authoritative source is Mojang's own
# client jar, whose assets/minecraft tree has the same shape the loader expects.
MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"


def mojang_client_jar(version: str, cache_root: pathlib.Path) -> pathlib.Path | None:
    """Download and unpack assets/minecraft for `version` straight from Mojang."""
    import io
    import zipfile

    jar = cache_root / "minecraft-client.jar"
    if not jar.is_file():
        import json as _json
        import urllib.request
        with urllib.request.urlopen(MANIFEST, timeout=TIMEOUT) as r:
            manifest = _json.load(r)
        entry = next((v for v in manifest["versions"] if v["id"] == version), None)
        if entry is None:
            return None
        with urllib.request.urlopen(entry["url"], timeout=TIMEOUT) as r:
            version_json = _json.load(r)
        url = version_json["downloads"]["client"]["url"]
        with urllib.request.urlopen(url, timeout=TIMEOUT) as r:
            jar.parent.mkdir(parents=True, exist_ok=True)
            jar.write_bytes(r.read())

    target = cache_root / version
    if (target / "blockstates").is_dir():
        return target

    with zipfile.ZipFile(jar) as zf:
        for item in zf.namelist():
            if item.startswith("assets/minecraft/blockstates/") and item.endswith(".json"):
                _extract(zf, item, target / "blockstates" / pathlib.PurePosixPath(item).name)
            elif item.startswith("assets/minecraft/models/block/") and item.endswith(".json"):
                _extract(zf, item, target / "models" / "block" / pathlib.PurePosixPath(item).name)
    return target


def _extract(zf, member: str, dest: pathlib.Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(zf.read(member))


def wanted_blocks() -> set[str]:
    """Every vanilla block whose blockstate we could need.

    The carrier families, plus the full-cube pool, plus a few blocks the pack reads
    directly (the vanilla wall and fence templates the baker resolves through).
    """
    names: set[str] = set()
    for family in vc.FAMILIES.values():
        names.update(family.blocks)
    names.update(vc.SOLID_CARRIERS)
    return names


def fetch(url: str) -> bytes | None:
    try:
        with urllib.request.urlopen(url, timeout=TIMEOUT) as response:
            return response.read()
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError):
        return None


def store(path: pathlib.Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)


def get_blockstates(version: str, names: set[str], verify: bool) -> tuple[int, list[str]]:
    base = f"{MIRROR.format(version=version)}/blockstates"
    ok, missing = 0, []

    def one(name: str) -> tuple[str, bool]:
        path = CACHE / "blockstates" / f"{name}.json"
        if verify:
            return name, path.is_file() and path.stat().st_size > 10
        if path.is_file() and path.stat().st_size > 10:
            return name, True
        payload = fetch(f"{base}/{name}.json")
        if payload and payload[:1] == b"{":
            store(path, payload)
            return name, True
        return name, False

    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        for name, good in pool.map(one, sorted(names)):
            ok += good
            if not good:
                missing.append(name)
    return ok, missing


def referenced_models(version: str) -> tuple[int, list[str]]:
    """Fetch every model the cached blockstates point at, transitively.

    Multipart blockstates reference one model per part, and those models are usually
    thin children of a template that carries the actual geometry. Both the part and
    the template have to be present before anything can be baked.
    """
    base = f"{SOURCE.format(version=version)}/models/block"
    seen: set[str] = set()
    frontier: set[str] = set()

    for path in (root / "blockstates").glob("*.json"):
        try:
            data = json.loads(path.read_text())
        except json.JSONDecodeError:
            continue
        frontier |= collect_models(data)

    ok, missing = 0, []
    while frontier:
        batch, frontier = frontier - seen, frontier - seen
        seen |= batch

        def one(name: str) -> tuple[str, bool]:
            path = root / "models" / f"{name}.json"
            if path.is_file() and path.stat().st_size > 10:
                return name, True
            payload = fetch(f"{base}/{name}.json")
            if payload and payload[:1] == b"{":
                store(path, payload)
                return name, True
            return name, False

        with concurrent.futures.ThreadPoolExecutor(16) as pool:
            for name, good in pool.map(one, sorted(batch)):
                ok += good
                if not good:
                    missing.append(name)
                    continue
                try:
                    data = json.loads((root / "models" / f"{name}.json").read_text())
                except json.JSONDecodeError:
                    continue
                frontier |= collect_models(data)
    return ok, missing


def collect_models(node) -> set[str]:
    """Every model id referenced anywhere in a blockstate or model document."""
    found: set[str] = set()
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "model" and isinstance(value, str):
                found.add(value.split(":")[-1].split("/")[-1])
            elif key == "parent" and isinstance(value, str):
                found.add(value.split(":")[-1].split("/")[-1])
            else:
                found |= collect_models(value)
    elif isinstance(node, list):
        for item in node:
            found |= collect_models(item)
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--version", default=str(vc.VERIFIED_AGAINST))
    parser.add_argument("--verify", action="store_true",
                        help="report what is missing without fetching")
    args = parser.parse_args()

    names = wanted_blocks()
    print(f"vanilla reference data for {args.version}")

    # 26.x and later come from Mojang's client jar; 1.x from the community mirror.
    root = CACHE
    if vc.McVersion.parse(args.version) >= vc.McVersion(26, 0):
        unpacked = mojang_client_jar(args.version, CACHE)
        if unpacked is None:
            print(f"  {args.version} not found in the Mojang manifest")
            return 1
        root = unpacked
        print(f"  source: Mojang client jar -> {root}")
    else:
        print(f"  source: community mirror -> {CACHE}")
    print(f"  cache:  {root}")

    # From the client jar the whole cache is already written: mojang_client_jar() unpacks
    # every blockstate and every block model in the jar, which is a superset of what the
    # two network phases below assemble one file at a time.
    #
    # Continuing past it anyway is what made this script unusable for 26.x. Both network
    # phases were written for the 1.x community mirror, which does not carry calendar
    # versions: get_blockstates would 404 every name, and referenced_models() referenced a
    # `root` that was never one of its parameters and a `SOURCE` that does not exist in
    # this module -- a NameError waiting on the first 26.x run. It never fired because the
    # cache this script fills was populated by something else.
    if root != CACHE:
        pin_wall_templates(root)
        print("  source: complete (client jar); no per-file fetching needed")
        if args.verify:
            report(root)
            return 0
        return 0

    ok, missing = get_blockstates(args.version, names, args.verify)
    print(f"  blockstates: {ok}/{len(names)} present")
    if missing:
        print(f"    absent from {args.version} (expected for newer blocks): "
              f"{', '.join(missing[:8])}"
              + (f" ... and {len(missing) - 8} more" if len(missing) > 8 else ""))

    if args.verify:
        root = CACHE / args.version
        have = sum(1 for _ in (root / "models").glob("*.json")) \
            if (root / "models").is_dir() else 0
        print(f"  models: {have} present")
        return 0

    ok, missing = referenced_models(args.version)
    print(f"  models: {ok} fetched")
    if missing:
        print(f"    could not resolve: {', '.join(missing[:8])}"
              + (f" ... and {len(missing) - 8} more" if len(missing) > 8 else ""))

    pin_wall_templates(CACHE, args.version)
    print("  wall templates: pinned")
    return 0


#: The wall templates the wall baker seeds from, which it reads at the cache root.
WALL_TEMPLATES = ("template_wall_post", "template_wall_side", "template_wall_side_tall")


def pin_wall_templates(root: pathlib.Path, version: str | None = None) -> None:
    """Put the three wall templates where the generator looks for them.

    They sit at the cache root rather than under models/block/, so the unpack cannot
    satisfy the generator on its own. Copying beats fetching: for 26.x the mirror has no
    calendar version at all, so a fetch here always 404s and the build dies with "missing
    wall template".
    """
    for name in WALL_TEMPLATES:
        dest = CACHE / f"{name}.json"
        if dest.is_file():
            continue
        candidates = [root / "models" / "block" / f"{name}.json"]
        if version:
            candidates.append(root / version / "models" / "block" / f"{name}.json")
        for source in candidates:
            if source.is_file():
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(source.read_bytes())
                break


def report(root: pathlib.Path) -> None:
    """What the cache holds, for --verify."""
    states = root / "blockstates"
    models = root / "models" / "block"
    have_states = sum(1 for _ in states.glob("*.json")) if states.is_dir() else 0
    have_models = sum(1 for _ in models.glob("*.json")) if models.is_dir() else 0
    print(f"  blockstates: {have_states} present")
    print(f"  models: {have_models} present")
    missing = [n for n in WALL_TEMPLATES if not (CACHE / f"{n}.json").is_file()]
    if missing:
        print(f"  wall templates MISSING: {', '.join(missing)}")


if __name__ == "__main__":
    raise SystemExit(main())
