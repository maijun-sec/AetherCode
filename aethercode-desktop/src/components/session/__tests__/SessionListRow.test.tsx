import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Phase 4.1 (T-4-03): SessionListRow (source-level).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 4.1 / T-4-03: SessionListRow', () => {
  it('SessionListRow.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/session/SessionListRow.tsx'))).toBe(true);
  });

  it('exports SessionListRow component', () => {
    const src = read('src/components/session/SessionListRow.tsx');
    expect(src).toMatch(/export\s+function\s+SessionListRow\b/);
  });

  it('renders a title + cwd + last-active + tokens', () => {
    const src = read('src/components/session/SessionListRow.tsx');
    expect(src).toMatch(/session-list-row-title/);
    expect(src).toMatch(/session-list-row-cwd/);
    expect(src).toMatch(/session-list-row-when/);
    expect(src).toMatch(/session-list-row-tokens/);
  });

  it('renders a preview line (first user message)', () => {
    const src = read('src/components/session/SessionListRow.tsx');
    expect(src).toMatch(/session-list-row-preview/);
  });

  it('handles Enter / Space to select', () => {
    const src = read('src/components/session/SessionListRow.tsx');
    expect(src).toMatch(/e\.key\s*===\s*['"]Enter['"]/);
    expect(src).toMatch(/e\.key\s*===\s*['"] ['"]/);
  });

  it('hides the delete button on the current row', () => {
    const src = read('src/components/session/SessionListRow.tsx');
    expect(src).toMatch(/!isCurrent\s*&&/);
  });

  it('aria-selected tracks the current row', () => {
    const src = read('src/components/session/SessionListRow.tsx');
    expect(src).toMatch(/aria-selected/);
  });

  it('falls back to <未命名> when no title or preview (R222)', () => {
    // 历史 the fallback was "Session <id-suffix>"
    // — the user said the UUID tail was 难以辨认 (hard
    // to read) and asked for <未命名> when no prompt
    // exists. The session id is still in the row's
    // `title` attribute (browser tooltip) for power
    // users; the visible text is just <未命名>.
    const src = read('src/components/session/SessionListRow.tsx');
    expect(src).toMatch(/return\s*'\u672a\u547d\u540d'/);
  });
});
