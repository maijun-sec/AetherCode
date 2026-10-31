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
 * theme palettes.
 *
 * ## This file is the ONE owner of the TUI's theme vocabulary
 *
 * R386 found the theme names written in FOUR files, and the copies
 * had already disagreed:
 *
 *   - `src/themes.ts` shipped FIVE palettes (default / solarized /
 *     monokai / kanagawa / opencode);
 *   - `src/commands.ts` accepted only THREE of them — `/theme
 *     kanagawa` and `/theme opencode` were REJECTED with "unknown
 *     theme", and the error message listed three as if that were all
 *     the product had;
 *   - `src/state.ts` typed `themeName` as a three-value union, so the
 *     type system did not know the other two existed;
 *   - `src/tui.tsx` cast the name to the same three-value union.
 *
 * `kanagawa` and `opencode` were added in R166 "for OpenCode-style
 * polish" — two shipped palettes that NOBODY COULD SELECT.
 *
 * The names now live here and nowhere else. `resolveThemeName` is the
 * only way to turn what the user typed into a palette, and it is
 * case-insensitive (`/theme Kanagawa` works).
 *
 * ## The palette REACHES the screen
 *
 * R386's other finding was worse than a stale list: `/theme NAME`
 * printed "theme → solarized" and **nothing changed**. The TUI had
 * three theme systems that never met:
 *
 *   1. `src/theme.ts` exported a STATIC token table `t`. Thirty
 *      components import it and render with it. It never changes.
 *   2. `src/themes.ts` had five palettes and a `pickPalette()`.
 *   3. `src/ThemeContext.tsx` published a palette through a React
 *      context — and `useTheme()` had **ZERO call sites**, so the
 *      computed palette was discarded.
 *
 * So the palette is now the ACTIVE one: `t`'s palette roles are
 * getters over `getActivePalette()`, and `ThemeProvider` publishes
 * the active palette during render. Every component that reads `t`
 * repaints — with no change to any component.
 *
 * ## What a palette covers
 *
 * The 22 `Palette` roles are the UI chrome: brand, accent, the
 * ok/warn/err trio, the panel and header borders, the spinner, and
 * the six tool-category colours. They are what a theme changes.
 *
 * The markdown syntax colours (`tokKeyword`, `heading1`, …) and the
 * ICONS are deliberately NOT in the palette: they are a separate
 * layer, the way a code editor separates its workbench theme from
 * its token colours. A palette that also rewrote the syntax colours
 * would need 21 more hand-picked values per theme, and nobody
 * chooses those by hand.
 */

/** The palettes the TUI ships. */
export type ThemeName = "default" | "solarized" | "monokai" | "kanagawa" | "opencode";

export interface Palette {
  /**
   * The theme's own surface colour.
   *
   * The TUI cannot paint the terminal's background, so this is the
   * theme's INTENDED surface — it shows up in the picker's swatch and
   * is what a host that CAN set a background (the desktop) uses.
   */
  background: string;
  brand: string;
  brandBold: string;
  accent: string;
  ok: string;
  warn: string;
  err: string;
  dim: string;
  user: string;
  asst: string;
  code: string;
  panel: string;
  panelHot: string;
  panelOk: string;
  panelErr: string;
  header: string;
  spinner: string;
  catRead: string;
  catWrite: string;
  catSearch: string;
  catRun: string;
  catAgent: string;
  catOther: string;
}

const DEFAULT: Palette = {
  background: "#1F1326",
  brand: "yellowBright",
  brandBold: "#FFB347",
  accent: "cyan",
  ok: "green",
  warn: "yellow",
  err: "red",
  dim: "gray",
  user: "blue",
  asst: "white",
  code: "magenta",
  panel: "gray",
  panelHot: "yellowBright",
  panelOk: "green",
  panelErr: "red",
  header: "yellowBright",
  spinner: "yellowBright",
  catRead: "blue",
  catWrite: "yellowBright",
  catSearch: "cyan",
  catRun: "red",
  catAgent: "magenta",
  catOther: "gray",
};

const SOLARIZED: Palette = {
  background: "#002b36", // base03
  brand: "#b58900", // Solarized yellow
  brandBold: "#cb4b16", // orange
  accent: "#2aa198", // cyan
  ok: "#859900", // green
  warn: "#b58900", // yellow
  err: "#dc322f", // red
  dim: "#586e75", // base01
  user: "#268bd2", // blue
  asst: "#93a1a1", // base1
  code: "#d33682", // magenta
  panel: "#073642", // base02
  panelHot: "#b58900",
  panelOk: "#859900",
  panelErr: "#dc322f",
  header: "#b58900",
  spinner: "#b58900",
  catRead: "#268bd2",
  catWrite: "#b58900",
  catSearch: "#2aa198",
  catRun: "#dc322f",
  catAgent: "#d33682",
  catOther: "#586e75",
};

