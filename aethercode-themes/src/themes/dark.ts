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
 * dark — the previous default look. Borrowed from the existing
 * AetherCode TUI (prior round): warm amber primary on a near-black
 * background.
 *
 * design.md §5.1.2: "dark (existing)".
 */

import type { Theme } from '../types.js';
import { DEFAULT_FONT } from '../defaults.js';

export const dark: Theme = {
  name: 'dark',
  isDark: true,
  colors: {
    background: '#0d1117',
    foreground: '#e6edf3',
    accent: '#FFB347', // AetherCode brand amber
    muted: '#7d8590',
    success: '#3fb950',
    warning: '#d29922',
    error: '#f85149',
    border: '#30363d',
    selection: '#1f6feb',
  },
  font: DEFAULT_FONT,
};
