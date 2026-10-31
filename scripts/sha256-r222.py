#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R222: compute SHA256 of release artifacts."""
# Copyright (c) 2026 maijun
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

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
