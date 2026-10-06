#!/usr/bin/env python3
"""The plugin's installer, against the generator: the same pack, whatever the config.

The jar bundles one pack with everything on, and PackInstaller removes what a
server's config.yml turns off, guided by intermediate/pieces.json. That is only right
if the result is what the generator itself would build for that config. This runs
the installer's real filter code (from the built jar, via InstallerHarness.java) and
compares the item, block, furniture and recipe ids and the category lists with a
generator build of the same settings - the generator being the external truth here.

Two configs: the defaults, and one that flips most switches. Needs the plugin built
(cd cmb && gradle build) and javac/java.
"""

from __future__ import annotations

import pathlib
import subprocess
import sys
import tempfile

import yaml

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
# The built plugin, wherever this tree keeps it. Two layouts again: the working repository
# builds into cmb/build/libs, the published repository into build/libs. Globbing for the jar
# also stops this pinning a version -- it pinned 0.1.0, so the fixture would have skipped
# itself forever the moment the version moved to 1.0.0, and a fixture that can only ever
# skip is worse than no fixture because it still looks like coverage.
def _find_jar() -> pathlib.Path | None:
    for libs in (ROOT / "cmb/build/libs", ROOT / "build/libs"):
        if not libs.is_dir():
            continue
        jars = sorted(
            j for j in libs.glob("cmb-*.jar")
            if "sources" not in j.name and "javadoc" not in j.name
        )
        if jars:
            return jars[-1]
    return None


JAR = _find_jar()
GRADLE = pathlib.Path.home() / ".gradle/caches/modules-2/files-2.1"
FAILURES: list[str] = []

# (harness overrides, build-config edits that mean the same)
CASES = {
    "defaults": ([], {}),
    "flipped": (["terracotta=true", "concrete=true", "viabackwards=true", "doubles=furniture",
                 "vs.vanilla=false", "hs.cmb=false", "hs.vanilla=false", "vs.disabled=calcite_brick"],
                {"  disable-terracotta-variants: true": "  disable-terracotta-variants: false",
                 "  disable-concrete-variants: true": "  disable-concrete-variants: false",
                 "  viabackwards: false": "  viabackwards: true",
                 "    doubles: block": "    doubles: furniture",
                 "    vanilla: true\n    disabled: []\n    # What two slabs":
                     "    vanilla: false\n    disabled: [calcite_brick]\n    # What two slabs",
                 "  horizontal-stairs:\n    cmb: true\n    vanilla: true":
                     "  horizontal-stairs:\n    cmb: false\n    vanilla: false"}),
}


def check(ok: bool, message: str) -> None:
    print(f"  {'ok  ' if ok else 'FAIL'}  {message}")
    if not ok:
        FAILURES.append(message)


def ids(root: pathlib.Path) -> dict:
    out = {k: set() for k in ("items", "blocks", "furniture", "recipes")}
    out["categories"] = {}
    for path in root.glob("configuration/**/*.yml"):
        data = yaml.safe_load(path.read_text()) or {}
        for key in ("items", "blocks", "furniture", "recipes"):
            out[key] |= set((data.get(key) or {}).keys())
        for cid, cat in (data.get("categories") or {}).items():
            out["categories"][cid] = set(cat["list"])
    return out


def classpath() -> str:
    jars = [JAR]
    for pattern in ("paper-api-*.jar", "gson-*.jar", "snakeyaml-2*.jar"):
        jars.append(next(p for p in sorted(GRADLE.rglob(pattern)) if "sources" not in p.name))
    return ":".join(map(str, jars))

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
    if JAR is None:
        return skip("needs the plugin built: run ./gradlew build")
    print("installer vs generator")
    print("-" * 62)
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        cp = classpath()
        subprocess.run(["javac", "-cp", cp, "-d", str(tmp / "classes"),
                        str(HERE / "InstallerHarness.java")], check=True)
        upstream = tmp / "upstream"
        subprocess.run([sys.executable, str(HERE / "upstream.py"), "fetch", "--out", str(upstream)],
                       check=True, capture_output=True)
        for name, (overrides, edits) in CASES.items():
            installed = tmp / f"installed-{name}"
            subprocess.run(["java", "-cp", f"{cp}:{tmp / 'classes'}",
                            "net.cinchtail.cinchsmissingblocks.cmb.pack.InstallerHarness",
                            str(JAR), str(installed), *overrides], check=True)
            config = (HERE / "build-config.yml").read_text()
            for old, new in edits.items():
                assert config.count(old) == 1, f"{name}: build-config edit not found: {old!r}"
                config = config.replace(old, new)
            (tmp / f"{name}.yml").write_text(config)
            generated = tmp / f"generated-{name}"
            subprocess.run([sys.executable, str(HERE / "generate_pack.py"), "--mod", str(upstream),
                            "--out", str(generated), "--mc-version", "26.3",
                            "--build-config", str(tmp / f"{name}.yml")],
                           check=True, capture_output=True)
            a, b = ids(installed), ids(generated)
            for key in ("items", "blocks", "furniture", "recipes"):
                diff = sorted(a[key] ^ b[key])
                check(not diff, f"{name}: {key} match ({len(a[key])})" + (f" - differ: {diff[:3]}" if diff else ""))
            cats = sorted(c for c in set(a["categories"]) | set(b["categories"])
                          if a["categories"].get(c) != b["categories"].get(c))
            check(not cats, f"{name}: category lists match" + (f" - differ: {cats[:3]}" if cats else ""))
    print("-" * 62)
    if FAILURES:
        print(f"{len(FAILURES)} check(s) failed")
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
