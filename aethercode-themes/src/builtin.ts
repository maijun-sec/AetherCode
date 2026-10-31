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
 * aethercode-themes — built-in theme catalog.
 *
 * 5 themes ship with the module (design.md §5.1.2):
 *   - light (new app default)
 *   - dark (existing)
 *   - solarized-light
 *   - solarized-dark
 *   - high-contrast
 *
 * The `light` theme is the new app default per spec.md §5.1. The
 * `dark` theme is the previous default.
 */

import type { Theme } from './types.js';
import { light } from './themes/light.js';
import { dark } from './themes/dark.js';
import { solarizedLight } from './themes/solarized-light.js';
import { solarizedDark } from './themes/solarized-dark.js';
import { highContrast } from './themes/high-contrast.js';

/** Ordered list of shipped themes. Order = display order in the picker. */
export const BUILTIN_THEMES: ReadonlyArray<Theme> = [
  light,
  dark,
  solarizedLight,
  solarizedDark,
  highContrast,
];

/** Name → theme lookup. */
export const BUILTIN_THEMES_BY_NAME: Readonly<Record<string, Theme>> = Object.freeze(
  Object.fromEntries(BUILTIN_THEMES.map((t) => [t.name, t])),
);

/** The default app theme when no `theme.json` is present. */
export const DEFAULT_THEME_NAME = 'light';
