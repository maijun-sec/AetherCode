import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Phase 4.1 (T-4-01): SessionListFilter (source-level).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 4.1 / T-4-01: SessionListFilter', () => {
  it('SessionListFilter.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/session/SessionListFilter.tsx'))).toBe(true);
  });

  it('exports SessionListFilter component', () => {
    const src = read('src/components/session/SessionListFilter.tsx');
    expect(src).toMatch(/export\s+function\s+SessionListFilter\b/);
  });

  it('exports SessionListFilterValue + EMPTY_FILTER', () => {
    const src = read('src/components/session/SessionListFilter.tsx');
    expect(src).toMatch(/export\s+interface\s+SessionListFilterValue\b/);
    expect(src).toMatch(/export\s+const\s+EMPTY_FILTER\b/);
  });

  it('exports isFilterActive helper', () => {
    const src = read('src/components/session/SessionListFilter.tsx');
    expect(src).toMatch(/export\s+function\s+isFilterActive\b/);
  });

  it('renders a search input', () => {
    const src = read('src/components/session/SessionListFilter.tsx');
    expect(src).toMatch(/type="text"/);
    expect(src).toMatch(/placeholder="search/);
  });

  it('renders a cwd filter', () => {
    const src = read('src/components/session/SessionListFilter.tsx');
    expect(src).toMatch(/placeholder="cwd/);
  });

  it('renders a date range (since)', () => {
    const src = read('src/components/session/SessionListFilter.tsx');
    expect(src).toMatch(/type="date"/);
  });

  it('renders a "only mine" toggle', () => {
    const src = read('src/components/session/SessionListFilter.tsx');
    expect(src).toMatch(/type="checkbox"/);
    expect(src).toMatch(/onlyMine/);
  });

  it('debounces the query input (setTimeout)', () => {
    const src = read('src/components/session/SessionListFilter.tsx');
    expect(src).toMatch(/setTimeout/);
    expect(src).toMatch(/clearTimeout/);
    expect(src).toMatch(/debounceMs/);
  });
});
