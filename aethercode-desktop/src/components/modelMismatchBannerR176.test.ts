import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * visibility regression for the
 * model-mismatch banner.
 *
 * <p>The first R176 cut used 8% opacity amber
 * (`rgba(245, 158, 11, 0.08)`) which was
 * barely visible on the dark `--chat-surface`
 * (#252526). A user reported the banner
 * "flashed" but they couldn't see it. prior round
 * bumps the banner to the canonical
 * `--chat-warning-soft` (18% opacity), adds a
 * 3px left accent in `--chat-warning`, and a
 * soft shadow.
 *
 * <p>These source-pin tests pin the afterward-C
 * contract: a future "make it less obtrusive"
 * refactor will trip the regression and force a
 * conscious decision.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('对应历史 round: model-mismatch banner is unmistakable on the dark surface', () => {
  const cssSrc = read('src/components/MessageInput.css');

  it('uses --chat-warning-soft for the background (18% opacity, not 8% rgba)', () => {
    // The legacy-C bug was 8% rgba amber on
    // a dark surface. The fix uses the canonical
    // --chat-warning-soft (defined in App.css as
    // rgba(245, 158, 11, 0.18)) and a solid
    // --chat-warning border.
    expect(cssSrc).toMatch(/\.model-mismatch-banner\s*\{[\s\S]*?background:\s*var\(--chat-warning-soft\)/);
  });

  it('uses --chat-warning for the border + left accent (not 35% rgba)', () => {
    // The pre-fix border was rgba(245, 158, 11, 0.35)
    // — also too faint. The fix uses solid
    // --chat-warning with a thicker left accent.
    expect(cssSrc).toMatch(/\.model-mismatch-banner\s*\{[\s\S]*?border:\s*1px solid var\(--chat-warning\)/);
    expect(cssSrc).toMatch(/\.model-mismatch-banner\s*\{[\s\S]*?border-left:\s*4px solid var\(--chat-warning\)/);
  });

  it('does NOT use any 8% / 35% rgba amber for the banner (regression guard)', () => {
    // The 8% / 35% rgba values were the
    // visibility-killer. A refactor that
    // re-introduces them should be a conscious
    // decision (the comment is at line ~50 of
    // MessageInput.css).
    const bannerBlock = cssSrc.match(/\.model-mismatch-banner\s*\{[\s\S]*?\}/);
    expect(bannerBlock).toBeTruthy();
    expect(bannerBlock![0]).not.toMatch(/rgba\(245, 158, 11, 0\.08/);
    expect(bannerBlock![0]).not.toMatch(/rgba\(245, 158, 11, 0\.35/);
  });

  it('title text uses --chat-warning color (stands out against the soft background)', () => {
    // The amber title text reinforces the
    // "this is important" signal. Without the
    // accent color, the title disappears into
    // the muted text colour.
    expect(cssSrc).toMatch(/\.model-mismatch-banner-title\s*\{[\s\S]*?color:\s*var\(--chat-warning\)/);
  });

  it('model id chips use --chat-warning color on a dark code block', () => {
    // The `MiniMax-M1` and `MiniMax-M3`
    // labels inside the description need to be
    // easy to scan; an amber chip on a dark
    // background draws the eye to the
    // actionable info.
    expect(cssSrc).toMatch(/\.model-mismatch-banner-desc code\s*\{[\s\S]*?color:\s*var\(--chat-warning\)/);
  });
});
