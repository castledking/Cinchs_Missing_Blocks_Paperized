#!/usr/bin/env python3
"""The upstream mod this port is built from: pinned, fetched, verified, and bumped.

The plugin jar bundles a pack generated from Cinch's Missing Blocks at one pinned
upstream commit (upstream/upstream.properties). Builds fetch that commit from GitHub;
if GitHub can't be reached they use the copy kept in upstream/ (the fallback), and
either way the files are checked against the pin's content hash, so a build is the
same whichever source it came from.

Only the files the generator reads are kept and hashed - INCLUDE below - which keeps
the fallback small. The hash is over those files' paths and bytes, not the archive:
GitHub's zips aren't promised to be byte-stable.

    python3 tools/upstream.py fetch --out DIR [--offline]
        extract the pinned upstream into DIR (GitHub, else the fallback)
    python3 tools/upstream.py check
        compare the pin with upstream's latest version; prints JSON, exit 10 if newer
    python3 tools/upstream.py bump [--commit SHA]
        move the pin to upstream's latest commit (or SHA), refresh the fallback

Upstream publishes no tags or releases; a release is a change of `version=` in its
gradle.properties on the default branch, so that is what `check` compares. If it
starts tagging, the newest tag wins.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import pathlib
import shutil
import sys
import urllib.error
import urllib.request
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
UPSTREAM = HERE.parent / "upstream"
PIN = UPSTREAM / "upstream.properties"

#: Paths the generator reads from the mod (modsource, mod_data, generate_pack).
INCLUDE = ("common/src/main/resources/", "fabric/src/main/java/", "gradle.properties")
TIMEOUT = 30


def read_pin() -> dict[str, str]:
    pin = {}
    for line in PIN.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            pin[key.strip()] = value.strip()
    return pin


def write_pin(pin: dict[str, str]) -> None:
    PIN.write_text(
        "# The upstream commit the bundled pack is generated from. Moved by\n"
        "# `python3 tools/upstream.py bump` (the upstream-watch workflow does this and\n"
        "# opens a PR); never edited by hand. content_sha256 covers the files the\n"
        "# generator reads, so the GitHub copy and the fallback are provably the same.\n"
        + "".join(f"{key}={pin[key]}\n" for key in
                  ("repo", "branch", "version", "commit", "content_sha256")))


def fallback_path(pin: dict[str, str]) -> pathlib.Path:
    return UPSTREAM / f"cinchs-missing-blocks-{pin['version']}-{pin['commit'][:8]}.zip"


def _get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "cmb-paperized-upstream"})
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
        return response.read()


def _files_from_archive(data: bytes) -> dict[str, bytes]:
    """The INCLUDE files from a GitHub archive (one top-level folder) or a fallback zip."""
    files: dict[str, bytes] = {}
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = [n for n in archive.namelist() if not n.endswith("/")]
        prefix = ""
        if names and all("/" in n for n in names):
            top = names[0].split("/", 1)[0] + "/"
            if all(n.startswith(top) for n in names):
                prefix = top
        for name in names:
            rel = name[len(prefix):]
            if any(rel == inc or (inc.endswith("/") and rel.startswith(inc)) for inc in INCLUDE):
                files[rel] = archive.read(name)
    return files


def content_hash(files: dict[str, bytes]) -> str:
    digest = hashlib.sha256()
    for rel in sorted(files):
        digest.update(rel.encode() + b"\0" + hashlib.sha256(files[rel]).digest())
    return digest.hexdigest()


def _zip(files: dict[str, bytes]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for rel in sorted(files):
            info = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, files[rel])
    return buffer.getvalue()


def download(repo: str, commit: str) -> dict[str, bytes]:
    return _files_from_archive(_get(f"https://codeload.github.com/{repo}/zip/{commit}"))


def fetch(out: pathlib.Path, offline: bool) -> str:
    pin = read_pin()
    files, source = None, ""
    if not offline:
        try:
            files, source = download(pin["repo"], pin["commit"]), "github"
        except (urllib.error.URLError, OSError, zipfile.BadZipFile) as exc:
            print(f"upstream: GitHub unreachable ({exc}); using the fallback copy",
                  file=sys.stderr)
    if files is None:
        path = fallback_path(pin)
        if not path.is_file():
            raise SystemExit(f"upstream: no fallback copy at {path}")
        files, source = _files_from_archive(path.read_bytes()), "fallback"
    actual = content_hash(files)
    if actual != pin["content_sha256"]:
        raise SystemExit(f"upstream: {source} copy of {pin['commit'][:8]} does not match the "
                         f"pin (content {actual[:12]}, pinned {pin['content_sha256'][:12]})")
    if out.exists():
        shutil.rmtree(out)
    for rel, data in files.items():
        target = out / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    print(f"upstream: {pin['repo']} {pin['version']} @ {pin['commit'][:8]} from {source} "
          f"-> {out} ({len(files)} files)")
    return source


def _api(repo: str, path: str):
    return json.loads(_get(f"https://api.github.com/repos/{repo}{path}"))


def latest(repo: str, branch: str) -> dict[str, str]:
    """Upstream's newest release: the newest tag if it tags, else the branch head."""
    tags = _api(repo, "/tags?per_page=1")
    if tags:
        commit = tags[0]["commit"]["sha"]
        return {"commit": commit, "version": tags[0]["name"].lstrip("v")}
    commit = _api(repo, f"/commits/{branch}")["sha"]
    props = _get(f"https://raw.githubusercontent.com/{repo}/{commit}/gradle.properties").decode()
    version = next((line.split("=", 1)[1].strip() for line in props.splitlines()
                    if line.startswith("version=")), "unknown")
    return {"commit": commit, "version": version}


def check() -> int:
    pin = read_pin()
    new = latest(pin["repo"], pin["branch"])
    newer = new["version"] != pin["version"]
    print(json.dumps({"pinned": {"version": pin["version"], "commit": pin["commit"]},
                      "latest": new, "newer": newer}))
    return 10 if newer else 0


def bump(commit: str | None) -> None:
    pin = read_pin()
    if commit is None:
        new = latest(pin["repo"], pin["branch"])
    else:
        props = _get(f"https://raw.githubusercontent.com/{pin['repo']}/{commit}/gradle.properties").decode()
        new = {"commit": commit, "version": next(
            (line.split("=", 1)[1].strip() for line in props.splitlines()
             if line.startswith("version=")), "unknown")}
    files = download(pin["repo"], new["commit"])
    old_fallback = fallback_path(pin)
    pin.update(commit=new["commit"], version=new["version"], content_sha256=content_hash(files))
    fallback_path(pin).write_bytes(_zip(files))
    if old_fallback != fallback_path(pin) and old_fallback.exists():
        old_fallback.unlink()
    write_pin(pin)
    print(f"upstream: pinned {pin['version']} @ {pin['commit'][:8]} ({len(files)} files)")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("fetch")
    f.add_argument("--out", type=pathlib.Path, required=True)
    f.add_argument("--offline", action="store_true")
    sub.add_parser("check")
    b = sub.add_parser("bump")
    b.add_argument("--commit")
    args = parser.parse_args(argv[1:])
    if args.cmd == "fetch":
        fetch(args.out, args.offline)
        return 0
    if args.cmd == "check":
        return check()
    bump(args.commit)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
