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
 * R389 — the chat block model.
 *
 * ★ WHY THIS FILE EXISTS ★
 *
 * The desktop's centre column is NOT a wall of cards. It is a flat
 * markdown document:
 *
 *     ## <sub-task title>          ← always visible, the anchor
 *     ▸ 思考 · <first 80 chars>    ← folded block, expandable
 *     ▸ file_read · src/App.tsx    ← folded block, expandable
 *     ▸ 结果 · completed           ← always open, the summary
 *
 * The TUI rendered something else entirely: a dim "Assistant" label
 * above every reply, a dim "Thinking" label above every think, and a
 * BORDERED BOX around every tool call. Three chrome elements per
 * turn, and the boxes made a 40-turn session read like a stack of
 * crates instead of a conversation.
 *
 * ★ THE RULE LIVES HERE, NOT IN A COMPONENT ★
 *
 * This module is pure: no Ink, no DOM, no terminal. It decides what
 * the transcript IS — the block kinds, their order, and which ones
 * start open. The renderer decides how a block looks.
 *
 * That split is deliberate: the desktop's `MessageList.tsx` already
 * has the same two layers (a `buildBlocks` + `defaultOpenFor` pair
 * that is DOM-free, and a `BlockView` that is not). When the desktop
 * imports this module, the two renderers stop being able to describe
 * the same conversation differently — which is the failure mode this
 * repo's R377–R390 rounds have been closing one surface at a time.
 *
 * ★ WHAT THIS MODULE DELIBERATELY DOES NOT DO ★
 *
 * It does not re-derive anything from the wire. The daemon already
 * says which sub-task is open (`sub_task_start` / `sub_task_end`) and
 * the reducer already stamps each turn with the sub-task it belongs
 * to. This module only GROUPS what the reducer produced.
 */

import type { SubTaskView, Turn } from "../state.js";
import { fileChangeRuns } from "../fileChanges.js";

/* ===================================================================
 * Diff
 *
 * Ported from the desktop's `MessageList.tsx` so a `file_edit` shows
 * a real +/- diff in the terminal too, instead of the tool's
 * "edited /path (N replacements)" one-liner.
 * =================================================================== */

export type DiffSegment =
  | { kind: "meta"; text: string }
  | { kind: "add"; text: string }
  | { kind: "del"; text: string }
  | { kind: "ctx"; text: string }
  | { kind: "elided"; count: number };

/** Maximum segments kept in a snippet view. */
const DIFF_MAX_LINES = 50;
/** A run of more than this many context lines is collapsed. */
const DIFF_CTX_COLLAPSE_THRESHOLD = 4;

/**
 * Parse a unified diff into typed segments.
 *
 * Tolerant of malformed input: an unrecognised line becomes `ctx`
 * so the renderer still has something to show.
 */
export function summarizeDiff(diff: string): DiffSegment[] {
  if (!diff) return [];
  const raw = diff.split(/\r?\n/);
  const out: DiffSegment[] = [];
  let i = 0;
  // The leading "--- a/path" / "+++ b/path" headers become ONE meta
  // block — the renderer collapses them, but the first "---" stays so
  // the file name is visible in the snippet.
  let sawFileHeader = false;
  while (i < raw.length) {
    const line = raw[i];
    if (line.startsWith("---") && !sawFileHeader) {
      out.push({ kind: "meta", text: line });
      sawFileHeader = true;
      i++;
      if (i < raw.length && raw[i].startsWith("+++")) i++;
      continue;
    }
    if (line.startsWith("+++") && sawFileHeader) {
      i++;
      continue;
    }
    if (line.startsWith("@@")) {
      out.push({ kind: "meta", text: line });
      i++;
      continue;
    }
    if (line.startsWith("+")) {
      out.push({ kind: "add", text: line });
      i++;
      continue;
    }
    if (line.startsWith("-")) {
      out.push({ kind: "del", text: line });
      i++;
      continue;
    }
    if (
      line === "" ||
      line.startsWith(" ") ||
      (!line.startsWith("+") && !line.startsWith("-") && !line.startsWith("@"))
    ) {
      // Collect runs of context / blank lines.
      const runStart = i;
      while (i < raw.length) {
        const l = raw[i];
        if (l.startsWith("+") || l.startsWith("-") || l.startsWith("@@")) break;
        i++;
      }
      const runLength = i - runStart;
      if (runLength <= DIFF_CTX_COLLAPSE_THRESHOLD) {
        for (let j = runStart; j < i; j++) {
          out.push({ kind: "ctx", text: raw[j] || " " });
        }
      } else {
        // Long run: keep the first 2 + last 2, elide the middle.
        const keep = 2;
        for (let j = runStart; j < runStart + keep; j++) {
          out.push({ kind: "ctx", text: raw[j] || " " });
        }
        out.push({ kind: "elided", count: runLength - 2 * keep });
        for (let j = i - keep; j < i; j++) {
          out.push({ kind: "ctx", text: raw[j] || " " });
        }
      }
      continue;
    }
    // Unknown — treat as ctx so nothing is dropped.
    out.push({ kind: "ctx", text: line });
    i++;
  }
  if (out.length > DIFF_MAX_LINES) {
    const headCount = DIFF_MAX_LINES - 5;
    const tail = out.slice(out.length - 5);
    const elided = out.length - headCount - tail.length;
    return [...out.slice(0, headCount), { kind: "elided", count: elided }, ...tail];
  }
  return out;
}

