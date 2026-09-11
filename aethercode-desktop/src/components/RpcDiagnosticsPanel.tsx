import { useEffect, useMemo, useState } from 'react';
import { useStore } from '../store';
import type { RpcEvent } from '../lib/methods';
import { invoke } from '@tauri-apps/api/core';
import { save as saveDialog } from '@tauri-apps/plugin-dialog';
import './RpcDiagnosticsPanel.css';

/**
 * RPC diagnostics panel. Renders the last 50
 * RPC round-trips (method, params, duration, status,
 * timestamp) with a filter row at the top (method
 * substring + status filter) and an expandable
 * payload on each row. Opened via the keyboard
 * shortcut Ctrl/Cmd+` (mounted in the App shell)
 * and from a button in the Settings panel header
 * for users who'd rather click.
 *
 * Design choices:
 *  - Newest-first (the store pushes to the front
 *    on every event, so render() can iterate
 *    without a sort).
 *  - Status filter is a 3-state toggle: all / ok /
 *    error. The "error" filter is the one users
 *    reach for first when something is wrong, so it
 *    sits on the left.
 *  - The payload cell is collapsed by default and
 *    expands inline on click. A query with a 200-
 *    field payload would dominate the panel if it
 *    expanded on every render.
 *  - The clear button is a "Clear" <button> at the
 *    panel footer; it calls store.clearRpcEvents
 *    and the panel re-renders with the empty list.
 *  - Esc closes the panel (the same prior round
 *    persistent-UI affordance pattern).
 *
 * The panel reads `recentRpcEvents` directly from
 * the store (zustand selector). New events append
 * to the front; the panel's effect on the events
 * length is to scroll the inner list to the top
 * (the user just sent an RPC, they want to see it).
 */

const ALL = 'all';
const OK = 'ok';
const ERR = 'error';

type StatusFilter = typeof ALL | typeof OK | typeof ERR;

