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
