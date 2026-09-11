/**
 * T-6-05: `SessionDetailsPanel` (TUI right column).
 *
 * Renders the same fields as the desktop's
 * `SessionDetailsDrawer` (spec §3.1), in plain text:
 *
 *   - session id (truncated, copyable via a follow-up
 *     key binding; in this round we surface it as
 *     text and let the host mount a "Copy id" action)
 *   - title
 *   - cwd
 *   - model + effort
 *   - started_at / last_active_at (relative)
 *   - parent session (if any)
 *   - token totals (in / out / total cost)
 *   - TODO summary (counts by status)
 *
 * The panel is "headless" about transport: it accepts
 * the full SessionDetail + an optional "extra" prop
 * (parent info, TODO summary). The TUI passes a
 * `useSessionDetail` snapshot; tests pass a literal.
 *
 * Design notes:
 *   - The panel is a vertical Box with a thin separator
 *     between sections (no box border, matches the
 *     StatusBar's R167 aesthetic).
 *   - Empty fields render as "—" so the user always
 *     sees the layout, even before the data loads.
 *   - The "stale" badge is rendered when
 *     `lastActiveAt` is older than 30 minutes
 *     (spec §2.1 / StaleWarning).
 *   - The TODO summary is a small line — the full
 *     TodoBoard lives in the right column too
 *     (Phase 6.2 / T-6-07).
 */

import React from "react";
import { Box, Text } from "ink";
import { t, formatRelative, formatTokens, formatCost } from "../../theme.js";

/** A single TODO summary (counts per status). The full
 *  TODO list is rendered by `TodoBoard`; this is just
 *  the at-a-glance counter. */
export interface TodoSummary {
  total: number;
  pending: number;
  inProgress: number;
  completed: number;
  cancelled: number;
}

/** The data shape the panel needs. Mirrors
 *  `useSessionDetail`'s return type but with a stricter
 *  `id` (so we can render the truncated form). */
export interface SessionDetails {
  id: string;
  title: string;
  cwd: string;
  model: string;
  effort?: "low" | "medium" | "high" | "max" | string;
  startedAt: number;
  lastActiveAt: number;
  state: string;
  tokensIn: number;
  tokensOut: number;
  messageCount: number;
  preview?: string;
  parentSessionId?: string | null;
  /** Optional human-readable note (e.g. "spawned by
   *  long-running task t-12"). */
  parentNote?: string | null;
}

export interface SessionDetailsPanelProps {
  details: SessionDetails | null;
  todoSummary?: TodoSummary | null;
  /** Wall-clock for relative time formatting. Tests
   *  inject a fixed value to assert "5h ago" output. */
  now?: number;
  /** Toggle the parent-id link / copy id affordance. */
  onCopyId?: (id: string) => void;
}

/** Truncate a session id to the first 8 hex chars. */
export function shortId(id: string): string {
  if (!id) return "—";
  return id.length <= 12 ? id : id.slice(0, 8);
}

/** Format a relative timestamp ("3h ago"). Re-exported
 *  here so consumers don't have to dig into theme.js. */
export function formatTimeAgo(ts: number, now: number = Date.now()): string {
  if (!Number.isFinite(ts) || ts <= 0) return "—";
  return `${formatRelative(now - ts, 0)} ago`;
}

/** True if `lastActiveAt` is older than 30 minutes. The
 *  panel surfaces a "stale" badge so the user knows the
 *  re-attach flow will pop up on click. */
export function isStale(lastActiveAt: number, now: number = Date.now()): boolean {
  if (!Number.isFinite(lastActiveAt) || lastActiveAt <= 0) return false;
  return now - lastActiveAt > 30 * 60 * 1000;
}

/** Compose the "stale" badge. Pure helper. */
export function staleLabel(lastActiveAt: number, now: number = Date.now()): string {
  if (!isStale(lastActiveAt, now)) return "";
  return `stale (${formatRelative(now - lastActiveAt, 0)})`;
}

