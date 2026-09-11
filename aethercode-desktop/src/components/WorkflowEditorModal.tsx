// Workflow editor modal.
//
// /workflow create and /workflow modify both fire the
// `aethercode:open-workflow-editor` custom event. The
// App component (or MessageInput) listens for the event and
// mounts this modal with the action ("create" / "modify")
// and the workflow's current raw YAML (for modify) or a
// starter template (for create).
//
// prior round UX upgrade — the editor now feels like a real
// editor, not just a textarea. The body ships:
//   1. A live YAML "parse preview" that reads name,
//      description, version, and a count of declared
//      steps as the user types. Errors are surfaced
//      inline ("expected `id:` on line 7") instead of
//      only at save time.
//   2. An "insert step" palette with the 5 most common
//      step types (shell, agent, skill, parallel, gate).
//      Clicking a button inserts a well-formed snippet at
//      the cursor (or appends if the cursor is at EOF) and
//      focuses the textarea so the user can fill in
//      command/prompt/branch fields immediately.
//   3. A step list panel below the textarea. Each entry
//      is `id` + `type` + line number. Clicking jumps
//      the textarea scroll to that line and selects the
//      whole step block, so the user can copy/delete/reorder
//      without leaving the editor.
//   4. Cmd+S / Ctrl+S to save (in addition to the footer
//      button). Esc closes the modal (with the same
//      "discard changes?" semantics as Cancel).
//   5. Monospace indent guides in the textarea via a CSS
//      background pattern (1ch grid every 2 spaces) so
//      the user can see alignment at a glance.
//
// The "real" parse is still the daemon's WorkflowReader at
// save time; the preview here is a best-effort regex/indent
// walker that catches the common cases (top-level scalars,
// `steps:` block with `- id: ...` children). It is NOT a
// full YAML parser; complex flows (nested `parallel`
// branches, escaped strings, `inputs:` map) are
// best-effort. The save RPC is the source of truth.

import { useEffect, useMemo, useRef, useState } from 'react';
import { useStore } from '../store';
import './WorkflowEditorModal.css';

export interface WorkflowEditorOpenDetail {
  action: 'create' | 'modify';
  /** Name without extension. Set on modify; empty on create. */
  name?: string;
  /** Raw YAML body. Always set (starter template on create,
   *  current file content on modify). */
  raw?: string;
}

// a tiny best-effort extractor. Walks the body
// line-by-line, tracking the indent stack so we know when
// we're inside a `steps:` block. Returns top-level scalars
// and one level of `steps:` children with their line
// number, id, and type. NOT a full YAML parser; flows
// with quoting / multi-line scalars may give fuzzy
// results, but the common cases match.
interface ExtractedStep {
  id: string;
  type: string;
  line: number; // 1-indexed, the line of the `- id:` row
}
interface ExtractedWorkflow {
  name: string | null;
  description: string | null;
  version: number | null;
  steps: ExtractedStep[];
  /** Non-null when the walker noticed something that
   *  "looks like a step but is malformed" (e.g. a `- `
   *  without an `id:` field on the same line). The user
   *  sees this as a yellow warning in the status row. */
  warning: string | null;
}

