/**
 * T-454 (Phase 5 R3): ThemeSettings (desktop).
 *
 * Modal panel that lets the user configure the app's visual
 * surface: theme (color palette), font family, font size, and
 * background. Pairs with `aethercode-themes` so the chosen
 * theme / font round-trips through the same `theme.json` /
 * `font.yaml` files the TUI uses.
 *
 * design.md §5.1.4 / §5.3: live switching, user themes from
 * `<UserHome>/.aethercode/themes/`, and the desktop app's
 * default is now `light` (T-410).
 *
 * spec.md §5.2: ThemeSettings hosts the three knobs the user
 * needs:
 *   1. Theme picker — every theme the `ThemeStore` knows
 *      (built-ins + user YAML). Live preview swatch.
 *   2. Font family — free-text + common-presets dropdown.
 *   3. Background — light/dark, driven by the chosen theme
 *      but also overridable (some users want a dark theme on
 *      a forced-light background — e.g. for screenshots).
 *
 * The component is prop-driven: the host passes the `ThemeStore`
 * and a `onClose` callback. The component owns its own draft
 * state and only commits on "Apply" / "Save". This avoids a
 * swarm of side-effects when the user is just browsing.
 */

import { useEffect, useMemo, useState } from 'react';
import type { ThemeStore, Theme, ListedTheme } from 'aethercode-themes';
import './ThemeSettings.css';

export interface ThemeSettingsProps {
  /** The aethercode-themes store. The host owns the lifecycle
   *  (created once at app start). */
  store: ThemeStore;
  /** Currently active theme name. */
  activeName: string;
  /** Called when the user clicks the close button. */
  onClose: () => void;
  /** Optional callback when the user applies a change. The host
   *  is the one that knows about persistence + RPC — the panel
   *  just bubbles the draft. */
  onApply?: (draft: ThemeSettingsDraft) => void;
  /** Optional background override ("light" / "dark" / "auto"). */
  backgroundOverride?: 'light' | 'dark' | 'auto';
}

export interface ThemeSettingsDraft {
  /** The new theme name. */
  theme: string;
  /** Font family (CSS font-family string). */
  fontFamily: string;
  /** Font size in pixels. */
  fontSize: number;
  /** Background preference. */
  background: 'light' | 'dark' | 'auto';
}

/** Preset font families shown in the dropdown. Free-text is also
 *  supported via the input field. */
const FONT_PRESETS = [
  'JetBrains Mono',
  'Fira Code',
  'Cascadia Code',
  'SF Mono',
  'Menlo',
  'Monaco',
  'Source Code Pro',
  'Consolas',
];

/** Font size presets for the dropdown. */
const SIZE_PRESETS = [10, 11, 12, 13, 14, 15, 16, 18, 20];

