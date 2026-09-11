/**
 * T-406 smoke: the public surface re-exports the symbols the
 * downstream modules (TUI, desktop, engine) will import.
 */

import { describe, it, expect } from 'vitest';
import * as api from '../index.js';

describe('aethercode-themes — public API smoke', () => {
  it('exports the expected runtime surface', () => {
    const keys = Object.keys(api).sort();
    // types are erased; the runtime surface is what counts.
    for (const k of [
      // Round 1
      'BUILTIN_THEMES',
      'BUILTIN_THEMES_BY_NAME',
      'DEFAULT_THEME_NAME',
      'DEFAULT_FONT',
      'light',
      'dark',
      'solarizedLight',
      'solarizedDark',
      'highContrast',
      'defaultUserThemesDir',
      'defaultThemeFilePath',
      'loadUserThemesFromDir',
      'writeThemeYaml',
      'coerceTheme',
      'InvalidThemeError',
      'ThemeEventBus',
      'ThemeStore',
      'UnknownThemeError',
      'ActiveThemeFileError',
      // Round 2: T-410 default
      'defaultThemeForSurface',
      'APP_DEFAULT_THEME_NAME',
      'TUI_DEFAULT_THEME_NAME',
      // Round 2: T-401-ext font config
      'defaultFontConfigPath',
      'loadFontConfig',
      'writeFontConfig',
      'coerceFont',
      'InvalidFontConfigError',
      // Round 2: T-408 persistence
      'readActiveThemeFile',
      'writeActiveThemeFile',
      // Round 2: T-411 migration
      'migrateFromDesktopState',
      'legacyStateFilePath',
      'LegacyStateFileError',
      // Round 2: T-409 hot-reload
      'ThemeHotReloader',
      // Round 2: RPC handlers
      'THEME_RPC_HANDLERS',
      'themeList',
      'themeGet',
      'themeSet',
      'themeImport',
      'themeExport',
      'themeActive',
      'themeDefault',
      'themeMigrate',
      'fontGet',
      'fontSet',
      'RpcParamError',
    ]) {
      expect(keys, `missing export: ${k}`).toContain(k);
    }
  });
});
