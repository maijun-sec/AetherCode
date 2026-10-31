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
 * solarized-light — Ethan Schoonover's classic warm-light palette.
 * design.md §5.1.2: ships with the app.
 */

import type { Theme } from '../types.js';
import { DEFAULT_FONT } from '../defaults.js';

export const solarizedLight: Theme = {
  name: 'solarized-light',
  isDark: false,
  colors: {
    background: '#fdf6e3', // base3
    foreground: '#586e75', // base01
    accent: '#268bd2', // blue
    muted: '#93a1a1', // base1
    success: '#859900', // green
    warning: '#b58900', // yellow
    error: '#dc322f', // red
    border: '#eee8d5', // base2
    selection: '#eee8d5',
  },
  font: DEFAULT_FONT,
};
