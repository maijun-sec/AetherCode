/**
 * T-454 (Phase 5 R3): ThemeSettings (desktop) tests.
 *
 * Vitest with source-code assertions, matching the pattern used
 * by every other *.test.tsx in this directory. We assert on the
 * component's source (imports, props, helpers) rather than
 * rendering it with testing-library — keeps the test env
 * dependency-free (no jsdom / @testing-library/react).
 */

import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

const src = readFileSync(join(root, 'src', 'components', 'ThemeSettings.tsx'), 'utf-8');
const css = readFileSync(join(root, 'src', 'components', 'ThemeSettings.css'), 'utf-8');

describe('ThemeSettings (T-454)', () => {
  it('component file exists', () => {
    expect(existsSync(join(root, 'src', 'components', 'ThemeSettings.tsx'))).toBe(true);
  });

  it('exports the React component as a named function', () => {
    expect(src).toMatch(/export function ThemeSettings\b/);
  });

  it('imports aethercode-themes types (ThemeStore, Theme, ListedTheme)', () => {
    expect(src).toMatch(/import type \{[^}]*ThemeStore[^}]*\} from 'aethercode-themes'/);
    expect(src).toMatch(/Theme[^,}]*/);
    expect(src).toMatch(/ListedTheme/);
  });

  it('declares the ThemeSettingsDraft interface (the onApply payload)', () => {
    expect(src).toMatch(/export interface ThemeSettingsDraft/);
    expect(src).toMatch(/theme: string/);
    expect(src).toMatch(/fontFamily: string/);
    expect(src).toMatch(/fontSize: number/);
    expect(src).toMatch(/background: 'light' \| 'dark' \| 'auto'/);
  });

  it('subscribes to the store\'s "change" event (live theme refresh)', () => {
    expect(src).toMatch(/store\.on\(\s*'change'/);
  });

  it('renders a list row for every theme (ts-theme-list / ts-theme-row)', () => {
    expect(src).toMatch(/ts-theme-list/);
    expect(src).toMatch(/ts-theme-row/);
  });

  it('marks the user theme with the (user) badge', () => {
    expect(src).toMatch(/\(user\)/);
  });

  it('renders a 9-swatch palette per row', () => {
    // 9 swatches: background, foreground, accent, muted, success,
    // warning, error, border, selection.
    const swatchProps = src.match(/<Swatch color=\{t\.colors\.\w+\}/g) || [];
    expect(swatchProps.length).toBe(9);
  });

  it('includes a live preview that updates on hover', () => {
    expect(src).toMatch(/ts-preview/);
    expect(src).toMatch(/onMouseEnter/);
    expect(src).toMatch(/onMouseLeave/);
    expect(src).toMatch(/const previewName = hover \?\? draftTheme/);
  });

  it('exposes a font family input + dropdown of presets', () => {
    expect(src).toMatch(/const FONT_PRESETS/);
    expect(src).toMatch(/'JetBrains Mono'/);
    expect(src).toMatch(/'Fira Code'/);
  });

  it('exposes a font size input that clamps to [8, 32]', () => {
    expect(src).toMatch(/Math\.max\(8, Math\.min\(32/);
  });

  it('exposes a background segmented control (auto / light / dark)', () => {
    expect(src).toMatch(/\['auto', 'light', 'dark'\]/);
    expect(src).toMatch(/ts-segmented/);
    expect(src).toMatch(/ts-segment/);
  });

  it('has an Apply button that fires onApply with the draft', () => {
    expect(src).toMatch(/onApply\?\.\(draft\)/);
    expect(src).toMatch(/>Apply</);
  });

  it('has a Cancel button + close button + onClose wiring', () => {
    expect(src).toMatch(/>Cancel</);
    expect(src).toMatch(/onClick=\{onClose\}/);
  });

  it('CSS file exists with the panel + overlay + swatch styles', () => {
    expect(existsSync(join(root, 'src', 'components', 'ThemeSettings.css'))).toBe(true);
    expect(css).toMatch(/\.theme-settings-overlay/);
    expect(css).toMatch(/\.theme-settings-panel/);
    expect(css).toMatch(/\.ts-swatch/);
    expect(css).toMatch(/\.ts-theme-list/);
    expect(css).toMatch(/\.ts-segmented/);
  });

  it('matches the SettingsPanel visual language (overlay + panel)', () => {
    // Same overlay/panel CSS structure as SettingsPanel.
    expect(css).toMatch(/position: fixed/);
    expect(css).toMatch(/inset: 0/);
    expect(css).toMatch(/background: rgba\(0, 0, 0, 0\.5\)/);
    expect(css).toMatch(/border-radius: 8px/);
    expect(css).toMatch(/z-index: 100/);
  });

  it('aethercode-themes is no longer wired as a dependency in package.json (R209)', () => {
    // removed the `file:../aethercode-themes` dep
    // because the sibling package never shipped and the
    // R209 actual implementation went lightweight (CSS
    // variables + data-theme attribute), not the
    // heavyweight sibling package. The ThemeSettings
    // component now reads the sibling package dynamically
    // (or no-ops if missing) — there's nothing to wire
    // at install time.
    const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf-8'));
    expect(pkg.dependencies).toBeTruthy();
    expect(pkg.dependencies['aethercode-themes']).toBeUndefined();
  });
});
