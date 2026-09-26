/**
 * line-mode renderer (no Ink / no full-screen UI).
 *
 * R345 visual upgrade:
 * - Splash at start: brand mark + session summary + 1-line tips
 * - Per-turn status line before each run (model · mode · ctx · uptime)
 * - Tool cards show input digest + duration + result digest
 * - Markdown stream: bold / italic / code / headings are ANSI-styled
 * - Permission prompts: 1/2/3/4 numbered selector + risk-coloured header
 * - Multi-line input via trailing `\` (line continuation)
 */

import * as readline from "node:readline";
import { LineInput } from "./lineInput.js";
import { JsonRpcClient, type RpcValue } from "./jsonrpc.js";
import { handleSlash, SLASH_HELP } from "./commands.js";

export interface LineOptions {
  jar: string;
  cwd: string;
  javaBin: string;
  jvmArgs: string[];
  model?: string;
  noColor?: boolean;
}

const C = {
  reset:        "\x1b[0m",
  bold:         "\x1b[1m",
  dim:          "\x1b[2m",
  italic:       "\x1b[3m",
  underline:    "\x1b[4m",
  red:          "\x1b[31m",
  green:        "\x1b[32m",
  yellow:       "\x1b[33m",
  blue:         "\x1b[34m",
  magenta:      "\x1b[35m",
  cyan:         "\x1b[36m",
  gray:         "\x1b[90m",
  yellowBright: "\x1b[93m",
  cyanBright:   "\x1b[96m",
  // R346: a few more reserved for new visual surfaces
  bgGray:       "\x1b[100m", // used for permission prompt footer
  bgYellow:     "\x1b[43m",
  bgBlue:       "\x1b[44m",
} as const;

/** R346: tool-running spinner frames. The spinner is updated by
 *  `spinnerFrame(elapsedMs)`, which picks a frame based on the
 *  current millisecond clock — since the line renderer doesn't
 *  redraw in place, each new line prints the next frame and the
 *  user sees the sequence tick forward. */
const SPINNER_FRAMES = ["\u280B", "\u2819", "\u2838", "\u2834",
                        "\u2826", "\u2827", "\u2807", "\u280F"];
const SPINNER_PERIOD_MS = 120;
function spinnerFrame(elapsedMs: number): string {
  const idx = Math.floor(elapsedMs / SPINNER_PERIOD_MS) % SPINNER_FRAMES.length;
  return SPINNER_FRAMES[idx];
}

/** R346: short horizontal divider. width is the visible cell count
 *  AFTER the leading "  " indent and any prefix glyph. We use
 *  U+2500 (BOX DRAWINGS LIGHT HORIZONTAL) for a calmer look than
 *  the heavier U+2501. */
function renderDivider(width: number = 60, indent: boolean = true, noColor: boolean = false): string {
  const safeW = Math.max(8, Math.min(width, 120));
  const line = "\u2500".repeat(safeW);
  const prefix = indent ? "  " : "";
  return colorize(prefix + line, C.dim, noColor);
}

/** R346: footer line rendered right after `run_end`. Shows
 *  token totals + duration + (if provided) cost. Designed to be
 *  readable at a glance and match the run_start status line's
 *  weight, so the user has a clear "this turn consumed X" moment
 *  without needing to open /state. */
function renderRunFooter(
  usage: { input?: number; output?: number; costUsd?: number } | null | undefined,
  durationMs: number,
  noColor: boolean,
): string {
  const parts: string[] = [];
  parts.push(colorize("  \u2517\u2500 ", C.dim, noColor));
  parts.push(colorize("in", C.dim, noColor));
  const inN = usage?.input ?? 0;
  parts.push(colorize(` ${formatTokenCount(inN)}`, C.bold, noColor));
  parts.push(colorize(" \u00B7 ", C.dim, noColor));
  parts.push(colorize("out", C.dim, noColor));
  const outN = usage?.output ?? 0;
  parts.push(colorize(` ${formatTokenCount(outN)}`, C.bold, noColor));
  if (typeof usage?.costUsd === "number") {
    parts.push(colorize(" \u00B7 ", C.dim, noColor));
    parts.push(colorize(`$${usage.costUsd.toFixed(3)}`, C.yellowBright, noColor));
  }
  parts.push(colorize(" \u00B7 ", C.dim, noColor));
  parts.push(colorize(fmtDuration(durationMs), C.dim, noColor));
  return parts.join("");
}

function formatTokenCount(n: number): string {
  if (!Number.isFinite(n) || n <= 0) return "0";
  if (n < 1000) return String(n);
  if (n < 10_000) return `${(n / 1000).toFixed(2)}k`;
  if (n < 1_000_000) return `${(n / 1000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(2)}M`;
}

function colorize(s: string, color: string, noColor: boolean): string {
  if (noColor) return s;
  return `${color}${s}${C.reset}`;
}

type StopKind = "ok" | "loop" | "max_turns" | "error" | "empty" | null;

function classifyStopReason(reason: string | null | undefined): StopKind {
  if (reason == null) return null;
  const r = reason.toLowerCase();
  if (r === "loop_detected" || r.startsWith("loop_")) return "loop";
  if (r === "max_iterations" || r === "max_turns" || r === "max_tool_iterations") return "max_turns";
  if (r === "empty_input" || r === "empty") return "empty";
  if (r === "error" || r.startsWith("error_") || r.endsWith("_error")) return "error";
  return "ok";
}

function stopKindLabel(kind: StopKind, rawReason?: string): string {
  switch (kind) {
    case "loop":      return "stopped — loop";
    case "max_turns": return "stopped — max turns";
    case "error":     return "stopped — error";
    case "empty":     return "empty input";
    case "ok":        return "✓ ready";
    default:          return `run_end: ${rawReason ?? "?"}`;
  }
}

function stopKindColor(kind: StopKind): string {
  switch (kind) {
    case "loop":      return C.red;
    case "max_turns": return C.yellow;
    case "error":     return C.red;
    case "empty":     return C.gray;
    case "ok":        return C.green;
    default:          return C.dim;
  }
}

type LineCategory = "read" | "write" | "search" | "run" | "agent" | "other";

function categorizeLine(name: string | undefined | null): LineCategory {
  if (!name) return "other";
  const n = name.toLowerCase();
  if (n === "file_read" || n === "read" || n.startsWith("read_") || n === "cat" || n === "show" || n === "view") return "read";
  if (n === "glob" || n === "grep" || n === "search" || n.startsWith("search_") || n === "find") return "search";
  if (n === "file_write" || n === "file_edit" || n === "file_create" || n.startsWith("write_") || n.startsWith("edit_")) return "write";
  if (n === "bash" || n === "shell" || n === "exec" || n === "run_command" || n.startsWith("bash_") || n === "test" || n === "build") return "run";
  if (n === "agent" || n === "task" || n === "delegate" || n.startsWith("agent_") || n.startsWith("task_")) return "agent";
  return "other";
}

const CAT_ICON:  Record<LineCategory, string> = {
  read:   "\u25CB", write: "\u270E", search: "\u25CE", run: "\u25B7", agent: "\u2756", other: "\u2692",
};
const CAT_COLOR: Record<LineCategory, string> = {
  read:   C.blue, write: C.yellowBright, search: C.cyan,
  run:    C.red, agent: C.magenta,      other: C.gray,
};

function inputDigest(input: unknown): string {
  if (!input || typeof input !== "object") return "";
  const o = input as Record<string, unknown>;
  if (typeof o.command === "string") {
    const cmd = o.command.replace(/\s+/g, " ").trim();
    return cmd.length > 60 ? cmd.slice(0, 57) + "\u2026" : cmd;
  }
  if (typeof o.file_path === "string") return String(o.file_path);
  if (typeof o.path === "string")      return String(o.path);
  if (typeof o.url === "string")       return String(o.url);
  if (typeof o.pattern === "string")   return `pattern=${o.pattern}`;
  if (typeof o.query === "string")     return `q=${o.query}`;
  return "";
}

function resultDigest(result: unknown, maxLines: number = 2): string {
  if (result == null) return "";
  const s = typeof result === "string" ? result : JSON.stringify(result);
  if (!s) return "";
  const lines = s.split(/\r?\n/).filter((l) => l.trim().length > 0);
  const pick = lines.slice(0, maxLines);
  const truncated = lines.length > maxLines;
  return (pick.length === 0 ? "(empty)" : pick.join("\n")) + (truncated ? "\n\u2026" : "");
}

function fmtDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return "\u2014";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(s / 60);
  const rs = s % 60;
  return `${m}m${rs.toString().padStart(2, "0")}s`;
}

function modeStyle(mode: string): { label: string; bg: string; fg: string } {
  const m = mode.toUpperCase();
  switch (m) {
    case "DEFAULT":
    case "ASK_BEFORE_TOOL":
      return { label: "ASK",    bg: C.yellowBright, fg: C.bold };
    case "ACCEPT_TASK":
      return { label: "TASK",   bg: C.green,        fg: C.bold };
    case "ACCEPT_EDITS":
      return { label: "EDITS",  bg: C.green,        fg: C.bold };
    case "BYPASS_PERMISSIONS":
      return { label: "BYPASS", bg: C.red,          fg: C.bold };
    case "PLAN":
      return { label: "PLAN",   bg: C.magenta,      fg: C.bold };
    case "AUTO_READ_ONLY":
      return { label: "READ",   bg: C.cyan,         fg: C.bold };
    default:
      return { label: m,        bg: C.gray,         fg: C.bold };
  }
}

function modePill(mode: string, noColor: boolean): string {
  const s = modeStyle(mode);
  if (noColor) return `[${s.label}]`;
  return `${s.bg}[${s.label}]${C.reset}`;
}

