#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    ".local-build/aar/opencv-4.13.0.aar": "4d9cc797cb2bafb685dc2953aaf9ac9f983b9c7b98cf68fbc701d4490556ebf7",
    ".local-build/jars/core-3.5.3.jar": "8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82",
    ".local-build/jars/kotlin-serialization-compiler-plugin.jar": "0ae65b5473d81f6bc5beb8de3752586e2211f87ba2c19179c41d71f188c7501f",
    ".local-build/jars/kotlinx-serialization-json-jvm-1.6.2.jar": "8d2718bb042e830b12b7fb10af26d0fba43de1f1f9ffe0a6b131d4d251aac2cc",
    ".local-build/jars/zxing-compile-stubs.jar": "497434d73ec59caa8ff3f5eaee4fe818c930c32ecca61e9b53a8b5b5cdb6443e",
}

REBUILD_VAULT = {
    "deps_vault_rebuild/opencv_r1/pocketprint-opencv-4.13.0-min-r1.aar": ".local-build/aar/opencv-4.13.0.aar",
    "deps_vault_rebuild/runtime/jars/core-3.5.3.jar": ".local-build/jars/core-3.5.3.jar",
    "deps_vault_rebuild/runtime/jars/kotlin-serialization-compiler-plugin.jar": ".local-build/jars/kotlin-serialization-compiler-plugin.jar",
    "deps_vault_rebuild/runtime/jars/kotlinx-serialization-json-jvm-1.6.2.jar": ".local-build/jars/kotlinx-serialization-json-jvm-1.6.2.jar",
    "deps_vault_rebuild/runtime/jars/zxing-compile-stubs.jar": ".local-build/jars/zxing-compile-stubs.jar",
}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def safe_extract(zf: zipfile.ZipFile, dest: Path) -> None:
    root = dest.resolve()
    for info in zf.infolist():
        member = Path(info.filename)
        if member.is_absolute() or ".." in member.parts:
            raise RuntimeError(f"unsafe zip member: {info.filename}")
        target = (dest / member).resolve()
        if root != target and root not in target.parents:
            raise RuntimeError(f"unsafe zip member: {info.filename}")
    zf.extractall(dest)


def find_payload_root(extracted: Path) -> Path | None:
    direct = extracted / ".local-build"
    if direct.is_dir():
        return extracted
    candidates = [p.parent for p in extracted.rglob(".local-build") if p.is_dir()]
    return candidates[0] if len(candidates) == 1 else None


def verify(base: Path) -> None:
    bad = []
    for rel, want in EXPECTED.items():
        p = base / rel
        if not p.is_file():
            bad.append(f"MISSING  {rel}")
            continue
        got = sha256(p)
        if got != want:
            bad.append(f"MISMATCH {rel}\n  want {want}\n  got  {got}")
        else:
            print(f"OK       {rel}")
    if bad:
        raise RuntimeError("\n".join(bad))


def restore_rebuild_vault(zf: zipfile.ZipFile, temp: Path) -> Path:
    names = set(zf.namelist())
    missing = [name for name in REBUILD_VAULT if name not in names]
    if missing:
        raise RuntimeError("Deps Vault 缺少必需文件:\n" + "\n".join(missing))
    for src, rel in REBUILD_VAULT.items():
        dst = temp / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        with zf.open(src) as inp, dst.open("wb") as out:
            shutil.copyfileobj(inp, out)
    return temp


def main() -> int:
    ap = argparse.ArgumentParser(description="Restore PocketPrint .local-build from Deps Vault and verify SHA-256")
    ap.add_argument("vault", type=Path, help="PocketPrint Deps Vault zip")
    ap.add_argument("--force", action="store_true", help="replace an existing .local-build after verification")
    args = ap.parse_args()

    vault = args.vault.expanduser().resolve()
    if not vault.is_file():
        print(f"vault not found: {vault}", file=sys.stderr)
        return 2

    target = ROOT / ".local-build"
    if target.exists() and not args.force:
        try:
            verify(ROOT)
            print(f"\n.local-build already valid: {target}")
            return 0
        except Exception:
            print("existing .local-build is incomplete or mismatched; rerun with --force", file=sys.stderr)
            return 3

    with tempfile.TemporaryDirectory(prefix="xyprt-deps-") as td:
        temp = Path(td)
        with zipfile.ZipFile(vault) as zf:
            bad = zf.testzip()
            if bad:
                raise RuntimeError(f"Deps Vault CRC failed at: {bad}")
            if "deps_vault_rebuild/opencv_r1/pocketprint-opencv-4.13.0-min-r1.aar" in zf.namelist():
                payload_root = restore_rebuild_vault(zf, temp)
            else:
                safe_extract(zf, temp)
                payload_root = find_payload_root(temp)
                if payload_root is None:
                    raise RuntimeError("Deps Vault 中没有可识别的 .local-build 或完整可重建布局")
        verify(payload_root)
        source = payload_root / ".local-build"
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(source, target)

    verify(ROOT)
    print(f"\nrestored: {target}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
