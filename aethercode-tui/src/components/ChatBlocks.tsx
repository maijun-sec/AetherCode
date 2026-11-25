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
 * R389 — the chat column's renderer.
 *
 * ★ WHAT IT RENDERS ★
 *
 * The desktop's centre column is a flat document:
 *
 *     ## Sub-task title
 *     ▾ 思考 · the model worked out the plan…
 *       <the think, as markdown>
 *     ▸ file_read · src/App.tsx        1.4s
 *     ▾ 结果 · completed
 *       <the summary, as markdown>
 *
 * This component draws exactly that. No bordered boxes, no per-turn
 * "Assistant" label, no per-turn clock — the three pieces of chrome
 * that made the TUI read like a stack of crates instead of a
 * conversation.
 *
 * ★ THE FOLD STATE IS THE TURN'S OWN ★
 *
 * A folded think / tool row is expanded by pressing the SAME key that
 * expanded it before (Tab / Ctrl-E), because the state lives on the
 * turn, not in a parallel "which blocks are open" array. Two arrays
 * would be two truths about the same thing.
 */

import React from "react";
import { Box, Text } from "ink";
import type { Turn } from "../state.js";
import { formatDuration } from "../state.js";
import { t, icon } from "../theme.js";
import { Markdown } from "./Markdown.js";
import { SummaryFooter, splitAssistantMessage } from "./chat/SummaryFooter.js";
import {
  buildEditSnippetFromInput,
  finalThinkIndex,
  resolveOpen,
  summarizeDiff,
  thinkPreview,
  toolPreview,
  type AgentGroup,
  type ChatBlock,
  type DiffSegment,
} from "../chat/blocks.js";
import { CATEGORY_COLOR, CATEGORY_ICON } from "./ToolCard.js";
import { FileChangesCard } from "./FileChangesCard.js";
import { fileChangeOfTurnParts, summarizeFileChanges } from "../fileChanges.js";

interface Props {
  group: AgentGroup;
  /** The turns, keyed by id — the blocks carry ids, not payloads. */
  turnsById: Map<number, Turn>;
  /** is the run still in flight? */
  isLive: boolean;
  /** the global "collapse everything" toggle. */
  collapseAll: boolean;
  /** the reading column's width, in cells. */
  width: number;
  /** R388r: the file-changes card's interaction surface. */
  focusedFileCardTurnId?: number;
  fileCardActionsLive?: boolean;
  onShowFileCardFiles?: (turnId: number) => void;
  onUndoFileCard?: (turnId: number) => void;
  onReviewFileCard?: (turnId: number) => void;
}

/** the one-line body of a folded row. */
const FOLD_ROW_INDENT = 2;

export const ChatBlocks: React.FC<Props> = ({
  group,
  turnsById,
  isLive,
  collapseAll,
  width,
  focusedFileCardTurnId,
  fileCardActionsLive = true,
  onShowFileCardFiles,
  onUndoFileCard,
  onReviewFileCard,
}) => {
  const finalIdx = finalThinkIndex(group.blocks);
  return (
    <Box flexDirection="column">
      {group.blocks.map((block, i) => {
        const turn =
          block.kind === "tool" || block.kind === "think" || block.kind === "answer"
            ? turnsById.get(block.turnId)
            : undefined;
        const open = resolveOpen(block, {
          turn,
          isLast: i === group.blocks.length - 1,
          isFinalThink: i === finalIdx,
          isLive,
          collapseAll,
        });
        return (
          <BlockView
            key={block.id}
            block={block}
            turn={turn}
            turnsById={turnsById}
            open={open && !collapseAll}
            width={width}
            focusedFileCardTurnId={focusedFileCardTurnId}
            fileCardActionsLive={fileCardActionsLive}
            onShowFileCardFiles={onShowFileCardFiles}
            onUndoFileCard={onUndoFileCard}
            onReviewFileCard={onReviewFileCard}
          />
        );
      })}
    </Box>
  );
};