function ctxFillGlyph(inputTok: number | null, outputTok: number | null, ctxWindow: number): string {
  if (!ctxWindow || ctxWindow <= 0) return "";
  const total = (inputTok ?? 0) + (outputTok ?? 0);
  const ratio = Math.max(0, Math.min(1, total / ctxWindow));
  const cells = 8;
  const filled = Math.round(ratio * cells);
  const empty = cells - filled;
  const glyph = "\u2586".repeat(filled) + "\u2591".repeat(empty);
  const pct = Math.round(ratio * 100);
  const color =
    ratio > 0.8 ? C.red :
    ratio > 0.5 ? C.yellowBright :
                  C.green;
  return `${color}${glyph}${C.reset} ${pct}%`;
}

function shortCwd(p: string, maxLen: number = 36): string {
  if (!p) return "\u2014";
  if (p.length <= maxLen) return p;
  const parts = p.split(/[\\/]/);
  if (parts.length <= 2) return p;
  const tail = parts.slice(-2).join("/");
  return tail.length > maxLen ? `\u2026/${tail.slice(-(maxLen - 4))}` : `\u2026/${tail}`;
}

function shortSession(s: string): string {
  return !s || s === "\u2014" ? "\u2014" : s.slice(0, 8);
}

function renderInlineMarkdown(text: string, noColor: boolean): string {
  if (noColor) return text;

  // State machine that walks the text line by line, recognising block-
  // level markdown first (code fence / hr / blockquote / list / GFM
  // table / heading) and applying inline markdown (bold / italic /
  // inline code) only to *paragraph* lines — never inside code blocks,
  // so `**foo**` inside a ``` block stays literal.
  //
  // R358 visual upgrades over R357:
  //   - block spacing: heading / code / table / hr each get a blank
  //     line before AND after (list items and blockquote lines are
  //     NOT separated — they're contiguous by design).
  //   - heading underline: H1 = ═══, H2 = ─── full-width; H3+ = none
  //     (colour fade is enough at that level).
  //   - table box frame: full ╭─╮ / ├─┤ / ╰─╯ wrap instead of bare
  //     column dividers, with column widths driven by header / data.
  //   - code-block border: ── (light) → ━━ (heavy) so the surface
  //     reads as "container" not "deco".
  //   - blockquote: dim base + bright ┃ bar + bold-prefixed first
  //     line (R356 styledLine semantics, applied to block lines too).
  //
  // Box-drawing characters used:
  //   ╭ ╮ ╰ ╯ ━ ─ │ ┃ ▸ ┬ ┼ ┴ ├
  // All of these are present in cmd.exe's default raster font (verified
  // — the picker border (╭ ─ ─╮ / ╰ ─╯) already renders correctly).
  const lines = text.split("\n");
  const out: string[] = [];
  let i = 0;

  const RULE_W = 60;
  const RULE_HEAVY = "\u2501"; // ━
  const RULE_LIGHT = "\u2500"; // ─

  // ---- inline span renderer (heading REMOVED — handled separately;
  //     bold / italic / inline code only) ----
  const inlineSpan = (s: string): string => {
    let r = s.replace(/\*\*([^*\n]+)\*\*/g, `${C.bold}$1${C.reset}`);
    r = r.replace(/(?<!\*)\*([^*\n]+)\*(?!\*)/g, `${C.italic}$1${C.reset}`);
    r = r.replace(/`([^`\n]+)`/g, `${C.cyanBright}$1${C.reset}`);
    return r;
  };

  // ---- GFM table row split: | a | b | c | -> ["a","b","c"] ----
  const splitTableRow = (line: string): string[] => {
    let s = line.trim();
    if (s.startsWith("|")) s = s.slice(1);
    if (s.endsWith("|")) s = s.slice(0, -1);
    return s.split("|").map((c) => c.trim());
  };

  // Track previous block kind so we can decide spacing. "" = nothing
  // yet; "list"/"quote" = continuation of an in-progress group.
  let lastKind = "";
  const pushSpace = (): void => {
    if (out.length === 0) return;
    if (out[out.length - 1] === "") return;
    out.push("");
  };

  while (i < lines.length) {
    const line = lines[i];

    // ---- heading: # ... ###### ... ----
    const headingMatch = /^(#{1,6})\s+(.+?)\s*#*\s*$/.exec(line);
    if (headingMatch) {
      const hashes = headingMatch[1];
      const body = headingMatch[2];
      const level = hashes.length;
      const color =
        level === 1 ? C.bold + C.cyanBright
      : level === 2 ? C.bold + C.cyan
      : level === 3 ? C.bold + C.magenta
                    : C.bold + C.gray;
      const inlineBody = body; // heading text — keep literal (no bold/italic inside it for now)
      // Compute underline width: max(visible width of "level body", 8),
      // capped at terminal-friendly width.
      const visibleW = inlineBody.length + 2; // "  # " + body
      const uW = Math.min(RULE_W, Math.max(visibleW, 8));
      const underlineChar =
        level === 1 ? RULE_HEAVY
      : level === 2 ? RULE_LIGHT
                    : null;
      // Block spacing — heading is its own group.
      if (lastKind !== "" && lastKind !== "heading") pushSpace();
      out.push(colorize(`  ${hashes} ${inlineBody}`, color, noColor));
      if (underlineChar) {
        const underline = "  " + underlineChar.repeat(uW);
        out.push(colorize(underline, level === 1 ? C.cyanBright : C.cyan, noColor));
      }
      lastKind = "heading";
      i++;
      continue;
    }

    // ---- fenced code block: ``` or ~~~ ----
    const fenceMatch = /^(\s*)(`{3,}|~{3,})\s*([^\s]*)\s*$/.exec(line);
    if (fenceMatch) {
      const indent = fenceMatch[1] || "";
      const fenceChar = fenceMatch[2][0];
      const fenceLen = fenceMatch[2].length;
      const lang = fenceMatch[3] || "";
      // Find closing fence (same indent, same char, length >= fenceLen)
      const closeRe = new RegExp(`^${indent.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\s*${fenceChar === "`" ? "\\`" : "~"}{${fenceLen},}\\s*$`);
      let j = i + 1;
      while (j < lines.length && !closeRe.test(lines[j])) j++;
      const codeLines = lines.slice(i + 1, j);
      const label = lang ? ` ${lang} ` : " ";
      const topFill = Math.max(0, RULE_W - label.length - 4);
      // Block spacing — code is its own group.
      if (lastKind !== "" && lastKind !== "code") pushSpace();
      out.push(colorize(`  \u256D\u2501${label}`, C.dim, noColor) + colorize(RULE_LIGHT.repeat(topFill) + "\u256E", C.dim, noColor));
      for (const cl of codeLines) {
        out.push(colorize("  \u2503 ", C.dim, noColor) + colorize(cl || " ", C.cyan, noColor));
      }
      out.push(colorize(`  \u2570${RULE_LIGHT.repeat(RULE_W - 2)}\u256F`, C.dim, noColor));
      lastKind = "code";
      i = j + 1;
      continue;
    }

    // ---- horizontal rule: --- / *** / ___ (3+ chars) ----
    if (/^(\s*)(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      // Block spacing — hr is its own group.
      if (lastKind !== "" && lastKind !== "hr") pushSpace();
      out.push(colorize("  " + RULE_HEAVY.repeat(RULE_W), C.gray, noColor));
      lastKind = "hr";
      i++;
      continue;
    }

    // ---- blockquote: > ... ----
    const bqMatch = /^(\s*)>\s?(.*)$/.exec(line);
    if (bqMatch) {
      const indent = bqMatch[1] || "";
      const body = bqMatch[2];
      // Blockquote is a contiguous group — no leading blank line within.
      if (lastKind !== "" && lastKind !== "quote") pushSpace();
      // Dim base + bright ┃ bar (same trick as pickPermission's
      // styledLine — re-enter dim after every inline reset).
      const rendered = inlineSpan(body);
      const dimWrap = noColor
        ? rendered
        : `${C.dim}${rendered.replace(/\x1b\[0m/g, "\x1b[0m\x1b[2m")}${C.reset}`;
      out.push(
        (indent ? colorize(indent, C.reset, noColor) : "") +
        colorize("  \u2503 ", C.cyan, noColor) +
        dimWrap,
      );
      lastKind = "quote";
      i++;
      continue;
    }

    // ---- unordered list: - / * / + ----
    const bulletMatch = /^(\s*)([-*+])\s+(.+)$/.exec(line);
    if (bulletMatch) {
      const indent = bulletMatch[1] || "";
      const content = bulletMatch[3];
      // List is a contiguous group — no leading blank within.
      if (lastKind !== "" && lastKind !== "list") pushSpace();
      out.push((indent ? colorize(indent, C.reset, noColor) : "") + colorize("  \u25B8 ", C.cyan, noColor) + inlineSpan(content));
      lastKind = "list";
      i++;
      continue;
    }

    // ---- ordered list: 1. / 2. / ... ----
    const numMatch = /^(\s*)(\d+)\.\s+(.+)$/.exec(line);
    if (numMatch) {
      const indent = numMatch[1] || "";
      const num = numMatch[2];
      const content = numMatch[3];
      // Same group as bullet list (both render as the same "list"
      // family — we treat them as contiguous with each other too).
      if (lastKind !== "" && lastKind !== "list") pushSpace();
      out.push((indent ? colorize(indent, C.reset, noColor) : "") + colorize(`  ${num}. `, C.cyan, noColor) + inlineSpan(content));
      lastKind = "list";
      i++;
      continue;
    }

    // ---- GFM table: a | b | c\n---|---|---\n ... ----
    if (line.includes("|") && i + 1 < lines.length) {
      const sepLine = lines[i + 1];
      if (/^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)+\|?\s*$/.test(sepLine)) {
        const headerCells = splitTableRow(line);
        // Parse column alignment from the separator (`:---:` etc.).
        const sepCells = splitTableRow(sepLine);
        const aligns: ("left" | "right" | "center" | null)[] = sepCells.map((s) => {
          const left = s.startsWith(":");
          const right = s.endsWith(":");
          if (left && right) return "center";
          if (right) return "right";
          return "left";
        });
        // Collect data rows.
        const dataRows: string[][] = [];
        let j = i + 2;
        while (
          j < lines.length &&
          lines[j].includes("|") &&
          !/^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)+\|?\s*$/.test(lines[j]) &&
          lines[j].trim() !== ""
        ) {
          dataRows.push(splitTableRow(lines[j]));
          j++;
        }
        // Compute column widths — max of header / data, pad to 3 min.
        const colW = headerCells.map((h, idx) => {
          const widths = [stripAnsi(h).length, ...dataRows.map((r) => stripAnsi(r[idx] ?? "").length)];
          return Math.max(3, ...widths);
        });
        // Border builders.
        const border = (left: string, mid: string, right: string, fill: string): string => {
          const segs = colW.map((w) => fill.repeat(w + 2));
          return colorize(`  ${left}${segs.join(mid)}${right}`, C.dim, noColor);
        };
        // Block spacing — table is its own group.
        if (lastKind !== "" && lastKind !== "table") pushSpace();
        // Top border ╭─╮.
        out.push(border("\u256D", "\u252C", "\u256E", RULE_LIGHT));
        // Header row — bold + cyan, ┃ dividers, padding per align.
        const renderCell = (content: string, w: number, align: "left" | "right" | "center" | null): string => {
          const visible = stripAnsi(content);
          const pad = w - visible.length;
          const left = align === "right" ? pad : align === "center" ? Math.floor(pad / 2) : 0;
          const right = align === "right" ? 0 : pad - left;
          return " ".repeat(left) + content + " ".repeat(right);
        };
        const headerRow =
          "  " +
          colorize("\u2503", C.dim, noColor) + " " +
          headerCells
            .map((c, idx) =>
              colorize(renderCell(colorize(c, C.bold + C.cyan, noColor), colW[idx], aligns[idx]), C.reset, noColor),
            )
            .join(" " + colorize("\u2503", C.dim, noColor) + " ") +
          " " + colorize("\u2503", C.dim, noColor);
        out.push(headerRow);
        // Mid border ├─╮┼─┤.
        out.push(border("\u251C", "\u253C", "\u2524", RULE_LIGHT));
        // Data rows.
        for (const row of dataRows) {
          const cells = headerCells.map((_, idx) => row[idx] ?? "");
          const rowLine =
            "  " +
            colorize("\u2503", C.dim, noColor) + " " +
            cells
              .map((c, idx) => renderCell(inlineSpan(c), colW[idx], aligns[idx]))
              .join(" " + colorize("\u2503", C.dim, noColor) + " ") +
            " " + colorize("\u2503", C.dim, noColor);
          out.push(rowLine);
        }
        // Bottom border ╰─╯.
        out.push(border("\u2570", "\u2534", "\u256F", RULE_LIGHT));
        lastKind = "table";
        i = j;
        continue;
      }
    }

    // ---- default: paragraph (inline only). Empty lines collapse to
    //     a single blank separator — but only if we're not the first
    //     thing in the output. ----
    if (line.trim() === "") {
      // Drop, pushSpace will handle it next time we hit a non-empty.
      i++;
      continue;
    }
    if (lastKind !== "" && lastKind !== "para") pushSpace();
    out.push(inlineSpan(line));
    lastKind = "para";
    i++;
  }

  // Trim trailing blank lines that might have been inserted.
  while (out.length > 0 && out[out.length - 1] === "") out.pop();

  return out.join("\n");
}

interface DisplayState {
  sessionId: string;
  model: string;
  permissionMode: string;
  contextWindow: number;
  inputTokens: number | null;
  outputTokens: number | null;
  startedAt: number;
  cwd: string;
}

const INITIAL_STATE: DisplayState = {
  sessionId: "\u2014",
  model: "\u2014",
  permissionMode: "DEFAULT",
  contextWindow: 0,
  inputTokens: null,
  outputTokens: null,
  startedAt: Date.now(),
  cwd: "",
};

function renderStatusLine(state: DisplayState, inFlight: number, noColor: boolean): string {
  const parts: string[] = [];
  parts.push(colorize("  \u2520\u2500 ", C.dim, noColor));
  parts.push(colorize(state.model, C.cyan, noColor));
  parts.push(colorize(" \u00B7 ", C.dim, noColor));
  parts.push(modePill(state.permissionMode, noColor));
  parts.push(colorize(" \u00B7 ", C.dim, noColor));
  const ctx = ctxFillGlyph(state.inputTokens, state.outputTokens, state.contextWindow);
  if (ctx) parts.push(ctx + colorize(" \u00B7 ", C.dim, noColor));
  const ms = Date.now() - state.startedAt;
  parts.push(colorize(fmtDuration(ms), C.dim, noColor));
  if (inFlight > 0) {
    parts.push(colorize(` \u00B7 ${inFlight} tool${inFlight === 1 ? "" : "s"} in flight`, C.yellow, noColor));
  }
  return parts.join("");
}

function renderSplash(state: DisplayState, noColor: boolean): string {
  // R346: wrap the splash in a subtle box-drawing border. We
  // measure the longest inner content line and pad every row to
  // the same width so the right edge lines up. The top/bottom
  // rows use the standard ╭─╮ / ╰─╯ corners. We deliberately
  // keep the box width <= terminal width - 4 to leave breathing
  // room and avoid line-wrap disasters.
  const innerWidth = 56;
  const top    = "  " + "\u256D" + "\u2500".repeat(innerWidth + 2) + "\u256E";
  const bottom = "  " + "\u2570" + "\u2500".repeat(innerWidth + 2) + "\u256F";
  const sep    = "  " + "\u251C" + "\u2500".repeat(innerWidth + 2) + "\u2524";

  const titleLine = colorize("AetherCode", C.bold + C.cyanBright, noColor) +
                    colorize(" v0.2.1", C.dim, noColor);
  const subtitleLine = colorize("type a prompt, press Enter", C.dim, noColor);
  const ctxLine =
    colorize("\u232C ", C.cyan, noColor) + colorize(state.model, C.bold, noColor) +
    colorize("  \u00B7  ", C.dim, noColor) +
    modePill(state.permissionMode, noColor);
  const sessionLine =
    colorize("# ", C.dim, noColor) + colorize(shortSession(state.sessionId), C.dim, noColor);
  // Compose the row so we can compute the padding as a single
  // visible-cell count. ctxLine + 2-space gap + sessionLine is
  // one logical row inside the box.
  const ctxRow = ctxLine + "  " + sessionLine;
  const cwdLine =
    colorize("\u21B5 ", C.cyan, noColor) + colorize(shortCwd(state.cwd || process.cwd(), 48), C.dim, noColor);
  const slashLine =
    colorize("/", C.yellowBright, noColor) + colorize("help", C.dim, noColor) +
    colorize(" \u00B7 ", C.dim, noColor) +
    colorize("/", C.yellowBright, noColor) + colorize("clear", C.dim, noColor) +
    colorize(" \u00B7 ", C.dim, noColor) +
    colorize("/", C.yellowBright, noColor) + colorize("exit", C.dim, noColor) +
    colorize(" \u00B7 ", C.dim, noColor) +
    colorize("/", C.yellowBright, noColor) + colorize("demo", C.dim, noColor) +
    colorize(" \u00B7 ", C.dim, noColor) +
    colorize("/", C.yellowBright, noColor) + colorize("mode", C.dim, noColor) +
    colorize(" \u00B7 ", C.dim, noColor) +
    colorize("/", C.yellowBright, noColor) + colorize("no-confirm", C.dim, noColor);
  const historyLine =
    colorize("\u2191/\u2193", C.cyan, noColor) +
    colorize(" history in Ink TUI  \u00B7  ", C.dim, noColor) +
    colorize("trailing", C.dim, noColor) +
    colorize(" \\", C.yellowBright, noColor) +
    colorize(" for multi-line", C.dim, noColor);

  function row(content: string): string {
    const used = stripAnsi(content).length;
    const pad = Math.max(0, innerWidth - used);
    return colorize("  \u2502 ", C.dim, noColor) + content + " ".repeat(pad) + colorize(" \u2502", C.dim, noColor);
  }
  const out: string[] = [];
  out.push("");
  out.push(colorize(top, C.dim, noColor));
  out.push(row(titleLine));
  out.push(row(subtitleLine));
  out.push(colorize(sep, C.dim, noColor));
  out.push(row(ctxRow));
  out.push(row(cwdLine));
  out.push(colorize(sep, C.dim, noColor));
  out.push(row(slashLine));
  out.push(row(historyLine));
  out.push(colorize(bottom, C.dim, noColor));
  out.push("");
  return out.join("\n");
}

function stripAnsi(s: string): string {
  return s.replace(/\x1b\[[0-9;]*m/g, "");
}

interface ToolTrack {
  name: string;
  argsDigest: string;
  startedAt: number;
}

function toolStartLine(t: ToolTrack, noColor: boolean): string[] {
  const cat = categorizeLine(t.name);
  const icon = CAT_ICON[cat];
  const nameCol = CAT_COLOR[cat];
  const header: string[] = [];
  header.push(colorize("  ", C.dim, noColor));
  header.push(colorize(icon, nameCol, noColor));
  header.push(colorize(" tool: ", C.dim, noColor));
  header.push(colorize(t.name, C.bold, noColor));
  if (t.argsDigest) header.push(colorize("  " + t.argsDigest, C.dim, noColor));
  // R346: emit the first spinner frame on the same line as the
  // header so the user gets "what is running" + a tiny motion
  // indicator in a single visual unit. The frame is based on
  // Date.now() so successive tool starts rotate through the
  // glyphs instead of all starting on the same ⠋.
  const frame = spinnerFrame(Date.now());
  header.push(colorize("  ", C.dim, noColor));
  header.push(colorize(frame + " running\u2026", C.dim, noColor));
  return [header.join("")];
}

function toolDoneLine(t: ToolTrack, isErr: boolean, resultPreview: string, noColor: boolean): string[] {
  const cat = categorizeLine(t.name);
  const icon = CAT_ICON[cat];
  const nameCol = CAT_COLOR[cat];
  const out: string[] = [];
  out.push(colorize("  ", C.dim, noColor) + colorize(icon, nameCol, noColor) + " " +
           colorize(isErr ? "\u2717" : "\u2713", isErr ? C.red : C.green, noColor) +
           colorize(` tool: ${t.name}`, C.dim, noColor) +
           colorize(`  ${fmtDuration(Date.now() - t.startedAt)}`, C.dim, noColor));
  if (resultPreview) {
    for (const line of resultPreview.split("\n")) {
      out.push(colorize("    \u2502 ", C.dim, noColor) + renderInlineMarkdown(line, noColor));
    }
  }
  return out;
}

// R352: readOneChar (A/Y/D/N keystroke model) has been replaced by
// pickFromList + pickPermission. The picker takes over stdin briefly,
// draws an arrow-key selector with 1/2/3/4 hotkeys, and returns
// the user's choice. Esc cancels (= last option, which is "deny"
// for permissions — the safe default).

/**
 * R352c: serialize picker invocations so a model that fires N
 * permission requests in rapid succession doesn't spawn N concurrent
 * pickers (which previously stacked visually because each picker's
 * anchor only covered its own row band). Each pickPermission runs
 * one-at-a-time; queue depth is unbounded.
 */
let pickerChain: Promise<unknown> = Promise.resolve();
function runPicker<T>(fn: () => Promise<T>): Promise<T> {
  const next = pickerChain.then(() => fn());
  // Keep the chain alive even if a picker throws — the next picker
  // should still run.
  pickerChain = next.catch(() => undefined);
  return next;
}

/**
 * R352c: arrow-key dedup. Once `readline.emitKeypressEvents` is on,
 * Node fires BOTH a `keypress` event (with `{name: 'up'}` etc.) and
 * a `data` event (with the raw `\x1b[A` / `\x1b[B` bytes) for the
 * same keystroke. If both handlers act, one press = two jumps. We
 * gate both call sites through a 100ms cooldown so whichever fires
 * first wins; the second one no-ops.
 */
function makeArrowDedup(): () => boolean {
  let last = 0;
  return () => {
    const now = Date.now();
    if (now - last < 100) return false;
    last = now;
    return true;
  };
}

/**
 * R352: generic list picker. Takes over stdin while active, draws a
 * numbered list with a `❯` highlight, lets the user move the highlight
 * with ↑/↓ (or `1`..`N` for direct pick), and confirms with Enter /
 * Space. Esc cancels (returns the LAST option — typically "deny" or
 * "cancel" — so an accidental Esc is the safe default).
 *
 * The picker pauses LineInput's data listener so the user's keystrokes
 * don't leak into the input buffer; resume() is called on decide().
 *
 * In non-TTY mode (piped / CI) the picker resolves immediately to `null`
 * so the caller can fall back to its non-interactive default.
 */
async function pickFromList<T>(
  lineInput: LineInput,
  options: { label: string; value: T }[],
  initialIdx: number,
  title: string,
  hint: string,
  noColor: boolean,
): Promise<{ idx: number; value: T } | null> {
  if (!process.stdout.isTTY || options.length === 0) return null;

  lineInput.pause();

  return new Promise((resolvePromise) => {
    let selected = Math.max(0, Math.min(initialIdx, options.length - 1));
    let resolved = false;

    // R353: absolute-row positioning replaces the previous `\x1b[s/u`
    // save/restore anchor. The picker paints starting one row above
    // the input area (so the input prompt itself sits below the
    // picker's bottom border). lineInput.getInputTopRow() returns the
    // current input area's top row (0 if unknown — fallback below).
    const pickerTopRow = Math.max(1, lineInput.getInputTopRow() - 1);
    let firstRender = true;

    function render(): void {
      if (resolved) return;
      // R357: previously we used absolute CUP (`\x1b[R;CH`) + clear-to-end
      // (`\x1b[J`) to repaint the box. The user reports the picker still
      // "appends a new block below the previous one" on every arrow
      // press, even after R356's double-call toggle of SetConsoleMode
      // forced conhost to apply VT_PROCESSING. That suggests conhost
      // in the user's cmd.exe + Node 24 + koffi setup does NOT honor
      // absolute CUP / clear-to-end in the way we need.
      //
      // R357 switch: keep the box visual but repaint it with **relative**
      // cursor-up sequences (`\x1b[A`) + line-clear (`\x1b[K`).
      // `\x1b[A` is a single-row CUD/CUU primitive that doesn't depend
      // on absolute row counting and works on every VT-conformant
      // terminal including cmd.exe's default raster conhost. `\x1b[K`
      // (Erase in Line) only clears from cursor to end of the CURRENT
      // line — also rock-solid across all terminals.
      //
      // firstRender tracks whether we've already drawn the box once;
      // on subsequent renders we move the cursor up by the box height
      // and repaint each line. Total box height is options.length + 3
      // (top border + options + hint + bottom border).
      const boxH = options.length + 3;
      if (!firstRender) {
        // Walk the cursor up to the top border, then redraw.
        for (let i = 0; i < boxH; i++) {
          process.stdout.write("\x1b[A"); // CUU 1
        }
      }
      firstRender = false;

      const innerW = Math.max(56, stripAnsi(title).length + 4);
      const padTop = Math.max(0, innerW - stripAnsi(title).length - 4);
      const top = colorize(
        "  \u256D\u2500 " + title + " " + "\u2500".repeat(padTop) + "\u2500\u256E",
        C.dim,
        noColor,
      );
      const bottom = colorize(
        "  \u2570" + "\u2500".repeat(innerW + 2) + "\u256F",
        C.dim,
        noColor,
      );
      // Each line ends with \x1b[K (clear from cursor to end of line)
      // so the previous frame's longer lines don't bleed into the new
      // shorter frame (e.g. when an option label shrinks after
      // repaint).
      process.stdout.write(top + "\x1b[K\n");
      for (let i = 0; i < options.length; i++) {
        const opt = options[i];
        const isSel = i === selected;
        const arrow = isSel ? "> " : "  ";
        const labelColor = isSel ? C.yellowBright : C.dim;
        const arrowColor = isSel ? C.yellowBright : C.dim;
        process.stdout.write(
          colorize("  \u2502 ", C.dim, noColor) +
          colorize(arrow, arrowColor, noColor) +
          colorize(opt.label, labelColor, noColor) +
          "\x1b[K\n",
        );
      }
      process.stdout.write(
        colorize("  \u2502 ", C.dim, noColor) + colorize(hint, C.dim, noColor) + "\x1b[K\n",
      );
      process.stdout.write(bottom + "\x1b[K\n");
    }

    function cleanup(): void {
      process.stdin.removeListener("data", onData);
      process.stdin.removeListener("keypress", onKeypress as never);
      try {
        (process.stdin as unknown as { setRawMode: (b: boolean) => void }).setRawMode(false);
      } catch { /* ignore */ }
    }

    function decide(idx: number): void {
      if (resolved) return;
      resolved = true;
      cleanup();
      // R353: resume() → start() → render() uses CUP + clear-to-end
      // to repaint the prompt at lineInput.getInputTopRow(). That
      // automatically wipes whatever the picker drew above. We do
      // NOT also do `\x1b[u/J` here — the previous code did, and it
      // double-cleared everything (including the just-painted
      // prompt), which on cmd.exe often left artifacts.
      lineInput.resume();
      resolvePromise({ idx, value: options[idx].value });
    }

    function cancel(): void {
      // Default to last option (typically "deny" / "cancel" so Esc
      // is the safe choice).
      decide(options.length - 1);
    }

    // R352b: same fix as pickPermission — accumulate partial CSI
    // sequences across data events because cmd.exe tends to deliver
    // the 3 bytes of an arrow key one at a time.
    // R352c: arrow handling goes through a 100ms dedup because the
    // keypress event AND the raw data event fire for the same press.
    // R357-DIAG: log onData raw bytes + escBuf progress to verify
    // cmd.exe is actually delivering arrow bytes (and how they're
    // chunked).
    const diag = (() => {
      if (process.env.AETHERCODE_TUI_PICKER_DIAG === "0") return (_msg: string): void => {};
      return (msg: string): void => {
        try {
          require("node:fs").appendFileSync(
            "D:\\tmp\\ac-tui-picker-diag.log",
            `[${new Date().toISOString()}] pickFromList: ${msg}\n`,
          );
        } catch { /* ignore */ }
      };
    })();
    let escBuf = "";
    const shouldHandleArrow = makeArrowDedup();
    const onData = (chunk: Buffer | string): void => {
      if (resolved) return;
      const s = String(chunk);
      diag(`onData bytes=${JSON.stringify(s)} escBuf=${JSON.stringify(escBuf)}`);
      if (s.length === 0) return;
      for (let i = 0; i < s.length; i++) {
        const c = s[i];
        if (escBuf || c === "\x1b") {
          escBuf += c;
          if (escBuf === "\x1b[A") {
            const allow = shouldHandleArrow();
            diag(`match Up shouldHandleArrow=${allow}`);
            if (allow) {
              selected = (selected - 1 + options.length) % options.length;
              render();
            }
            escBuf = "";
            continue;
          }
          if (escBuf === "\x1b[B") {
            const allow = shouldHandleArrow();
            diag(`match Down shouldHandleArrow=${allow}`);
            if (allow) {
              selected = (selected + 1) % options.length;
              render();
            }
            escBuf = "";
            continue;
          }
          if (escBuf === "\x1b[5~" || escBuf === "\x1b[6~" ||
              escBuf === "\x1b[H"  || escBuf === "\x1b[F") {
            escBuf = "";
            continue;
          }
          if (escBuf.length >= 3) {
            const lastCode = escBuf.charCodeAt(escBuf.length - 1);
            if (lastCode >= 0x40 && lastCode <= 0x7E) {
              escBuf = "";
              continue;
            }
          }
          if (escBuf.length > 8) escBuf = "";
          continue;
        }

        const num = parseInt(c, 10);
        if (Number.isFinite(num) && num >= 1 && num <= options.length) {
          decide(num - 1);
          return;
        }
        if (c === "\r" || c === "\n" || c === " ") {
          decide(selected);
          return;
        }
        if (c === "\x1b") {
          escBuf = "\x1b";
          setTimeout(() => {
            if (escBuf === "\x1b" && !resolved) {
              escBuf = "";
              cancel();
            }
          }, 50);
          continue;
        }
      }
    };

    const onKeypress = (_s: string, k: { name?: string }): void => {
      if (resolved) return;
      const n = k.name ?? "";
      diag(`onKeypress name=${n}`);
      if (n === "up") {
        const allow = shouldHandleArrow();
        diag(`keypress Up shouldHandleArrow=${allow}`);
        if (allow) {
          selected = (selected - 1 + options.length) % options.length;
          render();
        }
      } else if (n === "down") {
        const allow = shouldHandleArrow();
        diag(`keypress Down shouldHandleArrow=${allow}`);
        if (allow) {
          selected = (selected + 1) % options.length;
          render();
        }
      } else if (n === "return" || n === "enter") {
        decide(selected);
      } else if (n === "escape") {
        cancel();
      }
    };

    // R353: no `\x1b[s` here — pickerTopRow is captured in JavaScript
    // at Promise construction time and render() positions itself via
    // CUP (see pickerTopRow declaration above). This makes the
    // picker robust to cmd.exe's flaky DECSC/DECRC save/restore.
    render();
    process.stdin.on("data", onData);
    process.stdin.on("keypress", onKeypress as never);
    try {
      (process.stdin as unknown as { setRawMode: (b: boolean) => void }).setRawMode(true);
      // R352b: enable the keypress parser so arrow keys arrive as
      // named events (`up` / `down`) rather than leaking the raw
      // 3-byte CSI sequence to the data listener. Required because
      // we don't go through readline.createInterface().
      readline.emitKeypressEvents(process.stdin);
    } catch { /* ignore */ }

    // R354c: Node's setRawMode(true) may or may not set
    // ENABLE_VIRTUAL_TERMINAL_INPUT and may or may not clear
    // ENABLE_ECHO_INPUT on the underlying conhost. Re-asserting the
    // full raw + VT mode here via koffi/SetConsoleMode is the only
    // way to be sure conhost won't echo arrow keys as `^[A` / `^[B`.
    lineInput.ensureRawMode();

    // 5-minute ceiling — same default as the old readOneChar.
    setTimeout(() => {
      if (!resolved) cancel();
    }, 300_000);
  });
}

/**
 * R352: permission prompt as a picker. Replaces the old
 * readOneChar A/Y/D/N keystroke model with an arrow-key selector
 * the user can navigate, plus 1/2/3/4 hotkeys and Enter / Esc.
 *
 * Renders a box with: header (risk + tool), input digest, reason,
 * one-line guidance, then the 4 options with the current selection
 * highlighted by a `❯` arrow. The box is static except for the
 * option highlight, which redraws on arrow keys.
 *
 * Esc and timeout both fall through to "deny" (the last option),
 * matching the old behaviour where waiting 5 minutes denied by
 * default.
 */
async function pickPermission(
  lineInput: LineInput,
  risk: string,
  tool: string,
  argDigest: string,
  reason: string,
  guidance: string,
  noColor: boolean,
): Promise<"allow" | "always_allow" | "deny" | "always_deny"> {
  if (!process.stdout.isTTY) return "deny";

  const options = [
    { label: "[1] A \u2014 allow once",                  value: "allow" as const },
    { label: "[2] Y \u2014 always for this session",     value: "always_allow" as const },
    { label: "[3] D \u2014 deny once",                   value: "deny" as const },
    { label: "[4] N \u2014 always deny",                 value: "always_deny" as const },
  ];

  const riskColor =
    risk === "CRITICAL" ? C.red :
    risk === "HIGH"     ? C.yellowBright :
                          C.yellow;

  lineInput.pause();

  return new Promise((resolvePromise) => {
    let selected = 0;
    let resolved = false;

    // R353: capture the input area's top row up-front (the picker
    // paints one row above it). Same pattern as pickFromList.
    // If getInputTopRow() returns 0 (unknown), we fall back to 1
    // and accept that the picker may overlap content above — the
    // alternatives (query cursor via DSR, hard-clear screen) are
    // either async or destructive, and line.ts always calls
    // setInputTopRow after the splash, so this fallback only
    // matters if a caller forgets.
    const pickerTopRow = Math.max(1, lineInput.getInputTopRow() - 1);
    let firstRender = true;

    function render(): void {
      if (resolved) return;
      // R357: see pickFromList render() above for the full rationale —
      // switch from absolute CUP + clear-to-end to relative cursor-up +
      // line-clear for cmd.exe reliability.
      const lines: string[] = [];

      // Static context: tool + risk + reason + guidance.
      // R356: reason / argDigest / guidance now go through
      // renderInlineMarkdown for style consistency with LLM output text.
      // (See pickFromList for the dim-re-emit subtlety comment.)
      const styledLine = (raw: string): string => {
        if (noColor) return raw;
        const rendered = renderInlineMarkdown(raw, noColor);
        return C.dim + rendered.replace(/\x1b\[0m/g, "\x1b[0m\x1b[2m") + "\x1b[0m";
      };

      lines.push(
        colorize("  \u2502  ", C.dim, noColor) +
        colorize("\u26A0 PERMISSION REQUIRED ", riskColor, noColor) +
        colorize(`[${risk}]`, C.bold, noColor) +
        colorize("  \u2014  ", C.dim, noColor) +
        colorize(`tool: ${tool}`, C.bold, noColor),
      );
      if (argDigest) {
        lines.push(
          colorize("  \u2502  ", C.dim, noColor) +
          colorize("input: ", C.dim, noColor) +
          styledLine(argDigest),
        );
      }
      if (reason) {
        lines.push(
          colorize("  \u2502  ", C.dim, noColor) +
          colorize("reason: ", C.dim, noColor) +
          styledLine(reason),
        );
      }
      lines.push(
        colorize("  \u2502  ", C.dim, noColor) +
        colorize("\u2937 ", C.dim, noColor) +
        styledLine(guidance),
      );

      // Options with the highlighted one.
      for (let i = 0; i < options.length; i++) {
        const opt = options[i];
        const isSel = i === selected;
        const arrow = isSel ? "> " : "  ";
        const color = isSel ? C.yellowBright : C.dim;
        lines.push(
          colorize("  \u2502 ", C.dim, noColor) +
          (isSel ? colorize(arrow, C.yellowBright, noColor) : colorize(arrow, C.dim, noColor)) +
          colorize(opt.label, color, noColor),
        );
      }
      lines.push(
        colorize("  \u2502 ", C.dim, noColor) +
        colorize("\u2191/\u2193 move  \u00B7  1/2/3/4  \u00B7  Enter  \u00B7  Esc deny", C.dim, noColor),
      );

      // Repaint: walk up to top, then redraw each line + clear remainder.
      // pickPermission's original design (R352) is a simple per-line
      // `│` prefix with NO top/bottom border — keep that, R357 only
      // changes the repaint strategy (relative CUU + EL).
      if (!firstRender) {
        for (let i = 0; i < lines.length; i++) {
          process.stdout.write("\x1b[A"); // CUU 1
        }
      }
      firstRender = false;
      for (const ln of lines) {
        process.stdout.write(ln + "\x1b[K\n");
      }
    }

    function cleanup(): void {
      process.stdin.removeListener("data", onData);
      process.stdin.removeListener("keypress", onKeypress as never);
      try {
        (process.stdin as unknown as { setRawMode: (b: boolean) => void }).setRawMode(false);
      } catch { /* ignore */ }
    }

    function decide(idx: number): void {
      if (resolved) return;
      resolved = true;
      cleanup();
      // R353: lineInput.resume() → start() → render() uses CUP +
      // clear-to-end to repaint the prompt at inputTopRow. The
      // picker's content lives above inputTopRow, so it stays on
      // screen UNLESS the resume render's clear-to-end sweeps it
      // away — which it does, because CUP to inputTopRow + clear
      // from cursor to end-of-screen also wipes anything between
      // pickerTopRow (= inputTopRow - 1) and screen bottom.
      lineInput.resume();
      resolvePromise(options[idx].value);
    }

    function cancel(): void { decide(options.length - 1); }

    // R352b: cmd.exe (and any TTY that doesn't have the keypress
    // parser pre-enabled) tends to deliver the 3 bytes of an arrow
    // key's CSI sequence as SEPARATE data events — so the strict
    // `s === "\x1b[A"` check rarely matches. We accumulate partial
    // sequences across events instead. The keypress listener is
    // also wired (see emitKeypressEvents below) for terminals where
    // it does work, and is the preferred path for arrow keys.
    // R352c: arrow handling is gated through `shouldHandleArrow`,
    // which dedups across keypress + data firing on the same press.
    // R357-DIAG: same as pickFromList — log onData raw bytes.
    const diag = (() => {
      if (process.env.AETHERCODE_TUI_PICKER_DIAG === "0") return (_msg: string): void => {};
      return (msg: string): void => {
        try {
          require("node:fs").appendFileSync(
            "D:\\tmp\\ac-tui-picker-diag.log",
            `[${new Date().toISOString()}] pickPermission: ${msg}\n`,
          );
        } catch { /* ignore */ }
      };
    })();
    let escBuf = "";
    const shouldHandleArrow = makeArrowDedup();
    const onData = (chunk: Buffer | string): void => {
      if (resolved) return;
      const s = String(chunk);
      diag(`onData bytes=${JSON.stringify(s)} escBuf=${JSON.stringify(escBuf)}`);
      if (s.length === 0) return;
      // Drive one byte at a time so partial ESC sequences assemble
      // across events. We avoid \r\n being split mid-pair by handling
      // each char individually.
      for (let i = 0; i < s.length; i++) {
        const c = s[i];
        // Begin or continue an ESC sequence.
        if (escBuf || c === "\x1b") {
          escBuf += c;
          // Up / Down arrow (CSI A / B).
          if (escBuf === "\x1b[A") {
            const allow = shouldHandleArrow();
            diag(`match Up shouldHandleArrow=${allow}`);
            if (allow) {
              selected = (selected - 1 + options.length) % options.length;
              render();
            }
            escBuf = "";
            continue;
          }
          if (escBuf === "\x1b[B") {
            const allow = shouldHandleArrow();
            diag(`match Down shouldHandleArrow=${allow}`);
            if (allow) {
              selected = (selected + 1) % options.length;
              render();
            }
            escBuf = "";
            continue;
          }
          // Page Up / Page Down / Home / End — ignore but consume.
          if (escBuf === "\x1b[5~" || escBuf === "\x1b[6~" ||
              escBuf === "\x1b[H"  || escBuf === "\x1b[F") {
            escBuf = "";
            continue;
          }
          // CSI sequences end at a letter in 0x40-0x7E. Anything
          // past that means it's a stale buffer — discard.
          if (escBuf.length >= 3) {
            const lastCode = escBuf.charCodeAt(escBuf.length - 1);
            if (lastCode >= 0x40 && lastCode <= 0x7E) {
              escBuf = "";
              continue;
            }
          }
          // Bail out if the buffer grows without terminating.
          if (escBuf.length > 8) escBuf = "";
          continue;
        }

        const num = parseInt(c, 10);
        if (Number.isFinite(num) && num >= 1 && num <= options.length) {
          decide(num - 1);
          return;
        }
        if (c === "\r" || c === "\n" || c === " ") {
          decide(selected);
          return;
        }
        // Bare ESC starts a sequence; if no follow-up byte arrives
        // within 50ms, treat it as the Escape key alone (= cancel).
        if (c === "\x1b") {
          escBuf = "\x1b";
          setTimeout(() => {
            if (escBuf === "\x1b" && !resolved) {
              escBuf = "";
              cancel();
            }
          }, 50);
          continue;
        }
      }
    };

    const onKeypress = (_s: string, k: { name?: string }): void => {
      if (resolved) return;
      const n = k.name ?? "";
      diag(`onKeypress name=${n}`);
      if (n === "up") {
        const allow = shouldHandleArrow();
        diag(`keypress Up shouldHandleArrow=${allow}`);
        if (allow) {
          selected = (selected - 1 + options.length) % options.length;
          render();
        }
      } else if (n === "down") {
        const allow = shouldHandleArrow();
        diag(`keypress Down shouldHandleArrow=${allow}`);
        if (allow) {
          selected = (selected + 1) % options.length;
          render();
        }
      } else if (n === "return" || n === "enter") {
        decide(selected);
      } else if (n === "escape") {
        cancel();
      }
    };

    // R353: no `\x1b[s` here — pickerTopRow (captured up-front in JS)
// is what render() uses. See pickerTopRow declaration above.
    render();
    process.stdin.on("data", onData);
    process.stdin.on("keypress", onKeypress as never);
    try {
      (process.stdin as unknown as { setRawMode: (b: boolean) => void }).setRawMode(true);
      // R352b: explicitly enable the keypress parser so `up` /
      // `down` show up as named events (instead of leaking raw
      // `\x1b[A` / `\x1b[B` text). Required because LineInput replaces
      // the readline.Interface that would normally enable this.
      readline.emitKeypressEvents(process.stdin);
    } catch { /* ignore */ }

    // R354c: re-assert raw + VT mode via koffi so conhost doesn't
    // echo arrow keys as `^[A` / `^[B`. See pickFromList above for
    // the long version.
    lineInput.ensureRawMode();

    setTimeout(() => { if (!resolved) cancel(); }, 300_000);
  });
}

/**
 * R352: mode picker. Replaces the "/mode <NAME>" type-the-name
 * flow with an arrow-key selector over the available modes.
 * Current mode is pre-selected; Esc cancels.
 */
async function pickMode(
  lineInput: LineInput,
  currentMode: string,
  setMode: (mode: string) => Promise<unknown>,
  logAbove: (text: string) => void,
  noColor: boolean,
): Promise<void> {
  const modes = [
    { label: "DEFAULT              \u2014 ask before each tool call",   value: "DEFAULT" },
    { label: "ACCEPT_TASK          \u2014 auto-allow all tools",         value: "ACCEPT_TASK" },
    { label: "ACCEPT_EDITS         \u2014 auto-allow file edits only",   value: "ACCEPT_EDITS" },
    { label: "BYPASS_PERMISSIONS   \u2014 no prompts, danger!",          value: "BYPASS_PERMISSIONS" },
    { label: "PLAN                 \u2014 plan only, don't execute",     value: "PLAN" },
    { label: "AUTO_READ_ONLY       \u2014 auto-allow read-only tools",   value: "AUTO_READ_ONLY" },
  ];
  const initialIdx = modes.findIndex((m) => m.value === currentMode);
  const result = await pickFromList(
    lineInput,
    modes,
    initialIdx >= 0 ? initialIdx : 0,
    "Pick permission mode",
    "\u2191/\u2193 move  \u00B7  Enter select  \u00B7  Esc cancel",
    noColor,
  );
  if (!result) {
    logAbove(colorize("  (\u00B7 mode change cancelled)", C.dim, noColor));
    return;
  }
  try {
    await setMode(result.value);
    logAbove(colorize(`  \u2713 mode \u2192 ${result.value}`, C.green, noColor));
  } catch (e) {
    logAbove(
      colorize(`  \u2717 mode change failed: ${(e as Error).message}`, C.red, noColor),
    );
  }
}

export async function runLineMode(opts: LineOptions): Promise<number> {
  const noColor = opts.noColor || !process.stdout.isTTY;
  const client = new JsonRpcClient({
    jarPath: opts.jar,
    cwd: opts.cwd,
    javaBinary: opts.javaBin,
    jvmArgs: opts.jvmArgs,
    onNotification: () => undefined,
    onExit: (code) => {
      if (code !== 0 && code !== null) {
        process.stderr.write(`[ac-tui] daemon exited code=${code}\n`);
      }
    },
  });

  let displayState: DisplayState = { ...INITIAL_STATE };
  let sessionId = "\u2014";
  let model = opts.model ?? "\u2014";
  try {
    const st = (await client.request("getState")) as Record<string, unknown> | null | undefined;
    const obj = (st ?? {}) as Record<string, unknown>;
    sessionId = String(obj.sessionId ?? "\u2014");
    model = String(obj.model ?? model);
    displayState = {
      ...displayState,
      sessionId,
      model,
      permissionMode: String(obj.permissionMode ?? "DEFAULT"),
      contextWindow: Number(obj.contextWindow ?? 0) || 0,
      cwd: opts.cwd || process.cwd(),
    };
  } catch (e) {
    console.error(colorize(`failed to reach daemon: ${(e as Error).message}`, C.red, noColor));
    return 2;
  }

  let runState: "idle" | "streaming" | "running-tool" = "idle";
  let textBuffer = "";
  let textFlushTimer: NodeJS.Timeout | null = null;
  // R347: minimal turn buffer for /export. We don't try to be
  // exhaustive — just record every user prompt and the
  // assistant text_delta stream as Turn-shaped records so the
  // export helper can render Markdown. Power users can hit
  // /export and get something useful; the Ink TUI keeps the
  // richer turn model.
  type LineTurn = { id: number; role: "user" | "assistant"; text: string; ts: number };
  const turnsBuffer: LineTurn[] = [];
  let turnCounter = 0;
  let currentUserPrompt = "";
  let currentTool: ToolTrack | null = null;
  let lastToolWas = "\u2014";
  let lastToolDigest = "";

  function flushText(): void {
    if (textBuffer.length === 0) return;
    const text = textBuffer;
    textBuffer = "";
    logAbove(renderInlineMarkdown(text, noColor));
  }

  function scheduleFlush(delayMs = 80): void {
    if (textFlushTimer) clearTimeout(textFlushTimer);
    textFlushTimer = setTimeout(flushText, delayMs);
  }

  const onRunEnd = () => {
    sawRunEnd = true;
    inFlight = Math.max(0, inFlight - 1);
    if (!rlClosed) {
      lineInput.redraw();
    }
    tryExit();
  };
  (runLineMode as unknown as { _onRunEnd?: () => void })._onRunEnd = onRunEnd;

  client.setNotificationHandler((method: string, params: RpcValue | undefined) => {
    if (method === "stream_event") {
      const p = (params ?? {}) as { event?: Record<string, unknown> };
      const ev = p.event ?? {};
      const t = ev.type as string | undefined;
      if (t === "run_start") {
        runState = "streaming";
        textBuffer = "";
        if (typeof ev.model === "string") displayState.model = ev.model;
        if (typeof ev.permissionMode === "string") displayState.permissionMode = ev.permissionMode;
        // R346: divider + idle hint from the previous turn (if any)
        // before the new turn starts. We persist lastRunEndedAt in
        // displayState so the footer line can show "this turn took X".
        runStartedAt = Date.now();
        logAbove("");
        logAbove(renderDivider(60, true, noColor));
        logAbove(renderStatusLine(displayState, inFlight + 1, noColor));
        logAbove(colorize("  \u2502", C.dim, noColor));
      } else if (t === "text_delta") {
        textBuffer += String(ev.text ?? "");
        scheduleFlush();
      } else if (t === "tool_use_start") {
        if (textFlushTimer) { clearTimeout(textFlushTimer); textFlushTimer = null; }
        flushText();
        const name = String(ev.name ?? "?");
        const args = ev.input as unknown;
        currentTool = {
          name,
          argsDigest: inputDigest(args),
          startedAt: Date.now(),
        };
        runState = "running-tool";
        for (const line of toolStartLine(currentTool, noColor)) logAbove(line);
      } else if (t === "tool_result") {
        const isErr = Boolean(ev.isError);
        const name = String(ev.id ?? currentTool?.name ?? "?");
        const resultStr = typeof ev.result === "string" ? ev.result :
                         ev.result != null ? JSON.stringify(ev.result) : "";
        lastToolWas = name;
        lastToolDigest = resultDigest(resultStr, 2);
        const track = currentTool ?? { name, argsDigest: "", startedAt: Date.now() };
        const lines = toolDoneLine(track, isErr, lastToolDigest, noColor);
        for (const line of lines) logAbove(line);
        currentTool = null;
        runState = "streaming";
      } else if (t === "run_end") {
        if (textFlushTimer) { clearTimeout(textFlushTimer); textFlushTimer = null; }
        // R347: capture the full assistant text into the turn
        // buffer right BEFORE flushing so /export gets the
        // complete response (not just the unstreamed tail).
        const assistantText = textBuffer;
        flushText();
        runState = "idle";
        if (assistantText && assistantText.length > 0) {
          turnsBuffer.push({ id: ++turnCounter, role: "assistant", text: assistantText, ts: Date.now() });
        }
        const stopReason = String(ev.stopReason ?? "end_turn");
        const kind = classifyStopReason(stopReason);
        const tag = stopKindLabel(kind, stopReason);
        const color = stopKindColor(kind);
        logAbove(colorize(`  [${tag}]`, color, noColor) +
                 colorize(`  (reason: ${stopReason})`, C.dim, noColor));
        if (kind === "loop") {
          logAbove(colorize("  (the model repeated the same call or hit a long-running output; daemon stopped it)", C.dim, noColor));
        } else if (kind === "max_turns") {
          logAbove(colorize("  (the run hit the per-query turn cap; raise it with --max-turns, or narrow the prompt)", C.dim, noColor));
        } else if (kind === "error") {
          logAbove(colorize("  (check the daemon log on stderr for details)", C.dim, noColor));
        }
        const usage = (ev as { usage?: { input?: number; output?: number; costUsd?: number } }).usage;
        if (usage) {
          displayState.inputTokens = usage.input ?? displayState.inputTokens;
          displayState.outputTokens = usage.output ?? displayState.outputTokens;
        }
        // R346: turn footer — token totals + cost + wall-clock duration.
        // We compute duration from the run_start we recorded above so
        // the value matches the user's intuition (typing → first byte
        // is excluded; the model + tool work is what we measure).
        const turnDuration = runStartedAt > 0 ? Date.now() - runStartedAt : 0;
        if (usage || turnDuration > 0) {
          logAbove(renderRunFooter(usage ?? null, turnDuration, noColor));
        }
        // R346: idle hint pinned to the bottom of the turn. Lists the
        // total session uptime + last tool + last result digest so the
        // user can pick up where they left off without opening /state.
        const sessionUptime = fmtDuration(Date.now() - displayState.startedAt);
        const idleParts: string[] = [];
        idleParts.push(colorize("  \u00B7 ", C.dim, noColor));
        idleParts.push(colorize(`session ${sessionUptime}`, C.dim, noColor));
        if (lastToolWas && lastToolWas !== "\u2014") {
          idleParts.push(colorize("  \u00B7  ", C.dim, noColor));
          idleParts.push(colorize(`last: ${lastToolWas}`, C.dim, noColor));
        }
        if (lastToolDigest) {
          const oneLine = lastToolDigest.replace(/\n.*$/s, "").slice(0, 80);
          idleParts.push(colorize(`  ${oneLine}`, C.dim, noColor));
        }
        logAbove(idleParts.join(""));
        onRunEnd();
      } else if (t === "side_note") {
        // R346: render memory / side-note as a small bordered card so
        // it stands out from the regular text stream. We keep it dim
        // by default — just a touch of colour around the message —
        // and we only switch to the stronger card for the "memory"
        // kind because that's the signal the user actually cares
        // about (the daemon recorded a fact worth remembering).
        const msg = String(ev.message ?? "");
        const kind = String((ev as { kind?: string }).kind ?? "");
        if (kind === "memory") {
          logAbove(colorize("  \u250C\u2500 memory \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510", C.cyan, noColor));
          for (const line of msg.split(/\r?\n/)) {
            logAbove(colorize("  \u2502 ", C.cyan, noColor) + colorize(line, C.dim, noColor));
          }
          logAbove(colorize("  \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518", C.cyan, noColor));
        } else {
          logAbove(colorize(`  \u00B7 ${msg}`, C.dim, noColor));
        }
      } else if (t === "plan") {
        const items = Array.isArray(ev.items) ? ev.items : [];
        const lines = items.map((it, i) => colorize(`  ${String(i + 1).padStart(2, " ")}. `, C.dim, noColor) + renderInlineMarkdown(String(it), noColor));
        logAbove(colorize(`\u2731 Plan (${items.length} item${items.length === 1 ? "" : "s"}):`, C.cyan, noColor));
        for (const l of lines) logAbove(l);
      }
    } else if (method === "log") {
      const p = (params ?? {}) as { message?: string };
      logAbove(colorize(`  [log] ${p.message ?? ""}`, C.gray, noColor));
    } else if (method === "task_state") {
      const p = (params ?? {}) as { taskId?: string; status?: string };
      logAbove(colorize(`  [task ${p.taskId ?? "?"} \u2192 ${p.status ?? "?"}]`, C.dim, noColor));
    } else if (method === "permission_request") {
      const p = (params ?? {}) as Record<string, unknown>;
      const tool = String(p.tool ?? "(unknown)");
      const input = (p.input as Record<string, unknown>) ?? {};
      const reason = String(p.reason ?? "");
      const risk = String(p.riskLevel ?? "medium").toUpperCase();
      const requestId = String(p.requestId ?? "");
      const argDigest = inputDigest(input);
      const guidance = (() => {
        switch (risk) {
          case "CRITICAL":
            return tool === "bash" ? "this shell command can read, write, or delete anything on this machine" :
                   tool === "file_write" ? "this will overwrite or create a file on disk" :
                   `this is a ${tool} call that the daemon flagged as CRITICAL`;
          case "HIGH":
            return tool === "bash" ? "this shell command runs outside the sandbox" :
                   tool === "file_write" ? "this will create or change a file" :
                   `this is a ${tool} call that the daemon flagged as HIGH`;
          case "MEDIUM":
            return tool === "bash" ? "this shell command will run with your user permissions" :
                   tool === "file_read" ? "this reads the file contents" :
                   `this is a ${tool} call that the daemon flagged as MEDIUM`;
          default:
            return `this is a ${tool} call (LOW risk)`;
        }
      })();
      // R352: use the picker instead of the old logAbove + readOneChar
      // box. pickPermission draws its own context + option rows and
      // pauses LineInput internally.
      // R352c: runPicker serializes concurrent permission_request
      // notifications — when the model fires N tool calls in a row
      // they queue up rather than spawning N pickers that stack
      // visually.
      logAbove("");
      void (async () => {
        const decision = await runPicker(() => pickPermission(
          lineInput,
          risk,
          tool,
          argDigest,
          reason,
          guidance,
          noColor,
        ));
        if (!rlClosed) lineInput.redraw();
        try {
          await client.request("permissionResponse", {
            requestId,
            decision,
            reason: `line mode picked ${decision}`,
          });
        } catch (e) {
          logAbove(colorize(`permission reply failed: ${(e as Error).message}`, C.red, noColor));
        }
      })();
    }
  });

  process.stdout.write(renderSplash(displayState, noColor) + "\n");

  // R351: line mode now uses our own LineInput instead of Node's
  // built-in `readline.Interface`. The built-in's _refreshLine relies
  // on cursor-up working reliably for wrapped multi-byte input —
  // cmd.exe doesn't honor that, so every backspace on a wrapped
  // Chinese prompt left ghost rows on screen. The custom handler
  // counts visual cells (CJK = 2) and re-paints the whole input area
  // on every change.
  let rlClosed = false;
  const lineInput = new LineInput(
    {
      onSubmit: (submitted: string) => {
        if (submitted.endsWith("\\")) {
          pendingLines.push(submitted.slice(0, -1));
          if (process.stdout.isTTY) {
            // R346: show which continuation line we're on so a multi-line
            // paste is easy to follow. The first line uses the regular
            // ❯ prompt; subsequent continuations show … 2 / … 3 / …. The
            // dim dot before the row number is the same character as the
            // … prefix to keep the visual family consistent.
            const next = pendingLines.length + 1;
            const tag = next >= 10 ? `${next}` : `${next}`;
            const promptStr =
              colorize("\u2026 ", C.dim, noColor) +
              colorize(tag, C.cyan, noColor) +
              colorize(" ", C.dim, noColor);
            lineInput.setPrompt(promptStr);
            lineInput.clearBuffer();
            lineInput.redraw();
          }
          return;
        }
        pendingLines.push(submitted);
        submitPrompt();
      },
      onClose: () => {
        rlClosed = true;
        if (inFlight === 0) {
          tryExit();
        } else {
          setTimeout(() => {
            if (exitResolve) {
              const r = exitResolve;
              exitResolve = null;
              process.stderr.write("[ac-tui] in-flight query didn't finish in 5s; exiting\n");
              r(1);
            }
          }, 5000);
        }
      },
    },
    process.stdin.isTTY ?? false,
  );
  lineInput.setPrompt(colorize("\u276F ", C.yellowBright, noColor));
  lineInput.start();

  // R353: tell LineInput where the input area begins on screen.
  // render() positions the prompt at this row with CUP. Without
  // this, render() falls back to "paint at current cursor" which
  // works once (the very first paint) but breaks every subsequent
  // render — the prompt would drift down the screen by one row
  // per render because we never get back to the right anchor.
  const splashLines = Math.max(1, renderSplash(displayState, noColor).split("\n").length - 1);
  lineInput.setInputTopRow(splashLines + 1);  // +1 for the trailing "\n" we wrote

  let inFlight = 0;
  let sawRunEnd = false;
  // R346: wall-clock anchor for the currently-active turn. Set
  // on run_start, read on run_end. Zero before the first turn.
  let runStartedAt = 0;
  let exitResolve: ((code: number) => void) | null = null;
  const exitPromise = new Promise<number>((resolve) => { exitResolve = resolve; });

  function tryExit(): void {
    if (!rlClosed || !exitResolve) return;
    if (inFlight === 0) {
      const r = exitResolve;
      exitResolve = null;
      r(0);
    }
  }

  function logAbove(text: string): void {
    // R353: defer to LineInput.writeAbove, which holds the input
    // area's absolute screen row (inputTopRow) and uses CUP
    // (`\x1b[<row>;<col>H`) to clear the old prompt + buffer before
    // writing the log line. This replaces the R351b `process.stdout.write
    // + lineInput.redraw()` dance, which depended on the terminal
    // remembering a saved cursor via `\x1b[s/u` — a slot cmd.exe
    // would silently corrupt across many renders, leading to
    // 30+ rows of ghost text accumulating above the prompt after
    // a long Chinese input.
    if (process.stdout.isTTY) {
      lineInput.writeAbove(text);
    } else {
      process.stdout.write(text + "\n");
    }
  }

  let pendingLines: string[] = [];

  function submitPrompt(): void {
    const text = pendingLines.join("\n").trim();
    pendingLines = [];
    lineInput.setPrompt(colorize("\u276F ", C.yellowBright, noColor));
    lineInput.clearBuffer();
    if (!text) {
      if (!rlClosed) lineInput.redraw();
      return;
    }
    void handlePrompt(text);
  }

  async function handlePrompt(text: string): Promise<void> {
    // R347: capture the user's prompt into the turn buffer so
    // /export can render it. Slash commands are NOT captured —
    // they're meta, not conversation.
    currentUserPrompt = text;
    const slash = handleSlash(text);
    if (slash?.local === "__EXIT__") {
      // R350: do NOT reset `inFlight` here. The /demo handler
      // bumps it on entry and decrements on exit. If the user
      // sends /exit mid-demo (e.g. while piping a sequence),
      // we want the close handler to honour the in-flight demo
      // and wait for its 5s ceiling — not stomp on the demo's
      // async setTimeout chain by calling exit immediately.
      // The previous version reset inFlight to 0 here, which
      // caused the demo's pending `await sleep(...)` chains to
      // be cut off when stdin EOFs after /exit.
      lineInput.close();
      rlClosed = true;
      tryExit();
      return;
    }
    if (slash?.local === "__CLEAR__") {
      process.stdout.write("\x1b[2J\x1b[H");
      process.stdout.write(renderSplash(displayState, noColor) + "\n");
      // R353: hard-clear wipes the screen, so the input area's
      // anchor row resets to right after the re-printed splash.
      const splashLines = Math.max(1, renderSplash(displayState, noColor).split("\n").length - 1);
      lineInput.setInputTopRow(splashLines + 1);
      if (!rlClosed) lineInput.redraw();
      return;
    }
    if (slash?.local === "__HISTORY__") {
      logAbove(colorize("(history navigation is only available in the Ink TUI; use \u2191/\u2193 in a real terminal)", C.dim, noColor));
      return;
    }
    if (slash?.local === "__DEMO__") {
      // R346: register the demo as in-flight so the close handler
      // waits for the setTimeout chain to drain before exiting.
      // Without this, the runLineDemo sequence (300/700/.../3300ms)
      // gets cut off the moment stdin EOFs in pipe mode.
      inFlight++;
      try {
        await runLineDemo();
      } finally {
        inFlight = Math.max(0, inFlight - 1);
        if (!rlClosed) lineInput.redraw();
        tryExit();
      }
      return;
    }
    if (slash?.local?.startsWith("__EXPORT_MD__:") || slash?.local?.startsWith("__EXPORT_JSON__:")) {
      // R347: line-mode export. Mirrors the Ink TUI tui.tsx
      // handler but writes straight to disk and surfaces a
      // logAbove ack instead of a toast. Empty path = the
      // default `<cwd>/.aethercode/exports/session-<id>-<YYYYMMDD-HHMM>.{md,json}`.
      const isJson = slash.local.startsWith("__EXPORT_JSON__:");
      const userPath = slash.local.slice(isJson ? "__EXPORT_JSON__:".length : "__EXPORT_MD__:".length);
      inFlight++;
      try {
        const { exportToMarkdown, exportToJson, exportWithFrontmatter, defaultExportPath, writeExport } = await import("./exportScrollback.js");
        const { mkdirSync } = await import("node:fs");
        const path = userPath && userPath.length > 0
          ? userPath
          : defaultExportPath(opts.cwd || process.cwd(), displayState.sessionId, isJson ? "json" : "md");
        mkdirSync(path.replace(/[\\/][^\\/]+$/, ""), { recursive: true });
        const body = isJson
          ? exportToJson(turnsBuffer as unknown as Parameters<typeof exportToJson>[0])
          : exportWithFrontmatter(turnsBuffer as unknown as Parameters<typeof exportWithFrontmatter>[0], displayState.model, displayState.sessionId, { cwd: opts.cwd || process.cwd() });
        writeExport(path, body);
        logAbove(colorize(`  ✓ exported ${turnsBuffer.length} turns → ${path}`, C.green, noColor));
      } catch (e) {
        logAbove(colorize(`  ✗ export failed: ${(e as Error).message}`, C.red, noColor));
      } finally {
        inFlight = Math.max(0, inFlight - 1);
        if (!rlClosed) lineInput.redraw();
        tryExit();
      }
      return;
    }
    if (slash?.local && slash.local === SLASH_HELP) {
      logAbove(colorize(SLASH_HELP, C.dim, noColor));
      return;
    }
    if (slash?.local === "__PICK_MODE__") {
      // R352: bare `/mode` shows the picker. Pass the current mode
      // so the user lands on the right row.
      await pickMode(
        lineInput,
        displayState.permissionMode,
        async (mode: string) => {
          await client.request("setPermissionMode", { mode });
          displayState.permissionMode = mode;
        },
        logAbove,
        noColor,
      );
      if (!rlClosed) lineInput.redraw();
      return;
    }
    if (slash?.local) {
      logAbove(colorize(slash.local, C.dim, noColor));
      return;
    }
    if (slash?.rpcMethod) {
      try {
        const result = await client.request<unknown>(slash.rpcMethod, slash.rpcParams as RpcValue);
        logAbove(colorize(`${slash.rpcMethod} \u2192 ${JSON.stringify(result)}`, C.dim, noColor));
      } catch (e) {
        logAbove(colorize(`${slash.rpcMethod} failed: ${(e as Error).message}`, C.red, noColor));
      }
      return;
    }
    logAbove("");
    inFlight++;
    sawRunEnd = false;
    // R347: record the user turn. We push the user prompt
    // BEFORE awaiting the daemon so the export always has the
    // user's question even if the model never does.
    turnsBuffer.push({ id: ++turnCounter, role: "user", text: currentUserPrompt, ts: Date.now() });
    try {
      await client.request("query", { prompt: text });
    } catch (e) {
      logAbove(colorize(`query failed: ${(e as Error).message}`, C.red, noColor));
      inFlight--;
      if (!rlClosed) lineInput.redraw();
      tryExit();
    }
  }

  // The line / close event handlers were moved into the LineInput
  // constructor (onSubmit / onClose callbacks) above.

  return exitPromise.then(async (code) => {
    await client.stop();
    return code;
  });

  async function runLineDemo(): Promise<void> {
    const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));
    const say = (text: string, color: string = C.dim) => logAbove(colorize("  " + text, color, noColor));
    const sayMarkdown = (text: string) => logAbove("  " + renderInlineMarkdown(text, noColor));
    logAbove("");
    say("(demo) starting canned conversation", C.gray);
    sayMarkdown("use the file_read tool to look at package.json");
    await sleep(300);
    currentTool = { name: "file_read", argsDigest: "package.json", startedAt: Date.now() };
    for (const line of toolStartLine(currentTool, noColor)) logAbove(line);
    await sleep(400);
    {
      const track = currentTool ?? { name: "file_read", argsDigest: "package.json", startedAt: Date.now() };
      for (const l of toolDoneLine(track, false, resultDigest('{ "name": "aethercode-tui", "version": "0.2.1" }', 2), noColor)) {
        logAbove(l);
      }
      currentTool = null;
    }
    await sleep(400);
    sayMarkdown("Sure \u2014 let me read the manifest. The TUI is built on **Ink 5** (React for CLI) and bundles into ~2.3 MB.");
    await sleep(600);
    currentTool = { name: "file_write", argsDigest: "package.json", startedAt: Date.now() };
    for (const line of toolStartLine(currentTool, noColor)) logAbove(line);

    // R352: use the picker for the demo's permission step too.
    // Same flow as a real permission_request, just with hard-coded
    // MEDIUM / file_write / canned guidance.
    logAbove("");
    const decision = await pickPermission(
      lineInput,
      "MEDIUM",
      "file_write",
      "package.json",
      "demo",
      "this will create or change a file (demo)",
      noColor,
    );
    if (!rlClosed) lineInput.redraw();
    const decisionLabel = ({
      allow: "1\u2003allow once",
      always_allow: "2\u2003always for this session",
      deny: "3\u2004deny once",
      always_deny: "4\u2004always deny",
    } as const)[decision];
    say(`(demo) you picked [${decisionLabel}] \u2014 ${decision === "allow" || decision === "always_allow" ? "tool allowed" : "tool denied"}`, C.cyan);

    {
      const track = currentTool ?? { name: "file_write", argsDigest: "package.json", startedAt: Date.now() };
      const ok = decision === "allow" || decision === "always_allow";
      const resultText = ok ? "wrote 47 bytes to package.json" : "denied by user (demo)";
      for (const l of toolDoneLine(track, !ok, resultDigest(resultText, 1), noColor)) {
        logAbove(l);
      }
      currentTool = null;
    }
    await sleep(400);
    sayMarkdown("Updated the manifest and bumped the patch version. Ready for review.");
    logAbove(colorize("  [\u2713 ready]", C.green, noColor) +
             colorize("  (reason: end_turn)", C.dim, noColor));
    await sleep(400);
    say("(demo) conversation complete \u2014 restart to clear", C.gray);
    if (!rlClosed) lineInput.redraw();
    // R346: drain the setTimeout chain (longest step is ~3.3s)
    // before allowing the close handler to exit. Without this,
    // piped / stdin-EOF runs are cut off after the first step.
    await sleep(400);
  }
}