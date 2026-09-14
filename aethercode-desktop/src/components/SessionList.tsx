import { useEffect, useState, useRef } from 'react';
import { useStore } from '../store';
import type { SessionInfo } from '../lib/methods';
import { VariableSizeList } from './session/SessionListVirtual';
import './SessionList.css';

/** Sidebar session stack. Lists every session the daemon
 *  knows about; the current one is highlighted. Click to
 *  switch (loadSession RPC). The "+" button at the bottom
 *  starts a new session — it clears currentSessionId so the
 *  next query() lands in a fresh one. After the query, the
 *  store auto-picks the most-recently-used session as
 *  current (see sendMessage in store/index.ts).
 *
 *  Visual model (intentionally dense, IDE-style):
 *    ┌──────────────────────────────────┐
 *    │ ● fathom-dfa · R437 验证 (verify)     ✓  │  ← current (highlighted)
 *    │ ○ AetherCode · SubTask 折叠 (collapse)  ▷ │
 *    │ ○ fathom-dfa · 修 Z3 leak (fix Z3)     │
 *    │ + New Session                         │
 *    └──────────────────────────────────┘
 *
 *  Status dot colours:
 *    ●  active     — current session, in progress (isStreaming)
 *    ●  current    — current session, idle
 *    ○  idle       — not current, last-used recently
 *    ◌  stale      — not current, last-used > 1h ago
 */
function formatLastUsed(ts?: number): string {
  if (!ts) return '—';
  const now = Date.now();
  const dt = now - ts;
  if (dt < 60_000) return 'just now';
  if (dt < 3_600_000) return `${Math.floor(dt / 60_000)}m`;
  if (dt < 86_400_000) return `${Math.floor(dt / 3_600_000)}h`;
  return `${Math.floor(dt / 86_400_000)}d`;
}

function sessionLabel(s: SessionInfo): string {
  // Prefer the engine-provided name; fall back to the
  // first-user-message preview (prior round), then to the id's
  // tail (last 8 chars) so the user can tell two anonymous
  // sessions apart at a glance.
  if (s.name && String(s.name).trim()) return String(s.name);
  // the daemon's listSessions can populate a
  // `preview` field (first user message, capped at 200
  // chars) when called with { withPreview: true }. The
  // store passes that flag from refreshSessions() so the
  // user always sees meaningful titles without the boot
  // round-trip having to opt in.
  //
  // the user wants a SHORT summary, not a 200-char
  // preview ("usually 10 characters or fewer"). Truncate to ~10 chars
  // (Chinese-friendly — Array.from(text) iterates by code
  // point, not UTF-16 code unit, so a Chinese char is one
  // item not two). The daemon's extractSessionPreview is
  // still the first user message; the LLM-generated
  // "10-char summary" is a future round (R223+ candidate). For
  // now we truncate the preview so the user at least
  // sees "Fix Cwe252..." instead of the full sentence.
  if (s.preview && String(s.preview).trim()) {
    return shortSummary(String(s.preview).trim());
  }
  // defensive — the daemon's listSessions can
  // return entries with null/undefined id in odd
  // states (e.g. mid-migration). Render a stable label
  // instead of throwing on .slice().
  //
  // change the fallback from `Session 2baefad9` to
  // `New Session`. The user said the id-based label "came back"
  // even after the 10-char cap fix — because the
  // R222 fix only kicks in when there's a `preview`.
  // A fresh session that the daemon hasn't back-filled
  // a preview for has neither name nor preview, so it
  // fell through to the id slice. R223 drops the id
  // slice from the common path: when neither name nor
  // preview is set, render "New Session" regardless of
  // messageCount. The id slice stays as the final
  // defensive fallback for the truly degenerate case
  // (no name, no preview, no messageCount) where
  // the user might want to inspect / report it.
  if (s.messageCount == null) {
    const id = s?.id;
    if (typeof id === 'string' && id.length > 0) {
      return `Session ${id.slice(-8)}`;
    }
    return '新会话';
  }
  return '新会话';
}

