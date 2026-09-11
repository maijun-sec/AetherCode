/**
 * R180 → T-185: `ContextMeter.tsx` (TUI status bar widget).
 *
 * Per `design.md §2.8` and `spec.md §2.7`:
 *
 *   - Samples `inputTokens` every 2 s.
 *   - 3 colour bands:
 *       < 50 %  →  green
 *       50-80 % →  amber
 *       > 80 %  →  red
 *   - On > 30 % drop in a single sample: that's a compact event
 *     — log it (in the TUI console + via `onCompactEvent`).
 *   - Compaction Recommended button when >= 80 % (wired to `onRunCompact`).
 *
 * Width is 200 px per `design.md §5.2.5`. The bar is rendered
 * with `ink`'s `<Box>` + `<Text>` components so it composes with
 * the existing StatusBar.
 *
 * The component is intentionally **headless about transport**:
 * it accepts `info` (current token snapshot) and the two
 * callbacks. The TUI passes the `compact/run` RPC bridge as
 * `onRunCompact`; tests pass a stub.
 */

import React, { useEffect, useRef, useState } from "react";
import { Box, Text, useInput } from "ink";

/** A single sample of the context-usage time-series. The meter
 *  keeps a rolling window of the last N samples (default 32 ≈
 *  one minute at 2 s/sample) and renders the latest as the bar. */
export interface ContextSample {
  /** Epoch ms when the sample was taken. */
  ts: number;
  /** Token count of the current input prompt. */
  inputTokens: number;
}

/** The minimal "what's happening in the engine" snapshot the
 *  meter needs to render. The TUI pulls this from `state.ts`
 *  on every poll; tests pass a static object. */
export interface ContextInfo {
  /** Token count of the current input prompt. */
  inputTokens: number;
  /** Effective input window (maxTokens - maxOutputFloor). */
  maxTokens: number;
  /** Optional timestamp of the last compact event — used to
   *  surface a transient "↓ compacted" hint. */
  lastCompactTs?: number | null;
  /** When true, the meter treats the session as auto-compact
   *  disabled (red border, warning text). The TUI derives this
   *  from `compact/status`. */
  autoCompactDisabled?: boolean;
}

/** Default sample interval in ms (T-182). 2 s per design. */
export const DEFAULT_SAMPLE_INTERVAL_MS = 2_000;

/** Default bar width in px (T-180). 200 px per design §5.2.5. */
export const DEFAULT_BAR_WIDTH = 200;

/** Drop threshold for a "compact event" (T-183). > 30 % in 1
 *  sample is treated as evidence of a compact pass having run. */
export const COMPACT_DROP_THRESHOLD = 0.30;

/** The 80 % mark at which the Compaction Recommended button shows (T-184). */
export const RECOMMEND_BUTTON_THRESHOLD = 0.80;

/** Maximum number of samples kept in the rolling window. */
export const MAX_SAMPLES = 64;

/** Props accepted by `<ContextMeter>`. */
export interface ContextMeterProps {
  /** Current snapshot of the engine's input-token state. */
  info: ContextInfo;
  /** Fired when the user activates the Compaction Recommended button (Enter /
   *  space). The TUI wires this to the `compact/run` RPC. */
  onRunCompact?: () => void;
  /** Fired when the meter observes a > 30 % drop in `inputTokens`
   *  between two consecutive samples. The TUI logs the event;
   *  tests assert the callback was called. */
  onCompactEvent?: (drop: { from: number; to: number; atMs: number }) => void;
  /** Bar width in characters. Default 200. */
  width?: number;
  /** Sample interval in ms. Default 2_000. */
  sampleIntervalMs?: number;
  /** Override clock (for tests). */
  now?: () => number;
  /** Show a textual "%" label next to the bar. Default true. */
  showLabel?: boolean;
}

/** Map a fill ratio to a colour band. The thresholds match
 *  `design.md §2.8` exactly: < 50 % green, 50-80 % amber, > 80 % red. */
export function bandColor(ratio: number): "green" | "yellow" | "red" {
  if (ratio > 0.80) return "red";
  if (ratio >= 0.50) return "yellow";
  return "green";
}

/** Fill a bar of `width` characters to `pct` (0..1). Uses
 *  Unicode block characters for a smooth gradient. */
export function fillBar(pct: number, width: number): { filled: number; partial: boolean; empty: number } {
  if (width <= 0) return { filled: 0, partial: false, empty: 0 };
  const clamped = Math.max(0, Math.min(1, pct));
  const exact = clamped * width;
  const filled = Math.floor(exact);
  const partial = exact - filled > 0.5;
  const empty = Math.max(0, width - filled - (partial ? 1 : 0));
  return { filled, partial, empty };
}

/** Pure helper: detect a > 30 % drop between two samples. */
export function detectCompactDrop(
  prev: ContextSample | null,
  next: ContextSample,
  threshold: number = COMPACT_DROP_THRESHOLD,
): { from: number; to: number; atMs: number } | null {
  if (!prev) return null;
  if (prev.inputTokens <= 0) return null;
  const drop = (prev.inputTokens - next.inputTokens) / prev.inputTokens;
  if (drop > threshold) {
    return { from: prev.inputTokens, to: next.inputTokens, atMs: next.ts };
  }
  return null;
}

