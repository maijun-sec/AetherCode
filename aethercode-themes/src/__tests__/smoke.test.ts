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
