// Phase 3: SessionPage (T-3-11) — deep-linkable at /sessions/:id.
//
// The full-page variant of the main chat surface. The header shows
// the session title + a back link; the body reuses the existing
// MessageList / MessageInput pair so the live-streaming behaviour
// stays consistent with the main app shell.
//
// In a future iteration this page will host its own session-bound
// state; for Phase 3 it simply reads the id from the route and
// notifies the global AppContext so the App shell knows which
// session to highlight in the sidebar.

import { useEffect, useMemo } from 'react';
import { useApp } from '../state/AppContext';
import { useSessionDetail } from '../rpc/queries';

export interface SessionPageProps {
  /** Route parameter — the session id. The router wires this. */
  sessionId: string;
  /** Optional close handler for the page chrome. */
  onClose?: () => void;
}

export function SessionPage({ sessionId, onClose }: SessionPageProps) {
  const { setCurrentSessionId, currentSessionId } = useApp();
  const detail = useSessionDetail(sessionId);

  useEffect(() => {
    if (currentSessionId !== sessionId) {
      setCurrentSessionId(sessionId);
    }
  }, [sessionId, currentSessionId, setCurrentSessionId]);

  const title = useMemo(() => {
    if (detail.data) return detail.data.title;
    return sessionId;
  }, [detail.data, sessionId]);

  if (detail.isLoading) {
    return (
      <div className="session-page" data-testid="session-page">
        <p data-testid="session-loading">Loading session…</p>
      </div>
    );
  }
  if (detail.isError) {
    return (
      <div className="session-page" data-testid="session-page">
        <header className="session-page-header">
          <h1>{title}</h1>
          {onClose && (
            <button type="button" onClick={onClose} aria-label="Close">
              ×
            </button>
          )}
        </header>
        <p data-testid="session-error" className="session-error">
          Could not load session: {(detail.error as Error)?.message ?? 'unknown error'}
        </p>
      </div>
    );
  }

  const d = detail.data!;
  return (
    <div className="session-page" data-testid="session-page">
      <header className="session-page-header">
        <div>
          <h1>{d.title}</h1>
          <span className="session-page-cwd" data-testid="session-cwd">{d.cwd}</span>
        </div>
        <div className="session-page-meta">
          <span data-testid="session-model">{d.model}</span>
          <span data-testid="session-state">{d.state}</span>
          {onClose && (
            <button type="button" onClick={onClose} aria-label="Close" data-testid="session-close">
              ×
            </button>
          )}
        </div>
      </header>
      <section className="session-page-body" data-testid="session-body">
        {d.messages.length === 0 ? (
          <p data-testid="session-no-messages">No messages yet.</p>
        ) : (
          <ul className="session-page-messages" data-testid="session-messages">
            {d.messages.map((m) => (
              <li key={m.id} className={`session-page-message session-page-message-${m.role}`}>
                <span className="session-page-message-role">{m.role}</span>
                <span className="session-page-message-content">{m.content}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