function extractWorkflow(body: string): ExtractedWorkflow {
  const lines = body.split(/\r?\n/);
  let name: string | null = null;
  let description: string | null = null;
  let version: number | null = null;
  const steps: ExtractedStep[] = [];
  let warning: string | null = null;

  // Track the indent stack so we know when a `- ` line
  // belongs to the top-level `steps:` block (2-space
  // indent under a zero-indent `steps:`) and not to
  // a nested `branches:` list (4-space indent).
  let inTopSteps = false; // true between `steps:` (col 0) and the next col-0 key
  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i];
    const line = i + 1;
    // skip blanks and comments
    if (/^\s*(#|$)/.test(raw)) continue;
    const indent = raw.match(/^(\s*)/)?.[1].length ?? 0;
    const trimmed = raw.trim();

    // Top-level (0-indent) key
    if (indent === 0) {
      const m = trimmed.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.*)$/);
      if (m) {
        const key = m[1];
        const val = m[2].trim().replace(/^['"]|['"]$/g, '');
        inTopSteps = false;
        if (key === 'name' && val) name = val;
        else if (key === 'description' && val) description = val;
        else if (key === 'version' && val) {
          const n = Number(val);
          if (Number.isFinite(n)) version = n;
        } else if (key === 'steps') {
          inTopSteps = true;
        }
        continue;
      }
      inTopSteps = false;
      continue;
    }

    // Inside `steps:` (2-space indent) — `- id: ...`
    if (inTopSteps && indent === 2 && trimmed.startsWith('- ')) {
      const after = trimmed.slice(2);
      const idMatch = after.match(/^id\s*:\s*(.*)$/);
      if (idMatch) {
        const id = idMatch[1].trim().replace(/^['"]|['"]$/g, '');
        // type may be on the same line OR on a child line
        let type = '';
        const typeInline = after.match(/type\s*:\s*([A-Za-z_][A-Za-z0-9_]*)/);
        if (typeInline) {
          type = typeInline[1];
        } else {
          // Look at the next non-blank, more-indented line
          for (let j = i + 1; j < lines.length; j++) {
            const child = lines[j];
            if (/^\s*(#|$)/.test(child)) continue;
            const childIndent = child.match(/^(\s*)/)?.[1].length ?? 0;
            if (childIndent <= 2) break;
            const t = child.trim().match(/^type\s*:\s*([A-Za-z_][A-Za-z0-9_]*)/);
            if (t) { type = t[1]; break; }
          }
        }
        steps.push({ id, type, line });
        continue;
      } else {
        // A `- ` with no `id:` — flag it so the user knows
        // the daemon will reject the file.
        if (!warning) {
          warning = `line ${line}: a \`- \` row is missing \`id:\` — every step needs one`;
        }
      }
    }
  }

  return { name, description, version, steps, warning };
}

// snippet templates for the "insert step" palette.
// Kept inline so the editor works offline (no resource
// fetch); the user can edit the inserted block before
// saving. Each template ends with a newline so the cursor
// lands at the start of a fresh row.
const STEP_SNIPPETS: Record<string, string> = {
  shell:
`  - id: step-name
    type: shell
    cmd: "echo hello"
    timeout: 60000
    continue_on_error: false
`,
  agent:
`  - id: step-name
    type: agent
    agent: coder
    prompt: |
      Replace this with the task. Multi-line YAML literal
      keeps newlines so the prompt reads naturally.
    timeout: 300000
`,
  skill:
`  - id: step-name
    type: skill
    skill: code-review
    inputs:
      scope: "git diff main..HEAD"
    continue_on_error: true
`,
  parallel:
`  - id: step-name
    type: parallel
    branches:
      - id: branch-a
        type: shell
        cmd: "echo a"
      - id: branch-b
        type: shell
        cmd: "echo b"
`,
  gate:
`  - id: step-name
    type: gate
    when: "{{steps.prev.exitCode == 0}}"
    then:
      - id: ok-path
        type: shell
        cmd: "echo ok"
    else_:
      - id: fail-path
        type: shell
        cmd: "echo failed"
`,
};

const STEP_TYPES: { key: keyof typeof STEP_SNIPPETS; label: string }[] = [
  { key: 'shell',   label: '+ shell' },
  { key: 'agent',   label: '+ agent' },
  { key: 'skill',   label: '+ skill' },
  { key: 'parallel', label: '+ parallel' },
  { key: 'gate',    label: '+ gate' },
];

export function WorkflowEditorModal() {
  const [open, setOpen] = useState<WorkflowEditorOpenDetail | null>(null);
  const [name, setName] = useState('');
  const [body, setBody] = useState('');
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);
  const refreshWorkflows = useStore((s) => s.refreshWorkflows);
  const availableWorkflows = useStore((s) => s.availableWorkflows);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  // listen for the custom event fired by
  // /workflow create / /workflow modify. The detail
  // includes the action and the initial raw content.
  useEffect(() => {
    const onOpen = (e: Event) => {
      const ce = e as CustomEvent<WorkflowEditorOpenDetail>;
      const d = ce.detail;
      setOpen(d);
      setName(d.name ?? '');
      setBody(d.raw ?? '');
      setErr(null);
      setSaving(false);
      setDirty(false);
    };
    window.addEventListener('aethercode:open-workflow-editor', onOpen);
    return () => window.removeEventListener('aethercode:open-workflow-editor', onOpen);
  }, []);

  // global keybindings. Cmd/Ctrl+S saves; Esc
  // closes (with discard-changes warning if dirty). We
  // attach to `document` rather than the textarea so the
  // shortcut works even when the user is in the name input
  // or focused on a step list item. Bail early if the
  // modal isn't open.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        if (!saving) void handleSave();
      } else if (e.key === 'Escape') {
        e.preventDefault();
        handleCancel();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, saving, name, body, dirty]);

  // live parse preview. Recomputed on every body
  // change; the result drives the step list, the status
  // row, and the body validity badge.
  const extracted = useMemo(() => extractWorkflow(body), [body]);

  if (!open) return null;

  // derive a tentative default name from the YAML
  // body for create mode. The user can override before save.
  // We look for `^name: <value>` at the top of the body.
  const derivedName = (() => {
    if (name) return name;
    return extracted.name ?? '';
  })();

  const handleSave = async () => {
    const finalName = (name || derivedName).trim();
    if (!finalName) {
      setErr('name 不能为空');
      return;
    }
    if (!/^[a-z0-9][a-z0-9_-]*$/i.test(finalName)) {
      setErr('name 必须是 kebab-case (字母数字 + _ -)');
      return;
    }
    if (!body.trim()) {
      setErr('内容不能为空');
      return;
    }
    setSaving(true);
    setErr(null);
    try {
      const r = await (await import('../lib/methods')).rpc.writeWorkflow(finalName, body);
      if (!r?.ok) {
        setErr(r?.reason ?? '保存失败');
        setSaving(false);
        return;
      }
      await refreshWorkflows();
      setSaving(false);
      setDirty(false);
      setOpen(null);
    } catch (e: any) {
      setErr(e?.message ?? String(e));
      setSaving(false);
    }
  };

  const handleCancel = () => {
    if (dirty && !window.confirm('放弃未保存的修改？')) return;
    setOpen(null);
    setErr(null);
    setDirty(false);
  };

  // insert a step snippet at the cursor (or
  // append to EOF if the cursor is past the end). We
  // preserve the textarea's selection state, splice the
  // snippet, then re-focus + place the cursor at the
  // end of the inserted block. The `id: step-name`
  // placeholder is selected so the user can type to
  // replace it.
  const insertSnippet = (key: keyof typeof STEP_SNIPPETS) => {
    const ta = textareaRef.current;
    if (!ta) return;
    const snippet = STEP_SNIPPETS[key];
    const start = ta.selectionStart;
    const end = ta.selectionEnd;
    const before = body.slice(0, start);
    const after = body.slice(end);
    // Make sure we're on a fresh line: if `before` does
    // not end with a newline, prepend one. Same for
    // `after` — don't break the user's next line.
    const needLeadingNl = before.length > 0 && !before.endsWith('\n');
    const needTrailingNl = after.length > 0 && !after.startsWith('\n');
    const insert = (needLeadingNl ? '\n' : '') + snippet + (needTrailingNl ? '' : '');
    const next = before + insert + after;
    setBody(next);
    setDirty(true);
    // Place cursor on the first `id:` line, just after
    // `id: ` so the user can type the name. The snippet's
    // first line is `  - id: <placeholder>` — measure
    // the prefix length and use it as the offset.
    const idOffset = before.length + (needLeadingNl ? 1 : 0) + snippet.indexOf('step-name') + 'step-name'.length;
    requestAnimationFrame(() => {
      ta.focus();
      ta.setSelectionRange(idOffset, idOffset);
    });
  };

  // jump the textarea scroll to the line of a
  // step and select the whole step block. The block
  // spans from the `- id:` line to the next sibling
  // step (or end of body). A simple regex from the
  // saved line is enough; the user can re-click if
  // their hand-edited file has odd structure.
  const jumpToStep = (line: number) => {
    const ta = textareaRef.current;
    if (!ta) return;
    const lines = body.split('\n');
    const startIdx = line - 1;
    let endIdx = lines.length;
    for (let i = startIdx + 1; i < lines.length; i++) {
      // next sibling `- ` at column 2 OR a col-0 key
      if (/^  - /.test(lines[i]) || /^[A-Za-z_]/.test(lines[i])) {
        endIdx = i;
        break;
      }
    }
    const startChar = lines.slice(0, startIdx).join('\n').length + (startIdx > 0 ? 1 : 0);
    const endChar = lines.slice(0, endIdx).join('\n').length;
    ta.focus();
    ta.setSelectionRange(startChar, endChar);
    // Approximate scroll: textarea rows ≈ line number;
    // Chrome honours `scrollTop = lineHeight * line`.
    const lineHeight = parseFloat(getComputedStyle(ta).lineHeight) || 18;
    ta.scrollTop = Math.max(0, (startIdx - 2) * lineHeight);
  };

  // a small live status row at the bottom of the
  // body. The collision check is informational — the save
  // will still succeed (overwrite), but we surface it so
  // the user doesn't accidentally clobber a file. The
  // parse status surfaces the extracted step count and
  // any walker warnings.
  const collision = (() => {
    const finalName = (name || derivedName).trim();
    if (!finalName) return null;
    if (open.action === 'create' &&
        availableWorkflows.some((w) => w.name === finalName)) {
      return `已存在 ${finalName}，保存会覆盖。`;
    }
    return null;
  })();

  return (
    <div className="workflow-editor-backdrop" role="dialog" aria-modal="true">
      <div className="workflow-editor-modal">
        <div className="workflow-editor-head">
          <div className="workflow-editor-title">
            {open.action === 'create' ? '新建 workflow' : `编辑 workflow · ${open.name ?? ''}`}
          </div>
          <button className="workflow-editor-close" onClick={handleCancel} title="关闭 (Esc)">×</button>
        </div>
        <div className="workflow-editor-body">
          <label className="workflow-editor-field">
            <span className="workflow-editor-label">name</span>
            <input
              className="workflow-editor-name"
              value={name}
              onChange={(e) => { setName(e.target.value); setDirty(true); }}
              placeholder="kebab-case-name"
              spellCheck={false}
              autoFocus
            />
            {derivedName && !name && (
              <span className="workflow-editor-hint">
                检测到 body 里的 name: <code>{derivedName}</code> — 保存时用这个
              </span>
            )}
          </label>
          <label className="workflow-editor-field">
            <span className="workflow-editor-label">yaml</span>
            <div className="workflow-editor-palette" role="toolbar" aria-label="插入 step 模板">
              {STEP_TYPES.map((t) => (
                <button
                  key={t.key}
                  type="button"
                  className="workflow-editor-palette-btn"
                  onClick={() => insertSnippet(t.key)}
                  title={`在光标处插入 ${t.label.slice(2)} 步骤模板`}
                >{t.label}</button>
              ))}
            </div>
            <textarea
              ref={textareaRef}
              className="workflow-editor-textarea"
              value={body}
              onChange={(e) => { setBody(e.target.value); setDirty(true); }}
              spellCheck={false}
              rows={20}
            />
          </label>
          {extracted.steps.length > 0 && (
            <div className="workflow-editor-field workflow-editor-field-preview">
              <span className="workflow-editor-label">live preview</span>
              <div className="workflow-editor-preview" role="region" aria-label="Workflow structure preview">
                <div className="workflow-editor-preview-row">
                  <span className="workflow-editor-preview-key">name</span>
                  <span className="workflow-editor-preview-val">
                    {extracted.name ?? <em>—</em>}
                  </span>
                </div>
                <div className="workflow-editor-preview-row">
                  <span className="workflow-editor-preview-key">description</span>
                  <span className="workflow-editor-preview-val">
                    {extracted.description ?? <em>—</em>}
                  </span>
                </div>
                <div className="workflow-editor-preview-row">
                  <span className="workflow-editor-preview-key">version</span>
                  <span className="workflow-editor-preview-val">
                    {extracted.version != null ? `v${extracted.version}` : <em>—</em>}
                  </span>
                </div>
                <div className="workflow-editor-preview-row">
                  <span className="workflow-editor-preview-key">steps</span>
                  <span className="workflow-editor-preview-val">{extracted.steps.length}</span>
                </div>
                <ol className="workflow-editor-steplist">
                  {extracted.steps.map((s, i) => (
                    <li
                      key={`${s.line}-${s.id}`}
                      className={`workflow-editor-steplist-item ${s.type ? `workflow-editor-steplist-${s.type}` : ''}`}
                      onClick={() => jumpToStep(s.line)}
                      title={`第 ${s.line} 行 — 点击跳到 textarea`}
                    >
                      <span className="workflow-editor-steplist-num">{i + 1}.</span>
                      <span className="workflow-editor-steplist-id">{s.id}</span>
                      <span className="workflow-editor-steplist-type">{s.type || '?'}</span>
                      <span className="workflow-editor-steplist-line">L{s.line}</span>
                    </li>
                  ))}
                </ol>
                {extracted.warning && (
                  <div className="workflow-editor-preview-warn">⚠ {extracted.warning}</div>
                )}
              </div>
            </div>
          )}
          <div className="workflow-editor-status">
            <span className="workflow-editor-stat">{body.length} 字符</span>
            <span className="workflow-editor-stat">
              {extracted.steps.length > 0
                ? `✓ ${extracted.steps.length} steps`
                : '⚠ 0 steps'}
            </span>
            {extracted.version !== null && (
              <span className="workflow-editor-stat">v{extracted.version}</span>
            )}
            {collision && <span className="workflow-editor-warn">⚠ {collision}</span>}
            {extracted.warning && <span className="workflow-editor-warn">⚠ {extracted.warning}</span>}
            {err && <span className="workflow-editor-err">✗ {err}</span>}
            {dirty && <span className="workflow-editor-stat workflow-editor-stat-dirty">● unsaved</span>}
          </div>
        </div>
        <div className="workflow-editor-foot">
          <span className="workflow-editor-hint" style={{ flex: 1 }}>
            Cmd/Ctrl+S 保存 · Esc 关闭
          </span>
          <button
            className="workflow-editor-btn workflow-editor-btn-cancel"
            onClick={handleCancel}
            disabled={saving}
          >取消</button>
          <button
            className="workflow-editor-btn workflow-editor-btn-save"
            onClick={handleSave}
            disabled={saving}
          >{saving ? '保存中…' : (open.action === 'create' ? '创建' : '保存')}</button>
        </div>
      </div>
    </div>
  );
}
