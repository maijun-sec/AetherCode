import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * regression guard for the "blank screen on render error"
 * safety net. R179 was a best-guess fix for the "blank screen on
 * submit" race in the store's sync handler. R180 is the
 * belt-and-suspenders: even if some future refactor introduces a
 * new render error in MessageList / its sub-components, the user
 * sees a fallback card with a "重试" button and the actual error
 * message — not a totally blank center column.
 *
 * These source-pin tests pin:
 *   1. The {@code ErrorBoundary} component file exists with a class
 *      component that implements {@code componentDidCatch} and
 *      {@code getDerivedStateFromError}.
 *   2. {@code App.tsx} imports and wraps {@code <MessageList />} in
 *      an {@code <ErrorBoundary label="Chat 列表">}. Renaming or
 *      removing the wrap will fail this test even if the code
 *      "looks fine" in review.
 *   3. The fallback UI includes a "重试" button (literal string)
 *      and an `R180 ErrorBoundary` console marker (for DevTools
 *      discoverability when a real bug fires).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R180: ErrorBoundary presence + integration', () => {
  it('ErrorBoundary.tsx exists and is a real class component', () => {
    const src = readSrc('src/components/ErrorBoundary.tsx');
    // Class component (React error boundaries can only be class
    // components in React 19).
    expect(src, 'ErrorBoundary must extend Component').toMatch(
      /class\s+ErrorBoundary\s+extends\s+Component/,
    );
    // Both lifecycle hooks required.
    expect(
      src,
      'ErrorBoundary must implement componentDidCatch to log the error',
    ).toMatch(/componentDidCatch\s*\(/);
    expect(
      src,
      'ErrorBoundary must implement getDerivedStateFromError to capture the error in state',
    ).toMatch(/getDerivedStateFromError\s*\(/);
  });

  it('ErrorBoundary fallback includes a 重试 button and an R180 console marker', () => {
    const src = readSrc('src/components/ErrorBoundary.tsx');
    // Strip comments so the regex doesn't match the explanatory
    // JSDoc that quotes the pre-fix symptom.
    const stripped = src
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/.*$/gm, '');
    // R180 source contract: the reset button must say "重试".
    // Renaming the label will break UX expectations and
    // the regression test will catch it.
    expect(
      stripped,
      'ErrorBoundary fallback must render a button labeled "重试" so ' +
        'users have a one-click way to retry rendering after a crash',
    ).toMatch(/>\s*重试\s*</);
    // R180 source contract: the console.error must include the
    // "R180 ErrorBoundary" marker so a real crash in production
    // is easy to find in DevTools.
    expect(
      stripped,
      'componentDidCatch must log with the "R180 ErrorBoundary" tag so ' +
        'the crash is discoverable in DevTools',
    ).toMatch(/R180 ErrorBoundary/);
  });

  it('App.tsx imports ErrorBoundary and wraps <MessageList /> with it', () => {
    const src = readSrc('src/App.tsx');
    // Import statement.
    expect(
      src,
      'App.tsx must import ErrorBoundary from ./components/ErrorBoundary',
    ).toMatch(
      /import\s*\{\s*ErrorBoundary\s*\}\s*from\s*['"]\.\/components\/ErrorBoundary['"]/,
    );
    // Strip comments so the regex doesn't match the JSDoc
    // explanation block that quotes the bug.
    const stripped = src
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/.*$/gm, '');
    // Wrap must be in the MainLayout JSX, in the position where
    // MessageList used to be the only child. Use a loose regex
    // that tolerates the leading whitespace / ternary that
    // chooses between Welcome and the chat.
    const wrapRegex =
      /<ErrorBoundary\b[^>]*label=["']Chat 列表["'][^>]*>\s*<MessageList\s*\/>\s*<\/ErrorBoundary>/;
    expect(
      wrapRegex.test(stripped),
      'App.tsx must wrap <MessageList /> in <ErrorBoundary label="Chat 列表"> ' +
        '— without this wrap a render error in any MessageList sub-component ' +
        'will produce a blank "白板" column (the R179 failure mode we are ' +
        'guarding against in R180)',
    ).toBe(true);
  });
});
