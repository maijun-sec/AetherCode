/**
 * aethercode-themes — public API.
 *
 * Phase 5.1 (T-400–T-406): types, default themes, user YAML loader,
 * ThemeStore with `themeChanged` event, and the public surface that
 * TUI / desktop / engine import.
 *
 * Phase 5 Round 2 (T-407–T-411, T-422):
 *   - migration.ts    — `desktop-state.json` → `theme.json`
 *   - persistence.ts  — atomic write helper for `theme.json`
 *   - hot-reload.ts   — `fs.watch`-driven live re-read
 *   - default-theme.ts— surface-aware default (light for app,
 *                       dark for tui)
 *   - font-config.ts  — `<UserHome>/.aethercode/font.yaml`
 *   - rpc-handlers.ts — JSON-RPC handler bundle for the daemon
 *
 * The on-disk shape of a user theme is identical to the `Theme`
 * interface — no wrapping, no extra fields. This is intentional: a
 * user can `cat themes/my.yaml` and see exactly what the runtime sees.
 */

// ---- types ----
export type { Theme, ThemeColors, ThemeFont, ListedTheme, ThemeOrigin, ActiveThemeFile } from './types.js';

// ---- built-ins ----
export {
  BUILTIN_THEMES,
  BUILTIN_THEMES_BY_NAME,
  DEFAULT_THEME_NAME,
} from './builtin.js';
export { DEFAULT_FONT } from './defaults.js';
export { light } from './themes/light.js';
export { dark } from './themes/dark.js';
export { solarizedLight } from './themes/solarized-light.js';
export { solarizedDark } from './themes/solarized-dark.js';
export { highContrast } from './themes/high-contrast.js';

// ---- user loader ----
export {
  defaultUserThemesDir,
  defaultThemeFilePath,
  loadUserThemesFromDir,
  writeThemeYaml,
  coerceTheme,
  InvalidThemeError,
} from './user-themes.js';

// ---- events ----
export { ThemeEventBus } from './events.js';
export type { ThemeChangedEvent, ThemeEventMap } from './events.js';

// ---- store ----
export { ThemeStore, UnknownThemeError, ActiveThemeFileError } from './theme-store.js';
export type { ThemeStoreOptions } from './theme-store.js';

// ---- Round 2 additions ----

// T-410: surface-aware default
export {
  defaultThemeForSurface,
  APP_DEFAULT_THEME_NAME,
  TUI_DEFAULT_THEME_NAME,
  type Surface,
} from './default-theme.js';

// T-401 extension: user font config
export {
  defaultFontConfigPath,
  loadFontConfig,
  writeFontConfig,
  coerceFont,
  InvalidFontConfigError,
} from './font-config.js';

// T-408: atomic persistence
export { readActiveThemeFile, writeActiveThemeFile, type PersistenceOptions } from './persistence.js';

// T-411: legacy migration
export {
  migrateFromDesktopState,
  legacyStateFilePath,
  LegacyStateFileError,
  type MigrationOptions,
  type MigrationResult,
} from './migration.js';

// T-409: hot-reload
export { ThemeHotReloader, type ThemeHotReloaderOptions } from './hot-reload.js';

// RPC handler bundle
export {
  THEME_RPC_HANDLERS,
  themeList,
  themeGet,
  themeSet,
  themeImport,
  themeExport,
  themeActive,
  themeDefault,
  themeMigrate,
  fontGet,
  fontSet,
  RpcParamError,
  type ThemeRpcContext,
  type RpcHandler,
} from './rpc-handlers.js';
