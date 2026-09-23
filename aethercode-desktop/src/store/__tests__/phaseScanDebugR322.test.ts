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
import { join } from 'node:path';
import {
  scanLatestSddPhaseInMessages,
  SDD_PHASE_TITLE_TO_ID,
  SDD_PHASE_DONE_RE,
} from '../index';

const ML = join(process.cwd(), 'src', 'components', 'MessageList.tsx');
const ml = readFileSync(ML, 'utf8');

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
    const storeSrc = readFileSync(join(process.cwd(), 'src', 'store', 'index.ts'), 'utf8');
    // text_delta inline scan (gated on no-pending-confirm)
    expect(storeSrc).toMatch(/case 'text_delta'[\s\S]*?sddScanAndApplyPhaseUpdate\(\)/);
    // run_end inline scan (canonical backstop)
    expect(storeSrc).toMatch(/case 'run_end'[\s\S]*?sddScanAndApplyPhaseUpdate\(\)/);
  });
});
