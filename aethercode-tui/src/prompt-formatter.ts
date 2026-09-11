/**
 * format a `getSystemPrompt` response for the TUI sideNote.
 *
 * The RPC returns {text, totalChars, sectionCount, sections: [{name,
 * length, source, firstLine}, ...]}. We render a small table with the
 * section name, source label, byte count, and a truncated first line
 * so the user can sanity-check their rules / identity / workflow
 * without scrolling through tens of KB of prompt.
 *
 * The first-line truncation keeps the table readable when a section
 * starts with a long sentence (e.g. a 200-char intro from a project
 * rules file). 80 chars is the sweet spot — long enough to identify
 * the section, short enough to fit on a TUI row.
 *
 * Extracted to its own module (rather than inlined in tui.tsx) so it
 * can be unit-tested without spinning up the renderer, and so the
 * rendering contract is documented in one place.
 */

export interface SystemPromptSection {
  name?: string;
  length?: number;
  source?: string;
  firstLine?: string;
  paths?: string[];
}

/**
 * shape of a single-section response from
 * {@code getSystemPromptSection}. The field {@code ok} is
 * always present and is the only field the renderer needs to
 * inspect to decide between success and error. On success the
 * remaining fields are: name, source, length, text. On
 * failure: error (string), available (string[] of section
 * names that *do* exist — so the renderer can show "did you
 * mean…?" without a second RPC).
 */
export interface SystemPromptSectionResponse {
  ok?: boolean;
  name?: string;
  source?: string;
  length?: number;
  text?: string;
  error?: string;
  available?: string[];
}

/** separator we use between the section header and the
 *  section body. Three blank lines gives the user a clear visual
 *  break from the previous turn without wasting too much
 *  scrollback. */
const SECTION_HEADER_SEP = "\n\n\n";

export interface SystemPromptResponse {
  text?: string;
  totalChars?: number;
  sectionCount?: number;
  sections?: SystemPromptSection[];
}

export const FIRST_LINE_MAX = 80;
export const FIRST_LINE_KEEP = 77; // 80 - 3 (ellipsis "...")
export const FIRST_LINE_ELLIPSIS = "...";

// ---------------------------------------------------------------------------
// ANSI color codes for the source label.
//
//  We deliberately use a small set of distinct hues so the
//  user can scan the table at a glance and tell the three
//  main source classes apart:
//    * default  — built-in identity / workflow (the engine's
//                 own prompt). Green = "trust this, it's the
//                 model-issued baseline".
//    * rules:*  — user-supplied rules (prior round). Blue = "this
//                 came from a .aethercode/rules/ file, you can
//                 edit it".
//    * builder  — caller-overridden identity / workflow (prior round).
//                 Yellow = "look closer, the caller customised
//                 the default".
//    * planMode / memory / unknown — neutral grey.
//
//  Each color is wrapped in a {@link COLOR_RESET} so the
//  colouring does not bleed into subsequent columns.
// ---------------------------------------------------------------------------

export const COLOR_DEFAULT  = "\u001b[32m"; // green
export const COLOR_RULES    = "\u001b[34m"; // blue
export const COLOR_BUILDER  = "\u001b[33m"; // yellow
export const COLOR_NEUTRAL  = "\u001b[90m"; // bright black / dark grey
export const COLOR_RESET    = "\u001b[0m";

/**
 * pick a color for a source label. New sources
 * default to {@link COLOR_NEUTRAL} so the color contract
 * stays stable when a future R-round adds a new label.
 */
export function colorForSource(source: string | null | undefined): string {
  const s = (source ?? "").toLowerCase();
  if (s.startsWith("rules"))    return COLOR_RULES;
  if (s === "builder")          return COLOR_BUILDER;
  if (s === "default")          return COLOR_DEFAULT;
  return COLOR_NEUTRAL;
}

/** cap a paths-list display. Long lists (e.g. 8+
 *  rule files) are summarised as "first N" + " (+M more)"
 *  so the table row stays scannable. */
export const PATHS_DISPLAY_MAX = 3;

/** render a list of absolute file paths as a
 *  short, human-friendly string suitable for the
 *  "paths" column in the /prompt table. Each path is
 *  shortened to its basename (the user can guess the
 *  rest), and the result is wrapped in a leading "["
 *  / trailing "]" so the column is easy to scan. */
export function formatPaths(paths: string[] | null | undefined): string {
  if (!Array.isArray(paths) || paths.length === 0) return "";
  const basenames = paths.map(p => {
    // Normalize: support both POSIX and Windows separators
    // (the loader may have produced backslashes on
    // Windows). Split on either, take the last segment.
    const idx = Math.max(p.lastIndexOf("/"), p.lastIndexOf("\\"));
    return idx >= 0 ? p.slice(idx + 1) : p;
  });
  if (basenames.length <= PATHS_DISPLAY_MAX) {
    return "[" + basenames.join(", ") + "]";
  }
  const shown = basenames.slice(0, PATHS_DISPLAY_MAX).join(", ");
  const more = basenames.length - PATHS_DISPLAY_MAX;
  return "[" + shown + ", +" + more + " more]";
}