const MONOKAI: Palette = {
  background: "#272822",
  brand: "#f8c339", // vivid yellow
  brandBold: "#ffeb70",
  accent: "#66d9ef", // cyan
  ok: "#a6e22e", // green
  warn: "#e6db74", // yellow
  err: "#f92672", // pink
  dim: "#75715e", // comment
  user: "#fd971f", // orange
  asst: "#f8f8f2", // fg
  code: "#ae81ff", // purple
  panel: "#3e3d32", // bg highlight
  panelHot: "#f8c339",
  panelOk: "#a6e22e",
  panelErr: "#f92672",
  header: "#f8c339",
  spinner: "#f8c339",
  catRead: "#fd971f",
  catWrite: "#f8c339",
  catSearch: "#66d9ef",
  catRun: "#f92672",
  catAgent: "#ae81ff",
  catOther: "#75715e",
};

/**
 * kanagawa — a warm, low-contrast palette popularised
 * by the VS Code "Kanagawa Wave" theme. Autumn red and indigo
 * dominate, with spring green for success. Designed for long
 * coding sessions — the high contrast between brand/err makes
 * permission prompts pop, while the muted dim/spinner/panel
 * keeps the chat scrollback readable for hours.
 */
const KANAGAWA: Palette = {
  background: "#1f1f28", // kanagawa wave bg
  brand: "#c34043", // autumn red (slightly more orange than red)
  brandBold: "#e46876", // dragon pink (bolder)
  accent: "#7fb4ca", // spring blue (calm, not loud)
  ok: "#87a987", // spring green
  warn: "#dca561", // autumn yellow
  err: "#c34043", // autumn red
  dim: "#625E5A", // boat stone (subdued)
  user: "#7fb4ca", // spring blue
  asst: "#DCD7BA", // fuji white (assistant prose — easy on eyes)
  code: "#957FB8", // ono purple
  panel: "#36313C", // sumi ink 5
  panelHot: "#dca561", // autumn yellow
  panelOk: "#87a987", // spring green
  panelErr: "#c34043", // autumn red
  header: "#7fb4ca", // spring blue header
  spinner: "#dca561", // autumn yellow spinner
  catRead: "#7fb4ca", // spring blue
  catWrite: "#dca561", // autumn yellow
  catSearch: "#87a987", // spring green
  catRun: "#c34043", // autumn red
  catAgent: "#957FB8", // ono purple
  catOther: "#625E5A", // boat stone
};

/**
 * opencode — the palette the OpenCode TUI uses. Indigo
 * primary, coral accent, neutral greys. Lower visual weight
 * than "default" so the model output stays the centre of
 * attention; the brand colour only appears on header chips
 * and the active selection. Best for users who prefer less
 * colour noise.
 */
const OPENCODE: Palette = {
  background: "#111827", // gray-900
  brand: "#6366f1", // indigo-500 (OpenCode primary)
  brandBold: "#818cf8", // indigo-400
  accent: "#f472b6", // pink-400 (warm coral accent)
  ok: "#10b981", // emerald-500
  warn: "#f59e0b", // amber-500
  err: "#ef4444", // red-500
  dim: "#6b7280", // gray-500
  user: "#60a5fa", // blue-400
  asst: "#e5e7eb", // gray-200
  code: "#a78bfa", // violet-400
  panel: "#374151", // gray-700
  panelHot: "#f59e0b", // amber-500
  panelOk: "#10b981", // emerald-500
  panelErr: "#ef4444", // red-500
  header: "#6366f1", // indigo-500
  spinner: "#818cf8", // indigo-400
  catRead: "#60a5fa", // blue-400
  catWrite: "#f59e0b", // amber-500
  catSearch: "#10b981", // emerald-500
  catRun: "#ef4444", // red-500
  catAgent: "#a78bfa", // violet-400
  catOther: "#6b7280", // gray-500
};

export const PALETTES: Record<ThemeName, Palette> = {
  default: DEFAULT,
  solarized: SOLARIZED,
  monokai: MONOKAI,
  kanagawa: KANAGAWA,
  opencode: OPENCODE,
};

/**
 * The theme the TUI starts in.
 *
 * A named constant rather than a literal at every call site, because
 * "default" is ALSO an ordinary English word that appears in a dozen
 * unrelated places (a highlighter's fallback language, a prompt
 * formatter's colour name). Writing the literal at every call site
 * would make the vocabulary impossible to police: the R386 gate has
 * to be able to say "the theme names are written in exactly one
 * file", and that only works if the one ambiguous name is reached
 * through a symbol.
 */
