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
 * R388n — the CHECKPOINT menu.
 *
 * <p>Claude Code's rewind menu (reached with `/rewind` or `Esc Esc`)
 * offers, at each point in the conversation:
 *
 * <ul>
 *   <li>Restore code and conversation</li>
 *   <li>Restore conversation</li>
 *   <li>Restore code</li>
 *   <li>Summarize from here</li>
 *   <li>Summarize up to here</li>
 *   <li>Never mind</li>
 * </ul>
 *
 * <p>Before R388n the TUI had `/rewind <n>` (a bare number — the user
 * had to know the index) and `Ctrl+Z` (a blind rewind to the last user
 * message). There was no MENU, and two of the six options had no
 * backend at all: "Restore code" and "Summarize from here" were
 * Claude Code features the daemon did not implement.
 *
 * <p>★ The menu is a list of ACTIONS, not a list of labels. ★ Every
 * entry names the operation it performs, so the same six strings cannot
 * be reused for two different operations — and the gate checks the
 * ACTION, not the text.
 */

import { hintFor } from "../componentKeys.js";
import React, { useState } from "react";
import { Box, Text, useInput } from "ink";
import { PanelBody, fitRow, blank } from "./PanelBody.js";
import { listWindow, moveSelection } from "../listWindow.js";
import { terminalSize } from "../terminalGeometry.js";

/**
 * The six operations the menu offers.
 *
 * <p>★ ONE list, and it is the ONLY list. ★ The component renders it,
 * the gate reads it, and the app dispatches on it. A second copy of
 * "what the menu offers" is Lesson 1207's shape — one "list" that is
 * really several lists, and every one of them drifts.
 */
export const CHECKPOINT_ACTIONS = [
  "restore_code_and_conversation",
  "restore_conversation",
  "restore_code",
  "summarize_from_here",
  "summarize_up_to_here",
  "never_mind",
] as const;

/** One of the menu's actions. */
export type CheckpointAction = (typeof CHECKPOINT_ACTIONS)[number];

/** What each action does, and what the menu says. */
export interface CheckpointActionInfo {
  /** The label the menu shows. */
  label: string;
  /** One line: what the action does. */
  blurb: string;
  /** Does it change the FILES? */
  touchesCode: boolean;
  /** Does it change the CONVERSATION? */
  touchesConversation: boolean;
}

/**
 * The action table.
 *
 * <p>★ The `code` / `conversation` FLAGS are the contract. ★
 *
 * <p>"Restore code and conversation" is not a THIRD implementation — it
 * is "restore code" AND "restore conversation". If the menu had a
 * separate handler for it, the two would drift: a fix to the code
 * restore would not reach the combined one, and the user would get
 * code restored in one menu entry and not the other.
 *
 * <p>So the combined action is expressed as the FLAGS of the two
 * simple ones, and the dispatcher reads the flags.
 */
export const CHECKPOINT_ACTION_INFO: Readonly<Record<CheckpointAction, CheckpointActionInfo>> = {
  restore_code_and_conversation: {
    label: "Restore code and conversation",
    blurb: "put the files AND the conversation back to this point",
    touchesCode: true,
    touchesConversation: true,
  },
  restore_conversation: {
    label: "Restore conversation",
    blurb: "roll the conversation back to this point (files untouched)",
    touchesCode: false,
    touchesConversation: true,
  },
  restore_code: {
    label: "Restore code",
    blurb: "put the files back to this point (conversation untouched)",
    touchesCode: true,
    touchesConversation: false,
  },
  summarize_from_here: {
    label: "Summarize from here",
    // ★ WHAT ACTUALLY DIFFERS BETWEEN THE TWO SUMMARIZE ACTIONS ★
    //
    // Both summarise the HEAD (everything before the point) and keep
    // the TAIL (everything after it). The daemon's boundary is the
    // point's own message: `from` splits BEFORE it and `up_to` splits
    // AFTER it, so `up_to` folds the point's own message into the
    // summary and `from` keeps it.
    //
    // (An earlier version of this file claimed the two were OPPOSITE
    // — "keep what follows" vs "keep what came before". That was
    // wrong: it was read off the menu's NAMES, not off the daemon.
    // The engine summarises the head and keeps the tail in BOTH
    // cases; see `summarizeAtTranscriptIndex`.)
    //
    // Both entries used to carry the SAME blurb, so the user could
    // not tell them apart — and one of the two descriptions was wrong
    // for the action it described.
    blurb: "summarize everything BEFORE this point, keep what follows",
    touchesCode: false,
    touchesConversation: true,
  },
  summarize_up_to_here: {
    label: "Summarize up to here",
    blurb: "summarize up to AND INCLUDING this point, keep what follows",
    touchesCode: false,
    touchesConversation: true,
  },
  never_mind: {
    label: "Never mind",
    blurb: "close the menu and change nothing",
    touchesCode: false,
    touchesConversation: false,
  },
};

