#!/usr/bin/env python3
"""Inspect the model's <think> blocks in a session jsonl."""
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

import json
import re
import sys
from pathlib import Path

p = Path(r'D:\tmp\.aethercode\sessions\2026-09-05T06-47-44.155696300Z_2baefad9.jsonl')
lines = p.read_text(encoding='utf-8').splitlines()
print(f"total lines: {len(lines)}")
print()

idx = 0
for line in lines:
    try:
        obj = json.loads(line)
    except Exception:
        continue
    if obj.get('role') != 'assistant':
        continue
    idx += 1
    # find first text block with <think>
    think = None
    for blk in obj.get('content', []):
        if blk.get('type') == 'text':
            m = re.search(r'<think>(.*?)</think>', blk.get('text', ''), re.DOTALL)
            if m:
                think = m.group(1).strip()
                break
    # find tool_use blocks
    tools = [b for b in obj.get('content', []) if b.get('type') == 'tool_use']
    if think:
        t = think.replace('\n', ' ').replace('  ', ' ')[:200]
        print(f"[#{idx:>2}] THINK: {t}")
    if tools:
        for tu in tools:
            inp = tu.get('input', {})
            print(f"        TOOL: {tu.get('name')}  input_keys={list(inp.keys())}  has_command={'command' in inp}")
    if not think and not tools:
        print(f"[#{idx:>2}] (no think, no tool)")
