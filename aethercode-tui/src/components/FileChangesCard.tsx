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
 * R388q — the FILE-CHANGES card.
 *
 * <p>★ WHAT IT ANSWERS ★ One line that says what the turn DID:
 *
 * <pre>
 *   ╭────────────────────────────────────────────────────────────────╮
 *   │ ✎ 已编辑 19 个文件 +2235 -36 │ 查看文件 [撤销 u] [审查 r]        │
 *   ╰────────────────────────────────────────────────────────────────╯
 * </pre>
 *
 * <p>★ THE SHAPE IS THE POINT ★ The desktop's card has seven parts,
 * and each one carries information:
 *
 * <ol>
 *   <li>an ICON in its own cell — the category at a glance;</li>
 *   <li>a BOLD title — "已编辑 19 个文件" — the count is the headline,
 *       because "how many files" is the question;</li>
 *   <li>the DIFF COUNTS in colour: green {@code +N}, red {@code -N}.
 *       Green and red are the ONLY colours in the card that carry
 *       meaning, so they must not be muted;</li>
 *   <li>a SEPARATOR — the counts and the actions are different kinds
 *       of thing and must not run together;</li>
 *   <li>a MUTED LINK — "查看文件" — the way to see the detail;</li>
 *   <li>TWO ACTIONS — each with its KEY, so a keyboard-only session
 *       can still drive them.</li>
 * </ol>
 *
 * <p>★ ONE CARD FOR MANY FILES ★ Nineteen files, ONE card. A card
 * per file would be a wall of noise and would not answer "what did
 * this turn change?" — the per-file detail lives behind the expander.
 *
 * <p>★ ROUNDED BORDERS ★ The card's frame is `round`, like the input
 * pill and the markdown blocks — the app's idiom for a FRAMED
 * SURFACE. A square frame reads as a table cell; a rounded one reads
 * as a card.
 *
 * <p>★ A TERMINAL CANNOT INLINE A BORDERED BOX ★ The desktop draws
 * "撤销" and "审查" as two outlined boxes side by side. A terminal
 * cannot: a bordered Ink Box occupies TWO rows (a top border row and
 * a bottom border row), so putting one inside a row makes the row two
 * rows tall and the card four. So the actions are rendered as
 * single-line bracket labels — "[撤销 u]" — which is the app's
 * existing idiom for an option (the permission modal writes
 * "1 [A] allow this call once"). The key is part of the label, so the
 * action is still pressable with the keyboard alone.
 *
 * <p>★ THE COUNTS ARE NEVER TRUNCATED ★ The counts ARE the answer —
 * a "+3097 -1" where the truth is "+3097 -14" is worse than no card
 * at all. So the budget is computed in CELLS (the title is CJK: 10
 * characters are 15 cells), and the parts are dropped in priority
 * order until the row fits: first the actions, then the link, and
 * only then — as a last resort — the title's tail.
 *
 * <p>★ R388r: THE CARD IS PURE PRESENTATION ★ R388q gave the card its
 * own key handler, gated on an internal `focused` flag that nothing
 * ever set — so the advertised `[撤销 u]` could never fire. The card
 * now renders what the app will do and takes the focus as a PROP; the
 * app owns the keys. An action that cannot fire is DIMMED, so the
 * user learns why the key did nothing instead of concluding the
 * feature is broken.
 */

import React from "react";
import { Box, Text } from "ink";
import { t, icon } from "../theme.js";
import { cellWidth, fitCells } from "../cellWidth.js";
import type { FileChangeSummary } from "../fileChanges.js";
// R388r: the card's ACTIONS — one table, read by the card AND by the app.
import { FILE_CARD_ACTION_INFO } from "../fileCardActions.js";