/** The hard ceiling on the raw file_edit input, in characters. */
const HARD_MAX_INPUT_CHARS = 32_000;

/**
 * Build a unified diff from a `file_edit` tool's raw input.
 *
 * Returns null when the input does not carry BOTH `old_string` and
 * `new_string` — the caller then falls back to the tool's own output
 * line rather than inventing a diff.
 */
export function buildEditSnippetFromInput(input: unknown): string | null {
  if (!input || typeof input !== "object") return null;
  const obj = input as Record<string, unknown>;
  const oldStr = typeof obj.old_string === "string" ? capInput(obj.old_string) : null;
  const newStr = typeof obj.new_string === "string" ? capInput(obj.new_string) : null;
  const path = typeof obj.file_path === "string" ? obj.file_path.slice(0, 200) : null;
  if (oldStr == null || newStr == null) return null;
  const oldLines = oldStr.split(/\r?\n/);
  const newLines = newStr.split(/\r?\n/);
  const head = path ? `--- a/${path}\n+++ b/${path}\n` : "";
  const hunk = `@@ -1,${oldLines.length} +1,${newLines.length} @@`;
  const body = [...oldLines.map((l) => `-${l}`), ...newLines.map((l) => `+${l}`)].join("\n");
  return head + hunk + "\n" + body;
}

function capInput(s: string): string {
  return s.length > HARD_MAX_INPUT_CHARS ? s.slice(0, HARD_MAX_INPUT_CHARS) : s;
}

/* ===================================================================
 * The block model
 * =================================================================== */

export type ChatBlock =
  /** The sub-task title — the `##` anchor of a group. */
  | { kind: "header"; id: string; title: string }
  /** A `<think>` block. Folded by default. */
  | { kind: "think"; id: string; turnId: number; md: string }
  /** The model's reply. Always open — this is the answer. */
  | { kind: "answer"; id: string; turnId: number; md: string }
  /** A tool call. Folded by default. */
  | { kind: "tool"; id: string; turnId: number }
  /**
   * A run of consecutive file-changing tool calls, collapsed into ONE
   * summary row.
   *
   * <p>R389: the desktop's chat column shows one `file_edit` row per
   * call. The TUI has the end-of-turn summary card instead (R388q:
   * "已编辑 19 个文件 +2235 -36"), and the card's grouping is the
   * shared rule in `fileChanges.ts` — so the block model uses that
   * same rule, rather than deciding for itself which calls are file
   * changes.
   */
  | { kind: "files"; id: string; turnIds: number[] }
  /** A finished sub-task's summary. Always open. */
  | { kind: "result"; id: string; title: string; status: string; md: string };

/** One contiguous run of agent output — a preamble, or a sub-task. */
export interface AgentGroup {
  kind: "preamble" | "subtask";
  /** The sub-task's composite id (`taskId:subTaskId`), or "" for a preamble. */
  id: string;
  /** The sub-task title (the `##` heading). Null for a preamble. */
  title: string | null;
  /** The sub-task's last known status. Null for a preamble. */
  status: string | null;
  /** The sub-task's closing summary. Null until it closes. */
  summary: string | null;
  blocks: ChatBlock[];
}

/**
 * The transcript's timeline.
 *
 * ★ CHRONOLOGICAL, NOT GROUPED BY TYPE ★ The desktop once rendered
 * all user messages, then all agent blocks, then all system messages
 * — so the engine's `[loop_warn]` and `[todo-step-bump]` notes floated
 * to the bottom and the user lost the order of events. The timeline
 * interleaves everything by timestamp.
 */
export type TimelineEvent =
  | { kind: "user"; ts: number; turn: Turn }
  | { kind: "system"; ts: number; turn: Turn }
  | { kind: "plan"; ts: number; turn: Turn }
  | { kind: "agent"; ts: number; group: AgentGroup };

