import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * per-tool "safe" badge in a dedicated ToolsPanel.
 *
 * Source-only assertions — the panel itself is a React
 * component that would need a DOM harness to render, which
 * the existing Desktop tests don't do. We verify the wiring
 * (file presence, imports, App.tsx mount, store field) so
 * any future cleanup that removes the panel is caught.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R104: Tools & Permission panel', () => {
  it('ToolsPanel.tsx exists', () => {
    const path = join(root, 'src', 'components', 'ToolsPanel.tsx');
    expect(existsSync(path)).toBe(true);
  });

  it('ToolsPanel.css exists', () => {
    const path = join(root, 'src', 'components', 'ToolsPanel.css');
    expect(existsSync(path)).toBe(true);
  });

  it('ToolsPanel.tsx imports useStore + ./ToolsPanel.css', () => {
    const src = readFileSync(join(root, 'src', 'components', 'ToolsPanel.tsx'), 'utf-8');
    expect(src).toMatch(/import\s+\{[^}]*useStore[^}]*\}\s+from\s+['"]\.\.\/store['"]/);
    expect(src).toMatch(/import\s+['"]\.\/ToolsPanel\.css['"]/);
  });

  it('ToolsPanel.tsx renders ALLOW/ASK/DENY badge colour map', () => {
    const src = readFileSync(join(root, 'src', 'components', 'ToolsPanel.tsx'), 'utf-8');
    expect(src).toContain('ALLOW');
    expect(src).toContain('ASK');
    expect(src).toContain('DENY');
    const colors = src.match(/#[0-9a-fA-F]{6}/g) ?? [];
    expect(new Set(colors).size).toBeGreaterThanOrEqual(3);
  });

  it('ToolsPanel.tsx wires Esc to onClose', () => {
    const src = readFileSync(join(root, 'src', 'components', 'ToolsPanel.tsx'), 'utf-8');
    expect(src).toMatch(/key\s*===\s*['"]Escape['"]/);
    expect(src).toMatch(/onClose\(\)/);
  });

  it('App.tsx imports ToolsPanel', () => {
    const src = readFileSync(join(root, 'src', 'App.tsx'), 'utf-8');
    expect(src).toMatch(/import\s+\{\s*ToolsPanel\s*\}\s+from\s+['"]\.\/components\/ToolsPanel['"]/);
  });

  it('App.tsx renders ToolsPanel conditionally', () => {
    const src = readFileSync(join(root, 'src', 'App.tsx'), 'utf-8');
    expect(src).toMatch(/showTools\s*&&\s*<ToolsPanel/);
  });

  it('App.tsx wires Ctrl/Cmd+T to toggle the panel', () => {
    const src = readFileSync(join(root, 'src', 'App.tsx'), 'utf-8');
    expect(src).toMatch(/e\.key\s*===\s*['"]t['"]\s*\|\|\s*e\.key\s*===\s*['"]T['"]/);
    expect(src).toMatch(/setShowTools/);
  });

  it('Header.tsx has a tools button (🔧)', () => {
    const src = readFileSync(join(root, 'src', 'components', 'Header.tsx'), 'utf-8');
    expect(src).toMatch(/onToolsClick/);
    expect(src).toMatch(/🔧/);
  });

  it('store/index.ts adds toolActions to the initial state', () => {
    const src = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');
    expect(src).toMatch(/toolActions:\s*\[\]/);
  });

  it('store/index.ts fetches listToolActions on boot', () => {
    const src = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');
    expect(src).toMatch(/rpc\.listToolActions\(\)/);
  });

  it('lib/methods.ts has the listToolActions typed wrapper', () => {
    const src = readFileSync(join(root, 'src', 'lib', 'methods.ts'), 'utf-8');
    expect(src).toMatch(/listToolActions\(\)/);
    expect(src).toMatch(/ToolActionInfo/);
  });
});
