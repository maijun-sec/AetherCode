// Copyright (c) 2026 maijun

// Licensed under the MIT License.
// See the LICENSE file in the project root for the full license text.

/**
 * The Ctrl-? help overlay.
 *
 * R378: this used to carry its OWN hand-written lists of shortcuts and
 * slash commands — a second copy of a truth that had already drifted
 * out of sync with the app-level key handler (it was missing Esc to
 * stop a running turn, among 22 other chords) and that leaked eight
 * development notes into user-facing text ("R37: toggle side panel",
 * "对应历史 round: …").
 *
 * Now there is one registry — `src/keybindings.ts` — and this file
 * RENDERS it. The R378 gate checks the registry against the handler,
 * so the overlay cannot promise a chord nobody binds, and the handler
 * cannot grow a chord the overlay does not mention.
 *
 * The slash-command section is derived too: the panel-opening commands
 * come from the panel registry, and the full list is one keystroke
 * away (`/help`), which is where the complete catalogue lives.
 */

import React from "react";
import { Box, Text } from "ink";
import { t, wordmark } from "../theme.js";
import {
  bindingUnavailable,
  bindingsIn,
  unavailableNote,
  type KeyGroup,
  type Keybinding,
} from "../keybindings.js";
import { ADVERTISED_INTENTS } from "../panelIntents.js";

interface Shortcut {
  key: string;
  desc: string;
  when?: string;
  /** The RPC this chord calls, when the daemon cannot serve it. */
  unavailable?: string;
}

/** One registry entry, flattened for display. */
function toShortcut(b: Keybinding): Shortcut {
  return {
    key: b.label,
    desc: b.desc,
    when: b.when,
    unavailable: bindingUnavailable(b) ? unavailableNote(b) : undefined,
  };
}

// R378: the three section names are kept — the R49 gate pins them, and
// they still describe what the overlay shows. What changed is WHERE the
// rows come from: one registry, not three hand-written arrays.
const SESSION_SHORTCUTS: Shortcut[] = bindingsIn("session").map(toShortcut);
const DISPLAY_SHORTCUTS: Shortcut[] = bindingsIn("display").map(toShortcut);
const EDITING_SHORTCUTS: Shortcut[] = bindingsIn("editing").map(toShortcut);
const SUBAGENT_SHORTCUTS: Shortcut[] = bindingsIn("subagent").map(toShortcut);
const PERMISSION_SHORTCUTS: Shortcut[] = bindingsIn("permission").map(toShortcut);

/**
 * The commands the overlay lists: the ones that open a UI surface.
 *
 * <p>Derived from the panel registry, so a command that opens a panel
 * and a command that does not cannot drift apart. The FULL catalogue
 * (every command the dispatcher answers) is one keystroke away in
 * `/help`, which is where it belongs — 86 rows do not fit in a modal.
 */
const SLASH_GROUPS: Array<{ title: string; cmds: Array<{ name: string; desc: string }> }> = [
  {
    title: "panels",
    cmds: ADVERTISED_INTENTS.map((i) => ({ name: i.command, desc: i.title })),
  },
];

function ShortcutRows({ items }: { items: Shortcut[] }): React.ReactElement {
  return (
    <>
      {items.map((s, i) => (
        <Text key={i}>
          <Text color={t.warn}> {s.key.padEnd(14, " ")}</Text>
          <Text dimColor={!!s.unavailable}>{s.desc}</Text>
          {s.when ? <Text dimColor> ({s.when})</Text> : null}
          {s.unavailable ? <Text color={t.err}> — {s.unavailable}</Text> : null}
        </Text>
      ))}
    </>
  );
}

export const HelpOverlay: React.FC = () => {
  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor={t.brand}
      paddingX={2}
      paddingY={1}
    >
      <Text>
        <Text color={t.brand} bold>
          {wordmark}
        </Text>
        <Text> — keyboard shortcuts & slash commands</Text>
      </Text>
      <Text> </Text>

      <SectionHeader label="session" />
      <ShortcutRows items={SESSION_SHORTCUTS} />

      <Text> </Text>
      <SectionHeader label="display" />
      <ShortcutRows items={DISPLAY_SHORTCUTS} />

      <Text> </Text>
      <SectionHeader label="editing" />
      <ShortcutRows items={EDITING_SHORTCUTS} />

      <Text> </Text>
      <SectionHeader label="sub-agent panel" />
      <ShortcutRows items={SUBAGENT_SHORTCUTS} />

      <Text> </Text>
      <SectionHeader label="permission prompt" />
      <ShortcutRows items={PERMISSION_SHORTCUTS} />

      <Text> </Text>
      <Text bold color={t.accent}>
        slash commands
      </Text>
      {SLASH_GROUPS.map((g) => (
        <Box key={g.title} flexDirection="column" marginLeft={2}>
          <Text color={t.dim}>{g.title}</Text>
          {g.cmds.map((c, i) => (
            <Box key={i} flexDirection="row">
              <Box width={16}>
                <Text color={t.accent}>{c.name.padEnd(14, " ")}</Text>
              </Box>
              <Text dimColor>{c.desc}</Text>
            </Box>
          ))}
        </Box>
      ))}
      <Box marginLeft={2}>
        <Text dimColor>type /help for every command</Text>
      </Box>

      <Text> </Text>
      <Text dimColor>Press any key to close.</Text>
    </Box>
  );
};

const SectionHeader: React.FC<{ label: string }> = ({ label }) => (
  <Text>
    <Text color={t.accent} bold>
      {label}
    </Text>
  </Text>
);
