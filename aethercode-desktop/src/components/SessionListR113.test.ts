import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * tests for the SessionList defensive rendering.
 *
 * legacy a session entry with a null/undefined id would
 * either throw on `s.id.slice(-8)` (React error boundary)
 * or render empty text. R113 makes the component
 * defensive: bad entries render a "invalid session entry"
 * placeholder so the user can see *something* is in the
 * list (matches the count badge).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R113: SessionList defensive rendering', () => {
  it('SessionList.tsx sessionLabel defends against non-string id', () => {
    const src = read('src/components/SessionList.tsx');
    // The function body should check for typeof string before
    // calling slice.
    expect(src).toMatch(/typeof\s+id\s*===\s*['"]string['"]/);
  });

  it('SessionList.tsx renders an invalid-entry placeholder when id is missing', () => {
    const src = read('src/components/SessionList.tsx');
    expect(src).toMatch(/invalid session entry/);
  });

  it('SessionList.tsx filters by hasId before calling handleSwitch / handleDelete', () => {
    const src = read('src/components/SessionList.tsx');
    // The guard should sit BEFORE the click / delete handlers
    // (those use s.id which would throw).
    expect(src).toMatch(/if\s*\(\s*!hasId\s*\)/);
  });

  it('SessionList.tsx still has a sessionLabel function', () => {
    const src = read('src/components/SessionList.tsx');
    expect(src).toMatch(/function\s+sessionLabel/);
  });
});
