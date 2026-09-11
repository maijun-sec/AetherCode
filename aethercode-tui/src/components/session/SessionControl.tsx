/**
 * T-6-06: `SessionControl` (Continue / Pause / Stop).
 *
 * Per spec §5.2, the user sees a horizontal row of 3
 * buttons at the top of the session panel:
 *
 *   - Continue  — sends `task/resume <childId>` (the
 *                 session goes from `paused` / `completed`
 *                 back to `running`).
 *   - Pause     — sends `task/pause` (the agent stops at
 *                 the next safe checkpoint).
 *   - Stop      — sends `task/kill` (graceful shutdown,
 *                 final state is persisted).
 *
 * The component is "headless" about transport: it accepts
 * the three callbacks. The TUI wires them to the
 * `JsonRpcClient.request` method. Tests pass stubs and
 * assert the right method was called.
 *
 * Keyboard:
 *   - `c` → Continue
 *   - `p` → Pause
 *   - `s` → Stop
 *   - `j` / `k` (or Tab) → move focus left/right
 *   - `Enter` / `Space` → activate the focused button
 *
 * Disabled buttons (e.g. when the session is already
 * running and "Pause" is the only valid action) are
 * rendered in a different color. The host passes the
 * current `state` and the component computes which
 * actions are valid.
 */

import React, { useState } from "react";
import { Box, Text, useInput } from "ink";
import { t, icon } from "../../theme.js";

export type SessionState =
  | "running"
  | "paused"
  | "completed"
  | "failed"
  | "cancelled";

export type ControlAction = "continue" | "pause" | "stop";

export interface SessionControlProps {
  state: SessionState;
  /** "running" | "paused" | "completed" | "failed" | "cancelled".
   *  Disabled buttons render in a different colour; the
   *  caller can also pass `disabled` overrides for
   *  edge cases. */
  disabled?: Partial<Record<ControlAction, boolean>>;
  onContinue?: () => void;
  onPause?: () => void;
  onStop?: () => void;
  /** Optional: hide the keyboard hint line (when the
   *  panel is rendered in a non-interactive context). */
  showHotkeyHint?: boolean;
}

/** Pure helper: which actions are valid in each state. */
export function validActions(state: SessionState): ReadonlySet<ControlAction> {
  switch (state) {
    case "running":   return new Set(["pause", "stop"]);
    case "paused":    return new Set(["continue", "stop"]);
    case "completed": return new Set(["continue"]);
    case "failed":    return new Set(["continue", "stop"]);
    case "cancelled": return new Set(["continue"]);
    default:          return new Set();
  }
}

const ACTION_ORDER: ControlAction[] = ["continue", "pause", "stop"];

const HOTKEY: Record<ControlAction, string> = {
  continue: "c",
  pause: "p",
  stop: "s",
};

const LABEL: Record<ControlAction, string> = {
  continue: "Continue",
  pause: "Pause",
  stop: "Stop",
};

const COLOR: Record<ControlAction, string> = {
  continue: t.ok,
  pause: t.warn,
  stop: t.err,
};

export const SessionControl: React.FC<SessionControlProps> = ({
  state,
  disabled,
  onContinue,
  onPause,
  onStop,
  showHotkeyHint = true,
}) => {
  const valid = validActions(state);
  const [focus, setFocus] = useState<number>(0);
  const onClick = (a: ControlAction) => {
    if (!valid.has(a)) return;
    if (disabled?.[a]) return;
    switch (a) {
      case "continue": onContinue?.(); break;
      case "pause":    onPause?.(); break;
      case "stop":     onStop?.(); break;
    }
  };

  useInput((input, key) => {
    if (input === HOTKEY.continue) { onClick("continue"); return; }
    if (input === HOTKEY.pause)    { onClick("pause");    return; }
    if (input === HOTKEY.stop)     { onClick("stop");     return; }
    if (key.tab) {
      setFocus((f) => (f + 1) % ACTION_ORDER.length);
      return;
    }
    if (key.leftArrow) {
      setFocus((f) => (f - 1 + ACTION_ORDER.length) % ACTION_ORDER.length);
      return;
    }
    if (key.rightArrow) {
      setFocus((f) => (f + 1) % ACTION_ORDER.length);
      return;
    }
    if (key.return || input === " ") {
      onClick(ACTION_ORDER[focus]!);
      return;
    }
  });

  return (
    <Box flexDirection="column" paddingX={1}>
      <Box flexDirection="row">
        {ACTION_ORDER.map((a, i) => {
          const isValid = valid.has(a);
          const isDisabled = !!disabled?.[a];
          const isFocused = i === focus;
          const label = LABEL[a];
          const color = isValid && !isDisabled ? COLOR[a] : t.dim;
          return (
            <Box key={a} marginRight={1}>
              <Text
                color={isFocused ? color : color}
                bold={isFocused}
                backgroundColor={isFocused ? color : undefined}
              >
                {isFocused ? " " : ""}{label}{isFocused ? " " : ""}
              </Text>
              {isFocused ? (
                <Text color="black"> </Text>
              ) : null}
              <Text dimColor> [{HOTKEY[a]}]</Text>
            </Box>
          );
        })}
      </Box>
      {showHotkeyHint ? (
        <Box flexDirection="row">
          <Text dimColor>{icon.arrow} c/p/s to act · Tab/←/→ to focus · Enter to confirm</Text>
        </Box>
      ) : null}
    </Box>
  );
};

/** Build the props for an integration test. The shape is
 *  intentionally minimal so a test can pass a literal
 *  without depending on the rest of the type hierarchy. */
export function makeSessionControlProps(state: SessionState, cb: {
  onContinue?: () => void;
  onPause?: () => void;
  onStop?: () => void;
}): SessionControlProps {
  return { state, ...cb };
}

export default SessionControl;