const FILLED = "█";
const PARTIAL = "▏";
const EMPTY = "░";

export const ContextMeter: React.FC<ContextMeterProps> = ({
  info,
  onRunCompact,
  onCompactEvent,
  width = DEFAULT_BAR_WIDTH,
  sampleIntervalMs = DEFAULT_SAMPLE_INTERVAL_MS,
  now,
  showLabel = true,
}) => {
  const clock = now ?? (() => Date.now());
  const [samples, setSamples] = useState<ContextSample[]>(() => [
    { ts: clock(), inputTokens: info.inputTokens },
  ]);
  const [buttonFocused, setButtonFocused] = useState(false);
  const lastCompactEventRef = useRef<number | null>(info.lastCompactTs ?? null);
  // Keep the latest `onCompactEvent` / `onRunCompact` callbacks in a
  // ref so the polling effect doesn't re-run when they change.
  const callbacksRef = useRef({ onCompactEvent, onRunCompact });
  callbacksRef.current = { onCompactEvent, onRunCompact };

  // Polling: every `sampleIntervalMs` ms, push a new sample.
  // On the first sample after mount we just record; on every
  // subsequent sample we run `detectCompactDrop` against the
  // previous sample and fire `onCompactEvent` if the drop is
  // > 30 % (T-182 / T-183).
  useEffect(() => {
    const id = setInterval(() => {
      const ts = clock();
      setSamples((prev) => {
        const last = prev.length > 0 ? prev[prev.length - 1] : null;
        const next: ContextSample = { ts, inputTokens: info.inputTokens };
        const drop = detectCompactDrop(last, next);
        if (drop) {
          // Fire the event AFTER state update so the meter can
          // reflect the new (post-drop) value in the same paint.
          queueMicrotask(() => {
            try { callbacksRef.current.onCompactEvent?.(drop); } catch { /* ignore */ }
          });
        }
        const merged = prev.concat(next);
        if (merged.length > MAX_SAMPLES) {
          return merged.slice(merged.length - MAX_SAMPLES);
        }
        return merged;
      });
    }, sampleIntervalMs);
    return () => clearInterval(id);
  }, [info.inputTokens, sampleIntervalMs, clock]);

  // Keep `lastCompactTs` in sync so the transient hint follows
  // RPC-driven compact events too (not just the sampled ones).
  useEffect(() => {
    const last = info.lastCompactTs ?? null;
    if (last != null && last !== lastCompactEventRef.current) {
      lastCompactEventRef.current = last;
    }
  }, [info.lastCompactTs]);

  // Keyboard: Tab focuses the button; Enter / Space fires it.
  useInput((input, key) => {
    if (key.tab) {
      setButtonFocused((b) => !b);
      return;
    }
    if (!buttonFocused) return;
    if (key.return || input === " ") {
      try { callbacksRef.current.onRunCompact?.(); } catch { /* ignore */ }
    }
  });

  const ratio = info.maxTokens > 0 ? info.inputTokens / info.maxTokens : 0;
  const color = bandColor(ratio);
  const { filled, partial, empty } = fillBar(ratio, width);
  const pct = Math.round(ratio * 100);
  const showButton = ratio >= RECOMMEND_BUTTON_THRESHOLD;
  const hint =
    lastCompactEventRef.current != null
    && clock() - lastCompactEventRef.current < 5_000
      ? "↓ compacted"
      : null;

  return (
    <Box flexDirection="row" alignItems="center">
      <Text dimColor>ctx </Text>
      <Text>
        <Text color={color}>{"["}</Text>
        <Text color={color}>{FILLED.repeat(filled)}{partial ? PARTIAL : ""}</Text>
        <Text dimColor>{EMPTY.repeat(empty)}</Text>
        <Text color={color}>{"]"}</Text>
      </Text>
      {showLabel ? (
        <Text>
          <Text dimColor>  </Text>
          <Text color={color}>{pct}%</Text>
          <Text dimColor> ({info.inputTokens}/{info.maxTokens})</Text>
        </Text>
      ) : null}
      {hint ? <Text color="green">  {hint}</Text> : null}
      {info.autoCompactDisabled ? (
        <Text color="red">  ⚠ auto-compact off</Text>
      ) : null}
      {showButton ? (
        <Box marginLeft={1}>
          <Text
            backgroundColor={buttonFocused ? color : undefined}
            color={buttonFocused ? "black" : color}
          >
            {buttonFocused ? " ▶ 压缩推荐 " : "   压缩推荐   "}
          </Text>
        </Box>
      ) : null}
    </Box>
  );
};

/** Default props merged — useful for tests + Storybook. */
export const DEFAULT_CONTEXT_METER_PROPS: Partial<ContextMeterProps> = {
  width: DEFAULT_BAR_WIDTH,
  sampleIntervalMs: DEFAULT_SAMPLE_INTERVAL_MS,
  showLabel: true,
};