const BlockView: React.FC<{
  block: ChatBlock;
  turn: Turn | undefined;
  turnsById: Map<number, Turn>;
  open: boolean;
  width: number;
  focusedFileCardTurnId?: number;
  fileCardActionsLive?: boolean;
  onShowFileCardFiles?: (turnId: number) => void;
  onUndoFileCard?: (turnId: number) => void;
  onReviewFileCard?: (turnId: number) => void;
}> = ({
  block,
  turn,
  turnsById,
  open,
  width,
  focusedFileCardTurnId,
  fileCardActionsLive,
  onShowFileCardFiles,
  onUndoFileCard,
  onReviewFileCard,
}) => {
  if (block.kind === "header") {
    // The sub-task title — the `##` anchor of the group. The
    // desktop renders it as an `<h2>`; the terminal renders it as
    // a bold, coloured heading with NO `#` prefix, because the
    // Markdown renderer does the same for a document heading.
    return (
      <Box marginTop={1}>
        <Text bold color={t.heading2}>
          {block.title}
        </Text>
      </Box>
    );
  }

  if (block.kind === "result") {
    return (
      <Box flexDirection="column" marginTop={0}>
        <FoldRow
          open
          label="结果"
          detail={block.status}
          status={block.status === "failed" ? "error" : "ok"}
        />
        <Box paddingLeft={FOLD_ROW_INDENT}>
          <Markdown text={block.md} />
        </Box>
      </Box>
    );
  }

  if (block.kind === "answer") {
    // The model's reply. Always visible by default — this is the
    // answer, and the desktop's rule is that the final reasoning is
    // never folded away.
    //
    // The `## Summary` footer is the TUI's own convention (T-6-14):
    // the summary is pulled out of the body and rendered last, so it
    // is always on screen even when the body is long.
    const { lead, summary } = splitAssistantMessage(block.md);
    const body = lead.trim();
    if (!body && !summary) return null;
    // The user folded it (Tab) — show the one-line preview, the way
    // the old per-turn renderer did.
    if (!open) {
      return (
        <Box flexDirection="column">
          <Text dimColor>
            {truncate(body.replace(/\n+/g, " "), turn?.previewChars ?? 240)} · Tab to expand
          </Text>
          <SummaryFooter summary={summary} />
        </Box>
      );
    }
    return (
      <Box flexDirection="column">
        {body ? <Markdown text={body} /> : null}
        <SummaryFooter summary={summary} />
      </Box>
    );
  }

  if (block.kind === "think") {
    const preview = thinkPreview(block.md);
    return (
      <Box flexDirection="column">
        <FoldRow open={open} label="思考" detail={preview} status="neutral" />
        {open ? (
          <Box paddingLeft={FOLD_ROW_INDENT}>
            <Markdown text={block.md} />
          </Box>
        ) : null}
      </Box>
    );
  }

  if (block.kind === "files") {
    // The file-changes summary — R388q's card, now living INSIDE the
    // agent block instead of floating between the turns. The card's
    // grouping is the shared rule, so the card the user sees and the
    // card the `u` key acts on are the same card.
    const first = turnsById.get(block.turnIds[0]);
    if (!first) return null;
    const changes = block.turnIds
      .map((id) => turnsById.get(id))
      .filter((t): t is Turn => !!t)
      .map((t) => fileChangeOfTurnParts(t.role, t.toolName, t.toolArgs))
      .filter((c): c is NonNullable<typeof c> => !!c);
    if (changes.length === 0) return null;
    return (
      <Box flexDirection="column" marginTop={0}>
        <FileChangesCard
          summary={summarizeFileChanges(changes)}
          width={Math.max(20, width - 2)}
          expanded={!first.collapsed}
          focused={focusedFileCardTurnId === first.id}
          actionsLive={fileCardActionsLive}
          onShowFiles={() => onShowFileCardFiles?.(first.id)}
          onUndo={() => onUndoFileCard?.(first.id)}
          onReview={() => onReviewFileCard?.(first.id)}
        />
      </Box>
    );
  }

  // block.kind === "tool"
  if (!turn) return null;
  return <ToolBlock turn={turn} open={open} width={width} />;
};
/**
 * One folded row.
 *
 * `▸` when folded, `▾` when open — the same affordance the desktop's
 * `<details>`/`<summary>` gives, minus the DOM.
 */
const FoldRow: React.FC<{
  open: boolean;
  label: string;
  detail?: string;
  status: "ok" | "error" | "neutral";
}> = ({ open, label, detail, status }) => {
  const color = status === "error" ? t.err : status === "ok" ? t.ok : t.dim;
  return (
    <Box>
      <Text color={t.dim}>{open ? "▾ " : "▸ "}</Text>
      <Text bold color={status === "neutral" ? t.asst : color}>
        {label}
      </Text>
      {detail ? <Text dimColor> {detail}</Text> : null}
    </Box>
  );
};

/**
 * A tool call.
 *
 * The folded form is one line: category icon, tool name, the
 * interesting field of the input, and the duration. That is the
 * desktop's `<summary>` row.
 */
