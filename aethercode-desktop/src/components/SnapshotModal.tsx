import { useEffect, useState } from 'react';
import { rpc } from '../lib/methods';
import { useRpc } from '../rpc/queries';
import { JsonRpcClient } from '../rpc/client';
import './SnapshotModal.css';

/**
 * R284: a single original message as the daemon emits it
 * through {@code compact/getSnapshot}. The shape is the
 * same {@code Message.toMap()} JSON — id, role, content
 * (array of {@code ContentBlock} maps), timestamp,
 * metadata. We type it loosely because the renderer
 * only walks the text content.
 */
export type SnapshotMessage = Record<string, unknown>;

/** R284: resolve the JsonRpcClient the modal should
 *  talk to. Tests inject a mock-instrumented client
 *  via {@link JsonRpcClientContext}; production paths
 *  fall through to {@link rpc} which carries the
 *  singleton Tauri-backed client. The {@link useRpc}
 *  helper from {@link rpc/queries} wraps the
 *  React-Context lookup; calling it from a child of the
 *  RpcProvider (e.g. under {@link TestProviders})
 *  returns the test's mock client. */
function useModalRpc(): { compactGetSnapshot: typeof rpc.compactGetSnapshot } {
  const client: JsonRpcClient | undefined = useRpc();
  // if a test passed a mock client via the RpcProvider,
  // dispatch the call through it (we can't reuse the
  // production `rpc` singleton — its Tauri invoke
  // would fail in jsdom). The mock client's
  // JsonRpcClient.call wraps the MockRpcServer handlers.
  if (client) {
    return {
      compactGetSnapshot: (opts) =>
        client.call('compact/getSnapshot', {
          sessionId: opts.sessionId ?? null,
          compactionIndex: opts.compactionIndex,
        }) as ReturnType<typeof rpc.compactGetSnapshot>,
    };
  }
  return { compactGetSnapshot: rpc.compactGetSnapshot };
}

/**
 * R284: "View original context" modal. Opened from
 * MessageList when the user clicks the affordance on a
 * compaction-summary message. The modal fetches the
 * pre-compaction snapshot via {@code compact/getSnapshot}
 * and renders each original message in a flat scrollable
 * list (we don't try to reconstruct the rich sub-task /
 * step UI from the snapshot — the user just wants to read
 * what was summarised, not re-play the run).
 *
 * <p>The shape mirrors the existing ToolPermissionPrompter
 * modal — dark backdrop, centred card, ESC to close,
 * clicking the backdrop closes. State is intentionally
 * minimal: we're not trying to be a transcript editor.
 */
export interface SnapshotModalProps {
  open: boolean;
  /** id of the session the snapshot belongs to.
   *  When omitted, defaults to the engine's active
   *  session on the daemon side. */
  sessionId?: string;
  /** 0-based monotonic index of the compaction this
   *  snapshot was written for. The summary-message
   *  metadata's {@code compactionIndex} carries it. */
  compactionIndex: number;
  /** The summary text — rendered at the top of the
   *  modal so the user can compare the original
   *  context with the model-written synthesis. */
  summaryPreview?: string;
  onClose: () => void;
}

export function SnapshotModal(props: SnapshotModalProps) {
  const { open, sessionId, compactionIndex, summaryPreview, onClose } = props;
  const { compactGetSnapshot } = useModalRpc();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [messages, setMessages] = useState<SnapshotMessage[] | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);

  // fetch when the modal opens. We re-issue the RPC on
  // every open so the user can revisit the same
  // snapshot after the daemon has written more — the
  // snapshot file is read-only on disk so the response
  // is stable, but the surface keeps the
  // data-fresh-on-open behaviour the rest of the
  // renderer relies on.
  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    setMessages(null);
    compactGetSnapshot({ sessionId, compactionIndex })
      .then((resp) => {
        if (cancelled) return;
        if (!resp.ok) {
          setError(`snapshot ${compactionIndex} not found`);
          return;
        }
        setMessages(resp.snapshot.messages ?? []);
        setFileName(resp.snapshot.fileName ?? null);
      })
      .catch((e) => {
        if (cancelled) return;
        setError(e?.message ?? 'failed to load snapshot');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, sessionId, compactionIndex]);

  // ESC to close.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div
      className="snapshot-modal-backdrop"
      role="dialog"
      aria-modal="true"
      aria-label="View original context"
      data-testid="snapshot-modal"
      onClick={onClose}
    >
      <div
        className="snapshot-modal-card"
        onClick={(e) => e.stopPropagation()}
        data-testid="snapshot-modal-card"
      >
        <header className="snapshot-modal-header">
          <div className="snapshot-modal-title-row">
            <span className="snapshot-modal-icon" aria-hidden>↺</span>
            <h2 className="snapshot-modal-title">View original context</h2>
          </div>
          <button
            type="button"
            className="snapshot-modal-close"
            onClick={onClose}
            aria-label="Close"
            data-testid="snapshot-modal-close"
          >
            ×
          </button>
        </header>
        <div className="snapshot-modal-meta">
          <span className="snapshot-modal-pill">
            compaction #{compactionIndex}
          </span>
          {fileName && (
            <span className="snapshot-modal-filename" title={fileName}>
              {fileName}
            </span>
          )}
        </div>
        {summaryPreview && (
          <section
            className="snapshot-modal-summary"
            data-testid="snapshot-modal-summary"
          >
            <h3>Summary (what the model saw)</h3>
            <pre>{summaryPreview}</pre>
          </section>
        )}
        <section className="snapshot-modal-body">
          <h3>Original context ({messages?.length ?? 0} msgs)</h3>
          {loading && (
            <div className="snapshot-modal-status" data-testid="snapshot-modal-loading">
              loading…
            </div>
          )}
          {error && (
            <div className="snapshot-modal-status snapshot-modal-error" data-testid="snapshot-modal-error">
              {error}
            </div>
          )}
          {!loading && !error && messages && (
            <ol className="snapshot-modal-messages" data-testid="snapshot-modal-messages">
              {messages.map((m, i) => (
                <li key={String(m.id ?? i)} className="snapshot-modal-message">
                  <div className="snapshot-modal-message-head">
                    <span className="snapshot-modal-message-role">
                      {String(m.role ?? 'unknown')}
                    </span>
                    <span className="snapshot-modal-message-idx">#{i + 1}</span>
                  </div>
                  <pre className="snapshot-modal-message-body">
                    {firstTextOf(m) || '(empty)'}
                  </pre>
                </li>
              ))}
            </ol>
          )}
        </section>
      </div>
    </div>
  );
}

/** Walk the message map's content[0].text (the same
 *  shape Message.toMap uses). Returns the joined text
 *  if there are multiple text blocks. */
function firstTextOf(msg: Record<string, unknown>): string {
  const content = msg.content;
  if (!Array.isArray(content) || content.length === 0) return '';
  const parts: string[] = [];
  for (const block of content) {
    if (
      block && typeof block === 'object'
      && (block as Record<string, unknown>).type === 'text'
      && typeof (block as Record<string, unknown>).text === 'string'
    ) {
      parts.push((block as Record<string, unknown>).text as string);
    }
  }
  return parts.join('');
}