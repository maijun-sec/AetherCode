/**
 * T-431 (Phase 5 R4): NotificationSettings.
 *
 * 1:1 port of deepagents-code's `NotificationSettings` (Java
 * port at `NotificationSettings.java`). Warning-toggle
 * definitions for the notification hub's settings section.
 * The module is a thin registry of `(key, label)` pairs that
 * drive the notification-center settings panel.
 *
 * The settings themselves are a registry — no UI is rendered
 * here. The host (NotificationCenter.tsx) imports
 * `WARNING_TOGGLES` to build its settings section. A standalone
 * `<NotificationSettings />` component is also exported for
 * callers that want a full-window settings view (e.g. a
 * "Notifications" tab in a settings window).
 *
 * design.md §5.2: notification settings.
 */

import React, { useState } from "react";
import { Box, Text, useInput } from "ink";

/** Warning key for YOLO-mode acknowledgement. */
export const YOLO_WARNING_KEY = "yolo_mode";

/** Warning key for cold-cache prompt-cost warning. */
export const COLD_CACHE_WARNING_KEY = "cold_cache_warning";

/** Warning key for missing `ripgrep` binary. */
export const RIPGREP_WARNING_KEY = "ripgrep";

/** Warning key for missing TAVILY API key. */
export const TAVILY_WARNING_KEY = "tavily";

/**
 * A single toggle row in the notification settings panel.
 * Mirrors the Java record.
 */
export interface Toggle {
  /** Stable identifier; the host stores the user's choice
   *  against this key. */
  warningKey: string;
  /** Human-readable label rendered in the panel. */
  label: string;
}

/**
 * The static list of warning toggles, in display order.
 * Mirrors the Java `WARNING_TOGGLES`.
 */
export const WARNING_TOGGLES: ReadonlyArray<Toggle> = [
  { warningKey: COLD_CACHE_WARNING_KEY, label: "Warn before expensive cold prompt-cache turns" },
  { warningKey: RIPGREP_WARNING_KEY, label: "Warn when ripgrep is not installed" },
  { warningKey: TAVILY_WARNING_KEY, label: "Warn when TAVILY_API_KEY is not set (web search)" },
  { warningKey: YOLO_WARNING_KEY, label: "Warn when YOLO mode is active (no approval review)" },
];

/** Convenience: look up a toggle's label by warning key. */
export function labelFor(key: string): string {
  for (const t of WARNING_TOGGLES) {
    if (t.warningKey === key) return t.label;
  }
  return key;
}

export interface NotificationSettingsProps {
  /** Current state. `true` means enabled, `false` means
   *  suppressed. Missing keys default to `true`. */
  state: Record<string, boolean>;
  /** Called when the user toggles a warning. */
  onToggle: (warningKey: string, enabled: boolean) => void;
  /** Called when the user dismisses the settings view. */
  onClose: () => void;
  /** Optional title override. */
  title?: string;
  /** Optional override for the toggle registry. */
  toggles?: ReadonlyArray<Toggle>;
}

/** Standalone settings view. Most callers use the embedded
 *  version in NotificationCenter; this is for callers that
 *  want a full-window settings tab. */
export const NotificationSettings: React.FC<NotificationSettingsProps> = ({
  state,
  onToggle,
  onClose,
  title = "Notification settings",
  toggles,
}) => {
  const toggleList = toggles ?? WARNING_TOGGLES;
  const [selected, setSelected] = useState(0);

  useInput((input, key) => {
    if (key.escape) {
      onClose();
      return;
    }
    if (key.upArrow || input === "k") {
      setSelected((h) => (h - 1 + toggleList.length) % Math.max(1, toggleList.length));
      return;
    }
    if (key.downArrow || input === "j") {
      setSelected((h) => (h + 1) % Math.max(1, toggleList.length));
      return;
    }
    if (key.return || input === " " || input === "x" || input === "X") {
      const t = toggleList[selected];
      if (t) {
        const next = !(state[t.warningKey] ?? true);
        onToggle(t.warningKey, next);
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
        <Text dimColor>  ↑/↓ (j/k): navigate  Enter/Space: toggle  Esc: close</Text>
      </Box>
      <Text> </Text>
      {toggleList.map((t, i) => {
        const enabled = state[t.warningKey] ?? true;
        const isHighlighted = i === selected;
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
      <Text> </Text>
      <Text dimColor>  Press Enter or Space to flip the highlighted toggle.</Text>
    </Box>
  );
};

/** Pure helper: produce the next state when toggling a key.
 *  Mirrors `NotificationSettings.flip` in deepagents-code. */
export function flipToggle(state: Record<string, boolean>, key: string): Record<string, boolean> {
  const next = !(state[key] ?? true);
  return { ...state, [key]: next };
}

/** Pure helper: produce an "all enabled" initial state from
 *  the registry. */
export function defaultToggleState(toggles: ReadonlyArray<Toggle> = WARNING_TOGGLES): Record<string, boolean> {
  const m: Record<string, boolean> = {};
  for (const t of toggles) m[t.warningKey] = true;
  return m;
}

/** Pure helper: count the number of enabled toggles. */
export function countEnabled(state: Record<string, boolean>, toggles: ReadonlyArray<Toggle> = WARNING_TOGGLES): number {
  let n = 0;
  for (const t of toggles) if (state[t.warningKey] ?? true) n++;
  return n;
}
