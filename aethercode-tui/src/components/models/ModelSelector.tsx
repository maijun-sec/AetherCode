/**
 * T-6-12 / spec.md §10 / design.md §5.1: the TUI's
 * {@code ModelSelector}.
 *
 * <p>Modal picker, list grouped by provider. Each model shows:
 * <ul>
 *   <li>name (with tier marker — e.g. "haiku" / "sonnet" /
 *       "opus" / "gpt-5")</li>
 *   <li>provider (e.g. "anthropic", "openai")</li>
 *   <li>contextWindow (formatted: "200k")</li>
 *   <li>maxOutput (formatted: "8k")</li>
 *   <li>pricing ($/M input, $/M output, $/M cached)</li>
 *   <li>capability badges (vision, tools, json-mode,
 *       reasoning)</li>
 * </ul>
 *
 * <p>An optional "last used" badge marks the most-recently
 * selected model. Picking a model fires {@code onSelect(model)}
 * (the host wires it to {@code model/set} RPC).
 *
 * <p>Pure-helper exports (testable without React):
 * <ul>
 *   <li>{@link MODELS} — a small built-in catalog. The host
 *       typically replaces this with the result of
 *       {@code model/list}, but the default is useful for
 *       tests and for the first-run UX.</li>
 *   <li>{@link groupModelsByProvider} — bucketed grouping for
 *       the list.</li>
 *   <li>{@link formatPrice} — renders {@code $/M} values.</li>
 *   <li>{@link formatContext} — renders contextWindow
 *       values.</li>
 *   <li>{@link deriveTier} — pulls the "tier" out of a model
 *       name ("claude-haiku-4-5" → "haiku").</li>
 * </ul>
 */

import React, { useState, useMemo, useEffect } from "react";
import { Box, Text, useInput } from "ink";
import { t } from "../../theme.js";

// --------------------------------------------------------------------
//  Types
// --------------------------------------------------------------------

export interface ModelCapabilities {
  vision?: boolean;
  tools?: boolean;
  jsonMode?: boolean;
  reasoning?: boolean;
  /** Free-form: a "fast" / "balanced" / "deep" label. */
  tier?: string;
}

export interface ModelProfile {
  name: string;
  provider: string;
  contextWindow: number;
  maxOutput: number;
  /** USD per million input tokens. */
  priceIn: number;
  /** USD per million output tokens. */
  priceOut: number;
  /** USD per million cached input tokens. 0 if N/A. */
  priceCached: number;
  capabilities: ModelCapabilities;
}

export interface ModelSelectorProps {
  /** The model catalog. Defaults to {@link MODELS} when not
   *  provided. */
  models?: ModelProfile[];
  /** The currently-active model name (drawn from
   *  {@code model/getCurrent} on the host). */
  current?: string | null;
  /** The most-recently used model name. Receives a "last
   *  used" badge. */
  lastUsed?: string | null;
  /** Fired when the user picks a model (Enter). */
  onSelect?: (model: ModelProfile) => void;
  /** Fired when the user presses Esc. */
  onCancel?: () => void;
  /** Optional: filter by provider at mount (substring). */
  initialProviderFilter?: string;
}

// --------------------------------------------------------------------
//  Built-in catalog (small; the host usually overrides)
// --------------------------------------------------------------------

export const MODELS: ReadonlyArray<ModelProfile> = [
  {
    name: "claude-opus-4-1",
    provider: "anthropic",
    contextWindow: 200_000,
    maxOutput: 8_192,
    priceIn: 15,
    priceOut: 75,
    priceCached: 1.5,
    capabilities: { vision: true, tools: true, jsonMode: true, reasoning: true, tier: "opus" },
  },
  {
    name: "claude-sonnet-4-5",
    provider: "anthropic",
    contextWindow: 200_000,
    maxOutput: 8_192,
    priceIn: 3,
    priceOut: 15,
    priceCached: 0.3,
    capabilities: { vision: true, tools: true, jsonMode: true, reasoning: true, tier: "sonnet" },
  },
  {
    name: "claude-haiku-4-5",
    provider: "anthropic",
    contextWindow: 200_000,
    maxOutput: 8_192,
    priceIn: 0.8,
    priceOut: 4,
    priceCached: 0.08,
    capabilities: { vision: true, tools: true, jsonMode: true, reasoning: true, tier: "haiku" },
  },
  {
    name: "gpt-5",
    provider: "openai",
    contextWindow: 256_000,
    maxOutput: 16_384,
    priceIn: 5,
    priceOut: 20,
    priceCached: 0.5,
    capabilities: { vision: true, tools: true, jsonMode: true, reasoning: true, tier: "deep" },
  },
  {
    name: "gpt-5-mini",
    provider: "openai",
    contextWindow: 256_000,
    maxOutput: 16_384,
    priceIn: 0.5,
    priceOut: 2,
    priceCached: 0.05,
    capabilities: { vision: true, tools: true, jsonMode: true, reasoning: true, tier: "fast" },
  },
  {
    name: "gemini-2-5-pro",
    provider: "google",
    contextWindow: 1_000_000,
    maxOutput: 8_192,
    priceIn: 1.25,
    priceOut: 5,
    priceCached: 0.31,
    capabilities: { vision: true, tools: true, jsonMode: true, reasoning: true, tier: "deep" },
  },
];