/** One row of the checkpoint list. */
export interface CheckpointRow {
  /** The checkpoint's id. */
  id: string;
  /** The 0-based user-message ordinal it sits at. */
  userMessageIndex: number;
  /** A one-line summary of the turn. */
  summary: string;
  /** The files this checkpoint covers. */
  files: Array<{ path: string; name: string; restorable: boolean; created: boolean }>;
}

export interface CheckpointMenuProps {
  /** The checkpoints, newest last. */
  checkpoints: ReadonlyArray<CheckpointRow>;
  /** Called with the chosen action and the chosen checkpoint. */
  onChoose: (action: CheckpointAction, checkpoint: CheckpointRow) => void;
  /** Called when the user dismisses the menu without choosing. */
  onClose: () => void;
  /** The CONTENT width. */
  width?: number;
  /** The panel's title. */
  title?: string;
  /**
   * How many checkpoint rows the list may show at once.
   *
   * <p>★ WHY IT IS A PROP ★ The panel cannot know how much room it
   * has: the same menu is rendered inside the TUI's panel column (whose
   * height depends on the terminal) and inside the line renderer. So
   * the caller decides, and the default is a number that fits a
   * 30-row terminal.
   */
  maxRows?: number;
  /**
   * How many checkpoints the daemon has EVICTED (its store is bounded).
   *
   * <p>★ WHY THE MENU SAYS SO ★ The list is not the session's whole
   * history. A menu that silently starts at "point 40" looks as
   * though the daemon lost the history — so the menu says that the
   * older points are gone, instead of pretending they never existed.
   */
  evicted?: number;
}

/** The default number of checkpoint rows the list shows. */
export const DEFAULT_CHECKPOINT_ROWS = 8;

