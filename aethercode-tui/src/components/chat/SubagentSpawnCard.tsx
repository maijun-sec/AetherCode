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
 * TUI parity for the desktop's `SubagentSpawnCard`.
 *
 * <p>Inline card surfaced in the chat transcript when an
 * assistant message declares a sub-agent. The card shows the
 * subagent's id + role + status and the task description it was
 * given. A "View subagent →" link switches the active
 * transcript to the subagent (the host wires this to
 * {@code loadSession(jobId)} via the {@code onOpen} prop).
 *
 * <p>R381: the card used to advertise "(Enter expand)" and keep a
 * `open` state that NOTHING ever set — `setOpen` was never called, so
 * the body could not be expanded and the description was never shown.
 * The promise was decorative, which is worse than no promise at all.
 *
 * <p>So the card now shows its body. Enter belongs to the prompt, and
 * a card that swallowed it would break sending a message.
 *
 * <p>This is a port of the desktop's
 * {@code aethercode-desktop/src/components/chat/SubagentSpawnCard.tsx}.
 * The desktop collapses the card because a mouse can expand it; a
 * terminal has no mouse, so the body is always visible here.
 */

import React from "react";
import { Box, Text, useInput } from "ink";
import { hintFor } from "../../componentKeys.js";

export interface SubagentSpawnCardData {
  subagentId: string;
  role: string;
  description: string;
  status: string;
  ts: number;
}

export interface SubagentSpawnCardProps {
  card: SubagentSpawnCardData;
  /** Called when the user clicks "View subagent →" or
   *  presses Enter on the header. The host wires this to
   *  {@code loadSession(subagentId)} + the R214
   *  {@code viewSubagent} action so the chat shows the
   *  subagent's transcript. */
  onOpen?: (subagentId: string) => void;
  /** Optional: dismiss the card (e.g. when the user clears
   *  the chat or hits 'x' on the card). */
  onDismiss?: (subagentId: string) => void;
  /** Width in characters — the host reads the terminal
   *  width and passes half (the left column) to keep the
   *  card from wrapping. */
  width?: number;
}

const STATUS_ICON: Record<string, string> = {
  spawned: "⤵",
  running: "▶",
  ok: "✓",
  failed: "✗",
  cancelled: "⊘",
};

const STATUS_COLOR: Record<string, string> = {
  spawned: "cyan",
  running: "yellow",
  ok: "green",
  failed: "red",
  cancelled: "gray",
};

export function SubagentSpawnCard({ card, onOpen, onDismiss, width = 60 }: SubagentSpawnCardProps) {
  const icon = STATUS_ICON[card.status] ?? "·";
  const color = STATUS_COLOR[card.status] ?? "gray";
  const idShort =
    card.subagentId.length > 12 ? card.subagentId.slice(0, 10) + "…" : card.subagentId;

  useInput((input, key) => {
    if (input === "x" && onDismiss) {
      onDismiss(card.subagentId);
    }
  });

  return (
    <Box
      flexDirection="column"
      borderStyle="round"
      borderColor={color === "gray" ? "gray" : "cyan"}
      width={width}
      paddingX={1}
    >
      <Box>
        <Text color={color}>{icon} </Text>
        <Text bold color={color}>
          {card.role || "subagent"}
        </Text>
        <Text dimColor> {idShort}</Text>
        <Text color={color} dimColor>
          {" "}
          [{card.status}]
        </Text>
      </Box>
      <Box flexDirection="column" marginTop={1}>
        {card.description ? <Text>{card.description}</Text> : null}
        {onOpen ? (
          <Box marginTop={1}>
            <Text color="cyan" underline>
              {`▶ View subagent (${idShort}) →`}
            </Text>
          </Box>
        ) : null}
        {onDismiss ? (
          <Box marginTop={1}>
            <Text dimColor> ({hintFor("components/chat/SubagentSpawnCard.tsx")})</Text>
          </Box>
        ) : null}
      </Box>
    </Box>
  );
}

/** A list of spawn cards, sorted by ts asc. The host renders
 *  this once below the Scrollback so each spawn gets a card
 *  in chat order. */
export interface SubagentSpawnCardsProps {
  cards: Array<{ eventId: string; card: SubagentSpawnCardData }>;
  onOpen?: (subagentId: string) => void;
  onDismiss?: (subagentId: string) => void;
  width?: number;
}

export function SubagentSpawnCards({ cards, onOpen, onDismiss, width }: SubagentSpawnCardsProps) {
  if (cards.length === 0) return null;
  return (
    <Box flexDirection="column">
      {cards.map(({ eventId, card }) => (
        <SubagentSpawnCard
          key={eventId}
          card={card}
          onOpen={onOpen}
          onDismiss={onDismiss}
          width={width}
        />
      ))}
    </Box>
  );
}

export default SubagentSpawnCard;
