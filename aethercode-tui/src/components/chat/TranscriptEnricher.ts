/**
 * T-7-03: `TranscriptEnricher` (TUI auto-summary middleware).
 *
 * Per spec §12.3, every assistant turn ends with a
 * `## Summary` block. The runtime guarantees this even if
 * the LLM forgets — it issues a tiny "post-turn" model
 * call and injects the response as the summary footer.
 *
 * The TUI's role is:
 *   1. Detect, on every assistant turn end, whether the
 *      last message already contains a `## Summary` block.
 *   2. If not, send a `summary/missing` RPC so the
 *      supervisor fires the post-turn model call. (The
 *      TUI never generates the summary itself; the model
 *      is owned by the Java side.)
 *   3. When the supervisor emits a `summary_injected`
 *      event, the renderer patches the message in place
 *      so the footer is always visible.
 *
 * The module is "headless" — it exposes pure helpers +
 * a small React hook. The TUI wires the hook into the
 * `Scrollback` so every turn end is checked.
 *
 * Pure helpers:
 *   - `extractSummary(text)` — returns the body of the
 *     `## Summary` block, or `null` if not present.
 *   - `hasSummaryBlock(text)` — boolean.
 *   - `enrichmentRequired(messages, lastIndex)` — true
 *     if the last assistant message is missing the block.
 */

import type { RpcEvent } from "../../rpc/types.js";

// --------------------------------------------------------------------
//  Pure helpers
// --------------------------------------------------------------------

/** Regex for the `## Summary` block. The block body is
 *  captured greedily up to the next `## ` heading or end
 *  of string. The TUI renderer relies on this same regex
 *  to extract the footer (see `SummaryFooter.tsx`). */
const SUMMARY_HEADING_RE = /^##\s*Summary\s*$/m;
const NEXT_HEADING_RE = /^##\s+\S/m;

/** Extract the `## Summary` block body from a message
 *  text. Returns `null` if no block is present. The body
 *  is the text between the `## Summary` heading and the
 *  next `## ` heading (or end of string), trimmed. */
export function extractSummary(text: string): string | null {
  if (!text) return null;
  const lines = text.split(/\r?\n/);
  let inSummary = false;
  const body: string[] = [];
  for (const line of lines) {
    if (!inSummary) {
      if (SUMMARY_HEADING_RE.test(line)) {
        inSummary = true;
      }
      continue;
    }
    if (NEXT_HEADING_RE.test(line)) {
      break;
    }
    body.push(line);
  }
  if (!inSummary) return null;
  const trimmed = body.join("\n").trim();
  return trimmed.length === 0 ? null : trimmed;
}

/** True if the message text contains a non-empty
 *  `## Summary` block. */
export function hasSummaryBlock(text: string): boolean {
  return extractSummary(text) !== null;
}

/** A minimal "message" shape. The TUI's `State.turns`
 *  is more elaborate, but the enricher only needs the
 *  text + role. */
export interface AssistantMessage {
  role: "user" | "assistant" | "tool" | "system";
  text: string;
}

/** Pure helper: does the message at `lastIndex` need the
 *  post-turn summary? Returns `true` when the message is
 *  an assistant turn AND its text has no `## Summary`
 *  block (or the block is empty). */
export function enrichmentRequired(
  messages: ReadonlyArray<AssistantMessage>,
  lastIndex: number
): boolean {
  if (lastIndex < 0 || lastIndex >= messages.length) return false;
  const m = messages[lastIndex]!;
  if (m.role !== "assistant") return false;
  return !hasSummaryBlock(m.text);
}

/** Pure helper: build the post-turn prompt the supervisor
 *  sends to the LLM when the summary is missing. The
 *  prompt is the spec's verbatim text (§12.3). */
export function buildAutoSummaryPrompt(messages: ReadonlyArray<AssistantMessage>, lastIndex: number): string {
  const slice = messages.slice(Math.max(0, lastIndex - 4), lastIndex + 1);
  return `Summarize the last ${slice.length} messages in 1-3 lines, naming what was done and what is intended next.`;
}

/** Pure helper: is the incoming RPC event a
 *  `summary_injected` from the supervisor? The TUI's
 *  `useRpcSubscription` calls this to filter the event
 *  stream. */
export function isSummaryInjectedEvent(ev: RpcEvent | { kind?: string } | null | undefined): boolean {
  if (!ev) return false;
  const k = (ev as { kind?: string }).kind;
  return k === "summary_injected" || k === "summary_injected_event";
}

/** Extract the summary body from a `summary_injected`
 *  event payload. The supervisor ships:
 *    { kind: "summary_injected", sessionId, seq, ts,
 *      params: { index, summary } }
 *  Returns `null` if the payload is malformed. */
export function summaryFromEvent(ev: RpcEvent | { params?: any }): string | null {
  const params = (ev as { params?: any })?.params;
  if (!params) return null;
  const s = typeof params.summary === "string" ? params.summary : null;
  if (!s) return null;
  const trimmed = s.trim();
  return trimmed.length === 0 ? null : trimmed;
}

// --------------------------------------------------------------------
//  React hook (T-7-03)
// --------------------------------------------------------------------

/** `useTranscriptEnricher` watches the messages and
 *  fires `onMissingSummary(lastIndex)` whenever the last
 *  message needs an auto-summary. The TUI wires
 *  `onMissingSummary` to a `summary/missing` RPC call
 *  (so the supervisor issues the post-turn model call).
 *
 *  The hook is intentionally a thin wrapper around the
 *  pure helpers — the actual LLM call is owned by the
 *  supervisor (spec §12.3). */
export interface UseTranscriptEnricherOptions {
  messages: ReadonlyArray<AssistantMessage>;
  /** Called when the last assistant message is missing
   *  the `## Summary` block. The caller is responsible
   *  for sending the RPC. */
  onMissingSummary?: (lastIndex: number, prompt: string) => void;
  /** Skip while the parent is in a "loading" state. */
  enabled?: boolean;
}

import { useEffect } from "react";

export function useTranscriptEnricher(opts: UseTranscriptEnricherOptions): {
  needsEnrichment: boolean;
  lastIndex: number | null;
} {
  const last = opts.messages.length - 1;
  const needs = opts.enabled !== false && enrichmentRequired(opts.messages, last);
  useEffect(() => {
    if (!needs) return;
    const prompt = buildAutoSummaryPrompt(opts.messages, last);
    try {
      opts.onMissingSummary?.(last, prompt);
    } catch { /* ignore — the host may be unmounted */ }
    // We intentionally re-run on every change to
    // `messages` so a freshly-loaded transcript triggers
    // the enricher once per turn.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [last, needs, opts.messages]);
  return { needsEnrichment: needs, lastIndex: needs ? last : null };
}
