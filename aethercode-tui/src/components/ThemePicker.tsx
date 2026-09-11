/**
 * T-422 (Phase 5 Round 2): ThemePicker.
 *
 * Live theme switcher. Lists every theme the `aethercode-themes`
 * `ThemeStore` knows about (built-ins + user YAML overrides), with
 * a 9-swatch colour preview, origin tag, and the currently active
 * marker. The user navigates with ↑/↓, confirms with Enter, and
 * dismisses with Esc. The picker is a *modal* — when open it
 * floats above the rest of the TUI.
 *
 * design.md §5.1.4: "The `ThemePicker` component shows a live preview
 * swatch per theme." Live preview means: as the user moves the
 * highlight, the picker's parent calls `store.setActive(highlight)`
 * so the entire TUI re-renders in the candidate palette. The
 * picker's own swatches are unaffected (they always render the
 * candidate's actual colours).
 *
 * The component is prop-driven — it does not import
 * `aethercode-themes` directly. The host wires the props from a
 * `ThemeStore` instance (see `aethercodeThemesBridge.ts`). This
 * keeps the picker testable and the package boundary clean.
 */

import React, { useEffect, useMemo, useState } from "react";
import { Box, Text, useInput } from "ink";

/** A 9-color palette for swatch rendering. Mirrors `aethercode-themes`'s
 *  `Theme.colors` shape; redefined here so the picker doesn't need
 *  to import the upstream module. */
export interface ThemeSwatches {
  background: string;
  foreground: string;
  accent: string;
  muted: string;
  success: string;
  warning: string;
  error: string;
  border: string;
  selection: string;
}

/** A single row in the picker. */
export interface PickerTheme {
  /** Stable identifier — passed to `onSelect`. */
  name: string;
  /** Whether the theme is a dark one. Drives a small "●/○" indicator. */
  isDark: boolean;
  /** Where the theme came from — used to label the row. */
  origin: "builtin" | "user";
  /** 9-color palette. */
  colors: ThemeSwatches;
  /** Optional font family, rendered as a tiny caption. */
  fontFamily?: string;
}

export interface ThemePickerProps {
  /** All themes to show. The store's `list()` is the typical source. */
  themes: ReadonlyArray<PickerTheme>;
  /** The currently active theme name. Marked with ●. */
  current: string;
  /** Called when the user confirms a pick. */
  onSelect: (name: string) => void;
  /** Called when the user dismisses the picker (Esc). */
  onClose: () => void;
  /**
   * Optional live-preview hook. Called with the highlighted theme
   * name whenever the highlight changes, so the host can call
   * `store.setActive(name)` and the rest of the TUI re-renders in
   * the candidate palette. If omitted, the picker is non-preview
   * and only fires `onSelect` on Enter.
   */
  onPreview?: (name: string) => void;
  /** Override the modal title. */
  title?: string;
  /** Cap the visible rows. Default 9 — fits in a 24-row terminal. */
  maxVisible?: number;
}

