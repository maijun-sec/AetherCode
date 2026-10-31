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
 * high-contrast — accessibility theme. Maximum contrast between
 * foreground and background, bright saturated accents, thick
 * borders. Follows WCAG AAA where possible (≥7:1 fg/bg).
 * design.md §5.1.2.
 */

import type { Theme } from '../types.js';
import { DEFAULT_FONT } from '../defaults.js';

export const highContrast: Theme = {
  name: 'high-contrast',
  isDark: true,
  colors: {
    background: '#000000',
    foreground: '#ffffff',
    accent: '#00ffff', // cyan
    muted: '#c0c0c0', // silver
    success: '#00ff00', // lime
    warning: '#ffff00', // yellow
    error: '#ff4040', // bright red
    border: '#ffffff',
    selection: '#0000ff', // pure blue
  },
  font: DEFAULT_FONT,
};
