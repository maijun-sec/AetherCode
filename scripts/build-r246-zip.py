"""R245 release: build aethercode-0.2.62.zip."""
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

import os
import sys
import zipfile
from pathlib import Path

RELEASE_ROOT = Path(r"D:\work\workspace\idea\engine\AetherCode\release")
SRC_DIR = RELEASE_ROOT / "aethercode-0.2.62"
OUT_ZIP = RELEASE_ROOT / "aethercode-0.2.62.zip"
EXCLUDE_NAMES = {"_trash_r246", ".DS_Store"}

def iter_files(root: Path):
    for p in sorted(root.rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(root)
        if any(part in EXCLUDE_NAMES for part in rel.parts):
            continue
        yield p, rel

def main() -> int:
    if not SRC_DIR.is_dir():
        print(f"missing src dir: {SRC_DIR}", file=sys.stderr)
        return 1

    files = list(iter_files(SRC_DIR))
    print(f"zipping {len(files)} files from {SRC_DIR} (excluding {EXCLUDE_NAMES})")
    if OUT_ZIP.exists():
        OUT_ZIP.unlink()

    with zipfile.ZipFile(OUT_ZIP, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for src, rel in files:
            arc = "aethercode-0.2.62/" + rel.as_posix()
            zf.write(src, arc)

    size = OUT_ZIP.stat().st_size
    print(f"OK: {OUT_ZIP}  {size:,} bytes  ({size/1024/1024:.2f} MB)")
    return 0

if __name__ == "__main__":
    sys.exit(main())
