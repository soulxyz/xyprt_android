#!/usr/bin/env python3
from __future__ import annotations
import hashlib
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    ".local-build/aar/onnxruntime-android-1.24.1.aar": "133134e2b01ffb91f8f66d46a3149f7fe402ac2e3e2d1df4f8fc679e999689f6",
    ".local-build/aar/opencv-4.13.0.aar": "4d9cc797cb2bafb685dc2953aaf9ac9f983b9c7b98cf68fbc701d4490556ebf7",
    ".local-build/jars/core-3.5.3.jar": "8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82",
    ".local-build/jars/kotlin-serialization-compiler-plugin.jar": "0ae65b5473d81f6bc5beb8de3752586e2211f87ba2c19179c41d71f188c7501f",
    ".local-build/jars/kotlinx-serialization-json-jvm-1.6.2.jar": "8d2718bb042e830b12b7fb10af26d0fba43de1f1f9ffe0a6b131d4d251aac2cc",
    ".local-build/jars/zxing-compile-stubs.jar": "497434d73ec59caa8ff3f5eaee4fe818c930c32ecca61e9b53a8b5b5cdb6443e",
}

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

bad = False
for rel, want in EXPECTED.items():
    p = ROOT / rel
    if not p.is_file():
        print(f"MISSING  {rel}")
        bad = True
        continue
    got = sha256(p)
    if got != want:
        print(f"MISMATCH {rel}\n  want {want}\n  got  {got}")
        bad = True
    else:
        print(f"OK       {rel}")

sys.exit(1 if bad else 0)
