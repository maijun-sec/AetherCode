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
 * toast notifications.
 *
 * A short-lived banner that appears at the top of the screen
 * (above the header) for "transient" events like:
 *   - permission grants / denials
 *   - new task notifications
 *   - daemon connection state changes
 *   - low-cost side notes
 *
 * Toasts are different from side notes in that they:
 *   - appear at the top, not interleaved with the scrollback
 *   - have a 2-second TTL (auto-dismiss)
 *   - use a different visual style (border + icon)
 *
 * Implementation: we keep a queue of toasts in state. Each toast
 * has a unique id + createdAt. A 200ms tick effect removes
 * toasts older than 2s. The ToastStack component renders the
 * top N toasts in reverse-chronological order.
 *
 * The user doesn't interact with toasts — they auto-dismiss.
 * If the user wants the full message, they can re-look at the
 * scrollback (side notes are still recorded there).
 */

import React from "react";
import { Box, Text } from "ink";
import { t, icon } from "../theme.js";

export interface Toast {
  id: number;
  kind: "info" | "ok" | "warn" | "err" | "rpc";
  text: string;
  /** Epoch ms. */
  createdAt: number;
}

const KIND_ICON: Record<Toast["kind"], string> = {
  info: icon.note,
  ok: icon.ok,
  warn: icon.warn,
  err: icon.err,
  rpc: icon.dot,
};

const KIND_COLOR: Record<Toast["kind"], string> = {
  info: t.accent,
  ok: t.ok,
  warn: t.warn,
  err: t.err,
  rpc: t.dim,
};

const TOAST_TTL_MS = 2000;
const TOAST_MAX = 4;

export const ToastStack: React.FC<{ toasts: Toast[] }> = ({ toasts }) => {
  if (toasts.length === 0) return null;
  return (
    <Box flexDirection="column-reverse">
      {toasts.slice(0, TOAST_MAX).map((t2) => (
        <Box
          key={t2.id}
          borderStyle="round"
          borderColor={KIND_COLOR[t2.kind]}
          paddingX={1}
          marginY={0}
        >
          <Text>
            <Text color={KIND_COLOR[t2.kind]}>{KIND_ICON[t2.kind]} </Text>
            <Text>{t2.text}</Text>
          </Text>
        </Box>
      ))}
    </Box>
  );
};

/** Filter toasts that are still alive (not older than TTL). */
export function liveToasts(toasts: Toast[], now: number = Date.now()): Toast[] {
  return toasts.filter((t2) => now - t2.createdAt < TOAST_TTL_MS);
}
