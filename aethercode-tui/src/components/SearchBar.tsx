/**
 * search / find bar.
 *
 * Press Ctrl-F to open. A search input appears at the bottom of
 * the scrollback, above the input box. As the user types, the
 * scrollback filters to highlight matching turns (or just
 * scrolls to the first match — see tui.tsx).
 *
 * Press Esc to close. The search is purely client-side; it
 * doesn't talk to the daemon. The user searches the local
 * scrollback (state.turns), not the entire session history.
 *
 * The match logic is a simple case-insensitive substring match
 * over each turn's text + toolName + toolArgs + toolResult +
 * planItems. We re-export the matcher so tests can verify it
 * without a React tree.
 */

import React, { useState, useEffect, useRef } from "react";
import { Box, Text } from "ink";
import TextInput from "ink-text-input";
import type { Turn } from "../state.js";
import { t, icon } from "../theme.js";

/** search turns by a query string. Returns indices of
 *  matching turns in the order they appear in the input array.
 *  Case-insensitive substring match across text + tool fields. */
export function searchTurns(turns: Turn[], query: string | null | undefined): number[] {
  if (!query) return [];
  const q = query.toLowerCase();
  const out: number[] = [];
  for (let i = 0; i < turns.length; i++) {
    const t = turns[i];
    const hay = [
      t.text,
      t.toolName,
      t.toolArgs,
      t.toolResult,
      ...(t.planItems ?? []),
    ].filter(Boolean).join(" ").toLowerCase();
    if (hay.includes(q)) out.push(i);
  }
  return out;
}

interface SearchBarProps {
  turns: Turn[];
  onClose: () => void;
  /** Called when the user presses Enter on a query. Receives the
   *  indices of matching turns. The parent can use this to scroll
   *  to the first match or filter the scrollback. */
  onCommit: (indices: number[]) => void;
  /** Initial query (e.g. when re-opening after a match). */
  initial?: string;
}

export const SearchBar: React.FC<SearchBarProps> = ({ turns, onClose, onCommit, initial = "" }) => {
  const [query, setQuery] = useState(initial);
  const matches = searchTurns(turns, query);
  const lastQuery = useRef(query);

  // Live-update the match count as the user types. We also notify
  // the parent so the scrollback can highlight the matches.
  useEffect(() => {
    if (query !== lastQuery.current) {
      lastQuery.current = query;
      onCommit(matches);
    }
  }, [query, matches.length, onCommit]);

  return (
    <Box
      borderStyle="single"
      borderColor={t.accent}
      paddingX={1}
      flexDirection="row"
      justifyContent="space-between"
    >
      <Text>
        <Text color={t.accent} bold>{icon.search} </Text>
        <TextInput
          value={query}
          onChange={setQuery}
          onSubmit={() => onCommit(matches)}
          placeholder="search scrollback…"
        />
      </Text>
      <Text dimColor>
        {query ? (
          matches.length === 0 ? "no matches  " : `${matches.length} match${matches.length === 1 ? "" : "es"}  `
        ) : "type to search  "}
        Esc: close
      </Text>
    </Box>
  );
};