export const ThemePicker: React.FC<ThemePickerProps> = ({
  themes,
  current,
  onSelect,
  onClose,
  onPreview,
  title = "Theme",
  maxVisible = 9,
}) => {
  // Highlight index — starts on the active theme if present, else 0.
  const initial = useMemo(() => {
    const idx = themes.findIndex((t) => t.name === current);
    return idx >= 0 ? idx : 0;
  }, [themes, current]);
  const [highlight, setHighlight] = useState(initial);

  // Clamp highlight when the catalog shrinks (e.g. user deleted a
  // theme file while the picker is open).
  useEffect(() => {
    if (highlight >= themes.length) setHighlight(Math.max(0, themes.length - 1));
  }, [themes.length, highlight]);

  // Fire preview whenever the highlight moves, but skip the first
  // render so opening the picker doesn't immediately switch themes.
  const [hasMoved, setHasMoved] = useState(false);
  useEffect(() => {
    if (!hasMoved) return;
    const t = themes[highlight];
    if (t && onPreview) onPreview(t.name);
  }, [highlight, hasMoved, themes, onPreview]);

  useInput((input, key) => {
    if (key.escape) {
      onClose();
      return;
    }
    if (key.return) {
      const t = themes[highlight];
      if (t) onSelect(t.name);
      onClose();
      return;
    }
    if (key.upArrow) {
      setHasMoved(true);
      setHighlight((h) => (h - 1 + themes.length) % themes.length);
      return;
    }
    if (key.downArrow) {
      setHasMoved(true);
      setHighlight((h) => (h + 1) % themes.length);
      return;
    }
    if (input === "j") {
      setHasMoved(true);
      setHighlight((h) => (h + 1) % themes.length);
      return;
    }
    if (input === "k") {
      setHasMoved(true);
      setHighlight((h) => (h - 1 + themes.length) % themes.length);
      return;
    }
  });

  // Scroll window — keep the highlighted row visible.
  const start = Math.max(0, Math.min(highlight - Math.floor(maxVisible / 2), themes.length - maxVisible));
  const end = Math.min(themes.length, start + maxVisible);
  const visible = themes.slice(start, end);

  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor="cyan"
      paddingX={2}
      paddingY={1}
    >
      <Box>
        <Text color="cyan" bold>◆ {title} picker</Text>
        <Text dimColor>  ↑/↓ (j/k): navigate  Enter: select  Esc: close</Text>
      </Box>
      <Text> </Text>
      {themes.length === 0 ? (
        <Text dimColor>  no themes available</Text>
      ) : (
        visible.map((t, i) => {
          const realIndex = start + i;
          const isHighlighted = realIndex === highlight;
          const isActive = t.name === current;
          return (
            <Box key={t.name} flexDirection="row">
              <Text color={isHighlighted ? "cyan" : undefined}>
                {isHighlighted ? "▶" : " "}{" "}
              </Text>
              <Text color={isActive ? "yellowBright" : undefined}>
                {isActive ? "●" : " "}{" "}
              </Text>
              <Text>
                <Text bold={isHighlighted}>{t.name.padEnd(18)}</Text>
                <Text dimColor> </Text>
                <Text dimColor>{t.origin === "user" ? "(user)  " : "(built-in)"}</Text>
              </Text>
              <Text> </Text>
              <Swatch colors={t.colors} />
              <Text> </Text>
              {t.isDark ? <Text color="gray">●</Text> : <Text color="gray">○</Text>}
            </Box>
          );
        })
      )}
      {themes.length > maxVisible ? (
        <Text dimColor>  {start + 1}–{end} of {themes.length}</Text>
      ) : null}
    </Box>
  );
};

/** 9 small color blocks side-by-side, in role order. */
const Swatch: React.FC<{ colors: ThemeSwatches }> = ({ colors }) => {
  const roles: Array<[string, string]> = [
    ["bg", colors.background],
    ["fg", colors.foreground],
    ["ac", colors.accent],
    ["mu", colors.muted],
    ["ok", colors.success],
    ["wn", colors.warning],
    ["er", colors.error],
    ["bd", colors.border],
    ["sl", colors.selection],
  ];
  return (
    <Box>
      {roles.map(([label, hex], i) => (
        // Render the role as a coloured block. ink accepts hex strings.
        <Text key={i} backgroundColor={normalizeHex(hex)} color={normalizeHex(hex)}>
          {"  "}
        </Text>
      ))}
    </Box>
  );
};

/** Some swatches are CSS names (e.g. "white"). ink only understands
 *  hex codes and a small set of named colours. We pass the raw
 *  value through — when ink doesn't understand it, the cell renders
 *  with the default fg/bg. Tests assert the raw passthrough. */
function normalizeHex(c: string): string {
  return c;
}

/** Pure helper: format a theme row label. Mirrors
 *  `AgentSelectorScreen.formatLabel` in deepagents-code so the
 *  two surfaces (TUI + desktop) print the same suffixes.
 *  Exposed for tests and any future call sites that need the
 *  same string. */
export function formatThemeLabel(
  name: string,
  active: string,
  isDark: boolean,
  origin: "builtin" | "user",
): string {
  const tag = isDark ? "dark" : "light";
  const originTag = origin === "user" ? "user" : "built-in";
  if (name === active) return `${name} (current, ${tag}, ${originTag})`;
  return `${name} (${tag}, ${originTag})`;
}

/** T-422 helper: filter the theme catalog by name. Pure
 *  function, exported for tests + reuse by the desktop picker. */
export function filterThemes(
  themes: ReadonlyArray<PickerTheme>,
  query: string,
): ReadonlyArray<PickerTheme> {
  const q = query.trim().toLowerCase();
  if (!q) return themes;
  return themes.filter(
    (t) =>
      t.name.toLowerCase().includes(q) ||
      (t.fontFamily ?? "").toLowerCase().includes(q) ||
      t.origin.toLowerCase().includes(q),
  );
}
