/**
 * T-421 (Phase 5 R3): EffortPicker.
 *
 * 1:1 port of deepagents-code's `EffortSelectorScreen` (Java port
 * at `EffortSelectorScreen.java`). Lets the user pick a
 * reasoning-effort level for the active `provider:model` pair.
 *
 * design.md §5.2: the picker lists the supported effort labels
 * (low / medium / high / xhigh, etc., model-dependent) and
 * dispatches `effort/set` on Enter.
 *
 * The component is prop-driven. The host fetches the supported
 * efforts (typically `model/info.efforts` from the daemon) and
 * supplies them as `efforts`. The picker does NOT decide the
 * catalog — that's a per-model concern of the LLM provider.
 */

import React, { useEffect, useMemo, useState } from "react";
import { Box, Text, useInput } from "ink";

/** A single effort level. The label is shown in the picker; the
 *  id is what gets sent to the daemon (so the host can map
 *  "xhigh" → "extra_high" or whatever the wire protocol wants). */
export interface EffortEntry {
  /** Display label (e.g. "low"). */
  label: string;
  /** Optional wire id (defaults to label). */
  id?: string;
  /** Optional one-line description. */
  description?: string;
}

export interface EffortPickerProps {
  /** All effort levels the host supports for the active model. */
  efforts: ReadonlyArray<EffortEntry>;
  /** The currently active effort. Marked with (current). */
  current: string | null;
  /** The model spec the efforts are for (e.g. "claude-opus-4-5"). */
  modelSpec?: string;
  /** Optional default effort. Marked with (default). */
  defaultEffort?: string | null;
  /** Called when the user confirms a pick. */
  onSelect: (id: string) => void;
  /** Called when the user dismisses the picker. */
  onClose: () => void;
  /** Optional title override. */
  title?: string;
  /** Cap visible rows. Default 8. */
  maxVisible?: number;
}

/** Resolve the id used by the daemon. Defaults to `label`. */
function resolveId(e: EffortEntry): string {
  return e.id ?? e.label;
}

export const EffortPicker: React.FC<EffortPickerProps> = ({
  efforts,
  current,
  modelSpec,
  defaultEffort,
  onSelect,
  onClose,
  title = "Select Reasoning Effort",
  maxVisible = 8,
}) => {
  // Resolve the *id* of the current effort so we can match it
  // even when host supplied `id: "extra_high"` but `label: "xhigh"`.
  const currentId = useMemo(() => {
    if (!current) return null;
    const hit = efforts.find((e) => resolveId(e) === current || e.label === current);
    return hit ? resolveId(hit) : current;
  }, [efforts, current]);

  // Highlight starts on the current effort if present, else 0.
  const initial = useMemo(() => {
    if (!currentId) return 0;
    const idx = efforts.findIndex((e) => resolveId(e) === currentId);
    return idx >= 0 ? idx : 0;
  }, [efforts, currentId]);
  const [highlight, setHighlight] = useState(initial);

  // Clamp highlight when the catalog shrinks.
  useEffect(() => {
    if (highlight >= efforts.length) {
      setHighlight(Math.max(0, efforts.length - 1));
    }
  }, [efforts.length, highlight]);

  useInput((input, key) => {
    if (key.escape) {
      onClose();
      return;
    }
    if (key.return) {
      const e = efforts[highlight];
      if (e) onSelect(resolveId(e));
      onClose();
      return;
    }
    if (key.upArrow) {
      setHighlight((h) => (h - 1 + efforts.length) % Math.max(1, efforts.length));
      return;
    }
    if (key.downArrow) {
      setHighlight((h) => (h + 1) % Math.max(1, efforts.length));
      return;
    }
    if (input === "j") {
      setHighlight((h) => (h + 1) % Math.max(1, efforts.length));
      return;
    }
    if (input === "k") {
      setHighlight((h) => (h - 1 + efforts.length) % Math.max(1, efforts.length));
      return;
    }
  });

  // Scroll window.
  const start = Math.max(0, Math.min(highlight - Math.floor(maxVisible / 2), Math.max(0, efforts.length - maxVisible)));
  const end = Math.min(efforts.length, start + maxVisible);
  const visible = efforts.slice(start, end);

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
        <Text dimColor>  ↑/↓ (j/k): navigate  Enter: select  Esc: close</Text>
      </Box>
      {modelSpec ? (
        <Text dimColor>  for {modelSpec}</Text>
      ) : null}
      <Text> </Text>
      {efforts.length === 0 ? (
        <Text dimColor>  no effort levels available for this model</Text>
      ) : (
        visible.map((e, i) => {
          const realIndex = start + i;
          const isHighlighted = realIndex === highlight;
          const id = resolveId(e);
          const isCurrent = id === currentId;
          const isDefault = defaultEffort != null && id === defaultEffort;
          return (
            <Box key={id} flexDirection="row">
              <Text color={isHighlighted ? "cyan" : undefined}>
                {isHighlighted ? "▶" : " "}{" "}
              </Text>
              <Text color={isCurrent ? "yellowBright" : undefined}>
                {isCurrent ? "●" : " "}{" "}
              </Text>
              <Text>
                <Text bold={isHighlighted}>{e.label.padEnd(12)}</Text>
                {isDefault ? <Text color="magenta"> (default)</Text> : null}
                {isCurrent ? <Text color="yellowBright"> (current)</Text> : null}
                {e.description ? <Text dimColor>  — {e.description}</Text> : null}
              </Text>
            </Box>
          );
        })
      )}
      {efforts.length > maxVisible ? (
        <Text dimColor>  {start + 1}–{end} of {efforts.length}</Text>
      ) : null}
    </Box>
  );
};

/** Pure helper: format the row label for an effort. Mirrors
 *  `EffortSelectorScreen.renderOption` in deepagents-code. */
export function formatEffortLabel(
  effort: string,
  current: string | null,
  defaultEffort: string | null,
): string {
  const isCurrent = effort === current;
  const isDefault = effort === defaultEffort;
  const markers: string[] = [];
  if (isCurrent) markers.push("current");
  if (isDefault) markers.push("default");
  if (markers.length === 0) return effort;
  return `${effort} (${markers.join(", ")})`;
}
