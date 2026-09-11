import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * tests for the TUI/Desktop skip-low + permissionModeSuggestion
 * wiring in the StatusBar.
 *
 * Source-only assertions, same pattern as ToolsPanel.test.ts
 * (prior round) and SessionPickerModal.test.tsx (prior round). The desktop
 * test harness doesn't have @testing-library/react installed.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R112: StatusBar skip-low + suggestion', () => {
  it('StatusBar reads lastSkipLow from engineState', () => {
    const src = read('src/components/StatusBar.tsx');
    expect(src).toMatch(/lastSkipLow/);
  });

  it('StatusBar reads skipLowWaterline from engineState', () => {
    const src = read('src/components/StatusBar.tsx');
    expect(src).toMatch(/skipLowWaterline/);
  });

  it('StatusBar renders the (low!) suffix when recent or at waterline', () => {
    const src = read('src/components/StatusBar.tsx');
    expect(src).toMatch(/\(low!\)/);
  });

  it('StatusBar applies status-skip-low class when low', () => {
    const src = read('src/components/StatusBar.tsx');
    expect(src).toMatch(/status-skip-low/);
  });

  it('StatusBar reads permissionModeSuggestion from engineState', () => {
    const src = read('src/components/StatusBar.tsx');
    expect(src).toMatch(/permissionModeSuggestion/);
  });

  it('StatusBar renders the 💡 suggested badge', () => {
    const src = read('src/components/StatusBar.tsx');
    expect(src).toMatch(/💡 suggested/);
  });

  it('StatusBar only shows suggestion when mode differs from current', () => {
    const src = read('src/components/StatusBar.tsx');
    expect(src).toMatch(/permissionModeSuggestion\.mode\s*!==\s*engineState\.permissionMode/);
  });

  it('StatusBar.css has the status-skip-low style', () => {
    const css = read('src/components/StatusBar.css');
    expect(css).toMatch(/\.status-item\.status-skip-low\b/);
  });

  it('StatusBar.css has the status-permission-suggestion style', () => {
    const css = read('src/components/StatusBar.css');
    expect(css).toMatch(/\.status-item\.status-permission-suggestion\b/  );
  });

  it('StatusBar.css has a skip-low pulse animation', () => {
    const css = read('src/components/StatusBar.css');
    expect(css).toMatch(/skip-low-pulse/);
  });
});
