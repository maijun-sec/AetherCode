import { useEffect, useMemo, useRef, useState } from 'react';
import { useStore } from '../store';
import type { SessionInfo } from '../lib/methods';
import { Kbd } from './atoms/Kbd';
import './SessionPickerModal.css';

/**
 * keyboard-driven session picker modal.
 *
 * Opens with Ctrl/Cmd+Shift+P (handled in App.tsx). Renders
 * a fuzzy-match search box over all known sessions plus a
 * "+ New session" row. Arrow keys move the highlight, Enter
 * switches, Esc closes.
 *
 * Visual model (intentionally VSCode-quick-open-like):
 *    ┌─────────────────────────────────────────┐
 *    │ ⌕  jump to session…                    │
 *    ├─────────────────────────────────────────┤
 *    │ ● fathom-dfa · R437 验证   just now  ✓ │  ← current
 *    │ ○ AetherCode · SubTask 折叠   5m     ▷ │
 *    │ ○ fathom-dfa · 修 Z3 leak     2h       │
 *    │ + new session                          │
 *    └─────────────────────────────────────────┘
 *
 * Why a modal in addition to the sidebar:
 *   - The sidebar (SessionList) shows the active session
 *     at a glance. The picker is for keyboard-driven
 *     "I have 12 sessions, jump to one" — fuzzy search
 *     is the killer feature when the list is long.
 *   - Same shape as CommandPalette (prior round) so the
 *     shortcut stack is consistent (Ctrl/Cmd+Shift+P for
 *     picker, Ctrl/Cmd+P for commands).
 */
export function SessionPickerModal({
  open,
  onClose,
}: {
  open: boolean;
  onClose: () => void;
}) {
  const { sessions, currentSessionId, switchSession, createNewSession } = useStore();
  const [query, setQuery] = useState('');
  const [highlight, setHighlight] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  // Sorted: current pinned to top, then by lastUsedAt desc.
  // "+ new session" is a synthetic row at the bottom.
  const sorted = useMemo(() => {
    return [...sessions].sort((a, b) => {
      if (a.id === currentSessionId) return -1;
      if (b.id === currentSessionId) return 1;
      return (b.lastUsedAt ?? 0) - (a.lastUsedAt ?? 0);
    });
  }, [sessions, currentSessionId]);

  // Filtered list. Empty query = show all + new-session row.
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    const matched = q
      ? sorted.filter((s) => matches(s, q))
      : sorted;
    // Always show the "+ new session" row.
    return [
      ...matched,
      { __new: true as const },
    ];
  }, [sorted, query]);

  // Reset highlight when the filtered list shape changes.
  useEffect(() => {
    setHighlight((h) => Math.min(h, Math.max(0, filtered.length - 1)));
  }, [filtered.length]);

  // Focus the search input on open. Also reset the query
  // so re-opening the modal shows the full list.
  useEffect(() => {
    if (open) {
      setQuery('');
      setHighlight(0);
      // requestAnimationFrame avoids a race with the
      // browser's focus management on the freshly-mounted
      // input element.
      requestAnimationFrame(() => inputRef.current?.focus());
    }
  }, [open]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setHighlight((h) => Math.min(h + 1, filtered.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setHighlight((h) => Math.max(h - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const row = filtered[highlight];
      if (!row) return;
      if ('__new' in row) {
        createNewSession();
        onClose();
      } else if (row.id !== currentSessionId) {
        void switchSession(row.id);
        onClose();
      } else {
        // Already on this session — just close.
        onClose();
      }
    } else if (e.key === 'Escape') {
      e.preventDefault();
      onClose();
    }
  };

  if (!open) return null;

  return (
    <div
      className="session-picker-overlay"
      role="dialog"
      aria-modal="true"
      aria-label="Session picker"
      onClick={onClose}
    >
      <div
        className="session-picker-modal"
        onClick={(e) => e.stopPropagation()}
      >
        <input
          ref={inputRef}
          className="session-picker-input"
          type="text"
          placeholder="jump to session…"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setHighlight(0);
          }}
          onKeyDown={handleKeyDown}
        />
        <ul className="session-picker-list" role="listbox">
          {filtered.map((row, idx) => {
            if ('__new' in row) {
              return (
                <li
                  key="__new"
                  className={[
                    'session-picker-item',
                    'session-picker-new',
                    idx === highlight ? 'highlight' : '',
                  ].filter(Boolean).join(' ')}
                  role="option"
                  aria-selected={idx === highlight}
                  onMouseEnter={() => setHighlight(idx)}
                  onClick={() => {
                    createNewSession();
                    onClose();
                  }}
                >
                  <span className="session-picker-dot dot-new">+</span>
                  <div className="session-picker-info">
                    <div className="session-picker-label">new session</div>
                    <div className="session-picker-meta">start a fresh one</div>
                  </div>
                </li>
              );
            }
            const s = row as SessionInfo;
            const isCurrent = s.id === currentSessionId;
            return (
              <li
                key={s.id}
                className={[
                  'session-picker-item',
                  isCurrent ? 'active' : '',
                  idx === highlight ? 'highlight' : '',
                ].filter(Boolean).join(' ')}
                role="option"
                aria-selected={idx === highlight}
                onMouseEnter={() => setHighlight(idx)}
                onClick={() => {
                  if (isCurrent) {
                    onClose();
                  } else {
                    void switchSession(s.id);
                    onClose();
                  }
                }}
                title={s.id}
              >
                <span
                  className={[
                    'session-picker-dot',
                    isCurrent ? 'dot-current' : 'dot-idle',
                  ].join(' ')}
                />
                <div className="session-picker-info">
                  <div className="session-picker-label">{labelFor(s)}</div>
                  <div className="session-picker-meta">
                    {whenFor(s)}
                    {s.messageCount != null && (
                      <>
                        <span className="session-picker-sep">·</span>
                        {s.messageCount} msg
                      </>
                    )}
                  </div>
                </div>
                {isCurrent && (
                  <span className="session-picker-current-tag">current</span>
                )}
              </li>
            );
          })}
        </ul>
        <div className="session-picker-footer">
          <Kbd>↑</Kbd>
          <Kbd>↓</Kbd>
          <span>navigate</span>
          <Kbd>Enter</Kbd>
          <span>switch</span>
          <Kbd>Esc</Kbd>
          <span>close</span>
        </div>
      </div>
    </div>
  );
}

function labelFor(s: SessionInfo): string {
  if (s.name && s.name.trim()) return s.name;
  return `Session ${s.id.slice(-8)}`;
}

function whenFor(s: SessionInfo): string {
  if (!s.lastUsedAt) return '—';
  const dt = Date.now() - s.lastUsedAt;
  if (dt < 60_000) return 'just now';
  if (dt < 3_600_000) return `${Math.floor(dt / 60_000)}m`;
  if (dt < 86_400_000) return `${Math.floor(dt / 3_600_000)}h`;
  return `${Math.floor(dt / 86_400_000)}d`;
}

/** case-insensitive substring match on label, id,
 *  and (if present) the engine-provided name. Empty query
 *  matches everything. */
function matches(s: SessionInfo, q: string): boolean {
  if (labelFor(s).toLowerCase().includes(q)) return true;
  if (s.id.toLowerCase().includes(q)) return true;
  if (s.name && s.name.toLowerCase().includes(q)) return true;
  return false;
}
