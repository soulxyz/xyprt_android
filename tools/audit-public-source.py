#!/usr/bin/env python3
"""Fail if tracked Git history contains private recovery material or obvious credentials."""
from __future__ import annotations
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

PRIVATE_PATH = re.compile(
    r"(^|/)(?:\.local-build|local-build|private-signing|private-ml|private-models|private-features|signing)(?:/|$)"
    r"|(^|/)(?:config\.local\.php|sources\.local\.php|model_keys\.local\.php|asset_keys\.local\.php|keystore\.properties|\.env(?:\..*)?)$"
    r"|(?:^|/).*\.(?:jks|keystore|p12|pfx|pem|key)$"
    r"|(?:^|/)passwords(?:_[^/]*)?\.txt$",
    re.IGNORECASE,
)

CONTENT_PATTERNS = [
    ("private key", re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")),
    ("AWS access key", re.compile(rb"AKIA[0-9A-Z]{16}")),
    ("Google API key", re.compile(rb"AIza[0-9A-Za-z_-]{30,}")),
    ("GitHub token", re.compile(rb"gh[pousr]_[A-Za-z0-9]{30,}")),
]


def git(*args: str, text: bool = True):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=text, stderr=subprocess.STDOUT)


def fail(message: str) -> None:
    print(f"FAIL  {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    try:
        git("rev-parse", "--is-inside-work-tree")
    except Exception as exc:
        fail(f"not a git repository: {exc}")

    tracked = [p for p in git("ls-files").splitlines() if p]
    def is_private_path(path: str) -> bool:
        if path == ".env.example" or path.endswith("/.env.example"):
            return False
        return bool(PRIVATE_PATH.search(path))

    bad_current = sorted(p for p in tracked if is_private_path(p))
    if bad_current:
        fail("private paths are tracked now:\n  " + "\n  ".join(bad_current))

    object_lines = git("rev-list", "--objects", "--all").splitlines()
    historical_paths: list[str] = []
    object_ids: set[str] = set()
    for line in object_lines:
        oid, _, path = line.partition(" ")
        if path and is_private_path(path):
            historical_paths.append(path)
        object_ids.add(oid)
    if historical_paths:
        fail("private paths exist in Git history:\n  " + "\n  ".join(sorted(set(historical_paths))))

    # Scan unique historical blobs. Skip large binaries; path rules above should catch private binaries.
    checked = 0
    for oid in object_ids:
        try:
            if git("cat-file", "-t", oid).strip() != "blob":
                continue
            size = int(git("cat-file", "-s", oid).strip())
            if size > 5_000_000:
                continue
            data = git("cat-file", "blob", oid, text=False)
        except subprocess.CalledProcessError:
            continue
        checked += 1
        for label, pattern in CONTENT_PATTERNS:
            if pattern.search(data):
                fail(f"{label} signature found in historical blob {oid}")

    print(f"PASS  public-source safety: {len(tracked)} tracked paths, {checked} historical text/small blobs checked")


if __name__ == "__main__":
    main()