export function ThemeSettings({
  store,
  activeName,
  onClose,
  onApply,
  backgroundOverride = 'auto',
}: ThemeSettingsProps) {
  // Snapshot the catalog once. The store's `change` event will
  // re-render the panel if a user drops a new theme file in.
  const [themes, setThemes] = useState<ReadonlyArray<ListedTheme>>(() => store.list());
  useEffect(() => {
    return store.on('change', () => setThemes(store.list()));
  }, [store]);

  const active = useMemo<Theme | null>(() => {
    try { return store.get(activeName); } catch { return null; }
  }, [store, activeName, themes]);

  // Draft — initialized from the active theme + the supplied
  // background override. The user can tweak freely without
  // touching the live theme.
  const [draftTheme, setDraftTheme] = useState<string>(activeName);
  const [draftFont, setDraftFont] = useState<string>(active?.font.family ?? 'JetBrains Mono');
  const [draftSize, setDraftSize] = useState<number>(active?.font.size ?? 13);
  const [draftBg, setDraftBg] = useState<'light' | 'dark' | 'auto'>(backgroundOverride);
  const [hover, setHover] = useState<string | null>(null); // live-preview hover
  const [savedAt, setSavedAt] = useState<number | null>(null);

  // What we preview: the hovered theme if any, else the draft.
  const previewName = hover ?? draftTheme;
  const preview = useMemo<Theme | null>(() => {
    try { return store.get(previewName); } catch { return null; }
  }, [store, previewName, themes]);

  const apply = () => {
    const draft: ThemeSettingsDraft = {
      theme: draftTheme,
      fontFamily: draftFont,
      fontSize: draftSize,
      background: draftBg,
    };
    onApply?.(draft);
    setSavedAt(Date.now());
  };

  return (
    <div className="theme-settings-overlay" role="dialog" aria-label="Theme settings">
      <div className="theme-settings-panel">
        <div className="theme-settings-header">
          <h2>Theme &amp; Font</h2>
          <button className="theme-settings-close" onClick={onClose} aria-label="Close">×</button>
        </div>
        <div className="theme-settings-body">
          {/* ---- Theme picker --------------------------------- */}
          <div className="ts-field">
            <span>Theme</span>
            <ul className="ts-theme-list">
              {themes.map((t) => {
                const isDraft = t.name === draftTheme;
                const isHover = t.name === hover;
                return (
                  <li
                    key={t.name}
                    className={
                      'ts-theme-row' +
                      (isDraft ? ' ts-draft' : '') +
                      (isHover ? ' ts-hover' : '')
                    }
                    onMouseEnter={() => setHover(t.name)}
                    onMouseLeave={() => setHover(prev => prev === t.name ? null : prev)}
                    onClick={() => setDraftTheme(t.name)}
                  >
                    <span className="ts-theme-name">
                      {t.isDark ? '●' : '○'} {t.name}
                      {t.origin === 'user' ? <em> (user)</em> : null}
                    </span>
                    <span className="ts-theme-swatches" aria-hidden="true">
                      <Swatch color={t.colors.background} />
                      <Swatch color={t.colors.foreground} />
                      <Swatch color={t.colors.accent} />
                      <Swatch color={t.colors.muted} />
                      <Swatch color={t.colors.success} />
                      <Swatch color={t.colors.warning} />
                      <Swatch color={t.colors.error} />
                      <Swatch color={t.colors.border} />
                      <Swatch color={t.colors.selection} />
                    </span>
                  </li>
                );
              })}
            </ul>
          </div>

          {/* ---- Live preview ---------------------------------- */}
          {preview ? (
            <div
              className={'ts-preview ' + (preview.isDark ? 'ts-dark' : 'ts-light')}
              data-theme={preview.name}
            >
              <div className="ts-preview-bar">
                <span style={{ color: preview.colors.foreground, background: preview.colors.background }}>
                  {preview.name}
                </span>
                <span style={{ color: preview.colors.muted }}>preview</span>
              </div>
              <div
                className="ts-preview-body"
                style={{
                  background: preview.colors.background,
                  color: preview.colors.foreground,
                  borderColor: preview.colors.border,
                  fontFamily: draftFont,
                  fontSize: `${draftSize}px`,
                }}
              >
                <span style={{ color: preview.colors.accent }}>●</span>{' '}
                <span style={{ color: preview.colors.success }}>ok</span>{' '}
                <span style={{ color: preview.colors.warning }}>warn</span>{' '}
                <span style={{ color: preview.colors.error }}>err</span>{' '}
                <span style={{ color: preview.colors.muted }}>muted</span>
              </div>
            </div>
          ) : null}

          {/* ---- Font ------------------------------------------ */}
          <div className="ts-field">
            <span>Font family</span>
            <div className="ts-row">
              <input
                type="text"
                value={draftFont}
                onChange={(e) => setDraftFont(e.target.value)}
                placeholder="JetBrains Mono"
              />
              <select
                value={FONT_PRESETS.includes(draftFont) ? draftFont : ''}
                onChange={(e) => { if (e.target.value) setDraftFont(e.target.value); }}
              >
                <option value="">presets…</option>
                {FONT_PRESETS.map((f) => <option key={f} value={f}>{f}</option>)}
              </select>
            </div>
          </div>

          <div className="ts-field">
            <span>Font size</span>
            <div className="ts-row">
              <input
                type="number"
                min={8}
                max={32}
                value={draftSize}
                onChange={(e) => setDraftSize(Math.max(8, Math.min(32, Number(e.target.value) || 13)))}
              />
              <select
                value={SIZE_PRESETS.includes(draftSize) ? String(draftSize) : ''}
                onChange={(e) => { if (e.target.value) setDraftSize(Number(e.target.value)); }}
              >
                <option value="">presets…</option>
                {SIZE_PRESETS.map((s) => <option key={s} value={s}>{s}px</option>)}
              </select>
            </div>
          </div>

          {/* ---- Background ------------------------------------ */}
          <div className="ts-field">
            <span>Background</span>
            <div className="ts-segmented">
              {(['auto', 'light', 'dark'] as const).map((b) => (
                <button
                  key={b}
                  className={'ts-segment' + (draftBg === b ? ' ts-selected' : '')}
                  onClick={() => setDraftBg(b)}
                >
                  {b}
                </button>
              ))}
            </div>
          </div>

          {savedAt ? (
            <div className="ts-saved" role="status">✓ saved</div>
          ) : null}
        </div>
        <div className="theme-settings-footer">
          <button className="ts-cancel" onClick={onClose}>Cancel</button>
          <button className="ts-apply" onClick={apply}>Apply</button>
        </div>
      </div>
    </div>
  );
}

function Swatch({ color }: { color: string }) {
  return <span className="ts-swatch" style={{ background: color }} aria-hidden="true" />;
}
