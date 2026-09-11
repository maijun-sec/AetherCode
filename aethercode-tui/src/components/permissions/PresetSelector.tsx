/**
 * T-6-11 / spec.md §9 / design.md §5.1: the TUI's
 * {@code PresetSelector}.
 *
 * <p>Three preset cards, side by side:
 * <ul>
 *   <li><b>permissive</b> — default for new users; allow
 *       read-only tools, prompt for everything else.</li>
 *   <li><b>cautious</b> — recommended; prompt for everything
 *       except read_file and glob_files.</li>
 *   <li><b>strict</b> — power-user default; deny bash and
 *       write_file until explicitly allowed.</li>
 * </ul>
 *
 * <p>Each card shows: name, 1-line description, the list of
 * tool categories the preset touches, and an "Apply" button
 * (or a "current" badge when this preset is already active).
 * Picking a card fires {@code onApply(preset)}.
 *
 * <p>Pure-helper exports:
 * <ul>
 *   <li>{@link PRESETS} — the catalog of presets (so the
 *       GrantsManager can render the same data without
 *       duplicating it).</li>
 *   <li>{@link formatPresetLine} — single-line summary
 *       (used by the GrantsManager's right column when in
 *       compact mode).</li>
 *   <li>{@link isValidPreset} — validator for the
 *       `grants/setPreset` parameter.</li>
 * </ul>
 */

import React from "react";
import { Box, Text, useInput } from "ink";
import { t } from "../../theme.js";

// --------------------------------------------------------------------
//  Types
// --------------------------------------------------------------------

export type PresetId = "permissive" | "cautious" | "strict";

export interface PresetDefinition {
  id: PresetId;
  name: string;
  description: string;
  /** Short list of tool categories the preset covers. */
  toolCategories: string[];
  /** Single-line description used by {@link formatPresetLine}. */
  summary: string;
}

/** The shipped catalog. The desktop uses the same shape. */
export const PRESETS: ReadonlyArray<PresetDefinition> = [
  {
    id: "permissive",
    name: "permissive",
    description: "Allow read-only tools; prompt for everything else.",
    toolCategories: ["fs.read", "fs.glob", "search.*"],
    summary: "allow read-only, prompt for the rest",
  },
  {
    id: "cautious",
    name: "cautious",
    description: "Prompt for everything except read_file and glob_files.",
    toolCategories: ["fs.read", "fs.glob"],
    summary: "prompt for everything (except read/glob)",
  },
  {
    id: "strict",
    name: "strict",
    description: "Deny bash and write_file until explicitly allowed.",
    toolCategories: ["shell.command.*", "fs.write"],
    summary: "deny bash + write_file by default",
  },
];

/** Validator: returns true if the input is a known preset id. */
export function isValidPreset(value: string): value is PresetId {
  return value === "permissive" || value === "cautious" || value === "strict";
}

/** Single-line summary used in compact layouts. */
export function formatPresetLine(p: PresetDefinition): string {
  return `${p.name} — ${p.summary}`;
}

// --------------------------------------------------------------------
//  Component
// --------------------------------------------------------------------

export interface PresetSelectorProps {
  /** The currently-active preset, if any. Drives the
   *  "(current)" badge. */
  active?: PresetId | null;
  /** Fired when the user picks a preset and presses
   *  Enter / clicks Apply. */
  onApply?: (preset: PresetId) => void;
  /** When true, render in a compact single-column form
   *  (used by {@link GrantsManager}). Default: false
   *  (3-card side-by-side layout). */
  compact?: boolean;
  /** Initial highlighted card. Default: 0. */
  initialIndex?: number;
}

const PRESET_COLOR: Record<PresetId, string> = {
  permissive: t.ok,
  cautious: t.warn,
  strict: t.err,
};

export const PresetSelector: React.FC<PresetSelectorProps> = ({
  active = null,
  onApply,
  compact = false,
  initialIndex = 0,
}) => {
  const [highlight, setHighlight] = React.useState(() => {
    // If a preset is already active, default the highlight to
    // that card so the user sees the relationship.
    if (active) {
      const idx = PRESETS.findIndex((p) => p.id === active);
      if (idx >= 0) return idx;
    }
    return initialIndex;
  });

  useInput((input, key) => {
    if (key.return) {
      const p = PRESETS[highlight];
      if (p && onApply) onApply(p.id);
      return;
    }
    if (key.leftArrow || input === "h") {
      setHighlight((h) => (h - 1 + PRESETS.length) % PRESETS.length);
      return;
    }
    if (key.rightArrow || input === "l") {
      setHighlight((h) => (h + 1) % PRESETS.length);
      return;
    }
    if (input === "1" || input === "2" || input === "3") {
      const idx = Number(input) - 1;
      const p = PRESETS[idx];
      if (p) {
        setHighlight(idx);
        if (onApply) onApply(p.id);
      }
      return;
    }
  });

  if (compact) {
    return (
      <Box flexDirection="column" marginTop={1}>
        <Text bold>preset</Text>
        {PRESETS.map((p, i) => {
          const isHi = i === highlight;
          const isActive = p.id === active;
          return (
            <Box key={p.id} flexDirection="column">
              <Text
                color={isHi ? "cyan" : undefined}
                bold={isHi || isActive}
              >
                {isHi ? "▶ " : "  "}
                {isActive ? "● " : "○ "}
                <Text color={PRESET_COLOR[p.id]} bold>
                  {p.name}
                </Text>
                {isActive ? (
                  <Text dimColor>  (current)</Text>
                ) : null}
              </Text>
              <Text dimColor>{"    "}{p.summary}</Text>
            </Box>
          );
        })}
        {onApply ? (
          <Text dimColor>
            Enter to apply · 1/2/3 quick
          </Text>
        ) : null}
      </Box>
    );
  }

  return (
    <Box
      flexDirection="column"
      borderStyle="round"
      borderColor={t.dim}
      paddingX={1}
    >
      <Text bold>Permission Presets</Text>
      <Text dimColor>Pick a starting point; you can change grants individually after.</Text>
      <Text> </Text>
      <Box flexDirection="row">
        {PRESETS.map((p, i) => {
          const isHi = i === highlight;
          const isActive = p.id === active;
          const color = PRESET_COLOR[p.id];
          return (
            <Box
              key={p.id}
              flexDirection="column"
              borderStyle={isHi ? "double" : "single"}
              borderColor={isHi ? color : t.dim}
              paddingX={1}
              marginRight={1}
              width={28}
            >
              <Box>
                <Text color={color} bold>{p.name}</Text>
                {isActive ? (
                  <Text dimColor>  (current)</Text>
                ) : null}
              </Box>
              <Text> </Text>
              <Text>{p.description}</Text>
              <Text> </Text>
              <Text dimColor>covers:</Text>
              {p.toolCategories.map((c) => (
                <Text key={c} dimColor>  · {c}</Text>
              ))}
              <Text> </Text>
              {onApply ? (
                isActive ? (
                  <Text dimColor>[ applied ]</Text>
                ) : (
                  <Text>
                    <Text color={isHi ? color : undefined} bold>
                      {isHi ? "[ Enter / 1-3 to apply ]" : "[ 1-3 to apply ]"}
                    </Text>
                  </Text>
                )
              ) : null}
            </Box>
          );
        })}
      </Box>
      <Text> </Text>
      <Text dimColor>
        ←/→ navigate · Enter apply · 1/2/3 quick-select
      </Text>
    </Box>
  );
};

export default PresetSelector;