export interface FileChangesCardProps {
  /** The aggregated changes. */
  summary: FileChangeSummary;
  /** The CONTENT width (the cells inside the border). */
  width: number;
  /** The per-file detail is expanded. */
  expanded?: boolean;
  /**
   * Is this card the focused one?
   *
   * <p>★ R388r: the focus is a PROP, not internal state. ★ R388q made
   * it internal (`useState(false)`) and nothing ever set it to true,
   * so the card's key handler was dead code — the card advertised
   * `[撤销 u]` and the key could never fire. The focus now comes from
   * the app, which owns which card is targeted.
   */
  focused?: boolean;
  /**
   * Are the card's keys live right now?
   *
   * <p>★ They are live only when the PROMPT IS EMPTY. ★ A letter
   * typed into the prompt is TEXT. The card dims its actions when they
   * cannot fire, so the user learns WHY the key did nothing instead of
   * concluding the feature is broken.
   */
  actionsLive?: boolean;
  /** Called when the user asks to see the files. */
  onShowFiles?: () => void;
  /** Called when the user asks to undo the changes. */
  onUndo?: () => void;
  /** Called when the user asks to review the changes. */
  onReview?: () => void;
}

/**
 * A single-line action.
 *
 * <p>★ ONE TEXT NODE ★ The label and its key hint are ONE string, so
 * they cannot wrap apart. A bordered Box was tried here and it makes
 * the card two rows taller — see the class comment.
 */
const CardAction: React.FC<{ label: string; keyHint: string; active?: boolean }> = ({
  label,
  keyHint,
  active = false,
}) => (
  <Text color={active ? t.accent : t.dim} bold={active}>
    {`[${label} ${keyHint}]`}
  </Text>
);

