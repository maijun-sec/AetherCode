import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * workflow picker LRU.
 *
 * "I run the same workflow every time" — a user who runs the
 * same workflow repeatedly shouldn't have to scroll
 * past the rest of the list every time. R117 adds a
 * "Recently used" section that surfaces the 5 most-recent
 * picks above the full list.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R117: store — recentWorkflows field + LRU semantics', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('declares recentWorkflows: string[] in the AppState interface', () => {
    expect(storeSrc).toMatch(/recentWorkflows:\s*string\[\]/);
  });

  it('initial state seeds recentWorkflows: []', () => {
    expect(storeSrc).toMatch(/recentWorkflows:\s*\[\]/);
  });

  it('declares recordRecentWorkflow: (name: string) => void in the AppState interface', () => {
    expect(storeSrc).toMatch(/recordRecentWorkflow:\s*\(name:\s*string\)\s*=>\s*void/);
  });

  it('recordRecentWorkflow implementation: push-to-front', () => {
    const block = storeSrc.match(/recordRecentWorkflow:\s*\(name\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/\[name,\s*\.\.\.cur\.filter/);
  });

  it('recordRecentWorkflow: dedupe (filter removes existing entry before prepending)', () => {
    // The LRU semantics: re-picking an existing
    // entry moves it to the front without duplicating.
    // The filter step is the dedupe.
    const block = storeSrc.match(/recordRecentWorkflow:\s*\(name\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/cur\.filter\(\(n\)\s*=>\s*n\s*!==\s*name\)/);
  });

  it('recordRecentWorkflow: caps at 5', () => {
    const block = storeSrc.match(/recordRecentWorkflow:\s*\(name\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/\.slice\(0,\s*5\)/);
  });

  it('recordRecentWorkflow: persists to localStorage keyed by cwd', () => {
    // Per-cwd key so a project A LRU doesn't bleed
    // into project B. The key prefix matches the
    // read path in initialize().
    const block = storeSrc.match(/recordRecentWorkflow:\s*\(name\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/aethercode-recent-workflows:/);
    expect(block![0]).toMatch(/window\.localStorage\.setItem/);
  });

  it('setActiveWorkflow calls recordRecentWorkflow when wf is non-null', () => {
    // The picker's onClick → setActiveWorkflow path
    // should LRU-push automatically. A separate
    // setActiveWorkflow(null) (the "clear" path) does
    // NOT push (the dedupe logic is in the LRU
    // helper, not in the clear path).
    const block = storeSrc.match(/setActiveWorkflow:\s*\(wf\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/if\s*\(wf\)\s*\{/);
    expect(block![0]).toMatch(/get\(\)\.recordRecentWorkflow\(wf\.name\)/);
  });

  it('initialize() hydrates recentWorkflows from localStorage', () => {
    const initBlock = storeSrc.match(/recentWorkflows:\s*\(\(\)\s*=>\s*\{[\s\S]*?\}\)\(\)/);
    expect(initBlock).toBeTruthy();
    expect(initBlock![0]).toMatch(/aethercode-recent-workflows:/);
    expect(initBlock![0]).toMatch(/JSON\.parse/);
  });
});

describe('R117: MessageInput — workflow picker LRU section', () => {
  const tsxSrc = readFileSync(join(root, 'src', 'components', 'MessageInput.tsx'), 'utf-8');
  const cssSrc = readFileSync(join(root, 'src', 'components', 'MessageInput.css'), 'utf-8');

  it('pulls recentWorkflows from the store', () => {
    expect(tsxSrc).toMatch(/recentWorkflows\s*,/);
  });

  it('renders a Recently used section above the full list', () => {
    expect(tsxSrc).toContain('workflow-recent-section');
    expect(tsxSrc).toContain('最近使用');
  });

  it('intersects the LRU with availableWorkflows (filters ghosts)', () => {
    // A workflow that was in the LRU but has since
    // been deleted shouldn't render as a ghost
    // button. The component uses a byName Map +
    // a filter(Boolean) to skip missing entries.
    const block = tsxSrc.match(/recentPicks\s*=\s*recentWorkflows[\s\S]*?\.filter\(/);
    expect(block).toBeTruthy();
  });

  it('hides the section when LRU is empty OR availableWorkflows is empty', () => {
    // The conditional `recentWorkflows.length > 0 &&
    // availableWorkflows.length > 0` ensures the
    // section doesn't add visual noise to a
    // first-launch (LRU empty) or empty-picker
    // (no workflows at all) state.
    expect(tsxSrc).toMatch(/recentWorkflows\.length\s*>\s*0\s*&&\s*availableWorkflows\.length\s*>\s*0/);
  });

  it('CSS adds a section label + divider for the LRU', () => {
    expect(cssSrc).toContain('.workflow-recent-section');
    expect(cssSrc).toContain('.workflow-recent-label');
    expect(cssSrc).toContain('.workflow-recent-divider');
  });
});
