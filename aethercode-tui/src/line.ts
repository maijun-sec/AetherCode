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
// R369: the one table that says what this daemon cannot do, shared
// with `handleSlash` and with the R366 gate.
import { unsupportedReason, unsupportedMessage } from "./rpcCapabilities.js";
import { describeSentinel, intentForSentinel } from "./panelIntents.js";
// R370 — `/sessions` filters rows by cwd with the same comparator
// Ink uses. See src/cwdMatch.ts for the four ways string equality
// gets a path wrong.
import { partitionByCwd } from "./cwdMatch.js";
// R372: the messages→turns converter, shared with the Ink renderer.
import { replayTranscript } from "./transcriptHydrate.js";
// R373: the workflow empty-state copy, shared with the Ink panel so
// the two renderers cannot drift apart.
import { workflowEmptyCopy } from "./workflowRows.js";
// R375: the run-control vocabulary, shared with the Ink renderer so
// the two cannot disagree about what "stop" means.
import {
  RUN_STOP_METHOD,
  RUN_PAUSE_METHOD,
  runParams,
  runOutcome,
  runAlreadyInState,
  runNothingInFlight,
  type RunAction,
} from "./runControl.js";
// R374: the update panel's content, shared with the Ink panel.
import {
  buildUpdatePanel,
  daemonVersionOf,
  scalarRows,
  UPDATE_ENDPOINTS,
  type UpdateVerb,
} from "./updateInfo.js";
import { TUI_VERSION } from "./version.js";

export interface LineOptions {
  jar: string;
  cwd: string;
  javaBin: string;
  jvmArgs: string[];
  model?: string;
  noColor?: boolean;
}

const C = {
  reset: "\x1b[0m",
  bold: "\x1b[1m",
  dim: "\x1b[2m",
  italic: "\x1b[3m",
  underline: "\x1b[4m",
  red: "\x1b[31m",
  green: "\x1b[32m",
  yellow: "\x1b[33m",
  blue: "\x1b[34m",
  magenta: "\x1b[35m",
  cyan: "\x1b[36m",
  gray: "\x1b[90m",
  yellowBright: "\x1b[93m",
  cyanBright: "\x1b[96m",
  // R346: a few more reserved for new visual surfaces
  bgGray: "\x1b[100m", // used for permission prompt footer
  bgYellow: "\x1b[43m",
  bgBlue: "\x1b[44m",
} as const;

/** R346: tool-running spinner frames. The spinner is updated by
 *  `spinnerFrame(elapsedMs)`, which picks a frame based on the
 *  current millisecond clock — since the line renderer doesn't
 *  redraw in place, each new line prints the next frame and the
 *  user sees the sequence tick forward. */
const SPINNER_FRAMES = [
  "\u280B",
  "\u2819",
  "\u2838",
  "\u2834",
  "\u2826",
  "\u2827",
  "\u2807",
  "\u280F",
];
const SPINNER_PERIOD_MS = 120;
function spinnerFrame(elapsedMs: number): string {
  const idx = Math.floor(elapsedMs / SPINNER_PERIOD_MS) % SPINNER_FRAMES.length;
  return SPINNER_FRAMES[idx];
}

/** R346: short horizontal divider. width is the visible cell count
 *  AFTER the leading "  " indent and any prefix glyph. We use
 *  U+2500 (BOX DRAWINGS LIGHT HORIZONTAL) for a calmer look than
 *  the heavier U+2501. */
function renderDivider(
  width: number = 60,
  indent: boolean = true,
  noColor: boolean = false,
): string {
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
  if (r === "max_iterations" || r === "max_turns" || r === "max_tool_iterations")
    return "max_turns";
  if (r === "empty_input" || r === "empty") return "empty";
  if (r === "error" || r.startsWith("error_") || r.endsWith("_error")) return "error";
  return "ok";
}

function stopKindLabel(kind: StopKind, rawReason?: string): string {
  switch (kind) {
    case "loop":
      return "stopped — loop";
    case "max_turns":
      return "stopped — max turns";
    case "error":
      return "stopped — error";
    case "empty":
      return "empty input";
    case "ok":
      return "✓ ready";
    default:
      return `run_end: ${rawReason ?? "?"}`;
  }
}

function stopKindColor(kind: StopKind): string {
  switch (kind) {
    case "loop":
      return C.red;
    case "max_turns":
      return C.yellow;
    case "error":
      return C.red;
    case "empty":
      return C.gray;
    case "ok":
      return C.green;
    default:
      return C.dim;
  }
}

type LineCategory = "read" | "write" | "search" | "run" | "agent" | "other";

function categorizeLine(name: string | undefined | null): LineCategory {
  if (!name) return "other";
  const n = name.toLowerCase();
  if (
    n === "file_read" ||
    n === "read" ||
    n.startsWith("read_") ||
    n === "cat" ||
    n === "show" ||
    n === "view"
  )
    return "read";
  if (n === "glob" || n === "grep" || n === "search" || n.startsWith("search_") || n === "find")
    return "search";
  if (
    n === "file_write" ||
    n === "file_edit" ||
    n === "file_create" ||
    n.startsWith("write_") ||
    n.startsWith("edit_")
  )
    return "write";
  if (
    n === "bash" ||
    n === "shell" ||
    n === "exec" ||
    n === "run_command" ||
    n.startsWith("bash_") ||
    n === "test" ||
    n === "build"
  )
    return "run";
  if (
    n === "agent" ||
    n === "task" ||
    n === "delegate" ||
    n.startsWith("agent_") ||
    n.startsWith("task_")
  )
    return "agent";
  return "other";
}

const CAT_ICON: Record<LineCategory, string> = {
  read: "\u25CB",
  write: "\u270E",
  search: "\u25CE",
  run: "\u25B7",
  agent: "\u2756",
  other: "\u2692",
};
const CAT_COLOR: Record<LineCategory, string> = {
  read: C.blue,
  write: C.yellowBright,
  search: C.cyan,
  run: C.red,
  agent: C.magenta,
  other: C.gray,
};

function inputDigest(input: unknown): string {
  if (!input || typeof input !== "object") return "";
  const o = input as Record<string, unknown>;
  if (typeof o.command === "string") {
    const cmd = o.command.replace(/\s+/g, " ").trim();
    return cmd.length > 60 ? cmd.slice(0, 57) + "\u2026" : cmd;
  }
  if (typeof o.file_path === "string") return String(o.file_path);
  if (typeof o.path === "string") return String(o.path);
  if (typeof o.url === "string") return String(o.url);
  if (typeof o.pattern === "string") return `pattern=${o.pattern}`;
  if (typeof o.query === "string") return `q=${o.query}`;
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
      return { label: "ASK", bg: C.yellowBright, fg: C.bold };
    case "ACCEPT_TASK":
      return { label: "TASK", bg: C.green, fg: C.bold };
    case "ACCEPT_EDITS":
      return { label: "EDITS", bg: C.green, fg: C.bold };
    case "BYPASS_PERMISSIONS":
      return { label: "BYPASS", bg: C.red, fg: C.bold };
    case "PLAN":
      return { label: "PLAN", bg: C.magenta, fg: C.bold };
    case "AUTO_READ_ONLY":
      return { label: "READ", bg: C.cyan, fg: C.bold };
    default:
      return { label: m, bg: C.gray, fg: C.bold };
  }
}

function modePill(mode: string, noColor: boolean): string {
  const s = modeStyle(mode);
  if (noColor) return `[${s.label}]`;
  return `${s.bg}[${s.label}]${C.reset}`;
}

function ctxFillGlyph(
  inputTok: number | null,
  outputTok: number | null,
  ctxWindow: number,
): string {
  if (!ctxWindow || ctxWindow <= 0) return "";
  const total = (inputTok ?? 0) + (outputTok ?? 0);
  const ratio = Math.max(0, Math.min(1, total / ctxWindow));
  const cells = 8;
  const filled = Math.round(ratio * cells);
  const empty = cells - filled;
  const glyph = "\u2586".repeat(filled) + "\u2591".repeat(empty);
  const pct = Math.round(ratio * 100);
  const color = ratio > 0.8 ? C.red : ratio > 0.5 ? C.yellowBright : C.green;
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
        level === 1
          ? C.bold + C.cyanBright
          : level === 2
            ? C.bold + C.cyan
            : level === 3
              ? C.bold + C.magenta
              : C.bold + C.gray;
      const inlineBody = body; // heading text — keep literal (no bold/italic inside it for now)
      // Compute underline width: max(visible width of "level body", 8),
      // capped at terminal-friendly width.
      const visibleW = inlineBody.length + 2; // "  # " + body
      const uW = Math.min(RULE_W, Math.max(visibleW, 8));
      const underlineChar = level === 1 ? RULE_HEAVY : level === 2 ? RULE_LIGHT : null;
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
      const closeRe = new RegExp(
        `^${indent.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\s*${fenceChar === "`" ? "\\`" : "~"}{${fenceLen},}\\s*$`,
      );
      let j = i + 1;
      while (j < lines.length && !closeRe.test(lines[j])) j++;
      const codeLines = lines.slice(i + 1, j);
      const label = lang ? ` ${lang} ` : " ";
      const topFill = Math.max(0, RULE_W - label.length - 4);
      // Block spacing — code is its own group.
      if (lastKind !== "" && lastKind !== "code") pushSpace();
      out.push(
        colorize(`  \u256D\u2501${label}`, C.dim, noColor) +
          colorize(RULE_LIGHT.repeat(topFill) + "\u256E", C.dim, noColor),
      );
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
      out.push(
        (indent ? colorize(indent, C.reset, noColor) : "") +
          colorize("  \u25B8 ", C.cyan, noColor) +
          inlineSpan(content),
      );
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
      out.push(
        (indent ? colorize(indent, C.reset, noColor) : "") +
          colorize(`  ${num}. `, C.cyan, noColor) +
          inlineSpan(content),
      );
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
          const widths = [
            stripAnsi(h).length,
            ...dataRows.map((r) => stripAnsi(r[idx] ?? "").length),
          ];
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
        const renderCell = (
          content: string,
          w: number,
          align: "left" | "right" | "center" | null,
        ): string => padCell(content, w, align);
        const headerRow =
          "  " +
          colorize("\u2503", C.dim, noColor) +
          " " +
          headerCells
            .map((c, idx) =>
              colorize(
                renderCell(colorize(c, C.bold + C.cyan, noColor), colW[idx], aligns[idx]),
                C.reset,
                noColor,
              ),
            )
            .join(" " + colorize("\u2503", C.dim, noColor) + " ") +
          " " +
          colorize("\u2503", C.dim, noColor);
        out.push(headerRow);
        // Mid border ├─╮┼─┤.
        out.push(border("\u251C", "\u253C", "\u2524", RULE_LIGHT));
        // Data rows.
        for (const row of dataRows) {
          const cells = headerCells.map((_, idx) => row[idx] ?? "");
          const rowLine =
            "  " +
            colorize("\u2503", C.dim, noColor) +
            " " +
            cells
              .map((c, idx) => renderCell(inlineSpan(c), colW[idx], aligns[idx]))
              .join(" " + colorize("\u2503", C.dim, noColor) + " ") +
            " " +
            colorize("\u2503", C.dim, noColor);
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
    parts.push(
      colorize(` \u00B7 ${inFlight} tool${inFlight === 1 ? "" : "s"} in flight`, C.yellow, noColor),
    );
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
  const top = "  " + "\u256D" + "\u2500".repeat(innerWidth + 2) + "\u256E";
  const bottom = "  " + "\u2570" + "\u2500".repeat(innerWidth + 2) + "\u256F";
  const sep = "  " + "\u251C" + "\u2500".repeat(innerWidth + 2) + "\u2524";

  const titleLine =
    colorize("AetherCode", C.bold + C.cyanBright, noColor) + colorize(" v0.2.1", C.dim, noColor);
  const subtitleLine = colorize("type a prompt, press Enter", C.dim, noColor);
  const ctxLine =
    colorize("\u232C ", C.cyan, noColor) +
    colorize(state.model, C.bold, noColor) +
    colorize("  \u00B7  ", C.dim, noColor) +
    modePill(state.permissionMode, noColor);
  const sessionLine =
    colorize("# ", C.dim, noColor) + colorize(shortSession(state.sessionId), C.dim, noColor);
  // Compose the row so we can compute the padding as a single
  // visible-cell count. ctxLine + 2-space gap + sessionLine is
  // one logical row inside the box.
  const ctxRow = ctxLine + "  " + sessionLine;
  const cwdLine =
    colorize("\u21B5 ", C.cyan, noColor) +
    colorize(shortCwd(state.cwd || process.cwd(), 48), C.dim, noColor);
  const slashLine =
    colorize("/", C.yellowBright, noColor) +
    colorize("help", C.dim, noColor) +
    colorize(" \u00B7 ", C.dim, noColor) +
    colorize("/", C.yellowBright, noColor) +
    colorize("clear", C.dim, noColor) +
    colorize(" \u00B7 ", C.dim, noColor) +
    colorize("/", C.yellowBright, noColor) +
    colorize("exit", C.dim, noColor) +
    colorize(" \u00B7 ", C.dim, noColor) +
    colorize("/", C.yellowBright, noColor) +
    colorize("demo", C.dim, noColor) +
    colorize(" \u00B7 ", C.dim, noColor) +
    colorize("/", C.yellowBright, noColor) +
    colorize("mode", C.dim, noColor) +
    colorize(" \u00B7 ", C.dim, noColor) +
    colorize("/", C.yellowBright, noColor) +
    colorize("no-confirm", C.dim, noColor);
  const historyLine =
    colorize("\u2191/\u2193", C.cyan, noColor) +
    colorize(" history in Ink TUI  \u00B7  ", C.dim, noColor) +
    colorize("trailing", C.dim, noColor) +
    colorize(" \\", C.yellowBright, noColor) +
    colorize(" for multi-line", C.dim, noColor);

  function row(content: string): string {
    const used = stripAnsi(content).length;
    const pad = Math.max(0, innerWidth - used);
    return (
      colorize("  \u2502 ", C.dim, noColor) +
      content +
      " ".repeat(pad) +
      colorize(" \u2502", C.dim, noColor)
    );
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

/**
 * R362: pad a (possibly coloured) cell to `w` VISIBLE columns.
 *
 * Hoisted out of `renderInlineMarkdown` so the slash-command
 * panels below build their tables from the exact same alignment
 * primitive the GFM table renderer uses. One padder = the two
 * surfaces cannot drift apart on padding rules, which is the
 * failure mode that makes a "consistent" UI look inconsistent.
 */
function padCell(
  content: string,
  w: number,
  align: "left" | "right" | "center" | null = "left",
): string {
  const pad = w - stripAnsi(content).length;
  if (pad <= 0) return content;
  const left = align === "right" ? pad : align === "center" ? Math.floor(pad / 2) : 0;
  const right = align === "right" ? 0 : pad - left;
  return " ".repeat(left) + content + " ".repeat(right);
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

function toolDoneLine(
  t: ToolTrack,
  isErr: boolean,
  resultPreview: string,
  noColor: boolean,
): string[] {
  const cat = categorizeLine(t.name);
  const icon = CAT_ICON[cat];
  const nameCol = CAT_COLOR[cat];
  const out: string[] = [];
  out.push(
    colorize("  ", C.dim, noColor) +
      colorize(icon, nameCol, noColor) +
      " " +
      colorize(isErr ? "\u2717" : "\u2713", isErr ? C.red : C.green, noColor) +
      colorize(` tool: ${t.name}`, C.dim, noColor) +
      colorize(`  ${fmtDuration(Date.now() - t.startedAt)}`, C.dim, noColor),
  );
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
      const bottom = colorize("  \u2570" + "\u2500".repeat(innerW + 2) + "\u256F", C.dim, noColor);
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
      } catch {
        /* ignore */
      }
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
        } catch {
          /* ignore */
        }
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
          if (
            escBuf === "\x1b[5~" ||
            escBuf === "\x1b[6~" ||
            escBuf === "\x1b[H" ||
            escBuf === "\x1b[F"
          ) {
            escBuf = "";
            continue;
          }
          if (escBuf.length >= 3) {
            const lastCode = escBuf.charCodeAt(escBuf.length - 1);
            if (lastCode >= 0x40 && lastCode <= 0x7e) {
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
    } catch {
      /* ignore */
    }

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
    { label: "[1] A \u2014 allow once", value: "allow" as const },
    { label: "[2] Y \u2014 always for this session", value: "always_allow" as const },
    { label: "[3] D \u2014 deny once", value: "deny" as const },
    { label: "[4] N \u2014 always deny", value: "always_deny" as const },
  ];

  const riskColor = risk === "CRITICAL" ? C.red : risk === "HIGH" ? C.yellowBright : C.yellow;

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
          colorize(
            "\u2191/\u2193 move  \u00B7  1/2/3/4  \u00B7  Enter  \u00B7  Esc deny",
            C.dim,
            noColor,
          ),
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
      } catch {
        /* ignore */
      }
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

    function cancel(): void {
      decide(options.length - 1);
    }

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
        } catch {
          /* ignore */
        }
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
          if (
            escBuf === "\x1b[5~" ||
            escBuf === "\x1b[6~" ||
            escBuf === "\x1b[H" ||
            escBuf === "\x1b[F"
          ) {
            escBuf = "";
            continue;
          }
          // CSI sequences end at a letter in 0x40-0x7E. Anything
          // past that means it's a stale buffer — discard.
          if (escBuf.length >= 3) {
            const lastCode = escBuf.charCodeAt(escBuf.length - 1);
            if (lastCode >= 0x40 && lastCode <= 0x7e) {
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
    } catch {
      /* ignore */
    }

    // R354c: re-assert raw + VT mode via koffi so conhost doesn't
    // echo arrow keys as `^[A` / `^[B`. See pickFromList above for
    // the long version.
    lineInput.ensureRawMode();

    setTimeout(() => {
      if (!resolved) cancel();
    }, 300_000);
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
    { label: "DEFAULT              \u2014 ask before each tool call", value: "DEFAULT" },
    { label: "ACCEPT_TASK          \u2014 auto-allow all tools", value: "ACCEPT_TASK" },
    { label: "ACCEPT_EDITS         \u2014 auto-allow file edits only", value: "ACCEPT_EDITS" },
    { label: "BYPASS_PERMISSIONS   \u2014 no prompts, danger!", value: "BYPASS_PERMISSIONS" },
    { label: "PLAN                 \u2014 plan only, don't execute", value: "PLAN" },
    { label: "AUTO_READ_ONLY       \u2014 auto-allow read-only tools", value: "AUTO_READ_ONLY" },
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
    logAbove(colorize(`  \u2717 mode change failed: ${(e as Error).message}`, C.red, noColor));
  }
}

