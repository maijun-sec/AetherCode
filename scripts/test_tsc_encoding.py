"""Quick test: does TypeScript accept → and · in a template literal?"""
import subprocess
import tempfile
import os
import sys

CWD = 'D:\\work\\workspace\\idea\\engine\\AetherCode\\aethercode-desktop'

# Test 1: minimal file with → and ·
content = "const x = `\u2192${1 + 1}\u00b7test`;\n"
print(f'Test content: {content!r}')

with tempfile.NamedTemporaryFile(
    suffix='.ts', delete=False, mode='wb', prefix='utf8bom-',
) as f:
    f.write(b'\xef\xbb\xbf')
    f.write(content.encode('utf-8'))
    tmp = f.name

try:
    r = subprocess.run(
        ['cmd', '/c', 'npx', 'tsc', '--noEmit', '--target', 'ES2021', '--strict', tmp],
        capture_output=True, text=True, cwd=CWD, shell=True,
    )
    print(f'stdout: {r.stdout}')
    print(f'stderr: {r.stderr}')
    print(f'returncode: {r.returncode}')
finally:
    os.unlink(tmp)

# Test 2: with nested template literal (matching L571's structure)
print()
print('=== Test 2: nested template literal ===')
content2 = "const x = `\u2192${'a'}${'b' ? `\u00b7${'c'}` : ''}`;\n"
print(f'Test content: {content2!r}')

with tempfile.NamedTemporaryFile(
    suffix='.ts', delete=False, mode='wb', prefix='utf8bom-',
) as f:
    f.write(b'\xef\xbb\xbf')
    f.write(content2.encode('utf-8'))
    tmp = f.name

try:
    r = subprocess.run(
        ['cmd', '/c', 'npx', 'tsc', '--noEmit', '--target', 'ES2021', '--strict', tmp],
        capture_output=True, text=True, cwd=CWD, shell=True,
    )
    print(f'stdout: {r.stdout}')
    print(f'stderr: {r.stderr}')
    print(f'returncode: {r.returncode}')
finally:
    os.unlink(tmp)
