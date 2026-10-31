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
 * permission modal.
 *
 * Shown when the daemon needs a permission decision. The modal
 * is modal — the rest of the TUI is hidden until the user picks
 * a choice (A / D / Y / N).
 *
 * Risk levels:
 *   - low (green): read-only style tool, usually pre-approved
 *   - medium (yellow): standard write — file_write, file_edit
 *   - high (orange): bash / shell / file_delete
 *   - critical (red, bold): rm -rf, sudo, mkfs, fork bomb, ...
 */

import React from "react";
import { Box, Text } from "ink";
import type { PermissionAsk } from "../state.js";
import { t, icon } from "../theme.js";

interface Props {
  ask: PermissionAsk;
}

const RISK_COLOR: Record<string, string> = {
  low: t.ok,
  medium: t.warn,
  high: "yellowBright", // brighter than yellow
  critical: t.err,
};

const RISK_LABEL: Record<string, string> = {
  low: "LOW",
  medium: "MEDIUM",
  high: "HIGH",
  critical: "CRITICAL",
};

function formatInput(input: Record<string, unknown>): string {
  // Pick out the most useful fields. For bash, show the command.
  // For file_*, show the path. Otherwise show a single-line summary.
  if (typeof input.command === "string") return `command: ${input.command}`;
  if (typeof input.file_path === "string") return `file_path: ${input.file_path}`;
  if (typeof input.path === "string") return `path: ${input.path}`;
  if (typeof input.url === "string") return `url: ${input.url}`;
  const keys = Object.keys(input);
  if (keys.length === 0) return "(no args)";
  return keys.map((k) => `${k}=${JSON.stringify(input[k])}`).join(", ");
}

export const PermissionModal: React.FC<Props> = ({ ask }) => {
  const riskColor = RISK_COLOR[ask.riskLevel] ?? t.warn;
  const riskLabel = RISK_LABEL[ask.riskLevel] ?? ask.riskLevel.toUpperCase();
  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor={riskColor}
      paddingX={2}
      paddingY={1}
    >
      <Text>
        <Text color={riskColor} bold>
          {icon.warn} PERMISSION REQUIRED{" "}
        </Text>
        <Text dimColor> [{riskLabel} risk]</Text>
      </Text>
      <Text> </Text>
      <Text>
        <Text>Tool: </Text>
        <Text bold color={t.brand}>
          {ask.tool}
        </Text>
      </Text>
      <Text>
        <Text>Input: </Text>
        <Text>{formatInput(ask.input)}</Text>
      </Text>
      <Text>
        <Text>Reason: </Text>
        <Text dimColor>{ask.reason}</Text>
      </Text>
      <Text> </Text>
      <Text>Choose an option:</Text>
      {/* R344: numbered selector (1/2/3/4) with letter aliases (A/Y/D/N).
       *  Numbered selector matches Claude Code's modal style. */}
      <Text>
        {" "}
        <Text color={t.ok} bold>
          1 [A]
        </Text>{" "}
        allow this call once
      </Text>
      <Text>
        {" "}
        <Text color={t.ok} bold>
          2 [Y]
        </Text>{" "}
        allow and remember (always for this tool+input)
      </Text>
      <Text>
        {" "}
        <Text color={t.err} bold>
          3 [D]
        </Text>{" "}
        deny this call once
      </Text>
      <Text>
        {" "}
        <Text color={t.err} bold>
          4 [N]
        </Text>{" "}
        deny and remember
      </Text>
    </Box>
  );
};
