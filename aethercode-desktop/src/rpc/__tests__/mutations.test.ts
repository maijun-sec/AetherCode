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
 * Phase 3 (T-3-04): TanStack Query mutations.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 3 / T-3-04: mutations.ts (source-level)', () => {
  it('mutations.ts exists', () => {
    expect(existsSync(join(root, 'src/rpc/mutations.ts'))).toBe(true);
  });

  it('exports useSpawnSession, useResumeSession, useSetModel, useSetPreset', () => {
    const src = read('src/rpc/mutations.ts');
    expect(src).toMatch(/export\s+function\s+useSpawnSession\b/);
    expect(src).toMatch(/export\s+function\s+useResumeSession\b/);
    expect(src).toMatch(/export\s+function\s+useSetModel\b/);
    expect(src).toMatch(/export\s+function\s+useSetPreset\b/);
  });

  it('useSpawnSession calls session/spawn with prompt + cwd + model', () => {
    const src = read('src/rpc/mutations.ts');
    expect(src).toMatch(/client\.call<SpawnResult>\(\s*['"]session\/spawn['"]/);
    expect(src).toMatch(/SpawnArgs/);
  });

  it('useResumeSession calls session/resume', () => {
    const src = read('src/rpc/mutations.ts');
    expect(src).toMatch(/client\.call<ResumeResult>\(\s*['"]session\/resume['"]/);
  });

  it('useSetModel calls model/set', () => {
    const src = read('src/rpc/mutations.ts');
    expect(src).toMatch(/client\.call<\{\s*ok:\s*true\s*\}>\(\s*['"]model\/set['"]/);
  });

  it('useSetPreset calls grants/setPreset', () => {
    const src = read('src/rpc/mutations.ts');
    expect(src).toMatch(/client\.call<\{\s*ok:\s*true;\s*preset/);
    expect(src).toMatch(/['"]permissive['"]/);
    expect(src).toMatch(/['"]cautious['"]/);
    expect(src).toMatch(/['"]strict['"]/);
  });

  it('exports the session lifecycle mutations', () => {
    const src = read('src/rpc/mutations.ts');
    expect(src).toMatch(/export\s+function\s+useRenameSession\b/);
    expect(src).toMatch(/export\s+function\s+useDeleteSession\b/);
    expect(src).toMatch(/export\s+function\s+useRestoreSession\b/);
    expect(src).toMatch(/export\s+function\s+useTrashSession\b/);
  });

  it('exports the grants mutations', () => {
    const src = read('src/rpc/mutations.ts');
    expect(src).toMatch(/export\s+function\s+useRevokeGrant\b/);
    expect(src).toMatch(/export\s+function\s+useClearGrants\b/);
  });

  it('exports a useTaskControl mutation (pause / resume / kill)', () => {
    const src = read('src/rpc/mutations.ts');
    expect(src).toMatch(/export\s+function\s+useTaskControl\b/);
    expect(src).toMatch(/task\/\$\{op\}/);
  });

  it('uses TanStack Query v5 useMutation', () => {
    const src = read('src/rpc/mutations.ts');
    expect(src).toMatch(/useMutation\b/);
    expect(src).toMatch(/@tanstack\/react-query/);
  });

  it('invalidateQueries after session lifecycle mutations', () => {
    const src = read('src/rpc/mutations.ts');
    expect(src).toMatch(/qc\.invalidateQueries/);
  });
});
