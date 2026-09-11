#!/usr/bin/env python3
"""Compare two sessions: working vs failing."""
import json
import sys
from pathlib import Path

def inspect(path: Path, label: str, max_assistant: int = 2):
    print(f"\n=== {label} ===")
    print(f"path: {path}")
    if not path.exists():
        print("  MISSING")
        return
    lines = path.read_text(encoding='utf-8').splitlines()
    print(f"  lines: {len(lines)}")
    roles = {}
    for ln in lines:
        try:
            obj = json.loads(ln)
        except Exception:
            continue
        r = obj.get('role', '?')
        roles[r] = roles.get(r, 0) + 1
    print(f"  roles: {roles}")
    # Show first 2 assistant think blocks
    a_count = 0
    for ln in lines:
        if a_count >= max_assistant:
            break
        try:
            obj = json.loads(ln)
        except Exception:
            continue
        if obj.get('role') != 'assistant':
            continue
        a_count += 1
        for blk in obj.get('content', []):
            if blk.get('type') == 'text':
                t = blk.get('text', '')[:300]
                print(f"  [asst #{a_count}] text-start: {t!r}")
                break
    # Show first tool_use input
    print(f"  first tool_use inputs:")
    shown = 0
    for ln in lines:
        if shown >= 3:
            break
        try:
            obj = json.loads(ln)
        except Exception:
            continue
        if obj.get('role') != 'assistant':
            continue
        for blk in obj.get('content', []):
            if blk.get('type') == 'tool_use':
                inp = blk.get('input', {})
                print(f"    {blk.get('name')}: input={inp}")
                shown += 1
                break

# Working sessions
inspect(Path(r'D:\tmp\abc_1\.aethercode\sessions\3e6f2af3-186c-4949-b52b-8692f2133e04.jsonl'), 'abc_1 working')
inspect(Path(r'D:\tmp\abc.r97prs-prerun.20260818-120837\.aethercode\sessions\d0be2618-9326-4866-93fd-e5256aa380a8.jsonl'), 'abc.r97prs-prerun working')

# Failing session
inspect(Path(r'D:\tmp\.aethercode\sessions\2026-09-05T06-47-44.155696300Z_2baefad9.jsonl'), '2baefad9 FAILING')

# Also check 158f1df0 in abc_4
inspect(Path(r'D:\tmp\abc_4\.aethercode\sessions\2026-09-05T09-03-36.887072100Z_158f1df0.jsonl'), '158f1df0 abc_4')