// =====================================================================
//  R362 — slash-command panels for line mode.
//
//  Before this block `handlePrompt` had a five-way if-chain and a
//  fallback that printed `slash.local` verbatim, so every sentinel
//  it did not recognise reached the user as literal text —
//  `__MEMORY_PANEL__:project` and friends. `SLASH_HELP` advertised
//  all of them.
//
//  This is line mode's answer to the same contract tui.tsx answers
//  with React components: no component tree, no hooks, no reducer —
//  just RPC calls turned into framed text by `renderPanel`.
//
//  Three rules govern everything below:
//
//    1. NEVER print a `__X__` token. Every unknown token exits
//       through `humanizeSentinelLine`, which asks the shared
//       `panelIntents` registry for a sentence first.
//    2. Reuse, don't reimplement. Selectors go through the
//       existing `pickFromList`; alignment goes through the
//       `padCell` the markdown table renderer uses; the box
//       characters are the ones R358 already proved render in
//       cmd.exe.
//    3. Say the truth about missing data. When the daemon has no
//       endpoint for a panel, name the endpoint and the reason.
//       A blank is a bug; a sentence with the reason is a feature.
// =====================================================================

const PANEL_DEFAULT_W = 80;
const PANEL_MIN_W = 56;
const PANEL_MAX_W = 96;

/** Terminal width for panels, clamped so tables never wrap. */
function panelWidth(): number {
  const cols = process.stdout.columns;
  if (typeof cols !== "number" || !Number.isFinite(cols) || cols <= 0) return PANEL_DEFAULT_W;
  return Math.max(PANEL_MIN_W, Math.min(PANEL_MAX_W, Math.floor(cols)));
}

function clipCell(s: string, w: number): string {
  const plain = stripAnsi(s);
  if (plain.length <= w) return s;
  if (w <= 1) return "\u2026";
  return plain.slice(0, w - 1) + "\u2026";
}

function wrapText(s: string, w: number): string[] {
  const out: string[] = [];
  for (const para of s.split(/\r?\n/)) {
    if (para.trim() === "") {
      out.push("");
      continue;
    }
    let line = "";
    for (const word of para.split(/\s+/).filter(Boolean)) {
      if (line === "") {
        line = word.length > w ? word.slice(0, w) : word;
      } else if (line.length + 1 + word.length <= w) {
        line += " " + word;
      } else {
        out.push(line);
        line = word.length > w ? word.slice(0, w) : word;
      }
    }
    if (line !== "") out.push(line);
  }
  return out;
}

