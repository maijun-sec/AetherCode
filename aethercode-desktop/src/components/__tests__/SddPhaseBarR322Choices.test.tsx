// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';

/**
 * R322 — structured ABCDE choices on SddPhaseBar.
 *
 * The user explicitly asked for lettered buttons (instead of icons)
 * so they don't have to remember which emoji means what, plus a
 * free-text "其他" input wired to the modify command. The bar now
 * renders:
 *   A — ✅ 接受 (approve)
 *   B — ✏️ 修改 (modify, focus textarea)
 *   C — ⏭️ 跳过 (skip, optional only)
 *   D — 🔁 重跑 (rerun)
 *   E — ✋ 暂停 (pause)
 * Plus a "其他" textarea wired to sendSsdCommand('modify', text).
 *
 * Source-pin style: read the files and assert the buttons + actions
 * + CSS grid layout.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const BAR = join(process.cwd(), 'src', 'components', 'SddPhaseBar.tsx');
const CSS = join(process.cwd(), 'src', 'components', 'SddPhaseBar.css');
const STORE = join(process.cwd(), 'src', 'store', 'index.ts');
const INPUT = join(process.cwd(), 'src', 'components', 'MessageInput.tsx');

const bar = readFileSync(BAR, 'utf8');
const css = readFileSync(CSS, 'utf8');
const store = readFileSync(STORE, 'utf8');
const input = readFileSync(INPUT, 'utf8');

describe('R322 SddPhaseBar lettered ABCDE choices', () => {
  it('renders four lettered buttons A/B/D/E (C-skip moved to chip strip)', () => {
    expect(bar).toMatch(/data-testid="sdd-btn-accept"[\s\S]*?data-sdd-choice="A"/);
    expect(bar).toMatch(/data-testid="sdd-btn-modify"[\s\S]*?data-sdd-choice="B"/);
    expect(bar).toMatch(/data-testid="sdd-btn-rerun"[\s\S]*?data-sdd-choice="D"/);
    expect(bar).toMatch(/data-testid="sdd-btn-pause"[\s\S]*?data-sdd-choice="E"/);
  });

  it('does NOT render a global C-skip button (R324: skip moved to chip)', () => {
    expect(bar).not.toMatch(/data-testid="sdd-btn-skip"/);
  });

  it('each button has a letter badge + label (R324: emoji dropped from labels, badge-only)', () => {
    expect(bar).toMatch(/<span className="sdd-btn-letter">A<\/span>/);
    expect(bar).toMatch(/<span className="sdd-btn-label">接受<\/span>/);
    expect(bar).toMatch(/<span className="sdd-btn-letter">D<\/span>/);
    expect(bar).toMatch(/<span className="sdd-btn-label">重跑<\/span>/);
  });

  it('C-skip button removed from action row (R324: moved to per-chip)', () => {
    // The previous C-skip button (with `waitingPhase.optional` /
    // `running phase.optional` guard) is gone. Skip is now per-
    // chip via the sdd-chip-skip-${id} buttons. Verify the old
    // skip-button code path no longer exists.
    expect(bar).not.toMatch(/data-testid="sdd-btn-skip"/);
  });

  it('rerun button calls sendSsdCommand("rerun")', () => {
    expect(bar).toMatch(/onClick=\{\(\)\s*=>\s*sendSsdCommand\(['"]rerun['"]\)\}/);
  });

  it('pause button calls sendSsdCommand("pause")', () => {
    expect(bar).toMatch(/onClick=\{\(\)\s*=>\s*sendSsdCommand\(['"]pause['"]\)\}/);
  });

  it('R328: C-skip-next button rendered inline when next phase is optional', () => {
    expect(bar).toMatch(/data-testid="sdd-btn-skip-next"/);
    expect(bar).toMatch(/data-sdd-choice="C"/);
  });

  it('R328: F-jump-to dropdown rendered with idle phase targets', () => {
    expect(bar).toMatch(/data-testid="sdd-btn-jump-to"/);
    expect(bar).toMatch(/data-sdd-choice="F"/);
    expect(bar).toMatch(/sddJumpToPhase/);
  });

  it('"其他" textarea removed (R325: use main chat input instead)', () => {
    // R325 removed the per-bar textarea. Free-text feedback
    // goes into the main chat input below. Verify the old
    // textarea + 发送修订 button are gone.
    expect(bar).not.toMatch(/data-testid="sdd-revise-textarea"/);
    expect(bar).not.toMatch(/data-testid="sdd-btn-send-revise"/);
    expect(bar).not.toMatch(/setReviseText/);
  });
});

describe('R322 store: sendSsdCommand supports rerun + pause', () => {
  it('TS signature includes rerun + pause', () => {
    expect(store).toMatch(/sendSsdCommand:\s*\(cmd:\s*'approve'\s*\|\s*'modify'\s*\|\s*'skip'\s*\|\s*'rerun'\s*\|\s*'pause'/);
  });

  it('rerun branch flips phase back to running and re-emits phase prompt', () => {
    expect(store).toMatch(/if\s*\(cmd\s*===\s*['"]rerun['"]\)/);
    expect(store).toMatch(/state:\s*'running',\s*startedAt:\s*Date\.now\(\),\s*endedAt:\s*undefined,\s*path:\s*undefined/);
    expect(store).toMatch(/action:\s*['"]run['"]/);
  });

  it('pause branch emits a system message and does not advance', () => {
    expect(store).toMatch(/if\s*\(cmd\s*===\s*['"]pause['"]\)/);
    expect(store).toMatch(/⏸️ 已暂停/);
  });
});

describe('R322 MessageInput: widen skip/approve regex', () => {
  it('skip regex now matches "跳过下一阶段" / "跳过 phase 3"', () => {
    expect(input).toMatch(/\/\^\(?⏭️\|跳过\|skip\)\\b\/i/);
  });

  it('approve regex now matches "继续下一阶段" / "next" / "ok"', () => {
    expect(input).toMatch(/\/\^\(?✅\|继续下一阶段\|继续\|next\|ok\|advance\|go\)\\b\/i/);
  });
});

describe('R322 CSS: letter badges + grid layout', () => {
  it('CSS uses inline-flex for the buttons row (R324: compact horizontal)', () => {
    expect(css).toMatch(/\.sdd-phase-actions-buttons\s*\{[^}]*display:\s*inline-flex/);
  });

  it('CSS defines .sdd-btn-letter badge style', () => {
    expect(css).toMatch(/\.sdd-btn-letter\s*\{/);
    expect(css).toMatch(/font-family:\s*ui-monospace/);
  });
});

describe('R324 chip-level skip / jump-to affordances', () => {
  it('chip strip renders ⏭ skip button per optional phase (sddSkipPhase)', () => {
    expect(bar).toMatch(/sddSkipPhase\(id\)/);
    expect(bar).toMatch(/data-testid=\{`sdd-chip-skip-\$\{id\}`\}/);
  });

  it('chip strip renders ⏩ jump-to button for future phases (sddJumpToPhase)', () => {
    expect(bar).toMatch(/sddJumpToPhase\(i \+ 1\)/);
    expect(bar).toMatch(/data-testid=\{`sdd-chip-jump-\$\{id\}`\}/);
  });

  it('skip button gated on optional + idle/running', () => {
    expect(bar).toMatch(/canSkipThisPhase\s*=\s*sddActive\s*&&\s*\(state\s*===\s*['"]idle['"]\s*\|\|\s*state\s*===\s*['"]running['"]\)\s*&&\s*optional/);
  });

  it('jump-to button gated on idle + future phase', () => {
    expect(bar).toMatch(/canJumpToThisPhase\s*=\s*sddActive\s*&&\s*\(state\s*===\s*['"]idle['"]\)/);
    expect(bar).toMatch(/\(i\s*\+\s*1\)\s*>\s*sddCurrentPhase/);
  });
});

describe('R324 textarea removed (use main chat input)', () => {
  it('no per-bar textarea exists', () => {
    expect(bar).not.toMatch(/<textarea/);
  });
});

describe('R325 action row only renders when waitingPhase exists', () => {
  /**
   * R325: the action row was previously always visible when
   * `sddActive` (R323 fix for the scan-state-machine bug). The
   * user then complained it was too crowded while a phase was
   * running — they wanted the buttons to appear ONLY when a
   * phase was actually waiting for input. We now gate on
   * `waitingPhase` again, which is safe because:
   *   1. The chip scan is more reliable now (debug log added
   *      in R322 to surface scan failures).
   *   2. The user has multiple fallbacks: chip-skip /
   *      chip-jump affordances (per-chip) + the main chat
   *      input's keyword routing ("继续" / "跳过" / etc.).
   */
  it('source: SddPhaseBar gates action row on waitingPhase', () => {
    expect(bar).toMatch(/\{waitingPhase\s*&&\s*\(/);
  });
});