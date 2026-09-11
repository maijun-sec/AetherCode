/**
 * minimal markdown-to-Ink renderer.
 * additions —
 *   - GFM-style tables (`| col | col |` / `|---|---|` / `|---|`)
 *   - horizontal rules (`---`, `***`, `___` on their own line)
 *   - ~~strike~~ inline (uses chalk's `strikethrough` via Ink dim)
 *   - blockquote (`> ...`)
 *
 * R215 — "TUI markdown beautification"
 *   The user reported TUI markdown looked bare compared to the
 *   desktop renderer. R215 polishes the visual treatment without
 *   adding any new dependencies (no `react-markdown` / `marked` /
 *   etc. — we keep the lightweight state-machine parser). The
 *   changes are:
 *
 *   - **Headings** carry level-specific colour + decoration.
 *     H1 = brand + bold + `═══` underline (full-width).
 *     H2 = accent + bold + `───` underline.
 *     H3 = magenta + bold (no underline, but 1 blank line above).
 *     H4-H6 fade to dim/white + bold so the user can scan
 *     document structure by colour alone.
 *
 *   - **Inline code** now has a filled-pill background
 *     (`backgroundColor: t.codeBg`) matching the desktop's
 *     `rgba(99, 102, 241, 0.12)` background. Falls back to bold
 *     + magenta where the terminal doesn't render bg.
 *
 *   - **Code blocks** get a brand-tinted "language" tag above
 *     the surface (a small filled pill with ` ``` lang ` text),
 *     a dim purple border, and a dark-magenta bg so the block
 *     reads as a distinct surface, not "indented text".
 *
 *   - **Blockquote** has a heavy `┃` left bar (instead of `│`)
 *     in `t.quoteBar` (cyan) and a brand-coloured `> ` prefix
 *     so the user can pick it out instantly.
 *
 *   - **Horizontal rules** use `━` (heavy box-drawing) in
 *     `t.rule` (brand colour) so the divider reads as a
 *     *structure marker*, not a dim line.
 *
 *   - **Tables** get brand-coloured bold headers, a heavy `━`
 *     separator, and bold `│` column dividers. The header row
 *     has a `t.brand` foreground to flag it as the "label row".
 *
 *   - **Lists** get colour-coded bullet markers (`▸` in
 *     `t.brand`) and accent-coloured numbers. Hanging indent
 *     bumps to 4 characters for legibility.
 *
 *   - **Block spacing** is bumped: each top-level block is
 *     followed by a single blank line so the document has
 *     visible paragraph rhythm (the desktop renderer does the
 *     same via CSS `margin: 0.3em 0`).
 *
 * Renders a few common inline elements:
 *   - `code`         inline code (magenta pill)
 *   - **bold**       bold
 *   - *italic*       italic
 *   - ~~strike~~     strikethrough (dim)
 *   - __underline__  underline
 *   - # heading      level-coloured, decorated
 *   - lists          bullet / numbered, colour-coded
 *   - ```lang```     fenced code block (lang tag + dark surface)
 *   - | table |      grid with brand-coloured headers
 *   - ---            horizontal rule (heavy, brand)
 *   - > quote        blockquote (heavy bar, accent text)
 *
 * The renderer is intentionally lightweight — no AST, just
 * a small state machine over lines.
 */

import React from "react";
import { Text, Box } from "ink";
import { t } from "../theme.js";
import {
  highlightCode,
  highlightInlineCode,
  tokenKindToColor,
  type TokenKind,
} from "./markdown/highlight.js";

interface MdProps {
  text: string;
}

interface Block {
  kind: "para" | "code" | "heading" | "list" | "blank" | "rule" | "table" | "quote";
  body: string;
  items?: string[];
  level?: number;
  /** optional language tag for code blocks (e.g. "java", "xml"). */
  lang?: string;
  /** Table-specific: parsed rows (each row is a list of cells). */
  rows?: string[][];
  /** Table-specific: column alignment. */
  aligns?: ("left" | "right" | "center" | null)[];
  /** list kind — bullet or ordered. Defaults to "bullet". */
  listKind?: "bullet" | "ordered";
}

