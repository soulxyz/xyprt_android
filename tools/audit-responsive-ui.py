#!/usr/bin/env python3
"""Small source-level guard for page-width regressions.

This is not a screenshot/layout replacement. It protects the specific class of bugs that caused
wide windows to keep a phone-sized left-aligned column or made max-width caps ineffective due to
Compose modifier ordering.
"""
from pathlib import Path
import re, sys

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/kotlin/io/github/soulxyz/xyprt/ui"
failures: list[str] = []

for p in UI.rglob("*.kt"):
    s = p.read_text(encoding="utf-8")
    # In Compose modifier order matters. fill first can consume the incoming width before widthIn
    # gets a chance to cap the child.
    for bad in (r"\.fillMaxWidth\(\)\s*\.widthIn\(\s*max\s*=", r"\.fillMaxSize\(\)\s*\.widthIn\(\s*max\s*="):
        if re.search(bad, s, re.S):
            failures.append(f"{p.relative_to(ROOT)}: max-width cap is applied after fill")

expected = {
    "ui/home/HomeScreen.kt": ("contentAlignment = Alignment.TopCenter", "widthIn(max = 980.dp).fillMaxSize()"),
    "ui/cocreator/CoCreatorScreen.kt": ("contentAlignment = Alignment.TopCenter", "widthIn(max = 900.dp)"),
    "ui/cocreator/EnhancedCapabilitiesScreen.kt": ("contentAlignment = Alignment.TopCenter", "widthIn(max = 900.dp).fillMaxSize()"),
    "ui/settings/SettingsScreen.kt": ("contentAlignment = Alignment.TopCenter", "widthIn(max = 900.dp)"),
    "ui/history/HistoryScreen.kt": ("contentAlignment = Alignment.TopCenter", "widthIn(max = 900.dp).fillMaxSize()"),
    "ui/info/InfoDialog.kt": ("widthIn(max = 640.dp).fillMaxWidth()",),
}
for rel, needles in expected.items():
    p = UI.parent / rel
    s = p.read_text(encoding="utf-8")
    for needle in needles:
        if needle not in s:
            failures.append(f"{p.relative_to(ROOT)}: missing responsive invariant {needle!r}")

home = (UI / "home/HomeScreen.kt").read_text(encoding="utf-8")
for needle in ("maxWidth < 360.dp", '"未连接"', "batteryPercent"):
    if needle not in home: failures.append(f"HomeScreen.kt: missing {needle!r}")

if failures:
    print("Responsive UI audit: FAIL")
    for f in failures: print(" -", f)
    sys.exit(1)
print(f"Responsive UI audit: PASS ({len(list(UI.rglob('*.kt')))} UI Kotlin files scanned)")
