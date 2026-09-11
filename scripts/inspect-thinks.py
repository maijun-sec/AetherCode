#!/usr/bin/env python3
"""Inspect the model's <think> blocks in a session jsonl."""
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