const ToolBlock: React.FC<{ turn: Turn; open: boolean; width: number }> = ({
  turn,
  open,
  width,
}) => {
  const status = turn.toolStatus ?? "running";
  const cat = turn.toolCategory ?? "other";
  const catIcon = CATEGORY_ICON[cat];
  const catColor = CATEGORY_COLOR[cat];
  const name = turn.toolName ?? "tool";
  const preview = toolPreview(name, turn.toolArgs);
  const duration =
    turn.toolStartedAt == null
      ? ""
      : formatDuration((turn.toolEndedAt ?? Date.now()) - turn.toolStartedAt);
  const statusMark = status === "running" ? icon.running : status === "error" ? icon.err : icon.ok;
  const statusColor = status === "running" ? catColor : status === "error" ? t.err : t.ok;

  return (
    <Box flexDirection="column">
      <Box>
        <Text color={t.dim}>{open ? "▾ " : "▸ "}</Text>
        <Text color={statusColor}>{statusMark} </Text>
        <Text color={catColor}>{catIcon} </Text>
        <Text bold>{name}</Text>
        {preview ? <Text dimColor> {preview}</Text> : null}
        {duration ? <Text dimColor> {duration}</Text> : null}
        {/* The old boxed card advertised its keys on the open row.
            The flat row keeps that, so the affordance does not
            disappear with the border. */}
        {open ? <Text dimColor> · d: 详情</Text> : null}
      </Box>
      {open ? <ToolBody turn={turn} width={width} /> : null}
    </Box>
  );
};

/**
 * The expanded body of a tool call: the input, the output, and — for
 * `file_edit` — a REAL diff built from the tool's raw
 * `old_string` / `new_string`.
 *
 * ★ WHY THE DIFF IS BUILT HERE ★ The daemon's `tool_result` for a
 * file_edit says "edited /path (3 replacements)". The desktop builds
 * a unified diff client-side from the `tool_use_start` input; the
 * TUI used to show the one-liner. Same rule, same place: the diff
 * comes from the input the model actually sent.
 *
 * ★ AND THE `d` KEY STILL WORKS ★ The old boxed card had a `d` key
 * that expanded the full args + full result. That affordance is
 * R34's, so it survives the move to the flat row: `toolExpanded`
 * turns the TRUNCATED preview into the FULL payload. A key that
 * silently stopped doing anything would be a regression the user
 * would feel, not one the gates would catch.
 */
const ToolBody: React.FC<{ turn: Turn; width: number }> = ({ turn, width }) => {
  const name = turn.toolName ?? "tool";
  const diff = EDIT_TOOLS.has(name) ? buildEditSnippetFromInput(parseArgs(turn.toolArgs)) : null;
  const expanded = turn.toolExpanded === true;
  const args = expanded ? (turn.toolArgs ?? "") : truncate(turn.toolArgs ?? "", 1200);
  const result = expanded ? (turn.toolResult ?? "") : truncate(turn.toolResult ?? "", 4000);

  return (
    <Box flexDirection="column" paddingLeft={FOLD_ROW_INDENT}>
      {args ? (
        <Box flexDirection="column">
          <Text dimColor>{icon.detail} 输入</Text>
          <Text color={t.code}>{args}</Text>
        </Box>
      ) : null}
      {diff ? (
        <Box flexDirection="column" marginTop={0}>
          <Text dimColor>{icon.detail} 变更</Text>
          <DiffView segments={summarizeDiff(diff)} />
        </Box>
      ) : result ? (
        <Box flexDirection="column" marginTop={0}>
          <Text dimColor>{icon.detail} 输出</Text>
          <Markdown text={result} />
        </Box>
      ) : null}
    </Box>
  );
};

/** the tools whose input carries a real diff. */
const EDIT_TOOLS = new Set(["file_edit", "edit_file", "FileEdit", "str_replace_editor"]);

function parseArgs(args: string | undefined): unknown {
  if (!args) return null;
  try {
    return JSON.parse(args);
  } catch {
    return null;
  }
}

/**
 * The diff, coloured.
 *
 * `+` green, `-` red, context dim, the hunk header dim. The elision
 * marker says how many lines were folded away, so a truncated diff
 * never looks like a complete one.
 */
const DiffView: React.FC<{ segments: DiffSegment[] }> = ({ segments }) => {
  return (
    <Box flexDirection="column">
      {segments.map((seg, i) => {
        if (seg.kind === "elided") {
          return (
            <Text key={i} dimColor>
              {`  … ${seg.count} 行未显示`}
            </Text>
          );
        }
        const color =
          seg.kind === "add"
            ? t.ok
            : seg.kind === "del"
              ? t.err
              : seg.kind === "meta"
                ? t.dim
                : undefined;
        return (
          <Text key={i} color={color} dimColor={seg.kind === "ctx" ? true : undefined}>
            {seg.text}
          </Text>
        );
      })}
    </Box>
  );
};

function truncate(s: string, n: number): string {
  if (s.length <= n) return s;
  return s.slice(0, Math.max(0, n - 1)) + "…";
}
