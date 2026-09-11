"""对应历史 round engineering milestone: assemble release/aethercode-0.2.63.

Scope: R247 (bearer-token auth) + R248 (TLS via HttpsServer + PKCS12).
The engine / desktop / TUI bundle are unchanged from 0.2.62;
the only difference is the new daemon features in the canonical
jar (BankServer now supports both bearer auth and HTTPS).
"""
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(r'D:\work\workspace\idea\engine\AetherCode')
SRC_EXE_DIR = ROOT / 'aethercode-desktop' / 'src-tauri' / 'target' / 'release'
PREV_RELEASE = ROOT / 'release' / 'aethercode-0.2.62'
DST_DIR = ROOT / 'release' / 'aethercode-0.2.63'
SRC_JAR = ROOT / 'aethercode' / 'dist' / 'aethercode-0.2.63.jar'

DST_DIR.mkdir(parents=True, exist_ok=True)

# 1) Desktop exe
src_exe = SRC_EXE_DIR / 'aethercode-desktop.exe'
dst_exe = DST_DIR / 'desktop' / 'aethercode-desktop.exe'
dst_exe.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(src_exe, dst_exe)
print(f'copied {src_exe.name} -> {dst_exe.relative_to(ROOT)}')

# 2) Desktop resources/
src_res = SRC_EXE_DIR / 'resources'
dst_res = DST_DIR / 'desktop' / 'resources'
if dst_res.exists():
    shutil.rmtree(dst_res)
shutil.copytree(src_res, dst_res)
print(f'copied resources/ -> {dst_res.relative_to(ROOT)}')

# 3) Canonical jar
dst_jar = DST_DIR / 'aethercode-0.2.63.jar'
shutil.copy2(SRC_JAR, dst_jar)
print(f'copied {SRC_JAR.name} -> {dst_jar.relative_to(ROOT)}')

# 4) Carry TUI / readme / run-tui scripts from prev release
for rel in ('ac-tui', 'ac-tui-standalone.exe', 'README.md', 'run-tui.bat', 'run-tui.sh'):
    src = PREV_RELEASE / rel
    dst = DST_DIR / rel
    if not src.exists():
        print(f'  (skip: {rel} not in prev)')
        continue
    if src.is_dir():
        if dst.exists():
            shutil.rmtree(dst)
        shutil.copytree(src, dst)
        print(f'copied {rel}/')
    else:
        shutil.copy2(src, dst)
        print(f'copied {rel}')

# 5) Final state
print('\n=== final release state ===')
total = 0
for p in sorted(DST_DIR.rglob('*')):
    if p.is_file():
        size = p.stat().st_size
        total += size
        rel = p.relative_to(DST_DIR)
        print(f'  {str(rel):50}  {size:>14,} B')
print(f'  {"TOTAL":50}  {total:>14,} B')
print(f'\nDST: {DST_DIR}')
sys.exit(0)
