/**
 * T-431 (Phase 5 R4): NotificationCenter.
 *
 * 1:1 port of deepagents-code's `NotificationCenterScreen`
 * (Java port at `NotificationCenterScreen.java`). Notification
 * hub for pending notices and warning preferences. Surfaces
 * `PendingNotification` entries as single-line rows plus an
 * expandable settings section (warning toggles).
 *
 * design.md §5.2: notification hub. The spec lists
 * `NotificationCenter` alongside the other R4 components.
 *
 * The component is prop-driven. The host fetches the
 * notification list and supplies it as `entries`. The host
 * also supplies the `WARNING_TOGGLES` registry (via
 * `./NotificationSettings.js`).
 */

import React, { useEffect, useMemo, useState } from "react";
import { Box, Text, useInput } from "ink";
import { WARNING_TOGGLES, type Toggle } from "./NotificationSettings.js";

/** Identifier of a `NotificationAction`. The host injects
 *  the concrete value type from the daemon. */
export interface ActionId {
  value: string;
}

export interface NotificationAction {
  actionId: ActionId;
  label: string;
  primary: boolean;
}

export interface PendingNotification {
  key: string;
  title: string;
  body?: string;
  actions: ReadonlyArray<NotificationAction>;
}

/** Dismissal payload identifying which action the user
 *  picked. */
export interface ActionResult {
  key: string;
  actionId: ActionId;
}

export interface NotificationCenterProps {
  /** All pending notifications. */
  entries: ReadonlyArray<PendingNotification>;
  /** Called when the user activates a notification. */
  onSelect: (result: ActionResult) => void;
  /** Called when the user dismisses the hub. */
  onClose: () => void;
  /** Called when the user toggles a warning in the settings
   *  section. The host is the one that persists the change. */
  onToggleWarning: (warningKey: string, enabled: boolean) => void;
  /** Optional override for the toggle registry. */
  toggles?: ReadonlyArray<Toggle>;
  /** Optional title override. */
  title?: string;
}

/** A row in the settings list. */
interface ToggleRow {
  key: string;
  label: string;
  enabled: boolean;
}

export const NotificationCenter: React.FC<NotificationCenterProps> = ({
  entries,
  onSelect,
  onClose,
  onToggleWarning,
  toggles,
  title = "Notification Center",
}) => {
  const toggleList = toggles ?? WARNING_TOGGLES;
  const [selectedRow, setSelectedRow] = useState(0);
  const [settingsExpanded, setSettingsExpanded] = useState(false);
  // Per-toggle state — defaults to enabled.
  const [toggleState, setToggleState] = useState<Record<string, boolean>>(() => {
    const m: Record<string, boolean> = {};
    for (const t of toggleList) m[t.warningKey] = true;
    return m;
  });

  // Total selectable rows: notifications + (when expanded) toggles + 1 for the header.
  const settingsHeaderIndex = entries.length;
  const toggleStart = entries.length + 1;
  const totalSelectable = settingsExpanded ? toggleStart + toggleList.length : settingsHeaderIndex + 1;

  // Clamp when the entry list shrinks.
  useEffect(() => {
    if (selectedRow >= totalSelectable) {
      setSelectedRow(Math.max(0, totalSelectable - 1));
    }
  }, [totalSelectable, selectedRow]);

  useInput((input, key) => {
    if (key.escape) {
      onClose();
      return;
    }
    if (key.upArrow || input === "k") {
      setSelectedRow((h) => (h - 1 + totalSelectable) % Math.max(1, totalSelectable));
      return;
    }
    if (key.downArrow || input === "j") {
      setSelectedRow((h) => (h + 1) % Math.max(1, totalSelectable));
      return;
    }
    if (key.tab) {
      setSettingsExpanded((v) => !v);
      return;
    }
    if (key.return) {
      if (selectedRow < entries.length) {
        const e = entries[selectedRow];
        if (e.actions.length > 0) {
          onSelect({ key: e.key, actionId: e.actions[0].actionId });
        }
        return;
      }
      if (selectedRow === settingsHeaderIndex) {
        // Toggle the section.
        setSettingsExpanded((v) => !v);
        return;
      }
      if (settingsExpanded) {
        const t = toggleList[selectedRow - toggleStart];
        if (t) {
          const next = !(toggleState[t.warningKey] ?? true);
          setToggleState((m) => ({ ...m, [t.warningKey]: next }));
          onToggleWarning(t.warningKey, next);
        }
      }
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
        <Text color="cyan" bold>◆ {title}</Text>
        <Text dimColor>  ↑/↓ (j/k): navigate  Enter: select  Tab: settings  Esc: close</Text>
      </Box>
      <Text> </Text>
      {entries.length === 0 ? (
        <Text dimColor>  No pending notifications.</Text>
      ) : (
        entries.map((p, i) => {
          const isHighlighted = i === selectedRow;
          return (
            <Box key={p.key} flexDirection="row">
              <Text color={isHighlighted ? "cyan" : undefined}>
                {isHighlighted ? "▶" : " "}{" "}
              </Text>
              <Text color={p.title ? undefined : "gray"}>{p.title || "(untitled)"}</Text>
              {p.actions.length > 0 ? (
                <Text dimColor>  ·  {p.actions.length} action{p.actions.length === 1 ? "" : "s"}</Text>
              ) : null}
            </Box>
          );
        })
      )}
      <Text> </Text>
      <Box flexDirection="row">
        <Text color={selectedRow === settingsHeaderIndex ? "cyan" : undefined}>
          {selectedRow === settingsHeaderIndex ? "▶" : " "}{" "}
        </Text>
        <Text color="cyan" bold>
          {settingsExpanded ? "▼ " : "▶ "}Notification settings
        </Text>
      </Box>
      {settingsExpanded ? (
        <Box flexDirection="column">
          {toggleList.map((t, i) => {
            const realIndex = toggleStart + i;
            const isHighlighted = realIndex === selectedRow;
            const enabled = toggleState[t.warningKey] ?? true;
            return (
              <Box key={t.warningKey} flexDirection="row">
                <Text color={isHighlighted ? "cyan" : undefined}>
                  {isHighlighted ? "▶" : " "}{" "}
                </Text>
                <Text>{enabled ? "[x]" : "[ ]"}</Text>
                <Text>  {t.label}</Text>
              </Box>
            );
          })}
        </Box>
      ) : null}
      <Text> </Text>
      <Text dimColor>  ↑/↓ navigate  Enter select  Tab expand settings  Esc close</Text>
    </Box>
  );
};

/** Pure helper: build the dismissal payload for a row pick. */
export function buildActionResult(
  entry: PendingNotification,
  actionIndex: number = 0,
): ActionResult | null {
  if (entry.actions.length === 0) return null;
  const a = entry.actions[Math.min(actionIndex, entry.actions.length - 1)];
  if (!a) return null;
  return { key: entry.key, actionId: a.actionId };
}

/** Pure helper: count the number of pending notifications. */
export function pendingCount(entries: ReadonlyArray<PendingNotification>): number {
  return entries.length;
}
