import { useEffect, useRef, useState, useMemo } from 'react';
import { useStore } from '../store';
import { rpc } from '../lib/methods';
import { Kbd } from './atoms/Kbd';
import './RpcCommandPalette.css';

// persistent favourites + recently-used RPCs.
// localStorage-backed so a reload restores the
// user's "starred" set and the LRU of methods
// they've actually called. The keys live next
// to the R122 enginePrefs blob but are
// RPC-palette-specific (different lifecycle:
// these are view preferences, not engine
// configuration).
const FAV_KEY = 'aethercode.rpcFavorites';
const RECENT_KEY = 'aethercode.rpcRecent';
const RECENT_MAX = 12;

function readFavs(): string[] {
  try {
    const raw = localStorage.getItem(FAV_KEY);
    if (!raw) return [];
    const v = JSON.parse(raw);
    return Array.isArray(v) ? v.filter((x) => typeof x === 'string') : [];
  } catch { return []; }
}
function writeFavs(arr: string[]) {
  try { localStorage.setItem(FAV_KEY, JSON.stringify(arr)); }
  catch { /* localStorage quota / disabled — silent */ }
}
function readRecents(): string[] {
  try {
    const raw = localStorage.getItem(RECENT_KEY);
    if (!raw) return [];
    const v = JSON.parse(raw);
    return Array.isArray(v) ? v.filter((x) => typeof x === 'string') : [];
  } catch { return []; }
}
function writeRecents(arr: string[]) {
  try { localStorage.setItem(RECENT_KEY, JSON.stringify(arr.slice(0, RECENT_MAX))); }
  catch { /* localStorage quota / disabled — silent */ }
}

/**
 * raw-RPC command palette. Power-user escape
 * hatch for "I want to call an RPC the UI doesn't
 * have a button for". Hotkey: Ctrl/Cmd+Shift+K
 * (Ctrl/Cmd+K is the R88 session palette; this
 * chord stays out of the way of the common one).
 *
 * <p>Flow:
 * <ol>
 *   <li>User types a substring (e.g. "setM") — fuzzy
 *       filter against the 50+ RPC names from
 *       {@code /api/methods}.</li>
 *   <li>User picks an RPC. A JSON textarea appears
 *       below the list with the params placeholder
 *       ({@code {} for most, {@code {model: "..."}}
 *       for the few with named fields).</li>
 *   <li>User edits the JSON. Validation happens
 *       inline — invalid JSON shows a red border
 *       and disables the Execute button.</li>
 *   <li>User presses Enter (in the textarea) or
 *       clicks Execute. The RPC fires through
 *       {@code AetherCodeRpc.call}, which goes
 *       through the same instrumentation as every
 *       other RPC — the R116 diagnostic panel sees
 *       it and {@code recentRpcEvents} logs it.</li>
 *   <li>The result renders in a collapsible "Result"
 *       pane below the params. Errors render with
 *       a red border and the JSON-RPC error body.</li>
 * </ol>
 *
 * <p>The list is loaded lazily on first open (the
 * store's {@code loadRpcMethods} action does a
 * single GET). After that the palette is offline —
 * no re-fetches, even on re-open.
 */
