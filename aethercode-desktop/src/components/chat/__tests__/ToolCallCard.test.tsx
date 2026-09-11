import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Phase 4.2 (T-4-12): ToolCallCard (source-level).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..', '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
})();
function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('Phase 4.2 / T-4-12: ToolCallCard', () => {
  it('ToolCallCard.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/chat/ToolCallCard.tsx'))).toBe(true);
  });

  it('exports ToolCallCard component', () => {
    const src = read('src/components/chat/ToolCallCard.tsx');
    expect(src).toMatch(/export\s+function\s+ToolCallCard\b/);
  });

  it('starts collapsed by default (useState false)', () => {
    const src = read('src/components/chat/ToolCallCard.tsx');
    expect(src).toMatch(/defaultExpanded\s*=\s*false/);
  });

  it('uses an aria-expanded toggle button', () => {
    const src = read('src/components/chat/ToolCallCard.tsx');
    expect(src).toMatch(/aria-expanded/);
  });

  it('renders tool name + category + risk pill', () => {
    const src = read('src/components/chat/ToolCallCard.tsx');
    expect(src).toMatch(/tool-call-card-name/);
    expect(src).toMatch(/tool-call-card-category/);
    expect(src).toMatch(/tool-call-card-risk/);
  });

  it('renders args as a key:value list (dl/dt/dd)', () => {
    const src = read('src/components/chat/ToolCallCard.tsx');
    expect(src).toMatch(/<dl/);
    expect(src).toMatch(/<dt/);
    expect(src).toMatch(/<dd/);
  });

  it('renders the result in a <pre> with a copy affordance', () => {
    const src = read('src/components/chat/ToolCallCard.tsx');
    expect(src).toMatch(/<pre/);
    expect(src).toMatch(/tool-call-card-result/);
  });

  it('shows the user\'s consent choice when present', () => {
    const src = read('src/components/chat/ToolCallCard.tsx');
    expect(src).toMatch(/consentChoice/);
    expect(src).toMatch(/CONSENT_LABELS/);
  });

  it('supports the 4 risk levels (low / medium / high / critical)', () => {
    const src = read('src/components/chat/ToolCallCard.tsx');
    expect(src).toMatch(/low/);
    expect(src).toMatch(/medium/);
    expect(src).toMatch(/high/);
    expect(src).toMatch(/critical/);
  });
});