// --------------------------------------------------------------------
//  Pure helpers
// --------------------------------------------------------------------

/** Group a list of models by provider (sorted). */
export function groupModelsByProvider(
  models: ModelProfile[],
): Array<{ provider: string; items: ModelProfile[] }> {
  const map = new Map<string, ModelProfile[]>();
  for (const m of models) {
    const arr = map.get(m.provider) ?? [];
    arr.push(m);
    map.set(m.provider, arr);
  }
  const out: Array<{ provider: string; items: ModelProfile[] }> = [];
  for (const [provider, items] of map) {
    items.sort((a, b) => a.name.localeCompare(b.name));
    out.push({ provider, items });
  }
  out.sort((a, b) => a.provider.localeCompare(b.provider));
  return out;
}

/** Render a $/M value. 0 → "—". < 1 → "$0.50" style. */
export function formatPrice(usd: number): string {
  if (!Number.isFinite(usd) || usd <= 0) return "—";
  if (usd < 1) return `$${usd.toFixed(2)}`;
  if (usd < 10) return `$${usd.toFixed(1)}`;
  return `$${usd.toFixed(0)}`;
}

/** Render a contextWindow value. 200_000 → "200k". */
export function formatContext(tokens: number): string {
  if (!Number.isFinite(tokens) || tokens <= 0) return "—";
  if (tokens < 1000) return String(tokens);
  if (tokens < 1_000_000) {
    const k = tokens / 1000;
    return k % 1 === 0 ? `${k}k` : `${k.toFixed(0)}k`;
  }
  return `${(tokens / 1_000_000).toFixed(1)}M`;
}

/** Render a maxOutput value. */
export function formatMaxOutput(tokens: number): string {
  if (!Number.isFinite(tokens) || tokens <= 0) return "—";
  if (tokens < 1000) return String(tokens);
  if (tokens < 1_000_000) {
    const k = tokens / 1000;
    return k % 1 === 0 ? `${k}k` : `${k.toFixed(0)}k`;
  }
  return `${(tokens / 1_000_000).toFixed(1)}M`;
}

/** Pull the "tier" out of a model name.
 *  "claude-haiku-4-5" → "haiku"; "gpt-5" → "5"; "opus-4-1" → "opus".
 *  Falls back to the name's tail segment after the first dash. */
export function deriveTier(name: string): string {
  if (!name) return "?";
  const knownTiers = ["haiku", "sonnet", "opus", "mini", "nano", "pro", "deep", "fast", "balanced"];
  const lower = name.toLowerCase();
  for (const t of knownTiers) {
    if (lower.includes(t)) return t;
  }
  const parts = name.split(/[-_]/);
  return parts[1] ?? parts[0] ?? name;
}

// --------------------------------------------------------------------
//  Component
// --------------------------------------------------------------------

const PROVIDER_COLOR: Record<string, string> = {
  anthropic: "yellowBright",
  openai: "green",
  google: "blue",
};

function CapabilityBadges({ caps }: { caps: ModelCapabilities }) {
  const items: string[] = [];
  if (caps.vision) items.push("vision");
  if (caps.tools) items.push("tools");
  if (caps.jsonMode) items.push("json");
  if (caps.reasoning) items.push("reasoning");
  if (items.length === 0) return null;
  return (
    <Text dimColor>
      {items.map((it, i) => (
        <Text key={it}>
          {i > 0 ? " " : "["}
          {it}
          {i === items.length - 1 ? "]" : ""}
        </Text>
      ))}
    </Text>
  );
}