export const DEFAULT_THEME_NAME: ThemeName = "default";

/** Available theme names — the ONE list. */
export const THEME_NAMES: ThemeName[] = ["default", "solarized", "monokai", "kanagawa", "opencode"];

/** One-line description of each theme, for the picker and `/help`. */
export const THEME_LABELS: Record<ThemeName, { label: string; blurb: string }> = {
  default: { label: "Default", blurb: "warm amber + cyan — the Claude Code look" },
  solarized: { label: "Solarized", blurb: "muted teal + sand" },
  monokai: { label: "Monokai", blurb: "vivid magenta + green" },
  kanagawa: { label: "Kanagawa", blurb: "autumn red + indigo, low contrast" },
  opencode: { label: "OpenCode", blurb: "indigo + coral, low visual weight" },
};

/**
 * Turn what the user typed into a theme name.
 *
 * Case-insensitive, because a theme name is a word, not an
 * identifier, and `/theme Kanagawa` is what a person types.
 *
 * @returns the canonical name, or undefined when the theme is unknown.
 */
export function resolveThemeName(name: string | null | undefined): ThemeName | undefined {
  if (!name) return undefined;
  const wanted = name.trim().toLowerCase();
  return THEME_NAMES.find((n) => n.toLowerCase() === wanted);
}

/** Pick a palette by name. Falls back to default if the name
 *  is not recognized (e.g. user typo or future removal). */
export function pickPalette(name: string | null | undefined): Palette {
  const resolved = resolveThemeName(name);
  return PALETTES[resolved ?? "default"];
}

/**
 * Is this palette a DARK one?
 *
 * DERIVED, not declared. The picker shows a "●/○" marker next to
 * every theme, and a hand-written `isDark` per palette is a second
 * copy of a fact the colours already carry — and a copy that can
 * disagree with them (a palette edited to a light background would
 * keep claiming to be dark).
 *
 * The background colour is the only surface the terminal actually
 * shows, so it is the right thing to measure. The maths is the
 * standard relative-luminance formula (ITU-R BT.601), and the
 * threshold is the conventional 0.5.
 *
 * A background that is neither a hex triple nor one of the eight
 * basic ANSI names cannot be measured, and this returns `true` for
 * it: every palette the TUI ships has a hex background, so the
 * fallback is unreachable today — and if a palette ever did use an
 * unmeasurable surface, "dark" is the conservative answer for a
 * terminal UI that paints light-on-dark text.
 */
export function paletteIsDark(palette: Palette): boolean {
  const bg = palette.background.trim().toLowerCase();
  if (bg.startsWith("#")) {
    const hex = bg.slice(1);
    const full =
      hex.length === 3
        ? hex
            .split("")
            .map((c) => c + c)
            .join("")
        : hex;
    if (full.length === 6) {
      const r = parseInt(full.slice(0, 2), 16) / 255;
      const g = parseInt(full.slice(2, 4), 16) / 255;
      const b = parseInt(full.slice(4, 6), 16) / 255;
      const lum = 0.299 * r + 0.587 * g + 0.114 * b;
      return lum < 0.5;
    }
    return true;
  }
  // The eight basic ANSI names. "brightWhite" and "white" are light;
  // everything else is dark.
  if (bg === "white" || bg === "brightwhite") return false;
  return true;
}

/**
 * The palette the TUI is currently rendering in.
 *
 * R386: this is what made the theme real. `t`'s palette roles are
 * getters over this value, and `ThemeProvider` publishes it during
 * render — so a theme change reaches every component without a
 * single component being changed.
 */
let activePalette: Palette = DEFAULT;

/** Publish the palette the UI should render in. */
export function setActivePalette(palette: Palette): void {
  activePalette = palette;
}

/** The palette the UI is currently rendering in. */
export function getActivePalette(): Palette {
  return activePalette;
}

/**
 * Publish the palette for the frame that is about to be built.
 *
 * R386: there is ONE publish site, and its POSITION is the whole
 * trick.
 *
 * The App component publishes at the TOP of its body. It builds the
 * JSX tree, and an expression like `color={t.warn}` in that tree
 * READS the token table while the tree is being built — i.e. BEFORE
 * any provider in the tree has had a chance to publish. If the
 * provider published instead, those elements would paint with the
 * PREVIOUS palette and only catch up on the next repaint: "one frame
 * late", which is the same user story as "never changes".
 *
 * The publish is idempotent (the same name publishes the same
 * palette), so a double render — StrictMode, a concurrent retry —
 * changes nothing.
 *
 * @returns the palette that was published.
 */
export function publishTheme(name: ThemeName): Palette {
  const palette = pickPalette(name);
  activePalette = palette;
  return palette;
}
