import { useEffect, useRef, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { rpc } from '../lib/methods';
import type {
  SubagentDashboardSnapshot,
  SubagentAgentMetric,
} from '../lib/methods';
import './SubagentDashboard.css';

// R373: per-agent dashboard surface for the desktop
// SubagentPanel. Polls SubagentRegistry.dashboardMetrics()
// (R372.4) every ~1.5s while mounted and renders a global
// rollup header + a grid of per-agent cards.
//
// Each card surfaces the five fields the user actually
// cares about:
//   1. live job counts (running / completed / failed)
//   2. token usage (with a small budget bar)
//   3. circuit breaker state (CLOSED / OPEN / HALF_OPEN)
//      with a live countdown when the circuit is tripped
//   4. concurrency quota (currently 1 per agent by default;
//      the dashboard makes the quota visible so a future
//      round can bump it for an agent and see the effect)
//   5. recent failures (consecutiveFailures) so the user
//      sees the breaker is approaching its threshold
//
// The poll interval (1.5s) is intentionally slower than the
// subagent_event notification cadence (~100ms during a job
// run) — the dashboard is a low-rate summary view, the
// per-job panel is the high-rate one. The two share the
// same backing store so they never disagree about which
// jobs are running; the dashboard just doesn't re-render on
// every notification.
//
// Empty registry: a single friendly placeholder card. The
// `if (agents.length === 0)` short-circuit avoids rendering
// a grid header + zero rows, which is visually noisy.

interface Props {
  // optional polling interval (ms). Defaults to 1500.
  // Exposed so a future "live" toggle can speed it up
  // without a code change. The poll runs only while the
  // dashboard tab is visible — there's no global ticker
  // burning CPU when the user is on the per-job tab.
  pollMs?: number;
}

export function SubagentDashboard({ pollMs = 1500 }: Props) {
  const [snap, setSnap] = useState<SubagentDashboardSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  // ref tracks whether the component is still mounted so a
  // late poll response doesn't try to setState on an
  // unmounted component (React 18 silent warning that hides
  // real bugs in test runs).
  const aliveRef = useRef(true);

  useEffect(() => {
    aliveRef.current = true;
    return () => { aliveRef.current = false; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const tick = async () => {
      try {
        const r = await invoke('rpc_call', {
          method: 'subagentDashboard',
          params: {},
        });
        if (cancelled || !aliveRef.current) return;
        // The rpc_call wrapper returns the raw result; cast
        // through unknown so TS doesn't widen the union. The
        // server always sets ok:true on success and the
        // fields below are guaranteed by the registry shape.
        const next = r as SubagentDashboardSnapshot;
        // Skip setState when the snapshot is referentially
        // identical to the previous one. The RPC returns a
        // fresh object each call so this short-circuit
        // rarely fires in practice, but it's a free win
        // when nothing's happening (idle workspace, empty
        // registry).
        setSnap((prev) => {
          if (prev && shallowEq(prev, next)) return prev;
          return next;
        });
        setError(null);
      } catch (e: any) {
        if (cancelled || !aliveRef.current) return;
        // The error is recoverable — the next tick will
        // reconnect. Surface a single inline note so the
        // user knows the dashboard is showing stale data.
        setError(typeof e === 'string' ? e : (e?.message ?? 'rpc failed'));
      } finally {
        if (!cancelled && aliveRef.current) setLoading(false);
      }
    };
    tick();
    const t = setInterval(tick, pollMs);
    return () => {
      cancelled = true;
      clearInterval(t);
    };
  }, [pollMs]);

  if (loading && !snap) {
    return (
      <div className="subagent-dashboard subagent-dashboard-loading">
        <span>Loading dashboard…</span>
      </div>
    );
  }

  const totals = snap?.totals;
  const agents = snap?.agents ?? [];
  const asOf = snap?.asOfMs;

  return (
    <div className="subagent-dashboard">
      <div className="subagent-dashboard-header">
        <span className="subagent-dashboard-title">Agent dashboard</span>
        {asOf ? (
          <span
            className="subagent-dashboard-asof"
            title={`Server timestamp ${new Date(asOf).toLocaleString()}`}
          >
            {formatRelative(asOf)}
          </span>
        ) : null}
      </div>

      {error ? (
        <div className="subagent-dashboard-error" title={error}>
          last fetch failed — showing stale data
        </div>
      ) : null}

      {totals ? (
        <div className="subagent-dashboard-totals">
          <TotalsPill
            label="running"
            value={totals.running}
            tone={totals.running > 0 ? 'accent' : 'dim'}
          />
          <TotalsPill
            label="completed"
            value={totals.completed}
            tone="ok"
          />
          <TotalsPill
            label="failed"
            value={totals.failed}
            tone={totals.failed > 0 ? 'error' : 'dim'}
          />
          <TotalsPill
            label="tokens"
            value={formatTokens(totals.tokensTotal)}
            tone="dim"
          />
          <TotalsPill
            label="agents"
            value={totals.agentsKnown}
            tone="dim"
          />
          {(totals.circuitOpen > 0 || totals.circuitHalfOpen > 0) ? (
            <TotalsPill
              label="circuit"
              value={`${totals.circuitOpen} open / ${totals.circuitHalfOpen} half`}
              tone={totals.circuitOpen > 0 ? 'error' : 'warn'}
            />
          ) : null}
        </div>
      ) : null}

      {agents.length === 0 ? (
        <div className="subagent-dashboard-empty">
          No agents have run yet. Once the model spawns a subagent
          (or you delegate to a configured one), per-agent rollups
          will appear here.
        </div>
      ) : (
        <div className="subagent-dashboard-grid">
          {agents.map((a) => (
            <AgentCard key={a.name} agent={a} />
          ))}
        </div>
      )}
    </div>
  );
}

// Single component, render one AgentCard. The card is the
// workhorse UI primitive — small enough that even with 10+
// agents on screen the grid still fits. Each card carries
// five "stat tiles" so a user can scan them at a glance.
function AgentCard({ agent }: { agent: SubagentAgentMetric }) {
  const total = agent.running + agent.completed + agent.failed;
  const failRate = total > 0 ? agent.failed / total : 0;
  // R374.2: editing the quota on a card. The pill
  // toggles into a small <input>; on Enter or blur we
  // fire subagentSetQuota and revert to the pill.
  // onEscape reverts without sending the RPC. A
  // transient "saving" pill replaces the pill while
  // the RPC is in flight so the user can see their
  // edit took effect; the next dashboard poll will
  // show the real value.
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<string>(String(agent.concurrencyQuota));
  const [saving, setSaving] = useState(false);
  const [savedFlash, setSavedFlash] = useState<number | null>(null);
  // sync draft when the upstream value changes (e.g.
  // the user cancels edit and a new poll arrives with
  // a different value).
  useEffect(() => {
    if (!editing) setDraft(String(agent.concurrencyQuota));
  }, [agent.concurrencyQuota, editing]);
  const beginEdit = () => {
    setDraft(String(agent.concurrencyQuota));
    setEditing(true);
  };
  const cancelEdit = () => {
    setDraft(String(agent.concurrencyQuota));
    setEditing(false);
  };
  const commit = async () => {
    const n = Number.parseInt(draft.trim(), 10);
    if (!Number.isFinite(n) || n < 0) {
      // invalid input — revert without sending
      cancelEdit();
      return;
    }
    setSaving(true);
    setEditing(false);
    try {
      await rpc.subagentSetQuota({ role: agent.name, quota: n });
      // Brief flash so the user sees "ok, applied". The
      // next dashboard poll will refresh the real
      // value (it can race the flash but that's fine —
      // both display the new value).
      setSavedFlash(Date.now());
      setTimeout(() => setSavedFlash(null), 1200);
    } catch (e) {
      // The dashboard doesn't show a toast; the user's
      // next poll will reflect the unchanged state. A
      // console hint is enough for dev mode.
      console.warn('subagentSetQuota failed', e);
    } finally {
      setSaving(false);
    }
  };
  return (
    <div className={`subagent-agent-card subagent-agent-${agent.circuitState.toLowerCase()}`}>
      <div className="subagent-agent-card-header">
        <span className="subagent-agent-card-name">{agent.name}</span>
        <div className="subagent-agent-card-header-right">
          <CircuitBadge state={agent.circuitState} ms={agent.breakerOpenRemainingMs} />
          <CircuitResetButton role={agent.name} state={agent.circuitState} />
        </div>
      </div>
      <div className="subagent-agent-card-stats">
        <Stat label="run" value={agent.running} tone={agent.running > 0 ? 'accent' : 'dim'} />
        <Stat label="done" value={agent.completed} tone="ok" />
        <Stat label="fail" value={agent.failed} tone={agent.failed > 0 ? 'error' : 'dim'} />
        <Stat label="tokens" value={formatTokens(agent.tokensTotal)} tone="dim" />
      </div>
      <div className="subagent-agent-card-bar">
        <div
          className="subagent-agent-card-bar-fill subagent-agent-card-bar-ok"
          style={{ width: `${pct(agent.completed, total)}%` }}
          title={`completed ${agent.completed}/${total}`}
        />
        <div
          className="subagent-agent-card-bar-fill subagent-agent-card-bar-fail"
          style={{ width: `${pct(agent.failed, total)}%` }}
          title={`failed ${agent.failed}/${total}`}
        />
      </div>
      <div className="subagent-agent-card-footer">
        <span
          className="subagent-agent-card-consecutive"
          title={`consecutive failures — circuit trips at the breaker threshold (default 3)`}
        >
          {agent.consecutiveFailures > 0
            ? `${agent.consecutiveFailures}× fail in a row`
            : 'no recent failures'}
        </span>
        {editing ? (
          // R374.2: inline editor replaces the pill.
          // Enter / blur commits, Escape cancels. A
          // very small input — the user just types a
          // number. We use inputMode="numeric" so the
          // soft keyboard on touch devices suggests
          // digits.
          <input
            className="subagent-agent-card-quota-input"
            type="number"
            inputMode="numeric"
            min={0}
            max={32}
            value={draft}
            autoFocus
            onChange={(e) => setDraft(e.target.value)}
            onBlur={() => { void commit(); }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                void commit();
              } else if (e.key === 'Escape') {
                e.preventDefault();
                cancelEdit();
              }
            }}
            title="Enter to save, Escape to cancel (clamped to 32)"
          />
        ) : (
          <button
            className={`subagent-agent-card-quota subagent-agent-card-quota-button ${saving ? 'subagent-agent-card-quota-saving' : ''} ${savedFlash != null ? 'subagent-agent-card-quota-saved' : ''}`}
            onClick={beginEdit}
            title={`concurrency quota — click to edit (default 1; max 32)`}
          >
            {saving ? 'saving…' : savedFlash != null ? '✓ saved' : `quota ${agent.concurrencyQuota}`}
          </button>
        )}
      </div>
      {failRate > 0 && (
        <div className="subagent-agent-card-fr" title="lifetime failure rate">
          {Math.round(failRate * 100)}% fail
        </div>
      )}
    </div>
  );
}

