/**
 * T-430 (Phase 5 R4): UpdateProgress.
 *
 * 1:1 port of deepagents-code's `UpdateProgressScreen` (Java
 * port at `UpdateProgressScreen.java`). Progress modal for
 * app self-update installs. Displays self-update progress and
 * a bounded log tail. Pressing `d` toggles details; `c` copies
 * the log path (or a warning fix command); `q` quits; Esc
 * closes (only after completion).
 *
 * design.md §5.2: "update flow".
 *
 * The component is prop-driven. The host feeds it the latest
 * version, the install command, and the log path. The host
 * also wires the `onAppendLine` (called by the worker as it
 * emits output), `onMarkSuccess` / `onMarkFailure` /
 * `onMarkWarning` (state transitions), and `onCopy` (clipboard).
 */

import React, { useEffect, useState } from "react";
import { Box, Text, useInput } from "ink";
import Spinner from "ink-spinner";

/** The update's terminal state. */
export type UpdateProgressState = "running" | "success" | "failure" | "warning";

export interface UpdateProgressProps {
  /** The version being installed. */
  latest: string;
  /** The install command the host is running. */
  command: string;
  /** The log file path. */
  logPath: string;
  /** Current state. */
  state: UpdateProgressState;
  /** Bounded log tail. The host appends to this list as the
   *  worker emits output. */
  tail: ReadonlyArray<string>;
  /** Optional status text override. */
  statusText?: string;
  /** Optional warning / fix text (for `state === "warning"`). */
  warningText?: string;
  /** Optional copy text + label (for `state === "warning"`). */
  copyText?: string;
  copyLabel?: string;
  /** Called when the user presses `d` to toggle details. */
  onToggleDetails: () => void;
  /** Called when the user presses `c` to copy. */
  onCopy: (text: string, label: string) => void;
  /** Called when the user presses `q` to quit (only fires
   *  when state is terminal). */
  onQuit: () => void;
  /** Called when the user dismisses the modal (only fires
   *  when state is terminal). */
  onClose: () => void;
  /** Cap on the visible tail rows. Default 30. */
  tailLimit?: number;
  /** Optional title override. */
  title?: string;
}

const DEFAULT_TAIL_LIMIT = 30;

function statusLine(state: UpdateProgressState, latest: string, statusText?: string): { glyph: string; text: string; color: string } {
  switch (state) {
    case "running":
      return { glyph: "⠋", text: statusText ?? `Installing v${latest}…`, color: "cyan" };
    case "success":
      return { glyph: "✓", text: statusText ?? `Update complete. Quit and relaunch aethercode to use v${latest}.`, color: "green" };
    case "failure":
      return { glyph: "✗", text: statusText ?? "Update failed.", color: "red" };
    case "warning":
      return { glyph: "⚠", text: statusText ?? warningStub(), color: "yellow" };
  }
}

function warningStub(): string {
  return "Update needs user action.";
}

function helpText(state: UpdateProgressState, detailsVisible: boolean, hasCopy: boolean): string {
  const parts: string[] = [];
  parts.push(`d ${detailsVisible ? "Hide details" : "Show details"}`);
  parts.push(state === "running" ? "Esc close when complete" : "Esc close");
  if (hasCopy) parts.push("c copy");
  if (state !== "running") parts.push("q quit");
  return parts.join("  ·  ");
}

export const UpdateProgress: React.FC<UpdateProgressProps> = ({
  latest,
  command,
  logPath,
  state,
  tail,
  statusText,
  warningText,
  copyText,
  copyLabel,
  onToggleDetails,
  onCopy,
  onQuit,
  onClose,
  tailLimit = DEFAULT_TAIL_LIMIT,
  title = "Updating aethercode",
}) => {
  const [detailsVisible, setDetailsVisible] = useState(false);

  // Cap the tail to the most-recent `tailLimit` lines.
  const visibleTail = tail.slice(Math.max(0, tail.length - tailLimit));

  // Auto-show details on failure so the user sees the log
  // without pressing `d`.
  useEffect(() => {
    if (state === "failure" || state === "warning") {
      setDetailsVisible(true);
    }
  }, [state]);

  useInput((input, key) => {
    if (input === "d" || input === "D") {
      setDetailsVisible((v) => {
        const next = !v;
        onToggleDetails();
        return next;
      });
      return;
    }
    if (input === "c" || input === "C") {
      // Prefer the warning copy text; fall back to the log path.
      const text = copyText && copyText.length > 0 ? copyText : logPath;
      const label = copyLabel && copyLabel.length > 0 ? copyLabel : "log path";
      onCopy(text, label);
      return;
    }
    if (input === "q" || input === "Q") {
      if (state !== "running") onQuit();
      return;
    }
    if (key.escape) {
      if (state !== "running") onClose();
      return;
    }
  });

  const status = statusLine(state, latest, state === "warning" ? warningText : statusText);
  const hasCopy = !!(copyText && copyText.length > 0) || detailsVisible;

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
      </Box>
      <Box>
        <Text color={status.color}>{status.glyph} </Text>
        <Text>{status.text}</Text>
      </Box>
      {detailsVisible ? (
        <Box flexDirection="column" marginTop={1}>
          <Text dimColor>  Running command: {command}</Text>
          {visibleTail.length === 0 ? (
            <Text dimColor>    (no output yet)</Text>
          ) : (
            visibleTail.map((line, i) => (
              <Text key={i} dimColor>    {line}</Text>
            ))
          )}
          <Text dimColor>  Log: {logPath}</Text>
        </Box>
      ) : null}
      <Text> </Text>
      <Text dimColor>  {helpText(state, detailsVisible, hasCopy)}</Text>
    </Box>
  );
};

/** Pure helper: build the tail (capped) for the picker.
 *  Mirrors `UpdateProgressScreen.appendLine` in
 *  deepagents-code. */
export function appendLineTail(
  tail: ReadonlyArray<string>,
  line: string,
  limit: number,
): string[] {
  const cap = limit > 0 ? limit : DEFAULT_TAIL_LIMIT;
  const out = [...tail, line];
  if (out.length <= cap) return out;
  return out.slice(out.length - cap);
}

/** Pure helper: build the success state. */
export function markSuccessStatus(latest: string): string {
  return `Update complete. Quit and relaunch aethercode to use v${latest}.`;
}

/** Pure helper: build the failure status text. */
export function markFailureStatus(command: string): string {
  return `Update failed. Try manually: ${command}`;
}

/** Pure helper: build the warning status text. */
export function markWarningStatus(warning: string): string {
  return warning;
}
