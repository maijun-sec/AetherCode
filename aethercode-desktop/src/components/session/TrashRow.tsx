// Phase 4.1 (T-4-07): TrashRow.
//
// One row in the trash list. Two actions: restore (moves the
// session back to the live list) and delete-forever (removes
// the session from the underlying store; the daemon rejects
// the call if the session isn't trashed, so the parent's
// TrashList handles the confirmation prompt).
//
// Visual model:
//
//   ┌──────────────────────────────────────────────┐
//   │ Fix Cwe252 unchecked return                  │
//   │ /Users/me/proj · trashed 2h ago · 4.2K tok  │
//   │                              [restore] [✕]   │
//   └──────────────────────────────────────────────┘

import { useMemo, type CSSProperties } from 'react';
import type { SessionListItem } from '../../rpc/types';

export interface TrashRowProps {
  session: SessionListItem;
  onRestore: (id: string) => void;
  onDeleteForever: (id: string) => void;
  style?: CSSProperties;
}

function formatTrashedAt(ts?: number | null): string {
  if (!ts) return '—';
  const now = Date.now();
  const dt = now - ts;
  if (dt < 60_000) return 'just now';
  if (dt < 3_600_000) return `${Math.floor(dt / 60_000)}m ago`;
  if (dt < 86_400_000) return `${Math.floor(dt / 3_600_000)}h ago`;
  return `${Math.floor(dt / 86_400_000)}d ago`;
}

function ellipsisePath(p: string, max: number): string {
  if (p.length <= max) return p;
  return p.slice(0, max - 1) + '…';
}

function formatTokens(n?: number): string {
  if (n == null) return '—';
  if (n < 1000) return String(n);
  if (n < 1_000_000) return `${(n / 1000).toFixed(1)}k`;
  return `${(n / 1_000_000).toFixed(2)}M`;
}

function sessionTitle(s: SessionListItem): string {
  if (s.title && s.title.trim()) return s.title;
  if (s.preview && s.preview.trim()) {
    const p = s.preview.trim();
    return p.length > 60 ? p.slice(0, 57) + '...' : p;
  }
  return `Session ${s.id.slice(-8)}`;
}

export function TrashRow({
  session,
  onRestore,
  onDeleteForever,
  style,
}: TrashRowProps) {
  const title = useMemo(() => sessionTitle(session), [session]);
  const cwd = useMemo(() => ellipsisePath(session.cwd ?? '', 40), [session.cwd]);
  const trashedAgo = useMemo(() => formatTrashedAt(session.trashedAt), [session.trashedAt]);
  const totalTokens = useMemo(
    () => (session.tokensIn ?? 0) + (session.tokensOut ?? 0),
    [session.tokensIn, session.tokensOut],
  );

  return (
    <div
      className="trash-row"
      style={style}
      role="option"
      aria-selected={false}
      title={`${session.id}\ntrashed ${trashedAgo}`}
    >
      <span className="trash-row-dot" aria-hidden />
      <div className="trash-row-body">
        <div className="trash-row-title">{title}</div>
        <div className="trash-row-meta">
          <span className="trash-row-cwd">{cwd || '—'}</span>
          <span className="trash-row-sep" aria-hidden>·</span>
          <span className="trash-row-when">trashed {trashedAgo}</span>
          {totalTokens > 0 && (
            <>
              <span className="trash-row-sep" aria-hidden>·</span>
              <span className="trash-row-tokens">{formatTokens(totalTokens)} tok</span>
            </>
          )}
        </div>
      </div>
      <div className="trash-row-actions">
        <button
          type="button"
          className="trash-row-restore"
          onClick={() => onRestore(session.id)}
          title="Restore this session to the live list"
        >restore</button>
        <button
          type="button"
          className="trash-row-delete-forever"
          onClick={() => onDeleteForever(session.id)}
          title="Permanently delete this session"
          aria-label={`Delete session ${title} forever`}
        >✕</button>
      </div>
    </div>
  );
}
