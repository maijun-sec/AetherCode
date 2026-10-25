/**
 * R379 — one-line reports for the RPCs the TUI fires on the user's
 * behalf.
 *
 * ## Why these live in a module
 *
 * The Ink renderer and the line renderer both answer `/tokens`,
 * `/rewind` and `/agent-pick-set-default`. When each of them wrote
 * its own sentence, the two surfaces could say different things about
 * the same answer — and the "raw JSON dump" fallback
 * (`session/tokens → {"ok":true,"input":123,...}`) is not a report at
 * all, it is the absence of one.
 *
 * ## The rule these follow
 *
 * Report what the daemon RESOLVED, never what we asked for (R377's
 * rule). `agent/setDefault` answers with the default it actually
 * applied, which is not necessarily the one that was requested.
 */

/** The `session/tokens` response. */
export interface SessionTokensReport {
  sessionId: string;
  input: number;
  output: number;
  total: number;
  costUsd: number;
}

/**
 * Render a `session/tokens` response as one line.
 *
 * <p>A response the daemon could not produce (no cost tracker) comes
 * back as `{ok: false, error}` — that is reported as the error it is,
 * never as a row of zeroes. Zero tokens is a MEASUREMENT; a missing
 * tracker is not.
 */
export function formatSessionTokens(result: unknown): string {
  if (result === null || typeof result !== "object") return "session/tokens → no answer";
  const r = result as Record<string, unknown>;
  if (r.ok === false) {
    return `session/tokens failed: ${String(r.error ?? "unknown error")}`;
  }
  const num = (v: unknown): number => (typeof v === "number" && Number.isFinite(v) ? v : 0);
  const input = num(r.input);
  const output = num(r.output);
  const total = typeof r.total === "number" ? num(r.total) : input + output;
  const cost = num(r.costUsd);
  const session = typeof r.sessionId === "string" && r.sessionId ? r.sessionId : "this session";
  return `tokens · ${session} · ${total} total (${input} in / ${output} out) · $${cost.toFixed(4)}`;
}

/**
 * Render a `rewind` response as one line.
 *
 * <p>A refused rewind (no such user message) is reported as the
 * refusal it is. The TUI must not say "rewound" when nothing moved.
 */
export function formatRewind(result: unknown): string {
  if (result === null || typeof result !== "object") return "rewind → no answer";
  const r = result as Record<string, unknown>;
  if (r.ok === false) {
    return `rewind failed: ${String(r.reason ?? "unknown reason")}`;
  }
  const kept = typeof r.kept === "number" ? r.kept : 0;
  const removed = typeof r.removed === "number" ? r.removed : 0;
  return `rewound · kept ${kept} message${kept === 1 ? "" : "s"} · dropped ${removed}`;
}

/**
 * Render an `agent/setDefault` response as one line.
 *
 * <p>The daemon reports the default it RESOLVED, so the line reports
 * that too — a daemon that answered with a different name than the
 * one asked for must not be echoed back as the request.
 */
export function formatSetDefaultAgent(result: unknown): string {
  if (result === null || typeof result !== "object") return "agent/setDefault → no answer";
  const r = result as Record<string, unknown>;
  if (r.ok === false) {
    return `agent/setDefault failed: ${String(r.error ?? "unknown error")}`;
  }
  const name = typeof r.defaultAgent === "string" ? r.defaultAgent : "";
  return name ? `default agent → ${name}` : "default agent → (none)";
}
