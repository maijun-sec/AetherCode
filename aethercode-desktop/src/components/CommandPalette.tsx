import { useEffect, useRef, useState, useMemo } from 'react';
import { useStore } from '../store';
import './CommandPalette.css';

/** VS Code-style command palette. Open with Ctrl/Cmd+K.
 *  Lists every session as a command (with the project name as
 *  the secondary text) plus "+ New session" at the bottom.
 *  ↑/↓ to navigate, Enter to invoke, Esc to close.
 *  Type to fuzzy-filter. Tab order: result rows only (the
 *  input itself is excluded so the focus chain doesn't trap
 *  the user). */
export function CommandPalette({ onClose }: { onClose: () => void }) {
  const {
    sessions,
    currentSessionId,
    isStreaming,
    switchSession,
    createNewSession,
  } = useStore();
  const [query, setQuery] = useState('');
  const [activeIdx, setActiveIdx] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLUListElement>(null);

  // Build the candidate list: every session, plus a synthetic
  // "+ New session" entry at the bottom.
  const items = useMemo(() => {
    const sessionItems = [...sessions]
      .sort((a, b) => {
        if (a.id === currentSessionId) return -1;
        if (b.id === currentSessionId) return 1;
        return (b.lastUsedAt ?? 0) - (a.lastUsedAt ?? 0);
      })
      .map((s) => ({
        kind: 'session' as const,
        id: s.id,
        label: s.name?.trim() || `Session ${s.id.slice(-8)}`,
        meta: s.lastUsedAt
          ? `${relativeTime(s.lastUsedAt)} · ${s.messageCount ?? 0} msg`
          : `${s.messageCount ?? 0} msg`,
        isCurrent: s.id === currentSessionId,
      }));
    return [
      ...sessionItems,
      { kind: 'new' as const, id: '__new__', label: '+ 新会话', meta: 'Start a fresh session', isCurrent: false },
    ];
  }, [sessions, currentSessionId]);

  // Fuzzy filter: case-insensitive substring match on label.
  const filtered = useMemo(() => {
    if (!query.trim()) return items;
    const q = query.toLowerCase();
    return items.filter((it) => it.label.toLowerCase().includes(q));
  }, [items, query]);

  // Reset activeIdx when filter changes.
  useEffect(() => { setActiveIdx(0); }, [query]);

  // Focus the input on open.
  useEffect(() => { inputRef.current?.focus(); }, []);

  // Scroll active row into view.
  useEffect(() => {
    if (!listRef.current) return;
    const row = listRef.current.querySelector<HTMLElement>(`[data-idx="${activeIdx}"]`);
    row?.scrollIntoView({ block: 'nearest' });
  }, [activeIdx, filtered.length]);

  const invoke = async (idx: number) => {
    const it = filtered[idx];
    if (!it) return;
    if (it.kind === 'new') {
      // mint a fresh local session id instead of
      // clearing the current one. Same UX as SessionList's
      // "+ New Session" button.
      createNewSession();
    } else if (it.id !== currentSessionId) {
      try { await switchSession(it.id); } catch (e) { console.error(e); }
    }
    onClose();
  };

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') { e.preventDefault(); onClose(); return; }
    if (e.key === 'Enter') { e.preventDefault(); void invoke(activeIdx); return; }
    if (e.key === 'ArrowDown') { e.preventDefault(); setActiveIdx((i) => Math.min(i + 1, filtered.length - 1)); return; }
    if (e.key === 'ArrowUp') { e.preventDefault(); setActiveIdx((i) => Math.max(i - 1, 0)); return; }
    if (e.key === 'Tab') { e.preventDefault(); setActiveIdx((i) => (e.shiftKey ? Math.max(0, i - 1) : Math.min(filtered.length - 1, i + 1))); }
  };

  return (
    <div className="command-palette-backdrop" onClick={onClose}>
      <div
        className="command-palette"
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
        onClick={(e) => e.stopPropagation()}
        onKeyDown={onKey}
      >
        <input
          ref={inputRef}
          className="command-palette-input"
          type="text"
          placeholder="切换 session…  (↑↓ 导航 · Enter 选择 · Esc 关闭)"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          spellCheck={false}
          autoComplete="off"
        />
        <ul className="command-palette-list" ref={listRef} role="listbox">
          {filtered.length === 0 ? (
            <li className="command-palette-empty">没有匹配的 session</li>
          ) : (
            filtered.map((it, idx) => (
              <li
                key={it.id}
                data-idx={idx}
                className={[
                  'command-palette-item',
                  idx === activeIdx ? 'active' : '',
                  it.kind === 'new' ? 'is-new' : '',
                  it.isCurrent ? 'is-current' : '',
                ].filter(Boolean).join(' ')}
                role="option"
                aria-selected={idx === activeIdx}
                onMouseEnter={() => setActiveIdx(idx)}
                onClick={() => void invoke(idx)}
              >
                <span className="command-palette-icon">
                  {it.kind === 'new' ? '+' : (it.isCurrent && isStreaming ? '●' : '○')}
                </span>
                <span className="command-palette-label">{it.label}</span>
                <span className="command-palette-meta">{it.meta}</span>
                {it.isCurrent && <span className="command-palette-badge">current</span>}
              </li>
            ))
          )}
        </ul>
        <div className="command-palette-footer">
          <span><kbd>↑</kbd><kbd>↓</kbd> 导航</span>
          <span><kbd>Enter</kbd> 选择</span>
          <span><kbd>Esc</kbd> 关闭</span>
        </div>
      </div>
    </div>
  );
}

function relativeTime(ts: number): string {
  const dt = Date.now() - ts;
  if (dt < 60_000) return 'just now';
  if (dt < 3_600_000) return `${Math.floor(dt / 60_000)}m ago`;
  if (dt < 86_400_000) return `${Math.floor(dt / 3_600_000)}h ago`;
  return `${Math.floor(dt / 86_400_000)}d ago`;
}
