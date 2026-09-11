import { useEffect, useState, useMemo } from 'react';
import { useStore } from '../store';
import { rpc } from '../lib/methods';
import './MemoryPanel.css';

/** Memory browser/editor. Three collapsible scopes
 *  (USER / PROJECT / LOCAL) listing markdown files the engine
 *  reads as soft context. Click a file to open the editor; the
 *  editor is a plain `<textarea>` with a sticky "保存 (Save)" / "取消 (Cancel)"
 *  bar so the user can see their changes before committing.
 *
 *  Save path:
 *    1. user edits textarea (dirty state, "保存" enabled)
 *    2. user clicks 保存 (Save) → writeMemoryFile RPC
 *    3. on success → clear dirty, refresh mtime, surface "[saved]"
 *       in the status line
 *
 *  Trade-off: we keep one editor at a time (single-tab). A multi-
 *  tab editor with split view would be nicer for power users but
 *  R92 is the first cut — the goal is "the user can see and edit
 *  memory without asking the model", not "full IDE". */

type Scope = 'USER' | 'PROJECT' | 'LOCAL';
const SCOPES: { key: Scope; label: string; desc: string }[] = [
  { key: 'USER',    label: 'USER',    desc: '~/.aethercode/agent-memory/<agentType>/' },
  { key: 'PROJECT', label: 'PROJECT', desc: '<cwd>/.aethercode/agent-memory/<agentType>/' },
  { key: 'LOCAL',   label: 'LOCAL',   desc: '<cwd>/.aethercode/agent-memory-local/<agentType>/' },
];

type FileEntry = { name: string; path: string; size: number; mtime: number; isEntry: boolean; scope: Scope };

