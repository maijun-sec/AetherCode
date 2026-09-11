/**
 * TUI parity for the desktop's `SubagentSpawnCard`.
 *
 * <p>Inline card surfaced in the chat transcript when an
 * assistant message declares a sub-agent. The card is collapsed
 * by default and shows the subagent's id + role + status.
 * Pressing {@code Enter} / {@code Space} (or clicking the
 * header) expands the body to show the assigned task
 * description. A "View subagent →" link switches the active
 * transcript to the subagent (the host wires this to
 * {@code loadSession(jobId)} via the {@code onOpen} prop).
 *
 * <p>This is a 1:1 port of the desktop's
 * {@code aethercode-desktop/src/components/chat/SubagentSpawnCard.tsx}.
 * Behaviour kept identical so the two surfaces feel the same
 * (collapse by default, expand via click/Enter/Space, "View
 * subagent →" link drives {@code onOpen}).
 */

import React, { useState } from "react";
import { Box, Text, useInput } from "ink";

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
  const [open, setOpen] = useState(false);
  const icon = STATUS_ICON[card.status] ?? "·";
  const color = STATUS_COLOR[card.status] ?? "gray";
  const idShort = card.subagentId.length > 12
    ? card.subagentId.slice(0, 10) + "…"
    : card.subagentId;

  useInput((input, key) => {
    if (input === "x" && onDismiss) {
      onDismiss(card.subagentId);
    }
  });

  return (
    <Box
      flexDirection="column"
      borderStyle="round"
      borderColor={open ? "cyan" : "gray"}
      width={width}
      paddingX={1}
    >
      <Box>
        <Text color={color}>{icon} </Text>
        <Text bold color={color}>{card.role || "subagent"}</Text>
        <Text dimColor>  {idShort}</Text>
        <Text color={color} dimColor>  [{card.status}]</Text>
        <Text dimColor>  {open ? "▾" : "▸"} (Enter expand)</Text>
      </Box>
      {open ? (
        <Box flexDirection="column" marginTop={1}>
          {card.description ? (
            <Text>{card.description}</Text>
          ) : null}
          {onOpen ? (
            <Box marginTop={1}>
              <Text color="cyan" underline>
                {`▶ View subagent (${idShort}) →`}
              </Text>
            </Box>
          ) : null}
          {onDismiss ? (
            <Box marginTop={1}>
              <Text dimColor>  (press x to dismiss)</Text>
            </Box>
          ) : null}
        </Box>
      ) : null}
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
