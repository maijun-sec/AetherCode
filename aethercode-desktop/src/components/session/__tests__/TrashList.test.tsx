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
 * Phase 4.1 (T-4-06): TrashList (source-level).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 4.1 / T-4-06: TrashList', () => {
  it('TrashList.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/session/TrashList.tsx'))).toBe(true);
  });

  it('exports TrashList component', () => {
    const src = read('src/components/session/TrashList.tsx');
    expect(src).toMatch(/export\s+function\s+TrashList\b/);
  });

  it('uses useSessionList with includeTrashed: true', () => {
    const src = read('src/components/session/TrashList.tsx');
    expect(src).toMatch(/useSessionList\s*\(\s*\{\s*includeTrashed:\s*true/);
  });

  it('renders the TrashRow child component', () => {
    const src = read('src/components/session/TrashList.tsx');
    expect(src).toMatch(/<TrashRow\b/);
    expect(src).toMatch(/from\s+['"]\.\/TrashRow['"]/);
  });

  it('sorts by trashedAt desc', () => {
    const src = read('src/components/session/TrashList.tsx');
    expect(src).toMatch(/trashedAt/);
    expect(src).toMatch(/b\.trashedAt/);
    expect(src).toMatch(/a\.trashedAt/);
    expect(src).toMatch(/sort/);
  });

  it('emits a confirm-on-second-click "empty trash" action', () => {
    const src = read('src/components/session/TrashList.tsx');
    expect(src).toMatch(/confirmEmpty/);
    expect(src).toMatch(/empty/);
  });

  it('calls session/restore on per-row restore', () => {
    const src = read('src/components/session/TrashList.tsx');
    expect(src).toMatch(/useRestoreSession/);
    expect(src).toMatch(/restore\.mutateAsync/);
  });

  it('calls session/delete on per-row "delete forever"', () => {
    const src = read('src/components/session/TrashList.tsx');
    expect(src).toMatch(/useDeleteSession/);
    expect(src).toMatch(/del\.mutateAsync/);
  });

  it('invalidateQueries after every mutation', () => {
    const src = read('src/components/session/TrashList.tsx');
    expect(src).toMatch(/qc\.invalidateQueries\s*\(\s*\{\s*queryKey:\s*\[\s*['"]sessionList['"]/);
  });
});

describe('Phase 4.1 / T-4-07: TrashRow', () => {
  it('TrashRow.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/session/TrashRow.tsx'))).toBe(true);
  });

  it('exports TrashRow component', () => {
    const src = read('src/components/session/TrashRow.tsx');
    expect(src).toMatch(/export\s+function\s+TrashRow\b/);
  });

  it('renders a restore button + delete-forever button', () => {
    const src = read('src/components/session/TrashRow.tsx');
    expect(src).toMatch(/trash-row-restore/);
    expect(src).toMatch(/trash-row-delete-forever/);
  });

  it('formats trashedAt as relative time', () => {
    const src = read('src/components/session/TrashRow.tsx');
    expect(src).toMatch(/formatTrashedAt/);
    expect(src).toMatch(/trashed/);
  });

  it('ellipsises the cwd', () => {
    const src = read('src/components/session/TrashRow.tsx');
    expect(src).toMatch(/ellipsisePath/);
  });
});