/** `MM-DD HH:MM`, or an em dash when the timestamp is absent. */
function fmtStamp(ts: unknown): string {
  const n = typeof ts === "number" ? ts : Number(ts);
  if (!Number.isFinite(n) || n <= 0) return "\u2014";
  const d = new Date(n);
  const p = (x: number): string => String(x).padStart(2, "0");
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** First present field out of `names` — RPC payload shapes drift
 *  between daemon versions, so every read goes through this rather
 *  than hard-coding one key and silently rendering a column of
 *  em dashes when the daemon renames it. */
function field(o: unknown, names: string[]): unknown {
  if (!o || typeof o !== "object") return undefined;
  const r = o as Record<string, unknown>;
  for (const n of names) {
    if (r[n] !== undefined && r[n] !== null) return r[n];
  }
  return undefined;
}

function fieldStr(o: unknown, names: string[], dflt: string = "\u2014"): string {
  const v = field(o, names);
  if (typeof v === "string") return v.length > 0 ? v : dflt;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return dflt;
}

/** Tolerant array unwrap: a bare array, or the usual envelope keys.
 *
 * R366: `projects` and `workflows` were missing from this list, so
 * `panelCwdPick` and `panelWorkflow` both got `[]` from a SUCCESSFUL
 * call and rendered an empty panel with no error anywhere. A picker
 * that lists nothing is indistinguishable from a picker that has
 * nothing to list.
 *
 * The keys are matched against what the daemon actually returns:
 *   listSessions  -> { sessions: [...] }
 *   listAgents    -> { ok, count, agents: [...] }
 *   listProjects  -> { projects: [...], current }
 *   listWorkflows -> { workflows: [...], count, dir }
 *   grants/list   -> { grants: [...] }        (R367)
 *
 * `grants/list` is the sharp one. R367 made `grants/*` reachable for
 * the first time, and `GrantHandlers.list` answers `{grants: [...]}`
 * — NOT a bare array. Without "grants" in this list, `/consents`
 * rendered an empty panel with "no stored grants" as its empty-state
 * message and no error anywhere, even with grants on disk. So the fix
 * that made the endpoint honest would have made the panel lie: before
 * it, the user got an honest "endpoint missing" banner; after it, a
 * confident empty list. Wiring is only half a feature.
 *
 * `r366-rpc-names.test.mjs` pins each of those five against this
 * list, so a new envelope key has to be added here deliberately. */
function asRows(v: unknown): unknown[] {
  if (Array.isArray(v)) return v;
  if (v && typeof v === "object") {
    const r = v as Record<string, unknown>;
    for (const k of [
      "items",
      "entries",
      "rows",
      "results",
      "sessions",
      "agents",
      "servers",
      "todos",
      "data",
      "projects",
      "workflows",
      "grants",
    ]) {
      if (Array.isArray(r[k])) return r[k] as unknown[];
    }
  }
  return [];
}

// -------------------------------------------------------------------------
//  Panel table primitives
// -------------------------------------------------------------------------

type PanelTone = "plain" | "strong" | "ok" | "warn" | "err" | "muted";

interface PanelCell {
  text: string;
  tone?: PanelTone;
}

interface PanelColumn {
  header: string;
  align?: "left" | "right";
  /** Hard minimum visible width (default: the header's width, min 3). */
  min?: number;
  /** Hard maximum for a flexible column. */
  max?: number;
  /** Share of the leftover width. Default 1. */
  flex?: number;
}

const PANEL_TONE_COLOR: Record<PanelTone, string> = {
  plain: "",
  strong: C.bold,
  ok: C.green,
  warn: C.yellowBright,
  err: C.red,
  muted: C.dim,
};

function tone(s: string, t: PanelTone, noColor: boolean): string {
  const c = PANEL_TONE_COLOR[t];
  return c ? colorize(s, c, noColor) : s;
}

/**
 * Split the cell budget across the columns.
 *
 * Two kinds of column:
 *   - fixed  (`flex` unset or 0): sized to its CONTENT, the same
 *     way the GFM table renderer sizes columns (widest of header
 *     and cells). A label column should not have to share space
 *     with the prose beside it.
 *   - flexible (`flex: n`): starts at its minimum and absorbs the
 *     remaining width in proportion to `n`. This is the column
 *     that gets clipped with an ellipsis, so it is the one the
 *     author has decided can be truncated.
 *
 * Overhead is `3 * n + 2`: each column costs its own width plus a
 * one-space gutter on each side plus the `│` separator
 * (`n + (n + 1)` spaces/separators, `n + 2` for the leading indent
 * and the two outer bars), which is exactly the width the box
 * builder emits — so the frame and the rows always agree.
 */
function allocatePanelWidths(cols: PanelColumn[], rows: PanelCell[][], total: number): number[] {
  const n = cols.length;
  if (n === 0) return [];
  const budget = Math.max(n * 3, total - (3 * n + 2));
  // A single very long cell must not eat the table: cap any fixed
  // column at half the budget.
  const fixedCap = Math.max(12, Math.floor(budget / 2));

  const isFixed = cols.map((c) => (c.flex ?? 0) <= 0);
  const out = cols.map((c, i) => {
    const lo = Math.max(3, c.min ?? stripAnsi(c.header).length);
    const hi = c.max ?? Number.MAX_SAFE_INTEGER;
    if (!isFixed[i]) return lo;
    let natural = stripAnsi(c.header).length;
    for (const r of rows) natural = Math.max(natural, stripAnsi(r[i]?.text ?? "").length);
    return Math.max(lo, Math.min(hi, fixedCap, natural));
  });

  let used = out.reduce((a, b) => a + b, 0);
  const flex = cols
    .map((c, i) => ({ i, share: c.flex ?? 0, max: c.max ?? Number.MAX_SAFE_INTEGER }))
    .filter((x) => x.share > 0 && out[x.i] < x.max);

  if (used >= budget) {
    // Over-subscribed: shave flexible columns first (they are the
    // ones the author agreed can lose characters), then the fixed
    // ones, and never below 3 so a column never collapses to `…`.
    let overflow = used - budget;
    for (const x of flex.slice().sort((a, b) => out[b.i] - out[a.i])) {
      if (overflow <= 0) break;
      const take = Math.min(overflow, out[x.i] - 3);
      out[x.i] -= take;
      overflow -= take;
    }
    for (let i = 0; i < out.length && overflow > 0; i++) {
      if (!isFixed[i]) continue;
      const take = Math.min(overflow, out[i] - 3);
      out[i] -= take;
      overflow -= take;
    }
    return out;
  }

  let leftover = budget - used;
  const totalShare = flex.reduce((a, x) => a + x.share, 0) || 1;
  for (const x of flex) {
    const give = Math.max(
      0,
      Math.min(Math.floor((leftover * x.share) / totalShare), x.max - out[x.i]),
    );
    out[x.i] += give;
    leftover -= give;
  }
  // Hand the integer-division remainder to whichever column still
  // has room, so the table always fills the requested width.
  for (const x of flex) {
    if (leftover <= 0) break;
    const give = Math.min(leftover, x.max - out[x.i]);
    out[x.i] += give;
    leftover -= give;
  }
  return out;
}

interface PanelOpts {
  title: string;
  subtitle?: string;
  columns: PanelColumn[];
  rows: PanelCell[][];
  noColor: boolean;
  width?: number;
  /** Rendered inside the frame when `rows` is empty. */
  empty?: string[];
  /** Dim line printed under the box. */
  hint?: string;
  /** Right-aligned dim counter on the title line. */
  note?: string;
}

/**
 * Framed table. Same box vocabulary as the GFM table in
 * `renderInlineMarkdown` (R358) so a slash-command panel and a
 * markdown table in the same scrollback read as one system.
 */
function renderPanel(o: PanelOpts): string[] {
  const nc = o.noColor;
  const total = o.width ?? panelWidth();
  const out: string[] = [];

  // Title line — the heaviest weight in the block.
  let head = "  " + colorize("\u25B8 ", C.dim, nc) + colorize(o.title, C.bold + C.cyanBright, nc);
  if (o.subtitle) head += colorize("  \u00B7  " + o.subtitle, C.dim, nc);
  if (o.note) {
    const gap = total - stripAnsi(head).length - stripAnsi(o.note).length;
    if (gap >= 2) head += " ".repeat(gap) + colorize(o.note, C.dim, nc);
  }
  out.push(head);

  const width = allocatePanelWidths(o.columns, o.rows, total);
  const bar = (l: string, mid: string, r: string): string =>
    colorize("  " + l + width.map((w) => "\u2500".repeat(w + 2)).join(mid) + r, C.dim, nc);
  const pipe = colorize("\u2503", C.dim, nc);

  if (o.rows.length === 0) {
    // Empty state: a single full-width cell. Drawing the column
    // grid here would advertise headers ("# | status | item")
    // that no row ever fills in, which reads as a broken table
    // rather than an empty one. Same frame vocabulary, no columns.
    const inner = Math.max(24, width.reduce((a, b) => a + b, 0) + 3 * (width.length - 1));
    const fill = Math.max(0, total - 5);
    out.push(colorize("  \u256D\u2500" + "\u2500".repeat(fill) + "\u2500\u256E", C.dim, nc));
    for (const line of o.empty ?? []) {
      out.push("  " + pipe + " " + colorize(clipCell(line, inner), C.dim, nc));
    }
    out.push(colorize("  \u2570" + "\u2500".repeat(fill) + "\u2500\u256F", C.dim, nc));
    if (o.hint) out.push(colorize("    " + o.hint, C.dim, nc));
    return out;
  }

  out.push(bar("\u256D", "\u252C", "\u256E"));
  out.push(
    "  " +
      pipe +
      " " +
      o.columns
        .map((c, i) => padCell(colorize(c.header, C.bold + C.cyan, nc), width[i], c.align))
        .join(" " + pipe + " ") +
      " " +
      pipe,
  );
  out.push(bar("\u251C", "\u253C", "\u2524"));

  for (const row of o.rows) {
    const cells = o.columns.map((c, i) => {
      const cell = row[i] ?? { text: "" };
      const text = clipCell(cell.text, width[i]);
      return padCell(tone(text, cell.tone ?? "plain", nc), width[i], c.align);
    });
    out.push("  " + pipe + " " + cells.join(" " + pipe + " ") + " " + pipe);
  }

  out.push(bar("\u2570", "\u2534", "\u256F"));
  if (o.hint) out.push(colorize("    " + o.hint, C.dim, nc));
  return out;
}

/** One-line verdict for a failed probe. "not implemented" and
 *  "no answer" are different diagnoses and the user can act on
 *  them differently, so they must not collapse into one string. */
function probeStatus(r: ProbeResult): string {
  if (r.missing) return "not implemented by this daemon";
  if (/timed?\s*out|timeout/i.test(r.reason))
    return `no answer within ${PANEL_PROBE_TIMEOUT_MS / 1000}s`;
  return "the daemon returned an error";
}

/**
 * "the daemon has no endpoint for this" panel. Names the endpoint
 * that is missing so the gap is actionable instead of mysterious.
 */
function panelMissingEndpoint(
  nc: boolean,
  o: {
    title: string;
    subtitle?: string;
    endpoint: string;
    instead: string[];
    hint?: string;
    status?: string;
  },
): string[] {
  return renderPanel({
    title: o.title,
    subtitle: o.subtitle,
    noColor: nc,
    columns: [
      { header: "endpoint", min: 9 },
      { header: "status", flex: 1 },
    ],
    rows: [
      [
        { text: o.endpoint, tone: "strong" },
        { text: o.status ?? "not available", tone: "err" },
      ],
      [
        { text: "use instead", tone: "muted" },
        { text: o.instead[0] ?? "", tone: "plain" },
      ],
      ...o.instead.slice(1).map((line): PanelCell[] => [
        { text: "", tone: "plain" },
        { text: line, tone: "plain" },
      ]),
    ],
    hint: o.hint,
  });
}

/**
 * "line mode cannot do this" panel. Carries the reason and the
 * concrete alternative so the user is never left guessing.
 */
function panelUnavailable(
  nc: boolean,
  o: { title: string; subtitle?: string; reason: string; instead: string[] },
): string[] {
  return renderPanel({
    title: o.title,
    subtitle: o.subtitle,
    noColor: nc,
    columns: [
      { header: "line mode", min: 10 },
      { header: "detail", flex: 1 },
    ],
    rows: [
      [
        { text: "not available", tone: "warn" },
        { text: o.reason, tone: "plain" },
      ],
      ...o.instead.map((line): PanelCell[] => [
        { text: "", tone: "plain" },
        { text: line, tone: "plain" },
      ]),
    ],
    hint: "run `ac-tui` without --line for the full panel",
  });
}

/** Pre-formatted multi-line text (bank reports, audit summaries). */
function panelReport(
  nc: boolean,
  o: { title: string; subtitle?: string; text: string; hint?: string },
): string[] {
  const width = panelWidth();
  const inner = Math.max(24, width - 8);
  const body = wrapText(o.text, inner);
  return renderPanel({
    title: o.title,
    subtitle: o.subtitle,
    noColor: nc,
    columns: [{ header: "", min: inner }],
    rows: body.map((line): PanelCell[] => [{ text: line, tone: line ? "plain" : "muted" }]),
    hint: o.hint,
  });
}

/**
 * One RPC call, normalised. `missing` is the signal that the
 * method is not registered at all (JSON-RPC -32601), which is a
 * different user-facing story from "the call failed".
 *
 * Deliberately a flat object rather than a discriminated union:
 * every caller wants `reason` / `missing` on the failure branch
 * and `value` on the success branch, and a union forces TS's
 * control-flow narrowing to survive every intervening closure.
 * A flat shape keeps the 16 call sites readable and immune to
 * which `tsc` flags the caller happens to compile with.
 */
interface ProbeResult {
  ok: boolean;
  value: unknown;
  reason: string;
  missing: boolean;
}

/**
 * Ceiling on a single panel probe. The daemon is supposed to
 * answer an unknown method with -32601, but a method it *routes*
 * to a subsystem that is down (the MCP servers, the updater) can
 * simply never reply. A panel must never hang the prompt on that,
 * so every probe is bounded and reported as a timeout.
 */
const PANEL_PROBE_TIMEOUT_MS = 8000;

async function probe(ctx: PanelCtx, method: string, params?: RpcValue): Promise<ProbeResult> {
  try {
    const value = await ctx.client.request<unknown>(method, params, {
      timeoutMs: PANEL_PROBE_TIMEOUT_MS,
    });
    return { ok: true, value, reason: "", missing: false };
  } catch (e) {
    const reason = (e as Error)?.message ?? String(e);
    return {
      ok: false,
      value: undefined,
      reason,
      missing: /-32601|method not found|not implemented/i.test(reason),
    };
  }
}

/**
 * Run a list picker after a panel, then act on the choice.
 *
 * `pickFromList` returns `null` for two very different reasons —
 * the user pressed Esc, or stdin is not a TTY (piped / CI). They
 * get different messages: a cancel is the user's choice, a
 * non-TTY stdin is a capability the user needs told about.
 */
async function choose<T>(
  ctx: PanelCtx,
  o: { options: { label: string; value: T }[]; initial: number; title: string; alt: string },
): Promise<T | null> {
  if (o.options.length === 0) {
    ctx.logAbove(colorize("  nothing to choose from", C.dim, ctx.noColor));
    return null;
  }
  const pick = await runPicker(() =>
    pickFromList(
      ctx.lineInput,
      o.options,
      o.initial,
      o.title,
      "\u2191/\u2193 move  \u00B7  Enter select  \u00B7  Esc cancel",
      ctx.noColor,
    ),
  );
  if (pick) return pick.value;
  ctx.logAbove(
    colorize(
      process.stdout.isTTY
        ? "  (\u00B7 cancelled)"
        : `  (\u00B7 picker needs an interactive terminal \u2014 ${o.alt})`,
      C.dim,
      ctx.noColor,
    ),
  );
  return null;
}

/** Ping a no-op so the input area repaints after a picker. */
function repaint(ctx: PanelCtx): void {
  ctx.repaint();
}

// -------------------------------------------------------------------------
//  Panel context
// -------------------------------------------------------------------------

type LineTurn = { id: number; role: "user" | "assistant"; text: string; ts: number };

interface LineTodo {
  id: string;
  title: string;
  status: string;
  owner?: string;
  detail?: string;
}

interface LineNotice {
  ts: number;
  level: "info" | "warn" | "err";
  source: string;
  text: string;
}

interface PanelCtx {
  client: JsonRpcClient;
  noColor: boolean;
  lineInput: LineInput;
  logAbove: (text: string) => void;
  repaint: () => void;
  sessionId: string;
  cwd: string;
  /** Live snapshot the splash / status surfaces read from. */
  state: DisplayState;
  /** Live references owned by `runLineMode`. */
  todos: LineTodo[];
  notices: LineNotice[];
  turns: LineTurn[];
  snippets: Map<string, string>;
  bookmarks: Set<number>;
}

function emitPanel(ctx: PanelCtx, lines: string[]): void {
  for (const l of lines) ctx.logAbove(l);
}

// -------------------------------------------------------------------------
//  /memory  and  /memory-edit
// -------------------------------------------------------------------------

function memorySummary(e: unknown): string {
  const k = fieldStr(e, ["key"], "");
  const v = fieldStr(e, ["value", "text", "description", "message"], "");
  if (k !== "" && v !== "") return `${k} = ${v}`;
  return v;
}

async function panelMemory(ctx: PanelCtx, scopeRaw: string): Promise<void> {
  const nc = ctx.noColor;
  const scopes = ["global", "project", "session"];
  const asked = scopes.includes(scopeRaw) ? scopeRaw : "project";

  const lists = await Promise.all(
    scopes.map(async (scope) => {
      const r = await probe(ctx, "memory/list", { scope, sessionId: ctx.sessionId } as RpcValue);
      return { scope, entries: r.ok ? asRows(field(r.value, ["entries", "items"])) : [] };
    }),
  );
  const counts = lists.map((l) => `${l.scope} ${l.entries.length}`);
  const active = lists.find((l) => l.scope === asked);

  const SHOWN = 40;
  const rows: PanelCell[][] = (active?.entries ?? []).slice(0, SHOWN).map((e, i) => [
    { text: String(i + 1), tone: "muted" },
    { text: fieldStr(e, ["kind"]), tone: "strong" },
    { text: fieldStr(e, ["source"]), tone: "muted" },
    { text: memorySummary(e), tone: "plain" },
    { text: fmtStamp(field(e, ["ts", "createdAt", "timestamp"])), tone: "muted" },
  ]);

  const total = active?.entries.length ?? 0;
  emitPanel(
    ctx,
    renderPanel({
      title: "memory",
      subtitle: `${asked} layer`,
      note: counts.join("  \u00B7  "),
      noColor: nc,
      columns: [
        { header: "#", align: "right", min: 2 },
        { header: "kind", min: 5 },
        { header: "source", min: 5 },
        { header: "entry", flex: 3 },
        { header: "updated", min: 11 },
      ],
      rows,
      empty: [
        `counts \u2014 ${counts.join("  \u00B7  ")}`,
        "",
        `nothing in the ${asked} layer yet. the daemon appends entries as the model`,
        "calls its memory tools (fact / rule / change / breadcrumb), and the",
        "extractor promotes them at turn end \u2014 nothing to configure up front.",
      ],
      hint: rows.length < total ? `showing ${rows.length} of ${total}` : undefined,
    }),
  );
}

function panelMemoryEdit(ctx: PanelCtx): void {
  emitPanel(
    ctx,
    panelUnavailable(ctx.noColor, {
      title: "memory editor",
      subtitle: "add / edit / remove a single entry",
      reason: "there is no update or delete RPC on memory/* \u2014 only append, get and list.",
      instead: [
        "/memory           list entries per layer",
        "/memory-compact   let the daemon compress the change log",
        "memory tools      ask the model to remember / forget something",
      ],
    }),
  );
}

// -------------------------------------------------------------------------
//  /mcp  /mcp-login  /mcp-reconnect
// -------------------------------------------------------------------------

async function panelMcp(
  ctx: PanelCtx,
  action: "view" | "login" | "reconnect",
  target: string,
): Promise<void> {
  const nc = ctx.noColor;
  const spec =
    action === "login"
      ? {
          endpoint: "mcp/login",
          params: { name: target },
          subtitle: target === "" ? "login" : `login \u00B7 ${target}`,
        }
      : action === "reconnect"
        ? {
            endpoint: "mcp/reconnect",
            params: target === "" ? {} : { name: target },
            subtitle: target === "" ? "reconnect all" : `reconnect \u00B7 ${target}`,
          }
        : { endpoint: "mcp/listServers", params: undefined, subtitle: "servers and tools" };

  const r = await probe(ctx, spec.endpoint, spec.params as RpcValue | undefined);

  if (!r.ok) {
    emitPanel(
      ctx,
      panelMissingEndpoint(nc, {
        title: action === "view" ? "MCP servers and their tools" : `MCP ${action}`,
        subtitle: spec.subtitle,
        endpoint: spec.endpoint,
        status: probeStatus(r),
        instead: [
          `the daemon answered: ${r.reason}`,
          action === "view"
            ? "mcp.json is still read from disk \u2014 edit it directly to add a server."
            : "restart the daemon after editing mcp.json to pick the change up.",
        ],
      }),
    );
    return;
  }

  if (action !== "view") {
    emitPanel(
      ctx,
      renderPanel({
        title: `MCP ${action}`,
        subtitle: spec.subtitle,
        noColor: nc,
        columns: [
          { header: "endpoint", min: 14 },
          { header: "result", flex: 1 },
        ],
        rows: [
          [
            { text: spec.endpoint, tone: "strong" },
            { text: "accepted", tone: "ok" },
          ],
        ],
        hint: "re-run /mcp to see the refreshed server list",
      }),
    );
    return;
  }

  const servers = asRows(r.value);
  const rows: PanelCell[][] = servers.map((s) => {
    const tools = asRows(field(s, ["tools", "capabilities"]));
    const status = fieldStr(s, ["status", "state", "connected"], "unknown");
    const tone: PanelTone = /connected|ready|ok/i.test(status)
      ? "ok"
      : /error|fail|disconn/i.test(status)
        ? "err"
        : "warn";
    return [
      { text: fieldStr(s, ["name", "id", "server"]), tone: "strong" },
      { text: status, tone },
      { text: String(tools.length), align: "right", tone: "muted" },
      { text: fieldStr(s, ["transport", "url", "command"]), tone: "muted" },
    ];
  });
  emitPanel(
    ctx,
    renderPanel({
      title: "MCP servers and their tools",
      subtitle: "servers and tools",
      noColor: nc,
      columns: [
        { header: "server", min: 10 },
        { header: "status", min: 8 },
        { header: "tools", align: "right", min: 5 },
        { header: "transport", flex: 1 },
      ],
      rows,
      empty: ["no servers registered \u2014 add one to mcp.json and restart the daemon"],
    }),
  );
}

// -------------------------------------------------------------------------
//  /threads
// -------------------------------------------------------------------------

async function panelThreads(ctx: PanelCtx, cwdOnly: boolean): Promise<void> {
  const nc = ctx.noColor;
  const r = await probe(ctx, "listSessions");
  if (!r.ok) {
    emitPanel(
      ctx,
      panelMissingEndpoint(nc, {
        title: "session threads",
        endpoint: "listSessions",
        status: probeStatus(r),
        instead: [`the daemon answered: ${r.reason}`],
      }),
    );
    return;
  }
  const all = asRows(r.value);
  const idOf = (s: unknown): string => fieldStr(s, ["id", "sessionId", "engineId"]);
  // R370 — `/sessions` is "this project's sessions". Same lookup as
  // Ink, same reason (`sameCwd`, not `===`), and the unattributed
  // remainder is COUNTED rather than dropped so the panel does not
  // report "0 sessions" next to `/threads`'s 20 with no explanation.
  const { mine, other } = partitionByCwd(all, (s) => fieldStr(s, ["cwd", "project"]), ctx.cwd);
  const sessions = cwdOnly ? mine : all;
  const rows: PanelCell[][] = sessions.map((s) => [
    { text: idOf(s), tone: idOf(s) === ctx.sessionId ? "ok" : "plain" },
    { text: fieldStr(s, ["state", "status"]), tone: "muted" },
    { text: fieldStr(s, ["model"]), tone: "muted" },
    { text: fieldStr(s, ["cwd", "project"]), tone: "muted" },
    // R370: `lastUsedAt` is the field `listSessions` actually sends
    // (epoch millis from SessionInfo.lastModified()). The old
    // reader asked for `updatedAt` / `lastActiveAt`, neither of
    // which the daemon emits, so this column was blank on every row
    // in every run.
    { text: fmtStamp(field(s, ["lastUsedAt", "updatedAt", "lastActiveAt", "ts"])), tone: "muted" },
  ]);
  const live = sessions.filter((s) => idOf(s) === ctx.sessionId).length;
  emitPanel(
    ctx,
    renderPanel({
      title: cwdOnly ? `sessions in ${ctx.cwd || "(no cwd)"}` : "session threads",
      subtitle: cwdOnly
        ? "continue one of this project's sessions"
        : "switch the daemon to another thread",
      note:
        cwdOnly && other.length > 0
          ? `${sessions.length} of ${all.length} attributed here · ${other.length} have no recorded directory (/threads shows all)`
          : `${sessions.length} known`,
      noColor: nc,
      columns: [
        { header: "id", min: 8 },
        { header: "state", min: 6 },
        { header: "model", min: 8 },
        { header: "cwd", flex: 2 },
        { header: "last seen", min: 11 },
      ],
      rows,
      empty: cwdOnly
        ? [
            `no session is recorded under ${ctx.cwd || "the current directory"}`,
            "use /threads to see every session",
          ]
        : ["the daemon reports no sessions yet \u2014 start one with a prompt"],
    }),
  );
  if (live === 0 && sessions.length > 0) {
    ctx.logAbove(
      colorize("    the current thread is not in this list; picking one will switch it", C.dim, nc),
    );
  }
  if (sessions.length === 0) return;
  const picked = await choose(ctx, {
    options: sessions.map((s) => ({
      label: `${idOf(s)}  ${fieldStr(s, ["state", "cwd"], "")}`,
      value: idOf(s),
    })),
    initial: Math.max(
      0,
      sessions.findIndex((s) => idOf(s) === ctx.sessionId),
    ),
    title: cwdOnly ? "Continue a session" : "Switch session thread",
    alt: "use `/session <id>` instead",
  });
  repaint(ctx);
  if (picked === null) return;
  // R370 — this sent `{ id: picked }` and the daemon has never
  // accepted it. `AetherCodeMethods.loadSession` opens with
  // `stringOrThrow(p, "sessionId")`, so every line-mode thread or
  // session switch died on `-32602 invalid params` — after printing
  // a table the user was invited to pick from. The Ink path has sent
  // `{ sessionId }` all along, which is why only cmd.exe mode was
  // broken. Same class as R364's `setCwd`: a DTO key copied by name
  // instead of read off the handler.
  const res = await probe(ctx, "loadSession", { sessionId: picked } as RpcValue);
  if (res.ok) {
    // R370 — the daemon's active session changed, so line mode's
    // own `ctx.sessionId` is stale: the status line and every later
    // `idOf(s) === ctx.sessionId` "this is you" marker would keep
    // pointing at the session we just left. Take the id off the
    // response when the daemon sent one.
    const back = (res.value as { sessionId?: unknown } | null | undefined)?.sessionId;
    if (typeof back === "string" && back.length > 0) ctx.sessionId = back;

    // R372 — replay the transcript into the scrollback.
    //
    // Line mode's scrollback is a `LineTurn[]` (role + text + ts), so
    // the Ink `Turn` is projected down: a tool call becomes a
    // `[tool] name` line with its result, and everything else keeps
    // its role. The projection is lossy on purpose — line mode has no
    // tool cards to hang a duration or a category off.
    const tr = await probe(ctx, "getTranscript");
    if (tr.ok) {
      const replay = replayTranscript(tr.value, 1);
      ctx.turns.length = 0;
      for (const t of replay.turns) {
        ctx.turns.push(
          t.role === "tool"
            ? {
                id: t.id,
                role: "assistant",
                text: `[tool] ${t.toolName ?? t.text}` + (t.toolResult ? `\n${t.toolResult}` : ""),
                ts: t.ts,
              }
            : {
                id: t.id,
                role: t.role === "assistant" ? "assistant" : "user",
                text: t.text,
                ts: t.ts,
              },
        );
      }
      repaint(ctx);
      const dropped = replay.totalMessages - replay.replayedMessages;
      ctx.logAbove(
        colorize(
          dropped > 0
            ? `    replayed ${replay.replayedMessages} of ${replay.totalMessages} messages (${dropped} older ones not shown)`
            : `    replayed ${replay.turns.length} turns`,
          C.dim,
          nc,
        ),
      );
    } else {
      // The switch happened; the replay did not. Say so, rather than
      // leaving an empty scrollback that reads like an empty session.
      ctx.logAbove(colorize("    its history could not be read", C.dim, nc));
    }
  }
  ctx.logAbove(
    res.ok
      ? colorize(`  \u2713 now working in ${ctx.sessionId}`, C.green, nc)
      : colorize(`  \u2717 loadSession failed: ${res.reason}`, C.red, nc),
  );
}

async function panelContext(ctx: PanelCtx): Promise<void> {
  const nc = ctx.noColor;
  const r = await probe(ctx, "getContextInfo");
  if (!r.ok) {
    emitPanel(
      ctx,
      panelMissingEndpoint(nc, {
        title: "context window",
        endpoint: "getContextInfo",
        status: probeStatus(r),
        instead: [`the daemon answered: ${r.reason}`],
      }),
    );
    return;
  }
  const v = (r.value ?? {}) as Record<string, unknown>;
  if (v.ok === false) {
    emitPanel(ctx, [
      colorize(`  \u2717 getContextInfo refused: ${String(v.error ?? "no engine")}`, C.red, nc),
    ]);
    return;
  }
  const n = (x: unknown): number => (typeof x === "number" && Number.isFinite(x) ? x : 0);
  const window = n(v.contextWindow);
  if (window <= 0) {
    // An `ok:true` answer with a zero window would render "0 / 0",
    // which reads as "you have no context at all" rather than "the
    // daemon did not know". Same reasoning as the Ink side.
    emitPanel(ctx, [
      colorize("  \u2014 the daemon reported no context window for this model", C.dim, nc),
    ]);
    return;
  }
  const compact = await probe(ctx, "compact/status");
  const cv = (compact.ok ? compact.value : null) as Record<string, unknown> | null;
  const disabled = cv?.autoCompactDisabled === true;
  const ev = (cv?.lastEvent ?? null) as Record<string, unknown> | null;
  const lastCompact =
    ev && typeof ev.ts === "number" && ev.ts > 0 ? new Date(ev.ts).toLocaleTimeString() : "";

  const rows: PanelCell[][] = [
    [
      { text: "model", tone: "muted" },
      { text: `${String(v.provider ?? "—")}/${String(v.model ?? "—")}` },
    ],
    [
      { text: "window", tone: "muted" },
      {
        text: `${window.toLocaleString()} tokens (max output ${n(v.maxOutputTokens).toLocaleString()})`,
      },
    ],
    [
      { text: "in use", tone: "muted" },
      { text: `~${n(v.estimatedTokens).toLocaleString()} tokens — ${n(v.usagePercent)}%` },
    ],
    [
      { text: "transcript", tone: "muted" },
      { text: `${n(v.transcriptChars).toLocaleString()} characters` },
    ],
    [
      { text: "auto-compact", tone: "muted" },
      {
        text: compact.ok
          ? disabled
            ? "OFF — the window fills until the provider rejects the turn"
            : "on"
          : "unknown (compact/status unavailable)",
        tone: disabled ? "err" : "muted",
      },
    ],
  ];
  if (lastCompact) rows.push([{ text: "last compact", tone: "muted" }, { text: lastCompact }]);

  emitPanel(
    ctx,
    renderPanel({
      title: "context window",
      subtitle: "what is filling the window",
      noColor: nc,
      columns: [
        { header: "field", min: 13 },
        { header: "value", flex: 3 },
      ],
      rows,
      empty: ["the daemon reported nothing about the context window"],
    }),
  );
  ctx.logAbove(
    colorize("    token count is the daemon's estimate (transcript characters ÷ 4)", C.dim, nc),
  );
}

// -------------------------------------------------------------------------
//  /consents  (grants)
// -------------------------------------------------------------------------

async function panelConsents(ctx: PanelCtx): Promise<void> {
  const nc = ctx.noColor;
  // `grants/*` is the daemon's own surface; `permission/*` is the
  // older alias the TUI's typed helper still speaks. Try both so
  // the panel works against either daemon vintage.
  let method = "grants/list";
  let r = await probe(ctx, method);
  if (!r.ok && r.missing) {
    method = "permission/list";
    r = await probe(ctx, method);
  }
  if (!r.ok) {
    emitPanel(
      ctx,
      panelMissingEndpoint(nc, {
        title: "stored tool grants",
        subtitle: "review and revoke",
        endpoint: "grants/list, permission/list",
        status: probeStatus(r),
        instead: [`the daemon answered: ${r.reason}`],
      }),
    );
    return;
  }
  const grants = asRows(r.value);
  const idOf = (g: unknown): string => fieldStr(g, ["id", "grantId"], "");
  const rows: PanelCell[][] = grants.map((g) => {
    const decision = fieldStr(g, ["decision"], "allow");
    return [
      { text: idOf(g), tone: "muted" },
      { text: fieldStr(g, ["scope"]), tone: "muted" },
      { text: fieldStr(g, ["category"]), tone: "strong" },
      { text: decision, tone: decision === "deny" ? "err" : "ok" },
      { text: fieldStr(g, ["reason", "tool"]), tone: "plain" },
      { text: fmtStamp(field(g, ["createdAt", "ts"])), tone: "muted" },
    ];
  });
  emitPanel(
    ctx,
    renderPanel({
      title: "stored tool grants",
      subtitle: "review and revoke",
      note: `${grants.length} stored`,
      noColor: nc,
      columns: [
        { header: "id", min: 6 },
        { header: "scope", min: 6 },
        { header: "category", min: 8 },
        { header: "decision", min: 8 },
        { header: "reason", flex: 2 },
        { header: "created", min: 11 },
      ],
      rows,
      empty: ["no stored grants \u2014 nothing has been auto-allowed yet"],
      hint: "revoking re-prompts on the next matching tool call",
    }),
  );
  const picked = await choose(ctx, {
    options: grants.map((g) => ({
      label: `${fieldStr(g, ["category"])}  ${fieldStr(g, ["decision"])}  ${fieldStr(g, ["reason", "tool"], "")}`,
      value: idOf(g),
    })),
    initial: 0,
    title: "Revoke a grant",
    alt: "clear them all with /consents in the full TUI",
  });
  repaint(ctx);
  if (picked === null) return;
  const res = await probe(ctx, "grants/revoke", { id: picked } as RpcValue);
  ctx.logAbove(
    res.ok
      ? colorize(`  \u2713 revoked ${picked}`, C.green, nc)
      : colorize(`  \u2717 revoke failed: ${res.reason}`, C.red, nc),
  );
}

// -------------------------------------------------------------------------
//  /notifications
// -------------------------------------------------------------------------

const NOTICE_TONE: Record<LineNotice["level"], PanelTone> = {
  info: "plain",
  warn: "warn",
  err: "err",
};

function panelNotifications(ctx: PanelCtx): void {
  const nc = ctx.noColor;
  const rows: PanelCell[][] = ctx.notices
    .slice(-40)
    .reverse()
    .map((n) => [
      { text: fmtStamp(n.ts), tone: "muted" },
      { text: n.level, tone: NOTICE_TONE[n.level] },
      { text: n.source, tone: "muted" },
      { text: n.text, tone: "plain" },
    ]);
  emitPanel(
    ctx,
    renderPanel({
      title: "notification center",
      subtitle: "notices this session",
      note: `${ctx.notices.length} captured`,
      noColor: nc,
      columns: [
        { header: "time", min: 11 },
        { header: "level", min: 5 },
        { header: "source", min: 7 },
        { header: "notice", flex: 3 },
      ],
      rows,
      empty: [
        "no notices captured this session.",
        "",
        "line mode records only what the daemon pushes (log / task_state /",
        "a failed run). it cannot page or badge in the background the way the",
        "full TUI does \u2014 this list is the audit trail for the current run.",
      ],
      hint: "kept in memory only \u2014 it is cleared when the TUI exits",
    }),
  );
}

// -------------------------------------------------------------------------
//  /agent-pick
// -------------------------------------------------------------------------

async function panelAgentPick(ctx: PanelCtx): Promise<void> {
  const nc = ctx.noColor;
  const r = await probe(ctx, "listAgents");
  if (!r.ok) {
    emitPanel(
      ctx,
      panelMissingEndpoint(nc, {
        title: "active agent",
        endpoint: "listAgents",
        status: probeStatus(r),
        instead: [`the daemon answered: ${r.reason}`, "/agents   list them as raw JSON"],
      }),
    );
    return;
  }
  const agents = asRows(r.value);
  const nameOf = (a: unknown): string => fieldStr(a, ["name", "id", "agent"], "");
  const rows: PanelCell[][] = agents.map((a) => [
    { text: nameOf(a), tone: "strong" },
    { text: fieldStr(a, ["role", "kind"]), tone: "muted" },
    { text: fieldStr(a, ["model"]), tone: "muted" },
    { text: fieldStr(a, ["description", "summary"]), tone: "plain" },
  ]);
  emitPanel(
    ctx,
    renderPanel({
      title: "active agent",
      subtitle: "pick the agent for this session",
      note: `${agents.length} installed`,
      noColor: nc,
      columns: [
        { header: "agent", min: 10 },
        { header: "role", min: 6 },
        { header: "model", min: 8 },
        { header: "description", flex: 3 },
      ],
      rows,
      empty: ["no agents installed \u2014 create one with /init"],
    }),
  );
  const picked = await choose(ctx, {
    options: agents.map((a) => ({ label: nameOf(a), value: nameOf(a) })),
    initial: 0,
    title: "Pick active agent",
    alt: "use `/agent <name>` instead",
  });
  repaint(ctx);
  if (picked === null) return;
  // R369: probe this and it returns -32601, because the daemon
  // registers no such method. The Ink side already says so in plain
  // words before the user picks (tui.tsx `case "agents"`), so line
  // mode should not be the one that fails with a raw error code.
  // Same table `handleSlash` consults.
  const agentWhy = unsupportedReason("agent/setDefault");
  if (agentWhy) {
    ctx.logAbove(colorize(`  ✗ ${unsupportedMessage("agent/setDefault", agentWhy)}`, C.red, nc));
    return;
  }
  const res = await probe(ctx, "agent/setDefault", { name: picked } as RpcValue);
  ctx.logAbove(
    res.ok
      ? colorize(`  \u2713 default agent \u2192 ${picked}`, C.green, nc)
      : colorize(`  \u2717 agent/setDefault failed: ${res.reason}`, C.red, nc),
  );
}

// -------------------------------------------------------------------------
//  /effort-pick
// -------------------------------------------------------------------------

const EFFORT_LEVELS = ["low", "medium", "high", "xhigh"] as const;

const EFFORT_EFFECT: Record<string, string> = {
  low: "fastest and cheapest; mechanical edits, lookups",
  medium: "balanced \u2014 the default for most work",
  high: "thorough; multi-file reasoning and architecture",
  xhigh: "slowest, priciest; only for genuinely hard problems",
};

async function pickEffort(ctx: PanelCtx): Promise<void> {
  const nc = ctx.noColor;
  const rows: PanelCell[][] = EFFORT_LEVELS.map((lv) => [
    { text: lv, tone: "strong" },
    { text: EFFORT_EFFECT[lv], tone: "muted" },
  ]);
  emitPanel(
    ctx,
    renderPanel({
      title: "reasoning effort",
      subtitle: "how hard the model should think",
      noColor: nc,
      columns: [
        { header: "level", min: 8 },
        { header: "effect", flex: 1 },
      ],
      rows,
      hint: "the engine records the level per run; /effort <level> sets it without a prompt",
    }),
  );
  const picked = await choose(ctx, {
    options: EFFORT_LEVELS.map((lv) => ({
      label: `${lv}  \u2014  ${EFFORT_EFFECT[lv]}`,
      value: lv,
    })),
    initial: 1,
    title: "Pick reasoning effort",
    alt: "use `/effort low|medium|high|xhigh` instead",
  });
  repaint(ctx);
  if (picked === null) return;
  // R369: same as above — `effort/set` is not registered either, so the
  // picker must say so in words instead of probing into -32601.
  const effortWhy = unsupportedReason("effort/set");
  if (effortWhy) {
    ctx.logAbove(colorize(`  ✗ ${unsupportedMessage("effort/set", effortWhy)}`, C.red, nc));
    return;
  }
  const res = await probe(ctx, "effort/set", { effort: picked } as RpcValue);
  ctx.logAbove(
    res.ok
      ? colorize(`  \u2713 effort \u2192 ${picked}`, C.green, nc)
      : colorize(`  \u2717 effort/set failed: ${res.reason}`, C.red, nc),
  );
}

// -------------------------------------------------------------------------
//  /cwd-pick
// -------------------------------------------------------------------------

async function panelCwdPick(ctx: PanelCtx): Promise<void> {
  const nc = ctx.noColor;
  const r = await probe(ctx, "listProjects");
  const fromDaemon = r.ok
    ? asRows(r.value).map((p) => fieldStr(p, ["path", "cwd", "project", "id"], ""))
    : [];
  const here = ctx.cwd || process.cwd();
  const parent =
    here
      .replace(/[\\/]+$/, "")
      .split(/[\\/]/)
      .slice(0, -1)
      .join("/") || "/";
  const candidates = [...new Set([here, parent, process.env.HOME ?? "", ...fromDaemon])].filter(
    Boolean,
  );

  emitPanel(
    ctx,
    renderPanel({
      title: "daemon working directory",
      subtitle: "switch project",
      note: `current ${here}`,
      noColor: nc,
      columns: [
        { header: "path", flex: 1 },
        { header: "", min: 4 },
      ],
      rows: candidates.map((c): PanelCell[] => [
        { text: shortCwd(c, 60), tone: c === here ? "ok" : "plain" },
        { text: c === here ? "here" : "", tone: "muted" },
      ]),
      empty: ["no candidates \u2014 pass an explicit path with `/cwd <path>`"],
    }),
  );
  const picked = await choose(ctx, {
    options: candidates.map((c) => ({ label: c, value: c })),
    initial: 0,
    title: "Switch daemon working directory",
    alt: "use `/cwd <path>` instead",
  });
  repaint(ctx);
  if (picked === null) return;
  if (picked === here) {
    ctx.logAbove(colorize("  \u00B7 already there", C.dim, nc));
    return;
  }
  const res = await probe(ctx, "switchProject", { cwd: picked } as RpcValue);
  if (res.ok) {
    ctx.logAbove(colorize(`  \u2713 cwd \u2192 ${picked}`, C.green, nc));
  } else {
    ctx.logAbove(colorize(`  \u2717 switchProject failed: ${res.reason}`, C.red, nc));
  }
}

// -------------------------------------------------------------------------
//  /todos
// -------------------------------------------------------------------------

const TODO_TONE: Record<string, PanelTone> = {
  completed: "ok",
  in_progress: "warn",
  cancelled: "muted",
  pending: "muted",
};

const TODO_MARK: Record<string, string> = {
  completed: "[x]",
  in_progress: "[>]",
  cancelled: "[-]",
  pending: "[ ]",
};

function panelTodos(ctx: PanelCtx): void {
  const nc = ctx.noColor;
  const done = ctx.todos.filter((t) => t.status === "completed").length;
  const rows: PanelCell[][] = ctx.todos.map((t, i) => [
    { text: String(i + 1), align: "right", tone: "muted" },
    { text: TODO_MARK[t.status] ?? "[ ]", tone: TODO_TONE[t.status] ?? "muted" },
    { text: t.status.replace(/_/g, " "), tone: "muted" },
    { text: t.title, tone: t.status === "in_progress" ? "strong" : "plain" },
    { text: t.owner ?? "", tone: "muted" },
  ]);
  emitPanel(
    ctx,
    renderPanel({
      title: "TODO board",
      subtitle: "the model's current plan",
      note: ctx.todos.length > 0 ? `${done}/${ctx.todos.length} done` : undefined,
      noColor: nc,
      columns: [
        { header: "#", align: "right", min: 2 },
        { header: "", min: 3 },
        { header: "status", min: 8 },
        { header: "item", flex: 3 },
        { header: "owner", min: 5 },
      ],
      rows,
      empty: [
        "no plan yet.",
        "",
        "the todo list is written by the model through its todo_write tool.",
        'ask for a plan ("draft a 4-step plan for X") and it appears here as',
        "the run progresses \u2014 the daemon re-sends the whole list on each",
        "update, so this view is always the current snapshot.",
      ],
    }),
  );
}

// -------------------------------------------------------------------------
//  /workflow
// -------------------------------------------------------------------------

async function panelWorkflow(ctx: PanelCtx): Promise<void> {
  const nc = ctx.noColor;
  const r = await probe(ctx, "listWorkflows");
  if (!r.ok) {
    emitPanel(
      ctx,
      panelMissingEndpoint(nc, {
        title: "workflow picker",
        endpoint: "listWorkflows",
        status: probeStatus(r),
        instead: [
          `the daemon answered: ${r.reason}`,
          "/workflow <name>   run one by name without a picker",
        ],
      }),
    );
    return;
  }
  const flows = asRows(r.value);
  // R373: the DISPLAY name and the ADDRESSABLE id are different
  // strings, and `runWorkflow` resolves a FILE. The daemon publishes
  // `id` (the workflow file's name) for exactly this; the fallback to
  // `name` keeps an older daemon working.
  const nameOf = (w: unknown): string => fieldStr(w, ["name", "id"], "");
  const idOf = (w: unknown): string => fieldStr(w, ["id", "name"], "");
  const rows: PanelCell[][] = flows.map((w) => [
    { text: nameOf(w), tone: "strong" },
    { text: fieldStr(w, ["description", "summary"]), tone: "plain" },
    { text: String(asRows(field(w, ["steps"])).length), align: "right", tone: "muted" },
  ]);
  // R373: the daemon publishes the files it could not READ. An
  // unreadable file and an empty directory are different facts, and
  // the empty state has to say which one it is — "no workflows
  // defined" would blame the user for a permission problem.
  //
  // The COPY comes from the same pure function the Ink panel uses, so
  // the two renderers cannot drift apart. The line frame's `empty:`
  // slot is only rendered when there are no rows, so it asks for the
  // copy directly rather than for "the empty state, if any".
  const skipped = asRows(field(r.value, ["skipped"])).map((s) => String(s));
  const dir = fieldStr(r.value, ["dir"], "") || null;
  const empty: string[] = workflowEmptyCopy(skipped, dir).lines;
  emitPanel(
    ctx,
    renderPanel({
      title: "workflow picker",
      subtitle: "run a workflow by name",
      note: `${flows.length} defined`,
      noColor: nc,
      columns: [
        { header: "workflow", min: 12 },
        { header: "description", flex: 3 },
        { header: "steps", align: "right", min: 5 },
      ],
      rows,
      empty,
    }),
  );
  const picked = await choose(ctx, {
    options: flows.map((w) => ({ label: nameOf(w), value: idOf(w) })),
    initial: 0,
    title: "Run a workflow",
    alt: "use `/workflow <name>` instead",
  });
  repaint(ctx);
  if (picked === null) return;
  // The id, not the display name — see `idOf` above.
  const res = await probe(ctx, "runWorkflow", { name: picked } as RpcValue);
  ctx.logAbove(
    res.ok
      ? colorize(`  \u2713 started workflow ${picked}`, C.green, nc)
      : colorize(`  \u2717 workflow/run failed: ${res.reason}`, C.red, nc),
  );
}

// -------------------------------------------------------------------------
//  /update  /update-deps
// -------------------------------------------------------------------------

async function panelUpdate(ctx: PanelCtx, verb: UpdateVerb): Promise<void> {
  const nc = ctx.noColor;
  const endpoint = UPDATE_ENDPOINTS[verb];
  const r = await probe(ctx, endpoint);
  // The daemon's version, for the same "current vs available" shape
  // the Ink panel shows. A failed ping is not fatal: the panel still
  // opens, and the version reads "not reported by the daemon".
  const ping = await probe(ctx, "ping");
  // R374: the COPY comes from the same pure module the Ink panel
  // uses, so the two renderers cannot drift apart.
  const model = buildUpdatePanel(verb, {
    tuiVersion: TUI_VERSION,
    daemonVersion: ping.ok ? daemonVersionOf(ping.value) : null,
    endpointMissing: r.missing,
    reason: r.ok ? null : r.reason,
    reported: r.ok ? scalarRows(r.value) : [],
  });
  emitPanel(
    ctx,
    renderPanel({
      title: model.title,
      subtitle: model.endpoint,
      noColor: nc,
      columns: [
        { header: "field", min: 16 },
        { header: "value", flex: 1 },
      ],
      rows: [
        ...model.rows.map(([k, v]): PanelCell[] => [
          { text: k, tone: "strong" },
          { text: v, tone: "plain" },
        ]),
        // The honest answer leads the prose, then the manual path.
        [
          { text: "status", tone: "muted" },
          { text: model.headline, tone: model.endpointMissing ? "err" : "plain" },
        ],
        ...model.manual.map((line): PanelCell[] => [
          { text: "", tone: "plain" },
          { text: line, tone: "plain" },
        ]),
        [
          { text: "changelog", tone: "muted" },
          { text: model.changelogUrl, tone: "plain" },
        ],
      ],
    }),
  );
}

// -------------------------------------------------------------------------
//  SDD family
// -------------------------------------------------------------------------

async function panelSddStatus(ctx: PanelCtx): Promise<void> {
  const nc = ctx.noColor;
  const r = await probe(ctx, "sdd.status");
  if (!r.ok) {
    emitPanel(
      ctx,
      panelMissingEndpoint(nc, {
        title: "SDD run status",
        endpoint: "sdd.status",
        status: probeStatus(r),
        instead: [`the daemon answered: ${r.reason}`, "/sdd <intent>   start a spec-driven run"],
      }),
    );
    return;
  }
  const obj = (r.value ?? {}) as Record<string, unknown>;
  const state = (field(obj, ["state", "run"]) ?? obj) as Record<string, unknown>;
  const phase = fieldStr(state, ["phase", "currentPhase"], "not started");
  const slug = fieldStr(state, ["slug", "id"], "");
  const rows: PanelCell[][] = [
    [
      { text: "slug", tone: "muted" },
      { text: slug, tone: "strong" },
    ],
    [
      { text: "phase", tone: "muted" },
      { text: phase, tone: "plain" },
    ],
    [
      { text: "intent", tone: "muted" },
      { text: fieldStr(state, ["intent", "title"]), tone: "plain" },
    ],
    [
      { text: "updated", tone: "muted" },
      { text: fmtStamp(field(state, ["updatedAt", "ts"])), tone: "muted" },
    ],
  ];
  const history = asRows(field(state, ["history", "phases", "steps"]));
  emitPanel(
    ctx,
    renderPanel({
      title: "SDD run status",
      subtitle: slug === "" ? "no active run" : slug,
      noColor: nc,
      columns: [
        { header: "field", min: 9 },
        { header: "value", flex: 1 },
      ],
      rows,
      hint:
        history.length > 0
          ? undefined
          : "/sdd-approve  approve the current phase  \u00B7  /sdd-modify <note>  send feedback",
    }),
  );
  for (const h of history.slice(-8)) {
    ctx.logAbove(
      "  " +
        colorize("\u2503 ", C.dim, nc) +
        colorize(
          `${fieldStr(h, ["phase", "name"], "?")}  ${fieldStr(h, ["status", "decision"], "")}`,
          C.dim,
          nc,
        ),
    );
  }
}

async function panelSddAction(
  ctx: PanelCtx,
  o: { title: string; method: string; params: RpcValue },
): Promise<void> {
  const nc = ctx.noColor;
  const r = await probe(ctx, o.method, o.params);
  if (!r.ok) {
    emitPanel(
      ctx,
      panelMissingEndpoint(nc, {
        title: o.title,
        endpoint: o.method,
        status: probeStatus(r),
        instead: [`the daemon answered: ${r.reason}`],
      }),
    );
    return;
  }
  const obj = (r.value ?? {}) as Record<string, unknown>;
  const ok = field(obj, ["ok"]) !== false;
  ctx.logAbove(
    ok
      ? colorize(
          `  \u2713 ${o.title} \u2014 ${fieldStr(obj, ["state", "phase", "status"], "accepted")}`,
          C.green,
          nc,
        )
      : colorize(`  \u2717 ${o.title} \u2014 ${fieldStr(obj, ["error"], "rejected")}`, C.red, nc),
  );
}

// -------------------------------------------------------------------------
//  /bank-stats  /bank-recall  /memory-audit
//
//  The bank lives in a separate process, so these three read it
//  directly out of `aethercode-memory` exactly as tui.tsx does —
//  no JSON-RPC hop.
// -------------------------------------------------------------------------

async function panelBank(ctx: PanelCtx, recall: boolean, kind: string, n: number): Promise<void> {
  const nc = ctx.noColor;
  try {
    const mod = (await import("aethercode-memory")) as unknown as {
      readBankStats?: () => Promise<{ text?: string }>;
      readBankRecall?: (kind: string, n: number) => Promise<{ text?: string }>;
    };
    const summary = recall ? await mod.readBankRecall!(kind, n) : await mod.readBankStats!();
    emitPanel(
      ctx,
      panelReport(nc, {
        title: recall ? "bank recall" : "strategy bank",
        subtitle: recall ? `${kind} \u00B7 top ${n}` : "size and per-kind outcomes",
        text: summary?.text ?? "(no report)",
      }),
    );
  } catch (e) {
    emitPanel(
      ctx,
      panelReport(nc, {
        title: recall ? "bank recall" : "strategy bank",
        text: `error \u2014 ${(e as Error).message}`,
        hint: "the bank server lives beside the daemon; is it running?",
      }),
    );
  }
}

async function panelMemoryAudit(ctx: PanelCtx): Promise<void> {
  const nc = ctx.noColor;
  try {
    const mod = (await import("aethercode-memory")) as unknown as {
      auditSelfEval?: () => Promise<{ text?: string }>;
    };
    const summary = await mod.auditSelfEval!();
    emitPanel(
      ctx,
      panelReport(nc, {
        title: "memory audit",
        subtitle: "cross-process self-eval",
        text: summary?.text ?? "(no report)",
      }),
    );
  } catch (e) {
    emitPanel(
      ctx,
      panelReport(nc, {
        title: "memory audit",
        text: `error \u2014 ${(e as Error).message}`,
        hint: "the bank server lives beside the daemon; is it running?",
      }),
    );
  }
}

// -------------------------------------------------------------------------
//  /tutorial  /bookmark  /snippet
// -------------------------------------------------------------------------

function panelTutorial(ctx: PanelCtx): void {
  const nc = ctx.noColor;
  emitPanel(ctx, renderSplash(ctx.state, nc).split("\n"));
  emitPanel(
    ctx,
    renderPanel({
      title: "quick reference",
      subtitle: "the commands worth memorising",
      noColor: nc,
      columns: [
        { header: "key", min: 12 },
        { header: "does", flex: 1 },
      ],
      rows: [
        [
          { text: "/help", tone: "strong" },
          { text: "every slash command, grouped", tone: "plain" },
        ],
        [
          { text: "/mode", tone: "strong" },
          { text: "pick the permission mode (arrow keys)", tone: "plain" },
        ],
        [
          { text: "/memory", tone: "strong" },
          { text: "browse the 3-layer memory", tone: "plain" },
        ],
        [
          { text: "/todos", tone: "strong" },
          { text: "the model's current plan", tone: "plain" },
        ],
        [
          { text: "/threads", tone: "strong" },
          { text: "switch session thread", tone: "plain" },
        ],
        [
          { text: "/consents", tone: "strong" },
          { text: "review and revoke stored tool grants", tone: "plain" },
        ],
        [
          { text: "/export md", tone: "strong" },
          { text: "write this session to markdown", tone: "plain" },
        ],
        [
          { text: "/exit", tone: "strong" },
          { text: "leave (Ctrl+C works too)", tone: "plain" },
        ],
      ],
      hint: "line mode is the cmd.exe path: no full-screen UI, same daemon",
    }),
  );
}

function panelBookmark(ctx: PanelCtx, target: string): void {
  const nc = ctx.noColor;
  const assistantTurns = ctx.turns.filter((t) => t.role === "assistant");
  if (assistantTurns.length === 0) {
    ctx.logAbove(
      colorize("  nothing to bookmark yet \u2014 let one turn complete first", C.dim, nc),
    );
    return;
  }
  const idx = target === "" ? assistantTurns.length : Number(target);
  if (!Number.isInteger(idx) || idx < 1 || idx > assistantTurns.length) {
    emitPanel(
      ctx,
      renderPanel({
        title: "bookmark",
        subtitle: `/bookmark ${target}`,
        noColor: nc,
        columns: [
          { header: "field", min: 8 },
          { header: "value", flex: 1 },
        ],
        rows: [
          [
            { text: "turn", tone: "muted" },
            { text: target, tone: "err" },
          ],
          [
            { text: "valid", tone: "muted" },
            { text: `1 \u2013 ${assistantTurns.length} assistant turns`, tone: "plain" },
          ],
        ],
        hint: "/bookmark with no argument marks the most recent turn",
      }),
    );
    return;
  }
  const turn = assistantTurns[idx - 1];
  const wasSet = ctx.bookmarks.has(turn.id);
  if (wasSet) ctx.bookmarks.delete(turn.id);
  else ctx.bookmarks.add(turn.id);
  emitPanel(
    ctx,
    renderPanel({
      title: "bookmark",
      subtitle: wasSet ? "removed" : "marked",
      noColor: nc,
      columns: [
        { header: "turn", min: 6 },
        { header: "when", min: 11 },
        { header: "first line", flex: 2 },
      ],
      rows: [
        [
          { text: `${idx}/${assistantTurns.length}`, tone: "strong" },
          { text: fmtStamp(turn.ts), tone: "muted" },
          { text: turn.text.replace(/\s+/g, " ").trim(), tone: "plain" },
        ],
      ],
      hint: wasSet
        ? "removed"
        : "line mode keeps bookmarks for this session only \u2014 run /bookmark <n> again to clear",
    }),
  );
}

function panelSnippet(ctx: PanelCtx, op: string, name: string): void {
  const nc = ctx.noColor;
  switch (op) {
    case "list": {
      const names = [...ctx.snippets.keys()].sort();
      emitPanel(
        ctx,
        renderPanel({
          title: "snippets",
          subtitle: "saved this session",
          note: `${names.length} saved`,
          noColor: nc,
          columns: [
            { header: "name", min: 12 },
            { header: "body", flex: 3 },
          ],
          rows: names.map((n): PanelCell[] => [
            { text: n, tone: "strong" },
            { text: ctx.snippets.get(n)!.replace(/\s+/g, " ").trim(), tone: "plain" },
          ]),
          empty: [
            "no snippets saved this session.",
            "",
            "/snippet save <name>   stores the last assistant turn",
            "/snippet load <name>   prints it back",
          ],
        }),
      );
      return;
    }
    case "save": {
      const last = [...ctx.turns]
        .reverse()
        .find((t) => t.role === "assistant" && t.text.trim().length > 0);
      if (!last) {
        ctx.logAbove(
          colorize("  nothing to save yet \u2014 let one turn complete first", C.dim, nc),
        );
        return;
      }
      ctx.snippets.set(name, last.text);
      ctx.logAbove(
        colorize(
          `  \u2713 snippet \u201c${name}\u201d \u2192 ${last.text.length} chars from the last reply`,
          C.green,
          nc,
        ),
      );
      return;
    }
    case "load": {
      const body = ctx.snippets.get(name);
      if (body === undefined) {
        ctx.logAbove(
          colorize(`  \u2717 no snippet \u201c${name}\u201d \u2014 /snippet list`, C.red, nc),
        );
        return;
      }
      ctx.logAbove(renderInlineMarkdown(body, nc));
      return;
    }
    case "delete": {
      if (!ctx.snippets.delete(name)) {
        ctx.logAbove(
          colorize(`  \u2717 no snippet \u201c${name}\u201d \u2014 /snippet list`, C.red, nc),
        );
        return;
      }
      ctx.logAbove(colorize(`  \u2713 deleted snippet \u201c${name}\u201d`, C.green, nc));
      return;
    }
    default:
      ctx.logAbove(colorize(`  unknown snippet op: ${op}`, C.red, nc));
  }
}

// -------------------------------------------------------------------------
//  Sentinel dispatch
// -------------------------------------------------------------------------

/** `__MEMORY_PANEL__:project` -> `__MEMORY_PANEL__` / `project`. */
function splitSentinel(local: string): { base: string; payload: string } {
  const i = local.indexOf(":");
  return i < 0
    ? { base: local, payload: "" }
    : { base: local.slice(0, i), payload: local.slice(i + 1) };
}

/** Only `__X__`-shaped tokens get sanitised; see the fallback. */
const SENTINEL_SHAPE = /^__[A-Z_0-9]+__(?::|$)/;

/**
 * The last-resort branch.
 *
 * `handleSlash` overloads `local` for two different things: an
 * internal sentinel (`__MEMORY_PANEL__:project`) AND a
 * user-facing sentence (`usage: /model <name>`). So this only
 * rewrites tokens that actually look like sentinels and passes
 * every other string through verbatim — otherwise we would
 * "sanitise" the usage text into noise.
 */
/**
 * panelIntents.ts's separator was round-tripped through a GBK
 * decode: the em dash in `describeSentinel` landed in the source
 * as U+9225 followed by a literal "?" (its own comments use
 * U+922B for the same original character, so the corruption is not
 * even self-consistent). Normalise it here rather than editing the
 * shared registry, which another change owns.
 *
 * Replacement is `U+9225 + "?" -> em dash + space`, because the
 * registry emits a space BEFORE the dash and none after it.
 */
const MOJIBAKE_DASH = "\u9225?";

function humanizeSentinelLine(local: string, noColor: boolean): string {
  if (!SENTINEL_SHAPE.test(local)) {
    return colorize(local, C.dim, noColor);
  }
  const { base, payload } = splitSentinel(local);
  const sentence = describeSentinel(base, "line").split(MOJIBAKE_DASH).join("\u2014 ");
  const where = payload === "" || intentForSentinel(base) === undefined ? "" : `  (${payload})`;
  return colorize("  \u00B7 " + sentence + where, C.dim, noColor);
}

/**
 * Route one sentinel token to a line-mode renderer.
 *
 * Returns `false` for anything this block does not own, which is
 * the caller's signal to fall back to `humanizeSentinelLine`.
 * The `base` comparison is exhaustive against the token set in
 * `commands.ts`; unknown tokens fall through so a future command
 * degrades to a sentence instead of a raw token.
 */
async function renderPanelSentinel(
  ctx: PanelCtx,
  local: string,
  rpcParams: RpcValue | undefined,
): Promise<boolean> {
  const { base, payload } = splitSentinel(local);
  const nc = ctx.noColor;

  switch (base) {
    // --- context -----------------------------------------------------
    case "__MEMORY_PANEL__":
      await panelMemory(ctx, payload || "project");
      return true;
    case "__MEMORY_EDIT__":
      panelMemoryEdit(ctx);
      return true;
    // R371: the sentinel R362's registry declared with a
    // `no-command` blocker, because nothing ever emitted it. Now
    // `/context` does, so line mode has to claim it — otherwise the
    // R362 gate fails and, worse, cmd.exe users would see the raw
    // token.
    case "__CONTEXT_METER__":
      await panelContext(ctx);
      return true;

    // --- session -----------------------------------------------------
    case "__THREAD_SELECTOR__":
      await panelThreads(ctx, false);
      return true;
    // R370 — `/sessions`. Same panel, same continuation, scoped to
    // the current cwd. The R362 gate requires BOTH renderers to claim
    // a sentinel, so an arm here is not optional: without it, typing
    // `/sessions` under cmd.exe prints the raw token.
    case "__SESSION_PICKER__":
      await panelThreads(ctx, true);
      return true;
    case "__TODO_BOARD__":
      panelTodos(ctx);
      return true;

    // --- config ------------------------------------------------------
    case "__AGENT_PICK__":
      await panelAgentPick(ctx);
      return true;
    case "__EFFORT_PICK__":
      await pickEffort(ctx);
      return true;
    case "__CWD_PICK__":
      await panelCwdPick(ctx);
      return true;
    case "__GRANTS_MANAGER__":
      await panelConsents(ctx);
      return true;
    case "__NOTIFICATION_CENTER__":
      panelNotifications(ctx);
      return true;

    // --- mcp ---------------------------------------------------------
    case "__MCP_VIEWER__":
      await panelMcp(ctx, "view", "");
      return true;
    case "__MCP_LOGIN__":
      await panelMcp(ctx, "login", payload);
      return true;
    case "__MCP_RECONNECT__":
      await panelMcp(ctx, "reconnect", payload);
      return true;

    // --- workflow ----------------------------------------------------
    case "__WORKFLOW_PICKER__":
      await panelWorkflow(ctx);
      return true;

    // --- system ------------------------------------------------------
    case "__UPDATE_AVAILABLE__":
      await panelUpdate(ctx, "check");
      return true;
    case "__UPDATE_INSTALL__":
      // R374: install is its OWN verb. It used to share the check
      // verb, which is how "is there a newer version?" and "install
      // it" ended up saying the same sentence.
      await panelUpdate(ctx, "install");
      return true;
    case "__UPDATE_DEPS__":
      await panelUpdate(ctx, "deps");
      return true;

    // --- SDD ---------------------------------------------------------
    case "__SDD_STATUS__":
      await panelSddStatus(ctx);
      return true;
    case "__SDD_START__":
      await panelSddAction(ctx, {
        title: "sdd start",
        method: "sdd.start",
        params: (rpcParams ?? { intent: payload }) as RpcValue,
      });
      return true;
    case "__SDD_ADVANCE__":
      await panelSddAction(ctx, {
        title: `sdd ${payload || "advance"}`,
        method: "sdd.advance",
        params: (rpcParams ?? { action: payload }) as RpcValue,
      });
      return true;
    case "__SDD_ABORT__":
      await panelSddAction(ctx, {
        title: "sdd abort",
        method: "sdd.abort",
        params: {} as RpcValue,
      });
      return true;
    case "__SDD_VIEW__":
    case "__SDD_DIFF__":
    case "__SDD_SNAPSHOT__":
    case "__SDD_CLEAR_CACHE__": {
      const what =
        base === "__SDD_DIFF__"
          ? "diff a phase"
          : base === "__SDD_SNAPSHOT__"
            ? "snapshot a phase"
            : base === "__SDD_CLEAR_CACHE__"
              ? "clear the phase cache"
              : "view a phase";
      emitPanel(
        ctx,
        panelUnavailable(nc, {
          title: `SDD ${base === "__SDD_CLEAR_CACHE__" ? "cache" : "phase"}`,
          subtitle: what,
          reason: `\`${what}\` reads and writes spec files under the run directory, which only the full TUI's file actions do.`,
          instead: ["/sdd-status   read the run state the daemon already tracks"],
        }),
      );
      return true;
    }

    // --- bank / audit ------------------------------------------------
    case "__BANK_STATS__":
      await panelBank(ctx, false, "", 0);
      return true;
    case "__BANK_RECALL__": {
      const i = payload.lastIndexOf(":");
      const kind = i >= 0 ? payload.slice(0, i) : payload;
      const n = i >= 0 ? Math.max(1, Number(payload.slice(i + 1)) || 3) : 3;
      await panelBank(ctx, true, kind, n);
      return true;
    }
    case "__MEMORY_AUDIT__":
      await panelMemoryAudit(ctx);
      return true;

    // --- local conveniences -------------------------------------------
    case "__TUTORIAL__":
      panelTutorial(ctx);
      return true;
    case "__BOOKMARK_LAST__":
      panelBookmark(ctx, "");
      return true;
    case "__BOOKMARK__":
      panelBookmark(ctx, payload);
      return true;
    case "__SNIPPET_LIST__":
      panelSnippet(ctx, "list", "");
      return true;
    case "__SNIPPET_SAVE__":
      panelSnippet(ctx, "save", payload);
      return true;
    case "__SNIPPET_LOAD__":
      panelSnippet(ctx, "load", payload);
      return true;
    case "__SNIPPET_DELETE__":
      panelSnippet(ctx, "delete", payload);
      return true;

    // --- Ink-only surfaces line mode has no concept of ----------------
    case "__THEMES_PICK__":
    case "__THEME__":
    case "__LAYOUT__":
    case "__BUDGET__":
      emitPanel(
        ctx,
        panelUnavailable(nc, {
          title: base
            .replace(/^__|__$/g, "")
            .replace(/_/g, " ")
            .toLowerCase(),
          subtitle: payload || undefined,
          reason:
            "line mode's palette is fixed at startup \u2014 there is no theme, layout or budget state to change here.",
          instead: ["/mode   permission mode does change in line mode"],
        }),
      );
      return true;

    default:
      return false;
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

  // R364 — reconcile the launch directory against the DAEMON's.
  //
  // `opts.cwd` is where the TUI was started, which is not necessarily
  // where the engine is rooted (the desktop spawns the daemon with its
  // own `--cwd`; a daemon restarted by someone else keeps its
  // binding). `getState` does NOT return a cwd, so before this every
  // cwd surface in line mode showed the launch directory forever.
  //
  // `listProjects` is the read that carries the truth — it returns
  // `{cwd, name, active}` with `active: true` on the current binding
  // (`AetherCodeMethods.listProjects`), and `panelCwdPick` already
  // depends on that exact shape.
  void (async () => {
    try {
      const projects = (await client.request("listProjects")) as
        Array<{ cwd?: string; active?: boolean }> | null | undefined;
      const current = Array.isArray(projects)
        ? (projects.find((p) => p?.active) ?? projects[0])
        : undefined;
      const daemonCwd = String(current?.cwd ?? "").trim();
      if (daemonCwd && daemonCwd !== displayState.cwd) displayState.cwd = daemonCwd;
    } catch {
      // Best-effort: the launch dir stays as the fallback.
    }
  })();

  let runState: "idle" | "streaming" | "running-tool" = "idle";
  let textBuffer = "";
  let textFlushTimer: NodeJS.Timeout | null = null;
  // R347: minimal turn buffer for /export. We don't try to be
  // exhaustive — just record every user prompt and the
  // assistant text_delta stream as Turn-shaped records so the
  // export helper can render Markdown. Power users can hit
  // /export and get something useful; the Ink TUI keeps the
  // richer turn model.
  const turnsBuffer: LineTurn[] = [];
  let turnCounter = 0;
  let currentUserPrompt = "";
  let currentTool: ToolTrack | null = null;
  let lastToolWas = "\u2014";
  let lastToolDigest = "";

  // R362: state the slash-command panels render from. The Ink TUI
  // keeps this in its reducer; line mode has no reducer, so the
  // two live arrays are owned here and the notification handler
  // writes into them directly.
  const todos: LineTodo[] = [];
  const notices: LineNotice[] = [];
  const snippets = new Map<string, string>();
  const bookmarks = new Set<number>();

  /** Ring-buffer the daemon's notices so /notifications has
   *  something real to show. The daemon pushes log / task_state /
   *  failure summaries; there is no query endpoint for history. */
  function recordNotice(level: LineNotice["level"], source: string, text: string): void {
    notices.push({ ts: Date.now(), level, source, text });
    if (notices.length > 200) notices.splice(0, notices.length - 200);
  }

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

  /**
   * R363 — the daemon-side run id of the turn in flight, and the
   * latch that keeps Esc from firing the `cancel` RPC twice.
   *
   * `cancel` is keyed by runId and the daemon hard-fails without one
   * (`AetherCodeMethods.cancel` reads `params.runId` unconditionally).
   * So we have to persist the id we get back from `query`, with the
   * stream notifications as a fallback.
   *
   * Line mode has no reducer, so both live as plain locals closed
   * over by the notification handler, `handlePrompt` and `onEscape`.
   */
  let activeRunId: string | null = null;
  let cancelSent = false;
  // R375 — the mirror of the daemon's per-session auto-continue flag.
  // Line mode has no reducer, so it lives here; it is cleared where
  // the daemon clears its own flag (the next user-prompt submit).
  let continuationPaused = false;

  const onRunEnd = () => {
    sawRunEnd = true;
    inFlight = Math.max(0, inFlight - 1);
    // R363 — the run is over, so its id is no longer cancellable and
    // a pending cancel is moot. Clearing BOTH here is what re-arms
    // Esc for the next turn; leaving `cancelSent` set would make the
    // first Esc of the following run a silent no-op.
    activeRunId = null;
    cancelSent = false;
    if (!rlClosed) {
      lineInput.redraw();
    }
    tryExit();
  };
  (runLineMode as unknown as { _onRunEnd?: () => void })._onRunEnd = onRunEnd;

  client.setNotificationHandler((method: string, params: RpcValue | undefined) => {
    if (method === "stream_event") {
      const p = (params ?? {}) as { event?: Record<string, unknown>; runId?: string };
      const ev = p.event ?? {};
      const t = ev.type as string | undefined;
      // R363 — runId fallback. `handlePrompt` captures the id from the
      // `query` response, but that reply can be lost (transport drop
      // between request and response) while the stream keeps flowing.
      // The notification carries the same id, so adopting it here keeps
      // Esc functional instead of stranding the user in a run they
      // cannot stop.
      if (p.runId) activeRunId = p.runId;
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
        if (textFlushTimer) {
          clearTimeout(textFlushTimer);
          textFlushTimer = null;
        }
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
        const resultStr =
          typeof ev.result === "string"
            ? ev.result
            : ev.result != null
              ? JSON.stringify(ev.result)
              : "";
        lastToolWas = name;
        lastToolDigest = resultDigest(resultStr, 2);
        const track = currentTool ?? { name, argsDigest: "", startedAt: Date.now() };
        const lines = toolDoneLine(track, isErr, lastToolDigest, noColor);
        for (const line of lines) logAbove(line);
        currentTool = null;
        runState = "streaming";
      } else if (t === "run_end") {
        if (textFlushTimer) {
          clearTimeout(textFlushTimer);
          textFlushTimer = null;
        }
        // R347: capture the full assistant text into the turn
        // buffer right BEFORE flushing so /export gets the
        // complete response (not just the unstreamed tail).
        const assistantText = textBuffer;
        flushText();
        runState = "idle";
        if (assistantText && assistantText.length > 0) {
          turnsBuffer.push({
            id: ++turnCounter,
            role: "assistant",
            text: assistantText,
            ts: Date.now(),
          });
        }
        const stopReason = String(ev.stopReason ?? "end_turn");
        const kind = classifyStopReason(stopReason);
        const tag = stopKindLabel(kind, stopReason);
        const color = stopKindColor(kind);
        logAbove(
          colorize(`  [${tag}]`, color, noColor) +
            colorize(`  (reason: ${stopReason})`, C.dim, noColor),
        );
        if (kind === "loop") {
          logAbove(
            colorize(
              "  (the model repeated the same call or hit a long-running output; daemon stopped it)",
              C.dim,
              noColor,
            ),
          );
        } else if (kind === "max_turns") {
          logAbove(
            colorize(
              "  (the run hit the per-query turn cap; raise it with --max-turns, or narrow the prompt)",
              C.dim,
              noColor,
            ),
          );
        } else if (kind === "error") {
          logAbove(colorize("  (check the daemon log on stderr for details)", C.dim, noColor));
        }
        // R362: a non-clean stop is the one thing a user genuinely
        // wants to find later, so it goes into the /notifications
        // ring buffer as well as the scrollback.
        if (kind !== "ok" && kind !== null) {
          recordNotice(kind === "error" ? "err" : "warn", "run", `stopped \u2014 ${stopReason}`);
        }
        const usage = (ev as { usage?: { input?: number; output?: number; costUsd?: number } })
          .usage;
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
          logAbove(
            colorize(
              "  \u250C\u2500 memory \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510",
              C.cyan,
              noColor,
            ),
          );
          for (const line of msg.split(/\r?\n/)) {
            logAbove(colorize("  \u2502 ", C.cyan, noColor) + colorize(line, C.dim, noColor));
          }
          logAbove(
            colorize(
              "  \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518",
              C.cyan,
              noColor,
            ),
          );
        } else {
          logAbove(colorize(`  \u00B7 ${msg}`, C.dim, noColor));
        }
      } else if (t === "plan") {
        const items = Array.isArray(ev.items) ? ev.items : [];
        const lines = items.map(
          (it, i) =>
            colorize(`  ${String(i + 1).padStart(2, " ")}. `, C.dim, noColor) +
            renderInlineMarkdown(String(it), noColor),
        );
        logAbove(
          colorize(
            `\u2731 Plan (${items.length} item${items.length === 1 ? "" : "s"}):`,
            C.cyan,
            noColor,
          ),
        );
        for (const l of lines) logAbove(l);
      }
    } else if (method === "cwd_changed") {
      // R364 — the daemon emits this on EVERY cwd change
      // (`AetherCodeMethods.switchProject` → NOTIFY_CWD_CHANGED).
      // Pre-R364 line mode had no case for it, so a `/cwd-pick` that
      // SUCCEEDED left the status bar's cwd pill showing the old
      // directory for the rest of the session — the user switched
      // project and the UI insisted nothing had moved.
      //
      // This is not redundant with the `switchProject` response: the
      // notification also covers changes this window did not
      // initiate (the `/cwd` slash command, the desktop, another
      // connected client).
      const p = (params ?? {}) as { newCwd?: string; oldCwd?: string };
      if (p.newCwd) {
        displayState.cwd = p.newCwd;
        logAbove(colorize(`  cwd \u2192 ${p.newCwd}`, C.green, noColor));
        if (!rlClosed) lineInput.redraw();
      }
    } else if (method === "log") {
      const p = (params ?? {}) as { message?: string };
      logAbove(colorize(`  [log] ${p.message ?? ""}`, C.gray, noColor));
      recordNotice("info", "log", String(p.message ?? ""));
    } else if (method === "task_state") {
      // R362: the daemon multiplexes two things onto `task_state`
      // — ordinary task transitions, and the full todo snapshot
      // the model writes (`kind=todo_update`). The snapshot is
      // STATE, not an event: the model re-sends the whole list on
      // every change, so logging it produced a `[task ? -> ?]`
      // line per update and left /todos with nothing to render.
      const p = (params ?? {}) as {
        taskId?: string;
        status?: string;
        kind?: string;
        params?: { todos?: unknown };
      };
      if (p.kind === "todo_update") {
        const incoming = asRows(p.params?.todos);
        todos.length = 0;
        for (const t of incoming) {
          todos.push({
            id: fieldStr(t, ["id"], String(todos.length + 1)),
            title: fieldStr(t, ["title", "content", "text"]),
            status: fieldStr(t, ["status"], "pending"),
            owner: fieldStr(t, ["owner"], "") || undefined,
            detail: fieldStr(t, ["detail"], "") || undefined,
          });
        }
        return;
      }
      const line = `${p.taskId ?? "?"} \u2192 ${p.status ?? "?"}`;
      logAbove(colorize(`  [task ${line}]`, C.dim, noColor));
      recordNotice("info", "task", line);
    } else if (method === "permission_request") {
      const p = (params ?? {}) as Record<string, unknown>;
      const tool = String(p.tool ?? "(unknown)");
      const input = (p.input as Record<string, unknown>) ?? {};
      const reason = String(p.reason ?? "");
      const risk = String(p.riskLevel ?? "medium").toUpperCase();
      const requestId = String(p.requestId ?? "");
      const argDigest = inputDigest(input);
      // R363 — runId fallback #2. A permission ask is proof a run is
      // live and the daemon has already stamped it with the owning
      // runId. This is the last chance to make Esc work before the
      // first token arrives. The daemon's legacy placeholder is "n/a"
      // (see AetherCodeMethods.askPermission), which is not a real id.
      const askRunId = String(p.runId ?? "");
      if (askRunId && askRunId !== "n/a") activeRunId = askRunId;
      const guidance = (() => {
        switch (risk) {
          case "CRITICAL":
            return tool === "bash"
              ? "this shell command can read, write, or delete anything on this machine"
              : tool === "file_write"
                ? "this will overwrite or create a file on disk"
                : `this is a ${tool} call that the daemon flagged as CRITICAL`;
          case "HIGH":
            return tool === "bash"
              ? "this shell command runs outside the sandbox"
              : tool === "file_write"
                ? "this will create or change a file"
                : `this is a ${tool} call that the daemon flagged as HIGH`;
          case "MEDIUM":
            return tool === "bash"
              ? "this shell command will run with your user permissions"
              : tool === "file_read"
                ? "this reads the file contents"
                : `this is a ${tool} call that the daemon flagged as MEDIUM`;
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
        const decision = await runPicker(() =>
          pickPermission(lineInput, risk, tool, argDigest, reason, guidance, noColor),
        );
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

  /**
   * R363/R375 — interrupt the run in flight. Same three rules as the Ink
   * TUI's `stopActiveRun`:
   *   1. never fire blind (no runId => say so, don't send a doomed RPC)
   *   2. never fire twice (`cancelSent` latch)
   *   3. never swallow a failed cancel (release the latch, report it)
   *
   * R375: this is the ONE implementation of "stop" in line mode too —
   * Esc, the `/stop` command and the status line's Stop button all come
   * through here.
   */
  async function stopActiveRun(explicitRunId?: string | null): Promise<void> {
    if (cancelSent) return;
    const target = explicitRunId ?? activeRunId;
    if (!target) {
      logAbove(colorize(`  ${runNothingInFlight()}`, C.yellow, noColor));
      return;
    }
    cancelSent = true;
    logAbove(colorize(`  ${runOutcome("stop", true, { runId: target })}`, C.dim, noColor));
    if (!rlClosed) lineInput.redraw();
    try {
      await client.request(RUN_STOP_METHOD, runParams("stop", { runId: target }));
      logAbove(colorize(`  interrupt sent for run ${target}`, C.dim, noColor));
    } catch (e) {
      // Either the daemon never saw this run or the transport dropped.
      // The run is not ours to stop, so release the latch — otherwise
      // Esc is dead for the rest of the session.
      cancelSent = false;
      activeRunId = null;
      logAbove(
        colorize(`  ${runOutcome("stop", false, { error: (e as Error).message })}`, C.red, noColor),
      );
    }
    if (!rlClosed) lineInput.redraw();
  }

  /**
   * R375 — pause / resume this session's automatic continuation.
   *
   * The daemon owns the flag and clears it on the next user-prompt
   * submit. Line mode has no state store, so it keeps the mirror in a
   * local variable — cleared at the same moment the daemon clears it.
   */
  async function pauseAutoContinue(paused: boolean): Promise<void> {
    const action: RunAction = paused ? "pause" : "continue";
    // R375 — idempotent, and it says so.
    if (continuationPaused === paused) {
      const already = runAlreadyInState(action);
      if (already) {
        logAbove(colorize(`  ${already}`, C.dim, noColor));
        if (!rlClosed) lineInput.redraw();
        return;
      }
    }
    try {
      await client.request(RUN_PAUSE_METHOD, runParams(action));
      continuationPaused = paused;
      logAbove(colorize(`  ${runOutcome(action, true)}`, C.dim, noColor));
    } catch (e) {
      logAbove(
        colorize(`  ${runOutcome(action, false, { error: (e as Error).message })}`, C.red, noColor),
      );
    }
    if (!rlClosed) lineInput.redraw();
  }

  const lineInput = new LineInput(
    {
      // R363 — Esc interrupts the live run. `LineInput` fires this
      // only for a BARE Esc (not a CSI sequence), so arrow keys are
      // unaffected; and `pickFromList` calls `lineInput.pause()` while
      // a picker owns the keyboard, so closing a picker still wins.
      onEscape: () => {
        if (inFlight > 0) void stopActiveRun();
      },
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
  lineInput.setInputTopRow(splashLines + 1); // +1 for the trailing "\n" we wrote

  let inFlight = 0;
  let sawRunEnd = false;
  // R346: wall-clock anchor for the currently-active turn. Set
  // on run_start, read on run_end. Zero before the first turn.
  let runStartedAt = 0;
  let exitResolve: ((code: number) => void) | null = null;
  const exitPromise = new Promise<number>((resolve) => {
    exitResolve = resolve;
  });

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
      logAbove(
        colorize(
          "(history navigation is only available in the Ink TUI; use \u2191/\u2193 in a real terminal)",
          C.dim,
          noColor,
        ),
      );
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
    if (
      slash?.local?.startsWith("__EXPORT_MD__:") ||
      slash?.local?.startsWith("__EXPORT_JSON__:")
    ) {
      // R347: line-mode export. Mirrors the Ink TUI tui.tsx
      // handler but writes straight to disk and surfaces a
      // logAbove ack instead of a toast. Empty path = the
      // default `<cwd>/.aethercode/exports/session-<id>-<YYYYMMDD-HHMM>.{md,json}`.
      const isJson = slash.local.startsWith("__EXPORT_JSON__:");
      const userPath = slash.local.slice(
        isJson ? "__EXPORT_JSON__:".length : "__EXPORT_MD__:".length,
      );
      inFlight++;
      try {
        const {
          exportToMarkdown,
          exportToJson,
          exportWithFrontmatter,
          defaultExportPath,
          writeExport,
        } = await import("./exportScrollback.js");
        const { mkdirSync } = await import("node:fs");
        const path =
          userPath && userPath.length > 0
            ? userPath
            : // R364: displayState.cwd is daemon-owned and updated on cwd_changed.
              defaultExportPath(
                displayState.cwd || opts.cwd || process.cwd(),
                displayState.sessionId,
                isJson ? "json" : "md",
              );
        mkdirSync(path.replace(/[\\/][^\\/]+$/, ""), { recursive: true });
        const body = isJson
          ? exportToJson(turnsBuffer as unknown as Parameters<typeof exportToJson>[0])
          : exportWithFrontmatter(
              turnsBuffer as unknown as Parameters<typeof exportWithFrontmatter>[0],
              displayState.model,
              displayState.sessionId,
              { cwd: displayState.cwd || opts.cwd || process.cwd() },
            );
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
      // R362: `handleSlash` returns either an internal sentinel
      // (`__MEMORY_PANEL__:project`) or an already-human sentence
      // (`usage: /model <name>`). Only the former is sanitised —
      // rewriting the latter would turn useful copy into noise.
      if (!SENTINEL_SHAPE.test(slash.local)) {
        logAbove(colorize(slash.local, C.dim, noColor));
        return;
      }
      // Register the panel as in-flight for the same reason
      // /demo and /export do: a piped run gets EOF the moment the
      // command is consumed, and without this the RPC result
      // would be cut off mid-flight by the 5s close handler.
      inFlight++;
      let handled = false;
      try {
        handled = await renderPanelSentinel(
          {
            client,
            noColor,
            lineInput,
            logAbove,
            repaint: () => {
              if (!rlClosed) lineInput.redraw();
            },
            sessionId: displayState.sessionId,
            cwd: displayState.cwd,
            state: displayState,
            todos,
            notices,
            turns: turnsBuffer,
            snippets,
            bookmarks,
          },
          slash.local,
          slash.rpcParams as RpcValue | undefined,
        );
      } finally {
        inFlight = Math.max(0, inFlight - 1);
      }
      if (!rlClosed) lineInput.redraw();
      tryExit();
      if (handled) return;
      // The fallback MUST NOT print the token. describeSentinel
      // turns it into a sentence naming what the panel was and
      // that line mode cannot open it.
      logAbove(humanizeSentinelLine(slash.local, noColor));
      return;
    }
    if (slash?.rpcMethod) {
      // R375: the run-control verbs use the same implementations the
      // Esc key uses, so line mode's `/stop` and its Esc cannot drift.
      if (slash.rpcMethod === RUN_STOP_METHOD) {
        const p = (slash.rpcParams ?? {}) as { runId?: string };
        await stopActiveRun(p.runId ?? null);
        return;
      }
      if (slash.rpcMethod === RUN_PAUSE_METHOD) {
        const p = (slash.rpcParams ?? {}) as { stopped?: boolean };
        await pauseAutoContinue(p.stopped !== false);
        return;
      }
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
    // R375 — the daemon clears its auto-continue flag on the next
    // user-prompt submit, so the mirror goes with it. A session that
    // claims to be paused while the daemon has already resumed it is
    // a lie the status line would tell.
    continuationPaused = false;
    try {
      // R363 — the `query` response carries `{runId, accepted}`; this
      // is where we learn the id that `cancel` needs. A daemon refusal
      // (`{ok:false, busyRunId}`) is adopted too, so Esc stops the run
      // that is actually blocking us instead of dead-ending.
      const resp =
        (await client.request<{
          runId?: string;
          accepted?: boolean;
          ok?: boolean;
          busyRunId?: string;
          error?: string;
        }>("query", { prompt: text })) ?? {};
      if (resp.runId) activeRunId = resp.runId;
      else if (resp.busyRunId) {
        activeRunId = resp.busyRunId;
        logAbove(colorize(`  \u26A0 ${resp.error ?? "session busy"}`, C.yellow, noColor));
        logAbove(colorize(`  esc interrupts run ${resp.busyRunId}`, C.dim, noColor));
      }
      if (resp.ok === false || resp.accepted === false) {
        // The daemon refused the turn. Do NOT count it as in-flight —
        // there is no run to watch, so leaving inFlight at 1 would hang
        // the close handler's 5s wait on every subsequent EOF.
        activeRunId = null;
        inFlight--;
        if (!rlClosed) lineInput.redraw();
        tryExit();
      }
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
    const say = (text: string, color: string = C.dim) =>
      logAbove(colorize("  " + text, color, noColor));
    const sayMarkdown = (text: string) => logAbove("  " + renderInlineMarkdown(text, noColor));
    logAbove("");
    say("(demo) starting canned conversation", C.gray);
    sayMarkdown("use the file_read tool to look at package.json");
    await sleep(300);
    currentTool = { name: "file_read", argsDigest: "package.json", startedAt: Date.now() };
    for (const line of toolStartLine(currentTool, noColor)) logAbove(line);
    await sleep(400);
    {
      const track = currentTool ?? {
        name: "file_read",
        argsDigest: "package.json",
        startedAt: Date.now(),
      };
      for (const l of toolDoneLine(
        track,
        false,
        resultDigest('{ "name": "aethercode-tui", "version": "0.2.1" }', 2),
        noColor,
      )) {
        logAbove(l);
      }
      currentTool = null;
    }
    await sleep(400);
    sayMarkdown(
      "Sure \u2014 let me read the manifest. The TUI is built on **Ink 5** (React for CLI) and bundles into ~2.3 MB.",
    );
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
    const decisionLabel = (
      {
        allow: "1\u2003allow once",
        always_allow: "2\u2003always for this session",
        deny: "3\u2004deny once",
        always_deny: "4\u2004always deny",
      } as const
    )[decision];
    say(
      `(demo) you picked [${decisionLabel}] \u2014 ${decision === "allow" || decision === "always_allow" ? "tool allowed" : "tool denied"}`,
      C.cyan,
    );

    {
      const track = currentTool ?? {
        name: "file_write",
        argsDigest: "package.json",
        startedAt: Date.now(),
      };
      const ok = decision === "allow" || decision === "always_allow";
      const resultText = ok ? "wrote 47 bytes to package.json" : "denied by user (demo)";
      for (const l of toolDoneLine(track, !ok, resultDigest(resultText, 1), noColor)) {
        logAbove(l);
      }
      currentTool = null;
    }
    await sleep(400);
    sayMarkdown("Updated the manifest and bumped the patch version. Ready for review.");
    logAbove(
      colorize("  [\u2713 ready]", C.green, noColor) +
        colorize("  (reason: end_turn)", C.dim, noColor),
    );
    await sleep(400);
    say("(demo) conversation complete \u2014 restart to clear", C.gray);
    if (!rlClosed) lineInput.redraw();
    // R346: drain the setTimeout chain (longest step is ~3.3s)
    // before allowing the close handler to exit. Without this,
    // piped / stdin-EOF runs are cut off after the first step.
    await sleep(400);
  }
}