export const CheckpointMenu: React.FC<CheckpointMenuProps> = ({
  checkpoints,
  onChoose,
  onClose,
  width = 76,
  title = "Rewind",
  maxRows = DEFAULT_CHECKPOINT_ROWS,
  evicted = 0,
}) => {
  // Which list is highlighted: the CHECKPOINT (top) or the ACTION
  // (bottom). Claude Code's menu picks the point first, then the
  // action — two questions, and answering them in one list of six
  // would multiply the entries by the number of checkpoints.
  const [checkpointIdx, setCheckpointIdx] = useState(checkpoints.length - 1);
  const [actionIdx, setActionIdx] = useState(0);
  const [focus, setFocus] = useState<"checkpoint" | "action">("action");

  const cp = checkpoints[checkpointIdx];

  // ★ THE WINDOW ★ Only `maxRows` checkpoints are shown, and the
  // window FOLLOWS the selection. Before this, every checkpoint was
  // rendered: 40 checkpoints made a 53-row frame — taller than the
  // terminal, so the panel ran off the screen.
  const win = listWindow(checkpoints.length, checkpointIdx, maxRows);
  const visible = checkpoints.slice(win.start, win.end + 1);

  useInput((input, key) => {
    if (key.escape) {
      onClose();
      return;
    }
    // Tab / Shift+Tab switch WHICH list has the focus.
    if (key.tab) {
      setFocus((f) => (f === "action" ? "checkpoint" : "action"));
      return;
    }
    if (key.upArrow) {
      if (focus === "action") {
        setActionIdx((i) => moveSelection(CHECKPOINT_ACTIONS.length, i, -1));
      } else {
        // ★ THE SELECTION WRAPS ★
        //
        // The old rule CLAMPED at both ends, and the list opened with
        // the selection on the NEWEST entry — so the very first press
        // of the down arrow did nothing: the selection was already at
        // the bottom and the clamp kept it there. A key that does
        // nothing is worse than a key that is absent: the user
        // concludes the feature is broken.
        //
        // Wrapping also means the up arrow at the top reaches the
        // newest entry, so both directions are always meaningful.
        setCheckpointIdx((i) => moveSelection(checkpoints.length, i, -1));
      }
      return;
    }
    if (key.downArrow) {
      if (focus === "action") {
        setActionIdx((i) => moveSelection(CHECKPOINT_ACTIONS.length, i, 1));
      } else {
        setCheckpointIdx((i) => moveSelection(checkpoints.length, i, 1));
      }
      return;
    }
    if (key.return) {
      if (!cp) {
        onClose();
        return;
      }
      onChoose(CHECKPOINT_ACTIONS[actionIdx], cp);
      return;
    }
    // The digits pick an ACTION directly — the menu is small and
    // fixed, so "3" is a fast path to "Restore code".
    //
    // ★ Only the digits that EXIST are bound. ★ There are six actions,
    // so the range is 1-6. An earlier version tested 1-9, which meant
    // 7, 8 and 9 were swallowed by the menu and did nothing — a key
    // the panel consumed without an answer. The hint says "1-6" and
    // the handler binds exactly that.
    if (/^[1-6]$/.test(input)) {
      const idx = Number.parseInt(input, 10) - 1;
      if (idx < CHECKPOINT_ACTIONS.length && cp) {
        onChoose(CHECKPOINT_ACTIONS[idx], cp);
      }
    }
  });

  return (
    <PanelBody width={width} title={title}>
      {checkpoints.length === 0 ? (
        <Text dimColor>{fitRow(" no checkpoints yet", width)}</Text>
      ) : (
        <>
          <Text dimColor>{fitRow(" point to return to:", width)}</Text>
          {/* ★ THE EVICTED NOTICE ★ The daemon's store is bounded, so
              the oldest points are GONE. Saying so is the difference
              between "the daemon forgot" and "the daemon kept the
              recent history and dropped the rest". */}
          {evicted > 0 ? (
            <Text dimColor>
              {fitRow(`   (the ${evicted} oldest point(s) are no longer available)`, width)}
            </Text>
          ) : null}
          {/* ★ THE "MORE ABOVE" MARKER ★ A window hides entries, and a
              silent truncation looks like the list ENDS there. The
              marker says how many are above, so the user knows the
              list continues and which direction to scroll. */}
          {win.hiddenBefore > 0 ? (
            <Text dimColor>{fitRow(`   ⋮ ${win.hiddenBefore} earlier point(s) above`, width)}</Text>
          ) : null}
          {visible.map((c, vi) => {
            // The window's own index — NOT the visible index. The list
            // is numbered by the checkpoint's own turn ordinal, so the
            // numbering does not shift as the window moves.
            const i = win.start + vi;
            return (
              <Text
                key={c.id}
                color={focus === "checkpoint" && i === checkpointIdx ? "cyan" : undefined}
                bold={i === checkpointIdx}
              >
                {fitRow(
                  ` ${i === checkpointIdx ? "▶" : " "} #${c.userMessageIndex + 1}  ${
                    c.summary || "(no summary)"
                  }`,
                  width,
                )}
              </Text>
            );
          })}
          {win.hiddenAfter > 0 ? (
            <Text dimColor>{fitRow(`   ⋮ ${win.hiddenAfter} later point(s) below`, width)}</Text>
          ) : null}
          <Text>{blank(width)}</Text>
          <Text dimColor>{fitRow(" what to do:", width)}</Text>
          {CHECKPOINT_ACTIONS.map((a, i) => {
            const info = CHECKPOINT_ACTION_INFO[a];
            return (
              <Text
                key={a}
                color={focus === "action" && i === actionIdx ? "cyan" : undefined}
                bold={i === actionIdx}
              >
                {fitRow(
                  ` ${focus === "action" && i === actionIdx ? "▶" : " "} ${i + 1}. ${info.label}`,
                  width,
                )}
              </Text>
            );
          })}
          {cp && cp.files.length > 0 ? (
            <>
              <Text>{blank(width)}</Text>
              <Text dimColor>{fitRow(" files at this point:", width)}</Text>
              {cp.files.slice(0, 6).map((f) => (
                <Text key={f.path} dimColor>
                  {fitRow(
                    `   ${f.name}${f.created ? " (new)" : ""}${f.restorable ? "" : " (binary)"}`,
                    width,
                  )}
                </Text>
              ))}
              {cp.files.length > 6 ? (
                <Text dimColor>{fitRow(`   … and ${cp.files.length - 6} more`, width)}</Text>
              ) : null}
            </>
          ) : null}
        </>
      )}
      <Text>{blank(width)}</Text>
      <Text dimColor>{fitRow(hintFor("components/CheckpointMenu.tsx"), width)}</Text>
    </PanelBody>
  );
};
