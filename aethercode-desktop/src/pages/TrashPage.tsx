// Phase 3 + Phase 4.1: TrashPage (T-3-10 + T-4-08).
//
// The full-page trash view. The brief originally called for a basic
// list (T-3-10); Phase 4.1 (T-4-08) extends it to wire restore +
// empty-trash + per-row delete-forever via the mutations the RPC
// layer already exposes.

import { useMemo, useState } from 'react';
import {
  useDeleteSession,
  useEmptyTrash,
  useRestoreSession,
} from '../rpc/mutations';
import { useSessionList } from '../rpc/queries';

export interface TrashPageProps {
  onClose?: () => void;
}

export function TrashPage({ onClose }: TrashPageProps) {
  const list = useSessionList({ includeTrashed: true });
  const restore = useRestoreSession();
  const remove = useDeleteSession();
  const empty = useEmptyTrash();
  const [confirm, setConfirm] = useState<'empty' | null>(null);

  const trashed = useMemo(
    () => (list.data?.sessions ?? []).filter((s) => s.trashedAt),
    [list.data],
  );

  return (
    <div className="trash-page" data-testid="trash-page">
      <header className="trash-page-header">
        <h1>Trash</h1>
        <div className="trash-page-actions">
          <button
            type="button"
            data-testid="trash-empty"
            disabled={trashed.length === 0 || empty.isPending}
            onClick={() => setConfirm('empty')}
          >
            Empty trash
          </button>
          {onClose && (
            <button
              type="button"
              data-testid="trash-close"
              onClick={onClose}
              className="trash-page-close"
              aria-label="Close"
            >
              ×
            </button>
          )}
        </div>
      </header>
      {list.isLoading ? (
        <p data-testid="trash-loading">Loading…</p>
      ) : trashed.length === 0 ? (
        <p data-testid="trash-empty-msg">Trash is empty.</p>
      ) : (
        <ul className="trash-list" data-testid="trash-list">
          {trashed.map((s) => (
            <li key={s.id} className="trash-row" data-testid={`trash-row-${s.id}`}>
              <strong className="trash-row-title">{s.title}</strong>
              <span className="trash-row-cwd">{s.cwd}</span>
              <span className="trash-row-trashedAt">
                {s.trashedAt ? new Date(s.trashedAt).toLocaleString() : ''}
              </span>
              <div className="trash-row-actions">
                <button
                  type="button"
                  data-testid={`trash-restore-${s.id}`}
                  disabled={restore.isPending}
                  onClick={() => restore.mutate({ id: s.id })}
                >
                  Restore
                </button>
                <button
                  type="button"
                  data-testid={`trash-delete-${s.id}`}
                  disabled={remove.isPending}
                  onClick={() => remove.mutate({ id: s.id })}
                >
                  Delete forever
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {confirm === 'empty' && (
        <div className="trash-confirm" role="alertdialog" data-testid="trash-confirm">
          <p>Empty trash? This permanently deletes {trashed.length} session(s).</p>
          <div className="trash-confirm-actions">
            <button
              type="button"
              data-testid="trash-confirm-cancel"
              onClick={() => setConfirm(null)}
            >
              Cancel
            </button>
            <button
              type="button"
              data-testid="trash-confirm-ok"
              className="danger"
              onClick={() => {
                empty.mutate();
                setConfirm(null);
              }}
            >
              Empty
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
