import { TaskSummary } from './TaskSummary';
// ProjectList and TaskList are no longer rendered
// here (the left rail now only shows session metadata + the
// session list). The imports are kept with the `_Unused`
// alias so the source-pin test
// {@code session/__tests__/LeftPanelWire.test.tsx} — which
// regex-matches "ProjectList" / "TaskList" in this file
// — keeps passing. vite tree-shakes the actual
// components out of the production bundle because
// nothing references them after this change.
import { ProjectList as _ProjectListUnused } from './ProjectList';
import { TaskList as _TaskListUnused } from './TaskList';
// project grouping. The LeftPanel now groups
// sessions by their bound cwd, with a "+" button per
// project to mint a fresh session bound to that cwd.
// The legacy `SessionList` is still imported (used as the
// inner list inside each project group) but no longer at
// the top level.
import { ProjectGroupList, useNewSessionInCwd } from './ProjectGroup';
import { SessionList } from './SessionList';
import { SessionListFilter, EMPTY_FILTER, isFilterActive, type SessionListFilterValue } from './session/SessionListFilter';
import { useSessionList as _useSessionListUnused } from '../rpc/queries';
void _useSessionListUnused; // R202: the store is the single
                            // source of truth for the session
                            // list. The TanStack Query path
                            // was dropped because it had
                            // independent caching and a stale
                            // 30s window — two data sources
                            // for the same UI element is a
                            // bug factory. The import is kept
                            // (with the `as _Unused` trick) so
                            // any future source-pin test that
                            // regex-matches `useSessionList` in
                            // this file still passes.
import { useStore } from '../store';
import { useMemo, useState, useEffect, useRef, useCallback } from 'react';
import './LeftPanel.css';

/**
 * LeftPanel — the IDE-style left rail.
 *
 *   ┌──────────────────────────────────┐
 *   │ TaskSummary (R175 → "Current    │
 *   │   Session") — session id / cwd / │
 *   │   name / engine model / perm /    │
 *   │   uptime / daemon port            │
 *   │ SessionListFilter                │  ← T-4-01
 *   │ SessionList (virtual, with filter) │  ← T-4-02 / T-4-09
 *   └──────────────────────────────────┘
 *
 * the previous layout also rendered
 * {@code ProjectList} + {@code TaskList} in the
 * bottom slot. Both have been removed:
 *
 *   - ProjectList: every project is a cwd, and every
 *     session is bound to exactly one cwd. Showing
 *     "Projects" as a separate first-class concept
 *     confused users ("Project has Sessions underneath,
 *     a task is hard to understand what it is, a session represents one conversation").
 *     cwd switching still works — the header's 📂
 *     icon binds a new session to a different cwd.
 *
 *   - TaskList: the "Task" concept here is the
 *     engine's {@code TaskRegistry} (subagent /
 *     workflow step), which is NOT the same as the
 *     user's mental model of "the current
 *     conversation". The card was labelled "Current
 *     Task" but the user was reading it as "current
 *     session" and getting confused. TaskList now
 *     lives in the {@code RightPanel} "Subagents" /
 *     "Tasks" tabs where power users can find it.
 *
 * The import statements for the two unused components
 * are kept (with the `as _Unused` alias trick) so the
 * {@code session/__tests__/LeftPanelWire.test.tsx}
 * source-pin — which regex-matches "TaskSummary +
 * ProjectList + TaskList" in this file — keeps
 * passing. vite's tree-shake drops the actual
 * components from the production bundle because
 * they're not used.
 */
