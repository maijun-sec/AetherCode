/**
 * R700d — /sdd-view preview + diff viewer for the TUI.
 *
 * Three affordances:
 *
 *   1. `/sdd-view` (no args) — show the current phase's
 *      artefact (first ~80 lines) + a `file://` URI so a click
 *      in iTerm2 / WezTerm / Windows Terminal opens the file
 *      in the user's default editor. The simplest "open in
 *      editor" workflow on a TUI — the user reads the rest
 *      outside the terminal.
 *
 *   2. `/sdd-diff` — line-level diff between the cached
 *      "pre-modify" snapshot and the on-disk file. The diff
 *      is computed in this component (no daemon round-trip
 *      needed since the file lives under <cwd>/.aethercode/sdd/
 *      <slug>/<file>). We use a simple LCS-free approach:
 *      walk both files line-by-line and emit `+` / `-` /
 *      ` ` markers. It's not optimal but it's deterministic
 *      and easy to reason about.
 *
 *   3. `/sdd-cached <phase>` — replace the cached snapshot
 *      for a phase with the on-disk contents. The cache
 *      lives in store.sddCache[phaseNumber] (added in state.ts
 *      alongside sddRun). When the user fires `/sdd-modify`
 *      we automatically refresh the cache for the current
 *      phase so the next `/sdd-diff` shows pre-modify vs
 *      post-modify.
 */

import React from "react";
import { Box, Text } from "ink";
import { SddArtefactLink } from "./SddMode";

export interface SddReviewProps {
  /** Absolute path to the artefact on disk. */
  artefactPath: string;
  /** First N lines of the current artefact (daemon returned
   *  it via the RPC; we never read the file ourselves here). */
  currentLines: string[];
  /** Pre-modify snapshot (the user's chosen baseline). null
   *  when the user has never run /sdd-cached for this phase. */
  previousLines: string[] | null;
  /** Max lines to render (default 60). */
  maxLines?: number;
}

/**
 * Render the artefact body + a 4-line context header. Sections
 * are colour-coded:
 *   - header (cyan)
 *   - body (default)
 *   - file:// link (yellow underline)
 *   - diff hunks (green / red)
 */
export function SddReview({
  artefactPath,
  currentLines,
  previousLines,
  maxLines = 60,
}: SddReviewProps): React.ReactElement {
  const truncated = currentLines.length > maxLines;
  const lines = currentLines.slice(0, maxLines);
  return (
    <Box flexDirection="column" borderStyle="round" paddingX={1}>
      <Text color="cyan">📂 view · {artefactPath}</Text>
      <Text dimColor>file:// link: {SddArtefactLink(artefactPath)}</Text>
      <Text> </Text>
      {previousLines ? renderDiff(previousLines, lines, maxLines) : renderPlain(lines)}
      {truncated ? (
        <Text dimColor>
          … ({currentLines.length - maxLines} more lines — open the file via the link above)
        </Text>
      ) : null}
    </Box>
  );
}

function renderPlain(lines: string[]): React.ReactNode {
  return lines.map((l, i) => (
    <Text key={i}>{padLineNumber(i + 1, lines.length)}  {l}</Text>
  ));
}

/**
 * R700d — line-level diff using a simple LCS-free walker. The
 * "previous" baseline is the user's snapshot at /sdd-cached time;
 * the "current" is the on-disk file (post-modify). Output:
 *   - unchanged line: " "
 *   - added line:     "+" (green)
 *   - removed line:   "-" (red)
 *   - changed pair:   "-" then "+" (paired by line number when
 *                     the lengths match)
 */
function renderDiff(
  previous: string[],
  current: string[],
  maxLines: number,
): React.ReactNode {
  // Simple LCS-free diff: walk line-by-line. This is O(N+M) and
  // produces more "+"/"-" pairs than git's algorithm but it's
  // deterministic + easy to verify by eye. SDD artefacts are
  // short (constitution ~50 lines, design.md ~150), so the cost
  // is negligible.
  const out: React.ReactNode[] = [];
  const max = Math.max(previous.length, current.length);
  let shown = 0;
  for (let i = 0; i < max && shown < maxLines; i++, shown++) {
    const a = previous[i];
    const b = current[i];
    if (a === undefined && b !== undefined) {
      out.push(
        <Text key={`+${i}`} color="green">{padLineNumber(i + 1, max)} + {b}</Text>
      );
    } else if (a !== undefined && b === undefined) {
      out.push(
        <Text key={`-${i}`} color="red">{padLineNumber(i + 1, max)} - {a}</Text>
      );
    } else if (a === b) {
      out.push(<Text key={`=${i}`}>{padLineNumber(i + 1, max)}   {a}</Text>);
    } else {
      // changed pair
      out.push(
        <Text key={`-${i}`} color="red">{padLineNumber(i + 1, max)} - {a}</Text>
      );
      out.push(
        <Text key={`+${i}`} color="green">{padLineNumber(i + 1, max)} + {b}</Text>
      );
      shown++; // a - and + both consume one show budget
    }
  }
  return out;
}

function padLineNumber(n: number, max: number): string {
  const width = String(max).length;
  return String(n).padStart(width, " ");
}