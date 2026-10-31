"""R226: promote 0.2.1 -> 0.2.51 in aethercode/dist, move old jars to _trash_r226."""
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
