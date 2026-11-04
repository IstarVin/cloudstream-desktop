#!/usr/bin/env python3
"""Plan, prepare notes for, or publish independent Cloudstream releases."""
import argparse
from functools import cmp_to_key
import json
import os
from pathlib import Path
import re
import subprocess
import sys

VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?")


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def has_origin():
    return "origin" in git("remote").split()


def fetch_tags():
    """Make local tags reflect origin so version planning cannot use a stale clone."""
    if has_origin():
        try:
            subprocess.check_output(["git", "fetch", "--tags", "origin"], text=True, stderr=subprocess.STDOUT)
        except subprocess.CalledProcessError as error:
            raise ValueError(f"Could not fetch tags from origin; resolve before planning versions:\n{error.output}")


def parse(version):
    match = VERSION.fullmatch(version)
    if not match:
        raise ValueError(f"Invalid version: {version}")
    prerelease = match[4]
    if prerelease and any(p.isdigit() and len(p) > 1 and p.startswith("0") for p in prerelease.split(".")):
        raise ValueError(f"Numeric prerelease identifiers cannot have leading zeros: {version}")
    return tuple(map(int, match.groups()[:3])), prerelease


def compare(a, b):
    av, ap = parse(a)
    bv, bp = parse(b)
    if av != bv:
        return (av > bv) - (av < bv)
    if ap == bp:
        return 0
    if ap is None or bp is None:
        return 1 if ap is None else -1
    for x, y in zip(ap.split("."), bp.split(".")):
        if x == y:
            continue
        if x.isdigit() and y.isdigit():
            return (int(x) > int(y)) - (int(x) < int(y))
        if x.isdigit() != y.isdigit():
            return -1 if x.isdigit() else 1
        return (x > y) - (x < y)
    return (len(ap.split(".")) > len(bp.split("."))) - (len(ap.split(".")) < len(bp.split(".")))


def plan(target, requested):
    versions = []
    for tag in git("tag", "--list", f"{target}-v*").splitlines():
        version = tag.removeprefix(f"{target}-v")
        try:
            parse(version)
        except ValueError:
            continue
        versions.append(version)
    previous = max(versions, key=cmp_to_key(compare)) if versions else None
    if requested in ("patch", "minor", "major"):
        numbers = list(parse(previous or "0.0.0")[0])
        index = {"major": 0, "minor": 1, "patch": 2}[requested]
        numbers[index] += 1
        numbers[index + 1:] = [0] * (2 - index)
        version = ".".join(map(str, numbers))
    else:
        parse(requested)
        version = requested
    if previous and compare(version, previous) <= 0:
        raise ValueError(f"{target}: {version} must be newer than {previous}")
    tag = f"{target}-v{version}"
    return {"target": target, "previous_tag": f"{target}-v{previous}" if previous else None,
            "version": version, "tag": tag, "notes": f"release-notes/{tag}.md"}


def prepare(items):
    for item in items:
        path = Path(item["notes"])
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists():
            path.write_text(f"# {item['target'].title()} {item['version']}\n\n"
                            "## Changes\n\nTODO: Replace with user-visible changes reviewed against the diff.\n")


def publish(items):
    # Complete all preflight checks before creating any tags or pushing anything.
    if git("status", "--porcelain"):
        raise ValueError("Commit release notes and other intended changes first; working tree must be clean.")
    if git("branch", "--show-current") != "main":
        raise ValueError("Publish from main after merging the release preparation.")
    if git("ls-remote", "--heads", "origin", "main"):
        subprocess.check_output(["git", "fetch", "origin", "main"], stderr=subprocess.STDOUT)
        if subprocess.run(["git", "merge-base", "--is-ancestor", "FETCH_HEAD", "HEAD"]).returncode != 0:
            raise ValueError("Local main is behind or diverged from origin/main; update it before publishing.")
    remote_tags = {line.split()[1] for line in git("ls-remote", "--tags", "origin").splitlines()}
    local_tags = set(git("tag", "--list").splitlines())
    for item in items:
        path = Path(item["notes"])
        body = path.read_text() if path.is_file() else ""
        if not body.strip() or re.search(r"\b(TODO|TBD)\b", body, re.I):
            raise ValueError(f"Write finished release notes in {path} before publishing.")
        git("ls-files", "--error-unmatch", str(path))
        if f"refs/tags/{item['tag']}" in remote_tags:
            raise ValueError(f"Remote tag already exists: {item['tag']}")
        if item["tag"] in local_tags:
            raise ValueError(f"Local tag already exists: {item['tag']}; inspect it before retrying.")
    created = []
    try:
        for item in items:
            subprocess.run(["git", "tag", "-a", item["tag"], "-m", f"Release {item['tag']}"], check=True)
            created.append(item["tag"])
        subprocess.run(["git", "push", "--atomic", "origin", "HEAD:refs/heads/main",
                        *[f"refs/tags/{tag}" for tag in created]], check=True)
    except BaseException:
        # Roll back only tags this run created and that did not reach origin.
        try:
            remote_tags = {line.split()[1] for line in git("ls-remote", "--tags", "origin").splitlines()}
        except (OSError, subprocess.CalledProcessError):
            remote_tags = set(created)  # remote state unknown: keep local tags for inspection
        for tag in created:
            if f"refs/tags/{tag}" not in remote_tags:
                subprocess.run(["git", "tag", "-d", tag], check=False, capture_output=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("plan", "prepare", "publish"))
    parser.add_argument("target", choices=("android", "server", "both"))
    parser.add_argument("bump", nargs="?", default="patch", help="patch (default), minor, major, or exact version")
    parser.add_argument("--android", help="Override Android bump or exact version")
    parser.add_argument("--server", help="Override server bump or exact version")
    args = parser.parse_args()
    root = Path(git("rev-parse", "--show-toplevel"))
    os.chdir(root)
    targets = ("android", "server") if args.target == "both" else (args.target,)
    requests = {target: getattr(args, target) or args.bump for target in targets}
    if args.command == "publish" and any(value in ("patch", "minor", "major") for value in requests.values()):
        parser.error("publish requires exact prepared versions, e.g. publish both --android 0.1.0 --server 0.2.0")
    fetch_tags()
    items = [plan(target, requests[target]) for target in targets]
    if args.command == "prepare":
        prepare(items)
    elif args.command == "publish":
        publish(items)
    print(json.dumps(items, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
