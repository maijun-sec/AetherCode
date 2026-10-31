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
 * command palette.
 *
 * A modal that opens with Ctrl-P. The user types a fragment of
 * a slash command name and the palette shows fuzzy matches in
 * rank order. Selecting a match (Enter) inserts the command into
 * the input box. Esc closes.
 *
 * This is the "discovery" surface for slash commands — instead
 * of having to remember `/mode` vs `/model`, the user can type
 * "mo" and see both ranked by relevance.
 *
 * The match algorithm is a simple subsequence + prefix hybrid:
 *   - Commands that start with the query are top-ranked.
 *   - Then commands that contain the query as a substring.
 *   - Then commands where every char of the query appears in
 *     order (subsequence) somewhere in the command.
 * Each match gets a score; we sort by score desc and return the
 * top N.
 */

import React, { useState, useEffect } from "react";
import { Box, Text } from "ink";
import TextInput from "ink-text-input";
import { hintFor } from "../componentKeys.js";
import { SLASH_COMMANDS, describeCommand } from "../commands.js";
import { t, icon } from "../theme.js";

export interface PaletteEntry {
  name: string;
  score: number;
  /** The character indices in `name` that the query matched. */
  highlights: number[];
}

/** rank slash commands by query relevance. Returns matches
 *  sorted by score desc, capped at `limit`. The highlight
 *  indices are the positions in `name` that the query matched
 *  (for the caller to render the matched chars in bold). */
export function rankCommands(query: string, limit = 8): PaletteEntry[] {
  if (!query) return [];
  const q = query.toLowerCase();
  const out: PaletteEntry[] = [];
  for (const name of SLASH_COMMANDS) {
    const lower = name.toLowerCase();
    let score = 0;
    const highlights: number[] = [];
    if (lower.startsWith(q)) {
      // Prefix match is the best signal.
      score = 1000 - (lower.length - q.length);
      for (let i = 0; i < q.length; i++) highlights.push(i);
    } else if (lower.includes(q)) {
      // Substring match is OK.
      const idx = lower.indexOf(q);
      score = 500 - idx;
      for (let i = 0; i < q.length; i++) highlights.push(idx + i);
    } else if (isSubsequence(q, lower)) {
      // Subsequence match is weak but better than nothing.
      score = 100;
      // Find the indices that matched.
      let qi = 0;
      for (let i = 0; i < lower.length && qi < q.length; i++) {
        if (lower[i] === q[qi]) {
          highlights.push(i);
          qi++;
        }
      }
    }
    if (score > 0) out.push({ name, score, highlights });
  }
  out.sort((a, b) => b.score - a.score);
  return out.slice(0, limit);
}

function isSubsequence(needle: string, haystack: string): boolean {
  let i = 0;
  for (const c of haystack) {
    if (c === needle[i]) i++;
    if (i === needle.length) return true;
  }
  return i === needle.length;
}

interface CommandPaletteProps {
  onClose: () => void;
  onSelect: (command: string) => void;
}

export const CommandPalette: React.FC<CommandPaletteProps> = ({ onClose, onSelect }) => {
  const [query, setQuery] = useState("");
  const [highlight, setHighlight] = useState(0);
  const matches = rankCommands(query);

  // Clamp highlight to valid range when matches change.
  useEffect(() => {
    if (highlight >= matches.length) setHighlight(0);
  }, [matches.length, highlight]);

  return (
    <Box
      flexDirection="column"
      borderStyle="double"
      borderColor={t.accent}
      paddingX={2}
      paddingY={1}
    >
      <Box>
        <Text color={t.accent} bold>
          {icon.search} command palette
        </Text>
        <Text dimColor> {hintFor("components/CommandPalette.tsx")}</Text>
      </Box>
      <Text> </Text>
      <Text>
        <Text color={t.warn}>{">"}</Text>
        <Text> </Text>
        <TextInput
          value={query}
          onChange={(v) => {
            setQuery(v);
            setHighlight(0);
          }}
          onSubmit={() => {
            const m = matches[highlight] ?? matches[0];
            if (m) onSelect("/" + m.name);
            onClose();
          }}
          placeholder="type a fragment (e.g. mo, he, ex)…"
        />
      </Text>
      <Text> </Text>
      {matches.length === 0 ? (
        <Text dimColor> no matches</Text>
      ) : (
        matches.map((m, i) => (
          <Box key={m.name} flexDirection="row">
            <Text color={i === highlight ? t.accent : t.dim}>{i === highlight ? "▶" : " "} </Text>
            <Text>
              /<Text bold={i === highlight}>{renderHighlighted(m.name, m.highlights)}</Text>
            </Text>
            <Text dimColor> </Text>
            <Text color={i === highlight ? t.accent : t.dim}>{describeCommand(m.name)}</Text>
          </Box>
        ))
      )}
    </Box>
  );
};

function renderHighlighted(name: string, indices: number[]): React.ReactNode {
  const set = new Set(indices);
  return name.split("").map((c, i) =>
    set.has(i) ? (
      <Text key={i} color="yellowBright">
        {c}
      </Text>
    ) : (
      <Text key={i}>{c}</Text>
    ),
  );
}
