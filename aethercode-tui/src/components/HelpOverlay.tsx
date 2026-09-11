/**
 * help overlay.
 * categorized help. Instead of a flat list of every shortcut,
 *      the help is organized into 4 sections:
 *        1. Session      — control the session / exit
 *        2. Display      — toggle panels, focus, search
 *        3. Editing      — history, completion, expand/collapse
 *        4. Slash cmds   — categorized command groups
 *      Each section gets a header + the relevant shortcuts.
 *      This is a much better "discovery" surface than the R31
 *      flat list — the user can scan by category.
 *
 * R49 also updates the slash-command list to include all the
 * new commands added in prior round:
 *   - /theme NAME
 *   - /layout NAME
 *   - /budget USD
 */

import React from "react";
import { Box, Text } from "ink";
import { t, wordmark } from "../theme.js";

interface Shortcut {
  key: string;
  desc: string;
}

const SESSION_SHORTCUTS: Shortcut[] = [
  { key: "Enter",         desc: "submit the current prompt" },
  { key: "Ctrl-C",        desc: "exit the TUI" },
  { key: "Ctrl-? / F1",   desc: "show this help" },
  { key: "Esc",           desc: "dismiss overlay / close search" },
];

const DISPLAY_SHORTCUTS: Shortcut[] = [
  { key: "Ctrl-B",        desc: "R37: toggle side panel" },
  { key: "Ctrl-F",        desc: "R40: open search bar" },
  { key: "Ctrl-P",        desc: "R47: open command palette" },
  { key: "Ctrl-O",        desc: "对应历史 round: collapse / expand all cards" },
];

const EDITING_SHORTCUTS: Shortcut[] = [
  { key: "↑ / ↓",         desc: "navigate input history" },
  { key: "Tab",           desc: "R38: complete slash command" },
  { key: "d",             desc: "R34: expand / collapse tool card details" },
  { key: "Ctrl-E",        desc: "对应历史 round: toggle the most recent card" },
  { key: "A / Y / D / N", desc: "对应历史 round: permission response" },
];

const SLASH_GROUPS: Array<{ title: string; cmds: Array<{ name: string; desc: string }> }> = [
  {
    title: "session",
    cmds: [
      { name: "/help",   desc: "this list" },
      { name: "/state",  desc: "engine state" },
      { name: "/ping",   desc: "liveness check" },
      { name: "/exit",   desc: "quit the TUI" },
    ],
  },
  {
    title: "tools",
    cmds: [
      { name: "/tools",    desc: "list available tools" },
      { name: "/tasks",    desc: "recent tool invocations" },
      { name: "/projects", desc: "known projects (cwds)" },
    ],
  },
  {
    title: "config",
    cmds: [
      { name: "/model <n>",  desc: "switch model" },
      { name: "/mode <n>",   desc: "switch permission mode" },
      { name: "/theme <n>",  desc: "default / solarized / monokai" },
      { name: "/layout <n>", desc: "full / minimal / focus" },
      { name: "/budget <n>", desc: "set per-session cost cap" },
    ],
  },
  {
    title: "data",
    cmds: [
      { name: "/history",    desc: "input history" },
      { name: "/sessions",   desc: "saved sessions" },
      { name: "/stats",      desc: "session stats" },
      { name: "/cwd <path>", desc: "switch project" },
    ],
  },
];

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
        <Text color={t.brand} bold>{wordmark}</Text>
        <Text> — keyboard shortcuts & slash commands</Text>
      </Text>
      <Text> </Text>

      <SectionHeader label="session" />
      {SESSION_SHORTCUTS.map((s, i) => <Row key={"s" + i} k={s.key} v={s.desc} />)}

      <Text> </Text>
      <SectionHeader label="display" />
      {DISPLAY_SHORTCUTS.map((s, i) => <Row key={"d" + i} k={s.key} v={s.desc} />)}

      <Text> </Text>
      <SectionHeader label="editing" />
      {EDITING_SHORTCUTS.map((s, i) => <Row key={"e" + i} k={s.key} v={s.desc} />)}

      <Text> </Text>
      <Text bold color={t.accent}>slash commands</Text>
      {SLASH_GROUPS.map((g) => (
        <Box key={g.title} flexDirection="column" marginLeft={2}>
          <Text color={t.dim}>{g.title}</Text>
          {g.cmds.map((c, i) => (
            <Box key={i} flexDirection="row">
              <Box width={16}><Text color={t.accent}>{c.name.padEnd(14, " ")}</Text></Box>
              <Text dimColor>{c.desc}</Text>
            </Box>
          ))}
        </Box>
      ))}

      <Text> </Text>
      <Text dimColor>Press any key to close.</Text>
    </Box>
  );
};

const SectionHeader: React.FC<{ label: string }> = ({ label }) => (
  <Text>
    <Text color={t.accent} bold>{label}</Text>
  </Text>
);

const Row: React.FC<{ k: string; v: string }> = ({ k, v }) => (
  <Text>
    <Text color={t.warn}>  {k.padEnd(14, " ")}</Text>
    <Text dimColor>{v}</Text>
  </Text>
);