/** Roles that belong to the agent's output stream. */
const AGENT_ROLES = new Set(["assistant", "thinking", "tool"]);

/**
 * Build the transcript's timeline.
 *
 * The turns are already in emission order, so the timeline is a
 * single walk: user / system / plan turns become their own events,
 * and the agent turns are collected into groups. A group breaks when
 * the sub-task changes, or when a non-agent turn separates two agent
 * turns.
 */
export function buildTimeline(
  turns: readonly Turn[],
  subTasks: readonly SubTaskView[],
  fileRuns?: ReadonlyMap<number, readonly number[]>,
): TimelineEvent[] {
  const subById = new Map<string, SubTaskView>();
  for (const st of subTasks) subById.set(st.id, st);

  // R389: the file-change runs are computed ONCE, by the shared rule,
  // so the card the user sees and the card the `u` key acts on are
  // the same card. The caller may pass the runs it already computed
  // (the Scrollback's `groupTurns` does); otherwise they are derived
  // here from the same rule.
  const runs = new Map<number, number[]>();
  const runOwner = new Map<number, number>();
  const source =
    fileRuns ?? new Map(fileChangeRuns(turns).map((r) => [r.startId, r.turnIds] as const));
  for (const [startId, ids] of source) {
    runs.set(startId, [...ids]);
    for (const id of ids) runOwner.set(id, startId);
  }
  const emittedRuns = new Set<number>();

  const events: TimelineEvent[] = [];
  let group: AgentGroup | null = null;
  let groupStart = 0;

  const flush = () => {
    if (group && group.blocks.length > 0) {
      events.push({ kind: "agent", ts: groupStart, group });
    }
    group = null;
  };

  for (const turn of turns) {
    if (!AGENT_ROLES.has(turn.role)) {
      flush();
      if (turn.role === "user") events.push({ kind: "user", ts: turn.ts, turn });
      else if (turn.role === "plan") events.push({ kind: "plan", ts: turn.ts, turn });
      else if (turn.role === "system" || turn.role === "error") {
        events.push({ kind: "system", ts: turn.ts, turn });
      }
      continue;
    }
    const subId = turn.subTaskId ?? "";
    if (!group || group.id !== subId) {
      flush();
      const sub = subById.get(subId);
      group = {
        kind: sub ? "subtask" : "preamble",
        id: subId,
        title: sub?.content ?? null,
        status: sub?.status ?? null,
        summary: sub?.summary ?? null,
        blocks: [],
      };
      groupStart = turn.ts;
    }
    // A run of file-changing tool calls collapses into ONE summary
    // block, emitted at the run's FIRST VISIBLE turn. (The first
    // VISIBLE, not the first overall: the transcript is sliced to a
    // row budget, so the run's head may be off-screen — and a card
    // that only appears when its head is on screen would make the
    // summary flicker as the user scrolls.)
    if (turn.role === "tool") {
      const owner = runOwner.get(turn.id);
      if (owner !== undefined) {
        if (!emittedRuns.has(owner)) {
          emittedRuns.add(owner);
          group.blocks.push({
            kind: "files",
            id: `fc-${owner}`,
            turnIds: runs.get(owner) ?? [turn.id],
          });
        }
        continue;
      }
    }
    group.blocks.push(blockForTurn(turn));
  }
  flush();

  // A sub-task's `##` heading and its `结果` block are properties of
  // the SUB-TASK, not of the turn stream, so they are attached here —
  // after the turns are grouped — rather than being emitted inline.
  for (const ev of events) {
    if (ev.kind !== "agent") continue;
    const g = ev.group;
    if (g.kind !== "subtask") continue;
    const sub = subById.get(g.id);
    if (!sub) continue;
    g.title = sub.content || null;
    g.status = sub.status;
    g.summary = sub.summary ?? null;
    if (g.title) {
      g.blocks.unshift({ kind: "header", id: `hdr-${g.id}`, title: g.title });
    }
    if (g.summary) {
      g.blocks.push({
        kind: "result",
        id: `res-${g.id}`,
        title: "结果",
        status: g.status ?? "completed",
        md: g.summary,
      });
    }
  }

  return events;
}

function blockForTurn(turn: Turn): ChatBlock {
  if (turn.role === "tool") {
    return { kind: "tool", id: `ev-${turn.id}`, turnId: turn.id };
  }
  if (turn.role === "thinking") {
    return { kind: "think", id: `t-${turn.id}`, turnId: turn.id, md: turn.text };
  }
  return { kind: "answer", id: `a-${turn.id}`, turnId: turn.id, md: turn.text };
}

