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
 * light — the new app default (white background, dark text).
 * design.md §5.1.2: "light (new default for the app)".
 */

import type { Theme } from '../types.js';
import { DEFAULT_FONT } from '../defaults.js';

export const light: Theme = {
  name: 'light',
  isDark: false,
  colors: {
    background: '#ffffff',
    foreground: '#1f2328',
    accent: '#0969da',
    muted: '#6e7781',
    success: '#1a7f37',
    warning: '#9a6700',
    error: '#cf222e',
    border: '#d0d7de',
    selection: '#b6e3ff',
  },
  font: DEFAULT_FONT,
};
