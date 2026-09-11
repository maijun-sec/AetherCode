/**
 * T-406: default themes ship with the expected 5 names, every
 * required color/font key is present, and the catalog is in display
 * order.
 */

import { describe, it, expect } from 'vitest';
import {
  BUILTIN_THEMES,
  BUILTIN_THEMES_BY_NAME,
  DEFAULT_THEME_NAME,
  light,
  dark,
  solarizedLight,
  solarizedDark,
  highContrast,
  DEFAULT_FONT,
} from '../index.js';
import type { Theme } from '../types.js';

const REQUIRED_COLOR_KEYS = [
  'background',
  'foreground',
  'accent',
  'muted',
  'success',
  'warning',
  'error',
  'border',
  'selection',
] as const;

const REQUIRED_FONT_KEYS = ['family', 'size', 'lineHeight', 'ligatures'] as const;

function assertValidTheme(t: Theme): void {
  expect(t.name).toMatch(/^[a-z][a-z0-9-]*$/);
  expect(typeof t.isDark).toBe('boolean');
  for (const k of REQUIRED_COLOR_KEYS) {
    expect(typeof t.colors[k], `colors.${k} on ${t.name}`).toBe('string');
    expect((t.colors[k] as string).length, `colors.${k} on ${t.name} empty`).toBeGreaterThan(0);
  }
  for (const k of REQUIRED_FONT_KEYS) {
    expect(t.font[k], `font.${k} on ${t.name}`).toBeDefined();
  }
  expect(t.font.size).toBeGreaterThan(0);
  expect(t.font.lineHeight).toBeGreaterThan(0);
}

describe('aethercode-themes — default themes (T-403)', () => {
  it('ships exactly 5 built-in themes', () => {
    expect(BUILTIN_THEMES).toHaveLength(5);
  });

  it('ships the expected 5 names', () => {
    const names = BUILTIN_THEMES.map((t) => t.name).sort();
    expect(names).toEqual(['dark', 'high-contrast', 'light', 'solarized-dark', 'solarized-light']);
  });

  it('light is the new app default', () => {
    expect(DEFAULT_THEME_NAME).toBe('light');
    expect(light.isDark).toBe(false);
  });

  it('every theme has a complete colors + font block', () => {
    for (const t of BUILTIN_THEMES) {
      assertValidTheme(t);
    }
  });

  it('the by-name map matches the list', () => {
    for (const t of BUILTIN_THEMES) {
      expect(BUILTIN_THEMES_BY_NAME[t.name]).toBe(t);
    }
  });

  it('dark theme isDark=true; the other 4 light themes are isDark=false (except high-contrast)', () => {
    expect(light.isDark).toBe(false);
    expect(dark.isDark).toBe(true);
    expect(solarizedLight.isDark).toBe(false);
    expect(solarizedDark.isDark).toBe(true);
    expect(highContrast.isDark).toBe(true);
  });

  it('individual theme exports are the same references as in the catalog', () => {
    expect(BUILTIN_THEMES_BY_NAME['light']).toBe(light);
    expect(BUILTIN_THEMES_BY_NAME['dark']).toBe(dark);
    expect(BUILTIN_THEMES_BY_NAME['solarized-light']).toBe(solarizedLight);
    expect(BUILTIN_THEMES_BY_NAME['solarized-dark']).toBe(solarizedDark);
    expect(BUILTIN_THEMES_BY_NAME['high-contrast']).toBe(highContrast);
  });

  it('default font is JetBrains Mono 14px ligatures on (per spec.md §5.1)', () => {
    expect(DEFAULT_FONT).toEqual({
      family: 'JetBrains Mono',
      size: 14,
      lineHeight: 1.4,
      ligatures: true,
    });
  });
});
