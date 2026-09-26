/**
 * theme + icons + tokens for the AetherCode TUI.
 *
 * The theme borrows the Claude Code aesthetic: a warm amber
 * primary color, cyan accents, and dim greys for secondary
 * content. We keep it small and import-friendly so components
 * don't reach into chalk directly.
 */

export const t = {
  // Foreground colors (chalk-compatible names; Ink understands them).
  brand: "yellowBright",       // AetherCode / brand text
  brandBold: "#FFB347",        // bold brand
  accent: "cyan",              // model name, key labels
  ok: "green",                 // success
  warn: "yellow",              // warning
  err: "red",                  // error
  dim: "gray",                 // de-emphasised text
  user: "blue",                // user prompts
  asst: "white",               // assistant prose
  code: "magenta",             // inline code
  // code backgrounds (used by inline code pill + code block surface).
  // We use the same color as the foreground for "filled" + the dim grey
  // for a contrasting outline. Terminal bg support is uneven, so the
  // inline code "pill" uses `backgroundColor` with the same hue as
  // the foreground and falls back to a bold + dim treatment where bg
  // isn't supported.
  codeBg: "#3F1A47",           // dark magenta bg for inline code (subtle)
  codeBlockBg: "#1F1326",      // very dark magenta bg for code blocks
  codeBorder: "#5A2A6A",       // dim purple border around code blocks
  panel: "gray",               // borders
  panelHot: "yellowBright",    // running tool border
  panelOk: "green",            // ok tool border
  panelErr: "red",             // error tool border
  header: "yellowBright",      // header bar
  spinner: "yellowBright",     // spinner color
  // heading colors. H1/H2 carry the brand weight; H3/H4 are
  // accent-tinted so the user can scan document structure by colour
  // alone. H5/H6 fade to dim — they're "sub-section" markers.
  heading1: "yellowBright",
  heading2: "cyan",
  heading3: "magenta",
  heading4: "white",
  heading5: "gray",
  heading6: "gray",
  // rule + table accent. HRs and table separators use the
  // brand color so they read as "structural" rather than "dim".
  rule: "yellowBright",
  tableHeader: "yellowBright",
  tableSep: "gray",
  // blockquote accent + content. The left bar is a strong
  // brand color so the user can identify quoted text at a glance.
  quoteBar: "cyan",
  quoteText: "white",
  // code-block syntax tokens. Each kind gets its own
  // colour so a user reading ` ```bash ``` / ` ```ts ``` `
  // output can see the structural difference between a
  // keyword, a string literal, a number, a comment, and a
  // builtin/operator. The palette is tuned to read against
  // the dark-magenta `codeBlockBg` surface; nothing is bold
  // (the inline-code pill is the bold thing — the code body
  // is monospace + colour-coded).
  tokKeyword:  "magenta",      // if const class function (control flow)
  tokString:   "green",        // "hello" 'world' `template`
  tokNumber:   "yellowBright", // 123 0xFF 3.14
  tokComment:  "gray",         // // # /* */ — italic in render
  tokBuiltin:  "cyan",         // true false null console Math
  tokOperator: "white",        // = + - * / => ===  (low-emphasis)
  // tool-category colors. The user can identify the *type* of
  // a tool call by its color even without reading the name.
  catRead:   "blue",            // file_read / glob / grep
  catWrite:  "yellowBright",    // file_write / file_edit
  catSearch: "cyan",            // search / find
  catRun:    "red",             // bash / shell / exec / test
  catAgent:  "magenta",         // agent / task / delegate
  catOther:  "gray",            // unknown
} as const;

export const icon = {
  ok: "✓",
  err: "✗",
  running: "◐",
  pending: "·",
  arrow: "▸",
  user: "›",
  tool: "⚒",
  plan: "✱",
  note: "·",
  warn: "⚠",
  dot: "●",
  ring: "○",
  // pill icons — one per chip in the header.
  model: "⌬",     // model name
  mode:  "⚙",     // permission mode
  live:  "●",     // connected / live
  dead:  "○",     // disconnected
  id:    "#",     // session id
  path:  "↳",     // working directory
  time:  "⏱",     // elapsed time
  cost:  "$",     // cost
  in:    "↑",     // input tokens
  out:   "↓",     // output tokens
  think: "✦",     // thinking
  stream: "▍",    // streaming
  toolRun: "▸",   // tool call running
  ask: "?",       // user ask
  pause: "‖",     // paused
  // R344 additions: provider pill + memory-bank status pill.
  provider: "⌃",  // provider name (e.g. minmax, openai)
  bank:     "◐",  // memory bank status (live count + kinds)
  // tool-category icons — one per ToolCategory.
  catRead:   "○",  // read (file_read, glob, grep)
  catWrite:  "✎",  // write/edit (file_write, file_edit)
  catSearch: "◎",  // search (find, search)
  catRun:    "▷",  // run (bash, shell, exec, test)
  catAgent:  "❖",  // agent / task / subagent
  catOther:  "⚒",  // generic tool
  // misc UI.
  detail: "≡",     // details / expanded view
  timer: "⏱",     // duration timer
  // search.
  search: "⌕",     // search / find
} as const;

export const banner = String.raw`
    _   _   ___   _____   ___    ___   _   _   _____   __  __  ___   ____
   /_\ | | | __| |   \ \ / /_\  |   \  | \ | | |   \ |  \/  || __| |__  |
  / _ \| |_| _|  | |) \ V / _ \ | |) | | .\| | | |) || |\/| || _|    / /
 /_/ \_\\___|___| |___/ \_/_/ \_\|___/  |_|\_| | |___||_|  |_||___|  /_/
                                                |_|                `;

export const wordmark = "AetherCode";

export function formatTokens(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return "—";
  if (n < 1000) return String(Math.round(n));
  if (n < 1_000_000) return `${(n / 1000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(2)}M`;
}

export function formatCost(usd: number | null | undefined): string {
  if (usd == null || !Number.isFinite(usd)) return "—";
  if (usd < 0.01) return `<$0.01`;
  return `$${usd.toFixed(2)}`;
}

export function formatRelative(ts: number, now: number = Date.now()): string {
  const diff = Math.max(0, now - ts);
  if (diff < 1000) return "now";
  if (diff < 60_000) return `${Math.floor(diff / 1000)}s`;
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  return `${Math.floor(diff / 3_600_000)}h`;
}
