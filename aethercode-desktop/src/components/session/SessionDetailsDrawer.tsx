// SessionDetailsDrawer.
//
// Renders the key metadata for the active session: id (copyable), cwd (copyable),
// model and effort, started / last-active timestamps, parent session, token usage,
// and TODO progress, plus entries that jump to the diff / events views.

import { useState } from 'react';
import { useSessionDetail } from '../../rpc/queries';
import { useApp } from '../../state/AppContext';
import './SessionDetailsDrawer.css';

export interface SessionDetailsDrawerProps {
  sessionId: string | null;
  /** Explicit open flag; when `undefined`, falls back to "open if a session is set", for compatibility with older callers that don't pass it. */
  open?: boolean;
  onClose: () => void;
  /** Callback to jump to the events view, injected by the page that hosts the drawer. */
  onOpenEvents?: (sessionId: string) => void;
  /** Callback to jump to the file-diffs view. */
  onOpenFileDiffs?: (sessionId: string) => void;
}

export function SessionDetailsDrawer({
  sessionId,
  open: openProp,
  onClose,
  onOpenEvents,
  onOpenFileDiffs,
}: SessionDetailsDrawerProps) {
  const detail = useSessionDetail(sessionId);
  const { setDetailsDrawerOpen } = useApp();
  const [copied, setCopied] = useState<'id' | 'cwd' | null>(null);

  // The prop takes precedence; when it isn't provided, fall back to "open if a session is set".
  const open = openProp ?? (sessionId !== null);

  return (
    <aside
      className={`session-details-drawer ${open ? 'open' : 'closed'}`}
      data-testid="session-details-drawer"
      data-open={open}
      aria-hidden={!open}
    >
      <header className="session-details-header">
        <h2>Session details</h2>
        <button
          type="button"
          aria-label="Close details drawer"
          data-testid="session-details-close"
          onClick={() => { setDetailsDrawerOpen(false); onClose(); }}
        >
          ×
        </button>
      </header>
      {open && (detail.isLoading ? (
        <p data-testid="session-details-loading">Loading…</p>
      ) : detail.isError || !detail.data ? (
        <p data-testid="session-details-error">
          Could not load session: {(detail.error as Error)?.message ?? 'unknown'}
        </p>
      ) : (
        <SessionDetailsBody
          d={detail.data}
          onCopy={(field) => {
            if (!navigator?.clipboard) return;
            const value = field === 'id' ? detail.data!.id : detail.data!.cwd;
            void navigator.clipboard.writeText(value).then(() => {
              setCopied(field);
              setTimeout(() => setCopied((c) => (c === field ? null : c)), 1500);
            });
          }}
          copied={copied}
          onOpenEvents={onOpenEvents}
          onOpenFileDiffs={onOpenFileDiffs}
        />
      ))}
    </aside>
  );
}

function fmtDate(ts: number | null | undefined): string {
  if (!ts) return '—';
  try { return new Date(ts).toLocaleString(); } catch { return String(ts); }
}

function SessionDetailsBody({
  d,
  onCopy,
  copied,
  onOpenEvents,
  onOpenFileDiffs,
}: {
  d: NonNullable<ReturnType<typeof useSessionDetail>['data']>;
  onCopy: (field: 'id' | 'cwd') => void;
  copied: 'id' | 'cwd' | null;
  onOpenEvents?: (sessionId: string) => void;
  onOpenFileDiffs?: (sessionId: string) => void;
}) {
  const completed = d.todos.filter((t) => t.status === 'completed').length;
  const total = d.todos.length;
  return (
    <div className="session-details-body" data-testid="session-details-body">
      <section className="details-row" data-testid="details-row-id">
        <label>id</label>
        <code data-testid="details-id">{d.id}</code>
        <button
          type="button"
          data-testid="details-copy-id"
          onClick={() => onCopy('id')}
        >
          {copied === 'id' ? 'Copied' : 'Copy'}
        </button>
      </section>
      <section className="details-row" data-testid="details-row-cwd">
        <label>cwd</label>
        <code data-testid="details-cwd">{d.cwd}</code>
        <button
          type="button"
          data-testid="details-copy-cwd"
          onClick={() => onCopy('cwd')}
        >
          {copied === 'cwd' ? 'Copied' : 'Copy'}
        </button>
      </section>
      <section className="details-row" data-testid="details-row-model">
        <label>model</label>
        <span data-testid="details-model">{d.model}</span>
        <span className="details-effort" data-testid="details-effort">{d.effort}</span>
      </section>
      <section className="details-row" data-testid="details-row-time">
        <label>started</label>
        <span data-testid="details-started">{fmtDate(d.startedAt)}</span>
        <label>last active</label>
        <span data-testid="details-last-active">{fmtDate(d.lastActiveAt)}</span>
      </section>
      {d.parentId && (
        <section className="details-row" data-testid="details-row-parent">
          <label>parent</label>
          <code data-testid="details-parent">{d.parentId}</code>
        </section>
      )}
      <section className="details-row" data-testid="details-row-tokens">
        <label>tokens</label>
        <span data-testid="details-tokens-in">{d.tokensIn.toLocaleString()} in</span>
        <span data-testid="details-tokens-out">{d.tokensOut.toLocaleString()} out</span>
      </section>
      <section className="details-row" data-testid="details-row-todos">
        <label>todos</label>
        <span data-testid="details-todos">
          {completed}/{total} completed
        </span>
      </section>
      <section className="details-row" data-testid="details-row-state">
        <label>state</label>
        <span data-testid="details-state">{d.state}</span>
      </section>
      <section className="details-row details-row-actions" data-testid="details-row-actions">
        <button
          type="button"
          data-testid="details-open-diffs"
          disabled={!onOpenFileDiffs}
          onClick={() => onOpenFileDiffs?.(d.id)}
        >
          Show file diffs
        </button>
        <button
          type="button"
          data-testid="details-open-events"
          disabled={!onOpenEvents}
          onClick={() => onOpenEvents?.(d.id)}
        >
          Show events
        </button>
      </section>
    </div>
  );
}
