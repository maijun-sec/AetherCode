/**
 * welcome banner.
 * a richer first-impression screen with shortcuts, model
 *      info, session id, cwd, and a one-glance "what to do".
 *      Still only shown when the scrollback is empty (so it
 *      disappears once the user submits the first prompt).
 *
 * compacted — the previous version ate half the terminal
 * with shortcut rows. We now show a single dense header line
 * (model · mode · cwd) plus a one-line tip. The user can press
 * Ctrl-? for the full shortcut list, which is the appropriate
 * place for it. The compact welcome keeps the conversation
 * scrollback in view from the very first interaction.
 *
 * T-443: `WelcomeBanner` is the rich variant (model, cwd, mcp
 * tools, etc.) — the new banner is shown on `tutorialOpen` and
 * as the first-launch splash. The compact `Welcome` component
 * is what sits at the top of an empty scrollback; the user
 * sees the SHORTCUTS section so the new ones (Tab to accept
 * autocomplete, Ctrl-B for sidebar, Ctrl-F for search) are
 * discoverable.
 */

import React from "react";
import { Box, Text } from "ink";
import { t, wordmark, icon } from "../theme.js";

interface Props {
  model: string;
  cwd: string;
  sessionId: string;
  /** also show the current permission mode (typically
   *  ACCEPT_TASK on first connect). The user asked "no
   *  mid-task stops" — the welcome screen confirms it. */
  mode?: string;
  /** R245.5: one-line bank status (e.g. "bank: 12 units,
   *  3 kinds, 8 ok / 1 notOk") or "bank: down" if the
   *  daemon isn't reachable. Rendered as a dim hint so
   *  the user sees the bank is wired up without scrolling.
   *  `undefined` means the status fetch is still in flight
   *  (we don't render anything yet, so the welcome doesn't
   *  flash an empty line). */
  bankStatus?: string;
}

/** a compact list of the shortcuts the user can use
 *  from the TUI. Renders inside the welcome panel; the
 *  full list lives in {@code HelpOverlay} (Ctrl-?). */
const SHORTCUTS: ReadonlyArray<{ key: string; label: string; desc: string }> = [
  { key: "Tab",     label: "accept",     desc: "Tab to accept the autocomplete suggestion" },
  { key: "Ctrl-B",  label: "sidebar",    desc: "toggle the left sidebar" },
  { key: "Ctrl-F",  label: "search",     desc: "open the in-scrollback search bar" },
  { key: "Ctrl-?",  label: "help",       desc: "open the full shortcut overlay" },
  { key: "Ctrl-S",  label: "subagents",  desc: "open the background subagent panel" },
  { key: "Ctrl-L",  label: "logs",       desc: "open the daemon log viewer" },
  { key: "/",       label: "commands",   desc: "open the slash command palette" },
  { key: "@",       label: "files",      desc: "fuzzy-complete a file path into the input" },
];

export const Welcome: React.FC<Props> = ({ model, cwd, sessionId, mode, bankStatus }) => {
  return (
    <Box
      flexDirection="column"
      borderStyle="round"
      borderColor={t.brand}
      paddingX={2}
      paddingY={1}
    >
      <Text>
        <Text color={t.brand} bold>{wordmark}</Text>
        <Text>  v0.2.1</Text>
        <Text dimColor>  ·  type a prompt and press Enter</Text>
      </Text>
      <Box>
        <Text>
          <Text color={t.accent}>{icon.model} </Text>
          <Text>{model}</Text>
        </Text>
        <Text dimColor>  ·  </Text>
        <Text>
          <Text color={t.accent}>{icon.id} </Text>
          <Text dimColor>{sessionId}</Text>
        </Text>
        <Text dimColor>  ·  </Text>
        <Text>
          <Text color={t.accent}>{icon.path} </Text>
          <Text dimColor>{cwd}</Text>
        </Text>
        <Text dimColor>  ·  </Text>
        <Text>
          <Text color={t.accent}>{icon.mode} </Text>
          <Text>{mode ?? "ACCEPT_TASK"}</Text>
        </Text>
      </Box>
      <Text dimColor>
        {icon.arrow} no mid-task stops (mode = ACCEPT_TASK) · / for commands · @ for files · Ctrl-? for shortcuts
      </Text>
      {/* R245.5: optional one-line bank snapshot. `undefined`
          means the fetch is still in flight (no flash); the
          host will pass a populated string once readBankStats
          returns. Showing the snapshot on the welcome screen
          is the cheapest way to surface the O-10 wiring
          (R244.2 + R244.3 + R245.1) without the user having
          to type /bank-stats themselves. */}
      {bankStatus ? (
        <Text dimColor>
          {icon.dot} {bankStatus}
        </Text>
      ) : null}
      {/* a one-glance SHORTCUTS grid so the user can
          see the new ones (Tab accept, Ctrl-B sidebar, etc)
          without opening the help overlay. The grid is
          dense — 2 columns × N rows — and uses dim text
          so it doesn't compete with the chat scrollback
          for attention. */}
      <Box marginTop={1} flexDirection="column">
        <Text dimColor>shortcuts:</Text>
        <Box flexWrap="wrap">
          {SHORTCUTS.map((s) => (
            <Box key={s.key} marginRight={2}>
              <Text color={t.accent}>{s.key}</Text>
              <Text dimColor>  {s.label}</Text>
            </Box>
          ))}
        </Box>
      </Box>
    </Box>
  );
};
