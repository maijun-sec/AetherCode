// R713 (2026-10-18): centralized defense against runaway
// LLM content. Every piece of text that an LLM can produce
// (think blocks, tool output, tool input, user message
// content, system notes) is untrusted input from a React
// perspective. Passing it through the renderer unfiltered
// has caused two known failure modes:
//
//   1. O(n^2) markdown parse / render — react-markdown's
//      remark-gfm autolink-literal pass is famously O(n^2)
//      on long input. A 286 KB tool_result, or even a 50 KB
//      think block, can pin the WebView2 renderer thread to
//      100% CPU and freeze the desktop UI (R712 root cause;
//      the WebView2 renderer accumulated 22,676 CPU min
//      over 11 hours on one bad line).
//
//   2. O(n) DOM mounting / diff — even non-markdown strings
//      end up as React children. A 286 KB user-content block
//      renders as one giant text node, which the reconciler
//      re-diffs on every keystroke. Slow but not infinite;
//      still a UX cliff the user complains about.
//
// Both are fixed the same way: cap before render. The caps
// below are tuned to be invisible on normal sessions (a long
// find-listing tops out at ~10 KB) and protective on bad
// ones. Anything beyond the cap is replaced with a clear
// "truncated" banner that points the user at the raw
// transcript if they need the full text.
//
// The daemon itself doesn't cap tool output (R711 is a
// future round, requires touching the Plumb boundary the
// user said to leave alone). This file is the *defensive*
// layer that keeps the desktop alive regardless of what the
// daemon ships.

/**
 * Hard upper bound for any string that is going to be
 * rendered as a ReactMarkdown body. 50 KB is ~12x the normal
 * readable preview and enough to display almost any real
 * tool output, think block, or user message. Beyond 50 KB
 * the markdown parser pays the O(n^2) cost and the user
 * wouldn't read the rest anyway.
 */
export const HARD_MAX_BODY_CHARS = 50_000;

/**
 * Inline preview cap (tool call body, pre-formatted snippet).
 * 4 KB matches the historical TOOL_OUTPUT_PREVIEW. Used for
 * the second cap after HARD_MAX so a normal-length body
 * still gets a clean "...(truncated)" marker.
 */
export const HARD_MAX_INLINE_CHARS = 4_000;

/**
 * Title / summary attribute cap. Tool summary rows, system
 * hint hover-tooltips, and similar places where the user
 * only ever skims. 200 chars keeps the tooltip readable.
 */
export const HARD_MAX_TITLE_CHARS = 200;

/**
 * Tool *input* cap. file_edit's `old_string` + `new_string`
 * can legitimately be a few KB but a self-referencing
 * transcript echo can be 286 KB. 32 KB is generous — Claude's
 * longest single file_edit in practice is ~5 KB.
 */
export const HARD_MAX_INPUT_CHARS = 32_000;

/**
 * Truncation marker used by all cap functions. The phrase
 * `[...R713: truncated from N to N chars ...]` is searchable
 * in the raw transcript so the user can grep for it if they
 * want to know which block got capped.
 */
const TRUNCATION_NOTE =
  (from: number, to: number, label?: string) =>
    `\n\n[...R713: ${label ? label + ' ' : ''}truncated from ${from} to ${to} chars to keep the chat responsive. The full content is in the raw transcript (.aethercode/sessions/<sessionId>.jsonl) if you need it.]`;

/**
 * Cap a string destined for a markdown body (think block,
 * user message, system note, agent summary). Anything over
 * HARD_MAX_BODY_CHARS gets a clear truncation banner.
 */
export function capForBody(s: string, label?: string): string {
  if (!s) return '';
  if (s.length <= HARD_MAX_BODY_CHARS) return s;
  return s.slice(0, HARD_MAX_BODY_CHARS)
    + TRUNCATION_NOTE(s.length, HARD_MAX_BODY_CHARS, label);
}

/**
 * Cap a string destined for an inline preview (tool output
 * body, pre-formatted snippet). The HARD_MAX is the safety
 * net for runaway transcripts; the smaller `max` is the
 * readable preview (4 KB by default).
 */
export function capForInline(s: string, max: number = HARD_MAX_INLINE_CHARS, label?: string): string {
  if (!s) return '';
  if (s.length > HARD_MAX_BODY_CHARS) {
    return s.slice(0, HARD_MAX_BODY_CHARS)
      + TRUNCATION_NOTE(s.length, HARD_MAX_BODY_CHARS, label);
  }
  return s.length > max ? s.slice(0, max) + '\n...(truncated)' : s;
}

/**
 * Cap a string for a title or summary attribute. Tool
 * summary rows, system hint tooltips, etc. These appear in
 * hover tooltips and DOM attribute strings, both of which
 * tax the WebView2 renderer per-character.
 */
export function capForTitle(s: string, max: number = HARD_MAX_TITLE_CHARS): string {
  if (!s) return '';
  return s.length > max ? s.slice(0, max - 1) + '…' : s;
}

/**
 * Cap a tool *input* (file_edit's old_string + new_string,
 * the model's command string, etc.). These are user-readable
 * but can be hijacked by a self-referencing tool loop
 * (model reads its own transcript, passes the content as
 * the next tool's input, the input becomes a transcript
 * fragment, etc.).
 */
export function capForInput(s: string, max: number = HARD_MAX_INPUT_CHARS, label?: string): string {
  if (!s) return '';
  if (s.length <= max) return s;
  return s.slice(0, max) + TRUNCATION_NOTE(s.length, max, label);
}

/**
 * Sanitize a string for safe inclusion in a DOM `title`
 * attribute. We don't HTML-encode here (React does that for
 * `title={...}`), but we do collapse newlines and trim
 * trailing whitespace so a multi-line LLM string doesn't
 * render as an unreadable tooltip blob.
 */
export function titleAttr(s: string, max: number = HARD_MAX_TITLE_CHARS): string {
  if (!s) return '';
  return capForTitle(s.replace(/\s+/g, ' ').trim(), max);
}
