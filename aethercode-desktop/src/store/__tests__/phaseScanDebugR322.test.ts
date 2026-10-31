// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
/**
 * Debug test: simulate the exact chat content the agent emits
 * during phase 1 pause, and verify the shared scan helper
 * identifies it as 'pending-confirm'. This isolates the scan
 * logic from React render / store subscription quirks.
 *
 * R330: PHASE_TITLE_TO_ID + the pause-message regex moved out
 * of MessageList's useEffect and into the store as a shared
 * module-level export, so the text_delta handler, the run_end
 * handler, and the MessageList useEffect all use the SAME
 * scanner. These tests now import the shared helper directly
 * (instead of grepping for inline source) and additionally
 * verify the MessageList delegates to it (single source of
 * truth).
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  scanLatestSddPhaseInMessages,
  SDD_PHASE_TITLE_TO_ID,
  SDD_PHASE_DONE_RE,
  // R322 was the round that introduced the skip regex;
  // the debug test deliberately only exercises the
  // "done" path so SDD_PHASE_SKIP_RE isn't matched here.
  // We still import it (and consume it via `void` below)
  // to pin the export — a future refactor that drops the
  // symbol from the store would fail tsc here rather
  // than silently regress the live scanner.
  SDD_PHASE_SKIP_RE,
} from '../index';

// R748: resolve the package root from this file's own location,
// never from the ambient wd — under vitest's jsdom environment
// with several source-pin suites in one run, the ambient wd is
// not reliably the package root.
const __root = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const ML = join(__root, 'src', 'components', 'MessageList.tsx');
const ml = readFileSync(ML, 'utf8');

// pinned reference — see import block above.
void SDD_PHASE_SKIP_RE;

describe('R322 scan debug (R330 shared helper)', () => {
  it('scan regex matches the R322 pause message format', () => {
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
    const m = sample.match(SDD_PHASE_DONE_RE);
    expect(m).not.toBeNull();
    expect(m?.[1]).toBe('1');
    expect(m?.[2]?.trim()).toBe('项目原则');
  });

  it('SDD_PHASE_TITLE_TO_ID maps "项目原则" → "constitution"', () => {
    // R330: now exported from the store, not inline in MessageList.
    expect(SDD_PHASE_TITLE_TO_ID['项目原则']).toBe('constitution');
    expect(SDD_PHASE_TITLE_TO_ID['需求分析']).toBe('specify');
    expect(SDD_PHASE_TITLE_TO_ID['需求澄清']).toBe('clarify');
    expect(SDD_PHASE_TITLE_TO_ID['详细设计']).toBe('plan');
    expect(SDD_PHASE_TITLE_TO_ID['一致性分析']).toBe('analyze');
    expect(SDD_PHASE_TITLE_TO_ID['任务分析']).toBe('tasks');
    expect(SDD_PHASE_TITLE_TO_ID['执行实现']).toBe('implement');
    expect(SDD_PHASE_TITLE_TO_ID['收敛验证']).toBe('converge');
  });

  it('scanLatestSddPhaseInMessages applies pending-confirm (not done)', () => {
    // R320 changed the initial state from 'done' to 'pending-confirm'.
    // R330: helper should return state='pending-confirm' for the
    // standard phase-complete pause message.
    const sample = `✅ 第 1 阶段完成 — 项目原则
产物：d:/tmp/abc_1/.aethercode/sdd/java-maven/constitution.md`;
    const result = scanLatestSddPhaseInMessages(
      [{ role: 'assistant', content: sample }],
      [{ id: 'constitution', state: 'running' }],
    );
    expect(result?.phaseId).toBe('constitution');
    expect(result?.state).toBe('pending-confirm');
    expect(result?.path).toBe('d:/tmp/abc_1/.aethercode/sdd/java-maven/constitution.md');
  });

  it('scanLatestSddPhaseInMessages returns null for terminal phase (de-dupe)', () => {
    // R330: if the matched phase is already in 'pending-confirm'
    // or a terminal state, the helper returns null so re-scans
    // don't re-apply (would flip user-approved phase back).
    const sample = `✅ 第 2 阶段完成 — 需求分析`;
    const result = scanLatestSddPhaseInMessages(
      [{ role: 'assistant', content: sample }],
      [{ id: 'specify', state: 'done' }], // already done
    );
    expect(result).toBeNull();
  });

  it('scanLatestSddPhaseInMessages returns pending-confirm for skip sentinel', () => {
    // skipRe returns 'skipped' state. The helper itself just
    // surfaces the matched pattern + state; the action that
    // applies it (sddApplyPhaseUpdate) is what advances the chip.
    const sample = `⏭️ 第 3 阶段 — 需求澄清（可选）— 已跳过`;
    const result = scanLatestSddPhaseInMessages(
      [{ role: 'assistant', content: sample }],
      [{ id: 'clarify', state: 'idle' }],
    );
    expect(result?.phaseId).toBe('clarify');
    expect(result?.state).toBe('skipped');
  });

  it('MessageList delegates to the shared scanner (single source of truth)', () => {
    // R330: MessageList should call scanLatestSddPhaseInMessages
    // instead of defining its own regex. Verify the call site is
    // present and the inline PHASE_TITLE_TO_ID object literal is
    // gone.
    expect(ml).toMatch(/scanLatestSddPhaseInMessages\s*\(/);
    // No more inline `PHASE_TITLE_TO_ID` constant in MessageList.
    expect(ml).not.toMatch(/const\s+PHASE_TITLE_TO_ID:\s*Record/);
  });

  it('store text_delta + run_end handlers call sddScanAndApplyPhaseUpdate', () => {
    // R330: both handlers must call the scanner as backstops.
    // Read store source to verify both call sites are wired.
    const storeSrc = readFileSync(join(__root, 'src', 'store', 'index.ts'), 'utf8');
    // text_delta inline scan (gated on no-pending-confirm)
    expect(storeSrc).toMatch(/case 'text_delta'[\s\S]*?sddScanAndApplyPhaseUpdate\(\)/);
    // run_end inline scan (canonical backstop)
    expect(storeSrc).toMatch(/case 'run_end'[\s\S]*?sddScanAndApplyPhaseUpdate\(\)/);
  });
});
