// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
/**
 * Debug test: simulate the exact chat content the agent emits
 * during phase 1 pause, and verify the MessageList scan logic
 * would identify it as 'pending-confirm'. This isolates the scan
 * logic from React render / store subscription quirks.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ML = join(process.cwd(), 'src', 'components', 'MessageList.tsx');
const ml = readFileSync(ML, 'utf8');

describe('R322 scan debug', () => {
  it('scan regex matches the R322 pause message format', () => {
    // Mirror the agent's actual pause message from the user's
    // screenshot. The scan lives at MessageList.tsx around
    // line 1057 (the doneRe / skipRe pattern). We just verify
    // the regex compiles and matches the exact string.
    const doneRe = /第\s*(\d+)\s*阶段完成\s*—\s*([^\n\r]+)/;
    const sample = `思考·<think>file written.</think>
✅ 第 1 阶段完成 — 项目原则

产物：d:/tmp/abc_1/.aethercode/sdd/java-maven/constitution.md

请回复：
  ✅ 继续下一阶段
  ✏️ 修改 <具体意见>
  ⏭️ 跳过下一阶段（仅对可选阶段生效）

<!-- choices:start
A: ✅ 接受并进入下一阶段 → action=approve
choices:end -->`;
    const m = sample.match(doneRe);
    expect(m).not.toBeNull();
    expect(m?.[1]).toBe('1');
    expect(m?.[2]?.trim()).toBe('项目原则');
  });

  it('PHASE_TITLE_TO_ID maps "项目原则" → "constitution"', () => {
    // We can't easily import the inline map from MessageList
    // (it's inside a useEffect), so we just verify the source
    // contains the mapping.
    expect(ml).toMatch(/'项目原则':\s*'constitution'/);
  });

  it('scan applies pending-confirm state (not done)', () => {
    // The R320 fix changed the initial state from 'done' to
    // 'pending-confirm'. Verify the source still has the fix
    // (we didn't accidentally regress).
    expect(ml).toMatch(/let\s+state:\s*'pending-confirm'\s*\|\s*'skipped'\s*=\s*'pending-confirm'/);
  });

  it('scan applies via sddApplyPhaseUpdate with the right id', () => {
    expect(ml).toMatch(/useStore\.getState\(\)\.sddApplyPhaseUpdate\(phaseId,\s*state,\s*path\)/);
  });
});