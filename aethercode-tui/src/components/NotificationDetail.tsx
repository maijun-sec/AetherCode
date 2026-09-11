/**
 * T-431 (Phase 5 R4): NotificationDetail.
 *
 * 1:1 port of deepagents-code's `NotificationDetailScreen`
 * (Java port at `NotificationDetailScreen.java`). Generic
 * detail modal for a single pending notification. Renders the
 * notification's title, body, and action list. Esc closes
 * without firing an action; Enter activates the highlighted
 * action.
 *
 * design.md §5.2: notification detail.
 *
 * The component is prop-driven. The host feeds it the
 * notification (typically a `PendingNotification` row from
 * the NotificationCenter) and wires `onAction` to dispatch
 * the picked action's RPC.
 */

import React, { useEffect, useState } from "react";
import { Box, Text, useInput } from "ink";

/** Mirrors the `NotificationAction` from `NotificationCenter`. */
export interface NotificationAction {
  actionId: string;
  label: string;
  primary: boolean;
}

export interface NotificationEntry {
  title: string;
  body?: string;
  actions: ReadonlyArray<NotificationAction>;
}

/** Dismissal: the actionId picked, or `null` for cancel. */
export type NotificationDetailResult = string | null;

export interface NotificationDetailProps {
  /** The notification to display. */
  entry: NotificationEntry;
  /** Called when the user activates an action. */
  onAction: (actionId: string) => void;
  /** Called when the user dismisses the modal. */
  onClose: () => void;
  /** Optional title override. */
  title?: string;
}

interface OptionEntry {
  label: string;
  actionId: string;
}

export const NotificationDetail: React.FC<NotificationDetailProps> = ({
  entry,
  onAction,
  onClose,
  title,
}) => {
  const options: OptionEntry[] = entry.actions.map((a) => ({
    label: a.label,
    actionId: a.actionId,
  }));
  const [selected, setSelected] = useState(0);

  useEffect(() => {
    if (selected >= options.length) {
      setSelected(Math.max(0, options.length - 1));
    }
  }, [options.length, selected]);

  useInput((input, key) => {
    if (key.escape) {
      onClose();
      return;
    }
    if (key.upArrow || input === "k" || (key.tab && key.shift)) {
      setSelected((h) => (h - 1 + options.length) % Math.max(1, options.length));
      return;
    }
    if (key.downArrow || input === "j" || key.tab) {
      setSelected((h) => (h + 1) % Math.max(1, options.length));
      return;
    }
    if (key.return) {
      const o = options[selected];
      if (o) onAction(o.actionId);
      return;
    }
  });

  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor="cyan"
      paddingX={2}
      paddingY={1}
    >
      <Box>
        <Text color="cyan" bold>◆ {title ?? entry.title}</Text>
      </Box>
      {entry.body ? (
        <Box flexDirection="column">
          {entry.body.split("\n").map((line, i) => (
            <Text key={i} dimColor>{line}</Text>
          ))}
        </Box>
      ) : null}
      <Text> </Text>
      {options.length === 0 ? (
        <Text dimColor>  (no actions)</Text>
      ) : (
        options.map((o, i) => {
          const isHighlighted = i === selected;
          const action = entry.actions.find((a) => a.actionId === o.actionId);
          const isPrimary = action?.primary ?? false;
          return (
            <Box key={o.actionId} flexDirection="row">
              <Text color={isHighlighted ? "cyan" : undefined}>
                {isHighlighted ? "▶" : " "}{" "}
              </Text>
              <Text color={isPrimary ? "cyan" : undefined} bold={isPrimary || isHighlighted}>
                {o.label}
              </Text>
            </Box>
          );
        })
      )}
      <Text> </Text>
      <Text dimColor>  ↑/↓ (j/k): navigate  Tab/Shift+Tab  Enter: select  Esc: back</Text>
    </Box>
  );
};

/** Pure helper: build the dismissal payload for an action. */
export function buildDetailResult(actionId: string | null): NotificationDetailResult {
  return actionId;
}

/** Pure helper: format a detail title. */
export function formatDetailTitle(title: string, body?: string): string {
  if (!body) return title;
  return `${title}  —  ${truncate(body, 60)}`;
}

function truncate(s: string, n: number): string {
  if (s.length <= n) return s;
  return s.slice(0, n - 1) + "…";
}
