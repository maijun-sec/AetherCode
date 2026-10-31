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

// tests for the medium+high-risk auto-approve
// badge in StatusBar.
//
// Pure-render test pattern (prior round
// precedent): regex pin via readFileSync +
// toContain. The R120 StatusBar suite uses
// the same approach; we mirror it for
// consistency.

import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';

const TSX = 'D:/work/workspace/idea/engine/AetherCode/aethercode-desktop/src/components/StatusBar.tsx';
const CSS = 'D:/work/workspace/idea/engine/AetherCode/aethercode-desktop/src/components/StatusBar.css';

const tsx = readFileSync(TSX, 'utf8');
const css = readFileSync(CSS, 'utf8');

describe('R126 StatusBar medium+high-risk badge — code pins', () => {
  it('StatusBar pulls the four R126 fields from the store', () => {
    expect(tsx).toContain('autoApproveMediumHigh');
    expect(tsx).toContain('autoApprovedElevatedCount');
    expect(tsx).toContain('setAutoApproveMediumHigh');
  });
  it('badge uses ▲ when enabled, ▽ when disabled', () => {
    // Two glyph uses — one for the on-state,
    // one for the off-state.
    expect(tsx).toContain("'▲'");
    expect(tsx).toContain("'▽'");
  });
  it('badge uses "auto-allow+" label', () => {
    // The label is a JSX text child.
    expect(tsx).toMatch(/auto-allow\+/);
  });
  it('hidden when flag is off AND count is 0', () => {
    // Conditional: (flag || count > 0)
    expect(tsx).toMatch(/autoApproveMediumHigh \|\| autoApprovedElevatedCount > 0/);
  });
  it('onClick toggles the flag', () => {
    expect(tsx).toContain('void setAutoApproveMediumHigh(!autoApproveMediumHigh)');
  });
  it('tooltip warns that critical risk is never auto-approved', () => {
    expect(tsx).toMatch(/Critical risk.*NEVER/);
  });
});

describe('R126 StatusBar CSS', () => {
  it('defines the elevated badge style', () => {
    expect(css).toContain('.status-auto-approve-elevated');
  });
  it('uses amber / warning colour', () => {
    expect(css).toMatch(/status-auto-approve-elevated[\s\S]{0,200}var\(--warning/);
  });
  it('hover state for the elevated badge', () => {
    expect(css).toContain('.status-auto-approve-elevated:hover');
  });
  it('disabled state for the elevated badge', () => {
    expect(css).toContain('.status-auto-approve-elevated.is-disabled');
  });
  it('count chip inside the elevated badge', () => {
    expect(css).toContain('.status-auto-approve-elevated .status-auto-approve-count');
  });
});
