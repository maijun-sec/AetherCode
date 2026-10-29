// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

/**
 * R374: the Ink surface for `/update` and `/update-deps`.
 *
 * ## Why this is not `UpdateAvailable` / `UpdateConfirm` / `UpdateProgress`
 *
 * All three of those exist and are complete, and all three are
 * unmounted — because each one needs a fact this daemon cannot
 * produce:
 *
 *   - `UpdateAvailable` needs a `PendingUpdate` ("an update is
 *     available" + the actions to take on it);
 *   - `UpdateConfirm` needs `current` and `latest` versions to
 *     confirm a move between;
 *   - `UpdateProgress` needs a download in flight.
 *
 * The daemon has no update RPC at all, so there is no "an update is
 * available" to render, no `latest` to compare against, and nothing
 * to download. Mounting any of them would put a button on screen
 * that cannot do anything — the exact "dead button" the R365
 * `backend-missing` blocker exists to prevent.
 *
 * ## What this shows instead
 *
 * The facts that DO exist, and the path that DOES work:
 *
 *   1. the versions, side by side (the "current vs available" shape
 *      every mature updater uses);
 *   2. a headline that says what did NOT happen;
 *   3. the manual path — the package-manager route Electron's own
 *      docs recommend for platforms with no built-in updater;
 *   4. the changelog URL, printed on request.
 *
 * The one interactive row is the changelog, and it prints a URL —
 * something a terminal can genuinely do. There is no "Install" row,
 * because there is nothing to install.
 */

import { hintFor } from "../componentKeys.js";
import React from "react";
import { Box, Text, useInput } from "ink";
import type { UpdatePanelModel } from "../updateInfo.js";

export interface UpdatePanelProps {
  /** The content, built by `buildUpdatePanel`. */
  model: UpdatePanelModel;
  /** Called when the user asks for the changelog. The host prints the
   *  URL — the terminal is what opens it, not this component. */
  onChangelog: () => void;
  /** Called when the user dismisses the panel. */
  onClose: () => void;
  /** Optional title override. */
  title?: string;
}

export const UpdatePanel: React.FC<UpdatePanelProps> = ({ model, onChangelog, onClose, title }) => {
  useInput((_input, key) => {
    if (key.escape) {
      onClose();
      return;
    }
    if (key.return) {
      onChangelog();
    }
  });

  return (
    <Box flexDirection="column" borderStyle="double" borderColor="cyan" paddingX={2} paddingY={1}>
      <Box>
        <Text color="cyan" bold>
          ◆ {title ?? model.title}
        </Text>
        <Text dimColor> {hintFor("components/UpdatePanel.tsx")}</Text>
      </Box>
      <Text dimColor> {model.endpoint}</Text>
      <Text> </Text>
      {/* The honest answer leads. A panel whose first line is a
          button is a promise the daemon cannot keep. */}
      <Text>{wrap(model.headline, 72)}</Text>
      <Text> </Text>
      {model.rows.map(([k, v]) => (
        <Box key={k} flexDirection="row">
          <Text dimColor> {k.padEnd(16)}</Text>
          <Text>{v}</Text>
        </Box>
      ))}
      <Text> </Text>
      {model.manual.map((l, i) => (
        <Text key={i} dimColor={i === 0 ? undefined : true}>
          {"  "}
          {l}
        </Text>
      ))}
      <Text> </Text>
      <Box flexDirection="row">
        <Text color="cyan"> ▶ </Text>
        <Text>View changelog</Text>
        <Text dimColor> {model.changelogUrl}</Text>
      </Box>
    </Box>
  );
};

/** Soft-wrap a paragraph to `width`, indenting continuations. */
function wrap(text: string, width: number): string {
  const words = text.split(/\s+/);
  const out: string[] = [];
  let line = "";
  for (const w of words) {
    if (line.length === 0) {
      line = w;
    } else if (line.length + 1 + w.length <= width) {
      line += " " + w;
    } else {
      out.push(line);
      line = w;
    }
  }
  if (line.length > 0) out.push(line);
  return out.join("\n");
}
