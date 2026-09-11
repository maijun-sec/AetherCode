/**
 * R245.2 (O-6): cross-process self-eval audit.
 *
 * <p>Aggregates the daemon's strategy-bank self-eval metrics
 * ({@code okCount}, {@code notOkCount}, {@code confidence})
 * into a single audit report that the TUI's MemoryAudit panel
 * can render. Closes the loop between R230 (aethercode-memory
 * MemoryAudit, which audits the local three-layer memory) and
 * R244.1 (aethercode-deepagents SelfEvalMiddleware, which
 * records ok/notOk outcomes on every recalled unit).</p>
 *
 * <h2>Why a separate module</h2>
 *
 * <p>{@code bank-recall.ts} is a thin wrapper around
 * {@link BankClient} for user-driven commands
 * ({@code /bank-stats}, {@code /bank-recall}). This module
 * does something different: it pulls every unit, computes
 * per-kind success rates + Laplace-smoothed confidence
 * averages, and produces a 1-screen audit summary. Living
 * in its own file keeps the {@code bank-recall} surface
 * focused on "read a unit" and the audit surface focused on
 * "evaluate the bank".</p>
 *
 * <h2>Wire dependency</h2>
 *
 * <p>Like {@code bank-recall}, this module reads the bank
 * URL from {@code AETHERCODE_BANK_URL} (default
 * {@code http://127.0.0.1:7777}) and returns a
 * {@link SelfEvalAuditSummary} that always populates
 * {@code text} so the TUI's renderer can just print it.</p>
 *
 * <h2>Confidence math</h2>
 *
 * <p>For each unit we compute {@code confidence = okCount /
 * (okCount + notOkCount + 1)} — the same Laplace-smoothed
 * formula R244.1 introduced. The +1 keeps zero-observation
 * units at 0.0 (not NaN) so the average is always defined.</p>
 */

import type { BankClient, BankStats, BankUnit } from './bank-client.js';
import { BankClientError } from './bank-client.js';
import { makeBankClient } from './bank-recall.js';

/** Per-kind audit row. {@code rate} is the success rate
 *  (0.0-1.0), {@code avgConfidence} is the Laplace-smoothed
 *  mean across that kind's units, {@code observationCount}
 *  is the total number of ok+notOk observations (used to
 *  sort "weakest" kinds by relevance, not just rate). */
export interface KindAudit {
  kind: string;
  units: number;
  ok: number;
  notOk: number;
  rate: number;
  avgConfidence: number;
  observationCount: number;
}

/** Top-level audit payload, plus the rendered one-screen
 *  text summary. {@code text} is always populated. */
export interface SelfEvalAuditReport {
  totalUnits: number;
  totalOk: number;
  totalNotOk: number;
  successRate: number;
  avgConfidence: number;
  perKind: KindAudit[];
  weakestKind: string | null;
  topKind: string | null;
  observed: number; // total ok+notOk across all units
}

/** TUI-shaped result; same contract as
 *  {@link import('./bank-recall.js').BankSummary}: text
 *  always populated, payload attached on success. */
export type SelfEvalAuditSummary =
  | { ok: true; text: string; report: SelfEvalAuditReport }
  | { ok: false; text: string; reason: string };

/** Default N to ask {@link BankClient#recallAllKinds} for.
 *  Matches the daemon's default LRU cap (R243.1) so we
 *  always get the full bank in one call. */
const RECALL_ALL_N = 1000;

/** Laplace smoothing constant for confidence. The +1 in
 *  the denominator keeps a unit with 0 observations at
 *  confidence 0.0 (not NaN) so the average is always
 *  defined. Mirrors the formula in
 *  {@code ReasoningUnit.confidence()} (R244.1). */
const LAPLACE_K = 1;

/** Compute the per-unit confidence score. Public so the
 *  TUI's audit report can expose it for ad-hoc inspection.
 *  Non-numeric or negative inputs are coerced to 0 so the
 *  denominator is always a positive integer (defensive
 *  against a future wire-format regression). */
