#!/usr/bin/env python3
"""Check if .minimax strings are in the bundled desktop JS."""
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
