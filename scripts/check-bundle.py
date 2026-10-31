#!/usr/bin/env python3
"""Check if .minimax strings are in the bundled desktop JS."""
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

import re
import sys
from pathlib import Path

p = Path(r'D:\work\workspace\idea\engine\AetherCode\aethercode-desktop\dist\assets\index-By-FySOd.js')
data = p.read_bytes()
print(f'js size: {len(data):,} bytes')

# 1. Any leftover .minimax (path or otherwise)
matches = list(re.finditer(rb'minimax', data))
print(f'\nminimax count: {len(matches)}')
for m in matches[:5]:
    start = max(0, m.start() - 30)
    end = min(len(data), m.end() + 30)
    ctx = data[start:end].decode('utf-8', errors='replace')
    print(f'  pos {m.start()}: ...{ctx}...')

# 2. The new aethercode/skills references
print(f'\naethercode/skills count: {data.count(b".aethercode/skills")}')

# 3. The new welcome tile desc text
text = "从用户级 ~/.aethercode/skills 或项目级 ./.aethercode/skills 选一个".encode('utf-8')
print(f'\nwelcome text found: {text in data}')

# 4. The "AetherCode app" replacement
print(f'\nAetherCode app count: {data.count(b"AetherCode app")}')
print(f'MiniMax Code app count: {data.count(b"MiniMax Code app")}')