/** Compose the TODO summary line. Pure helper. */
export function todoSummaryLine(summary: TodoSummary | null | undefined): string {
  if (!summary || summary.total === 0) return "—";
  const parts: string[] = [];
  if (summary.completed > 0) parts.push(`${summary.completed} done`);
  if (summary.inProgress > 0) parts.push(`${summary.inProgress} in progress`);
  if (summary.pending > 0) parts.push(`${summary.pending} pending`);
  if (summary.cancelled > 0) parts.push(`${summary.cancelled} cancelled`);
  return parts.join(" · ");
}

export const STALE_THRESHOLD_MS = 30 * 60 * 1000;

export const SessionDetailsPanel: React.FC<SessionDetailsPanelProps> = ({
  details,
  todoSummary,
  now = Date.now(),
  onCopyId,
}) => {
  if (!details) {
    return (
      <Box flexDirection="column" paddingX={1}>
        <Text color={t.brand} bold>session</Text>
        <Text dimColor>{"─".repeat(20)}</Text>
        <Text dimColor>  (no session loaded)</Text>
      </Box>
    );
  }
  const stale = isStale(details.lastActiveAt, now);
  const tokensTotal = details.tokensIn + details.tokensOut;
  // Cost is approximate; the desktop uses the model's
  // per-M pricing. The TUI doesn't have the model
  // registry in this round, so we surface a $0.00
  // placeholder; a future T-7 update will wire the
  // pricing.
  return (
    <Box flexDirection="column" paddingX={1}>
      <Box flexDirection="row" justifyContent="space-between">
        <Text color={t.brand} bold>session</Text>
        {stale ? <Text color={t.warn}>· stale</Text> : null}
      </Box>
      <Text dimColor>{"─".repeat(20)}</Text>

      <Row label="id"        value={shortId(details.id)} onClick={onCopyId ? () => onCopyId(details.id) : undefined} />
      <Row label="title"     value={details.title || "—"} />
      <Row label="cwd"       value={details.cwd || "—"} />
      <Row label="model"     value={details.model || "—"} />
      {details.effort ? <Row label="effort" value={details.effort} /> : null}
      <Row label="state"     value={details.state} />
      <Row label="started"   value={formatTimeAgo(details.startedAt, now)} />
      <Row label="active"    value={formatTimeAgo(details.lastActiveAt, now)} />
      {stale ? <Row label="stale" value={staleLabel(details.lastActiveAt, now)} color={t.warn} /> : null}
      {details.parentSessionId ? (
        <Row label="parent" value={shortId(details.parentSessionId) + (details.parentNote ? ` (${details.parentNote})` : "")} />
      ) : null}
      <Text dimColor>{"─".repeat(20)}</Text>

      <Text color={t.accent}>tokens</Text>
      <Text>
        <Text dimColor>  in  </Text>
        <Text>{formatTokens(details.tokensIn)}</Text>
        <Text dimColor>  ·  out  </Text>
        <Text>{formatTokens(details.tokensOut)}</Text>
        <Text dimColor>  ·  total  </Text>
        <Text>{formatTokens(tokensTotal)}</Text>
      </Text>
      <Text>
        <Text dimColor>  cost  </Text>
        <Text>{formatCost(null)}</Text>
        <Text dimColor>  ·  msgs  </Text>
        <Text>{details.messageCount}</Text>
      </Text>

      <Text dimColor>{"─".repeat(20)}</Text>
      <Text color={t.accent}>todos</Text>
      <Text>
        <Text dimColor>  </Text>
        <Text>{todoSummaryLine(todoSummary)}</Text>
      </Text>

      {details.preview ? (
        <>
          <Text dimColor>{"─".repeat(20)}</Text>
          <Text color={t.accent}>preview</Text>
          <Text>  {details.preview}</Text>
        </>
      ) : null}
    </Box>
  );
};

const Row: React.FC<{ label: string; value: string; color?: string; onClick?: () => void }> = ({ label, value, color, onClick }) => (
  <Box flexDirection="row">
    <Text dimColor>{label.padEnd(9, " ")} </Text>
    {onClick ? (
      <Text color="cyan" underline>{value}</Text>
    ) : (
      <Text color={color}>{value}</Text>
    )}
  </Box>
);

export default SessionDetailsPanel;