export function formatSystemPrompt(result: SystemPromptResponse | null | undefined): string {
  if (!result || typeof result !== "object") {
    return "system prompt: (no response)";
  }
  const sections = Array.isArray(result.sections) ? result.sections : [];
  const totalChars = typeof result.totalChars === "number"
    ? result.totalChars
    : (typeof result.text === "string" ? result.text.length : 0);
  const sectionCount = typeof result.sectionCount === "number"
    ? result.sectionCount
    : sections.length;

  // the rules section now carries a `paths`
  // field. We pre-compute the rendered string per
  // section so the column width can be set from the
  // widest row. Sections without a paths field
  // contribute the empty string (so the column is
  // blank for identity / workflow / etc.).
  const pathCells = sections.map((s) => formatPaths(s.paths));
  const hasAnyPaths = pathCells.some((p) => p.length > 0);

  const lines: string[] = [];
  lines.push(`system prompt (${sectionCount} sections, ${totalChars} chars)`);
  if (sections.length === 0) {
    lines.push("  (no sections — identity / workflow may be empty)");
    return lines.join("\n");
  }

  const nameWidth = Math.max(
    "section".length,
    ...sections.map((s) => (s.name ?? "?").length)
  );
  // Source column width is computed from the *plain*
  // source label — the ANSI color codes are zero-width
  // for length math but padEnd() doesn't know that, so
  // we strip them before measuring.
  const plainSource = (s: string | null | undefined) => (s ?? "?").replace(/\u001b\[[0-9;]*m/g, "");
  const sourceWidth = Math.max(
    "source".length,
    ...sections.map((s) => plainSource(s.source).length)
  );
  const pathWidth = hasAnyPaths
    ? Math.max("paths".length, ...pathCells.map((p) => p.length))
    : 0;
  let header = `  ${"section".padEnd(nameWidth)}  ${"source".padEnd(sourceWidth)}  length`;
  if (hasAnyPaths) header += `  ${"paths".padEnd(pathWidth)}`;
  header += "  first line";
  lines.push(header);
  for (let i = 0; i < sections.length; i++) {
    const s = sections[i];
    const name = s.name ?? "?";
    const source = s.source ?? "?";
    const length = typeof s.length === "number" ? s.length : 0;
    const firstLine = (s.firstLine ?? "").replace(/\s+/g, " ").trim();
    const truncated = firstLine.length > FIRST_LINE_MAX
      ? firstLine.slice(0, FIRST_LINE_KEEP) + FIRST_LINE_ELLIPSIS
      : firstLine;
    // source is color-coded. The colored string
    // is laid out so the visible characters line up
    // with the header's sourceWidth. padEnd() pads
    // the *display* width but counts the invisible
    // ANSI bytes too — to keep the alignment right
    // we pad the plain text, then splice the color
    // codes in around it.
    const plain = plainSource(source).padEnd(sourceWidth, " ");
    const color = colorForSource(source);
    const coloredSource = color + plain + COLOR_RESET;
    let row = `  ${name.padEnd(nameWidth)}  ${coloredSource}  ${String(length).padStart(6)}`;
    if (hasAnyPaths) {
      const cell = pathCells[i].padEnd(pathWidth, " ");
      row += `  ${cell}`;
    }
    row += `  ${truncated}`;
    lines.push(row);
  }
  return lines.join("\n");
}

/**
 * format a single-section response from
 * {@code getSystemPromptSection}. On success this returns a
 * one-line header (name, source label, length) followed by the
 * FULL section text — no truncation. The body is preserved
 * verbatim including indentation and blank lines, so the user
 * can sanity-check rules files, identity overrides, and the
 * long workflow prompt body.
 *
 * On failure (ok=false) the formatter returns a short
 * "section not found" message plus the {@code available} list
 * (the names of the sections the prompt *does* contain), so
 * the user can immediately retry with one of them.
 *
 * If the response is null/non-object or missing the
 * {@code ok} field, the formatter returns a graceful
 * "no response" placeholder — same contract as
 * {@link formatSystemPrompt}.
 */
export function formatSystemPromptSection(
  result: SystemPromptSectionResponse | null | undefined
): string {
  if (!result || typeof result !== "object") {
    return "system prompt section: (no response)";
  }
  if (result.ok !== true) {
    const name = result.name ? ` (${result.name})` : "";
    const err = result.error || "unknown error";
    const lines: string[] = [];
    lines.push(`system prompt section${name}: ${err}`);
    if (Array.isArray(result.available) && result.available.length > 0) {
      lines.push("  available: " + result.available.join(", "));
    }
    return lines.join("\n");
  }
  const name = result.name ?? "?";
  const source = result.source ?? "?";
  const length = typeof result.length === "number" ? result.length : 0;
  const text = typeof result.text === "string" ? result.text : "";
  // Header line — same column widths as formatSystemPrompt so
  // the two views visually align.
  const header = `system prompt section: ${name} (source=${source}, length=${length})`;
  if (text.length === 0) {
    return header + SECTION_HEADER_SEP + "(empty section)";
  }
  return header + SECTION_HEADER_SEP + text;
}
