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
 * R385: ModePicker — the Ink half of `/mode`.
 *
 * ## Why this component exists
 *
 * Bare `/mode` returned the sentinel `__PICK_MODE__`, and ONLY the
 * cmd.exe line mode claimed it (`line.ts` → `pickMode`). On Ink the
 * sentinel fell through to the R362 fallback, so the user typed `/mode`
 * and was told:
 *
 *     no panel for that command in the TUI yet — /help lists what is
 *     supported
 *
 * for a feature that worked fine one keystroke away in the other
 * renderer. A FALSE FAILURE is worse than an absent feature: it says
 * the capability does not exist.
 *
 * ## The list is not written here
 *
 * The rows come from `src/permissionModes.ts`, which is a transcription
 * of the daemon's `PermissionMode` enum and is checked against it by
 * `scripts/test/r385-mode-surface.test.mjs`. Writing the seven mode
 * names into this file would make a THIRD copy of a list that already
 * had four.
 *
 * ## The hint comes from the key registry
 *
 * The key line is rendered from `src/componentKeys.ts` (R379/R381), so
 * the keys this panel advertises are the keys it actually binds — the
 * gate compares the two in both directions.
 */

import { hintFor } from "../componentKeys.js";
import { PERMISSION_MODES } from "../permissionModes.js";
import React, { useEffect, useMemo, useState } from "react";
import { Box, Text, useInput } from "ink";

export interface ModePickerProps {
  /** The mode currently in force. Marked with (current). */
  current: string | null;
  /** Called when the user confirms a mode. */
  onSelect: (value: string) => void;
  /** Called when the user dismisses the picker. */
  onClose: () => void;
  /** Optional title override. */
  title?: string;
  /** Cap visible rows. Default 8 — enough for every mode the daemon has. */
  maxVisible?: number;
}

export const ModePicker: React.FC<ModePickerProps> = ({
  current,
  onSelect,
  onClose,
  title = "Permission mode",
  maxVisible = 8,
}) => {
  const modes = PERMISSION_MODES;

  // The daemon reports the mode it is in force; match it against the
  // catalog so the (current) marker lands on the right row even when
  // the two spell it differently.
  const currentValue = useMemo(() => {
    if (!current) return null;
    const hit = modes.find((m) => m.value === current.trim().toUpperCase());
    return hit ? hit.value : current.trim().toUpperCase();
  }, [current]);

  const initial = useMemo(() => {
    const idx = currentValue ? modes.findIndex((m) => m.value === currentValue) : -1;
    return idx >= 0 ? idx : 0;
  }, [modes, currentValue]);
  const [highlight, setHighlight] = useState(initial);

  // Clamp the highlight when the catalog shrinks.
  useEffect(() => {
    if (highlight >= modes.length) {
      setHighlight(Math.max(0, modes.length - 1));
    }
  }, [modes.length, highlight]);

  useInput((input, key) => {
    if (key.escape) {
      onClose();
      return;
    }
    if (key.return) {
      const m = modes[highlight];
      if (m) onSelect(m.value);
      onClose();
      return;
    }
    if (key.upArrow) {
      setHighlight((h) => (h - 1 + modes.length) % Math.max(1, modes.length));
      return;
    }
    if (key.downArrow) {
      setHighlight((h) => (h + 1) % Math.max(1, modes.length));
      return;
    }
    if (input === "j") {
      setHighlight((h) => (h + 1) % Math.max(1, modes.length));
      return;
    }
    if (input === "k") {
      setHighlight((h) => (h - 1 + modes.length) % Math.max(1, modes.length));
      return;
    }
  });

  // Scroll window.
  const start = Math.max(
    0,
    Math.min(highlight - Math.floor(maxVisible / 2), Math.max(0, modes.length - maxVisible)),
  );
  const end = Math.min(modes.length, start + maxVisible);
  const visible = modes.slice(start, end);

  return (
    <Box flexDirection="column" borderStyle="double" borderColor="cyan" paddingX={2} paddingY={1}>
      <Box>
        <Text color="cyan" bold>
          ◆ {title}
        </Text>
        <Text dimColor> {hintFor("components/ModePicker.tsx")}</Text>
      </Box>
      <Text> </Text>
      {visible.map((m, i) => {
        const realIndex = start + i;
        const isHighlighted = realIndex === highlight;
        const isCurrent = m.value === currentValue;
        return (
          <Box key={m.value} flexDirection="row">
            <Text color={isHighlighted ? "cyan" : undefined}>{isHighlighted ? "▶" : " "} </Text>
            <Text color={isCurrent ? "yellowBright" : undefined}>{isCurrent ? "●" : " "} </Text>
            <Text>
              <Text bold={isHighlighted}>{m.value.padEnd(19)}</Text>
              {m.label}
              {isCurrent ? <Text color="yellowBright"> (current)</Text> : null}
            </Text>
          </Box>
        );
      })}
      {modes.length > maxVisible ? (
        <Text dimColor>
          {" "}
          {start + 1}–{end} of {modes.length}
        </Text>
      ) : null}
      {modes[highlight] ? <Text dimColor> {modes[highlight].blurb}</Text> : null}
    </Box>
  );
};
