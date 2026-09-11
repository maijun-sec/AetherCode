#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R222: compute SHA256 of release artifacts."""
import hashlib
from pathlib import Path

REL = Path(r"D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.1")

artifacts = [
    "aethercode-0.2.1.jar",
    "ac-tui-standalone.exe",
    "ac-tui/ac-tui.js",
    "desktop/aethercode-desktop.exe",
]

def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest().lower()

print(f"{'artifact':<46} {'bytes':>12}  sha256")
print("-" * 95)
for name in artifacts:
    p = REL / name
    if not p.exists():
        print(f"{name:<46} MISSING")
        continue
    h = sha256(p)
    print(f"{name:<46} {p.stat().st_size:>12,}  {h}")

zip_path = REL.parent / "aethercode-0.2.1.zip"
if zip_path.exists():
    h = sha256(zip_path)
    print(f"{'aethercode-0.2.1.zip':<46} {zip_path.stat().st_size:>12,}  {h}")
