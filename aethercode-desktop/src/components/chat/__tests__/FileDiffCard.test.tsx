// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Phase 4.2 (T-4-13): FileDiffCard (source-level).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 4.2 / T-4-13: FileDiffCard', () => {
  it('FileDiffCard.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/chat/FileDiffCard.tsx'))).toBe(true);
  });

  it('exports FileDiffCard component', () => {
    const src = read('src/components/chat/FileDiffCard.tsx');
    expect(src).toMatch(/export\s+function\s+FileDiffCard\b/);
  });

  it('starts collapsed by default', () => {
    const src = read('src/components/chat/FileDiffCard.tsx');
    expect(src).toMatch(/defaultExpanded\s*=\s*false/);
  });

  it('parses unified diff lines (add / del / ctx / meta)', () => {
    const src = read('src/components/chat/FileDiffCard.tsx');
    expect(src).toMatch(/parseDiff/);
    expect(src).toMatch(/'add'|'del'|'ctx'|'meta'/);
  });

  it('computes addition / deletion counts', () => {
    const src = read('src/components/chat/FileDiffCard.tsx');
    expect(src).toMatch(/countDiff/);
    expect(src).toMatch(/additions/);
    expect(src).toMatch(/deletions/);
  });

  it('shows +N and -N badges in the header', () => {
    const src = read('src/components/chat/FileDiffCard.tsx');
    expect(src).toMatch(/file-diff-card-add/);
    expect(src).toMatch(/file-diff-card-del/);
  });

  it('offers a "copy" affordance via navigator.clipboard', () => {
    const src = read('src/components/chat/FileDiffCard.tsx');
    expect(src).toMatch(/navigator\.clipboard/);
    expect(src).toMatch(/file-diff-card-copy/);
  });

  it('uses a <pre> for the diff body with line-level styling', () => {
    const src = read('src/components/chat/FileDiffCard.tsx');
    expect(src).toMatch(/<pre/);
    expect(src).toMatch(/add/);
    expect(src).toMatch(/del/);
  });
});
