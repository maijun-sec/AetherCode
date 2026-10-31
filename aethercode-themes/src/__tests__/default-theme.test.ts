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

/**
 * T-410: surface-aware default theme resolver.
 *
 *  - app → "light" (spec.md §5.1, design.md §5.3)
 *  - tui → "dark"   (preserves the existing TUI amber-on-dark look)
 *  - unknown surface → "light" as the safe default
 *  - the exported constants are stable across the surface switch
 */

import { describe, it, expect } from 'vitest';
import {
  defaultThemeForSurface,
  APP_DEFAULT_THEME_NAME,
  TUI_DEFAULT_THEME_NAME,
  type Surface,
} from '../index.js';

describe('aethercode-themes — default theme (T-410)', () => {
  it('app surface returns "light"', () => {
    expect(defaultThemeForSurface('app')).toBe('light');
  });

  it('tui surface returns "dark"', () => {
    expect(defaultThemeForSurface('tui')).toBe('dark');
  });

  it('unknown surface falls back to "light"', () => {
    // Force the type system to accept a bogus value so we can
    // confirm the exhaustive-default branch.
    const bogus = 'nonsense' as Surface;
    expect(defaultThemeForSurface(bogus)).toBe('light');
  });

  it('the constants match what the resolver returns', () => {
    expect(APP_DEFAULT_THEME_NAME).toBe('light');
    expect(TUI_DEFAULT_THEME_NAME).toBe('dark');
    expect(defaultThemeForSurface('app')).toBe(APP_DEFAULT_THEME_NAME);
    expect(defaultThemeForSurface('tui')).toBe(TUI_DEFAULT_THEME_NAME);
  });
});