export const ModelSelector: React.FC<ModelSelectorProps> = ({
  models,
  current = null,
  lastUsed = null,
  onSelect,
  onCancel,
  initialProviderFilter = "",
}) => {
  const list = models ?? MODELS;
  const [highlight, setHighlight] = useState(0);
  const [providerFilter, setProviderFilter] = useState<string>(initialProviderFilter);

  // Apply provider filter.
  const filtered = useMemo(() => {
    if (!providerFilter) return Array.from(list);
    const f = providerFilter.toLowerCase();
    return list.filter((m) => m.provider.toLowerCase().includes(f));
  }, [list, providerFilter]);

  const grouped = useMemo(() => groupModelsByProvider(filtered), [filtered]);

  // Flatten the grouped list for highlight navigation.
  const flat: ModelProfile[] = useMemo(() => {
    const out: ModelProfile[] = [];
    for (const g of grouped) for (const m of g.items) out.push(m);
    return out;
  }, [grouped]);

  // Clamp highlight if filter changes.
  useEffect(() => {
    if (highlight >= flat.length) {
      setHighlight(Math.max(0, flat.length - 1));
    }
  }, [flat.length, highlight]);

  useInput((input, key) => {
    if (key.escape) {
      if (onCancel) onCancel();
      return;
    }
    if (key.return) {
      const m = flat[highlight];
      if (m && onSelect) onSelect(m);
      return;
    }
    if (key.upArrow || input === "k") {
      setHighlight((h) => (h - 1 + flat.length) % flat.length);
      return;
    }
    if (key.downArrow || input === "j") {
      setHighlight((h) => (h + 1) % flat.length);
      return;
    }
  });

  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor={t.brand}
      paddingX={2}
      paddingY={1}
    >
      <Text>
        <Text color={t.brand} bold>
          ⌬ Model
        </Text>
        <Text dimColor>  ·  {list.length} models available</Text>
      </Text>
      <Text> </Text>
      <Box flexDirection="row">
        <Text dimColor>filter: </Text>
        <Text color="cyan">{providerFilter || "(all)"}</Text>
      </Box>
      <Text> </Text>

      {grouped.map((g) => {
        const color = PROVIDER_COLOR[g.provider] ?? t.dim;
        return (
          <Box key={g.provider} flexDirection="column" marginTop={1}>
            <Text>
              <Text color={color} bold>▼ {g.provider}</Text>
            </Text>
            {g.items.map((m) => {
              // The flat list is in the same order as the grouped
              // output, so the index matches.
              const idx = flat.indexOf(m);
              const isHi = idx === highlight;
              const isCurrent = m.name === current;
              const isLast = m.name === lastUsed;
              const tier = m.capabilities.tier ?? deriveTier(m.name);
              return (
                <Box key={m.name} flexDirection="row">
                  <Text color={isHi ? "cyan" : undefined}>
                    {isHi ? "▶" : " "}{" "}
                  </Text>
                  <Text color={isHi ? "white" : undefined} bold={isHi}>
                    {m.name.padEnd(20)}
                  </Text>
                  <Text dimColor>  ({tier})</Text>
                  {isCurrent ? (
                    <Text color={t.ok} bold>  [current]</Text>
                  ) : isLast ? (
                    <Text dimColor>  [last used]</Text>
                  ) : null}
                </Box>
              );
            })}
            {/* Detail row for the highlighted model in this group. */}
            {(() => {
              const hiModel = flat[highlight];
              if (!hiModel || hiModel.provider !== g.provider) return null;
              return (
                <Box flexDirection="column" marginLeft={4} marginTop={1}>
                  <Text>
                    <Text dimColor>ctx </Text>
                    <Text>{formatContext(hiModel.contextWindow)}</Text>
                    <Text dimColor>  ·  out </Text>
                    <Text>{formatMaxOutput(hiModel.maxOutput)}</Text>
                    <Text dimColor>  ·  $/M in </Text>
                    <Text>{formatPrice(hiModel.priceIn)}</Text>
                    <Text dimColor>  out </Text>
                    <Text>{formatPrice(hiModel.priceOut)}</Text>
                    <Text dimColor>  cached </Text>
                    <Text>{formatPrice(hiModel.priceCached)}</Text>
                  </Text>
                  <CapabilityBadges caps={hiModel.capabilities} />
                </Box>
              );
            })()}
          </Box>
        );
      })}

      <Text> </Text>
      <Text dimColor>
        ↑/↓ navigate · Enter select · Esc cancel
      </Text>
    </Box>
  );
};

export default ModelSelector;
