import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Phase 5 (T-5-08 / T-5-09): GrantsList + GrantsFilter (source-level).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 5 / T-5-08: GrantsList', () => {
  it('GrantsList.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/permissions/GrantsList.tsx'))).toBe(true);
  });

  it('exports GrantsList component', () => {
    const src = read('src/components/permissions/GrantsList.tsx');
    expect(src).toMatch(/export\s+function\s+GrantsList\b/);
  });

  it('calls useGrantsList + useRevokeGrant', () => {
    const src = read('src/components/permissions/GrantsList.tsx');
    expect(src).toMatch(/useGrantsList/);
    expect(src).toMatch(/useRevokeGrant/);
  });

  it('renders a revoke button per row', () => {
    const src = read('src/components/permissions/GrantsList.tsx');
    expect(src).toMatch(/grants-list-revoke/);
    expect(src).toMatch(/revoke\.mutateAsync/);
  });

  it('surfaces scope + category + decision + pattern', () => {
    const src = read('src/components/permissions/GrantsList.tsx');
    expect(src).toMatch(/grants-list-scope/);
    expect(src).toMatch(/grants-list-category/);
    expect(src).toMatch(/grants-list-decision/);
    expect(src).toMatch(/grants-list-pattern/);
  });
});

describe('Phase 5 / T-5-09: GrantsFilter', () => {
  it('GrantsFilter.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/permissions/GrantsFilter.tsx'))).toBe(true);
  });

  it('exports GrantsFilter component', () => {
    const src = read('src/components/permissions/GrantsFilter.tsx');
    expect(src).toMatch(/export\s+function\s+GrantsFilter\b/);
  });

  it('exports GrantsFilterValue + EMPTY_GRANTS_FILTER + isFilterActive', () => {
    const src = read('src/components/permissions/GrantsFilter.tsx');
    expect(src).toMatch(/export\s+interface\s+GrantsFilterValue\b/);
    expect(src).toMatch(/export\s+const\s+EMPTY_GRANTS_FILTER\b/);
    expect(src).toMatch(/export\s+function\s+isFilterActive\b/);
  });

  it('filters by scope + decision + category + query', () => {
    const src = read('src/components/permissions/GrantsList.tsx');
    expect(src).toMatch(/filter\.scope/);
    expect(src).toMatch(/filter\.decision/);
    expect(src).toMatch(/filter\.category/);
    expect(src).toMatch(/filter\.query/);
  });
});
