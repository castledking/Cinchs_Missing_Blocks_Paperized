#!/usr/bin/env python3
"""What a vanilla block state string actually *is*.

``minecraft:barrel``, ``minecraft:barrel[facing=north]`` and
``minecraft:barrel[facing=north,open=false]`` are three spellings of one state:
CraftEngine resolves every ``state:`` through vanilla's BlockStateParser, which fills
any property left out from the block's default. Comparing state strings therefore
misses collisions, and a property that does not exist at all (chiseled_bookshelf's
imaginary ``occupied``) is only discovered when CraftEngine fails to parse it.

This module canonicalises a state against Mojang's own block report, so both cases
are build failures instead of surprises on a live server.

The data lives in ``fixtures/blocks_<version>.json``: properties, value sets and the
default state for every carrier candidate, extracted from the report the server jar's
data generator writes. To refresh it::

    java -DbundlerMainClass=net.minecraft.data.Main \\
        -jar server/cache/mojang_<version>.jar --reports --output /tmp/mcreports
    python3 tools/block_identities.py /tmp/mcreports/reports/blocks.json <version>
"""

from __future__ import annotations

import json
import pathlib
import sys

FIXTURES = pathlib.Path(__file__).resolve().parent / "fixtures"

#: Blocks kept in the fixture besides the carrier candidates, so exclusions stay
#: checkable: non-full-cube blocks that are never cube carriers.
REFERENCE_ONLY = ("anvil", "chipped_anvil", "damaged_anvil", "bell", "lectern")


class Identities:
    """Properties and defaults for the blocks a version's fixture covers."""

    def __init__(self, version: str, blocks: dict[str, dict]):
        self.version = version
        self.blocks = blocks

    @classmethod
    def load(cls, version: str) -> "Identities | None":
        path = FIXTURES / f"blocks_{version}.json"
        if not path.is_file():
            return None
        return cls(version, json.loads(path.read_text())["blocks"])

    def canonical(self, state: str) -> tuple[str | None, str | None]:
        """``(canonical, None)`` or ``(None, reason)`` for a state string.

        The canonical form names every property, in sorted order, with omitted ones
        taken from the default - exactly the state the parser would produce.
        """
        block, _, rest = state.partition("[")
        namespace, _, name = block.rpartition(":")
        if namespace not in ("", "minecraft"):
            return None, f"not a vanilla block: {block}"
        entry = self.blocks.get(name)
        if entry is None:
            return None, f"no identity data for minecraft:{name} on {self.version}"
        values = dict(entry["default"])
        for pair in filter(None, rest.rstrip("]").split(",")):
            prop, _, value = pair.partition("=")
            prop, value = prop.strip(), value.strip()
            if prop not in entry["properties"]:
                return None, f"minecraft:{name} has no property '{prop}'"
            if value not in entry["properties"][prop]:
                return None, f"minecraft:{name}[{prop}] has no value '{value}'"
            values[prop] = value
        inner = ",".join(f"{k}={values[k]}" for k in sorted(values))
        return f"minecraft:{name}" + (f"[{inner}]" if inner else ""), None


def extract(report: dict, blocks: set[str]) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for name in sorted(blocks):
        entry = report.get(f"minecraft:{name}")
        if entry is None:
            # Canonical but absent on this version, e.g. the infested walls. The
            # allocator filters those through the registry; nothing to record.
            continue
        default = next(s for s in entry["states"] if s.get("default"))
        out[name] = {
            "properties": {k: sorted(v) for k, v in
                           sorted(entry.get("properties", {}).items())},
            "default": dict(sorted(default.get("properties", {}).items())),
            "states": len(entry["states"]),
        }
    return out


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__)
        return 2
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
    import vanilla_carriers as vc

    report = json.loads(pathlib.Path(argv[1]).read_text())
    version = argv[2]
    wanted = (set(vc.SOLID_CARRIERS) | set(vc.FORMER_CUBE_CARRIERS)
              | set(REFERENCE_ONLY))
    for family in vc.FAMILIES.values():
        wanted |= set(family.blocks)
    data = {
        "source": f"Mojang data generator --reports, server jar {version}",
        "blocks": extract(report, wanted),
    }
    FIXTURES.mkdir(exist_ok=True)
    path = FIXTURES / f"blocks_{version}.json"
    path.write_text(json.dumps(data, indent=1, sort_keys=True) + "\n")
    print(f"wrote {path} ({len(data['blocks'])} blocks)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
