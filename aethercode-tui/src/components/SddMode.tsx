/**
 * R700 — Minimal SDD mode banner for the TUI.
 *
 * The TUI renders SDD progress as a single compact panel
 * above the input box. Each phase chip shows one of:
 *   ⏳  idle / running    →  dim
 *   ✓   done              →  green
 *   ↷   skipped           →  yellow
 *   ⏸   pending_confirm   →  reverse video (the "you are here" marker)
 *   ✗   failed            →  red
 *
 * Below the chip strip we print a one-line summary of the
 * current phase + the action hint:
 *
 *   ──────────────────────────────────────
 *   phase 4/8 · 详细设计 · pending your review
 *   artefact: /.../.aethercode/sdd/foo/design.md
 *   [a]pprove  [m]odify  [s]kip  [v]iew  [q]uit
 *
 * The user issues the corresponding slash commands in the
 * input box below (`/sdd-approve`, `/sdd-modify <feedback>`,
 * `/sdd-skip`, `/sdd-abort`, `/sdd-status`). This component is
 * a pure read-only renderer; state mutations go through
 * `dispatch({ type: "sddRun.set", state })` from tui.tsx.
 *
 * The full React-style chip-strip equivalent of the desktop's
 * SddPhaseBar lives in aethercode-desktop/components/SddPhaseBar.tsx
 * — the TUI keeps the surface area tight to fit a 120x40 terminal.
 */

import React from "react";
import { Box, Text } from "ink";
import type { SddRunState, SddPhaseEntry } from "../state";

export interface SddModeProps {
  run: SddRunState;
  cwd: string;
}

const CHIP: Record<string, string> = {
  idle:             "⏳",
  running:          "▶ ",
  pending_confirm:  "⏸ ",
  done:             "✓ ",
  skipped:          "↷ ",
  failed:           "✗ ",
};

const COLOR_BY_STATE: Record<string, string | undefined> = {
  done:           "green",
  skipped:        "yellow",
  pending_confirm:"cyan",
  failed:         "red",
  running:        "magenta",
  idle:           "gray",
};

/**
 * R700 — single-line 8-chip strip + the "you are here"
 * marker on the current phase. Mirrors the desktop's
 * SddPhaseBar.tsx but condensed for a 120-column terminal.
 */
export function SddMode({ run, cwd }: SddModeProps): React.ReactElement | null {
  if (!run) return null;
  const current = run.currentPhase ?? run.phases.length;
  const currentEntry: SddPhaseEntry | undefined = run.phases.find((p) => p.phaseNumber === current);
  const title = currentEntry?.title ?? "?";
  const detail = currentEntry?.state === "pending_confirm"
    ? "pending your review"
    : (currentEntry?.state ?? "running");
  const artefactPath = currentEntry?.path ?? "(not yet written)";
  return (
    <Box flexDirection="column" borderStyle="single" paddingX={1}>
      <Text>
        📐 SDD · slug:<Text color="green">{run.slug}</Text> · {current}/{run.phases.length} · {title} · {detail}
      </Text>
      <Text>{renderChipStrip(run.phases, current)}</Text>
      <Text>
        artefact: <Text color="cyan">{artefactPath}</Text>
      </Text>
      <Text dimColor>
        cwd: {cwd} · status: {run.status}
      </Text>
      <Text dimColor>
        /sdd-approve  /sdd-modify &lt;feedback&gt;  /sdd-skip  /sdd-status  /sdd-abort
      </Text>
    </Box>
  );
}

/**
 * Build the chip-strip string for `phases`, highlighting the
 * `current` phase with `[ ]` brackets and per-state colour.
 */
function renderChipStrip(phases: SddPhaseEntry[], current: number): React.ReactNode {
  return phases.map((p, idx) => {
    const sym = CHIP[p.state] ?? "?";
    const active = p.phaseNumber === current;
    const color = COLOR_BY_STATE[p.state];
    const sep = idx > 0 ? " " : "";
    return (
      <Text key={p.phaseNumber}>
        {sep}
        {active ? "[" : " "}
        <Text color={color as Parameters<typeof Text>[0]["color"]}>{sym}{p.phaseNumber}</Text>
        {active ? "]" : " "}
      </Text>
    );
  });
}

/**
 * R700 — Convert an absolute filesystem path into a portable
 * `file://` URI. The TUI prints this on `/sdd-view` so the user
 * can click the link in iTerm2 / WezTerm / Windows Terminal
 * and have the artefact open in their default editor.
 *
 * Windows: file:///C:/path/to/foo.md
 * POSIX:   file:///home/user/foo.md
 */
export function SddArtefactLink(absPath: string): string {
  // Convert Windows backslashes to forward slashes so the
  // `file://` URL is portable across runtimes.
  const normalised = absPath.replace(/\\/g, "/");
  if (/^[a-zA-Z]:\//.test(normalised)) {
    return `file:///${normalised}`;   // Windows: file:///C:/...
  }
  return `file://${normalised}`;
}