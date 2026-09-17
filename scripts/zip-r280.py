"""R280 combined release: zip the new release dir.
Same layout as R279 — release/aethercode-0.2.70/{ac-tui,desktop,aethercode-0.2.70.jar,RELEASE-NOTES.md}
gets bundled into release/aethercode-0.2.70.zip (sibling of the
release/ directory, NOT inside it). Only the desktop/aethercode.jar
inside changes (R279 -> R280 bytecode). The exe and the TUI bundle
are unchanged from R279 — only the jar is refreshed.
"""
import hashlib
import os
import zipfile

release = r'D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.70'
# Place the zip SIBLING of the release/ dir, not inside it. Past
# zip-rmem*.py scripts nested the zip inside release/<ver>/ which
# broke the canonical `release/aethercode-0.2.70.zip` layout. We
# write the zip here explicitly.
zip_path = r'D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.70.zip'

count = 0
size = 0
with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for root, dirs, files in os.walk(release):
        for f in files:
            if f == 'aethercode-0.2.70.zip':
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