function parseBlocks(src: string): Block[] {
  const lines = src.split(/\r?\n/);
  const out: Block[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.trim().length === 0) { out.push({ kind: "blank", body: "" }); i++; continue; }
    const fence = line.match(/^```(\w*)\s*$/);
    if (fence) {
      const lang = fence[1] ?? "";
      const body: string[] = [];
      i++;
      while (i < lines.length && !/^```\s*$/.test(lines[i])) { body.push(lines[i]); i++; }
      // keep the language in the block metadata so the
      // renderer can show a tiny `lang` tag above the code.
      // `level` is reused as "has language" since the existing
      // field was already a non-negative integer.
      out.push({ kind: "code", body: body.join("\n"), level: lang ? 1 : 0, lang });
      i++; // skip closing fence
      continue;
    }
    const h = line.match(/^(#{1,6})\s+(.*)$/);
    if (h) { out.push({ kind: "heading", body: h[2], level: h[1].length }); i++; continue; }
    // horizontal rules — a line that's only -, *, or _ (3+ chars).
    if (/^([-*_])\1{2,}\s*$/.test(line.trim())) {
      out.push({ kind: "rule", body: "" });
      i++; continue;
    }
    // blockquote.
    const q = line.match(/^>\s+(.*)$/);
    if (q) {
      const body: string[] = [q[1]];
      i++;
      while (i < lines.length && /^>\s+/.test(lines[i])) {
        body.push(lines[i].replace(/^>\s+/, ""));
        i++;
      }
      out.push({ kind: "quote", body: body.join(" ") });
      continue;
    }
    // tables — header row, separator row, then data rows.
    // We look for a header line with at least 2 cells and a
    // separator line that matches /^\s*\|?(\s*:?-+:?\s*\|)+\s*:?-+:?\s*\|?\s*$/
    if (/\|/.test(line) && i + 1 < lines.length) {
      const sep = lines[i + 1];
      if (isTableSeparator(sep)) {
        const header = splitTableRow(line);
        const aligns = parseAligns(sep);
        const rows: string[][] = [header];
        i += 2;
        while (i < lines.length && /\|/.test(lines[i]) && lines[i].trim().length > 0) {
          rows.push(splitTableRow(lines[i]));
          i++;
        }
        out.push({ kind: "table", body: "", rows, aligns });
        continue;
      }
    }
    if (/^(\s*)[-*+]\s+/.test(line) || /^\s*\d+\.\s+/.test(line)) {
      // figure out whether the list is bullet or ordered.
      // We use the first item to decide (a mixed list is
      // rendered as "bullet" — markdown doesn't formally
      // support it but we don't want to crash on it).
      const isOrdered = /^\s*\d+\.\s+/.test(line);
      const items: string[] = [];
      while (i < lines.length && (/^(\s*)[-*+]\s+/.test(lines[i]) || /^\s*\d+\.\s+/.test(lines[i]))) {
        items.push(lines[i].replace(/^(\s*)(?:[-*+]|\d+\.)\s+/, ""));
        i++;
      }
      out.push({ kind: "list", body: "", items, listKind: isOrdered ? "ordered" : "bullet" });
      continue;
    }
    // Paragraph: consume until blank or block-start
    const para: string[] = [line];
    i++;
    while (i < lines.length && lines[i].trim().length > 0
           && !/^```/.test(lines[i]) && !/^#{1,6}\s+/.test(lines[i])
           && !/^([-*_])\1{2,}\s*$/.test(lines[i].trim())
           && !/^>\s+/.test(lines[i])
           && !/^(\s*)[-*+]\s+/.test(lines[i]) && !/^\s*\d+\.\s+/.test(lines[i])) {
      para.push(lines[i]);
      i++;
    }
    out.push({ kind: "para", body: para.join(" ") });
  }
  return out;
}

/** detect a markdown table separator line.
 *  Accepts: `|---|---|`, `| :--- | :---: | ---: |`, `:---:` (center). */
function isTableSeparator(line: string): boolean {
  return /^\s*\|?\s*(:?-+:?\s*\|\s*)*:?-+:?\s*\|?\s*$/.test(line);
}

/** split a table row by `|`, trimming each cell. */
function splitTableRow(line: string): string[] {
  // Strip leading/trailing pipes, then split.
  const trimmed = line.replace(/^\s*\|/, "").replace(/\|\s*$/, "");
  return trimmed.split("|").map((c) => c.trim());
}

/** parse alignment from separator. `:---` = left, `---:` = right,
 *  `:---:` = center, `---` = default. */
function parseAligns(sep: string): ("left" | "right" | "center" | null)[] {
  return splitTableRow(sep).map((cell) => {
    const t = cell.trim();
    const left = t.startsWith(":");
    const right = t.endsWith(":");
    if (left && right) return "center";
    if (right) return "right";
    if (left) return "left";
    return null;
  });
}

/** render a table with column widths computed from content.
 * brand-coloured header, heavy `━` separator, bold `│`
 *  column dividers. The table has a 2-char left indent so it
 *  nests inside the chat gutter without colliding with the
 *  conversation rail. */
function renderTable(rows: string[][], aligns: ("left" | "right" | "center" | null)[]): React.ReactNode {
  if (rows.length === 0) return null;
  // Compute column widths (max cell length per column).
  const cols = rows[0].length;
  const widths: number[] = Array(cols).fill(0);
  for (const row of rows) {
    for (let c = 0; c < cols; c++) {
      const cell = (row[c] ?? "").replace(/[`*_~]/g, "");
      if (cell.length > widths[c]) widths[c] = cell.length;
    }
  }
  // Cap each column at 40 chars to avoid terminal blowup.
  for (let c = 0; c < cols; c++) widths[c] = Math.min(widths[c], 40);
  const totalWidth = widths.reduce((a, b) => a + b + 3, -3);
  const lines: React.ReactNode[] = [];
  for (let r = 0; r < rows.length; r++) {
    const row = rows[r];
    const isHeader = r === 0;
    const cells: React.ReactNode[] = [];
    for (let c = 0; c < cols; c++) {
      const cell = row[c] ?? "";
      const w = widths[c];
      // Pad cell to column width. Right/center alignment is rare
      // for our model output, so we keep left + dim header.
      const padded = cell.padEnd(w, " ");
      // header cells get a brand colour (so they read as
      // labels). Data cells keep the default `asst` colour so
      // the contrast against the header is clear.
      cells.push(
        <Text key={`c${c}`} color={isHeader ? t.tableHeader : undefined} bold={isHeader}>
          {padded}
        </Text>
      );
      if (c < cols - 1) {
        // heavy `┃` column divider so the table structure
        // is unmistakable. (We use `┃` rather than `║` because
        // the latter is double-line — single heavy is the
        // convention for code-block / log tables.)
        cells.push(<Text key={`s${c}`} color={t.tableSep} bold> ┃ </Text>);
      }
    }
    // header is the only row that gets a trailing
    // accent (the full-width `━` underline drawn *outside* the
    // cells), so the user gets a strong "this is the label row"
    // signal even before reading the cell content.
    lines.push(
      <Text key={`r${r}`}>
        {cells}
      </Text>
    );
    if (isHeader) {
      // Add the separator line as its own row. R215: heavy `━`
      // in `t.tableSep` (dim grey) so it reads as a structural
      // divider, not a continuation of the header.
      const sepLine = widths.map((w) => "━".repeat(w)).join("─╂─");
      lines.push(<Text key={`sep`} color={t.tableSep} bold>{sepLine}</Text>);
    }
  }
  // wrap the table in a 2-char left indent so it sits
  // inside the chat gutter (matching the rest of the markdown
  // content) without colliding with the conversation rail.
  return (
    <Box key="table" flexDirection="column" marginLeft={2} marginY={0}>
      {lines}
      {/* bottom rule for visual closure — the table "closes"
          so the user can tell where it ends in a long scrollback. */}
      <Text color={t.tableSep}>{"─".repeat(Math.min(totalWidth, 80))}</Text>
    </Box>
  );
}

