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
 * T-429 (Phase 5 R4): ThreadSelector.
 *
 * 1:1 port of deepagents-code's `ThreadSelectorScreen` (Java
 * port at `ThreadSelectorScreen.java`). Full-screen thread picker
 * for the `/threads` command. Lists known threads with a fuzzy
 * filter; Enter selects the highlighted thread; Esc cancels.
 *
 * design.md §5.2: "thread picker" (the spec lists `ThreadSelector`
 *  under the same family as AgentSelector / EffortPicker).
 *
 * The component is prop-driven. The host fetches the thread
 * list (via `session/list` or equivalent RPC) and supplies it
 * as `threads`. The picker bubbles the chosen threadId on
 * Enter and `null` on cancel.
 */

import { hintFor } from "../componentKeys.js";
import React, { useEffect, useMemo, useRef, useState } from "react";
import { Box, Text, useInput } from "ink";
import TextInput from "ink-text-input";
// R370 — path equality, not string equality. See src/cwdMatch.ts.
import { sameCwd } from "../cwdMatch.js";
// R388j — the shell owns the frame; the body owns the content.
import { PanelBody, fitRow, blank, filterRow } from "./PanelBody.js";

/** One thread row. Mirrors the Java record. */
export interface ThreadInfo {
  threadId: string;
  agentName: string;
  messages: number;
  createdAt: string;
  updatedAt: string;
  gitBranch?: string;
  prompt: string;
  cwd: string;
  /**
   * R388n: the session file's size in BYTES.
   *
   * <p>The daemon has always sent `sizeBytes` on `listSessions` — it
   * is the source of the `messageCount` proxy. The TUI never read it.
   * Claude Code's `/resume` selector shows the size next to each row,
   * and it is the only number that says "this session is 40 MB, do not
   * open it by accident".
   */
  sizeBytes?: number;
}

/**
 * R388n: a session's size, in the compact form the desktop's session
 * rail uses ("12 B" / "4 KB" / "3 MB").
 *
 * <p>A size of 0 or less is "" — an unknown size is not "0 B".
 */