export function unitConfidence(okCount: number, notOkCount: number): number {
  const ok = finiteNonNeg(okCount);
  const not = finiteNonNeg(notOkCount);
  const denom = ok + not + LAPLACE_K;
  return ok / denom;
}

/** Defensive coercion: non-finite or negative → 0. */
function finiteNonNeg(v: number): number {
  return Number.isFinite(v) && v >= 0 ? v : 0;
}

/** Pull every unit (up to {@link RECALL_ALL_N}) and
 *  aggregate the per-kind + global audit. Returns the
 *  raw {@link SelfEvalAuditReport} (no formatting) so
 *  callers that want JSON or custom rendering can use it
 *  directly. Throws if the bank is unreachable; the TUI
 *  helper {@link auditSelfEval} catches and wraps. */
export async function buildSelfEvalReport(
  client: BankClient,
): Promise<SelfEvalAuditReport> {
  // stats() gives the cheap global snapshot; recallAllKinds
  // gives per-unit okCount/notOkCount we need for the
  // per-kind breakdown. Two round trips is fine — both are
  // <10 KB JSON.
  const [stats, units] = await Promise.all([
    client.stats(),
    client.recallAllKinds(RECALL_ALL_N),
  ]);
  return aggregateReport(stats, units);
}

/** Pure aggregator: turn {@link BankStats} + a flat
 *  {@link BankUnit} list into a {@link SelfEvalAuditReport}.
 *  Exported (and `export`-ed) so unit tests can verify the
 *  math without a network mock. */
export function aggregateReport(stats: BankStats, units: BankUnit[]): SelfEvalAuditReport {
  // Per-kind buckets.
  const buckets = new Map<string, { units: number; ok: number; notOk: number; confSum: number; observed: number }>();
  for (const u of units) {
    let b = buckets.get(u.taskKind);
    if (!b) {
      b = { units: 0, ok: 0, notOk: 0, confSum: 0, observed: 0 };
      buckets.set(u.taskKind, b);
    }
    const uOk = numberOf(u.okCount);
    const uNotOk = numberOf(u.notOkCount);
    b.units += 1;
    b.ok += uOk;
    b.notOk += uNotOk;
    b.observed += uOk + uNotOk;
    b.confSum += unitConfidence(uOk, uNotOk);
  }

  // Build the perKind list; sort weakest-first by rate,
  // breaking ties by observation count (more observed =
  // more trustworthy).
  const perKind: KindAudit[] = [];
  for (const [kind, b] of buckets) {
    const total = b.ok + b.notOk;
    perKind.push({
      kind,
      units: b.units,
      ok: b.ok,
      notOk: b.notOk,
      rate: total === 0 ? 0 : b.ok / total,
      avgConfidence: b.units === 0 ? 0 : b.confSum / b.units,
      observationCount: b.observed,
    });
  }
  perKind.sort((a, b) => {
    if (a.rate !== b.rate) return a.rate - b.rate;
    return b.observationCount - a.observationCount;
  });

  // Global metrics come from `stats` when available, but we
  // re-derive from the units as a defensive cross-check.
  // The per-unit numbers can lag behind stats by one record
  // if a recordOutcome race fires between the two calls;
  // we prefer `stats` for the headline numbers.
  const observed = stats.totalOk + stats.totalNotOk;
  const successRate = observed === 0 ? 0 : stats.totalOk / observed;
  const avgConfidence = perKind.length === 0
    ? 0
    : perKind.reduce((s, k) => s + k.avgConfidence, 0) / perKind.length;

  // Weakest / top picks only consider kinds with at least
  // one observation — a 0-obs kind with rate=0 is just
  // unranked, not "weakest".
  const ranked = perKind.filter((k) => k.observationCount > 0);
  const weakestKind = ranked.length === 0 ? null : ranked[0].kind;
  const topKind = ranked.length === 0 ? null : ranked[ranked.length - 1].kind;

  return {
    totalUnits: stats.size,
    totalOk: stats.totalOk,
    totalNotOk: stats.totalNotOk,
    successRate,
    avgConfidence,
    perKind,
    weakestKind,
    topKind,
    observed,
  };
}

