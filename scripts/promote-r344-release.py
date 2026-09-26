"""R344 release: assemble release/aethercode-0.2.71/.

Round R341-R343 = desktop-side ProviderModelPicker / two-tier provider
config cascade. The TUI is unchanged from R341 (TUI source files were
not touched by R341-R343). The Java daemon picks up R343 backend
changes (ProviderRegistry, DaemonRunner, ProviderSpec, RegistryHelper).

Includes:
  - canonical shaded CLI jar (rebuilt against R343 sources)
  - Tauri desktop exe + bundle/{nsis,msi} installers + resources/
  - TUI Ink bundle (ac-tui.js) next to the jar (auto-detection)
  - desktop exe copy in install/ + bundled daemon jar copy next to it
  - README explaining what's inside + smoke-test recipes
"""
import shutil
import sys
from pathlib import Path

ROOT = Path(r"D:\work\workspace\idea\engine\AetherCode")
RELEASE_ROOT = ROOT / "release"
VERSION = "0.2.71"
DST_DIR = RELEASE_ROOT / f"aethercode-{VERSION}"

SRC_EXE_DIR = ROOT / "aethercode-desktop" / "src-tauri" / "target" / "release"
SRC_JAR = ROOT / "aethercode" / "dist" / "aethercode-0.2.1.jar"
SRC_TUI_BUNDLE = ROOT / "aethercode" / "dist" / "ac-tui" / "ac-tui.js"
SRC_TUI_README = ROOT / "aethercode" / "dist" / "ac-tui" / "README.md"

DST_DIR.mkdir(parents=True, exist_ok=True)

def copy_file(src: Path, dst: Path, label: str):
    if not src.exists():
        print(f"  (missing: {src.relative_to(ROOT)})")
        return False
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)
    print(f"  copied {label}: {src.name} ({src.stat().st_size:,} B) -> {dst.relative_to(DST_DIR)}")
    return True

print(f"=== Promoting AetherCode {VERSION} (R344 snapshot) ===")
print(f"    dst: {DST_DIR}")
print()

# 1) Canonical shaded CLI jar (root level)
copy_file(SRC_JAR, DST_DIR / f"aethercode-{VERSION}.jar", "canonical jar")

# 2) TUI Ink bundle next to the jar (ac-tui auto-detects this path)
copy_file(SRC_TUI_BUNDLE, DST_DIR / "ac-tui" / "ac-tui.js", "TUI bundle")
copy_file(SRC_TUI_README, DST_DIR / "ac-tui" / "README.md", "TUI README")

# 3) Desktop installer bundle: pick only the *current* Tauri config
#    version (tauri.conf.json:version) from each of nsis/ and msi/.
#    Tauri builds are incremental and leave old version-tagged
#    installers behind, so we must filter or the release would
#    ship every historical build.
import json

tauri_conf = SRC_EXE_DIR.parent / "tauri.conf.json"
tauri_version = "0.3.0"  # default; fall back to JSON if readable
if tauri_conf.is_file():
    try:
        tauri_version = json.loads(tauri_conf.read_text(encoding="utf-8")).get("version", "0.3.0")
    except Exception:
        pass
print(f"  tauri version filter: {tauri_version}")

src_bundle = SRC_EXE_DIR / "bundle"
dst_bundle = DST_DIR / "desktop" / "bundle"
bundle_size = 0
bundle_count = 0
if src_bundle.is_dir():
    if dst_bundle.exists():
        shutil.rmtree(dst_bundle)
    for sub in ("nsis", "msi"):
        src_sub = src_bundle / sub
        if not src_sub.is_dir():
            continue
        matching = sorted(p for p in src_sub.iterdir()
                          if p.is_file() and tauri_version in p.name)
        if not matching:
            print(f"  (warning: no {tauri_version}* installers under {src_sub.relative_to(ROOT)})")
            continue
        dst_sub = dst_bundle / sub
        dst_sub.mkdir(parents=True, exist_ok=True)
        for src in matching:
            dst = dst_sub / src.name
            shutil.copy2(src, dst)
            bundle_size += src.stat().st_size
            bundle_count += 1
            print(f"    {sub}/{src.name}  ({src.stat().st_size:,} B)")
    print(f"  copied desktop bundle/{tauri_version}/: {bundle_count} files, {bundle_size:,} B")
else:
    print(f"  (missing: {src_bundle.relative_to(ROOT)})")

# 4) Desktop exe at install/ for portable use
copy_file(SRC_EXE_DIR / "aethercode-desktop.exe", DST_DIR / "desktop" / "aethercode-desktop.exe", "desktop exe")

# 5) Desktop resources/ (icons, capabilities, etc.)
src_res = SRC_EXE_DIR / "resources"
dst_res = DST_DIR / "desktop" / "resources"
if src_res.is_dir():
    if dst_res.exists():
        shutil.rmtree(dst_res)
    shutil.copytree(src_res, dst_res)
    res_size = sum(p.stat().st_size for p in dst_res.rglob("*") if p.is_file())
    print(f"  copied desktop resources/: {res_size:,} B")
else:
    print(f"  (missing: {src_res.relative_to(ROOT)})")

# 6) Daemon jar copy next to the desktop exe (the Tauri app spawns
#    `java -jar aethercode.jar` from its install dir, so the jar has
#    to live beside aethercode-desktop.exe).
copy_file(SRC_JAR, DST_DIR / "desktop" / "aethercode.jar", "daemon jar copy")

# 7) Final state
print()
print("=== final release state ===")
total = 0
for p in sorted(DST_DIR.rglob("*")):
    if p.is_file():
        size = p.stat().st_size
        total += size
        rel = p.relative_to(DST_DIR)
        print(f"  {str(rel):<60}  {size:>14,} B")
print(f"  {'TOTAL':<60}  {total:>14,} B  ({total/1024/1024:.2f} MB)")
print(f"\nDST: {DST_DIR}")