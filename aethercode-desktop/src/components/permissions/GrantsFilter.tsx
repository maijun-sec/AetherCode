// Phase 5 (T-5-09): GrantsFilter.
//
// Filter controls for the GrantsList. Three axes:
//   - scope     (user / project / session / all)
//   - decision  (allow / deny / all)
//   - category  (free text, matched as substring)
//   - query     (free text, matched against id +
//                category + pattern)
//
// State is lifted to the parent (GrantsList) so the
// filter can be reused outside the list (e.g. on a
// per-session detail drawer). The component is purely
// controlled.

import { type ChangeEvent } from 'react';

export type GrantScope = 'user' | 'project' | 'session' | 'all';
export type GrantDecision = 'allow' | 'deny' | 'all';

export interface GrantsFilterValue {
  scope: GrantScope;
  category: string;
  decision: GrantDecision;
  query: string;
}

export const EMPTY_GRANTS_FILTER: GrantsFilterValue = {
  scope: 'all',
  category: '',
  decision: 'all',
  query: '',
};

export function isFilterActive(f: GrantsFilterValue): boolean {
  return f.scope !== 'all' || f.decision !== 'all' || f.category.length > 0 || f.query.length > 0;
}

export interface GrantsFilterProps {
  value: GrantsFilterValue;
  onChange: (next: GrantsFilterValue) => void;
  totalCount?: number;
  visibleCount?: number;
}

export function GrantsFilter({ value, onChange, totalCount, visibleCount }: GrantsFilterProps) {
  const emit = (patch: Partial<GrantsFilterValue>) => onChange({ ...value, ...patch });
  const onScope = (e: ChangeEvent<HTMLSelectElement>) =>
    emit({ scope: e.target.value as GrantScope });
  const onDecision = (e: ChangeEvent<HTMLSelectElement>) =>
    emit({ decision: e.target.value as GrantDecision });
  const onCategory = (e: ChangeEvent<HTMLInputElement>) => emit({ category: e.target.value });
  const onQuery = (e: ChangeEvent<HTMLInputElement>) => emit({ query: e.target.value });
  const onClear = () => onChange(EMPTY_GRANTS_FILTER);

  return (
    <div className="grants-filter" role="search" aria-label="Filter grants">
      <div className="grants-filter-row">
        <label className="grants-filter-field">
          <span className="grants-filter-label">scope</span>
          <select value={value.scope} onChange={onScope} aria-label="Scope">
            <option value="all">all</option>
            <option value="user">user</option>
            <option value="project">project</option>
            <option value="session">session</option>
          </select>
        </label>
        <label className="grants-filter-field">
          <span className="grants-filter-label">decision</span>
          <select value={value.decision} onChange={onDecision} aria-label="Decision">
            <option value="all">all</option>
            <option value="allow">allow</option>
            <option value="deny">deny</option>
          </select>
        </label>
        <label className="grants-filter-field grants-filter-grow">
          <span className="grants-filter-label">category</span>
          <input
            type="text"
            value={value.category}
            onChange={onCategory}
            placeholder="bash.command, edit_file, …"
            spellCheck={false}
          />
        </label>
        <label className="grants-filter-field grants-filter-grow">
          <span className="grants-filter-label">search</span>
          <input
            type="text"
            value={value.query}
            onChange={onQuery}
            placeholder="id / pattern…"
            spellCheck={false}
          />
        </label>
        {isFilterActive(value) && (
          <button
            type="button"
            className="grants-filter-clear"
            onClick={onClear}
            title="Clear all filters"
          >clear</button>
        )}
      </div>
      {typeof totalCount === 'number' && typeof visibleCount === 'number' && (
        <div className="grants-filter-count" aria-live="polite">
          {visibleCount === totalCount
            ? `${totalCount} grant${totalCount === 1 ? '' : 's'}`
            : `${visibleCount} of ${totalCount}`}
        </div>
      )}
    </div>
  );
}
