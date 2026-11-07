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
 * R388k — CommandHelpPanel: `/help` as a LIST, not a wall of text.
 *
 * ## The bug this exists to fix
 *
 * `/help` returned `SLASH_HELP` — one ~110-line string — which the
 * renderer emitted as a single `sideNote`. The scrollback truncated it
 * to the available height: on a real TTY, **3 lines were visible** and
 * commands 4–89 were unreachable. There was no scroll, no pagination and
 * no filter, so the help surface could not answer the question it
 * exists to answer.
 *
 * ## The benchmark
 *
 * Every tool in the comparison solved it, and none of them solved it by
 * truncating:
 *
 *   * Claude Code — typing `/` opens a menu that merges built-ins,
 *     bundled skills, user skills, plugin commands and MCP prompt
 *     commands into ONE list; `/commands` gives the full list; `?` on
 *     an empty input toggles a shortcut panel.
 *   * Cursor — a `/` menu plus `/help [command]` for per-command detail.
 *   * OpenCode — commands carry a `description` "shown in command lists
 *     and discovery".
 *   * Aider — `/help` renders the command table **generated from
 *     source** (`cog`/`get_help_md`), so the help text cannot drift
 *     from the command table because it IS the command table.
 *
 * Aider's approach is the one to copy, and it fits an existing project
 * rule: R380 made `SLASH_COMMANDS_DETAILED` the ONLY list, and its gate
 * checks both directions. This panel **PROJECTS** that registry — it
 * does not re-print it as prose.
 *
 * ## The list is not written here
 *
 * The rows come from `src/commands.ts` (`SLASH_COMMANDS_DETAILED`).
 * Writing the 89 command names into this file would make a SECOND copy
 * of a list that already had one — and R380's whole subject is that a
 * second copy drifts silently.
 *
 * ## The hint comes from the key registry
 *
 * The key line is rendered from `src/componentKeys.ts` (R379/R381), so
 * the keys this panel advertises are the keys it actually binds.
 */

import { hintFor } from "../componentKeys.js";
// R388j — the shell owns the frame; the body owns the content.
import { PanelBody, fitRow, blank, filterRow } from "./PanelBody.js";
// R388k: the rows are PROJECTED from the one command catalog. The
// projection and the filter live in `src/commandHelp.ts` — pure
// functions, testable WITHOUT a renderer (Lesson 1323).
import { buildHelpRows, filterHelpRows, helpWindow, type HelpRow } from "../commandHelp.js";
import React, { useMemo, useState } from "react";
import { Box, Text, useInput } from "ink";

export interface CommandHelpPanelProps {
  /** Called with the command the user chose (without the leading `/`). */
  onRun: (command: string) => void;
  /** Called when the user dismisses the panel. */
  onClose: () => void;
  /** Optional title override. */
  title?: string;
  /** Cap visible rows. */
  maxVisible?: number;
  /**
   * R388j: the CONTENT width — the cells available between the shell's
   * border columns. Every row is laid out to this number, so nothing
   * can overflow the frame.
   */
  width?: number;
}

/**
 * One row of the help list.
 *
 * A row is a command the user can RUN. Aliases are included — the user
 * may type them — but they are marked, so the list never implies that
 * `/theme-pick` and `/theme` are two different features.
 */
export type { HelpRow };

export const CommandHelpPanel: React.FC<CommandHelpPanelProps> = ({
  onRun,
  onClose,
  title = "Commands",
  maxVisible = 18,
  width = 76,
}) => {
  const [filter, setFilter] = useState("");
  const [highlight, setHighlight] = useState(0);

  const allRows = useMemo(() => buildHelpRows(), []);
  const rows = useMemo(() => filterHelpRows(allRows, filter), [allRows, filter]);

  // Keep the highlight inside the filtered list.
  const safeHighlight = rows.length === 0 ? 0 : Math.min(highlight, rows.length - 1);

  useInput((_input, key) => {
    if (key.escape) {
      onClose();
      return;
    }
    if (key.return) {
      const r = rows[safeHighlight];
      if (r) onRun(r.name);
      onClose();
      return;
    }
    if (key.upArrow) {
      setHighlight((h) => (h - 1 + Math.max(1, rows.length)) % Math.max(1, rows.length));
      return;
    }
    if (key.downArrow) {
      setHighlight((h) => (h + 1) % Math.max(1, rows.length));
      return;
    }
    // Everything else is the FILTER's. The filter box owns the
    // letters, so the panel's own handler must not bind any — a key
    // that means two things means neither. (R381's gate checks both
    // directions: a table that promises a key the handler does not
    // bind, AND a handler that binds a key the table does not list.)
  });

  // Scroll window. The window follows the highlight and never runs
  // past either end of the list, so the counter is honest.
  const { start, end } = helpWindow(rows.length, safeHighlight, maxVisible);
  const visible = rows.slice(start, end);

  // The description column: whatever is left after the name column.
  const nameWidth = Math.min(22, Math.max(12, width - 40));

  return (
    <PanelBody width={width} title={title}>
      <Box width={width}>
        {filterRow({
          width,
          value: filter,
          placeholder: "filter commands…",
          onChange: (v) => {
            setFilter(v);
            setHighlight(0);
          },
        })}
      </Box>
      <Text>{blank(width)}</Text>
      {visible.length === 0 ? (
        <Text dimColor>{fitRow(`no command matches "${filter}"`, width)}</Text>
      ) : (
        visible.map((r, i) => {
          const realIndex = start + i;
          const isHighlighted = realIndex === safeHighlight;
          const label = `/${r.name}${r.args ? " " + r.args : ""}`;
          const desc = r.isAlias ? `(${r.description})` : r.description;
          return (
            <Box key={r.name} flexDirection="row" width={width}>
              <Text color={isHighlighted ? "cyan" : undefined}>{isHighlighted ? "▶" : " "} </Text>
              <Text wrap="truncate-end">
                <Text bold={isHighlighted}>{fitRow(label, nameWidth)}</Text>
                <Text dimColor={r.isAlias}>
                  {" "}
                  {fitRow(desc, Math.max(4, width - nameWidth - 3))}
                </Text>
              </Text>
            </Box>
          );
        })
      )}
      {rows.length > maxVisible ? (
        <Text dimColor>
          {" "}
          {start + 1}–{end} of {rows.length}
        </Text>
      ) : (
        <Text dimColor>
          {" "}
          {rows.length} of {allRows.length} commands
        </Text>
      )}
      <Text>{blank(width)}</Text>
      <Text dimColor wrap="truncate-end">
        {fitRow(hintFor("components/CommandHelpPanel.tsx"), width)}
      </Text>
    </PanelBody>
  );
};
