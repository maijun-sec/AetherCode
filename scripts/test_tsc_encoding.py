"""Quick test: does TypeScript accept → and · in a template literal?"""
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
