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
 * aethercode-themes — type definitions.
 *
 * See design.md §5.1.1. The shape is deliberately stable: a `Theme` is
 * a flat record of {name, isDark, colors, font} and the on-disk JSON
 * form is identical (no extra wrapping). That makes the YAML user
 * override format trivial — it's the same object literal.
 */

/** All 9 semantic color roles. */
export interface ThemeColors {
  /** Page / panel background. */
  readonly background: string;
  /** Default text color on top of `background`. */
  readonly foreground: string;
  /** Highlighted text, brand chips, focused borders. */
  readonly accent: string;
  /** De-emphasised text, captions, dividers. */
  readonly muted: string;
  /** Success / ok states (tool ok, confirmation chips). */
  readonly success: string;
  /** Warning / caution states. */
  readonly warning: string;
  /** Error / failure states. */
  readonly error: string;
  /** Panel / box borders, dividers. */
  readonly border: string;
  /** Selection highlight (selected list row, current line). */
  readonly selection: string;
}

/** Font preferences shipped alongside the color palette. */
export interface ThemeFont {
  /** Font family, e.g. "JetBrains Mono", "Menlo". */
  readonly family: string;
  /** Font size in pixels. */
  readonly size: number;
  /** Line-height multiplier (e.g. 1.4 means 1.4 × size). */
  readonly lineHeight: number;
  /** Whether to enable programming ligatures. */
  readonly ligatures: boolean;
}

/** A complete, immutable theme. */
export interface Theme {
  /**
   * Stable identifier. Default themes use kebab-case: "light", "dark",
   * "solarized-light", "solarized-dark", "high-contrast". User themes
   * use the basename of their YAML file.
   */
  readonly name: string;
  /**
   * Whether the background is dark. The app uses this to invert
   * misc single-color icons (e.g. spinners, status dots).
   */
  readonly isDark: boolean;
  readonly colors: ThemeColors;
  readonly font: ThemeFont;
}

/** Where a theme came from. The store exposes this so the picker can
 *  label user themes with a "(user)" badge. */
export type ThemeOrigin = 'builtin' | 'user';

/** A theme as listed by the store — same shape as `Theme` plus origin. */
export interface ListedTheme extends Theme {
  readonly origin: ThemeOrigin;
}

/** The on-disk shape of `<UserHome>/.aethercode/theme.json`. */
export interface ActiveThemeFile {
  /** Schema version — bumped on breaking changes. */
  readonly schemaVersion: 1;
  /** The active theme name. Resolved via `ThemeStore.get`. */
  readonly active: string;
}

