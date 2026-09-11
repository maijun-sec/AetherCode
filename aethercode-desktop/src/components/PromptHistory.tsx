import { useEffect, useState, useMemo } from 'react';
import { useStore } from '../store';
import './PromptHistory.css';

/** Prompt history + template favorites.
 *
 *  Two related affordances in the right panel:
 *    • History  — last N user messages across all sessions,
 *      persisted in localStorage. Click to re-fill the input
 *      (the user reviews / edits before sending).
 *    • Templates — user-saved prompt snippets, also in
 *      localStorage. Insert at the cursor; "★ save current"
 *      captures whatever is in the input box.
 *
 *  Storage key: `aethercode-prompt-history` /
 *  `aethercode-prompt-templates`. Per-session scoping is a
 *  follow-up; the user said P2 is fine without it. The data
 *  shape is intentionally tiny ({ ts, text } for history;
 *  { id, label, text } for templates) so a 1MB localStorage
 *  cap holds years of usage. */

const HIST_KEY = 'aethercode-prompt-history';
const TMPL_KEY = 'aethercode-prompt-templates';
const HIST_MAX = 50;

interface HistEntry { ts: number; text: string; }
interface TmplEntry { id: string; label: string; text: string; slashName?: string; }

function load<T>(key: string, fallback: T): T {
  if (typeof window === 'undefined' || !window.localStorage) return fallback;
  try {
    const raw = window.localStorage.getItem(key);
    if (!raw) return fallback;
    return JSON.parse(raw) as T;
  } catch { return fallback; }
}
function save<T>(key: string, value: T) {
  if (typeof window === 'undefined' || !window.localStorage) return;
  try { window.localStorage.setItem(key, JSON.stringify(value)); } catch {}
}

function shortPreview(text: string, max = 60): string {
  const one = text.replace(/\s+/g, ' ').trim();
  return one.length > max ? one.slice(0, max - 1) + '…' : one;
}

