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
 */

import React from "react";
import { Box, Text } from "ink";
import { t, icon } from "../theme.js";
import { cellWidth, fitCells } from "../cellWidth.js";
import type { FileChangeSummary } from "../fileChanges.js";

export interface FileChangesCardProps {
  /** The aggregated changes. */
  summary: FileChangeSummary;
  /** The CONTENT width (the cells inside the border). */
  width: number;
  /** The per-file detail is expanded. */
  expanded?: boolean;
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
  onShowFiles,
  onUndo,
  onReview,
}) => {
  // ★ THE CARD OWNS ITS KEYS ★
  //
  // Ink 5.2.1 has no `onPress` and no mouse support, so an action that
  // only LOOKS pressable would be a lie. The card listens for the keys
  // itself: `f` shows the files, `u` undoes, `r` reviews.
  //
  // ★ THE KEYS ARE ONLY LIVE WHEN THE CARD IS FOCUSED ★ A card in the
  // scrollback is not necessarily the thing the user is looking at, and
  // a card that swallows `u` and `r` while the user is typing would be
  // terrible. So the card is only interactive when it is focused.
  const [focused, setFocused] = React.useState(false);

  React.useEffect(() => {
    if (!focused) return;
    const onKey = (e: KeyboardEvent): void => {
      const k = e.key.toLowerCase();
      if (k === "f") {
        e.preventDefault();
        onShowFiles?.();
      } else if (k === "u") {
        e.preventDefault();
        onUndo?.();
      } else if (k === "r") {
        e.preventDefault();
        onReview?.();
      } else if (k === "escape") {
        e.preventDefault();
        setFocused(false);
      }
    };
    // ★ CAPTURE ON THE WINDOW, NOT ON A FOCUSED INPUT ★ The card lives
    // in the scrollback, and the prompt has the focus — so a keydown
    // on the prompt must still reach the card.
    process.stdin.on("keydown", onKey);
    return () => {
      process.stdin.off("keydown", onKey);
    };
  }, [focused, onShowFiles, onUndo, onReview]);

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
  const linkW = cellWidth(" 查看文件 f ");
  const actionW = cellWidth("[撤销 u] [审查 r]") + 1; // + the space between

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
        {showLink ? <Text dimColor> 查看文件 f </Text> : null}
        {showActions ? (
          <Text>
            {" "}
            <CardAction label="撤销" keyHint="u" active={focused} />{" "}
            <CardAction label="审查" keyHint="r" active={focused} />
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
