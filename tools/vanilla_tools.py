#!/usr/bin/env python3
"""Which vanilla blocks need the correct tool to drop: fixtures/vanilla_tools_<ver>.json.

The mod's blocks copy a vanilla block's settings (``Block.Settings.copy(Blocks.CALCITE)``),
so a CMB block needs the correct tool exactly when the block it copies does. Vanilla
sets that in code, so it is read off a bootstrapped server
(``VanillaToolRequirements.java``) rather than guessed from mining tags. To refresh
(needs ``javac``/``java``)::

    python3 tools/vanilla_tools.py <server-dir> <version>

``<server-dir>`` is a Paper server that has run once.
"""

from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import tempfile

FIXTURES = pathlib.Path(__file__).resolve().parent / "fixtures"
HELPER = pathlib.Path(__file__).resolve().parent / "VanillaToolRequirements.java"


def load(version: str) -> set[str] | None:
    path = FIXTURES / f"vanilla_tools_{version}.json"
    return set(json.loads(path.read_text())["requires_tool"]) if path.is_file() else None


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__)
        return 2
    server, version = pathlib.Path(argv[1]), argv[2]
    jar = server / "versions" / version / f"paper-{version}.jar"
    classpath = ":".join([str(jar), *map(str, sorted((server / "libraries").rglob("*.jar")))])
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["javac", "-cp", classpath, "-d", tmp, str(HELPER)], check=True)
        out = pathlib.Path(tmp) / "tools.txt"
        subprocess.run(["java", "-cp", f"{classpath}:{tmp}", "VanillaToolRequirements", str(out)],
                       check=True, capture_output=True)
        ids = out.read_text().split()
    target = FIXTURES / f"vanilla_tools_{version}.json"
    target.write_text(json.dumps({"source": f"{version} server, requiresCorrectToolForDrops",
                                  "requires_tool": ids}, indent=1) + "\n")
    print(f"wrote {target}: {len(ids)} blocks need the correct tool")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
