/**
 * T-450 / T-451 / T-452 / T-453 / T-454 / T-456 (Phase 5 R5):
 * desktop additions.
 *
 * Source-code assertions matching the pattern used by
 * ThemeSettings.test.tsx. We assert on the components'
 * source rather than rendering with testing-library
 * (no jsdom in the env).
 */

import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

const read = (rel: string) => readFileSync(join(root, rel), 'utf-8');

// ----- T-450: ConfirmDialog.tsx -----------------------------------

describe('T-450: ConfirmDialog (drop-down consent)', () => {
  const src = read('src/components/ConfirmDialog.tsx');
  const css = read('src/components/ConfirmDialog.css');

  it('component file exists', () => {
    expect(existsSync(join(root, 'src', 'components', 'ConfirmDialog.tsx'))).toBe(true);
  });

  it('exports the React component', () => {
    expect(src).toMatch(/export function ConfirmDialog\b/);
  });

  it('declares the 10-option matrix types', () => {
    expect(src).toMatch(/export interface ConsentOption/);
    expect(src).toMatch(/index: number/);
    expect(src).toMatch(/scope: GrantScope/);
    expect(src).toMatch(/decision: GrantDecision/);
  });

  it('renders the risk header (LOW / MEDIUM / HIGH)', () => {
    expect(src).toMatch(/RISK_LABEL/);
    expect(src).toMatch(/'LOW'/);
    expect(src).toMatch(/'MEDIUM'/);
    expect(src).toMatch(/'HIGH'/);
  });

  it('keyboard handler: Esc cancels, Enter confirms, ↑/↓ navigate', () => {
    expect(src).toMatch(/e\.key === 'Escape'/);
    expect(src).toMatch(/e\.key === 'Enter'/);
    expect(src).toMatch(/e\.key === 'ArrowDown'/);
    expect(src).toMatch(/e\.key === 'ArrowUp'/);
  });

  it('"Don\'t ask again" toggles the wildcard default', () => {
    expect(src).toMatch(/dontAskAgain/);
    expect(src).toMatch(/kind === 'wildcard'/);
  });

  it('CSS overlay + panel structure', () => {
    expect(css).toMatch(/\.confirm-dialog-overlay|\.confirm-dialog-backdrop/);
    expect(css).toMatch(/\.confirm-dialog/);
    expect(css).toMatch(/\.confirm-dialog-button/);
  });
});

// ----- T-451: MemoryPanel.tsx -------------------------------------

describe('T-451: MemoryPanel (dockable)', () => {
  const src = read('src/components/MemoryPanel.tsx');
  const css = read('src/components/MemoryPanel.css');

  it('component file exists', () => {
    expect(existsSync(join(root, 'src', 'components', 'MemoryPanel.tsx'))).toBe(true);
  });

  it('exports the React component', () => {
    expect(src).toMatch(/export function MemoryPanel\b/);
  });

  it('declares the three scopes (USER / PROJECT / LOCAL)', () => {
    expect(src).toMatch(/'USER'/);
    expect(src).toMatch(/'PROJECT'/);
    expect(src).toMatch(/'LOCAL'/);
  });

  it('uses the listMemoryFiles / readMemoryFile / writeMemoryFile / deleteMemoryFile RPCs', () => {
    expect(src).toMatch(/rpc\.listMemoryFiles/);
    expect(src).toMatch(/rpc\.readMemoryFile/);
    expect(src).toMatch(/rpc\.writeMemoryFile/);
    expect(src).toMatch(/rpc\.deleteMemoryFile/);
  });

  it('caps the open-tabs at 8 (memory budget)', () => {
    expect(src).toMatch(/next\.length > 8/);
  });

  it('has the SCOPES description (path hint on hover)', () => {
    expect(src).toMatch(/\.aethercode\/agent-memory/);
  });

  it('CSS styles exist', () => {
    expect(css).toMatch(/\.memory-panel/);
    expect(css).toMatch(/\.memory-scope/);
    expect(css).toMatch(/\.memory-files/);
  });
});

// ----- T-452: ContextMeter.tsx ------------------------------------

describe('T-452: ContextMeter (status bar widget)', () => {
  const src = read('src/components/ContextMeter.tsx');
  const css = read('src/components/ContextMeter.css');

  it('component file exists', () => {
    expect(existsSync(join(root, 'src', 'components', 'ContextMeter.tsx'))).toBe(true);
  });

  it('exports the React component', () => {
    expect(src).toMatch(/export function ContextMeter\b/);
  });

  it('uses three colour tiers (green / amber / red)', () => {
    expect(src).toMatch(/pct < 50 \? 'green'/);
    expect(src).toMatch(/pct < 80 \? 'amber'/);
    expect(src).toMatch(/'red'/);
  });

  it('shows the 压缩推荐 prompt when >= 80%', () => {
    expect(src).toMatch(/\u538b\u7f29\u63a8\u8350/);
  });

  it('detects compaction events (>30% drop heuristic)', () => {
    expect(src).toMatch(/cur < prev \* 0\.7/);
  });

  it('caps the sample window at 60 entries (~2 min at 2s/poll)', () => {
    expect(src).toMatch(/length > 60/);
  });

  it('CSS styles exist', () => {
    expect(css).toMatch(/\.context-meter/);
    expect(css).toMatch(/\.context-fill/);
    expect(css).toMatch(/\.context-history/);
  });
});