export function RpcCommandPalette({ onClose }: { onClose: () => void }) {
  const { rpcMethods, rpcMethodInfos, loadRpcMethods } = useStore();
  const [query, setQuery] = useState('');
  // tag chip filter. Each entry is a
  // tag string the user has toggled on. A
  // method matches when it carries ALL of
  // the active tags (AND semantics, so the
  // user narrows by clicking more chips).
  // Empty set = "all tags" (no narrowing).
  // The chips are populated from the
  // RpcMethodInfo entries the daemon sends;
  // when the daemon is legacy, the set is
  // empty and the chip bar is hidden.
  const [activeTags, setActiveTags] = useState<Set<string>>(new Set());
  const [activeIdx, setActiveIdx] = useState(0);
  // favourite + recently-used sets. Loaded
  // from localStorage on mount (synchronous —
  // these are small and the palette is the only
  // surface that cares). The "selected" row's
  // star button toggles the favourite; a
  // successful execute() bumps the recent list.
  // Both lists are intersected with `filtered`
  // (the current tag / search filter) so they
  // respect the same narrowing rules.
  const [favs, setFavs] = useState<string[]>(() => readFavs());
  const [recents, setRecents] = useState<string[]>(() => readRecents());
  const [paramsText, setParamsText] = useState('{}');
  const [paramsError, setParamsError] = useState<string | null>(null);
  const [result, setResult] = useState<{
    ok: boolean;
    body: unknown;
    atMs: number;
  } | null>(null);
  const [executing, setExecuting] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const paramsRef = useRef<HTMLTextAreaElement>(null);

  // Lazy-load the method list the first time the
  // palette opens. After that the store's "already
  // have it" guard short-circuits.
  useEffect(() => { void loadRpcMethods(); }, [loadRpcMethods]);

  // Focus the search input on open.
  useEffect(() => { inputRef.current?.focus(); }, []);

  // derive the tag-counts map for the
  // chip bar. We show the user how many
  // methods each tag would reveal — "engine
  // (15)" tells the user the filter is worth
  // clicking. The map is computed once per
  // rpcMethodInfos change (not per render).
  const tagCounts = useMemo(() => {
    const m = new Map<string, number>();
    for (const info of rpcMethodInfos) {
      for (const tag of info.tags) {
        m.set(tag, (m.get(tag) ?? 0) + 1);
      }
    }
    return m;
  }, [rpcMethodInfos]);

  // Filter the method list. Two predicates
  // AND together: substring search + tag
  // filter. The tag filter is empty (= no
  // narrowing) when activeTags is empty, so
  // a legacy daemon (no tag info) still
  // gets the R121 substring behaviour.
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return rpcMethods.filter((name) => {
      if (q && !name.toLowerCase().includes(q)) return false;
      if (activeTags.size > 0) {
        const info = rpcMethodInfos.find((i) => i.name === name);
        if (!info) return false;
        for (const t of activeTags) {
          if (!info.tags.includes(t)) return false;
        }
      }
      return true;
    });
  }, [rpcMethods, rpcMethodInfos, query, activeTags]);

  // Reset active row when the filter changes.
  useEffect(() => { setActiveIdx(0); }, [query, activeTags]);

  // Scroll the active row into view as the user
  // arrows / types.
  useEffect(() => {
    if (!listRef.current) return;
    const row = listRef.current.querySelector<HTMLElement>(`[data-idx="${activeIdx}"]`);
    row?.scrollIntoView({ block: 'nearest' });
  }, [activeIdx, filtered.length]);

  const selected = filtered[activeIdx];

  // Live JSON validation as the user types in the
  // params textarea. Empty string counts as "use
  // {}" — the daemon accepts an empty params for
  // ping etc.
  useEffect(() => {
    if (!paramsText.trim()) {
      setParamsError(null);
      return;
    }
    try {
      JSON.parse(paramsText);
      setParamsError(null);
    } catch (e) {
      setParamsError((e as Error).message);
    }
  }, [paramsText]);

  const execute = async () => {
    if (!selected || executing) return;
    let parsed: unknown = {};
    if (paramsText.trim()) {
      try { parsed = JSON.parse(paramsText); }
      catch (e) {
        setParamsError((e as Error).message);
        return;
      }
    }
    setExecuting(true);
    const atMs = Date.now();
    try {
      // AetherCodeRpc.call emits a RpcEvent via
      // the R116 instrumentation, so the
      // diagnostic panel sees this call
      // automatically.
      const body = await rpc.call(selected, parsed as Record<string, unknown>);
      setResult({ ok: true, body, atMs });
      // bump recents on success only.
      // Errors don't earn a slot — a transient
      // "no such session" from a typo'd call
      // shouldn't pollute the LRU. The
      // bumped name is filtered to the visible
      // method list at render time, so a
      // favourite for a method the daemon
      // stopped serving (e.g. a deprecated
      // RPC) gracefully disappears from the
      // "Recent" section rather than showing
      // an empty row.
      setRecents((cur) => {
        const next = [selected, ...cur.filter((n) => n !== selected)].slice(0, RECENT_MAX);
        writeRecents(next);
        return next;
      });
    } catch (e) {
      setResult({ ok: false, body: e, atMs });
    } finally {
      setExecuting(false);
    }
  };

  // toggle a method's pinned state. The
  // favourite list is small (power users pin a
  // dozen methods at most) so we don't need an
  // LRU — insertion order is enough. The render
  // section then re-orders the row to the top
  // when it carries the fav tag.
  const toggleFav = (name: string) => {
    setFavs((cur) => {
      const next = cur.includes(name)
        ? cur.filter((n) => n !== name)
        : [...cur, name];
      writeFavs(next);
      return next;
    });
  };

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      // If a result is showing, Escape clears it
      // first (so the user can inspect without
      // accidentally closing the palette). A
      // second Escape closes.
      if (result) { setResult(null); return; }
      e.preventDefault();
      onClose();
      return;
    }
    if (e.key === 'Enter') {
      // Enter on the search input (or anywhere
      // outside the params textarea) fires
      // execute.
      if (e.target instanceof HTMLTextAreaElement) return;
      e.preventDefault();
      void execute();
      return;
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActiveIdx((i) => Math.min(i + 1, Math.max(0, filtered.length - 1)));
      return;
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActiveIdx((i) => Math.max(i - 1, 0));
      return;
    }
  };

  return (
    <div className="rpc-palette-backdrop" onClick={onClose}>
      <div
        className="rpc-palette"
        role="dialog"
        aria-modal="true"
        aria-label="RPC command palette"
        onClick={(e) => e.stopPropagation()}
        onKeyDown={onKey}
      >
        <div className="rpc-palette-header">
          <input
            ref={inputRef}
            className="rpc-palette-input"
            type="text"
            placeholder={`调任意 RPC — 搜索 ${rpcMethods.length || '...'} 个方法…`}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            spellCheck={false}
            autoComplete="off"
          />
          <span className="rpc-palette-count">
            {filtered.length} / {rpcMethods.length}
          </span>
        </div>

        {/* tag chip bar. Only rendered when
            the daemon returned at least one
            RpcMethodInfo entry (i.e. the daemon
            is R124+). Older daemons skip the bar
            entirely — the R121 substring search
            still works. The bar shows each tag
            the daemon knows about (from
            tagCounts) with its method count, in
            the order the daemon emitted them
            (so engine / session / workflow sit
            together — the layout order matches
            AetherCodeMethods.METHOD_TAGS).
            Click toggles a chip; the AND-
            semantics filter applies on every
            click. */}
        {tagCounts.size > 0 && (
          <div className="rpc-palette-tags" role="group" aria-label="Filter by tag">
            {Array.from(tagCounts.entries()).map(([tag, count]) => {
              const active = activeTags.has(tag);
              return (
                <button
                  key={tag}
                  type="button"
                  className={`rpc-palette-tag-chip${active ? ' is-active' : ''}`}
                  onClick={() => {
                    setActiveTags((cur) => {
                      const next = new Set(cur);
                      if (next.has(tag)) next.delete(tag);
                      else next.add(tag);
                      return next;
                    });
                  }}
                  title={
                    active
                      ? `Remove "${tag}" filter (currently AND'd with other active tags)`
                      : `Filter to methods tagged "${tag}" (${count} match)`
                  }
                >
                  {tag}
                  <span className="rpc-palette-tag-count">{count}</span>
                </button>
              );
            })}
            {activeTags.size > 0 && (
              <button
                type="button"
                className="rpc-palette-tag-clear"
                onClick={() => setActiveTags(new Set())}
                title="Clear all tag filters"
              >
                clear
              </button>
            )}
          </div>
        )}

        <ul className="rpc-palette-list" ref={listRef} role="listbox">
          {rpcMethods.length === 0 ? (
            <li className="rpc-palette-empty">
              加载中…（首次打开会拉取 {`{httpUrl}/api/methods`}）
            </li>
          ) : filtered.length === 0 ? (
            <li className="rpc-palette-empty">没有匹配的方法</li>
          ) : (
            <>
              {/* pinned section. Only
                  rendered when at least one
                  favourite survives the current
                  filter. The pinned list is in
                  insertion order (oldest first,
                  newest last) so the user sees a
                  stable order. Clicking the ★
                  unpins; the row then falls out
                  of this section but stays in
                  the main list below. */}
              {(() => {
                const pinned = filtered.filter((m) => favs.includes(m));
                if (pinned.length === 0) return null;
                return (
                  <li className="rpc-palette-section-header" aria-hidden="true">
                    ★ pinned <span className="rpc-palette-section-count">{pinned.length}</span>
                  </li>
                );
              })()}
              {filtered.map((m, idx) => {
                if (!favs.includes(m)) return null;
                return (
                  <li
                    key={`fav-${m}`}
                    data-idx={idx}
                    className={`rpc-palette-item rpc-palette-item-fav${idx === activeIdx ? ' active' : ''}`}
                    role="option"
                    aria-selected={idx === activeIdx}
                    onMouseEnter={() => setActiveIdx(idx)}
                    onClick={() => setActiveIdx(idx)}
                  >
                    <button
                      type="button"
                      className="rpc-palette-fav-btn is-pinned"
                      title="Unpin from favourites"
                      onClick={(e) => { e.stopPropagation(); toggleFav(m); }}
                    >
                      ★
                    </button>
                    <span className="rpc-palette-name">{m}</span>
                    <span className="rpc-palette-meta">
                      {(idx === activeIdx) ? '↵ select' : ''}
                    </span>
                  </li>
                );
              })}
              {/* recently-used section.
                  Only rendered when at least
                  one recent survives the
                  current filter AND is NOT
                  already pinned (pinned wins
                  — a recently-used favourite
                  lives in the pinned section
                  only, not both). Same
                  insertion-order as pinned. */}
              {(() => {
                const r = filtered.filter((m) => recents.includes(m) && !favs.includes(m));
                if (r.length === 0) return null;
                return (
                  <li className="rpc-palette-section-header" aria-hidden="true">
                    🕒 recent <span className="rpc-palette-section-count">{r.length}</span>
                  </li>
                );
              })()}
              {filtered.map((m, idx) => {
                if (!recents.includes(m) || favs.includes(m)) return null;
                return (
                  <li
                    key={`rec-${m}`}
                    data-idx={idx}
                    className={`rpc-palette-item rpc-palette-item-recent${idx === activeIdx ? ' active' : ''}`}
                    role="option"
                    aria-selected={idx === activeIdx}
                    onMouseEnter={() => setActiveIdx(idx)}
                    onClick={() => setActiveIdx(idx)}
                  >
                    <button
                      type="button"
                      className="rpc-palette-fav-btn is-unpinned"
                      title="Pin to favourites"
                      onClick={(e) => { e.stopPropagation(); toggleFav(m); }}
                    >
                      ☆
                    </button>
                    <span className="rpc-palette-name">{m}</span>
                    <span className="rpc-palette-meta">
                      {(idx === activeIdx) ? '↵ select' : ''}
                    </span>
                  </li>
                );
              })}
              {/* Regular list. Pinned + recent
                  are filtered out of the
                  bottom section so each
                  method appears exactly once
                  on screen. */}
              {(() => {
                const special = new Set([...favs, ...recents]);
                const rest = filtered.filter((m) => !special.has(m));
                if (rest.length === 0) return null;
                if (favs.length > 0 || recents.length > 0) {
                  return (
                    <li className="rpc-palette-section-header" aria-hidden="true">
                      all <span className="rpc-palette-section-count">{rest.length}</span>
                    </li>
                  );
                }
                return null;
              })()}
              {filtered.map((m, idx) => {
                if (favs.includes(m) || recents.includes(m)) return null;
                return (
                  <li
                    key={`all-${m}`}
                    data-idx={idx}
                    className={`rpc-palette-item${idx === activeIdx ? ' active' : ''}`}
                    role="option"
                    aria-selected={idx === activeIdx}
                    onMouseEnter={() => setActiveIdx(idx)}
                    onClick={() => setActiveIdx(idx)}
                  >
                    <button
                      type="button"
                      className="rpc-palette-fav-btn is-unpinned"
                      title="Pin to favourites"
                      onClick={(e) => { e.stopPropagation(); toggleFav(m); }}
                    >
                      ☆
                    </button>
                    <span className="rpc-palette-name">{m}</span>
                    <span className="rpc-palette-meta">
                      {(idx === activeIdx) ? '↵ select' : ''}
                    </span>
                  </li>
                );
              })}
            </>
          )}
        </ul>

        {selected && (
          <div className="rpc-palette-params">
            <label className="rpc-palette-label" htmlFor="rpc-params">
              params (JSON)
            </label>
            <textarea
              ref={paramsRef}
              id="rpc-params"
              className={`rpc-palette-params-input${paramsError ? ' has-error' : ''}`}
              value={paramsText}
              onChange={(e) => setParamsText(e.target.value)}
              spellCheck={false}
              autoComplete="off"
              rows={3}
            />
            {paramsError && (
              <div className="rpc-palette-params-error" role="alert">
                ⚠ {paramsError}
              </div>
            )}
            <div className="rpc-palette-params-actions">
              <button
                className="rpc-palette-execute"
                disabled={!!paramsError || executing}
                onClick={() => void execute()}
              >
                {executing ? '执行中…' : `执行 ${selected} (Enter)`}
              </button>
            </div>
          </div>
        )}

        {result && (
          <div className={`rpc-palette-result ${result.ok ? 'is-ok' : 'is-err'}`}>
            <div className="rpc-palette-result-header">
              <span>
                {result.ok ? '✓' : '✗'}{' '}
                {result.ok ? 'ok' : 'error'}{' '}
                <span className="rpc-palette-result-time">
                  · {new Date(result.atMs).toLocaleTimeString()}
                </span>
              </span>
              <button
                className="rpc-palette-result-close"
                onClick={() => setResult(null)}
                aria-label="Close result"
              >
                ×
              </button>
            </div>
            <pre className="rpc-palette-result-body">
              {JSON.stringify(result.body, null, 2)}
            </pre>
          </div>
        )}

        <div className="rpc-palette-footer">
          <span><Kbd>↑</Kbd><Kbd>↓</Kbd> 导航</span>
          <span><Kbd>Enter</Kbd> 执行</span>
          <span><Kbd>Esc</Kbd> 关闭 / 清结果</span>
        </div>
      </div>
    </div>
  );
}
