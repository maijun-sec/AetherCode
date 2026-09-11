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
