"""R344 release: build aethercode-0.2.71.zip.

Mirrors scripts/build-r250-zip.py — compresses release/aethercode-0.2.71/
into release/aethercode-0.2.71.zip with a consistent aethercode-0.2.71/
archive root. Excludes OS / trash metadata.
"""
import sys
import zipfile
from pathlib import Path

RELEASE_ROOT = Path(r"D:\work\workspace\idea\engine\AetherCode\release")
VERSION = "0.2.71"
SRC_DIR = RELEASE_ROOT / f"aethercode-{VERSION}"
OUT_ZIP = RELEASE_ROOT / f"aethercode-{VERSION}.zip"
EXCLUDE_NAMES = {"_trash_r344", ".DS_Store", "Thumbs.db"}


def iter_files(root: Path):
    for p in sorted(root.rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(root)
        # any directory part equal to an EXCLUDE_NAMES entry, or any
        # filename starting with an EXCLUDE_NAMES entry (covers
        # R344's `_trash_r344_providers.yaml` left by daemon
        # bootstrap that we renamed).
        skip = False
        for part in rel.parts:
            for ex in EXCLUDE_NAMES:
                if part == ex or part.startswith(ex):
                    skip = True
                    break
            if skip:
                break
        if skip:
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
        print(f"  removed old {OUT_ZIP.name}")

    with zipfile.ZipFile(OUT_ZIP, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for src, rel in files:
            arc = f"aethercode-{VERSION}/" + rel.as_posix()
            zf.write(src, arc)

    size = OUT_ZIP.stat().st_size
    print(f"OK: {OUT_ZIP}  {size:,} bytes  ({size/1024/1024:.2f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())