"""R229: zip the new release dir."""
import os
import zipfile

release = r'D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.53'
zip_path = os.path.join(release, 'aethercode-0.2.53.zip')

count = 0
size = 0
with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for root, dirs, files in os.walk(release):
        for f in files:
            if f == 'aethercode-0.2.53.zip':
                continue
            full = os.path.join(root, f)
            arc = os.path.relpath(full, release)
            z.write(full, arc)
            count += 1
            size += os.path.getsize(full)
print(f'zipped {count} files ({size:,} bytes uncompressed)')
print(f'-> {zip_path}')

import hashlib
with open(zip_path, 'rb') as f:
    sha = hashlib.sha256(f.read()).hexdigest().upper()
print(f'zip SHA256: {sha}')

with zipfile.ZipFile(zip_path) as z:
    for info in sorted(z.infolist(), key=lambda x: -x.file_size):
        print(f'  {info.file_size:>12,}  {info.filename}')
