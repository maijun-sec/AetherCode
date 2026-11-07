/**
 * R388k — SDD run discovery for the line renderer.
 *
 * The Ink renderer keeps the active SDD run in reducer state
 * (`state.sddRun?.slug`) and therefore always knows the slug. The line
 * renderer has no such state: it is a plain command loop, so it cannot
 * "remember" a slug that was printed to the screen several commands ago —
 * and it certainly cannot remember one that was created in a *previous*
 * session.
 *
 * Pre-R388k the line renderer simply never sent a slug at all
 * (`{action: payload}` for `sdd.advance`, `{}` for `sdd.abort`, and
 * `probe(ctx, "sdd.status")` with no params). The daemon rejected all of
 * them with "slug and action are required", so **every** SDD command after
 * `/sdd <intent>` failed in a pure terminal, with an error that looked
 * exactly like "this daemon has no SDD support".
 *
 * The fix is to *discover* the slug rather than remember it. The daemon
 * already exposes the primitive: `sdd.listRuns {cwd}` returns every run
 * under `<cwd>/.aethercode/sdd/`, each carrying `slug`, `status`,
 * `currentPhase` and `lastUpdatedAt`.
 *
 * This is also the shape every benchmark tool converges on. Devin Local
 * writes its plan to a Markdown file "that lives outside your repository
 * and persists for the session"; Claude Code keeps plan files in
 * `~/.claude/plans/`; OpenCode's commands share one registry. The slug
 * already lives on disk in `phase-state.json` — reading it is the honest
 * design, and it survives a restart, which session state does not.
 *
 * ONE RULE, ONE LOOKUP: the active run for a cwd is the non-terminal run
 * with the most recent `lastUpdatedAt`. There is deliberately no second
 * list and no hard-coded slug.
 */

/** A run as the daemon reports it in `sdd.listRuns`. */
export interface SddRunSummary {
  slug: string;
  /** `running` | `idle` | `done` | `aborted` | `failed` */
  status: string;
  currentPhase: number;
  /** ISO-8601 timestamp, or "" when the daemon did not report one. */
  lastUpdatedAt: string;
  intent?: string;
}

/**
 * Run statuses that mean "this run is over". A run in one of these
 * statuses can never be advanced, so it is never the active run.
 *
 * `idle` counts as ACTIVE on purpose: a run that has finished a phase and
 * is waiting for the user's approve / modify / skip reply is exactly the
 * run the user wants to advance.
 */
const TERMINAL_STATUSES = new Set(["done", "aborted", "failed", "complete", "completed"]);

/** True when the run can still be advanced / inspected. */
export function isActiveRun(run: SddRunSummary): boolean {
  if (!run.slug) return false;
  return !TERMINAL_STATUSES.has((run.status || "").toLowerCase());
}

/**
 * Pick the active run out of a list of runs.
 *
 * Tie-break: most recent `lastUpdatedAt` wins. A run with no timestamp
 * sorts oldest, so a timestamped run always beats an untimestamped one.
 * Ties beyond that keep the list order (the daemon lists newest-first).
 *
 * Returns null when there is no active run — the caller must then say so
 * clearly rather than sending a malformed request.
 */
export function pickActiveRun(runs: SddRunSummary[]): SddRunSummary | null {
  let best: SddRunSummary | null = null;
  let bestTs = -Infinity;
  for (const r of runs) {
    if (!isActiveRun(r)) continue;
    const ts = parseStamp(r.lastUpdatedAt);
    if (best === null || ts > bestTs) {
      best = r;
      bestTs = ts;
    }
  }
  return best;
}

/**
 * Parse an ISO-8601 timestamp to epoch millis. Returns -Infinity for
 * anything unparseable so an undated run sorts oldest.
 */
function parseStamp(s: string | undefined): number {
  if (!s) return -Infinity;
  const t = Date.parse(s);
  return Number.isNaN(t) ? -Infinity : t;
}

/**
 * Pull the run list out of an `sdd.listRuns` response.
 *
 * The daemon answers `{ok: true, runs: [...]}` (`AetherCodeMethods.sddListRuns`).
 * The envelope key is read tolerantly because payload shapes drift between
 * daemon versions — the same reason every other read in the line renderer
 * goes through `field()`.
 */
export function extractRuns(value: unknown): SddRunSummary[] {
  const root = value as Record<string, unknown> | null | undefined;
  const raw = Array.isArray(root?.runs)
    ? root.runs
    : Array.isArray(root)
      ? root
      : Array.isArray(root?.items)
        ? root.items
        : [];
  const out: SddRunSummary[] = [];
  for (const item of raw) {
    if (!item || typeof item !== "object") continue;
    const r = item as Record<string, unknown>;
    const slug = typeof r.slug === "string" ? r.slug : "";
    if (!slug) continue;
    out.push({
      slug,
      status: typeof r.status === "string" ? r.status : "",
      currentPhase: typeof r.currentPhase === "number" ? r.currentPhase : 0,
      lastUpdatedAt: typeof r.lastUpdatedAt === "string" ? r.lastUpdatedAt : "",
      intent: typeof r.intent === "string" ? r.intent : undefined,
    });
  }
  return out;
}

/**
 * Build the params every SDD verb needs.
 *
 * `sdd.advance` needs `{slug, action}`; `sdd.status` and `sdd.abort` need
 * `{slug, cwd}`. Sending `cwd` on all of them is harmless (the daemon
 * ignores what it does not read) and it removes a whole class of
 * "which verb wanted cwd" mistakes.
 */
export function sddParams(
  run: SddRunSummary,
  cwd: string,
  extra?: Record<string, unknown>,
): Record<string, unknown> {
  return { slug: run.slug, cwd, ...(extra ?? {}) };
}
