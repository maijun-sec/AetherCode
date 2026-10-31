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
 * aethercode-themes — surface-aware default theme resolver (T-410).
 *
 * design.md §5.3 / spec.md §5.1: the desktop app ships with `light` as the
 * default background (was `dark`); the TUI keeps `dark` so the existing
 * warm-amber palette on a black terminal doesn't surprise long-time
 * terminal users. Both surfaces are allowed to override via
 * `<UserHome>/.aethercode/theme.json`.
 *
 * The default for a *given* surface is intentionally a pure function —
 * no file I/O, no globals — so it can be used in tests, in CLI
 * defaults, and in the React picker before the async `load()` resolves.
 */

/** The two rendering surfaces. Kept narrow on purpose. */
export type Surface = 'app' | 'tui';

/** The new app default per spec.md §5.1. */
export const APP_DEFAULT_THEME_NAME = 'light';
/** The TUI keeps the previous dark background — see design.md §5.3. */
export const TUI_DEFAULT_THEME_NAME = 'dark';

/**
 * Resolve the default theme name for a surface. Falls back to the
 * shared `light` if the caller asks about an unknown surface, so
 * that the desktop app wins for any new surface that doesn't pick
 * its own default.
 */
export function defaultThemeForSurface(surface: Surface): string {
  switch (surface) {
    case 'app':
      return APP_DEFAULT_THEME_NAME;
    case 'tui':
      return TUI_DEFAULT_THEME_NAME;
    default: {
      // Exhaustiveness — the union is closed.
      const _exhaustive: never = surface;
      void _exhaustive;
      return APP_DEFAULT_THEME_NAME;
    }
  }
}
