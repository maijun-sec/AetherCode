/**
 * R245.1 (O-10): TUI-side BankClient wrapper.
 *
 * <p>Mirrors the Java {@code BankClient} in
 * {@code aethercode-deepagents} but lives in the TUI process.
 * The TUI uses it to read the daemon's strategy bank (recall
 * top-N units) so it can render a side-note when the user asks
 * via {@code /bank-stats} or {@code /bank-recall}. All calls
 * are best-effort: if the daemon is down, the helper just
 * returns a {@code down} summary instead of throwing — the
 * TUI renderer stays branch-free.</p>
 *
 * <h2>Why a separate wrapper</h2>
 *
 * <p>The {@code aethercode-memory} package already exports
 * {@link BankClient} (added in R244.3). This module wraps it
 * with three TUI/desktop affordances:</p>
 * <ol>
 *   <li>Reads the bank URL from {@code AETHERCODE_BANK_URL}
 *       (default {@code http://127.0.0.1:7777}) so a user can
 *       point at a remote daemon without rebuilding.</li>
 *   <li>Returns a plain object (not a thrown exception) when
 *       the bank is unreachable, so the TUI can render an
 *       inline "bank: down" line without try/catch noise at
 *       every call site.</li>
 *   <li>Provides {@link formatBankStats} and
 *       {@link formatRecall} helpers that turn the raw
 *       {@link BankStats} / {@link BankUnit} payloads into
 *       1-line summaries.</li>
 * </ol>
 *
 * <p>Tests live in {@code __tests__/bank-recall.test.ts}
 * (vitest, alongside {@code bank-client.test.ts} from
 * R244.3).</p>
 */

import { BankClient, BankClientError, type BankStats, type BankUnit } from './bank-client.js';

/** Default base URL the daemon's {@code BankServer} listens on
 *  (see {@code TalonSelfReflectWiring.startBankServer} in
 *  aethercode-deepagents). Users override via the
 *  {@code AETHERCODE_BANK_URL} env var. */
export const DEFAULT_BANK_URL = 'http://127.0.0.1:7777';

/** Read the bank base URL from {@code AETHERCODE_BANK_URL}, falling
 *  back to {@link DEFAULT_BANK_URL}. Trims a single trailing slash
 *  so downstream callers can blindly concatenate paths. */
export function resolveBankUrl(env: Record<string, string | undefined> = process.env): string {
  const raw = env.AETHERCODE_BANK_URL ?? DEFAULT_BANK_URL;
  return raw.endsWith('/') ? raw.slice(0, -1) : raw;
}

/** Construct a {@link BankClient} bound to the configured URL. The
 *  client is cheap (just holds the URL + a fetch reference) so
 *  callers can build a new one per command. */
export function makeBankClient(env: Record<string, string | undefined> = process.env): BankClient {
  return new BankClient(resolveBankUrl(env));
}

/** TUI-shaped result of any bank read: either a one-line text
 *  summary plus the raw payload, or a {@code ok: false} flag
 *  with a short reason. The {@code text} field is always
 *  populated so the TUI can just print it. */
export type BankSummary =
  | { ok: true; text: string; stats: BankStats }
  | { ok: true; text: string; units: BankUnit[]; kind: string }
  | { ok: false; text: string; reason: string };

/** One-line text summary of a {@link BankStats} payload, e.g.
 *  {@code "bank: 12 units, 3 kinds, 8 ok / 1 notOk"}. */
export function formatBankStats(s: BankStats): string {
  const kindList = s.kinds.length === 0 ? '0 kinds' : `${s.kinds.length} kinds`;
  const ratio = s.totalOk + s.totalNotOk === 0
    ? 'no outcomes yet'
    : `${s.totalOk} ok / ${s.totalNotOk} notOk`;
  return `bank: ${s.size} units, ${kindList}, ${ratio}`;
}

/** One-line text summary of a recall result for a single kind,
 *  e.g. {@code "bank[file_edit]: 3 units (top: mkdir -p /x)"}.
 *  If the top unit has no example, the trailing ": ..." is
 *  omitted. */
export function formatRecall(kind: string, units: BankUnit[]): string {
  if (units.length === 0) return `bank[${kind}]: 0 units`;
  const top = units[0];
  const ex = top.example && top.example.length > 0 ? `: ${top.example}` : '';
  return `bank[${kind}]: ${units.length} units (top: ${top.fixStrategy}${ex})`;
}

/** Read bank stats; return a {@link BankSummary} that the TUI
 *  can render with no extra branching. Used by
 *  {@code /bank-stats} and the welcome-banner hook. */
export async function readBankStats(env: Record<string, string | undefined> = process.env): Promise<BankSummary> {
  const client = makeBankClient(env);
  try {
    const stats = await client.stats();
    return { ok: true, text: formatBankStats(stats), stats };
  } catch (e: unknown) {
    return downSummary(e);
  }
}

/** Read top-N units for a task kind. {@code n} defaults to 3,
 *  matching the daemon's default. Empty {@code kind} returns
 *  a {@code down} summary without hitting the wire. */
export async function readBankRecall(
  kind: string,
  n: number = 3,
  env: Record<string, string | undefined> = process.env,
): Promise<BankSummary> {
  if (!kind) {
    return { ok: false, text: `bank[${kind}]: kind must be non-empty`, reason: 'empty-kind' };
  }
  const client = makeBankClient(env);
  try {
    const units = await client.recallFor(kind, n);
    return { ok: true, text: formatRecall(kind, units), units, kind };
  } catch (e: unknown) {
    return downSummary(e);
  }
}

/** Map a thrown error from {@link BankClient} into the TUI's
 *  {@code down} summary shape. We collapse every error into
 *  the same {@code ok:false} payload so the TUI's renderer
 *  only has to handle two cases. */
function downSummary(e: unknown): BankSummary {
  if (e instanceof BankClientError) {
    if (e.status === 0) {
      return { ok: false, text: 'bank: down (transport error)', reason: e.message };
    }
    return { ok: false, text: `bank: down (HTTP ${e.status})`, reason: e.message };
  }
  const msg = e instanceof Error ? e.message : String(e);
  return { ok: false, text: 'bank: down', reason: msg };
}
