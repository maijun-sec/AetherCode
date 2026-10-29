// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

/**
 * R373: WorkflowPicker — the Ink surface for `/workflow`.
 *
 * ## Why this component exists
 *
 * Line mode has had a workflow panel since R102 (`line.ts`
 * `panelWorkflow`); the Ink renderer had none, so the registry row
 * `workflow-picker` sat at `ink: 'unavailable'` for three rounds
 * (R362 declared it, R365 re-derived the truth and found line mode
 * was real, R366 fixed the RPC names the line frame was calling).
 * What was missing was only this: a panel that lists the workflows
 * and starts one.
 *
 * ## The two fields that are NOT the same thing
 *
 * `name` is the DISPLAY name — the `name:` the YAML declares.
 * `id` is the ADDRESSABLE id — the workflow FILE's name.
 *
 * They are not interchangeable, and the reason is on the daemon side:
 * `WorkflowPaths.workflowFile(cwd, name)` resolves a FILE, so a file
 * called `deploy.yaml` that declares `name: ship-it` is listed as
 * "ship-it" but cannot be started under that name. R373 made the
 * daemon publish `id` for exactly this reason. This component sends
 * `id` and displays `name`.
 *
 * ## Honest empty states
 *
 * Three different situations look the same if you only count rows:
 *
 *   1. the directory has no workflows        -> "no workflows defined"
 *   2. every workflow file was unreadable    -> "N files could not be read"
 *   3. the filter matched nothing            -> "no matches for ..."
 *
 * (2) is the one that used to be invisible: the daemon dropped
 * unreadable files silently, so "there is nothing here" and "you
 * cannot read anything here" were the same answer. R373 publishes
 * the skipped file names, and this component refuses to collapse
 * them into one.
 */

import { hintFor } from "../componentKeys.js";
import React, { useEffect, useMemo, useState } from "react";
import { Box, Text, useInput } from "ink";
// R373: the row type and the empty-state copy live in one leaf module
// so the gate can RUN them and so there is exactly one definition of
// "a workflow" and "what the empty panel says".
import { workflowEmptyState, type WorkflowEmptyState, type WorkflowRow } from "../workflowRows.js";

/** One workflow row. Mirrors the daemon's `listWorkflows` entry. */
export type WorkflowInfo = WorkflowRow;

export interface WorkflowPickerProps {
  /** Every workflow the daemon listed. */
  workflows: ReadonlyArray<WorkflowInfo>;
  /**
   * The directory the daemon scanned. `null` means "the daemon has
   * not told us yet" — NOT "the directory is empty", so the empty
   * state must not claim a path it does not have.
   */
  dir: string | null;
  /**
   * The workflow files the daemon could not read. Non-empty means
   * the empty list is not an empty DIRECTORY.
   */
  skipped: ReadonlyArray<string>;
  /** Called with the addressable id when the user starts a workflow. */
  onRun: (id: string) => void;
  /** Called when the user dismisses the picker. */
  onClose: () => void;
  /** Optional title override. */
  title?: string;
  /** Cap visible rows. Default 10. */
  maxVisible?: number;
}

export const WorkflowPicker: React.FC<WorkflowPickerProps> = ({
  workflows,
  dir,
  skipped,
  onRun,
  onClose,
  title = "Run a workflow",
  maxVisible = 10,
}) => {
  const [highlight, setHighlight] = useState(0);

  // Clamp the highlight when the list shrinks.
  useEffect(() => {
    if (highlight >= workflows.length) {
      setHighlight(Math.max(0, workflows.length - 1));
    }
  }, [workflows.length, highlight]);

  useInput((input, key) => {
    if (key.escape) {
      onClose();
      return;
    }
    if (key.upArrow || input === "k") {
      setHighlight((h) => (h - 1 + Math.max(1, workflows.length)) % Math.max(1, workflows.length));
      return;
    }
    if (key.downArrow || input === "j") {
      setHighlight((h) => (h + 1) % Math.max(1, workflows.length));
      return;
    }
    if (key.return) {
      const w = workflows[highlight];
      if (!w) return;
      // The ID, never the display name — see the file header.
      onRun(w.id);
    }
  });

  // Scroll window.
  const start = useMemo(() => {
    if (workflows.length <= maxVisible) return 0;
    return Math.max(
      0,
      Math.min(highlight - Math.floor(maxVisible / 2), workflows.length - maxVisible),
    );
  }, [highlight, workflows.length, maxVisible]);
  const end = Math.min(workflows.length, start + maxVisible);
  const visible = workflows.slice(start, end);

  return (
    <Box flexDirection="column" borderStyle="double" borderColor="cyan" paddingX={2} paddingY={1}>
      <Box>
        <Text color="cyan" bold>
          ◆ {title}
        </Text>
        <Text dimColor> {hintFor("components/WorkflowPicker.tsx")}</Text>
      </Box>
      {dir ? <Text dimColor> {dir}</Text> : null}
      <Text> </Text>
      {workflows.length === 0 ? (
        <EmptyState state={workflowEmptyState(workflows, skipped, dir)} />
      ) : (
        visible.map((w, i) => {
          const realIndex = start + i;
          const isHighlighted = realIndex === highlight;
          return (
            <Box key={w.id} flexDirection="column">
              <Box flexDirection="row">
                <Text color={isHighlighted ? "cyan" : undefined}>{isHighlighted ? "▶" : " "} </Text>
                <Text bold={isHighlighted}>{w.name}</Text>
                <Text dimColor> · {w.stepCount} steps</Text>
                {/* The file name is what the run actually addresses.
                    Showing it only when it differs from the display
                    name keeps the common case quiet and explains the
                    uncommon one. */}
                {w.id !== w.name ? <Text dimColor> ({w.id})</Text> : null}
              </Box>
              {w.description ? <Text dimColor> {truncate(w.description, 72)}</Text> : null}
              {w.inputs.length > 0 ? (
                <Text dimColor>
                  {"     "}needs: {w.inputs.join(", ")} · runs with no values
                </Text>
              ) : null}
            </Box>
          );
        })
      )}
      {workflows.length > maxVisible ? (
        <Text dimColor>
          {" "}
          {start + 1}–{end} of {workflows.length}
        </Text>
      ) : null}
    </Box>
  );
};

/**
 * The zero-row state. The COPY comes from `workflowEmptyState` so
 * the gate can assert the sentence the user actually reads, and so
 * the line-mode panel and this one cannot drift apart.
 */
const EmptyState: React.FC<{ state: WorkflowEmptyState | null }> = ({ state }) => {
  if (!state) return null;
  const warn = state.tone === "warn";
  return (
    <Box flexDirection="column">
      {state.lines.map((l, i) => (
        <Text key={i} color={warn && i === 0 ? "yellow" : undefined} dimColor={!warn || i > 0}>
          {"  "}
          {l}
        </Text>
      ))}
    </Box>
  );
};

function truncate(s: string, n: number): string {
  if (s.length <= n) return s;
  return s.slice(0, n - 1) + "…";
}

/** Pure helper: format a workflow row for tests and for any caller
 *  that wants the same string the component renders. */
export function formatWorkflowRow(w: WorkflowInfo, isHighlighted: boolean): string {
  const prefix = isHighlighted ? "▶ " : "  ";
  const id = w.id !== w.name ? ` (${w.id})` : "";
  return `${prefix}${w.name}  ·  ${w.stepCount} steps${id}`;
}
