import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * StatusBar auto-approve badge.
 *
 * <p>The badge is the user-facing affordance for
 * R120's daemon-side short-circuit. It is always
 * visible (so the user can see whether auto-allow
 * is on) and clickable (so they can flip the flag
 * without opening Settings).
 *
 * <p>These tests pin the wiring so a future
 * refactor that drops the click handler, the tooltip
 * shape, or the className contract gets caught.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R120: StatusBar auto-approve badge wiring', () => {
  const tsxSrc = readFileSync(join(root, 'src', 'components', 'StatusBar.tsx'), 'utf-8');

  it('pulls the four R120 fields from the store', () => {
    // The badge needs autoApproveLowRisk (for the
    // check/×), autoApprovedCount (for the count
    // span), recentAutoApproved (for the tooltip
    // recent list), and setAutoApproveLowRisk
    // (for the click handler). The destructure spans
    // multiple lines + has comments between fields.
    // We just confirm the four names appear in the
    // function body up to (but not including) the
    // first `return` statement — that's the region
    // the destructure lives in.
    const fnBodyStart = tsxSrc.indexOf('export function StatusBar()');
    expect(fnBodyStart).toBeGreaterThan(-1);
    const returnIdx = tsxSrc.indexOf('\n  return', fnBodyStart);
    expect(returnIdx).toBeGreaterThan(-1);
    const region = tsxSrc.slice(fnBodyStart, returnIdx);
    expect(region).toContain('autoApproveLowRisk');
    expect(region).toContain('autoApprovedCount');
    expect(region).toContain('recentAutoApproved');
    expect(region).toContain('setAutoApproveLowRisk');
  });

  it('renders a focusable <button> with the auto-approve className', () => {
    // The badge is a real <button> (not a <span>)
    // because clicking it flips the flag. The
    // is-disabled className is applied when the flag
    // is false so the visual treatment differs.
    const block = tsxSrc.match(/<button[\s\S]*?status-auto-approve[\s\S]*?<\/button>/);
    expect(block).toBeTruthy();
    expect(block![0]).toContain("className={`status-item status-auto-approve ${autoApproveLowRisk ? '' : 'is-disabled'}`}");
  });

  it('shows ✓ when autoApproveLowRisk is on, ✗ when off', () => {
    // The check / cross glyph is the primary signal
    // that the user sees at a glance. Pin both
    // branches of the conditional.
    const block = tsxSrc.match(/<button[\s\S]*?status-auto-approve[\s\S]*?<\/button>/);
    expect(block).toBeTruthy();
    expect(block![0]).toContain("{autoApproveLowRisk ? '✓' : '✗'} auto-allow");
  });

  it('renders the cumulative count only when > 0', () => {
    // prior round lesson applied: a badge with "0" looks
    // like a failure. The count span is conditional
    // on autoApprovedCount > 0 so the bar stays clean
    // for users who have never had a low-risk call.
    const block = tsxSrc.match(/<button[\s\S]*?status-auto-approve[\s\S]*?<\/button>/);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('autoApprovedCount > 0 && (');
    expect(block![0]).toContain('<span className="status-auto-approve-count">');
    expect(block![0]).toContain('{autoApprovedCount}');
  });

  it('clicking the button calls setAutoApproveLowRisk(!current)', () => {
    // The badge is the only way to flip the flag
    // without opening Settings. Optimistic update
    // happens inside the action after the RPC
    // succeeds, so the click handler is a thin
    // pass-through.
    const block = tsxSrc.match(/<button[\s\S]*?status-auto-approve[\s\S]*?<\/button>/);
    expect(block).toBeTruthy();
    expect(block![0]).toContain('onClick={() => void setAutoApproveLowRisk(!autoApproveLowRisk)}');
  });

  it('tooltip shows recent tools when present, plain hint when empty', () => {
    // The hover tooltip carries the cumulative count
    // + recent tool list when there is history, and
    // a plain "(click to toggle)" hint when there
    // isn't. Both branches must be present so the
    // title attribute is never undefined.
    const block = tsxSrc.match(/<button[\s\S]*?status-auto-approve[\s\S]*?<\/button>/);
    expect(block).toBeTruthy();
    expect(block![0]).toContain("title=");
    expect(block![0]).toContain('recentAutoApproved.length > 0');
    expect(block![0]).toContain('Auto-allow low risk: ${autoApproveLowRisk ? \'ON\' : \'OFF\'}');
    expect(block![0]).toContain('(click to toggle)');
    expect(block![0]).toContain('recent: ${recentAutoApproved.slice(0, 5).map((r) => r.tool).join(\', \')}');
  });
});

describe('R120: StatusBar CSS for the auto-approve badge', () => {
  const cssSrc = readFileSync(join(root, 'src', 'components', 'StatusBar.css'), 'utf-8');

  it('defines a .status-auto-approve base style', () => {
    expect(cssSrc).toContain('.status-auto-approve');
  });

  it('defines an .is-disabled state for the off case', () => {
    // The is-disabled className is applied when the
    // flag is false. The CSS gives it a different
    // visual treatment (muted colour) so the user
    // can tell at a glance that auto-allow is off.
    expect(cssSrc).toContain('.status-auto-approve.is-disabled');
  });

  it('defines a .status-auto-approve-count style for the cumulative count', () => {
    // The count span sits inside the button. It
    // needs a different visual treatment (e.g.
    // dimmed colour or a chip background) so the
    // "N" doesn't compete with the ✓/✗ glyph.
    expect(cssSrc).toContain('.status-auto-approve-count');
  });
});

describe('R120: AetherCodeRpc.setAutoApproveLowRisk wrapper', () => {
  const methodsSrc = readFileSync(join(root, 'src', 'lib', 'methods.ts'), 'utf-8');

  it('declares a typed setAutoApproveLowRisk on the rpc class', () => {
    // The TS wrapper must match the Java return
    // shape: { ok, enabled, autoApprovedCount }.
    // A refactor that returns just the boolean
    // would break the store's optimistic update.
    expect(methodsSrc).toMatch(
      /setAutoApproveLowRisk\(opts:\s*\{\s*enabled:\s*boolean\s*\}\):\s*Promise<\{\s*ok:\s*boolean;\s*enabled:\s*boolean;\s*autoApprovedCount:\s*number\s*\}>/,
    );
  });

  it('wraps rpc.call(\'setAutoApproveLowRisk\', opts)', () => {
    const block = methodsSrc.match(/setAutoApproveLowRisk[\s\S]*?return this\.call\('setAutoApproveLowRisk'[^)]+\);/);
    expect(block).toBeTruthy();
  });
});
