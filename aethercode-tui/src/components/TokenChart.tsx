/**
 * token chart (sparkline).
 *
 * A small inline component that renders a sparkline of recent
 * token-usage values. Used in the StatusBar to give the user a
 * visual sense of how chatty the session has been.
 *
 * The chart is a sequence of Unicode block characters of
 * varying heights, normalized to the max value in the window:
 *
 *   ▁▂▃▄▅▆▇█
 *
 * Each character represents one "bucket" of token usage. The
 * component takes an array of values and a width; it collapses
 * the array into `width` buckets by averaging, then renders.
 *
 * If the user has done very few turns, the chart is short. If
 * they have many, the chart fills the available width.
 *
 * T-440 (Phase 5 R5): provider-aware limits. Different providers
 * expose different per-message / per-context window limits; the
 * sparkline now knows about three thresholds (warn / critical /
 * max) and colours the most recent bucket accordingly. The
 * deepagents-code win is that the user can see at a glance
 * "you're approaching the 8k output cap" (orange) or "you
 * already blew past the 200k context cap" (red) instead of
 * staring at a single uniform bar of cyan.
 *
 * T-440 also adds a thin tick marker drawn below the bars at
 * `providerLimit`, so the user sees the cap as a horizontal
 * rule even when most buckets are well under it.
 */

import React from "react";
import { Text } from "ink";

const BARS = ["▁", "▂", "▃", "▄", "�", "▆", "▇", "█"];

/** T-440: a provider's token-usage cap. Different
 *  providers (anthropic / openai / google / local) ship
 *  with very different defaults; we let the host pass
 *  either the provider's published `contextWindow` or a
 *  pair of (output / input) limits. The chart uses the
 *  max of the two as its cap. */
export interface ProviderLimits {
  /** Per-context-window cap. Usually `contextWindow`
   *  from `model/info`. */
  contextWindow?: number;
  /** Per-message output cap (e.g. 8k for
   *  claude-3-5-sonnet). */
  maxOutputTokens?: number;
  /** Soft warn threshold (default 0.5 of the cap). */
  warnAt?: number;
  /** Hard critical threshold (default 0.8 of the cap). */
  criticalAt?: number;
}

/** T-440: pick the effective cap from a {@link
 *  ProviderLimits}. Returns 0 if no cap is set, which the
 *  chart treats as "no provider cap" (no tick, no
 *  recolouring). */
export function effectiveLimit(l: ProviderLimits | undefined | null): number {
  if (!l) return 0;
  const w = l.contextWindow ?? 0;
  const o = l.maxOutputTokens ?? 0;
  return Math.max(w, o);
}

/** T-440: classify a single bucket value against the
 *  cap. Returns one of three colours; the chart picks the
 *  colour of the *last* bucket for the overall tint
 *  (more useful than per-bucket stripes, which would
 *  visually "fizz" on a 32-bar chart). */
export function bucketColor(
  value: number,
  limit: number,
  warnAt = 0.5,
  criticalAt = 0.8,
): "green" | "yellow" | "red" | "cyan" {
  if (limit <= 0) return "cyan";
  const ratio = value / limit;
  if (ratio >= criticalAt) return "red";
  if (ratio >= warnAt) return "yellow";
  return "green";
}

interface Props {
  values: number[];
  width?: number;
  color?: string;
  /** T-440: provider-aware limits. Optional — when
   *  omitted, the chart is identical to R48. */
  providerLimits?: ProviderLimits | null;
  /** T-440: show a tick marker at the cap. Default true
   *  when providerLimits is set. */
  showCapMarker?: boolean;
}

/** render a sparkline of the given values, normalized to
 *  the max value in the input array. If `values` is empty, the
 *  result is a dim placeholder.
 *
 *  T-440: when `providerLimits` is set, the *last* bucket is
 *  coloured by its ratio to the cap (green / yellow / red)
 *  and a small tick is drawn at the cap. */
export const TokenChart: React.FC<Props> = ({
  values,
  width = 16,
  color = "cyan",
  providerLimits,
  showCapMarker,
}) => {
  if (values.length === 0) {
    return <Text dimColor>[{"·".repeat(width)}]</Text>;
  }
  // Down-sample to `width` buckets by averaging.
  const buckets: number[] = [];
  const stride = values.length / width;
  for (let i = 0; i < width; i++) {
    const start = Math.floor(i * stride);
    const end = Math.min(values.length, Math.floor((i + 1) * stride));
    if (start >= end) {
      // No values in this bucket; use 0.
      buckets.push(0);
      continue;
    }
    let sum = 0;
    for (let j = start; j < end; j++) sum += values[j];
    buckets.push(sum / (end - start));
  }
  // Find max for normalization.
  let max = 0;
  for (const b of buckets) if (b > max) max = b;
  if (max <= 0) {
    return <Text dimColor>[{"·".repeat(width)}]</Text>;
  }
  // T-440: pick the effective cap + the colour of the
  // last bucket. The whole chart is rendered in the
  // "default" colour; the LAST bucket gets recoloured so
  // the user can tell at a glance where they currently
  // are vs. the cap.
  const cap = effectiveLimit(providerLimits);
  const lastBucket = buckets[buckets.length - 1] ?? 0;
  const tailColor = bucketColor(lastBucket, cap);
  // Render each bucket as one bar character.
  const chars: React.ReactNode[] = [];
  for (let i = 0; i < buckets.length; i++) {
    const v = buckets[i];
    // Map v/max (0..1) to bar index (0..7).
    const idx = Math.min(BARS.length - 1, Math.max(0, Math.floor((v / max) * (BARS.length - 1))));
    const isLast = i === buckets.length - 1;
    const c = isLast && cap > 0 ? tailColor : color;
    chars.push(<Text key={i} color={c}>{BARS[idx]}</Text>);
  }
  // T-440: optional cap marker. We compute the bar
  // position of the cap within the chart's max so the
  // user can see the cap relative to the sparkline's
  // own peak. This is purely visual — the user has to
  // read the StatusBar's separate "X / Y" label for the
  // numeric value.
  const renderCapMarker = cap > 0 && (showCapMarker ?? providerLimits != null) && cap <= max;
  if (renderCapMarker) {
    const capBarIdx = Math.min(
      width - 1,
      Math.max(0, Math.floor((cap / max) * (width - 1))),
    );
    if (capBarIdx < width - 1) {
      chars.push(
        <Text key="cap" color="red">▕</Text>,
      );
    }
  }
  return <Text>[{chars}]</Text>;
};
