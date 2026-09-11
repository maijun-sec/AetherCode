// pure subagent event reducer.
//
// Extracted from store/index.ts so the shape is unit-testable in
// isolation (vitest). Mirrors the TUI's state.ts subagentEvent
// reducer so the two surfaces render the same labels. No
// framework, no Tauri imports — feed it an event, get a new
// state. Easy to reason about, easy to test.
//
// The reducer is the single source of truth for:
//   - the formatted status-bar string (formatSubagentStatus)
//   - the running count (increments on RUNNING, decrements on
//     terminal transitions)
//   - the in-memory job list (LRU-evicted, capped at
//     MAX_SUBAGENT_JOBS so a long session doesn't grow without
//     bound)
//   - the "last terminal" event for toast display
//
// The store wires `subagent_event` JSON-RPC notifications into
// `reduceSubagent` in its `rpc.on('subagent_event', ...)`
// subscription. Toast UI reads `subagentLastTerminal` and
// calls `dismissSubagentTerminal` after the auto-dismiss timer.

export type SubagentStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

/** One row in the in-memory subagent list. */
export interface SubagentJobView {
  jobId: string;
  role: string;
  status: SubagentStatus;
  /** Wall-clock ms when the job first appeared in RUNNING
   *  (or the first event we saw, if we missed the RUNNING
   *  transition). */
  startedAtMs: number;
  /** Wall-clock ms when the job entered a terminal state.
   *  Undefined while still running. */
  endedAtMs?: number;
  /** Engine-reported elapsed time at the latest event. */
  elapsedMs: number;
  /** Engine-supplied one-line summary (may be empty). */
  summary: string;
  /** the captured result text on COMPLETED
   *  transitions. Truncated to 4 KB on the wire. The
   *  SubagentPanel's "click to insert" action reads
   *  this. Undefined for non-COMPLETED jobs. */
  resultText?: string;
  /** most recent streaming partial result.
   *  Updated as the engine emits RUNNING events with
   *  the latest chunk. Cleared on terminal transitions
   *  (the final result takes over). The SubagentPanel
   *  shows this inline so the user sees a live preview
   *  of in-flight work without waiting for the
   *  terminal event. */
  partialResult?: string;
}

/** Snapshot for the toast component. Captures the engine's
 *  terminal transition verbatim so the toast can show
 *  "subagent sag-1 done (1.4s) — ok" with the engine's
 *  own words. */
export interface SubagentTerminalEvent {
  jobId: string;
  role: string;
  status: Exclude<SubagentStatus, 'RUNNING'>;
  summary: string;
  elapsedMs: number;
  atMs: number;
}

export interface SubagentState {
  /** Pre-formatted label for the StatusBar. */
  status: string;
  /** Number of currently-running subagent jobs. */
  running: number;
  /** In-memory job list keyed by jobId. LRU-evicted when
   *  the count exceeds {@link MAX_SUBAGENT_JOBS}. */
  jobs: Record<string, SubagentJobView>;
  /** Most recent terminal event. Cleared via
   *  `dismissTerminal` (e.g. by the toast's auto-dismiss
   *  timer). Null when no terminal event is pending
   *  display. */
  lastTerminal: SubagentTerminalEvent | null;
}

export const INITIAL_SUBAGENT: SubagentState = {
  status: '',
  running: 0,
  jobs: {},
  lastTerminal: null,
};

/** In-memory cap for the job list. Oldest terminal jobs are
 *  evicted FIFO; an in-flight job is never evicted while it
 *  is still in the running count. 32 is enough to fill
 *  several minutes of background subagent churn without
 *  unbounded growth. */
export const MAX_SUBAGENT_JOBS = 32;

export interface SubagentEventAction {
  jobId: string;
  role: string;
  status: SubagentStatus;
  elapsedMs: number;
  atMs: number;
  summary: string;
  /** the engine session this subagent belongs to.
   *  Empty string is the "single-session" sentinel and
   *  the legacy-D wire shape; the reducer accepts it as
   *  "ours" when currentSessionId is also empty (so a
   *  fresh desktop before getState returns doesn't drop
   *  events). The store filter is what enforces strict
   *  per-session isolation. */
  sessionId?: string;
  /** the captured result text on COMPLETED
   *  transitions (truncated to 4 KB on the wire). The
   *  SubagentPanel uses it for the "insert into input"
   *  action. Falsy for non-COMPLETED transitions. */
  resultText?: string;
  /** streaming partial result. Updated on
   *  every RUNNING event so the SubagentPanel can
   *  show a live preview of in-flight work. Empty /
   *  undefined on terminal transitions. */
  partialResult?: string;
}

export function isTerminalStatus(status: SubagentStatus): boolean {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED';
}

