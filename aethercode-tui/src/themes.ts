/**
 * theme palettes.
 * extended with kanagawa + opencode for OpenCode-style
 *       polish. The TUI now ships 5 palettes.
 *
 * The TUI picks one of N color palettes. Each palette is a
 * complete mapping from semantic role → color name. Components
 * import `useTheme()` (a hook that reads the current theme
 * from state) and render with the appropriate color.
 *
 * We support 5 palettes for now:
 *   - "default"   — Claude-Code-inspired warm amber + cyan
 *   - "solarized" — Solarized Dark (muted teal + sand)
 *   - "monokai"   — Monokai (vivid magenta + green)
 *   - "kanagawa"  — R166: kanagawa wave (warm autumn red +
 *                   indigo + spring green) — same colours
 *                   the popular VS Code theme uses, designed
 *                   for long sessions with low eye strain.
 *   - "opencode"  — R166: OpenCode's signature palette (deep
 *                   indigo primary + warm coral accent +
 *                   neutral greys). Matches the OpenCode
 *                   desktop / CLI look so users migrating
 *                   from OpenCode feel at home.
 *
 * The default is "default". Switch with /theme NAME.
 * Switching is instant (no rebuild).
 */

export type ThemeName = "default" | "solarized" | "monokai" | "kanagawa" | "opencode";

export interface Palette {
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
  catRead:   "blue",
  catWrite:  "yellowBright",
  catSearch: "cyan",
  catRun:    "red",
  catAgent:  "magenta",
  catOther:  "gray",
};

const SOLARIZED: Palette = {
  brand: "#b58900",          // Solarized yellow
  brandBold: "#cb4b16",      // orange
  accent: "#2aa198",         // cyan
  ok: "#859900",             // green
  warn: "#b58900",           // yellow
  err: "#dc322f",            // red
  dim: "#586e75",            // base01
  user: "#268bd2",           // blue
  asst: "#93a1a1",           // base1
  code: "#d33682",           // magenta
  panel: "#073642",          // base02
  panelHot: "#b58900",
  panelOk: "#859900",
  panelErr: "#dc322f",
  header: "#b58900",
  spinner: "#b58900",
  catRead:   "#268bd2",
  catWrite:  "#b58900",
  catSearch: "#2aa198",
  catRun:    "#dc322f",
  catAgent:  "#d33682",
  catOther:  "#586e75",
};

const MONOKAI: Palette = {
  brand: "#f8c339",          // vivid yellow
  brandBold: "#ffeb70",
  accent: "#66d9ef",         // cyan
  ok: "#a6e22e",             // green
  warn: "#e6db74",           // yellow
  err: "#f92672",            // pink
  dim: "#75715e",            // comment
  user: "#fd971f",           // orange
  asst: "#f8f8f2",           // fg
  code: "#ae81ff",           // purple
  panel: "#3e3d32",          // bg highlight
  panelHot: "#f8c339",
  panelOk: "#a6e22e",
  panelErr: "#f92672",
  header: "#f8c339",
  spinner: "#f8c339",
  catRead:   "#fd971f",
  catWrite:  "#f8c339",
  catSearch: "#66d9ef",
  catRun:    "#f92672",
  catAgent:  "#ae81ff",
  catOther:  "#75715e",
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
  brand:       "#c34043",  // autumn red (slightly more orange than red)
  brandBold:   "#e46876",  // dragon pink (bolder)
  accent:      "#7fb4ca",  // spring blue (calm, not loud)
  ok:          "#87a987",  // spring green
  warn:        "#dca561",  // autumn yellow
  err:         "#c34043",  // autumn red
  dim:         "#625E5A",  // boat stone (subdued)
  user:        "#7fb4ca",  // spring blue
  asst:        "#DCD7BA",  // fuji white (assistant prose — easy on eyes)
  code:        "#957FB8",  // ono purple
  panel:       "#36313C",  // sumi ink 5
  panelHot:    "#dca561",  // autumn yellow
  panelOk:     "#87a987",  // spring green
  panelErr:    "#c34043",  // autumn red
  header:      "#7fb4ca",  // spring blue header
  spinner:     "#dca561",  // autumn yellow spinner
  catRead:     "#7fb4ca",  // spring blue
  catWrite:    "#dca561",  // autumn yellow
  catSearch:   "#87a987",  // spring green
  catRun:      "#c34043",  // autumn red
  catAgent:    "#957FB8",  // ono purple
  catOther:    "#625E5A",  // boat stone
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
  brand:       "#6366f1",  // indigo-500 (OpenCode primary)
  brandBold:   "#818cf8",  // indigo-400
  accent:      "#f472b6",  // pink-400 (warm coral accent)
  ok:          "#10b981",  // emerald-500
  warn:        "#f59e0b",  // amber-500
  err:         "#ef4444",  // red-500
  dim:         "#6b7280",  // gray-500
  user:        "#60a5fa",  // blue-400
  asst:        "#e5e7eb",  // gray-200
  code:        "#a78bfa",  // violet-400
  panel:       "#374151",  // gray-700
  panelHot:    "#f59e0b",  // amber-500
  panelOk:     "#10b981",  // emerald-500
  panelErr:    "#ef4444",  // red-500
  header:      "#6366f1",  // indigo-500
  spinner:     "#818cf8",  // indigo-400
  catRead:     "#60a5fa",  // blue-400
  catWrite:    "#f59e0b",  // amber-500
  catSearch:   "#10b981",  // emerald-500
  catRun:      "#ef4444",  // red-500
  catAgent:    "#a78bfa",  // violet-400
  catOther:    "#6b7280",  // gray-500
};

export const PALETTES: Record<ThemeName, Palette> = {
  default: DEFAULT,
  solarized: SOLARIZED,
  monokai: MONOKAI,
  kanagawa: KANAGAWA,
  opencode: OPENCODE,
};

/** Pick a palette by name. Falls back to default if the name
 *  is not recognized (e.g. user typo or future removal). */
export function pickPalette(name: string | null | undefined): Palette {
  const n = (name ?? "default") as ThemeName;
  return PALETTES[n] ?? DEFAULT;
}

/** Available theme names. */
export const THEME_NAMES: ThemeName[] = ["default", "solarized", "monokai", "kanagawa", "opencode"];
