import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Phase 4.2 (T-4-14): PlanCard (source-level).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 4.2 / T-4-14: PlanCard', () => {
  it('PlanCard.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/chat/PlanCard.tsx'))).toBe(true);
  });

  it('exports PlanCard component', () => {
    const src = read('src/components/chat/PlanCard.tsx');
    expect(src).toMatch(/export\s+function\s+PlanCard\b/);
  });

  it('exports parsePlanSteps helper', () => {
    const src = read('src/components/chat/PlanCard.tsx');
    expect(src).toMatch(/export\s+function\s+parsePlanSteps\b/);
  });

  it('parses numbered list, bulleted list, and task-list markers', () => {
    const src = read('src/components/chat/PlanCard.tsx');
    expect(src).toMatch(/d\+\)/);
    expect(src).toMatch(/\[-\*\]/);
    expect(src).toMatch(/xX/);
  });

  it('renders the steps as an <ol>', () => {
    const src = read('src/components/chat/PlanCard.tsx');
    expect(src).toMatch(/<ol/);
  });

  it('shows a "done" pill when the plan finished', () => {
    const src = read('src/components/chat/PlanCard.tsx');
    expect(src).toMatch(/done/);
    expect(src).toMatch(/plan-card-done-pill/);
  });

  it('starts expanded by default (unlike other cards)', () => {
    const src = read('src/components/chat/PlanCard.tsx');
    expect(src).toMatch(/defaultExpanded\s*=\s*true/);
  });
});
