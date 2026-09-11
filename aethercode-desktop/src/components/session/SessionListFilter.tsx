// Phase 4.1 (T-4-01): SessionListFilter.
//
// Search + cwd picker + date range filters for the session
// list. Pure controlled-component pattern: the parent owns
// the filter state, this component emits `onChange` patches
// so the list view can debounce + re-filter in one place.
//
// Visual model (the filter strip above the session list):
//
//   ┌──────────────────────────────────────────────┐
//   │ 🔍 [search...]   📁 [cwd...]   📅 [since]  │
//   └──────────────────────────────────────────────┘
//
// State (controlled, lifted to the parent):
//   - query:        free text, matched against title / cwd / preview
//   - cwd:          substring filter on the session cwd
//   - sinceMs:      wall-clock ms; sessions with lastActiveAt
//                   < sinceMs are hidden (date range filter)
//   - onlyMine:     "only my sessions" — the daemon tags
//                   sessions with the current user id
//
// Empty values mean "no filter" (the field is "active" only
// when the user typed something). The component surfaces a
// "Clear" pill when at least one filter is set so the user
// can reset in one click.

import { useEffect, useRef, useState, type ChangeEvent } from 'react';

export interface SessionListFilterValue {
  query: string;
  cwd: string;
  sinceMs: number | null;
  onlyMine: boolean;
}

export const EMPTY_FILTER: SessionListFilterValue = {
  query: '',
  cwd: '',
  sinceMs: null,
  onlyMine: false,
};

export function isFilterActive(v: SessionListFilterValue): boolean {
  return v.query.length > 0 || v.cwd.length > 0 || v.sinceMs != null || v.onlyMine;
}

export interface SessionListFilterProps {
  value: SessionListFilterValue;
  onChange: (next: SessionListFilterValue) => void;
  /** Optional debounce in ms for the `query` field. Default 120. */
  debounceMs?: number;
  /** Hide the "only mine" toggle if the daemon doesn't tag
   *  sessions by user. */
  showOnlyMine?: boolean;
  /** Optional count badge to show in the corner. */
  totalCount?: number;
  /** Optional count after filter applied. */
  visibleCount?: number;
}

/** Convert a `YYYY-MM-DD` value to wall-clock ms (UTC midnight).
 *  Returns `null` for empty input. The desktop renders this as
 *  `<input type="date">` so the value is always ISO-shaped. */
function dateInputToMs(v: string): number | null {
  if (!v) return null;
  // `new Date('YYYY-MM-DD')` parses as UTC midnight per the
  // HTML spec — the same shape the daemon emits.
  const t = Date.parse(v);
  return Number.isFinite(t) ? t : null;
}

/** Inverse of {@link dateInputToMs}. Returns the YYYY-MM-DD
 *  for an `<input type="date">` value, or '' for null. */
function msToDateInput(ms: number | null): string {
  if (ms == null) return '';
  const d = new Date(ms);
  // We render in UTC so the user sees the same day no matter
  // what timezone the renderer is in. The daemon treats
  // sinceMs as UTC ms anyway.
  const y = d.getUTCFullYear();
  const m = String(d.getUTCMonth() + 1).padStart(2, '0');
  const day = String(d.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

export function SessionListFilter({
  value,
  onChange,
  debounceMs = 120,
  showOnlyMine = true,
  totalCount,
  visibleCount,
}: SessionListFilterProps) {
  // Debounce the free-text fields so every keystroke doesn't
  // re-filter the list. The `useState` holds the local
  // (immediate) value; the parent's value is updated after
  // the debounce window.
  const [query, setQuery] = useState(value.query);
  const [cwd, setCwd] = useState(value.cwd);
  const queryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const cwdTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    setQuery(value.query);
  }, [value.query]);
  useEffect(() => {
    setCwd(value.cwd);
  }, [value.cwd]);

  useEffect(() => () => {
    if (queryTimerRef.current) clearTimeout(queryTimerRef.current);
    if (cwdTimerRef.current) clearTimeout(cwdTimerRef.current);
  }, []);

  const emit = (patch: Partial<SessionListFilterValue>) => onChange({ ...value, ...patch });

  const onQueryChange = (e: ChangeEvent<HTMLInputElement>) => {
    const next = e.target.value;
    setQuery(next);
    if (queryTimerRef.current) clearTimeout(queryTimerRef.current);
    queryTimerRef.current = setTimeout(() => emit({ query: next }), debounceMs);
  };
  const onCwdChange = (e: ChangeEvent<HTMLInputElement>) => {
    const next = e.target.value;
    setCwd(next);
    if (cwdTimerRef.current) clearTimeout(cwdTimerRef.current);
    cwdTimerRef.current = setTimeout(() => emit({ cwd: next }), debounceMs);
  };
  const onSinceChange = (e: ChangeEvent<HTMLInputElement>) => {
    emit({ sinceMs: dateInputToMs(e.target.value) });
  };
  const onOnlyMineChange = (e: ChangeEvent<HTMLInputElement>) => {
    emit({ onlyMine: e.target.checked });
  };
  const onClear = () => {
    setQuery(''); setCwd('');
    onChange(EMPTY_FILTER);
  };

  const active = isFilterActive(value);
  return (
    <div className="session-list-filter" role="search" aria-label="Filter sessions">
      <div className="session-list-filter-row">
        <label className="session-list-filter-field">
          <span className="session-list-filter-icon" aria-hidden>🔍</span>
          <input
            type="text"
            value={query}
            onChange={onQueryChange}
            placeholder="search title / preview…"
            spellCheck={false}
            aria-label="Search sessions by title or preview"
          />
        </label>
        <label className="session-list-filter-field">
          <span className="session-list-filter-icon" aria-hidden>📁</span>
          <input
            type="text"
            value={cwd}
            onChange={onCwdChange}
            placeholder="cwd…"
            spellCheck={false}
            aria-label="Filter sessions by cwd"
          />
        </label>
        <label className="session-list-filter-field session-list-filter-since">
          <span className="session-list-filter-icon" aria-hidden>📅</span>
          <input
            type="date"
            value={msToDateInput(value.sinceMs)}
            onChange={onSinceChange}
            aria-label="Filter sessions since this date"
          />
        </label>
        {showOnlyMine && (
          <label className="session-list-filter-only-mine">
            <input
              type="checkbox"
              checked={value.onlyMine}
              onChange={onOnlyMineChange}
              aria-label="Only my sessions"
            />
            <span>mine</span>
          </label>
        )}
        {active && (
          <button
            type="button"
            className="session-list-filter-clear"
            onClick={onClear}
            title="Clear all filters"
            aria-label="Clear all filters"
          >×</button>
        )}
      </div>
      {typeof totalCount === 'number' && typeof visibleCount === 'number' && (
        <div className="session-list-filter-count" aria-live="polite">
          {visibleCount === totalCount
            ? `${totalCount} session${totalCount === 1 ? '' : 's'}`
            : `${visibleCount} of ${totalCount}`}
        </div>
      )}
    </div>
  );
}