export function PromptHistory() {
  const messages = useStore((s) => s.messages);
  const setCurrentInput = useStore((s) => s.setCurrentInput);

  // History: derived from messages[] + persisted localStorage. We
  // append new user messages on render; dedup by exact text. The
  // history is global (not per-session) — most users want
  // "the query I sent yesterday" to survive a session switch.
  const [history, setHistory] = useState<HistEntry[]>(() => load(HIST_KEY, [] as HistEntry[]));
  const [templates, setTemplates] = useState<TmplEntry[]>(() => load(TMPL_KEY, [] as TmplEntry[]));
  const [newLabel, setNewLabel] = useState('');
  const [status, setStatus] = useState<string | null>(null);

  // Sync user messages from the live store into history.
  useEffect(() => {
    if (!messages.length) return;
    const userMsgs = messages.filter((m) => m.role === 'user');
    if (!userMsgs.length) return;
    setHistory((prev) => {
      const seen = new Set(prev.map((h) => h.text));
      const additions: HistEntry[] = [];
      for (const m of userMsgs) {
        const text = m.content?.trim() ?? '';
        if (!text) continue;
        if (seen.has(text)) continue;
        seen.add(text);
        additions.push({ ts: m.timestamp ?? Date.now(), text });
      }
      if (!additions.length) return prev;
      const merged = [...additions, ...prev].slice(0, HIST_MAX);
      save(HIST_KEY, merged);
      return merged;
    });
  }, [messages]);

  const insertIntoInput = (text: string) => {
    setCurrentInput(text);
    setStatus(`已填入 ${text.length} 字`);
    setTimeout(() => setStatus(null), 1500);
  };

  const saveTemplate = () => {
    const { currentInput } = useStore.getState();
    const text = currentInput.trim();
    if (!text) {
      setStatus('当前输入框是空的');
      setTimeout(() => setStatus(null), 1500);
      return;
    }
    const label = newLabel.trim() || shortPreview(text, 30);
    const t: TmplEntry = { id: 't-' + Date.now(), label, text };
    const next = [t, ...templates].slice(0, 50);
    setTemplates(next);
    save(TMPL_KEY, next);
    setNewLabel('');
    setStatus(`已保存模板「${label}」`);
    setTimeout(() => setStatus(null), 1500);
  };

  const removeTemplate = (id: string) => {
    const next = templates.filter((t) => t.id !== id);
    setTemplates(next);
    save(TMPL_KEY, next);
  };

  const removeHistory = (ts: number) => {
    const next = history.filter((h) => h.ts !== ts);
    setHistory(next);
    save(HIST_KEY, next);
  };

  const clearHistory = () => {
    if (!confirm('清空所有历史 prompt?')) return;
    setHistory([]);
    save(HIST_KEY, []);
  };

  const sortedHistory = useMemo(
    () => [...history].sort((a, b) => b.ts - a.ts),
    [history],
  );

  return (
    <div className="prompt-history">
      <div className="section-header">
        <span>History</span>
        <span className="prompt-history-count" title={`${history.length} prompts in localStorage`}>{history.length}</span>
        {history.length > 0 && (
          <button className="prompt-history-clear" onClick={clearHistory} title="清空所有历史">清空</button>
        )}
      </div>
      {status && <div className="prompt-history-status">{status}</div>}
      <ul className="prompt-history-list">
        {sortedHistory.length === 0 && (
          <li className="prompt-history-empty">（还没有历史）</li>
        )}
        {sortedHistory.map((h) => (
          <li
            key={h.ts}
            className="prompt-history-item"
            onClick={() => insertIntoInput(h.text)}
            title="点击填入输入框"
          >
            <span className="prompt-history-text">{shortPreview(h.text, 80)}</span>
            <button
              className="prompt-history-del"
              onClick={(e) => { e.stopPropagation(); removeHistory(h.ts); }}
              title="从历史中删除"
            >×</button>
          </li>
        ))}
      </ul>
      <div className="prompt-history-templates">
        <div className="section-header">
          <span>Templates</span>
          <span className="prompt-history-count" title={`${templates.length} templates`}>{templates.length}</span>
        </div>
        <div className="prompt-history-tmpl-row">
          <input
            className="prompt-history-tmpl-input"
            type="text"
            placeholder="模板标签 (可空)"
            value={newLabel}
            onChange={(e) => setNewLabel(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); saveTemplate(); } }}
            maxLength={40}
          />
          <button
            className="prompt-history-tmpl-save"
            onClick={saveTemplate}
            title="将当前输入框的内容保存为模板"
          >★ 保存</button>
        </div>
        <ul className="prompt-history-list">
          {templates.length === 0 && (
            <li className="prompt-history-empty">（没有模板）</li>
          )}
          {templates.map((t) => (
            <li
              key={t.id}
              className="prompt-history-item prompt-history-tmpl"
              onClick={() => insertIntoInput(t.text)}
              title="点击填入输入框"
            >
              <span className="prompt-history-tmpl-label">★ {t.label}</span>
              {t.slashName && (
                <span
                  className="prompt-history-tmpl-slash"
                  title="在输入框打这个 /command 会展开此模板"
                >/{t.slashName}</span>
              )}
              <span className="prompt-history-text">{shortPreview(t.text, 80)}</span>
              {/* tag the template with a /command name. The
                  input is shown only on hover (`.prompt-history-tmpl`
                  has the affordance as a small ghost button). Click
                  to toggle edit / clear. Empty string clears the
                  command name. */}
              <button
                className="prompt-history-tmpl-cmd"
                onClick={(e) => {
                  e.stopPropagation();
                  const next = prompt('为此模板设置 /command 名 (留空清除):', t.slashName ?? '');
                  if (next === null) return;
                  const trimmed = next.trim().replace(/^\/+/, '').toLowerCase();
                  if (!/^[a-z0-9_-]{1,32}$/.test(trimmed)) {
                    if (trimmed !== '') {
                      setStatus(`无效:「${trimmed}」— 只允许小写字母数字和 _ -`);
                      setTimeout(() => setStatus(null), 2000);
                    }
                    return;
                  }
                  setTemplates((prev) => {
                    const next0 = prev.map((x) => x.id === t.id ? { ...x, slashName: trimmed } : x);
                    save(TMPL_KEY, next0);
                    return next0;
                  });
                }}
                title="为此模板设置 /command 名"
              >{t.slashName ? '✎' : '+ /cmd'}</button>
              <button
                className="prompt-history-del"
                onClick={(e) => { e.stopPropagation(); removeTemplate(t.id); }}
                title="删除模板"
              >×</button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