export function formatBytes(sizeBytes: number | undefined): string {
  if (sizeBytes == null || !Number.isFinite(sizeBytes) || sizeBytes <= 0) return "";
  if (sizeBytes < 1024) return `${sizeBytes} B`;
  if (sizeBytes < 1024 * 1024) return `${Math.round(sizeBytes / 1024)} KB`;
  return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** Outcome of the cwd-switch prompt invoked when the user
 *  picks a thread whose cwd differs from the current one. */
export type CwdSwitchChoice = "switch" | "stay" | "abort";

export interface ThreadSelectorProps {
  /** All known threads. */
  threads: ReadonlyArray<ThreadInfo>;
  /** The current working directory. If the user picks a thread
   *  with a different cwd, the picker surfaces a switch
   *  prompt via `onCwdMismatch`. */
  currentCwd: string;
  /** Called when the user picks a thread. The second arg is the
   *  cwd-switch choice (the host decides how to handle it). */
  onSelect: (threadId: string, choice: CwdSwitchChoice) => void;
  /** Called when the user dismisses the picker. */
  onClose: () => void;
  /** Optional title override. */
  title?: string;
  /**
   * R370: shown under the title. `/sessions` uses it to print the
   * absolute cwd being filtered on, because the title only carries
   * the basename and two projects can share a leaf name.
   */
  subtitle?: string;
  /**
   * R370: the zero-rows message.
   *
   * The default is the component's historical "no threads found",
   * which is a LIE for `/sessions`: a cwd-scoped list that is empty
   * because nothing is attributed there is a different fact from a
   * daemon that has no sessions at all, and the host can tell those
   * apart. An empty panel that gives the same sentence for both is a
   * successful-but-uninformative panel.
   */
  emptyLabel?: string;
  /** Cap visible rows. Default 9. */
  maxVisible?: number;
  /** When `true`, show a "branch" column in each row. */
  showBranch?: boolean;
  /**
   * R388j: the CONTENT width — the cells available between the
   * shell's border columns. Every row is laid out to this number,
   * so nothing can overflow the frame.
   */
  width?: number;
  /**
   * R388j: called when the user presses `a` — "show me EVERY
   * session", not just this cwd's.
   *
   * <p>Why this exists: `/sessions` is scoped to the current
   * directory, and a directory that has never had a session in it
   * shows an empty list. The empty state then has to TELL the user
   * that the sessions exist somewhere else — but a sentence is not
   * a route. The user asked for "all the history of the current
   * cwd" and got "nothing here"; the way out has to be a keypress,
   * not a sentence that tells them to type another command and
   * remember its name.
   */
  onShowAll?: () => void;
  /**
   * R388j: when the panel is showing EVERY session (not just this
   * cwd's), the `a` key has nothing left to widen — so the
   * affordance is not advertised.
   */
  showAllOffered?: boolean;
  /**
   * R388n: the git branch of the CURRENT working directory.
   *
   * <p>Claude Code's `/resume` selector has a `Ctrl+B` chord: "only
   * sessions on my current branch". The filter needs the CURRENT
   * branch to compare against — the picker cannot discover it, because
   * it knows the sessions' branches but not the cwd's.
   */
  currentBranch?: string;
  /**
   * R388n: `Ctrl+R` — rename the highlighted session.
   *
   * <p>Claude Code lets the user name a session so the picker shows
   * something meaningful instead of a timestamp. The daemon's
   * `session/rename` RPC does the write.
   */
  onRename?: (threadId: string, title: string) => void;
  /**
   * R388n: `Ctrl+W` — show every session, across every WORKTREE.
   *
   * <p>A worktree is a different checkout of the same repo, and a
   * session started in one is invisible when the picker is scoped to
   * the cwd. This widens the scope past the cwd filter.
   */
  onShowAllWorktrees?: () => void;
  /**
   * R388n: `Ctrl+A` — show every session (across every project).
   */
  onShowAllProjects?: () => void;
}

export const ThreadSelector: React.FC<ThreadSelectorProps> = ({
  threads,
  currentCwd,
  onSelect,
  onClose,
  title = "Select Thread",
  subtitle,
  emptyLabel = "no threads found",
  maxVisible = 9,
  showBranch = false,
  width = 76,
  onShowAll,
  showAllOffered = false,
  currentBranch,
  onRename,
  onShowAllWorktrees,
  onShowAllProjects,
}) => {
  const [filter, setFilter] = useState("");
  const [highlight, setHighlight] = useState(0);
  // R388n: the PREVIEW pane. Claude Code's `/resume` selector opens a
  // preview with Space — the user reads what a session is about
  // BEFORE deciding to switch to it. Without the preview, picking a
  // session is a guess based on a one-line title.
  const [preview, setPreview] = useState(false);
  // R388n: the RENAME field. Ctrl+R opens it; the user types a name
  // and presses Enter to apply it.
  const [renaming, setRenaming] = useState(false);
  const [renameText, setRenameText] = useState("");
  // R388n: the branch filter. Ctrl+B narrows the list to the
  // sessions on the CURRENT branch.
  const [branchOnly, setBranchOnly] = useState(false);
  // R388j: the filter row's `TextInput` has its OWN `useInput`, and
  // Ink's `useInput` is NOT focus-scoped — so the `a` that widens the
  // scope ALSO lands in the filter. This latch swallows exactly that
  // one character, whichever order the two handlers run in.
  //
  // A widen is a MODE CHANGE (this cwd → every session), not a
  // refinement of the current filter, so the filter must be empty
  // afterwards either way.
  const swallowNextFilterChar = useRef(false);

  // Filter — empty query shows all. Match on threadId, agent,
  // branch, prompt, or cwd.
  //
  // R388n: the BRANCH filter is a separate axis from the text filter.
  // It is a MODE (Ctrl+B toggles it), not a refinement of the query —
  // so it is applied AFTER the text filter, and toggling it does not
  // touch the query.
  const filtered = useMemo(() => {
    const q = filter.trim().toLowerCase();
    const byText = q
      ? threads.filter(
          (t) =>
            t.threadId.toLowerCase().includes(q) ||
            t.agentName.toLowerCase().includes(q) ||
            (t.gitBranch ?? "").toLowerCase().includes(q) ||
            t.prompt.toLowerCase().includes(q) ||
            t.cwd.toLowerCase().includes(q),
        )
      : threads;
    if (!branchOnly) return byText;
    // ★ The branch filter needs a CURRENT branch to compare against. ★
    // Without one there is nothing to filter BY, and showing everything
    // while claiming "branch only" is a lie. So the filter is a no-op
    // until the host supplies the branch — and the host is told.
    if (!currentBranch) return byText;
    return byText.filter((t) => (t.gitBranch ?? "") === currentBranch);
  }, [threads, filter, branchOnly, currentBranch]);

  // Clamp highlight when the filtered list shrinks.
  useEffect(() => {
    if (highlight >= filtered.length) {
      setHighlight(Math.max(0, filtered.length - 1));
    }
  }, [filtered.length, highlight]);

  useInput((input, key) => {
    // R388n: the RENAME field owns the keyboard while it is open —
    // typing "d" must type a "d", not navigate. Esc cancels, Enter
    // applies. This branch comes FIRST, for the same reason the
    // comment box's does (R388l).
    if (renaming) {
      if (key.escape) {
        setRenaming(false);
        setRenameText("");
        return;
      }
      if (key.return) {
        const t = filtered[highlight];
        const name = renameText.trim();
        setRenaming(false);
        setRenameText("");
        if (t && name) onRename?.(t.threadId, name);
        return;
      }
      if (key.backspace || key.delete) {
        setRenameText((s) => s.slice(0, -1));
        return;
      }
      // A modifier chord is not text — Ctrl+R must not type an "r".
      if (key.ctrl || key.meta || key.shift) return;
      if (input && input.length === 1) setRenameText((s) => s + input);
      return;
    }
    if (key.escape) {
      // R388n: Esc closes the PREVIEW first — it is a mode, and Esc
      // means "leave the mode I am in" before it means "close the
      // panel".
      if (preview) {
        setPreview(false);
        return;
      }
      onClose();
      return;
    }
    // R388j: `a` widens the scope from "this cwd" to "everything".
    // The empty state promises this keypress, so it has to work.
    //
    // ★ THE FILTER SWALLOWS IT TOO. ★
    //
    // Ink's `useInput` is NOT focus-scoped: the `TextInput` in the
    // filter row has its OWN `useInput`, and it inserts any printable
    // character into the filter — including `a`. Returning from this
    // handler does NOT stop that.
    //
    // So the panel has to undo the damage: it clears the filter it
    // knows the `TextInput` has just been fed. The widen is a MODE
    // CHANGE (this cwd → every session), not a refinement of the
    // current filter, so a filter of "a" is meaningless anyway.
    if (input === "a" && onShowAll) {
      swallowNextFilterChar.current = true;
      setFilter("");
      onShowAll();
      return;
    }
    // R388n: the Ctrl chords. Claude Code's `/resume` selector binds
    // Ctrl+A (all projects), Ctrl+W (all worktrees), Ctrl+B (current
    // branch) and Ctrl+R (rename).
    //
    // ★ These are MODIFIER chords, so they never reach the filter row
    // as text — the `TextInput` inserts the bare letter, but the panel
    // swallows it the same way it swallows `a`. ★
    if (key.ctrl) {
      const chord = input.toLowerCase();
      if (chord === "r" && onRename) {
        swallowNextFilterChar.current = true;
        setRenameText("");
        setRenaming(true);
        return;
      }
      if (chord === "a" && onShowAllProjects) {
        swallowNextFilterChar.current = true;
        setFilter("");
        onShowAllProjects();
        return;
      }
      if (chord === "w" && onShowAllWorktrees) {
        swallowNextFilterChar.current = true;
        setFilter("");
        onShowAllWorktrees();
        return;
      }
      if (chord === "b") {
        swallowNextFilterChar.current = true;
        setFilter("");
        setBranchOnly((b) => !b);
        return;
      }
    }
    // R388n: Space toggles the PREVIEW. The user reads what a session
    // is about before switching to it.
    //
    // Space is also a printable character the filter row would
    // swallow, so the panel clears it the same way.
    if (input === " ") {
      swallowNextFilterChar.current = true;
      setFilter((f) => f);
      setPreview((p) => !p);
      return;
    }
    // R388n: a DIGIT jumps to that row and picks it. Claude Code's
    // `/resume` selector accepts "1".."9" as a direct pick — the user
    // does not have to arrow down to a session they can already see.
    //
    // The digit selects the Nth row of the VISIBLE list, so the
    // number always matches what is on screen.
    if (/^[1-9]$/.test(input)) {
      const idx = Number.parseInt(input, 10) - 1;
      const t = filtered[idx];
      if (!t) return;
      const choice: CwdSwitchChoice = chooseCwd(t.cwd, currentCwd);
      onSelect(t.threadId, choice);
      return;
    }
    if (key.upArrow || input === "k") {
      setHighlight((h) => (h - 1 + Math.max(1, filtered.length)) % Math.max(1, filtered.length));
      return;
    }
    if (key.downArrow || input === "j") {
      setHighlight((h) => (h + 1) % Math.max(1, filtered.length));
      return;
    }
    if (key.return) {
      const t = filtered[highlight];
      if (!t) return;
      // Decide the cwd-switch choice up-front. The host
      // gets a single arg pair: threadId + choice. We
      // default to "stay" if the cwds match, otherwise
      // "switch" (the host can re-prompt via the modal if
      // it wants explicit user input).
      const choice: CwdSwitchChoice = chooseCwd(t.cwd, currentCwd);
      onSelect(t.threadId, choice);
      return;
    }
  });

  // Scroll window.
  const start = useMemo(() => {
    if (filtered.length <= maxVisible) return 0;
    return Math.max(
      0,
      Math.min(highlight - Math.floor(maxVisible / 2), filtered.length - maxVisible),
    );
  }, [highlight, filtered.length, maxVisible]);
  const end = Math.min(filtered.length, start + maxVisible);
  const visible = filtered.slice(start, end);

  return (
    <PanelBody width={width} title={title} subtitle={subtitle}>
      {/* R388j: the filter row. A live `TextInput` renders only its
          own content, so the row is short and the frame's right
          border moves in. `filterRow` computes the input's width
          and pads the row to the panel's. */}
      {filterRow({
        width,
        value: filter,
        placeholder: "filter…",
        onChange: (v: string) => {
          // R388j: swallow the `a` the filter row also received.
          // See `swallowNextFilterChar`.
          if (swallowNextFilterChar.current) {
            swallowNextFilterChar.current = false;
            setFilter("");
            return;
          }
          setFilter(v);
        },
      })}
      <Text>{blank(width)}</Text>
      {threads.length === 0 ? (
        <Text dimColor>{fitRow(` ${emptyLabel}`, width)}</Text>
      ) : filtered.length === 0 ? (
        <Text dimColor>{fitRow(` no matches for "${filter}"`, width)}</Text>
      ) : (
        visible.map((t, i) => {
          const realIndex = start + i;
          const isHighlighted = realIndex === highlight;
          // R370: three states, not a boolean. `sameCwd`, not `!==` —
          // a row differing only in case or a trailing separator is
          // the SAME directory on Windows. And "unknown" is not
          // "different": `listSessions` omits `cwd` entirely when it
          // cannot attribute a session, and marking that row `cwd≠`
          // promised a project switch that the host cannot perform.
          const cwdUnknown = t.cwd.trim().length === 0;
          const cwdMismatch = !cwdUnknown && !sameCwd(t.cwd, currentCwd);
          // R388j: the row is laid out to the CONTENT width, and
          // every cell is fitted to it — so a long thread id or a
          // long prompt can never push the frame's border off the
          // edge. Before this the row was a bare concatenation and
          // a 40-char id overflowed the panel.
          //
          // The shape follows the desktop's session rail: a
          // two-cell gutter (selection mark + state dot), the
          // title, then the id tail. The SECOND line carries the
          // count and the age.
          const mark = isHighlighted ? "▶" : " ";
          const activeMark = cwdUnknown ? "·" : cwdMismatch ? "○" : "●";
          const label = t.agentName || t.threadId;
          // The id tail is what tells two anonymous sessions apart;
          // the prompt is the human-readable title.
          const idTail = t.threadId.length > 8 ? t.threadId.slice(-8) : t.threadId;
          const meta = `${t.messages} msgs`;
          // R370: `updatedAt` was carried on every row and rendered
          // nowhere, so "which one did I leave" was unanswerable in
          // a list whose ids are timestamps. R388j renders it in the
          // desktop's compact form ("12m" / "3h" / "5d").
          const when = t.updatedAt ? relativeAge(t.updatedAt) : "";
          // R388n: the BRANCH and the SIZE.
          //
          // The branch is the column that answers "which one was I
          // working on" — the daemon sends it now (R388n's
          // GitBranchReader), and the `showBranch` prop that was
          // declared for it and never used is finally honest.
          //
          // The size is the only number that says "this session is
          // 40 MB, do not open it by accident".
          const branch = t.gitBranch ? ` · ${t.gitBranch}` : "";
          const size = formatBytes(t.sizeBytes);
          // The title line: gutter (2) + label + " · " + id tail.
          // The id tail is fixed-width so the labels line up.
          const titleLine = `${mark}${activeMark} ${label}`;
          const titleBudget = Math.max(8, width - 4 - idTail.length - 3);
          const metaLine = `   ${meta}${when ? ` · ${when}` : ""}${branch}${size ? ` · ${size}` : ""}`;
          // R388n: the row NUMBER. The digit keys pick the Nth
          // VISIBLE row, so the number on screen and the number the
          // user presses must be the same thing — and the number is
          // the position in the FILTERED list, not in the window.
          const rowNumber = start + i + 1;
          return (
            <Box key={t.threadId} flexDirection="column">
              {/* R388j: ONE `<Text>` per row. Ink's flexbox
                  distributes a container's width between its
                  children and shrinks the first to its content
                  width — so a padded title followed by a separate
                  id-tail `<Text>` LOSES the padding and the row
                  comes out short. One `<Text>` has nothing to
                  distribute. */}
              <Text>
                {/* R388n: the number gutter. */}
                <Text dimColor>{rowNumber}. </Text>
                <Text color={isHighlighted ? "cyan" : undefined} bold={isHighlighted}>
                  {fitRow(titleLine, titleBudget)}
                </Text>
                <Text dimColor> · {idTail}</Text>
              </Text>
              <Text dimColor>{fitRow(metaLine, width)}</Text>
              {t.prompt ? <Text dimColor>{fitRow(`   ${t.prompt}`, width)}</Text> : null}
            </Box>
          );
        })
      )}
      {filtered.length > maxVisible ? (
        <Text dimColor>{fitRow(` ${start + 1}–${end} of ${filtered.length}`, width)}</Text>
      ) : null}
      {/* R388n: the PREVIEW pane. Claude Code's `/resume` selector
          opens a preview with Space: the user reads what a session is
          about BEFORE deciding to switch to it. Without it, picking a
          session is a guess based on a one-line title.

          The preview shows the facts the row cannot fit: the full
          path, the branch, the size, and the session's prompt. */}
      {preview && filtered[highlight] ? (
        <>
          <Text>{blank(width)}</Text>
          <Text color="cyan">
            {fitRow(
              ` preview · ${filtered[highlight].agentName || filtered[highlight].threadId}`,
              width,
            )}
          </Text>
          <Text dimColor>{fitRow(`   cwd: ${filtered[highlight].cwd || "(unknown)"}`, width)}</Text>
          <Text dimColor>
            {fitRow(
              `   branch: ${filtered[highlight].gitBranch ?? "(none)"} · size: ${
                formatBytes(filtered[highlight].sizeBytes) || "(unknown)"
              }`,
              width,
            )}
          </Text>
          {filtered[highlight].prompt ? (
            <Text dimColor>{fitRow(`   ${filtered[highlight].prompt}`, width)}</Text>
          ) : null}
        </>
      ) : null}
      {/* R388n: the RENAME field. The name the user types REPLACES
          the generated title — the daemon's `session/rename` writes
          it, and the picker shows it from then on. */}
      {renaming ? (
        <>
          <Text>{blank(width)}</Text>
          <Text color="cyan">{fitRow(" rename:", width)}</Text>
          <Text>{fitRow(` ${renameText}▌`, width)}</Text>
        </>
      ) : null}
      {/* R388j: the escape hatch out of a narrow scope. It is a
          KEYPRESS, not a sentence that tells the user to go and
          remember another command's name. */}
      {showAllOffered && onShowAll ? (
        <>
          <Text>{blank(width)}</Text>
          <Text color="cyan">
            {fitRow("a  show every session (not just this directory)", width)}
          </Text>
        </>
      ) : null}
      {/* R388n: the branch filter's own state line. The user needs to
          know that the list is NARROWED — a filter that is on but
          invisible looks like the list lost rows. */}
      {branchOnly ? (
        <>
          <Text>{blank(width)}</Text>
          <Text color="cyan">
            {fitRow(
              currentBranch
                ? `branch only: ${currentBranch}`
                : "branch only (no current branch — showing everything)",
              width,
            )}
          </Text>
        </>
      ) : null}
      <Text>{blank(width)}</Text>
      <Text dimColor>{fitRow(hintFor("components/ThreadSelector.tsx"), width)}</Text>
    </PanelBody>
  );
};

/**
 * R388j: a session's age, in the compact form the desktop's session
 * rail uses ("just now" / "12m" / "3h" / "5d").
 *
 * <p>The daemon's `updatedAt` arrives in several shapes — an ISO
 * string, an epoch-millis number, or a number-as-string — so the
 * parser accepts all three and falls back to "" for anything it
 * cannot read. A row that shows "just now" for a session from last
 * March is worse than a row that shows no time at all.
 */
export function relativeAge(updatedAt: string): string {
  const ts = parseTimestamp(updatedAt);
  if (ts == null) return "";
  const diff = Date.now() - ts;
  if (diff < 0) return "";
  if (diff < 60_000) return "just now";
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
  return `${Math.floor(diff / 86_400_000)}d`;
}

/** Epoch-millis from an ISO string, an epoch number, or a numeric string. */
function parseTimestamp(v: string | number | undefined | null): number | null {
  if (v == null) return null;
  if (typeof v === "number") return Number.isFinite(v) ? v : null;
  const trimmed = String(v).trim();
  if (trimmed.length === 0) return null;
  if (/^\d+$/.test(trimmed)) {
    const n = Number(trimmed);
    return Number.isFinite(n) ? n : null;
  }
  const parsed = Date.parse(trimmed);
  return Number.isNaN(parsed) ? null : parsed;
}

/** Pure helper: decide the cwd-switch choice based on a thread
 *  pick. Mirrors `ThreadSelectorScreen.chooseCwd` in
 *  deepagents-code. */
export function chooseCwd(threadCwd: string, currentCwd: string): CwdSwitchChoice {
  // R370: `sameCwd`, not `===`. Two spellings of one directory were
  // asking for a project switch that resolved to the project the
  // daemon is already in.
  if (sameCwd(threadCwd, currentCwd)) return "stay";
  return "switch";
}

/** Pure helper: format a thread row for tests + a possible
 *  call site that wants the same string (e.g. a status-bar
 *  pill). Mirrors `ThreadSelectorScreen.formatRow` in
 *  deepagents-code. */
export function formatThreadRow(t: ThreadInfo, isHighlighted: boolean): string {
  const prefix = isHighlighted ? "▶ " : "  ";
  const branch = t.gitBranch ? ` · ${t.gitBranch}` : "";
  return `${prefix}${t.threadId}  ${t.agentName}  ${t.messages} msgs${branch}`;
}