export function RpcDiagnosticsPanel({ onClose }: { onClose: () => void }) {
  const { recentRpcEvents, clearRpcEvents } = useStore();
  const [filter, setFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>(ALL);
  // the set of expanded row indices. The
  // index is the position in the rendered (post-
  // filter) list, not the index in the raw event
  // array, so toggling survives a re-filter.
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  // a transient status message after the
  // user clicks Export — "wrote 50 events to …"
  // or "export failed: <reason>". Cleared when
  // the user changes the filter or closes the
  // panel; lives at most a few seconds so the
  // user has feedback without a sticky banner.
  const [exportStatus, setExportStatus] = useState<string | null>(null);

  // Esc closes the panel. Mounted for the
  // lifetime of the panel (no conditional gate —
  // the panel only mounts when open).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  // apply the filters. methodFilter is a
  // case-insensitive substring match; statusFilter
  // is exact. The filter runs on every render but
  // with a 50-element cap the cost is negligible.
  const filtered = useMemo(() => {
    const f = filter.toLowerCase();
    return recentRpcEvents.filter((e) => {
      if (statusFilter === OK && !e.success) return false;
      if (statusFilter === ERR && e.success) return false;
      if (f && !e.method.toLowerCase().includes(f)) return false;
      return true;
    });
  }, [recentRpcEvents, filter, statusFilter]);

  // count badges for the status-filter chips.
  // The user picks the filter that matches what
  // they're looking for; the count tells them how
  // many rows are in each bucket without having to
  // switch filters.
  const counts = useMemo(() => {
    let ok = 0, err = 0;
    for (const e of recentRpcEvents) {
      if (e.success) ok++;
      else err++;
    }
    return { all: recentRpcEvents.length, ok, err };
  }, [recentRpcEvents]);

  return (
    <div className="rpc-diag-overlay" onClick={onClose}>
      <div className="rpc-diag-panel" onClick={(e) => e.stopPropagation()}>
        <div className="rpc-diag-header">
          <h2>RPC diagnostics</h2>
          <span className="rpc-diag-count">
            {recentRpcEvents.length} event{recentRpcEvents.length === 1 ? '' : 's'}
            {filter || statusFilter !== ALL ? (
              <> · showing {filtered.length} after filter</>
            ) : null}
          </span>
          <button className="rpc-diag-close" onClick={onClose} title="Close (Esc)">×</button>
        </div>
        <div className="rpc-diag-toolbar">
          <input
            className="rpc-diag-filter"
            type="text"
            placeholder="Filter by method name…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            autoFocus
          />
          <div className="rpc-diag-status-toggle" role="group" aria-label="Status filter">
            <button
              className={`rpc-diag-status-btn ${statusFilter === ALL ? 'is-active' : ''}`}
              onClick={() => setStatusFilter(ALL)}
            >all <span className="rpc-diag-status-count">{counts.all}</span></button>
            <button
              className={`rpc-diag-status-btn rpc-diag-status-ok ${statusFilter === OK ? 'is-active' : ''}`}
              onClick={() => setStatusFilter(OK)}
            >ok <span className="rpc-diag-status-count">{counts.ok}</span></button>
            <button
              className={`rpc-diag-status-btn rpc-diag-status-err ${statusFilter === ERR ? 'is-active' : ''}`}
              onClick={() => setStatusFilter(ERR)}
            >err <span className="rpc-diag-status-count">{counts.err}</span></button>
          </div>
        </div>
        <div className="rpc-diag-table">
          {filtered.length === 0 ? (
            <div className="rpc-diag-empty">
              {recentRpcEvents.length === 0
                ? 'No RPC events recorded yet. The store starts an empty buffer; events arrive as the renderer talks to the daemon.'
                : 'No events match the current filter.'}
            </div>
          ) : (
            filtered.map((e, i) => (
              <RpcEventRow
                key={i}
                event={e}
                expanded={expanded.has(i)}
                onToggle={() => {
                  setExpanded((cur) => {
                    const next = new Set(cur);
                    if (next.has(i)) next.delete(i);
                    else next.add(i);
                    return next;
                  });
                }}
              />
            ))
          )}
        </div>
        <div className="rpc-diag-footer">
          <span className="rpc-diag-footer-hint">
            new events appear at the top · click a row to see its params · Esc to close
          </span>
          {exportStatus && (
            // a transient export-result
            // message. Sits in the footer
            // because the action is footer-
            // triggered; lives next to the
            // Export / Clear buttons so the
            // cause → effect is obvious.
            <span className={`rpc-diag-export-status ${exportStatus.startsWith('✗') ? 'is-err' : 'is-ok'}`}>
              {exportStatus}
            </span>
          )}
          <button
            className="rpc-diag-export"
            // export the current (post-
            // filter) view, not the raw buffer.
            // The user has already narrowed to
            // "the 8 errors in the last minute";
            // exporting the unfiltered 50 would
            // defeat the point of the filter.
            onClick={() => void exportToJsonl(filtered, setExportStatus)}
            disabled={filtered.length === 0}
            title="Export the current filtered view to a .jsonl file"
          >
            Export ({filtered.length})
          </button>
          <button
            className="rpc-diag-export-report"
            // a markdown report — for the
            // user who'd rather read a summary
            // than grep a .jsonl. The report
            // includes a count summary, status
            // breakdown, duration percentiles
            // (p50/p95/p99), the 5 slowest
            // calls, and the 5 most-common
            // errors. Same write path as the
            // JSONL export (Rust Tauri command
            // + OS save dialog); distinct
            // extension (.md) so the OS picks
            // the right default app.
            onClick={() => void exportMarkdownReport(filtered, setExportStatus)}
            disabled={filtered.length === 0}
            title="Export a human-readable markdown report of the current view"
          >
            Report
          </button>
          <button
            className="rpc-diag-clear"
            onClick={() => clearRpcEvents()}
            disabled={recentRpcEvents.length === 0}
            title="Clear all recorded events"
          >
            Clear
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * export the current filtered view to a
 * .jsonl file. The user picks the destination
 * via a Tauri save dialog (the OS's native
 * dialog, not a custom one — keeps the trust
 * boundary with the file system on the OS
 * side). The Rust side writes the file via a
 * dedicated `write_text_file` command (a 10-
 * line Tauri command beats pulling in
 * tauri-plugin-fs + a new capability grant
 * for one tiny write).
 *
 * <p>One event per line. The schema is the
 * same as the in-memory `RpcEvent` shape so a
 * user who later writes a jq filter against
 * the file gets the same fields they see in
 * the panel: `{ method, params, durationMs,
 * success, error?, ts }`.
 */
async function exportToJsonl(
  events: RpcEvent[],
  setStatus: (s: string) => void,
): Promise<void> {
  // Suggest a timestamped filename in the
  // user's default download location. The
  // browser-style "rpc-events-2026-08-19T17-23.jsonl"
  // makes a session's exports self-labelling
  // when they pile up in a folder.
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const stamp = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}_${pad(now.getHours())}-${pad(now.getMinutes())}-${pad(now.getSeconds())}`;
  const suggested = `aethercode-rpc-events-${stamp}.jsonl`;
  let path: string | null;
  try {
    path = await saveDialog({
      title: 'Export RPC events',
      defaultPath: suggested,
      filters: [
        { name: 'JSON Lines', extensions: ['jsonl'] },
        { name: 'All Files', extensions: ['*'] },
      ],
    });
  } catch (e) {
    setStatus(`✗ save dialog failed: ${(e as Error).message}`);
    return;
  }
  if (!path) {
    // The user cancelled the dialog. Don't
    // show an error — a silent no-op is the
    // expected behaviour for "I changed my
    // mind".
    return;
  }
  // Build the .jsonl body. One event per
  // line, no trailing newline beyond the
  // last. JSON.stringify with no replacer
  // is fine because RpcEvent has only
  // JSON-safe values (string, number,
  // boolean, unknown-which-is-the-original-
  // params-object-already-serialised).
  const body = events.map((e) => JSON.stringify({
    method: e.method,
    params: e.params,
    durationMs: e.durationMs,
    success: e.success,
    ...(e.error ? { error: e.error } : {}),
    ts: e.ts,
  })).join('\n') + '\n';
  try {
    await invoke<void>('write_text_file', { path, contents: body });
    setStatus(`✓ wrote ${events.length} event${events.length === 1 ? '' : 's'} to ${path}`);
  } catch (e) {
    setStatus(`✗ export failed: ${(e as Error).message ?? String(e)}`);
  }
}

/**
 * export a human-readable markdown report
 * of the current filtered view. Same write path
 * as `exportToJsonl` (Rust Tauri command +
 * OS save dialog), different content shape
 * (.md) and different audience — the user who
 * would rather read a summary than grep a
 * JSONL.
 *
 * <p>Sections in the report:
 * <ol>
 *   <li>Header (timestamp + filter context).</li>
 *   <li>Summary (count, ok/error split, time
 *       range).</li>
 *   <li>Duration stats (mean + p50/p95/p99
 *       percentiles).</li>
 *   <li>Slowest 5 calls (table).</li>
 *   <li>Top 5 error messages (table — useful
 *       for "is this the same error 50 times
 *       or 5 different ones").</li>
 * </ol>
 *
 * <p>The percentiles are computed on the
 * full durationMs array (not a sample), so
 * the values are exact for the user's view.
 * For a 50-event buffer that's a 50-element
 * sort — well under a millisecond.
 */
async function exportMarkdownReport(
  events: RpcEvent[],
  setStatus: (s: string) => void,
): Promise<void> {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const stamp = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}_${pad(now.getHours())}-${pad(now.getMinutes())}-${pad(now.getSeconds())}`;
  const suggested = `aethercode-rpc-report-${stamp}.md`;
  let path: string | null;
  try {
    path = await saveDialog({
      title: 'Export RPC report (markdown)',
      defaultPath: suggested,
      filters: [
        { name: 'Markdown', extensions: ['md'] },
        { name: 'All Files', extensions: ['*'] },
      ],
    });
  } catch (e) {
    setStatus(`✗ save dialog failed: ${(e as Error).message}`);
    return;
  }
  if (!path) return; // silent no-op on cancel
  const body = renderMarkdownReport(events);
  try {
    await invoke<void>('write_text_file', { path, contents: body });
    setStatus(`✓ wrote report to ${path}`);
  } catch (e) {
    setStatus(`✗ report failed: ${(e as Error).message ?? String(e)}`);
  }
}

/** build the markdown body. Split out
 *  from the I/O wrapper so the test can pin
 *  the content shape without mocking the
 *  save dialog. Pure function: same input
 *  → same output. */
function renderMarkdownReport(events: RpcEvent[]): string {
  const lines: string[] = [];
  const generated = new Date();
  lines.push('# AetherCode RPC report');
  lines.push('');
  lines.push(`Generated: ${generated.toISOString()}`);
  lines.push(`Events: ${events.length}`);
  lines.push('');

  // --- Summary -----------------------------------------------------------
  const okCount = events.filter((e) => e.success).length;
  const errCount = events.length - okCount;
  const okPct = events.length > 0 ? Math.round((okCount / events.length) * 100) : 0;
  lines.push('## Summary');
  lines.push('');
  lines.push(`- **OK**: ${okCount} (${okPct}%)`);
  lines.push(`- **Errors**: ${errCount} (${100 - okPct}%)`);
  if (events.length > 0) {
    const tsSorted = [...events].sort((a, b) => a.ts - b.ts);
    const first = tsSorted[0].ts;
    const last = tsSorted[tsSorted.length - 1].ts;
    const spanMs = last - first;
    lines.push(`- **Time range**: ${new Date(first).toISOString()} → ${new Date(last).toISOString()}`);
    lines.push(`- **Span**: ${formatDuration(spanMs)}`);
  }
  lines.push('');

  // --- Duration stats ----------------------------------------------------
  if (events.length > 0) {
    const durations = events.map((e) => e.durationMs).sort((a, b) => a - b);
    const sum = durations.reduce((a, b) => a + b, 0);
    const mean = sum / durations.length;
    const p = (q: number) => durations[Math.min(durations.length - 1, Math.floor(q * durations.length))];
    lines.push('## Duration');
  lines.push('');
    lines.push(`- **Mean**: ${formatDuration(Math.round(mean))}`);
    lines.push(`- **p50**: ${formatDuration(p(0.50))}`);
    lines.push(`- **p95**: ${formatDuration(p(0.95))}`);
    lines.push(`- **p99**: ${formatDuration(p(0.99))}`);
    lines.push(`- **Max**: ${formatDuration(durations[durations.length - 1])}`);
    lines.push('');
  }

  // --- Slowest 5 calls ----------------------------------------------------
  const slowest = [...events].sort((a, b) => b.durationMs - a.durationMs).slice(0, 5);
  if (slowest.length > 0) {
    lines.push('## Slowest 5 calls');
    lines.push('');
    lines.push('| Method | Duration | Status | Time |');
    lines.push('| --- | --- | --- | --- |');
    for (const e of slowest) {
      lines.push(`| \`${e.method}\` | ${formatDuration(e.durationMs)} | ${e.success ? '✓' : '✗'} | ${new Date(e.ts).toISOString()} |`);
    }
    lines.push('');
  }

  // --- Top 5 errors -------------------------------------------------------
  const errors = events.filter((e) => !e.success);
  if (errors.length > 0) {
    // Bucket by (method, error-message) so
    // "50 instances of the same X" surfaces
    // as one row instead of 50.
    const buckets = new Map<string, { count: number; lastTs: number; sample: RpcEvent }>();
    for (const e of errors) {
      const key = `${e.method}\u0001${e.error ?? '(no error message)'}`;
      const cur = buckets.get(key);
      if (cur) {
        cur.count++;
        if (e.ts > cur.lastTs) {
          cur.lastTs = e.ts;
          cur.sample = e;
        }
      } else {
        buckets.set(key, { count: 1, lastTs: e.ts, sample: e });
      }
    }
    const top = [...buckets.values()].sort((a, b) => b.count - a.count).slice(0, 5);
    lines.push('## Top errors');
    lines.push('');
    lines.push('| Method | Count | Last seen | Sample error |');
    lines.push('| --- | --- | --- | --- |');
    for (const b of top) {
      const errMsg = (b.sample.error ?? '(no error message)').replace(/\|/g, '\\|').slice(0, 80);
      lines.push(`| \`${b.sample.method}\` | ${b.count} | ${new Date(b.lastTs).toISOString()} | ${errMsg} |`);
    }
    lines.push('');
  }

  return lines.join('\n');
}

/** human-friendly duration. < 1s shows
 *  ms; >= 1s shows seconds with 2 decimals.
 *  The split is the "stats page" convention
 *  (Datadog, Grafana all do the same). */
function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function RpcEventRow({
  event,
  expanded,
  onToggle,
}: {
  event: RpcEvent;
  expanded: boolean;
  onToggle: () => void;
}) {
  return (
    <div
      className={`rpc-diag-row ${event.success ? 'rpc-diag-row-ok' : 'rpc-diag-row-err'}`}
      onClick={onToggle}
    >
      <div className="rpc-diag-row-head">
        <span className="rpc-diag-row-status">{event.success ? '✓' : '✗'}</span>
        <span className="rpc-diag-row-method">{event.method}</span>
        <span className="rpc-diag-row-time">{formatRelative(event.ts)}</span>
        <span
          className={`rpc-diag-row-duration ${event.durationMs > 500 ? 'is-slow' : ''}`}
          title="round-trip duration"
        >
          {event.durationMs}ms
        </span>
      </div>
      {expanded && (
        <div className="rpc-diag-row-body" onClick={(e) => e.stopPropagation()}>
          <div className="rpc-diag-row-section">
            <div className="rpc-diag-row-section-label">params</div>
            <pre className="rpc-diag-row-payload">
              {formatJson(event.params)}
            </pre>
          </div>
          {!event.success && event.error && (
            <div className="rpc-diag-row-section">
              <div className="rpc-diag-row-section-label">error</div>
              <pre className="rpc-diag-row-payload rpc-diag-row-payload-error">
                {event.error}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function formatRelative(ts: number): string {
  const age = Date.now() - ts;
  if (age < 0) return 'just now';
  if (age < 1000) return 'just now';
  if (age < 60_000) return `${Math.round(age / 1000)}s ago`;
  if (age < 3_600_000) return `${Math.round(age / 60_000)}m ago`;
  return `${Math.round(age / 3_600_000)}h ago`;
}

function formatJson(v: unknown): string {
  if (v == null) return '(none)';
  if (typeof v === 'string' && v === '[unserialisable]') {
    return '[unserialisable — params contained a function or circular ref]';
  }
  try {
    return JSON.stringify(v, null, 2);
  } catch {
    return String(v);
  }
}
