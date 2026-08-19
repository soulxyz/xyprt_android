#!/usr/bin/env python3
"""Fail if proprietary scan implementation/runtime/model material is tracked in public Android Git."""
from __future__ import annotations
import subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
FORBIDDEN_PATH_PARTS=("private-features/","app/src/cocreator/kotlin/io/github/soulxyz/xyprt/scanner/OnnxEnhancedScanEngine.kt")
FORBIDDEN_NAMES=("OnnxEnhancedScanEngine.kt", ".tflite", ".tflite.obf")
def git(*a): return subprocess.check_output(["git","-C",str(ROOT),*a],text=True)
tracked=[p for p in git("ls-files").splitlines() if p]
bad=[p for p in tracked if any(x in p for x in FORBIDDEN_PATH_PARTS) or any(p.lower().endswith(x.lower()) for x in FORBIDDEN_NAMES)]
if bad:
    print("FAIL proprietary source tracked:\n  "+"\n  ".join(sorted(bad)),file=sys.stderr); raise SystemExit(1)
main=(ROOT/"app/src/main/kotlin/io/github/soulxyz/xyprt/scanner/EnhancedScanEngine.kt").read_text()
if "Class.forName" in main or "OnnxEnhancedScanEngine" in main:
    print("FAIL public engine boundary still references private implementation",file=sys.stderr); raise SystemExit(1)
print(f"PASS proprietary-source boundary: {len(tracked)} tracked paths")
