import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * persistent subagent toast + Esc hotkey.
 *
 * The prior round "auto-dismiss is user-hostile" lesson
 * applied to the subagent toast. legacy the toast
 * auto-dismissed after 4s; a user who kicked off a
 * background subagent and switched focus to another
 * window would return to find the toast already gone
 * and the StatusBar pill pointing at "subagent done"
 * with no way to see WHAT was done.
 *
 * R119 keeps the toast persistent until the user
 * dismisses it (click / Esc / new terminal event).
 * The × glyph becomes a focusable <button> with
 * aria-label so keyboard / screen-reader users can
 * reach it.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R119: SubagentToast is persistent', () => {
  const tsxSrc = readFileSync(join(root, 'src', 'components', 'SubagentToast.tsx'), 'utf-8');

  it('SubagentToast.tsx exists', () => {
    expect(tsxSrc.length).toBeGreaterThan(0);
  });

  it('removed the 4s auto-dismiss (no setTimeout for dismiss)', () => {
    // legacy: a setTimeout(... dismissSubagentTerminal, 4000)
    // call. R119 removes the auto-dismiss entirely. The
    // file should no longer reference TOAST_DURATION_MS
    // or a 4000ms dismiss timer.
    const stripped = tsxSrc
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/.*$/gm, '');
    expect(stripped).not.toMatch(/TOAST_DURATION_MS\s*=\s*4000/);
    expect(stripped).not.toMatch(/setTimeout\([^)]*dismissSubagentTerminal[^)]*4000/);
  });

  it('renders a focusable Dismiss <button>', () => {
    // The × glyph used to be a visual hint only;
    // R119 promotes it to a real <button> with
    // aria-label so keyboard / screen-reader users
    // can dismiss the toast.
    expect(tsxSrc).toMatch(/<button[\s\S]*?aria-label="Dismiss notification"/);
    expect(tsxSrc).toContain('subagent-toast-dismiss');
  });

  it('registers an Esc hotkey that calls dismissSubagentTerminal', () => {
    // The prior round lesson: persistent UI
    // affordances need a keyboard escape so the user
    // doesn't have to mouse to the × button. The
    // listener is only mounted while the toast is
    // visible (gated on subagent.lastTerminal).
    expect(tsxSrc).toMatch(/e\.key\s*===\s*['"]Escape['"]/);
    expect(tsxSrc).toMatch(/dismissSubagentTerminal\(\)/);
    expect(tsxSrc).toMatch(/if\s*\(!subagent\.lastTerminal\)\s*return/);
  });

  it('clicking the body still dismisses (same affordance as 历史)', () => {
    // The 历史 toast's onClick = dismiss; R119
    // keeps this for users who don't realise the new
    // button is there.
    expect(tsxSrc).toMatch(/onClick\s*=\s*\{\s*\(\)\s*=>\s*dismissSubagentTerminal\(\)\s*\}/);
  });

  it('clicking the dismiss button stops propagation so the body click does not fire twice', () => {
    // e.stopPropagation() in the button's onClick
    // is the difference between one dismiss RPC
    // and two (the body would otherwise re-dismiss
    // after the button handler). The test pins
    // this so a future refactor that drops the
    // stopPropagation() gets caught.
    expect(tsxSrc).toMatch(/e\.stopPropagation\(\)/);
  });
});

describe('R119: SubagentToast CSS adds a focusable dismiss button', () => {
  const cssSrc = readFileSync(join(root, 'src', 'components', 'SubagentToast.css'), 'utf-8');

  it('defines a .subagent-toast-dismiss style block', () => {
    expect(cssSrc).toContain('.subagent-toast-dismiss');
  });

  it('hover / focus styles exist for the dismiss button', () => {
    // the new button needs visible focus
    // styling so keyboard navigation lands the user
    // somewhere they can see.
    expect(cssSrc).toMatch(/\.subagent-toast-dismiss:hover/);
    expect(cssSrc).toMatch(/\.subagent-toast-dismiss:focus-visible/);
    expect(cssSrc).toMatch(/outline:\s*none/);
  });
});

describe('R119: StatusBar subagent indicator still surfaces the most-recent terminal', () => {
  const tsxSrc = readFileSync(join(root, 'src', 'components', 'StatusBar.tsx'), 'utf-8');

  it('renders the subagent status pill', () => {
    expect(tsxSrc).toMatch(/subagent\.status\s*&&/);
  });

  it('shows the running count when > 1', () => {
    expect(tsxSrc).toMatch(/subagent\.running\s*>\s*1/);
  });
});
