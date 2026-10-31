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
