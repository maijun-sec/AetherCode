// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

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
const EMPTY = "░";
const TICK = "▏";

export const ProgressBar: React.FC<Props> = ({
  value,
  max,
  width = 20,
  color = "cyan",
  showEmpty = true,
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
      <Text color={color}>
        {FILLED.repeat(full)}
        {partial ? TICK : ""}
      </Text>
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
