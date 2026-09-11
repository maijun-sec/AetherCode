"""R245: build release zip for aethercode-0.2.61.

Usage:
  python scripts/build-r245-zip.py
"""
import os
import zipfile
import sys
from pathlib import Path

RELEASE_ROOT = Path(r"D:\work\workspace\idea\engine\AetherCode\release")
SRC_DIR = RELEASE_ROOT / "aethercode-0.2.61"
OUT_ZIP = RELEASE_ROOT / "aethercode-0.2.61.zip"
EXCLUDE_NAMES = {"_trash_r245", ".DS_Store"}  # future backups, not in 0.2.61

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

    # DEFLATE level 6 = balance speed/ratio
    with zipfile.ZipFile(OUT_ZIP, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for src, rel in files:
            arc = "aethercode-0.2.61/" + rel.as_posix()
            zf.write(src, arc)

    size = OUT_ZIP.stat().st_size
    print(f"OK: {OUT_ZIP}  {size:,} bytes  ({size/1024/1024:.2f} MB)")
    return 0

if __name__ == "__main__":
    sys.exit(main())