/** returns true when the event should be processed
 *  for the given current session. An empty event sessionId
 *  is treated as "all sessions" so legacy-D daemons still
 *  surface events to the (single-session) desktop. A
 *  non-empty event sessionId is only processed when it
 *  matches the current session — non-matches are dropped on
 *  the floor (the daemon is process-singleton, so a noisy
 *  neighbour is the failure mode we're guarding against). */
export function isOurSession(
  evSessionId: string | undefined,
  currentSessionId: string | null,
): boolean {
  const ev = evSessionId ?? '';
  if (ev === '') return true;       // legacy / single-session sentinel
  if (currentSessionId == null || currentSessionId === '') return true;  // pre-init
  return ev === currentSessionId;
}

export function formatSubagentStatus(
  jobId: string,
  status: SubagentStatus,
  elapsedMs: number,
): string {
  const id = jobId || '?';
  switch (status) {
    case 'RUNNING':
      return `[${id}] running`;
    case 'COMPLETED':
      if (elapsedMs > 0) {
        const sec = elapsedMs / 1000;
        const txt = sec >= 10 ? `${Math.round(sec)}s` : `${sec.toFixed(1)}s`;
        return `[${id}] done ${txt}`;
      }
      return `[${id}] done`;
    case 'FAILED':
      return `[${id}] failed`;
    case 'CANCELLED':
      return `[${id}] cancelled`;
    default:
      return `[${id}] ${String(status).toLowerCase()}`;
  }
}

/** Apply a single subagent_event to the state and return the
 *  next state. Pure — no side effects, no Date.now() (caller
 *  supplies `atMs`). */
export function reduceSubagent(
  state: SubagentState,
  ev: SubagentEventAction,
): SubagentState {
  const { jobId, role, status, elapsedMs, atMs, summary } = ev;
  const terminal = isTerminalStatus(status);

  // Build the updated job record. Preserve the original
  // startedAtMs across transitions; only set endedAtMs on
  // terminal events. prior round: preserve a captured result
  // text across transitions — once a COMPLETED event
  // arrives we keep the text even if a stale FAILED
  // event sneaks in (the engine's markFailed guard
  // prevents this in practice, but defence in depth).
  const prev = state.jobs[jobId];
  const job: SubagentJobView = {
    jobId,
    role: role || prev?.role || '',
    status,
    startedAtMs:
      prev?.startedAtMs ??
      (status === 'RUNNING' ? Math.max(0, atMs - elapsedMs) : atMs),
    endedAtMs: terminal ? atMs : undefined,
    elapsedMs,
    summary,
    resultText: ev.resultText ?? prev?.resultText,
    // surface the streaming partial result on
    // RUNNING rows; clear on terminal transitions.
    partialResult: status === 'RUNNING'
      ? (ev.partialResult ?? prev?.partialResult)
      : undefined,
  };

  // Evict the oldest terminal job when the list is full and
  // this is a new job. Never evict an in-flight job (status
  // still RUNNING in the jobs map) — the running subagent is
  // visible to the user, removing it would create phantom
  // "where did it go?" moments.
  let jobs = state.jobs;
  if (!jobs[jobId]) {
    const ids = Object.keys(jobs);
    if (ids.length >= MAX_SUBAGENT_JOBS) {
      const terminalIds = ids.filter((id) => {
        const j = jobs[id];
        return j.status !== 'RUNNING' && j.endedAtMs != null;
      });
      // Pick the terminal job with the smallest endedAtMs.
      terminalIds.sort((a, b) => {
        const ea = jobs[a].endedAtMs ?? 0;
        const eb = jobs[b].endedAtMs ?? 0;
        return ea - eb;
      });
      const dropId = terminalIds[0];
      if (dropId) {
        const { [dropId]: _drop, ...rest } = jobs;
        jobs = rest;
      }
      // If no terminal job is evictable (all in-flight),
      // the cap is a soft limit — keep growing. This is
      // rare in practice because the user would need 33
      // simultaneous in-flight subagents.
    }
  }
  jobs = { ...jobs, [jobId]: job };

  // Running count: increment on RUNNING, decrement on
  // terminal. Clamp at 0 so a duplicate COMPLETED event
  // for a job we already cleared doesn't go negative.
  const running = terminal
    ? Math.max(0, state.running - 1)
    : state.running + 1;

  const next: SubagentState = {
    status: formatSubagentStatus(jobId, status, elapsedMs),
    running,
    jobs,
    lastTerminal: state.lastTerminal,
  };
  if (terminal) {
    next.lastTerminal = {
      jobId,
      role: role || prev?.role || '',
      status: status as Exclude<SubagentStatus, 'RUNNING'>,
      summary,
      elapsedMs,
      atMs,
    };
  }
  return next;
}

/** Clear the pending terminal event. Called by the toast
 *  after auto-dismiss (or by a click). */
export function dismissTerminal(state: SubagentState): SubagentState {
  return { ...state, lastTerminal: null };
}