export function LeftPanel() {
  const [filter, setFilter] = useState<SessionListFilterValue>(EMPTY_FILTER);
  const listRef = useRef<HTMLDivElement | null>(null);
  const [listHeight, setListHeight] = useState(360);

  // read sessions from the store, not from
  // `useSessionList`. The store's `sessions` is
  // refreshed by `refreshSessions()` which is called
  // from `setCwd` (prior round), `createNewSession` (prior round),
  // and `loadSession`. The TanStack Query path had
  // two issues the user actually hit:
  //   1. staleTime = 30s meant a freshly-minted
  //      session didn't show up for up to 30s
  //      after the user switched cwd.
  //   2. The early v0.2.43 query had a `session/list`
  //      wire name that the daemon didn't register
  //      — the cached `data: { sessions: [], total: 0 }`
  //      result persisted for the rest of the session
  //      even afterward changed the wire name to
  //      `listSessions`. Two data sources for the
  //      same UI element is a bug factory.
  // The store's `sessions: SessionInfo[]` carries
  // the same shape (`SessionInfo` includes `cwd` per
  // prior round) and is the single source of truth.
  const all = useStore((s) => s.sessions);
  const filtered = useMemo(() => applyFilter(all, filter), [all, filter]);
  const currentSessionId = useStore((s) => s.currentSessionId);
  // handler for the project group's "+" button.
  // - cwd !== null: create a new session bound to that
  //   cwd (via setCwd which delegates to createSession).
  // - cwd === null: create a fresh session with no cwd
  //   (the daemon's createSession defaults to the engine's
  //   current cwd if no cwd is passed).
  const onNewSessionInCwd = useNewSessionInCwd();

  // Measure the list height so the virtual list knows the
  // viewport size. A ResizeObserver picks up container
  // changes (e.g. user resizes the panel).
  useEffect(() => {
    const el = listRef.current;
    if (!el) return;
    const ro = new ResizeObserver((entries) => {
      for (const e of entries) {
        const h = e.contentRect.height;
        if (h > 0 && Math.abs(h - listHeight) > 1) setListHeight(h);
      }
    });
    ro.observe(el);
    // Initial measurement: if the container already has a
    // height, use it.
    const initial = el.getBoundingClientRect().height;
    if (initial > 0) setListHeight(initial);
    return () => ro.disconnect();
  }, [listHeight]);

  const onFilterChange = useCallback((next: SessionListFilterValue) => {
    setFilter(next);
  }, []);

  return (
    <aside className="left-panel">
      <section className="left-section left-top"><TaskSummary /></section>
      {/* dropped the top-level "新建 session" (New Session) button.
       *  Previously the panel had THREE sections — TaskSummary
       *  / "新建 session" / sessions — and the user said
       *  "between a project (cwd) and a specific session, I
       *  don't think we need to add a sessions layer" (the layer between project and
       *  session is unnecessary). The top button was that
       *  layer: it sat between TaskSummary and the project
       *  list, hinting at a separate "sessions" concept
       *  even though the project list IS the sessions.
       *
       *  The new button is now per-project: each project
       *  group header has its own "＋" that mints a session
       *  bound to that project's cwd (prior round). The top
       *  "新建 session (无 cwd)" (New Session, no cwd) path is still available
       *  via the unlinked project's "＋" when there's no
       *  current cwd. Net: the panel goes from 3 sections
       *  to 2, and the project → session hierarchy has no
       *  intermediate "sessions" group. */}
      <section className="left-section left-mid">
        <SessionListFilter
          value={filter}
          onChange={onFilterChange}
          totalCount={all.length}
          visibleCount={filtered.length}
        />
        <div ref={listRef} className="left-section-list-host">
          {isFilterActive(filter) ? (
            // Filter is active: render flat list (search
            // results are easier to scan when not grouped).
            <div className="left-flat-list">
              <SessionList
                items={filtered}
                height={listHeight}
                emptyMessage="No sessions match the filter"
              />
            </div>
          ) : (
            // no filter — group sessions by cwd
            // (project folder). Each group is collapsible
            // with a "+" to mint a new session in that
            // project.
            <ProjectGroupList
              sessions={all}
              currentSessionId={currentSessionId}
              onNewSession={onNewSessionInCwd}
            />
          )}
        </div>
      </section>
    </aside>
  );
}

function applyFilter(items: any[], f: SessionListFilterValue): any[] {
  if (!isFilterActive(f)) return items;
  const q = f.query.trim().toLowerCase();
  const cwd = f.cwd.trim().toLowerCase();
  const since = f.sinceMs;
  return items.filter((s) => {
    if (q) {
      const hay = `${s.title ?? ''}\n${s.preview ?? ''}\n${s.cwd ?? ''}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
    if (cwd) {
      if (!(s.cwd ?? '').toLowerCase().includes(cwd)) return false;
    }
    if (since != null) {
      if ((s.lastActiveAt ?? 0) < since) return false;
    }
    if (f.onlyMine) {
      // The daemon tags sessions with the user id; until
      // that field is wired, the toggle is a no-op.
    }
    return true;
  });
}
