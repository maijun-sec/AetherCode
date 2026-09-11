"""R227: copy new exe + resources/ to release dir, re-zip."""
import os
import shutil

src_dir = r'D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\src-tauri\target\release'
dst_dir = r'D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.51'

# 1) Copy new exe
src_exe = os.path.join(src_dir, 'aethercode-desktop.exe')
dst_exe = os.path.join(dst_dir, 'desktop', 'aethercode-desktop.exe')
os.makedirs(os.path.dirname(dst_exe), exist_ok=True)
shutil.copy2(src_exe, dst_exe)
print(f'copied {src_exe}\n     -> {dst_exe}')

# 2) Copy new resources/ alongside the exe
src_res = os.path.join(src_dir, 'resources')
dst_res = os.path.join(dst_dir, 'desktop', 'resources')
if os.path.exists(dst_res):
    shutil.rmtree(dst_res)
shutil.copytree(src_res, dst_res)
print(f'copied {src_res}\n     -> {dst_res}')

# 3) Verify sizes
for root, dirs, files in os.walk(dst_res):
    for f in files:
        full = os.path.join(root, f)
        rel = os.path.relpath(full, dst_res)
        print(f'  resources/{rel}: {os.path.getsize(full):>12,} B')

# 4) Copy the canonical jar too
src_jar = r'D:\work\workspace\idea\engine\AetherCode\aethercode\dist\aethercode-0.2.51.jar'
dst_jar = os.path.join(dst_dir, 'aethercode-0.2.51.jar')
if os.path.exists(dst_jar):
    os.remove(dst_jar)
shutil.copy2(src_jar, dst_jar)
print(f'copied {src_jar}\n     -> {dst_jar}')

# 5) Show final state
print('\n=== final release dir ===')
for root, dirs, files in os.walk(dst_dir):
    for f in sorted(files):
        full = os.path.join(root, f)
        rel = os.path.relpath(full, dst_dir)
        size = os.path.getsize(full)
        print(f'  {rel:35}  {size:>12,} B')
