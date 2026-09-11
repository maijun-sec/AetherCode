import { useEffect, useState } from 'react';
import { useStore } from '../store';
import './ToolsPanel.css';

/**
 * per-tool permission-action panel.
 *
 * Shows every tool in the daemon's tool pool alongside the
 * permission action the engine would take for a typical call
 * shape. The badge uses the same colour scheme as the
 * StatusBar skip-confirmation badge so the user can read the
 * matrix at a glance.
 *
 * Three columns:
 *  - name + description
 *  - default op-kind (READ / CREATE / MODIFY / ...)
 *  - action badge (ALLOW / ASK / DENY)
 *
 * The `defaultAction` is computed by the daemon from a sample
 * input (the same heuristic the matrix uses), so the badge
 * is a hint, not a contract. A user who calls `file_write` on
 * a path the matrix says ASK is still prompted.
 */

interface Props {
  onClose: () => void;
}

const ACTION_COLORS: Record<string, string> = {
  ALLOW: '#6cc28b',  // green
  ASK:   '#f4c471',  // amber
  DENY:  '#f48771',  // red
};

export function ToolsPanel({ onClose }: Props) {
  const { toolActions, tools, refreshTools, toolsRefreshedAt } = useStore();
  const [filter, setFilter] = useState('');
  // local "refreshing" flag so the ↻ button can
  // show a spinning hint during the round-trip. Set to
  // true on click, back to false in refreshTools' catch
  // block (or via the await). The store doesn't need
  // this state — it's purely cosmetic.
  const [refreshing, setRefreshing] = useState(false);

  // Esc closes the panel. Wired here (not in App.tsx) so the
  // listener is mounted only when the panel is open and torn
  // down when it closes. Captures nothing on the keyup
  // listener so React's exhaustive-deps lint doesn't trip.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  // when the panel opens and we have no data, kick
  // a refresh. The store's 30 s periodic poll is the
  // background safety net; this covers the user who opens
  // the panel right after launching the App and the
  // initialize() fetch failed silently. The effect runs
  // once on mount, not on every render, because we don't
  // want a re-fetch when the user just adds a filter
  // character.
  useEffect(() => {
    if (tools.length === 0 && toolActions.length === 0) {
      void refreshTools();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // click handler for the header ↻ button. Wraps
  // the store action in a try/finally so the spinner
  // clears even on a rejected RPC.
  const onRefresh = async () => {
    setRefreshing(true);
    try {
      await refreshTools();
    } finally {
      setRefreshing(false);
    }
  };

  // human-readable "last refresh" string. Shown
  // in the panel header so the user can tell whether
  // what they're looking at is fresh (just-launched) or
  // stale (5 min after a daemon restart). The formatter
  // mirrors the convention used in Header.tsx and other
  // status badges.
  const lastRefreshText = (() => {
    if (!toolsRefreshedAt) return '从未拉取';
    const ageMs = Date.now() - toolsRefreshedAt;
    if (ageMs < 1000) return '刚刚';
    if (ageMs < 60_000) return `${Math.round(ageMs / 1000)}s 前`;
    if (ageMs < 3_600_000) return `${Math.round(ageMs / 60_000)}m 前`;
    return `${Math.round(ageMs / 3_600_000)}h 前`;
  })();

  // Merge the two arrays by tool name. `toolActions` carries
  // the assessment; `tools` carries the description. We show
  // rows for every entry in either array, so a daemon without
  // the new RPC still renders the panel with all ASK badges
  // (safe default for a degraded state).
  const rows = (toolActions.length > 0 ? toolActions : tools.map((t) => ({
    name: t.name,
    description: t.description,
    defaultOpKind: 'EXEC',
    samplePath: null,
    isReadOnly: false,
    defaultAction: 'ASK',
    isSafe: false,
  })));

  const lowerFilter = filter.toLowerCase();
  const filtered = lowerFilter
    ? rows.filter((r) => r.name.toLowerCase().includes(lowerFilter)
        || r.description.toLowerCase().includes(lowerFilter))
    : rows;

  return (
    <div className="tools-panel-overlay" onClick={onClose}>
      <div className="tools-panel" onClick={(e) => e.stopPropagation()}>
        <div className="tools-panel-header">
          <h2>Tools & Permission</h2>
          <div className="tools-panel-header-actions">
            {/* ↻ refresh button. Always visible so the
                user can re-fetch even when the table is empty
                (the most common reason they'd want a manual
                refresh). The `refreshing` flag spins the icon
                via CSS for the duration of the round-trip. */}
            <button
              className={`tools-panel-refresh ${refreshing ? 'is-refreshing' : ''}`}
              onClick={() => void onRefresh()}
              title={`重新拉取 tool 列表 (上次: ${lastRefreshText})`}
            >
              ↻
            </button>
            <button className="close-btn" onClick={onClose} title="Close (Esc)">×</button>
          </div>
        </div>
        <p className="tools-panel-help">
          Each tool's <strong>default action</strong> is what the engine would do for
          a typical call shape. Read-only tools are always ALLOW. Mutating tools are
          ASK or ALLOW based on the path / op-kind rules in <code>.aethercode/config.json</code>.
          The badge is a hint — a real call may still prompt if the matrix says ASK.
        </p>
        {/* last-refresh timestamp line. Sits under the
            help text and above the filter input so the user
            can always see "what state is the data in". When
            the panel auto-refreshes on mount, this updates
            from "从未拉取" to "刚刚" without any user action. */}
        <div className="tools-panel-meta">
          最后刷新: <span className="tools-panel-meta-time">{lastRefreshText}</span>
          {tools.length > 0 && <> · {tools.length} 个工具</>}
        </div>
        <input
          className="tools-panel-filter"
          type="text"
          placeholder="Filter by name or description…"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          autoFocus
        />
        <div className="tools-panel-table">
          <div className="tools-panel-row tools-panel-head">
            <span className="col-name">Tool</span>
            <span className="col-op">Default op</span>
            <span className="col-action">Action</span>
          </div>
          {filtered.length === 0 ? (
            <div className="tools-panel-empty">
              {rows.length === 0
                // clearer empty state. The old
                // "daemon is starting up…" was a passive
                // message that left the user wondering
                // when (or whether) to act. The new text
                // tells them exactly what to do: click
                // ↻ in the header (or wait for the
                // 30 s auto-refresh).
                ? (
                  <div className="tools-panel-empty-empty">
                    <div>暂无工具数据</div>
                    <div className="tools-panel-empty-hint">
                      正在尝试拉取…如果长时间为空,点 ↻ 重试或检查 daemon。
                    </div>
                  </div>
                )
                : 'No tools match the filter.'}
            </div>
          ) : (
            filtered.map((r) => (
              <div key={r.name} className="tools-panel-row">
                <span className="col-name">
                  <span className="tool-name">{r.name}</span>
                  <span className="tool-desc">{r.description}</span>
                </span>
                <span className="col-op">
                  <code>{r.defaultOpKind}</code>
                  {r.samplePath ? (
                    <span className="sample-path" title={r.samplePath}>
                      {truncate(r.samplePath, 36)}
                    </span>
                  ) : null}
                  {r.isReadOnly ? <span className="ro-badge">read-only</span> : null}
                </span>
                <span className="col-action">
                  <span
                    className="action-badge"
                    style={{ backgroundColor: ACTION_COLORS[r.defaultAction] ?? '#777' }}
                    title={
                      r.defaultAction === 'ALLOW'
                        ? 'Always auto-allowed for this shape'
                        : r.defaultAction === 'DENY'
                          ? 'Blocked for this shape — change the matrix to use'
                          : 'Will prompt the user on a real call'
                    }
                  >
                    {r.defaultAction}
                  </span>
                  {r.isSafe ? <span className="safe-badge" title="ALLOW for the typical call shape">✓ safe</span> : null}
                </span>
              </div>
            ))
          )}
        </div>
        <div className="tools-panel-footer">
          <span className="footer-stat">
            {rows.length} tool{rows.length === 1 ? '' : 's'} ·
            {' '}{rows.filter((r) => r.isSafe).length} safe ·
            {' '}{rows.filter((r) => r.defaultAction === 'DENY').length} denied
          </span>
          <span className="footer-hint">Esc to close</span>
        </div>
      </div>
    </div>
  );
}

function truncate(s: string, n: number): string {
  if (s.length <= n) return s;
  return '…' + s.slice(s.length - (n - 1));
}
