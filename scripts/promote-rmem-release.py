"""R-MEM-1/2/3/4 combined release: copy new exe + resources/ to release dir, re-zip."""
import os
import shutil

src_dir = r'D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src-tauri\target\release'
prev_release = r'D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.53'
dst_dir = r'D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.54'

# 1) Create dst dir
os.makedirs(dst_dir, exist_ok=True)

# 2) Copy new exe
src_exe = os.path.join(src_dir, 'aethercode-desktop.exe')
dst_exe = os.path.join(dst_dir, 'desktop', 'aethercode-desktop.exe')
os.makedirs(os.path.dirname(dst_exe), exist_ok=True)
shutil.copy2(src_exe, dst_exe)
print(f'copied {src_exe}\n     -> {dst_exe}')

# 3) Copy new resources/ alongside the exe
src_res = os.path.join(src_dir, 'resources')
dst_res = os.path.join(dst_dir, 'desktop', 'resources')
if os.path.exists(dst_res):
    shutil.rmtree(dst_res)
shutil.copytree(src_res, dst_res)
print(f'copied {src_res}\n     -> {dst_res}')

# 4) Copy canonical jar
src_jar = r'D:\work\workspace\idea\engine\AetherCode\aethercode\dist\aethercode-0.2.54.jar'
dst_jar = os.path.join(dst_dir, 'aethercode-0.2.54.jar')
shutil.copy2(src_jar, dst_jar)
print(f'copied {src_jar}\n     -> {dst_jar}')

# 5) Copy tui / readme / run-tui scripts from R229
for rel in ('ac-tui', 'ac-tui-standalone.exe', 'README.md', 'run-tui.bat', 'run-tui.sh'):
    src = os.path.join(prev_release, rel)
    dst = os.path.join(dst_dir, rel)
    if not os.path.exists(src):
        print(f'  (skip: {rel} not in prev)')
        continue
    if os.path.isdir(src):
        if os.path.exists(dst):
            shutil.rmtree(dst)
        shutil.copytree(src, dst)
        print(f'copied {rel}/')
    else:
        shutil.copy2(src, dst)
        print(f'copied {rel}')

# 6) Show final state
print('\n=== final release state ===')
total = 0
for root, dirs, files in os.walk(dst_dir):
    for f in sorted(files):
        full = os.path.join(root, f)
        rel = os.path.relpath(full, dst_dir)
        size = os.path.getsize(full)
        total += size
        print(f'  {rel:50}  {size:>14,} B')
print(f'  {"TOTAL":50}  {total:>14,} B')