const INLINE = /(\*\*([^*]+)\*\*)|(\*([^*]+)\*)|(__([^_]+)__)|(~~([^~]+)~~)|(`([^`]+)`)/g;

/**
 * R220 — link pattern. Matches `[text](url)` where
 * `text` is non-`]` and `url` is non-`)`. We deliberately
 * keep this simple: no nested brackets, no escaped parens,
 * no `<...>` autolink. The model output rarely uses
 * those forms, and a fragile regex is worse than
 * missing the link (the user can still copy/paste from
 * the inline `text`).
 */
const LINK = /\[([^\]]+)\]\(([^)]+)\)/g;

/**
 * R220 — shorten a URL for inline display. We show the
 * user WHERE the link points (the URL hint is the value
 * of link preview) but long URLs are truncated. The
 * hostname is always preserved (that's the most useful
 * identifier); path + query are abbreviated.
 */
function shortenUrl(url: string, maxLen: number = 32): string {
  if (url.length <= maxLen) return url;
  // Try to preserve the hostname (e.g. "github.com").
  // Strip the protocol first, then keep the first two
  // path segments and ellipsize the rest.
  const stripped = url.replace(/^https?:\/\//, "");
  if (stripped.length <= maxLen) return stripped;
  // Take the first 2 segments (host + first path).
  const parts = stripped.split("/");
  if (parts.length >= 3) {
    const head = parts[0] + "/" + parts[1];
    if (head.length + 4 <= maxLen) {
      return head + "/…";
    }
  }
  return stripped.slice(0, maxLen - 1) + "…";
}

/** Render a non-link chunk via the INLINE regex.
 * extracted from `renderInline` so the link
 *  outer loop can recurse on each non-link sub-string. */
function renderInlineChunk(
  s: string,
  keyPrefix: string,
  baseOffset: number,
): React.ReactNode[] {
  const out: React.ReactNode[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  let idx = 0;
  INLINE.lastIndex = 0;
  while ((m = INLINE.exec(s)) !== null) {
    if (m.index > last) out.push(s.slice(last, m.index));
    if (m[2] != null) {
      // bold keeps its existing treatment — bold itself is
      // already a strong visual signal, no extra colour needed.
      out.push(<Text key={`${keyPrefix}-b${baseOffset}-${idx}`} bold>{m[2]}</Text>);
    } else if (m[4] != null) {
      out.push(<Text key={`${keyPrefix}-i${baseOffset}-${idx}`} italic>{m[4]}</Text>);
    } else if (m[6] != null) {
      out.push(<Text key={`${keyPrefix}-u${baseOffset}-${idx}`} underline>{m[6]}</Text>);
    } else if (m[8] != null) {
      // strikethrough — render as dim (Ink has no native
      // strikethrough attribute, so we use dim to signal "removed").
      out.push(
        <Text
          key={`${keyPrefix}-s${baseOffset}-${idx}`}
          dimColor
          strikethrough
        >
          {m[8]}
        </Text>,
      );
    } else if (m[10] != null) {
      // inline code is now a FILLED pill — same hue
      // foreground + background. The bg is a dark-magenta so
      // the code stands out without screaming. On terminals
      // that don't support `backgroundColor`, Ink falls back
      // to bold magenta (still legible).
      //
      // tokenize the inline code with the universal
      // inline-code tokenizer (`highlightInlineCode`). The
      // OUTER <Text> carries the `backgroundColor` (one pill,
      // one bg), while the INNER <Text> nodes carry the
      // per-token colour. This gives the user:
      //   `Use the \`git\` command` →
      //   Use the [pill:git] command     (git is plain/identifier)
      //   `\`if x > 0\`` →              (if is keyword magenta)
      //   [pill: keyword-magenta "if" white "x" white ">" white " " white "0" yellowBright]
      // The pill visual (prior round) is preserved; the contents
      // get colour-coded (prior round).
      //
      // Note: only <Text> in Ink accepts `backgroundColor`,
      // not <Box>. We wrap the tokens in a single <Text> so
      // the entire pill shares one bg (instead of N
      // disjoint bg segments). The leading + trailing space
      // <Text> nodes use the same bg so the pill has visible
      // padding on both sides.
      const tokens = highlightInlineCode(m[10]);
      out.push(
        <Text
          key={`${keyPrefix}-c${baseOffset}-${idx}`}
          backgroundColor={t.codeBg}
        >
          <Text backgroundColor={t.codeBg}> </Text>
          {tokens.map((tk, k) => {
            const colorKey = tokenKindToColor(tk.kind);
            const colorVal = (t as Record<string, string>)[colorKey];
            return (
              <Text
                key={`${keyPrefix}-c${baseOffset}-${idx}-t${k}`}
                color={colorVal}
                backgroundColor={t.codeBg}
              >
                {tk.text}
              </Text>
            );
          })}
          <Text backgroundColor={t.codeBg}> </Text>
        </Text>
      );
    }
    last = m.index + m[0].length;
    idx++;
  }
  if (last < s.length) out.push(s.slice(last));
  return out;
}

