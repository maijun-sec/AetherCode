/**
 * progress bar component.
 *
 * A small horizontal bar that fills left-to-right based on a
 * value/max ratio. Uses Unicode block characters for a smooth
 * fill. Renders as part of inline text (not a full Box) so it
 * can be embedded in the StatusBar or any text-flowing region.
 *
 * Examples:
 *   <ProgressBar value={0}   max={100} width={20} /> → [                    ]
 *   <ProgressBar value={50}  max={100} width={20} /> → [██████████          ]
 *   <ProgressBar value={100} max={100} width={20} /> → [████████████████████]
 *
 * The bar is intentionally tiny — 1 character of vertical
 * space. The user can glance at it without context-switching.
 */

import React from "react";
import { Text } from "ink";

interface Props {
  value: number;
  max: number;
  width?: number;
  color?: string;
  /** When true, the empty portion is rendered with a dim character
   *  so the bar is visible even at low values. Default: true. */
  showEmpty?: boolean;
}

const FILLED = "█";
const EMPTY  = "░";
const TICK   = "▏";

export const ProgressBar: React.FC<Props> = ({
  value, max, width = 20, color = "cyan", showEmpty = true,
}) => {
  if (max <= 0) {
    return <Text dimColor>[{"—".repeat(width)}]</Text>;
  }
  const pct = Math.max(0, Math.min(1, value / max));
  const exact = pct * width;
  const full = Math.floor(exact);
  const remainder = exact - full;
  // Partial block for the "in-between" position.
  const partial = remainder > 0.5 ? 1 : 0;
  const empty = Math.max(0, width - full - partial);

  return (
    <Text>
      <Text color={color}>{"["}</Text>
      <Text color={color}>{FILLED.repeat(full)}{partial ? TICK : ""}</Text>
      {showEmpty ? <Text dimColor>{EMPTY.repeat(empty)}</Text> : null}
      <Text color={color}>{"]"}</Text>
    </Text>
  );
};

/** Helper: clamp a value to [0, 1] for use as a fill ratio. */
export function ratio(value: number, max: number): number {
  if (max <= 0) return 0;
  return Math.max(0, Math.min(1, value / max));
}
