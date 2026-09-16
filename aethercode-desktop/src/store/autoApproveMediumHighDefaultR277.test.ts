// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R277 (2026-09-16): the desktop default for `autoApproveMediumHigh`
 * flipped back to `false`. The R268d default was `true` to fix a
 * batch-workflow idle-freeze (5-min permission timeout on every
 * file_write when the user wasn't at the keyboard) but it had the
 * side effect that even when the user picked "主动询问" (ASK_BEFORE_TOOL)
 * from the dropdown, every medium/high-risk tool call still went
 * through without a prompt — the flag silently overrode the mode.
 *
 * R277 splits the responsibilities cleanly:
 *   - permission mode is the user-visible source of truth for
 *     "should this be asked?" (set via setPermissionMode RPC)
 *   - autoApproveMediumHigh is an explicit override (set via the
 *     StatusBar ▲ toggle) that only fires in non-ask modes
 *
 * With the desktop default flipped to false, a new install starts
 * with: low-risk tools auto-approve (read-only/grep/etc.), every
 * mutating tool asks. The user can still flip the ▲ in the
 * StatusBar for batch workflows, but the default is the safe one.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R277: desktop default `autoApproveMediumHigh: false`', () => {
  it('the initial-state literal in store/index.ts is `false` (not `true`)', () => {
    // R268d changed it to `true` to fix a batch-workflow idle-freeze;
    // R277 flips it back to `false` so the explicit-ask modes
    // (ASK_BEFORE_TOOL / DEFAULT / PLAN) actually ask. The
    // JsonRpcPermissionPrompter also gained an isAskMode() guard so
    // even a stale localStorage `true` from before R277 won't bypass
    // asks.
    const src = readSrc('src/store/index.ts');
    // The default literal sits in the big initial-state object.
    // We assert on the exact `autoApproveMediumHigh: false` token —
    // if a future round re-introduces `true` as the default, this
    // test fails and we have to look at whether the daemon's
    // isAskMode() guard is still in place.
    const matches = src.match(/autoApproveMediumHigh:\s*(true|false)/g) ?? [];
    expect(matches.length).toBeGreaterThan(0);
    const falsyCount = matches.filter((m) => m.endsWith('false')).length;
    // The default in the initial state must be false. Other
    // references (test fixtures, type narrowing) can stay truthy.
    expect(
      falsyCount,
      `desktop initial state must default autoApproveMediumHigh to false (got ${matches.join(', ')})`,
    ).toBeGreaterThanOrEqual(1);
    // The actual default literal must not be true — it must be false.
    expect(
      src,
      'the default `autoApproveMediumHigh: true` literal from R268d must be gone — it silently overrode "主动询问"',
    ).not.toMatch(/autoApproveMediumHigh:\s*true\b/);
  });

  it('the daemon-side JsonRpcPermissionPrompter honours mode-as-source-of-truth', () => {
    // Source-pin the daemon fix. The isAskMode() guard is what stops
    // the stale-flag-bypasses-mode regression from ever coming back.
    const src = readSrc('../aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/permissions/JsonRpcPermissionPrompter.java');
    // The new guard must check `isAskMode()` BEFORE short-circuiting.
    // Find the autoApproveMediumHigh branch and verify the guard.
    const autoAppr = src.match(/if\s*\(\(\s*"medium"\s*\.\s*equals\s*\(riskLevel\)\s*\|\|\s*"high"\s*\.\s*equals\s*\(riskLevel\)\s*\)\s*&&\s*methods\.isAutoApproveMediumHigh\(\)[\s\S]*?\)/);
    expect(autoAppr, 'medium/high autoApproveMediumHigh branch must exist').not.toBeNull();
    expect(
      autoAppr![0],
      'R277 guard: the autoApproveMediumHigh short-circuit MUST also check `!methods.isAskMode()` so ASK_BEFORE_TOOL / DEFAULT / PLAN modes never get silently bypassed.',
    ).toMatch(/!\s*methods\.isAskMode\(\)/);
  });

  it('AetherCodeMethods exposes currentPermissionModeName() and isAskMode() for the guard', () => {
    // The guard reads `methods.isAskMode()`. If a future refactor
    // renames the helper, the guard stops compiling and this pin
    // catches the regression at code-review time.
    const src = readSrc('../aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java');
    expect(src).toMatch(/public\s+String\s+currentPermissionModeName\s*\(\s*\)/);
    expect(src).toMatch(/public\s+boolean\s+isAskMode\s*\(\s*\)/);
    // isAskMode must cover the three ask tiers by name.
    const helper = src.match(/public\s+boolean\s+isAskMode\s*\(\s*\)\s*\{[\s\S]*?\n\s*\}/);
    expect(helper, 'isAskMode body must exist').not.toBeNull();
    expect(helper![0]).toMatch(/ASK_BEFORE_TOOL/);
    expect(helper![0]).toMatch(/DEFAULT/);
    expect(helper![0]).toMatch(/PLAN/);
  });
});