function formatSize(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

function formatTime(ts: number): string {
  if (!ts) return 'never';
  const dt = Date.now() - ts;
  if (dt < 60_000) return 'just now';
  if (dt < 3_600_000) return `${Math.floor(dt / 60_000)}m ago`;
  if (dt < 86_400_000) return `${Math.floor(dt / 3_600_000)}h ago`;
  return new Date(ts).toLocaleDateString();
}

function newFileName(): string {
  const d = new Date();
  return `note-${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}-${String(d.getHours()).padStart(2, '0')}${String(d.getMinutes()).padStart(2, '0')}.md`;
}

export function MemoryPanel() {
  const engineState = useStore((s) => s.engineState);
  // Default agent type = current model id (matches what the
  // engine uses in MemoryRecall). Fall back to "default" so we
  // still browse SOMETHING on a fresh daemon.
  const agentType = engineState?.model ?? 'default';

  // Map of scope -> FileEntry[]. Refreshed on mount and after
  // each save/delete. We keep the loading state per-scope so a
  // single failed scope doesn't blank the whole panel.
  const [files, setFiles] = useState<Record<Scope, FileEntry[]>>({ USER: [], PROJECT: [], LOCAL: [] });
  const [loading, setLoading] = useState<Record<Scope, boolean>>({ USER: false, PROJECT: false, LOCAL: false });
  const [expanded, setExpanded] = useState<Record<Scope, boolean>>({ USER: true, PROJECT: true, LOCAL: true });

  // open tabs. Each tab is a (scope, name, content,
  // originalContent) tuple; the array preserves the user's
  // open-file context across scope browsing. `activeTabIdx`
  // is the index into `tabs` that's currently in the editor.
  // We cap at 8 tabs so a runaway open-loop doesn't blow out
  // the right panel.
  interface Tab { scope: Scope; name: string; content: string; originalContent: string; }
  const [tabs, setTabs] = useState<Tab[]>([]);
  const [activeTabIdx, setActiveTabIdx] = useState<number>(-1);
  const [status, setStatus] = useState<{ kind: 'info' | 'error' | 'success'; text: string } | null>(null);

  const activeTab = activeTabIdx >= 0 ? tabs[activeTabIdx] : null;
  const dirty = activeTab != null && activeTab.content !== activeTab.originalContent;
  // Stable helpers so child components can call them without
  // a new closure per render.
  const tabKey = (scope: Scope, name: string) => `${scope}::${name}`;

  const refresh = async (scope: Scope) => {
    setLoading((s) => ({ ...s, [scope]: true }));
    try {
      const r = await rpc.listMemoryFiles(scope, agentType);
      // defensive fallback. The R92 → R127 wire-name
      // rename nearly broke us once (see listMemoryFiles in
      // methods.ts); if the daemon ever returns the wrong
      // shape again and `r.files` is undefined, coerce to
      // `[]` so the useMemo `files.USER.length + ... + files.LOCAL.length`
      // doesn't crash the whole right panel.
      const incoming = ((r as any)?.files ?? []) as FileEntry[];
      setFiles((f) => ({ ...f, [scope]: Array.isArray(incoming) ? incoming : [] }));
    } catch (e: any) {
      setStatus({ kind: 'error', text: `List ${scope} failed: ${e?.message ?? String(e)}` });
    } finally {
      setLoading((s) => ({ ...s, [scope]: false }));
    }
  };

  useEffect(() => {
    void refresh('USER');
    void refresh('PROJECT');
    void refresh('LOCAL');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentType]);

  const openFile = async (scope: Scope, name: string) => {
    // if the file is already in tabs, just activate it.
    // Otherwise read from disk and add a new tab.
    const existing = tabs.findIndex((t) => t.scope === scope && t.name === name);
    if (existing >= 0) {
      setActiveTabIdx(existing);
      return;
    }
    try {
      const r = await rpc.readMemoryFile(scope, agentType, name);
      setTabs((prev) => {
        const next = [...prev, { scope, name, content: r.content, originalContent: r.content }];
        // Cap at 8 tabs; drop the oldest if exceeded. The
        // dropped tab's unsaved changes are lost — we surface
        // a status line so the user knows.
        if (next.length > 8) {
          const dropped = next.shift()!;
          setStatus({ kind: 'info', text: `Closed oldest tab "${dropped.scope}/${dropped.name}" (unsaved changes lost)` });
        }
        return next;
      });
      setActiveTabIdx(tabs.length < 8 ? tabs.length : tabs.length - 1);
      // Recompute outside of setTabs to avoid stale closure:
      setActiveTabIdx((prev) => prev + 1);
    } catch (e: any) {
      setStatus({ kind: 'error', text: `Read failed: ${e?.message ?? String(e)}` });
    }
  };

  const closeTab = (idx: number) => {
    const tab = tabs[idx];
    if (!tab) return;
    if (tab.content !== tab.originalContent) {
      if (!confirm(`Close ${tab.scope}/${tab.name}? Unsaved changes will be lost.`)) return;
    }
    setTabs((prev) => prev.filter((_, i) => i !== idx));
    setActiveTabIdx((prev) => {
      if (idx < prev) return prev - 1;
      if (idx === prev) return Math.min(prev, tabs.length - 2);
      return prev;
    });
  };

  const save = async () => {
    if (!activeTab) return;
    try {
      await rpc.writeMemoryFile(activeTab.scope, agentType, activeTab.name, activeTab.content);
      setTabs((prev) => prev.map((t, i) => i === activeTabIdx
        ? { ...t, originalContent: t.content }
        : t));
      setStatus({ kind: 'success', text: `Saved ${activeTab.scope}/${activeTab.name}` });
      await refresh(activeTab.scope);
    } catch (e: any) {
      setStatus({ kind: 'error', text: `Save failed: ${e?.message ?? String(e)}` });
    }
  };

  const remove = async (scope: Scope, name: string) => {
    if (!confirm(`Delete ${scope}/${name}? This cannot be undone.`)) return;
    try {
      await rpc.deleteMemoryFile(scope, agentType, name);
      setStatus({ kind: 'success', text: `Deleted ${scope}/${name}` });
      // also close the tab if open.
      const idx = tabs.findIndex((t) => t.scope === scope && t.name === name);
      if (idx >= 0) {
        setTabs((prev) => prev.filter((_, i) => i !== idx));
        setActiveTabIdx((prev) => (idx < prev ? prev - 1 : Math.min(prev, tabs.length - 2)));
      }
      await refresh(scope);
    } catch (e: any) {
      setStatus({ kind: 'error', text: `Delete failed: ${e?.message ?? String(e)}` });
    }
  };

  const newFile = async (scope: Scope) => {
    const name = newFileName();
    try {
      await rpc.writeMemoryFile(scope, agentType, name, `# ${name}\n\nNew memory file.\n`);
      await refresh(scope);
      await openFile(scope, name);
    } catch (e: any) {
      setStatus({ kind: 'error', text: `Create failed: ${e?.message ?? String(e)}` });
    }
  };

  const totalFiles = useMemo(
    // defensive — if any of the three slots is
    // undefined (e.g. a refresh() race that hasn't populated
    // this scope yet, or a future bug in the wire layer),
    // treat it as an empty list so this useMemo can't crash
    // the whole panel. The legacy crash took the entire
    // right panel down with it; from here on the only thing
    // a missing slot can do is under-count the badge.
    () => (files.USER?.length ?? 0) + (files.PROJECT?.length ?? 0) + (files.LOCAL?.length ?? 0),
    [files],
  );

  return (
    <div className="memory-panel">
      <div className="section-header memory-header">
        <span>Memory</span>
        <span className="memory-count" title={`${totalFiles} files across 3 scopes`}>{totalFiles}</span>
      </div>
      <div className="memory-agent-hint" title={agentType}>
        agent: <code>{agentType}</code>
      </div>
      {status && (
        <div className={`memory-status memory-status-${status.kind}`}>{status.text}</div>
      )}
      {SCOPES.map(({ key, label, desc }) => (
        <div key={key} className="memory-scope">
          <div
            className="memory-scope-head"
            onClick={() => setExpanded((e) => ({ ...e, [key]: !e[key] }))}
            title={desc}
          >
            <span className="memory-scope-toggle">{expanded[key] ? '▼' : '▶'}</span>
            <span className="memory-scope-label">{label}</span>
            <span className="memory-scope-count">{files[key].length}</span>
            <button
              className="memory-scope-add"
              onClick={(e) => { e.stopPropagation(); void newFile(key); }}
              title="New file"
            >+</button>
          </div>
          {expanded[key] && (
            <ul className="memory-files">
              {loading[key] && <li className="memory-empty">Loading…</li>}
              {!loading[key] && files[key].length === 0 && (
                <li className="memory-empty">(empty) click + to create</li>
              )}
              {files[key].map((f) => {
                // a file is "active" if its tab is open AND it's
                // the active tab. Open-but-not-active still gets
                // a subtle indicator (the title row).
                const tabIdx = tabs.findIndex((t) => t.scope === key && t.name === f.name);
                const isActive = tabIdx === activeTabIdx;
                const isOpen = tabIdx >= 0;
                return (
                  <li
                    key={f.path}
                    className={`memory-file ${isActive ? 'active' : ''} ${isOpen ? 'open' : ''}`}
                  >
                    <div className="memory-file-row1" onClick={() => void openFile(key, f.name)}>
                      <span className="memory-file-name">{f.name}</span>
                      {f.isEntry && <span className="memory-file-entry" title="Entry file">★</span>}
                      {isOpen && <span className="memory-file-open-dot" title="Open in tab">●</span>}
                    </div>
                    <div className="memory-file-row2">
                      <span className="memory-file-meta">{formatSize(f.size)} · {formatTime(f.mtime)}</span>
                      <button
                        className="memory-file-del"
                        onClick={(e) => { e.stopPropagation(); void remove(key, f.name); }}
                        title="Delete"
                      >🗑</button>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      ))}
      {/* tabbed editor. The tab bar sits above the textarea;
          each tab is a clickable label with a × to close. The
          active tab's content is in `activeTab`. We hide the
          whole editor block when no tabs are open so the right
          panel can shrink to just the file lists. */}
      {activeTab && (
        <div className="memory-editor">
          <div className="memory-editor-tabs">
            {tabs.map((t, i) => {
              const tDirty = t.content !== t.originalContent;
              return (
                <div
                  key={tabKey(t.scope, t.name)}
                  className={`memory-editor-tab ${i === activeTabIdx ? 'active' : ''}`}
                  onClick={() => setActiveTabIdx(i)}
                  title={`${t.scope}/${t.name}`}
                >
                  <span className="memory-editor-tab-label">
                    {tDirty && <span className="memory-editor-tab-dot" title="Unsaved changes">●</span>}
                    {t.name}
                  </span>
                  <button
                    className="memory-editor-tab-close"
                    onClick={(e) => { e.stopPropagation(); closeTab(i); }}
                    title="Close tab"
                  >×</button>
                </div>
              );
            })}
          </div>
          <div className="memory-editor-head">
            <span className="memory-editor-path">
              <code>{activeTab.scope}/{activeTab.name}</code>
              {dirty && <span className="memory-editor-dirty" title="Unsaved changes">●</span>}
            </span>
            <div className="memory-editor-actions">
              <button
                className="memory-editor-save"
                onClick={save}
                disabled={!dirty}
                title={dirty ? 'Save (Ctrl+S)' : 'No changes'}
              >Save</button>
            </div>
          </div>
          <textarea
            className="memory-editor-text"
            value={activeTab.content}
            onChange={(e) => {
              const v = e.target.value;
              setTabs((prev) => prev.map((t, i) => i === activeTabIdx ? { ...t, content: v } : t));
            }}
            spellCheck={false}
            onKeyDown={(e) => {
              if (e.key === 's' && (e.metaKey || e.ctrlKey)) {
                e.preventDefault();
                if (dirty) void save();
              } else if (e.key === 'Escape') {
                e.preventDefault();
                closeTab(activeTabIdx);
              }
            }}
          />
        </div>
      )}
    </div>
  );
}