/**
 * shorten a free-form string to a 1-line summary
 * suitable for the session-list label. We aim for ~10
 * visible characters (the user's "10 characters or fewer" rule)
 * with an ellipsis when the source is longer.
 *
 * The 10-char cap is "characters" not bytes — we use
 * `Array.from(text)` so a Chinese character counts as
 * one item (each Chinese char is 1 code point but
 * 2 UTF-16 code units, so a naive `.slice(0, 10)` would
 * chop CJK in half and produce a broken char). We also
 * strip newlines + collapse whitespace so a long first
 * user message like
 *   "Help me debug\n   the failing test\nplease"
 * becomes
 *   "Help me debug the …"
 * (we drop the leading "the" if it overflows the 10-char
 * cap rather than letting the ellipsis cover meaningful
 * content).
 */
function shortSummary(text: string, maxChars: number = 10): string {
  // 1. Collapse whitespace + newlines.
  const flat = text.replace(/\s+/g, ' ').trim();
  if (!flat) return '';
  // 2. Iterate by code point (Chinese-friendly). We
  // collect up to `maxChars` codepoints, then add an
  // ellipsis if anything was left over.
  const cps = Array.from(flat);
  if (cps.length <= maxChars) return flat;
  // Drop the trailing char so the ellipsis doesn't
  // replace a meaningful char; the user can hover the
  // row to see the full preview (see Tooltip on
  // session-info below).
  const head = cps.slice(0, maxChars).join('');
  return head + '…';
}

export interface SessionListProps {
  /** When provided, render this list instead of fetching from
   *  the store. Used by the LeftPanel to feed a filtered set
   *  to the virtual list. */
  items?: SessionInfo[];
  /** Pixel height of the scrollable region. Used by the
   *  VariableSizeList to compute the visible window. */
  height?: number;
  /** Render a custom empty state when the list is empty. */
  emptyMessage?: string;
  /** When set, the bottom "+ New Session" button calls this instead
   *  of the store's `createNewSession` (which is bound to the
   *  CURRENT cwd). This makes the bottom button behave the
   *  same as the project header's `＋` — i.e. it creates a
   *  session for the project whose group this SessionList
   *  is rendering, not for whatever the user is currently on.
   * legacy the bottom button always called
   *  `createNewSession()` which created for the active cwd,
   *  so clicking `+ New Session` inside "Unlinked Project" (49 sessions)
   *  silently created an abc_4 session — confusing. */
  onNewSession?: () => void;
  /** Project / cwd display name for the bottom button's
   *  tooltip. The user asked what scope the bottom button
   *  belongs to; the tooltip now spells it out. */
  newSessionLabel?: string;
  /** R266g: hide the `<div class="section-header">Sessions</div>`
   *  + count row. ProjectGroup already renders its own
   *  `<summary>` with the project name + count, so the inner
   *  header was a redundant "SESSIONS" row the user explicitly
   *  asked to remove (left rail should only show the project
   *  listing). When the parent does NOT render its own header
   *  (e.g. LeftPanel's flat filter view), this prop defaults to
   *  false and the header is shown as before. The
   *  "+ New Session" button stays — it's the per-group create
   *  action. */
  hideSectionHeader?: boolean;
}

