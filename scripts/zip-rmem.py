"""R-MEM-1/2/3/4 combined release: zip the new release dir."""
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
import os
import zipfile

release = r'D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.54'
zip_path = os.path.join(release, 'aethercode-0.2.54.zip')

count = 0
size = 0
with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for root, dirs, files in os.walk(release):
        for f in files:
            if f == 'aethercode-0.2.54.zip':
                continue
            full = os.path.join(root, f)
            arc = os.path.relpath(full, release)
            z.write(full, arc)
            count += 1
            size += os.path.getsize(full)
print(f'zipped {count} files ({size:,} bytes uncompressed)')

# SHA256 of the zip
h = hashlib.sha256()
with open(zip_path, 'rb') as f:
    for chunk in iter(lambda: f.read(65536), b''):
        h.update(chunk)
zip_sha = h.hexdigest().upper()
print(f'-> {zip_path}')
print(f'   SHA256 = {zip_sha}')
print(f'   size   = {os.path.getsize(zip_path):,} B')

with zipfile.ZipFile(zip_path) as z:
    for info in sorted(z.infolist(), key=lambda x: -x.file_size)[:15]:
        print(f'  {info.file_size:>14,}  {info.filename}')