/** Coerce a possibly-undefined count to a non-negative
 *  integer. Defensive: server could (theoretically) emit
 *  a non-numeric value if a future R246+ migration breaks
 *  the wire format, and we don't want a NaN poisoning the
 *  average. */
function numberOf(v: unknown): number {
  const n = Number(v);
  return Number.isFinite(n) && n >= 0 ? n : 0;
}

/** Render a {@link SelfEvalAuditReport} as a multi-line
 *  TUI block. ~6 lines by default — long enough to surface
 *  weakest/top kinds, short enough to fit in a side-note
 *  without scroll. */
export function formatSelfEvalReport(r: SelfEvalAuditReport): string {
  const lines: string[] = [];
  const rateText = r.observed === 0
    ? 'no outcomes yet'
    : `${pct(r.successRate)} (${r.totalOk}/${r.totalOk + r.totalNotOk})`;
  lines.push(`self-eval audit: ${r.totalUnits} units, success ${rateText}, avg confidence ${r.avgConfidence.toFixed(2)}`);
  if (r.observed > 0) {
    if (r.weakestKind) {
      const w = r.perKind.find((k) => k.kind === r.weakestKind);
      if (w && w.observationCount > 0) {
        lines.push(`  weakest: ${w.kind} (${pct(w.rate)} over ${w.observationCount} obs)`);
      }
    }
    if (r.topKind && r.topKind !== r.weakestKind) {
      const t = r.perKind.find((k) => k.kind === r.topKind);
      if (t && t.observationCount > 0) {
        lines.push(`  top:     ${t.kind} (${pct(t.rate)} over ${t.observationCount} obs)`);
      }
    }
  }
  return lines.join('\n');
}

/** Format a 0-1 rate as a percentage. */
function pct(r: number): string {
  return `${Math.round(r * 100)}%`;
}

/** Build a {@link BankClient} bound to the configured URL.
 *  Same shape as {@code bank-recall.makeBankClient}; kept
 *  here to keep this module self-contained for tests
 *  (so a unit test can pass its own client). */

/** TUI-facing entry point. Returns a
 *  {@link SelfEvalAuditSummary} that always populates
 *  {@code text} so the renderer can just print it. On
 *  failure the {@code reason} field is set to the
 *  underlying error message; the caller can decide
 *  whether to log it.
 *
 * <p>The {@code client} parameter is exposed (optional) so
 * tests can pass a fake {@link BankClient} without going
 * through {@code makeBankClient} — keeps the suite
 * deterministic without mocking the network layer.</p>
 */
export async function auditSelfEval(
  envOrClient: Record<string, string | undefined> | BankClient = process.env,
  maybeEnv?: Record<string, string | undefined>,
): Promise<SelfEvalAuditSummary> {
  let client: BankClient;
  if (envOrClient && typeof (envOrClient as BankClient).stats === 'function') {
    // First arg is a BankClient (test or DI scenario).
    client = envOrClient as BankClient;
  } else {
    // First arg is the env record; build a client from it.
    client = makeBankClient(envOrClient as Record<string, string | undefined>);
    // maybeEnv ignored in this branch; kept for signature symmetry.
    void maybeEnv;
  }
  try {
    const report = await buildSelfEvalReport(client);
    return { ok: true, text: formatSelfEvalReport(report), report };
  } catch (e: unknown) {
    if (e instanceof BankClientError) {
      if (e.status === 0) {
        return { ok: false, text: 'self-eval audit: down (transport error)', reason: e.message };
      }
      return { ok: false, text: `self-eval audit: down (HTTP ${e.status})`, reason: e.message };
    }
    // Diagnostic for non-BankClientError failures (e.g. a
    // test's fake client threw an unexpected shape). Surfacing
    // the constructor + message helps debug mock wiring
    // without leaking that detail to the user-facing text.
    const ctor = (e as { constructor?: { name?: string } })?.constructor?.name ?? 'unknown';
    const msg = e instanceof Error ? e.message : String(e);
    return { ok: false, text: 'self-eval audit: down', reason: `${ctor}: ${msg}` };
  }
}