export function SessionList({ items: propItems, height, emptyMessage, onNewSession, newSessionLabel, hideSectionHeader = false }: SessionListProps = {}) {
  const {
    sessions,
    currentSessionId,
    isStreaming,
    refreshSessions,
    switchSession,
    createNewSession,
    deleteSession,
  } = useStore();
  const [flashId, setFlashId] = useState<string | null>(null);
  const flashTimerRef = useRef<number | null>(null);

  // Refresh on mount + whenever a query starts (the daemon
  // may have created a new session in the background). Skip
  // when the parent passes items (the parent owns fetching).
  useEffect(() => {
    if (propItems) return;
    refreshSessions();
  }, [refreshSessions, isStreaming, propItems]);

  // Sort sessions by lastUsedAt desc (most recent at top).
  // Current session always pinned to top regardless.
  const baseItems = propItems ?? sessions;
  const sorted = [...baseItems].sort((a, b) => {
    if (a.id === currentSessionId) return -1;
    if (b.id === currentSessionId) return 1;
    return (b.lastUsedAt ?? 0) - (a.lastUsedAt ?? 0);
  });

  const handleSwitch = async (id: string) => {
    if (id === currentSessionId) return;
    try {
      await switchSession(id);
      // brief 200ms flash on the newly-active row so the
      // user has a visual confirmation "yes, I switched".
      setFlashId(id);
      if (flashTimerRef.current) window.clearTimeout(flashTimerRef.current);
      flashTimerRef.current = window.setTimeout(() => setFlashId(null), 220);
    } catch (e) {
      console.error('switchSession failed', e);
    }
  };

  const handleNew = () => {
    // instead of clearing the current session id and
    // hoping the daemon hands us a fresh one, generate a
    // local UUID and switch to it. This makes the LeftPanel
    // show the new session immediately, and the input
    // draft is keyed by the new id (prior round). The daemon's
    // session model is single-session, so the new local
    // session is just a UI concept; the engine still
    // answers queries on its one transcript.
    //
    // prefer the parent's `onNewSession` callback when
    // provided. legacy this always called the store's
    // `createNewSession`, which creates for the CURRENT cwd.
    // ProjectGroup passes its own `onNewSession(cwd)` so the
    // bottom button creates a session for that project, not
    // for whatever the user is currently on. This matters
    // when the user is viewing the "Unlinked Project" group
    // (sessions without a cwd binding) — clicking the
    // bottom button there used to silently create an
    // abc_4 session.
    if (onNewSession) {
      void onNewSession();
    } else {
      createNewSession();
    }
  };

  // per-row delete button. Confirms before removing;
  // refuses to delete the active session (the user must
  // switch first). The button is hidden on the active row
  // to make the constraint visually obvious — clicking it
  // would just hit the daemon's IllegalStateException and
  // show a confusing error.
  const handleDelete = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (id === currentSessionId) {
      window.alert('当前会话不能删除。先切换到别的会话再回来删除这个。');
      return;
    }
    if (!window.confirm(`确定删除 session ${id.slice(-8)}?消息历史会一起清掉。`)) return;
    await deleteSession(id);
  };

  // Build a single list of "rows" — the actual sessions
  // plus a synthetic "broken entry" row when the daemon
  // returns an entry without an id (R113 defensive
  // rendering). The virtual list then takes the rows
  // uniformly; the current-row style picks up via the
  // row's className.
  type RenderRow =
    | { kind: 'broken'; key: string }
    | { kind: 'session'; session: SessionInfo; key: string };
  const rows: RenderRow[] = sorted.map((s) => {
    const hasId = typeof s?.id === 'string' && s.id.length > 0;
    if (!hasId) {
      return { kind: 'broken', key: `broken-${sorted.indexOf(s)}` } as RenderRow;
    }
    return { kind: 'session', session: s, key: s.id } as RenderRow;
  });

  const listBody = sorted.length === 0 ? (
    <div className="session-empty">{emptyMessage ?? 'No sessions yet — type below to start'}</div>
  ) : height ? (
    <VariableSizeList
      items={rows}
      height={height}
      ariaLabel="Sessions"
      // bump itemHeight 56 → 64. The 14px label
      // (prior round) + 8px padding (prior round) needs 36px content +
      // 16px padding = ~52px, with margin + border it's
      // ~64px. The 56px constant was tuned for 12px/6px.
      // bump 64 → 72 to match the 15px label +
      // 10px padding (prior round). 15px + line-height 1.35
      // = 20.25px content + 3px margin-bottom = 23px,
      // plus 12px meta + 10px*2 padding = 45px content,
      // plus 2px margin + 2px border = ~72px.
      itemHeight={() => 72}
      itemKey={(_, r) => r.key}
      renderItem={(_, row, style) => {
        if (row.kind === 'broken') {
          return (
            <div
              className="session-item stale"
              style={style}
              role="option"
              aria-selected={false}
              title="session entry without an id"
            >
              <span className="session-dot dot-stale" />
              <div className="session-info">
                <div className="session-label">(invalid session entry)</div>
                <div className="session-meta">—</div>
              </div>
            </div>
          );
        }
        const s = row.session;
        const isCurrent = s.id === currentSessionId;
        const isFlash = s.id === flashId;
        const isStale = !isCurrent && (s.lastUsedAt ?? 0) < Date.now() - 3_600_000;
        return (
          <li
            className={[
              'session-item',
              isCurrent ? 'active' : '',
              isFlash ? 'flash' : '',
              isStale ? 'stale' : '',
            ].filter(Boolean).join(' ')}
            style={{ ...style, listStyle: 'none' }}
            role="option"
            aria-selected={isCurrent}
            onClick={() => handleSwitch(s.id)}
            title={`${s.id}\nlast used ${formatLastUsed(s.lastUsedAt)}`}
          >
            <span
              className={[
                'session-dot',
                isCurrent ? (isStreaming ? 'dot-active' : 'dot-current') : '',
                isStale ? 'dot-stale' : '',
              ].filter(Boolean).join(' ')}
            />
            <div className="session-info">
              <div className="session-label">{sessionLabel(s)}</div>
              <div className="session-meta">
                <span className="session-when">{formatLastUsed(s.lastUsedAt)}</span>
                {s.messageCount != null && (
                  <>
                    <span className="session-sep">·</span>
                    <span className="session-msg">{s.messageCount} msg</span>
                  </>
                )}
              </div>
            </div>
            {!isCurrent && (
              <button
                className="session-delete-btn"
                title="删除这个 session"
                onClick={(e) => handleDelete(s.id, e)}
                aria-label={`Delete session ${sessionLabel(s)}`}
              >×</button>
            )}
          </li>
        );
      }}
    />
  ) : (
    <ul className="session-items" role="listbox" aria-label="Sessions">
      {sorted.map((s) => {
        // defensive — entries without an id
        // are useless (no switch target, no delete
        // target). Render a placeholder row so the
        // user can see *something* is in the list
        // (the count badge already says "1") instead
        // of silently dropping the entry.
        const hasId = typeof s?.id === 'string' && s.id.length > 0;
        if (!hasId) {
          return (
            <li
              key={`broken-${sorted.indexOf(s)}`}
              className="session-item stale"
              role="option"
              aria-selected={false}
              title="session entry without an id"
            >
              <span className="session-dot dot-stale" />
              <div className="session-info">
                <div className="session-label">(invalid session entry)</div>
                <div className="session-meta">—</div>
              </div>
            </li>
          );
        }
        const isCurrent = s.id === currentSessionId;
        const isFlash = s.id === flashId;
        const isStale =
          !isCurrent && (s.lastUsedAt ?? 0) < Date.now() - 3_600_000;
        return (
          <li
            key={s.id}
            className={[
              'session-item',
              isCurrent ? 'active' : '',
              isFlash ? 'flash' : '',
              isStale ? 'stale' : '',
            ].filter(Boolean).join(' ')}
            role="option"
            aria-selected={isCurrent}
            onClick={() => handleSwitch(s.id)}
            title={`${s.id}\nlast used ${formatLastUsed(s.lastUsedAt)}`}
          >
            <span
              className={[
                'session-dot',
                isCurrent ? (isStreaming ? 'dot-active' : 'dot-current') : '',
                isStale ? 'dot-stale' : '',
              ].filter(Boolean).join(' ')}
            />
            <div className="session-info">
              <div className="session-label">
                {sessionLabel(s)}
              </div>
              <div className="session-meta">
                <span className="session-when">{formatLastUsed(s.lastUsedAt)}</span>
                {s.messageCount != null && (
                  <>
                    <span className="session-sep">·</span>
                    <span className="session-msg">{s.messageCount} msg</span>
                  </>
                )}
              </div>
            </div>
            {!isCurrent && (
              <button
                className="session-delete-btn"
                title="删除这个 session"
                onClick={(e) => handleDelete(s.id, e)}
                aria-label={`Delete session ${sessionLabel(s)}`}
              >
                ×
              </button>
            )}
          </li>
        );
      })}
    </ul>
  );
  return (
    <div className="session-list">
      {/* R266g: drop the inner "SESSIONS" header + count
       *  when the parent (ProjectGroup) already provides
       *  its own summary. The header was redundant —
       *  project "abc_1" already has a count badge in
       *  its <summary>, and the user explicitly asked
       *  to remove the second-level "SESSIONS" row. */}
      {!hideSectionHeader && (
        <div className="section-header">
          <span>Sessions</span>
          <span className="session-count" title={`${baseItems.length} sessions`}>
            {baseItems.length}
          </span>
        </div>
      )}
      {listBody}
      <button
        className="session-new-btn"
        onClick={handleNew}
        title={newSessionLabel ? `新建 session (${newSessionLabel})` : 'Start a new session'}
      >
        + 新会话
      </button>
    </div>
  );
}