function renderInline(s: string, keyPrefix: string): React.ReactNode[] {
  // R220 — link preview outer pass. We scan for
  // `[text](url)` first; non-link chunks are recursed
  // into `renderInlineChunk` for the standard INLINE
  // processing. Link matches become two Text nodes:
  //   1. `text` — accent (cyan) + underline so the user
  //      can see it's a link.
  //   2. ` (url-shortened)` — dim, immediately after the
  //      text. This is the "link preview" — the user
  //      sees the destination at a glance.
  //
  // We deliberately keep this minimal: no keyboard
  // interaction, no hover state, no click-to-open.
  // The user asked for "just make it look a little nicer,
  // doesn't need to be perfect, no need to overdo it" — link visibility is the
  // value, not link interactivity.
  const out: React.ReactNode[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  LINK.lastIndex = 0;
  while ((m = LINK.exec(s)) !== null) {
    if (m.index > last) {
      // Non-link chunk: recurse into the standard INLINE
      // renderer.
      out.push(...renderInlineChunk(s.slice(last, m.index), keyPrefix, last));
    }
    const text = m[1];
    const url = m[2];
    // link text in `t.accent` + underline. URL hint
    // in dim. The user can copy either piece from the
    // terminal (the `[text]` is the visual label, the
    // `(url)` is the actual destination).
    out.push(
      <Text key={`${keyPrefix}-l${m.index}-t`} color={t.accent} underline>
        {text}
      </Text>,
    );
    out.push(
      <Text key={`${keyPrefix}-l${m.index}-u`} dimColor>
        {` (${shortenUrl(url)})`}
      </Text>,
    );
    last = m.index + m[0].length;
  }
  if (last < s.length) {
    out.push(...renderInlineChunk(s.slice(last), keyPrefix, last));
  }
  return out;
}

/** pick the heading colour token by level. H1/H2 carry the
 *  brand weight; H3 is a magenta accent; H4 keeps the default
 *  white so the user can still read it; H5/H6 fade to dim. */
function headingColor(level: number): string {
  switch (level) {
    case 1: return t.heading1;
    case 2: return t.heading2;
    case 3: return t.heading3;
    case 4: return t.heading4;
    case 5: return t.heading5;
    case 6: return t.heading6;
    default: return t.heading4;
  }
}

/** heading underline character. H1 gets the heaviest `═`
 *  line (full double-line) so it reads as a "title". H2 gets a
 *  single `─` line as a "section" marker. H3+ have no underline —
 *  the colour change + extra spacing carries the weight. */
function headingUnderline(level: number): string | null {
  if (level === 1) return "═";
  if (level === 2) return "─";
  return null;
}

export const Markdown: React.FC<MdProps> = ({ text }) => {
  const blocks = parseBlocks(text);
  return (
    <Box flexDirection="column">
      {blocks.map((b, i) => {
        const key = `b${i}`;
        if (b.kind === "blank") {
          // collapse *consecutive* blank lines into a
          // single visual gap. The legacy renderer emitted
          // a literal empty <Text> </Text> for every blank
          // line, which produced double-spacing between blocks.
          // We now skip blanks that immediately follow another
          // blank — the spacing comes from each block's own
          // `marginY` instead.
          if (i > 0 && blocks[i - 1].kind === "blank") return null;
          return <Text key={key}> </Text>;
        }
        if (b.kind === "heading") {
          const level = b.level ?? 1;
          const color = headingColor(level);
          const underline = headingUnderline(level);
          // H1/H2 get a decorative underline. We compute
          // the width from the heading text length so the
          // underline hugs the text, but cap at 40 chars.
          const titleText = b.body;
          const rule = underline
            ? underline.repeat(Math.min(Math.max(titleText.length + 2, 8), 40))
            : null;
          return (
            <Box key={key} flexDirection="column" marginY={0} marginTop={1}>
              <Text>
                {/* H1 gets a small `▌` brand bar prefix so
                    the heading reads as a "section start" even
                    on a single-line view. H2-H6 skip the bar
                    (the colour is enough). */}
                {level === 1 ? <Text color={color} bold>▌ </Text> : null}
                <Text color={color} bold>{titleText}</Text>
              </Text>
              {rule ? <Text color={color}>{rule}</Text> : null}
            </Box>
          );
        }
        if (b.kind === "code") {
          // wrap the code block in a rounded box with a
          // left-side brand-color stripe so the user can SEE the
          // block boundary at a glance, even when the surrounding
          // text is also wrapped in indented bullets.
          //
          // the block now has 3 distinct visual layers —
          //   1. A "language" pill (filled bg, brand fg) at the
          //      top. Empty for plain (```) blocks.
          //   2. The body lines, each rendered in `t.code` so
          //      the code is unmistakable from the prose.
          //   3. A dim purple border (`t.codeBorder`) wrapping
          //      the whole thing. The previous `t.dim` border
          //      blended with chat-content borders; the brand-
          //      tinted purple is clearly "this is code".
          //
          // layer #2 is now tokenized via `highlightCode`
          // (regex-based, dependency-free). Each token kind
          // (keyword / string / number / comment / builtin /
          // operator) maps to its own `t.tok*` colour, so a
          // 30-line bash snippet reads as a structured script
          // instead of one flat magenta wall. An unknown /
          // empty language falls back to the legacy
          // single-colour behaviour.
          const lang = b.lang ?? "";
          const lines = b.body.split("\n");
          return (
            <Box key={key} flexDirection="column" marginLeft={2} marginY={1}
                  borderStyle="round" borderColor={t.codeBorder} paddingX={1}>
              {lang ? (
                <Box marginBottom={1}>
                  {/* language tag — a small filled pill
                      that says "this is a {lang} code block".
                      Uses `t.brand` bg so it pops without
                      competing with the code body. */}
                  <Text color="black" backgroundColor={t.brand} bold>
                    {` ${lang} `}
                  </Text>
                </Box>
              ) : null}
              {lines.map((ln, j) => {
                // tokenize the line, then emit one
                // <Text> per kind. Empty lines render as a
                // single space so the box height is preserved
                // (matches the legacy behaviour).
                if (ln === "") {
                  return <Text key={`${key}-l${j}`}> </Text>;
                }
                const tokens = highlightCode(ln, lang);
                return (
                  <Text key={`${key}-l${j}`}>
                    {tokens.map((tk, k) => {
                      const colorKey = tokenKindToColor(tk.kind);
                      const colorVal = (t as Record<string, string>)[colorKey];
                      // comments render italic so they
                      // visually recede (the desktop renderer's
                      // `.tok-comment` does the same).
                      return (
                        <Text
                          key={`${key}-l${j}-t${k}`}
                          color={colorVal}
                          italic={tk.kind === "comment"}
                        >
                          {tk.text}
                        </Text>
                      );
                    })}
                  </Text>
                );
              })}
            </Box>
          );
        }
        if (b.kind === "list") {
          // bullet/numbered markers are colour-coded so
          // the list "shape" is visible at a glance. The bullet
          // `▸` is in `t.brand` (yellow); the number `(1)` is
          // in `t.accent` (cyan). Hanging indent is bumped to
          // 4 characters so the text doesn't crowd the marker.
          const kind = b.listKind ?? "bullet";
          return (
            <Box key={key} flexDirection="column" marginLeft={2} marginY={0}>
              {(b.items ?? []).map((it, j) => {
                if (kind === "ordered") {
                  const num = String(j + 1).padStart(2, " ");
                  return (
                    <Text key={`${key}-i${j}`}>
                      <Text color={t.accent} bold>{`  ${num}. `}</Text>
                      {renderInline(it, `${key}-i${j}`)}
                    </Text>
                  );
                }
                // bullet: brand-coloured `▸` + 1-space hang.
                return (
                  <Text key={`${key}-i${j}`}>
                    <Text color={t.brand} bold>{"  ▸ "}</Text>
                    {renderInline(it, `${key}-i${j}`)}
                  </Text>
                );
              })}
            </Box>
          );
        }
        if (b.kind === "rule") {
          // horizontal rule is now brand-coloured and uses
          // the heavy `━` character. The 40-char width matches
          // the legacy default so existing layouts don't
          // shift; the colour is the new bit.
          return (
            <Box key={key} marginLeft={2} marginY={1}>
              <Text color={t.rule}>{"━".repeat(40)}</Text>
            </Box>
          );
        }
        if (b.kind === "quote") {
          // blockquote has a heavy `┃` left bar in
          // `t.quoteBar` (cyan) and accent-tinted text. The
          // previous version used `│` (thin) and dim grey —
          // easy to miss in a long scrollback. The new
          // treatment gives quoted text a clear identity.
          return (
            <Box key={key} flexDirection="row" marginLeft={2} marginY={0}>
              <Text color={t.quoteBar} bold>{"┃ "}</Text>
              <Box flexDirection="column" flexGrow={1}>
                <Text color={t.quoteText}>{renderInline(b.body, key)}</Text>
              </Box>
            </Box>
          );
        }
        if (b.kind === "table") {
          return renderTable(b.rows ?? [], b.aligns ?? []);
        }
        // para — R215: paragraphs now have a top margin so the
        // document has visible block rhythm (matches the
        // desktop's `margin: 0.3em 0`). Trailing whitespace
        // gets normalised so we don't emit double spaces when
        // the source has run-on paragraphs.
        return (
          <Box key={key} flexDirection="column" marginLeft={2} marginY={0} marginTop={1}>
            <Text>{renderInline(b.body, key)}</Text>
          </Box>
        );
      })}
    </Box>
  );
};