/* ===================================================================
 * The fold policy
 * =================================================================== */

/**
 * Should this block be expanded?
 *
 * ★ THE DESKTOP'S RULE, PLUS THE TERMINAL'S OWN STATE ★
 *
 * The desktop's rule:
 *
 *   - the sub-task heading, the result block and the file-changes
 *     summary are ALWAYS open (they are the anchors the user
 *     navigates by);
 *   - the FINAL think is ALWAYS open — it is the model's concluding
 *     reasoning, and the user always wants to see it;
 *   - while the run is live, the LAST block is open, so new output
 *     arrives visible without a click;
 *   - everything else is folded, because 50 short thinks in a long
 *     task otherwise turn the panel into a wall of text.
 *
 * The terminal's addition: a fold is the TURN's own state, so the
 * user can open and close it (Tab / Ctrl-E). A terminal has no
 * `<details>` element, so the fold has to live somewhere — and the
 * turn is where it already was.
 *
 * So the user's own choice wins, EXCEPT for the two blocks the
 * desktop refuses to hide (the final think, and the live tail).
 */
export function resolveOpen(
  block: ChatBlock,
  opts: {
    /** the turn behind the block, when it has one. */
    turn?: Turn;
    isLast: boolean;
    isFinalThink: boolean;
    isLive: boolean;
    /** the global "collapse everything" toggle. */
    collapseAll: boolean;
  },
): boolean {
  // The global toggle wins over everything: it is an explicit act.
  if (opts.collapseAll) return false;
  if (block.kind === "header" || block.kind === "result" || block.kind === "files") {
    return true;
  }
  if (block.kind === "answer") {
    return opts.turn ? !opts.turn.collapsed : true;
  }
  // think + tool
  const forced = (block.kind === "think" && opts.isFinalThink) || (opts.isLive && opts.isLast);
  if (forced) return true;
  return opts.turn ? !opts.turn.collapsed : false;
}

/**
 * The index of the LAST think block in a group, or -1.
 *
 * The desktop scans the blocks backwards for the last `think` — that
 * one is "the final think", the model's answer-side reasoning.
 */
export function finalThinkIndex(blocks: readonly ChatBlock[]): number {
  for (let i = blocks.length - 1; i >= 0; i--) {
    if (blocks[i].kind === "think") return i;
  }
  return -1;
}

/**
 * The one-line preview of a think block — what the folded row shows.
 *
 * The desktop strips markdown decoration and caps at 80 chars.
 */
export function thinkPreview(md: string, max = 80): string {
  const flat = md
    .replace(/^#+\s*/gm, "")
    .replace(/[*_`>]/g, "")
    .replace(/\s+/g, " ")
    .trim();
  if (flat.length <= max) return flat;
  return flat.slice(0, Math.max(0, max - 1)) + "…";
}

/**
 * The one-line preview of a tool call — what the folded row shows.
 *
 * The tool's input arrives as a JSON string; the preview is the
 * interesting field of it (a path, a command, a pattern), not the
 * whole blob.
 */
export function toolPreview(name: string | undefined, args: string | undefined, max = 72): string {
  if (!args) return "";
  let parsed: unknown;
  try {
    parsed = JSON.parse(args);
  } catch {
    parsed = undefined;
  }
  if (parsed && typeof parsed === "object") {
    const obj = parsed as Record<string, unknown>;
    const preferred = PREFERRED_INPUT_FIELDS[name ?? ""] ?? [
      "file_path",
      "path",
      "command",
      "pattern",
      "query",
      "url",
    ];
    for (const key of preferred) {
      const v = obj[key];
      if (typeof v === "string" && v.trim()) return clip(v.replace(/\s+/g, " ").trim(), max);
    }
  }
  const raw = args.replace(/\s+/g, " ").replace(/^\{/, "").replace(/\}$/, "").trim();
  return clip(raw, max);
}

const PREFERRED_INPUT_FIELDS: Record<string, string[]> = {
  file_read: ["file_path", "path"],
  read_file: ["file_path", "path"],
  file_write: ["file_path", "path"],
  write_file: ["file_path", "path"],
  file_edit: ["file_path", "path"],
  edit_file: ["file_path", "path"],
  bash: ["command", "cmd"],
  shell: ["command", "cmd"],
  grep: ["pattern", "query", "regex"],
  search: ["pattern", "query", "regex"],
  glob: ["pattern", "path"],
  web_fetch: ["url", "uri"],
  web_search: ["query", "q"],
};

function clip(s: string, max: number): string {
  if (s.length <= max) return s;
  return s.slice(0, Math.max(0, max - 1)) + "…";
}
