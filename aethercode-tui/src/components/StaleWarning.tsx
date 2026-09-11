/**
 * T-6-18: `StaleWarning` (re-attach prompt on stale session).
 *
 * Per spec §2.1, sessions with no activity for > 30 min
 * show a "stale" badge and a re-attach prompt on click.
 * This component is a centered modal that surfaces the
 * prompt and offers two actions:
 *
 *   - "Re-attach" (default) — calls `onReattach()`.
 *   - "Cancel"             — calls `onCancel()`.
 *
 * The component is headless about transport. The TUI
 * wires `onReattach` to `task/attach <id>` and
 * `onCancel` to a `dispatch({ type: "staleClosed" })`.
 *
 * Keyboard:
 *   - `Enter` / `y` → re-attach (default action)
 *   - `n` / `Esc`   → cancel
 *   - `Tab`         → swap the focused button
 *
 * Pure helpers:
 *   - `formatStaleDuration(ms)` — renders a human
 *     "2h 13m" string.
 *   - `shouldWarnStale(lastActiveAt, now, threshold)`
 *     — pure predicate so the TUI can decide whether
 *     to mount the modal at all.
 */

import React, { useState } from "react";
import { Box, Text, useInput } from "ink";
import { t, icon } from "../theme.js";

export const STALE_THRESHOLD_MS = 30 * 60 * 1000;

export interface StaleWarningProps {
  /** Last-active epoch ms. The modal is only meaningful
   *  when this is older than the threshold. */
  lastActiveAt: number;
  sessionId: string;
  onReattach: () => void;
  onCancel: () => void;
  /** Wall-clock for the duration display. Tests inject. */
  now?: number;
  /** Override the default 30 min threshold. */
  thresholdMs?: number;
}

/** Pure helper: format a duration in ms as a human
 *  "Xh Ym" / "Xm" / "Xs" string. */
export function formatStaleDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return "0s";
  const total = Math.floor(ms / 1000);
  if (total < 60) return `${total}s`;
  const m = Math.floor(total / 60);
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  const remM = m % 60;
  if (h < 24) return remM === 0 ? `${h}h` : `${h}h ${remM}m`;
  const d = Math.floor(h / 24);
  const remH = h % 24;
  return remH === 0 ? `${d}d` : `${d}d ${remH}h`;
}

/** Pure helper: should the modal show? `lastActiveAt`
 *  older than `thresholdMs` triggers the warning. */
export function shouldWarnStale(
  lastActiveAt: number,
  now: number = Date.now(),
  thresholdMs: number = STALE_THRESHOLD_MS
): boolean {
  if (!Number.isFinite(lastActiveAt) || lastActiveAt <= 0) return false;
  if (!Number.isFinite(now)) return false;
  return now - lastActiveAt > thresholdMs;
}

export const StaleWarning: React.FC<StaleWarningProps> = ({
  lastActiveAt,
  sessionId,
  onReattach,
  onCancel,
  now = Date.now(),
  thresholdMs = STALE_THRESHOLD_MS,
}) => {
  const [focus, setFocus] = useState<"reattach" | "cancel">("reattach");
  const elapsed = now - lastActiveAt;
  const isStale = shouldWarnStale(lastActiveAt, now, thresholdMs);

  useInput((input, key) => {
    if (key.return) {
      if (focus === "reattach") onReattach();
      else onCancel();
      return;
    }
    if (input === "y" || input === "Y") { onReattach(); return; }
    if (input === "n" || input === "N" || key.escape) { onCancel(); return; }
    if (key.tab || key.leftArrow || key.rightArrow) {
      setFocus((f) => (f === "reattach" ? "cancel" : "reattach"));
      return;
    }
  });

  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor={isStale ? t.warn : t.dim}
      paddingX={2}
      paddingY={1}
    >
      <Text>
        <Text color={t.warn} bold>{icon.warn} Re-attach? </Text>
        <Text dimColor>(stale session)</Text>
      </Text>
      <Text> </Text>
      <Text>
        <Text>This session has been inactive for </Text>
        <Text color={t.warn} bold>{formatStaleDuration(elapsed)}</Text>
        <Text>.</Text>
      </Text>
      <Text>
        <Text dimColor>id </Text>
        <Text color={t.accent}>{sessionId.slice(0, 12)}</Text>
      </Text>
      <Text> </Text>
      <Box flexDirection="row">
        <Text
          backgroundColor={focus === "reattach" ? t.ok : undefined}
          color={focus === "reattach" ? "black" : t.ok}
        >
          {focus === "reattach" ? " " : ""}[Re-attach (y)]{focus === "reattach" ? " " : ""}
        </Text>
        <Text>  </Text>
        <Text
          backgroundColor={focus === "cancel" ? t.dim : undefined}
          color={focus === "cancel" ? "black" : t.dim}
        >
          {focus === "cancel" ? " " : ""}[Cancel (n)]{focus === "cancel" ? " " : ""}
        </Text>
      </Box>
      <Text> </Text>
      <Text dimColor>Enter / y to re-attach · n / Esc to cancel · Tab to swap</Text>
    </Box>
  );
};

export default StaleWarning;
