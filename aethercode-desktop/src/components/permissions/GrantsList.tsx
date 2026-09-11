// Phase 5 (T-5-08): GrantsList.
//
// Lists the active grants (one row per persisted decision).
// The desktop reads them via `grants/list`. Each row
// surfaces the scope (user / project / session), the
// category (e.g. `bash.command.npm`), the decision
// (allow / deny), and a one-line pattern hint. The
// revoke button calls `grants/revoke`.
//
// Visual model:
//
//   ┌──────────────────────────────────────────────┐
//   │ [user] bash.command.npm           ✓ allow   │
//   │        pattern: npm test          [revoke]  │
//   ├──────────────────────────────────────────────┤
//   │ [project] edit_file               ✗ deny    │
//   │        pattern: *.{md,json}       [revoke]  │
//   └──────────────────────────────────────────────┘

import { useMemo } from 'react';
import { useGrantsList, useRevokeGrant } from '../../rpc/queries';
import { GrantsFilter, type GrantsFilterValue, EMPTY_GRANTS_FILTER, isFilterActive } from './GrantsFilter';
import { useState } from 'react';

export interface GrantsListProps {
  /** Initial scope filter. Defaults to "all scopes". */
  initialFilter?: GrantsFilterValue;
  /** When true, hide the inline filter (the parent
   *  provides its own). */
  hideFilter?: boolean;
  /** Fired after a successful revoke. */
  onRevoked?: (id: string) => void;
}

export function GrantsList({
  initialFilter,
  hideFilter = false,
  onRevoked,
}: GrantsListProps) {
  const [filter, setFilter] = useState<GrantsFilterValue>(initialFilter ?? EMPTY_GRANTS_FILTER);
  const query = useGrantsList();
  const revoke = useRevokeGrant();

  const filtered = useMemo(() => {
    const items = query.data ?? [];
    return items.filter((g) => {
      if (filter.scope !== 'all' && g.scope !== filter.scope) return false;
      if (filter.category && !g.category.toLowerCase().includes(filter.category.toLowerCase())) return false;
      if (filter.decision !== 'all' && g.decision !== filter.decision) return false;
      if (filter.query) {
        const q = filter.query.toLowerCase();
        const hay = `${g.category} ${g.pattern ?? ''} ${g.id}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }, [query.data, filter]);

  const handleRevoke = async (id: string) => {
    try {
      await revoke.mutateAsync({ id });
      onRevoked?.(id);
    } catch (e) {
      console.error('revoke failed', e);
    }
  };

  return (
    <div className="grants-list" role="region" aria-label="Active grants">
      {!hideFilter && (
        <GrantsFilter
          value={filter}
          onChange={setFilter}
          totalCount={(query.data ?? []).length}
          visibleCount={filtered.length}
        />
      )}
      {query.isLoading ? (
        <div className="grants-list-loading">Loading grants…</div>
      ) : filtered.length === 0 ? (
        <div className="grants-list-empty">
          {isFilterActive(filter) ? 'No grants match the filter.' : 'No active grants.'}
        </div>
      ) : (
        <ul className="grants-list-items" role="list">
          {filtered.map((g) => (
            <li key={g.id} className="grants-list-row" role="listitem">
              <div className="grants-list-row-main">
                <span className={`grants-list-scope grants-list-scope-${g.scope}`}>{g.scope}</span>
                <span className="grants-list-category">{g.category}</span>
                <span className={`grants-list-decision grants-list-decision-${g.decision}`}>
                  {g.decision === 'allow' ? '✓ allow' : '✗ deny'}
                </span>
                {g.pattern && <span className="grants-list-pattern">{g.pattern}</span>}
              </div>
              <button
                type="button"
                className="grants-list-revoke"
                onClick={() => handleRevoke(g.id)}
                title="Revoke this grant"
                aria-label={`Revoke grant ${g.id}`}
              >revoke</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
