// Phase 4.1 (T-4-06): TrashList.
//
// Lists trashed sessions. The daemon's `session/list` returns
// every session; the `includeTrashed: true` flag flips the
// default filter. We sort by `trashedAt` desc so the
// most-recently-deleted session sits at the top.
//
// The list subscribes to `session/list?includeTrashed=true`
// and offers:
//   - per-row restore (calls session/restore)
//   - per-row delete-forever (calls session/delete again,
//     which on a trashed session removes the row from the
//     underlying store permanently)
//   - bulk "Empty Trash" button (session/trash with empty=true)
//
// The component is intentionally controlled: the parent
// passes the items so the trash page can compose it with
// other widgets (e.g. a "Restore last" pill). When `items`
// is undefined, the component fetches its own data via
// the shared RPC client (the same pattern the SidePanel
// uses for the live list).

import { useMemo, useState } from 'react';
import { useRpc, useSessionList } from '../../rpc/queries';
import { useRestoreSession, useDeleteSession } from '../../rpc/mutations';
import { useQueryClient } from '@tanstack/react-query';
import { TrashRow } from './TrashRow';
import type { SessionListItem } from '../../rpc/types';

export interface TrashListProps {
  /** Pre-fetched trashed sessions. If `undefined`, the
   *  component fetches its own via `session/list`. */
  items?: SessionListItem[];
  /** When true, hide the header (used by the page wrapper
   *  that already has its own). */
  hideHeader?: boolean;
  /** Fired after a successful restore. */
  onRestored?: (id: string) => void;
  /** Fired after a successful "delete forever". */
  onDeleted?: (id: string) => void;
  /** Fired after the trash is emptied. */
  onEmptied?: () => void;
}

export function TrashList({
  items: providedItems,
  hideHeader = false,
  onRestored,
  onDeleted,
  onEmptied,
}: TrashListProps) {
  const client = useRpc();
  const qc = useQueryClient();
  const restore = useRestoreSession();
  const del = useDeleteSession();
  const [busy, setBusy] = useState(false);
  const [confirmEmpty, setConfirmEmpty] = useState(false);

  // Self-fetch when the parent didn't pass items.
  const query = useSessionList({ includeTrashed: true, limit: 500, withPreview: false });
  const live = query.data?.sessions ?? [];

  const items = useMemo(() => {
    const all = providedItems ?? live;
    return [...all]
      .filter((s) => s.trashedAt)
      .sort((a, b) => (b.trashedAt ?? 0) - (a.trashedAt ?? 0));
  }, [providedItems, live]);

  const handleRestore = async (id: string) => {
    try {
      await restore.mutateAsync({ id });
      onRestored?.(id);
      qc.invalidateQueries({ queryKey: ['sessionList'] });
    } catch (e) {
      console.error('restore failed', e);
    }
  };

  const handleDeleteForever = async (id: string) => {
    if (!window.confirm(`Permanently delete session ${id.slice(-8)}? This cannot be undone.`)) return;
    try {
      await del.mutateAsync({ id });
      onDeleted?.(id);
      qc.invalidateQueries({ queryKey: ['sessionList'] });
    } catch (e) {
      console.error('delete failed', e);
    }
  };

  const handleEmpty = async () => {
    if (!confirmEmpty) {
      setConfirmEmpty(true);
      window.setTimeout(() => setConfirmEmpty(false), 4_000);
      return;
    }
    setBusy(true);
    try {
      await client.call('session/trash', { empty: true });
      onEmptied?.();
      qc.invalidateQueries({ queryKey: ['sessionList'] });
    } catch (e) {
      console.error('empty trash failed', e);
    } finally {
      setBusy(false);
      setConfirmEmpty(false);
    }
  };

  return (
    <div className="trash-list" role="region" aria-label="Trashed sessions">
      {!hideHeader && (
        <div className="trash-list-head">
          <span className="trash-list-title">Trash</span>
          <span className="trash-list-count" title={`${items.length} trashed sessions`}>
            {items.length}
          </span>
          {items.length > 0 && (
            <button
              type="button"
              className={['trash-list-empty', confirmEmpty ? 'trash-list-empty-confirm' : ''].filter(Boolean).join(' ')}
              onClick={handleEmpty}
              disabled={busy}
              title={confirmEmpty ? 'Click again to confirm' : 'Empty the trash (irreversible)'}
            >
              {busy ? 'emptying…' : confirmEmpty ? 'confirm empty' : 'empty trash'}
            </button>
          )}
        </div>
      )}
      {items.length === 0 ? (
        <div className="trash-list-empty-state">No trashed sessions</div>
      ) : (
        <ul className="trash-list-items" role="listbox" aria-label="Trashed sessions">
          {items.map((s) => (
            <li key={s.id} className="trash-list-item-wrap">
              <TrashRow
                session={s}
                onRestore={() => handleRestore(s.id)}
                onDeleteForever={() => handleDeleteForever(s.id)}
              />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
