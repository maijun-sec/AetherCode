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

import React, { useEffect, useMemo, useState } from "react";
import { Box, Text, useInput } from "ink";
import TextInput from "ink-text-input";
// R370 — path equality, not string equality. See src/cwdMatch.ts.
import { sameCwd } from "../cwdMatch.js";

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
}) => {
  const [filter, setFilter] = useState("");
  const [highlight, setHighlight] = useState(0);

  // Filter — empty query shows all. Match on threadId, agent,
  // branch, prompt, or cwd.
  const filtered = useMemo(() => {
    if (!filter.trim()) return threads;
    const q = filter.toLowerCase();
    return threads.filter(
      (t) =>
        t.threadId.toLowerCase().includes(q) ||
        t.agentName.toLowerCase().includes(q) ||
        (t.gitBranch ?? "").toLowerCase().includes(q) ||
        t.prompt.toLowerCase().includes(q) ||
        t.cwd.toLowerCase().includes(q),
    );
  }, [threads, filter]);

  // Clamp highlight when the filtered list shrinks.
  useEffect(() => {
    if (highlight >= filtered.length) {
      setHighlight(Math.max(0, filtered.length - 1));
    }
  }, [filtered.length, highlight]);

  useInput((input, key) => {
    if (key.escape) {
      onClose();
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
    return Math.max(0, Math.min(highlight - Math.floor(maxVisible / 2), filtered.length - maxVisible));
  }, [highlight, filtered.length, maxVisible]);
  const end = Math.min(filtered.length, start + maxVisible);
  const visible = filtered.slice(start, end);

  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor="cyan"
      paddingX={2}
      paddingY={1}
    >
      <Box>
        <Text color="cyan" bold>◆ {title}</Text>
        <Text dimColor>  type to filter  ↑/↓ (j/k): navigate  Enter: select  Esc: cancel</Text>
      </Box>
      {subtitle ? <Text dimColor>  {subtitle}</Text> : null}
      <Text> </Text>
      <Box>
        <Text color="yellow">{">"}</Text>
        <Text> </Text>
        <TextInput
          value={filter}
          onChange={setFilter}
          placeholder="filter…  (matches threadId / agent / branch / prompt / cwd)"
        />
      </Box>
      <Text> </Text>
      {threads.length === 0 ? (
        <Text dimColor>  {emptyLabel}</Text>
      ) : filtered.length === 0 ? (
        <Text dimColor>  no matches for "{filter}"</Text>
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
          return (
            <Box key={t.threadId} flexDirection="column">
              <Box flexDirection="row">
                <Text color={isHighlighted ? "cyan" : undefined}>
                  {isHighlighted ? "▶" : " "}{" "}
                </Text>
                <Text bold={isHighlighted}>{t.threadId.padEnd(20)}</Text>
                <Text>  </Text>
                <Text>{t.agentName}</Text>
                <Text dimColor>  ·  {t.messages} msgs</Text>
                {showBranch && t.gitBranch ? (
                  <Text color="magenta">  ·  {t.gitBranch}</Text>
                ) : null}
                {cwdMismatch ? <Text color="yellow">  ·  cwd≠</Text> : null}
                {cwdUnknown ? <Text dimColor>  ·  cwd unrecorded</Text> : null}
                {/* R370: `updatedAt` was carried on every row and
                    rendered nowhere, so "which one did I leave" was
                    unanswerable in a list whose ids are timestamps. */}
                {t.updatedAt ? <Text dimColor>  ·  {t.updatedAt}</Text> : null}
              </Box>
              {t.prompt ? (
                <Text dimColor>     {truncate(t.prompt, 80)}</Text>
              ) : null}
            </Box>
          );
        })
      )}
      {filtered.length > maxVisible ? (
        <Text dimColor>  {start + 1}–{end} of {filtered.length}</Text>
      ) : null}
    </Box>
  );
};

function truncate(s: string, n: number): string {
  if (s.length <= n) return s;
  return s.slice(0, n - 1) + "…";
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
