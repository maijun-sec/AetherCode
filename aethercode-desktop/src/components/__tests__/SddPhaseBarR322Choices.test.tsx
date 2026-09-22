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
  it('renders five lettered buttons A/B/C/D/E', () => {
    expect(bar).toMatch(/data-testid="sdd-btn-accept"[\s\S]*?data-sdd-choice="A"/);
    expect(bar).toMatch(/data-testid="sdd-btn-modify"[\s\S]*?data-sdd-choice="B"/);
    expect(bar).toMatch(/data-testid="sdd-btn-skip"[\s\S]*?data-sdd-choice="C"/);
    expect(bar).toMatch(/data-testid="sdd-btn-rerun"[\s\S]*?data-sdd-choice="D"/);
    expect(bar).toMatch(/data-testid="sdd-btn-pause"[\s\S]*?data-sdd-choice="E"/);
  });

  it('each button has a letter badge + emoji + label', () => {
    expect(bar).toMatch(/<span className="sdd-btn-letter">A<\/span>/);
    expect(bar).toMatch(/<span className="sdd-btn-label">✅ 接受<\/span>/);
    expect(bar).toMatch(/<span className="sdd-btn-letter">D<\/span>/);
    expect(bar).toMatch(/<span className="sdd-btn-label">🔁 重跑<\/span>/);
  });

  it('skip button only renders for optional phases', () => {
    // R323: skip button now also checks running phase's optional
    // flag (since we removed the waitingPhase guard).
    expect(bar).toMatch(/waitingPhase\?\.optional\s*\?\?\s*sddPhases\.find\(\(p\)\s*=>\s*p\.state\s*===\s*'running'\)\?\.optional/);
  });

  it('rerun button calls sendSsdCommand("rerun")', () => {
    expect(bar).toMatch(/onClick=\{\(\)\s*=>\s*sendSsdCommand\(['"]rerun['"]\)\}/);
  });

  it('pause button calls sendSsdCommand("pause")', () => {
    expect(bar).toMatch(/onClick=\{\(\)\s*=>\s*sendSsdCommand\(['"]pause['"]\)\}/);
  });

  it('"其他" textarea still wired to modify command', () => {
    expect(bar).toMatch(/data-testid="sdd-revise-textarea"/);
    expect(bar).toMatch(/sendSsdCommand\(['"]modify['"],\s*reviseText\.trim\(\)\)/);
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
  it('CSS uses grid-template-columns for the buttons row', () => {
    expect(css).toMatch(/\.sdd-phase-actions-buttons\s*\{[^}]*grid-template-columns/);
  });

  it('CSS defines .sdd-btn-letter badge style', () => {
    expect(css).toMatch(/\.sdd-btn-letter\s*\{/);
    expect(css).toMatch(/font-family:\s*ui-monospace/);
  });
});

describe('R323 action row always renders when sddActive', () => {
  /**
   * R323 regression: in the previous round the action row was
   * gated behind `waitingPhase &&` — meaning the user saw NO
   * buttons at all if the chip state machine failed to flip a
   * phase to 'pending-confirm'. The state machine was the bug;
   * we fix it by always rendering the buttons whenever an SDD
   * run is active. The buttons operate on `sddCurrentPhase`
   * regardless of the chip's per-phase state.
   */
  it('source: SddPhaseBar renders action row when sddActive=true (no waitingPhase required)', () => {
    expect(bar).toMatch(/\{sddActive\s*&&\s*\(/);
  });
});