// tone="dim"        — quiet default
// tone="ok"         — green-tinted (completed count)
// tone="accent"     — blue-tinted (active count)
// tone="warn"       — yellow-tinted (half-open circuit, etc.)
// tone="error"      — red-tinted (failed / open circuit)
function Stat({
  label,
  value,
  tone,
}: {
  label: string;
  value: number | string;
  tone: 'dim' | 'ok' | 'accent' | 'warn' | 'error';
}) {
  return (
    <div className={`subagent-stat subagent-stat-${tone}`}>
      <div className="subagent-stat-value">{value}</div>
      <div className="subagent-stat-label">{label}</div>
    </div>
  );
}

function TotalsPill({
  label,
  value,
  tone,
}: {
  label: string;
  value: number | string;
  tone: 'dim' | 'ok' | 'accent' | 'warn' | 'error';
}) {
  return (
    <div className={`subagent-totals-pill subagent-totals-${tone}`}>
      <span className="subagent-totals-pill-value">{value}</span>
      <span className="subagent-totals-pill-label">{label}</span>
    </div>
  );
}

// CircuitBadge renders the small coloured chip that says
// CLOSED / OPEN / HALF_OPEN + a live countdown when the
// circuit is OPEN. The countdown ticks once per second via
// the same interval used elsewhere in the panel.
function CircuitBadge({
  state,
  ms,
}: {
  state: 'CLOSED' | 'OPEN' | 'HALF_OPEN';
  ms: number;
}) {
  const [, setTick] = useState(0);
  useEffect(() => {
    if (state !== 'OPEN') return;
    const t = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, [state]);
  return (
    <span className={`subagent-circuit subagent-circuit-${state.toLowerCase()}`}>
      <span className="subagent-circuit-dot" />
      {state === 'OPEN'
        ? `OPEN ${formatCountdown(ms)}`
        : state === 'HALF_OPEN'
          ? 'HALF-OPEN'
          : 'CLOSED'}
    </span>
  );
}

// R374.3: a tiny "Reset" button that sits next to the
// circuit chip when the state is OPEN or HALF_OPEN.
// The user has fixed the underlying cause and wants
// to short-circuit the 60s cooldown. The button calls
// subagentResetCircuit; the next dashboard poll shows
// the chip turn CLOSED.
function CircuitResetButton({
  role,
  state,
}: {
  role: string;
  state: 'CLOSED' | 'OPEN' | 'HALF_OPEN';
}) {
  // The button only renders when the circuit is
  // tripped. CLOSED cards have nothing to reset.
  if (state === 'CLOSED') return null;
  const [busy, setBusy] = useState(false);
  const [flash, setFlash] = useState<'ok' | 'fail' | null>(null);
  const onClick = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const r = await rpc.subagentResetCircuit({ role });
      // Show a brief "✓ reset" pill; the next poll will
      // see the chip turn CLOSED automatically (the
      // dashboard's polling refresh takes care of
      // that — we don't optimistically flip the
      // parent's circuitState).
      setFlash(r.cleared ? 'ok' : 'fail');
      setTimeout(() => setFlash(null), 1200);
    } catch (e) {
      setFlash('fail');
      setTimeout(() => setFlash(null), 1200);
      console.warn('subagentResetCircuit failed', e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <button
      className={`subagent-circuit-reset subagent-circuit-reset-${flash ?? 'ok'}`}
      onClick={onClick}
      disabled={busy}
      title={`force-clear the circuit breaker for ${role} (skip the 60s cooldown)`}
    >
      {busy ? 'resetting…' : flash === 'ok' ? '✓ reset' : 'Reset circuit'}
    </button>
  );
}

// 3-digit grouping for >=1k tokens. We don't roll up to
// "k"/"M" because the user wants exact usage when reading
// off a budget; rolling up makes it harder to estimate
// "how much more can I run".
function formatTokens(n: number): string {
  if (!Number.isFinite(n) || n <= 0) return '0';
  if (n < 1000) return String(n);
  return n.toLocaleString('en-US');
}

function formatRelative(ms: number): string {
  if (!Number.isFinite(ms)) return '—';
  const dt = Date.now() - ms;
  if (dt < 0) return 'now';
  if (dt < 60_000) return `${Math.floor(dt / 1000)}s ago`;
  return `${Math.floor(dt / 60_000)}m ago`;
}

function formatCountdown(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '0s';
  if (ms < 60_000) return `${Math.ceil(ms / 1000)}s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.ceil((ms % 60_000) / 1000);
  return `${m}m${s.toString().padStart(2, '0')}s`;
}

function pct(part: number, total: number): number {
  if (!Number.isFinite(part) || part <= 0) return 0;
  if (!Number.isFinite(total) || total <= 0) return 0;
  return Math.max(0, Math.min(100, Math.round((part / total) * 100)));
}

// shallowEq compares the dashboard fields the user can see
// without descending into the agents array — if the totals
// didn't move and the agent name list didn't change, we
// skip the re-render. Token counts are always changing
// under load so the optimisation helps more in idle.
function shallowEq(
  a: SubagentDashboardSnapshot,
  b: SubagentDashboardSnapshot,
): boolean {
  if (a.asOfMs === b.asOfMs) return true;
  const ta = a.totals;
  const tb = b.totals;
  if (
    ta.running !== tb.running ||
    ta.completed !== tb.completed ||
    ta.failed !== tb.failed ||
    ta.tokensTotal !== tb.tokensTotal ||
    ta.circuitOpen !== tb.circuitOpen ||
    ta.circuitHalfOpen !== tb.circuitHalfOpen
  ) return false;
  if (a.agents.length !== b.agents.length) return false;
  for (let i = 0; i < a.agents.length; i++) {
    const x = a.agents[i];
    const y = b.agents[i];
    if (
      x.name !== y.name ||
      x.circuitState !== y.circuitState ||
      x.tokensTotal !== y.tokensTotal ||
      x.running !== y.running ||
      x.completed !== y.completed ||
      x.failed !== y.failed
    ) return false;
  }
  return true;
}