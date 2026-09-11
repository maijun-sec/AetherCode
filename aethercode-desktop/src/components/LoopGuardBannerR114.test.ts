import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * persistent LoopGuardBanner + Ctrl+L hotkey +
 * StatusBar loop badge.
 *
 * The user-reported "loop terminates mid-task, I didn't
 * even see the warning" complaint hinges on three small
 * things:
 *   1. The banner was auto-dismissing after 8 s.
 *   2. There was no keyboard shortcut to answer it.
 *   3. There was no persistent reminder in the StatusBar
 *      when the banner was up.
 *
 * This test guards against any future round that
 * reverts one of these.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('对应历史 round: LoopGuardBanner is persistent', () => {
  const src = readFileSync(join(root, 'src', 'components', 'LoopGuardBanner.tsx'), 'utf-8');

  it('LoopGuardBanner.tsx exists', () => {
    const path = join(root, 'src', 'components', 'LoopGuardBanner.tsx');
    expect(existsSync(path)).toBe(true);
  });

  it('AUTO_DISMISS_MS is set to 0 (disabled) by default', () => {
    // The legacy-C constant was 8_000. prior round changes
    // it to 0 to disable auto-dismiss entirely. The
    // exact value matters — bumping it back to a positive
    // number would re-enable the 8s soft-dismiss that
    // caused the user-reported issue.
    expect(src).toMatch(/const\s+AUTO_DISMISS_MS\s*=\s*0/);
  });

  it('the auto-dismiss useEffect gates on AUTO_DISMISS_MS > 0', () => {
    // The old code unconditionally called setTimeout
    // when loopWarn was set. prior round short-circuits when
    // AUTO_DISMISS_MS <= 0, so the timer is never
    // scheduled and the banner stays put.
    expect(src).toMatch(/if\s*\(\s*AUTO_DISMISS_MS\s*<=\s*0\s*\)\s*return/);
  });

  it('the hint text no longer mentions the 8s auto-dismiss', () => {
    // Strip comments so the explanatory comment that
    // references the old behaviour doesn't fail the
    // test.
    const stripped = src
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/.*$/gm, '');
    expect(stripped).not.toMatch(/8\s*秒后自动忽略/);
    expect(stripped).not.toMatch(/8s/);
  });

  it('the hint text mentions Ctrl+L', () => {
    expect(src).toContain('Ctrl</kbd>+<kbd>L');
  });
});

describe('对应历史 round: Ctrl+L hotkey', () => {
  const src = readFileSync(join(root, 'src', 'components', 'LoopGuardBanner.tsx'), 'utf-8');

  it('registers a keydown listener for Ctrl+L / Cmd+L', () => {
    expect(src).toMatch(/window\.addEventListener\(\s*['"]keydown['"]\s*,\s*onKey\s*\)/);
    expect(src).toMatch(/e\.key\.toLowerCase\(\)\s*===\s*['"]l['"]/);
    expect(src).toMatch(/e\.ctrlKey\s*\|\|\s*e\.metaKey/);
  });

  it('only mounts the listener when the banner is active', () => {
    // The effect's first line is `if (!loopWarn) return;` —
    // mounting the keydown only while a warn is in flight
    // keeps the listener from interfering with regular
    // text input (the user can still type "l" in the
    // message box).
    expect(src).toMatch(/if\s*\(\s*!loopWarn\s*\)\s*return;\s*\n\s*const\s+onKey/);
  });

  it('hotkey handler is a no-op when the banner is already closing', () => {
    // The closingRef guard prevents a double-tap from
    // firing two loopAck RPCs. Test asserts the guard
    // is in place by name.
    expect(src).toMatch(/closingRef\.current/);
  });

  it('hotkey calls acknowledgeLoop (not dismissLoopWarn)', () => {
    // Ctrl+L means "yes, keep going" — i.e. the
    // acknowledge path, not the cancel path. The test
    // makes sure the implementation wires the right
    // action.
    const keyHandler = src.match(/onKey\s*=\s*\(e:\s*KeyboardEvent\)\s*=>\s*\{[\s\S]*?\}\s*;/);
    expect(keyHandler).toBeTruthy();
    expect(keyHandler![0]).toContain('acknowledgeLoop');
    expect(keyHandler![0]).not.toContain('dismissLoopWarn');
  });

  it('hotkey also fires Cmd+L (macOS)', () => {
    // The implementation uses e.metaKey alongside
    // e.ctrlKey; both branches are required.
    expect(src).toMatch(/e\.metaKey/);
  });
});

describe('对应历史 round: StatusBar loop badge', () => {
  const statusSrc = readFileSync(join(root, 'src', 'components', 'StatusBar.tsx'), 'utf-8');
  const statusCss = readFileSync(join(root, 'src', 'components', 'StatusBar.css'), 'utf-8');
  const bannerSrc = readFileSync(join(root, 'src', 'components', 'LoopGuardBanner.tsx'), 'utf-8');

  it('StatusBar pulls loopWarn from the store', () => {
    // The destructure spans many lines (R120 added four
    // more fields + comments below the prior round entry), so
    // the old anchor ("loopWarn }" with optional newline)
    // stops matching — the regex needs [\s\S] to cross
    // the R120 comment block.
    expect(statusSrc).toMatch(/loopWarn\s*,[\s\S]*?\}/);
  });

  it('StatusBar renders a loop badge when loopWarn is set', () => {
    expect(statusSrc).toContain('status-loop-warn');
    expect(statusSrc).toMatch(/loopWarn\s*&&\s*\(/);
    expect(statusSrc).toContain('loop {loopWarn.tier}/2');
  });

  it('loop badge is clickable and scrolls to the banner', () => {
    expect(statusSrc).toMatch(/document\.getElementById\(['"]loop-guard-banner['"]\)/);
    expect(statusSrc).toMatch(/scrollIntoView/);
  });

  it('CSS has tier-1 + tier-2 colour variants', () => {
    expect(statusCss).toContain('status-loop-tier-1');
    expect(statusCss).toContain('status-loop-tier-2');
  });

  it('CSS tier-2 has a pulse animation (urgent)', () => {
    // Tier 2 = "engine is about to stop", so the badge
    // should be visually impossible to miss. The CSS
    // re-uses the skip-low-pulse keyframes that already
    // exist (no need to add a new one).
    expect(statusCss).toMatch(/status-loop-tier-2[\s\S]*?animation:/);
  });

  it('LoopGuardBanner exposes id="loop-guard-banner" for the jump', () => {
    // The id is the contract between the StatusBar's
    // scrollIntoView call and the banner itself. If
    // someone renames the id, both files would have
    // to change in lockstep — the test pins the
    // current name so a refactor has to update both.
    expect(bannerSrc).toMatch(/id=['"]loop-guard-banner['"]/);
  });
});