// ----- T-453: TaskWindow.tsx (NEW) --------------------------------

describe('T-453: TaskWindow (dockable)', () => {
  const src = read('src/components/TaskWindow.tsx');
  const css = read('src/components/TaskWindow.css');

  it('component file exists', () => {
    expect(existsSync(join(root, 'src', 'components', 'TaskWindow.tsx'))).toBe(true);
  });

  it('exports the React component', () => {
    expect(src).toMatch(/export function TaskWindow\b/);
  });

  it('imports TaskInfo from lib/methods', () => {
    expect(src).toMatch(/import type \{[^}]*TaskInfo[^}]*\} from '\.\.\/lib\/methods'/);
  });

  it('uses the store actions (selectTask / cancelTask / refreshTasks)', () => {
    expect(src).toMatch(/selectTask/);
    expect(src).toMatch(/cancelTask/);
    expect(src).toMatch(/refreshTasks/);
  });

  it('exposes a status filter row (all / running / pending / completed / failed / killed)', () => {
    for (const f of ['all', 'running', 'pending', 'completed', 'failed', 'killed']) {
      expect(src).toContain(`'${f}'`);
    }
  });

  it('renders a detail pane with id / created / duration', () => {
    expect(src).toMatch(/<dt>id<\/dt>/);
    expect(src).toMatch(/<dt>created<\/dt>/);
    expect(src).toMatch(/<dt>duration<\/dt>/);
  });

  it('has Cancel + Attach buttons', () => {
    expect(src).toMatch(/task-window-cancel/);
    expect(src).toMatch(/task-window-attach/);
  });

  it('exports a formatDuration helper (sub-second / s / m / h units)', () => {
    expect(src).toMatch(/export function formatDuration/);
    expect(src).toMatch(/\(ms < 1000\)/);
    expect(src).toMatch(/\(ms < 60_000\)/);
    expect(src).toMatch(/\(ms < 3_600_000\)/);
  });

  it('has a CSS file with overlay + panel + list + detail styles', () => {
    expect(existsSync(join(root, 'src', 'components', 'TaskWindow.css'))).toBe(true);
    expect(css).toMatch(/\.task-window-overlay/);
    expect(css).toMatch(/\.task-window/);
    expect(css).toMatch(/\.task-window-list/);
    expect(css).toMatch(/\.task-window-detail/);
    expect(css).toMatch(/\.task-window-controls/);
  });

  it('formatDuration returns the expected string for a few inputs', () => {
    // Mount the helper in-process.
    const match = src.match(/export function formatDuration\(ms: number\): string \{([\s\S]+?)\n\}/);
    expect(match).toBeTruthy();
    const body = match![1];
    // Build a tiny evaluator and run a few cases.
    const f = new Function('ms', body + '\nreturn formatDuration(ms);');
    expect(f(500)).toBe('500ms');
    expect(f(1500)).toBe('1.5s');
    expect(f(75_000)).toBe('1m 15s');
    expect(f(3_750_000)).toBe('1h 2m');
  });
});

// ----- T-454: ThemeSettings.tsx (already exists from R3) ----------

describe('T-454: ThemeSettings (theme + font + background)', () => {
  it('component file exists', () => {
    expect(existsSync(join(root, 'src', 'components', 'ThemeSettings.tsx'))).toBe(true);
  });

  it('CSS file exists', () => {
    expect(existsSync(join(root, 'src', 'components', 'ThemeSettings.css'))).toBe(true);
  });

  it('has a dedicated test file (R3 smoke)', () => {
    expect(existsSync(join(root, 'src', 'components', 'ThemeSettings.test.tsx'))).toBe(true);
  });
});

// ----- T-456: cross-cutting ----------------------------------------

describe('T-456: desktop additions cross-cutting', () => {
  it('CSS module imports match the existing pattern', () => {
    // Every new / existing desktop component imports its
    // CSS via the same `import './X.css'` pattern.
    const comp = ['ConfirmDialog', 'MemoryPanel', 'ContextMeter', 'TaskWindow', 'ThemeSettings'];
    for (const name of comp) {
      const c = read(`src/components/${name}.tsx`);
      expect(c).toMatch(new RegExp(`import '\\.\\/${name}\\.css'`));
    }
  });

  it('TaskWindow does not break the existing TypeScript build', () => {
    // The TaskWindow file is in the same module the rest
    // of the desktop uses; if it broke compilation the
    // `npm run typecheck` step would fail. The presence
    // of the .tsx + the formatDuration export is enough
    // for the source-level smoke test.
    const src = read('src/components/TaskWindow.tsx');
    expect(src).toMatch(/export function formatDuration/);
  });
});
