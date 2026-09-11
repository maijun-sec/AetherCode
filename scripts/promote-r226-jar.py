"""R226: promote 0.2.1 -> 0.2.51 in aethercode/dist, move old jars to _trash_r226."""
import os
import shutil

dist = r'D:\work\workspace\idea\engine\AetherCode\aethercode\dist'
trash = os.path.join(dist, '_trash_r226')

# 1) Move 0.2.49 and 0.2.50 jars out of dist (they're already backed up in trash)
for name in ('aethercode-0.2.49.jar', 'aethercode-0.2.50.jar'):
    src = os.path.join(dist, name)
    if os.path.exists(src):
        os.remove(src)
        print(f'removed {name} from dist/ (backup at _trash_r226/{name})')

# 2) Promote 0.2.1.jar -> 0.2.51.jar so desktop ancestor walk picks it
src = os.path.join(dist, 'aethercode-0.2.1.jar')
dst = os.path.join(dist, 'aethercode-0.2.51.jar')
shutil.copy2(src, dst)
print(f'promoted 0.2.1 -> 0.2.51 ({os.path.getsize(dst)} bytes)')

# 3) Verify
print('--- dist/ jars after promotion ---')
for f in sorted(os.listdir(dist)):
    full = os.path.join(dist, f)
    if os.path.isfile(full) and f.endswith('.jar'):
        print(f'  {f:30}  {os.path.getsize(full):>12,} B')

print('--- _trash_r226/ jars ---')
if os.path.isdir(trash):
    for f in sorted(os.listdir(trash)):
        full = os.path.join(trash, f)
        if os.path.isfile(full) and f.endswith('.jar'):
            print(f'  {f:30}  {os.path.getsize(full):>12,} B')
