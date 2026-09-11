/**
 * T-430 (Phase 5 R4): UpdateAvailable.
 *
 * 1:1 port of deepagents-code's `UpdateAvailableScreen` (Java
 * port at `UpdateAvailableScreen.java`). Modal that surfaces a
 * single update notification with a "View changelog" row and
 * one row per configured action.
 *
 * design.md §5.2: "update flow / update available". The host
 *  fires this when the daemon reports a new release.
 *
 * The component is prop-driven. Enter on a row dispatches
 *  either `onChangelog` (for the "View changelog" row) or
 *  `onAction` (for the user-defined actions). Esc closes.
 */

import React, { useEffect, useState } from "react";
import { Box, Text, useInput } from "ink";

/** Identifier of a `NotificationAction`. The host injects the
 *  concrete value type from the daemon. */
export interface NotificationAction {
  actionId: string;
  label: string;
  primary: boolean;
}

/** The pending update notification. */
export interface PendingUpdate {
  title: string;
  body?: string;
  actions: ReadonlyArray<NotificationAction>;
}

/** Dismissal: the actionId picked, or `null` for cancel.
 *  `__changelog__` is the sentinel for the changelog row. */
export type UpdateAvailableResult =
  | { kind: "action"; actionId: string }
  | { kind: "changelog" }
  | { kind: "cancel" };

export const CHANGELOG_SENTINEL = "__changelog__";

export interface UpdateAvailableProps {
  /** The update notification. */
  entry: PendingUpdate;
  /** Called when the user activates the changelog row. */
  onChangelog: () => void;
  /** Called when the user activates an action row. */
  onAction: (actionId: string) => void;
  /** Called when the user dismisses the modal. */
  onClose: () => void;
  /** Optional title override. */
  title?: string;
  /** Override for the changelog URL. Surfaced via the
   *  onChangelog callback (the host is the one that
   *  actually opens the browser). */
  changelogUrl?: string;
}

/** A row in the option list. */
interface OptionEntry {
  label: string;
  actionId: string;
  changelog: boolean;
}

export const UpdateAvailable: React.FC<UpdateAvailableProps> = ({
  entry,
  onChangelog,
  onAction,
  onClose,
  title,
  changelogUrl,
}) => {
  // Options: changelog first, then one per action.
  const options: OptionEntry[] = [
    { label: "View changelog", actionId: CHANGELOG_SENTINEL, changelog: true },
    ...entry.actions.map((a) => ({ label: a.label, actionId: a.actionId, changelog: false })),
  ];

  const [highlight, setHighlight] = useState(0);

  useEffect(() => {
    if (highlight >= options.length) {
      setHighlight(Math.max(0, options.length - 1));
    }
  }, [options.length, highlight]);

  useInput((input, key) => {
    if (key.escape) {
      onClose();
      return;
    }
    if (key.upArrow || input === "k" || (key.tab && key.shift)) {
      setHighlight((h) => (h - 1 + options.length) % Math.max(1, options.length));
      return;
    }
    if (key.downArrow || input === "j" || key.tab) {
      setHighlight((h) => (h + 1) % Math.max(1, options.length));
      return;
    }
    if (key.return) {
      const o = options[highlight];
      if (!o) return;
      if (o.changelog) onChangelog();
      else onAction(o.actionId);
      return;
    }
  });

  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor="green"
      paddingX={2}
      paddingY={1}
    >
      <Box>
        <Text color="green" bold>◆ {title ?? entry.title}</Text>
      </Box>
      {entry.body ? <Text dimColor>  {entry.body}</Text> : null}
      <Text> </Text>
      {options.map((o, i) => {
        const isHighlighted = i === highlight;
        const prefix = isHighlighted ? "▶ " : "  ";
        if (o.changelog) {
          return (
            <Box key="changelog" flexDirection="row">
              <Text color={isHighlighted ? "cyan" : undefined}>{prefix}</Text>
              <Text dimColor>{o.label}</Text>
              {changelogUrl ? <Text dimColor>  ({changelogUrl})</Text> : null}
            </Box>
          );
        }
        const action = entry.actions.find((a) => a.actionId === o.actionId);
        const isPrimary = action?.primary ?? false;
        return (
          <Box key={o.actionId} flexDirection="row">
            <Text color={isHighlighted ? "cyan" : undefined}>{prefix}</Text>
            <Text color={isPrimary ? "cyan" : undefined} bold={isPrimary || isHighlighted}>
              {o.label}
            </Text>
          </Box>
        );
      })}
      <Text> </Text>
      <Text dimColor>  ↑/↓ (j/k): navigate  Tab/Shift+Tab  Enter: select  Esc: close</Text>
    </Box>
  );
};

/** Pure helper: build the dismissal payload for an action. */
export function dismissAction(actionId: string): UpdateAvailableResult {
  return { kind: "action", actionId };
}

/** Pure helper: build the dismissal payload for the
 *  changelog row. */
export function dismissChangelog(): UpdateAvailableResult {
  return { kind: "changelog" };
}

/** Pure helper: build the dismissal payload for cancel. */
export function dismissUpdateCancel(): UpdateAvailableResult {
  return { kind: "cancel" };
}

/** Default changelog URL. Mirrors the Java `CHANGELOG_URL`. */
export const DEFAULT_CHANGELOG_URL =
  "https://github.com/langchain-ai/deepagents/blob/main/libs/code/CHANGELOG.md";