export const FileChangesCard: React.FC<FileChangesCardProps> = ({
  summary,
  width,
  expanded = false,
  focused = false,
  actionsLive = true,
  onShowFiles,
  onUndo,
  onReview,
}) => {
  // ★ THE CARD HANDLES NO KEYS ITSELF ★
  //
  // R388q had the card listen on `process.stdin` for "f", "u" and "r",
  // gated on an internal `focused` flag that nothing ever set — so the
  // handler never ran. R388r moves the handling to the APP's key
  // handler, which is where every other binding in the app lives and
  // where the "the prompt is empty" guard can be applied once.
  //
  // The card is therefore PURE PRESENTATION: it shows what the app
  // will do and reports whether the user asked for it.
  //
  // ★ THE APP CALLS BACK IN ★ The callbacks are the card's whole
  // interaction surface, and they are optional — a card with no
  // callbacks shows its actions dimmed, because an action that cannot
  // happen must not look pressable.

  // ★ NOTHING TO SAY ★ A card with no files is not a card. The caller
  // must not render one — and if it does, it must not claim that zero
  // files were edited.
  if (summary.fileCount === 0) return null;

  const title = `已编辑 ${summary.fileCount} 个文件`;

  // ★ THE COUNTS ARE THE ANSWER ★ They are built first and they are
  // never truncated: if the row cannot hold them, the TITLE is what
  // gives way.
  const addedText = summary.added > 0 ? ` +${summary.added}` : "";
  const removedText = summary.removed > 0 ? ` -${summary.removed}` : "";
  const countsText = addedText + removedText;

  // ★ EVERY WIDTH IS IN CELLS ★ The title is CJK — "已编辑 19 个文件"
  // is TEN characters but FIFTEEN cells. A budget computed in
  // characters thinks the row is 5 cells wider than it is, so the row
  // overflows, Ink wraps it, and the counts get clipped ("-14" becomes
  // "-1"). Everything below is measured with `cellWidth`.
  const iconW = cellWidth(icon.catWrite) + 1; // the icon cell + its space
  const countsW = cellWidth(countsText);
  const titleW = cellWidth(title);
  const sepW = 3; // " │ "
  // ★ THE WIDTHS COME FROM THE ACTION TABLE ★ So a label that grows
  // (or shrinks) in the table moves the budget with it — a second
  // copy of the label in the layout is a second truth (Lesson 1207).
  const showFiles = FILE_CARD_ACTION_INFO.showFiles;
  const undo = FILE_CARD_ACTION_INFO.undo;
  const review = FILE_CARD_ACTION_INFO.review;
  const linkW = cellWidth(` ${showFiles.label} ${showFiles.key} `);
  const actionW =
    cellWidth(`[${undo.label} ${undo.key}]`) + cellWidth(`[${review.label} ${review.key}]`) + 1; // + the space between

  // The row's content budget: the card's width, minus the border's two
  // columns and the card's own one-cell horizontal padding on each side.
  const avail = Math.max(1, width - 2);

  // ★ WHAT SURVIVES, IN PRIORITY ORDER ★
  //   1. the counts — they are the answer;
  //   2. the title — but it may be shortened;
  //   3. the link;
  //   4. the actions.
  // So the actions are dropped first, then the link, and the title is
  // shortened only when even the bare counts and the icon do not fit.
  const wantsActions = avail >= iconW + titleW + countsW + sepW + linkW + actionW;
  const wantsLink = avail >= iconW + titleW + countsW + sepW + linkW;
  const showActions = wantsActions;
  const showLink = wantsLink;
  const showSeparator = showLink || showActions;

  // ★ THE ACTIONS ARE DIMMED WHEN THEY CANNOT FIRE ★ An action the
  // user cannot trigger must not LOOK triggerable. `actionsLive` is
  // false when the prompt has text (the letters are text then) or
  // when the card has no callback for it.
  const live = actionsLive;

  // The title may have to be shortened. It is shortened ONLY as a last
  // resort, and never so far that it loses the file count — the count
  // is the headline.
  let shownTitle = title;
  const titleRoom = avail - iconW - countsW - (showSeparator ? sepW : 0);
  if (cellWidth(shownTitle) > titleRoom) {
    // "已编辑 19 个文件" → "已编辑 19 个文件" truncated to fit. The
    // file count is at the front, so a cell-aware truncation keeps it.
    shownTitle = fitCells(title, Math.max(1, titleRoom), "");
  }

  return (
    <Box
      borderStyle="round"
      borderColor={focused ? t.accent : t.panel}
      flexDirection="column"
      flexShrink={0}
      paddingX={1}
      marginY={0}
      width={width + 2}
    >
      {/* ★ ONE ROW ★ The title, the counts, the link and the actions
          are on ONE line, like the desktop's card. The row does not
          stretch its children (`alignItems="flex-start"`), and every
          part is a single Text node, so nothing can wrap and the card
          stays 3 rows tall (border, content, border). */}
      <Box flexDirection="row" alignItems="flex-start" flexShrink={0}>
        <Text color={t.catWrite}>{icon.catWrite} </Text>
        <Text bold>{shownTitle}</Text>
        {addedText ? <Text color={t.ok}>{addedText}</Text> : null}
        {removedText ? <Text color={t.err}>{removedText}</Text> : null}
        {showSeparator ? <Text dimColor> │ </Text> : null}
        {showLink ? <Text dimColor>{` ${showFiles.label} ${showFiles.key} `}</Text> : null}
        {showActions ? (
          <Text>
            {" "}
            <CardAction label={undo.label} keyHint={undo.key} active={focused && live} />{" "}
            <CardAction label={review.label} keyHint={review.key} active={focused && live} />
          </Text>
        ) : null}
      </Box>

      {expanded ? (
        <Box flexDirection="column" marginTop={0} paddingLeft={1}>
          {summary.files.map((f) => (
            <Text key={`${f.path}:${f.tool}`} dimColor>
              {fitCells(
                ` ${f.name}${f.added > 0 ? ` +${f.added}` : ""}${f.removed > 0 ? ` -${f.removed}` : ""}`,
                Math.max(1, width - 2),
              )}
            </Text>
          ))}
        </Box>
      ) : null}
    </Box>
  );
};
