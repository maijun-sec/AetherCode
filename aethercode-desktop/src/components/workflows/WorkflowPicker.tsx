// Phase 5 (T-5-13): WorkflowPicker.
//
// Modal that lists every workflow the daemon knows about,
// with a search box, a built-in filter, and a "Run"
// button per row. The picker is the launcher for the
// `workflow/run` RPC — the user picks a workflow, fills
// in the declared inputs, and the daemon spawns a
// session with the workflow attached.
//
// Visual model:
//
//   ┌──────────────────────────────────────────────┐
//   │ Workflows                              ×    │
//   │ 🔍 [search…]            [builtins only ☐] │
//   ├──────────────────────────────────────────────┤
//   │ tdd-feature                               │
//   │   Red → Green → Refactor                   │
//   │   inputs: feature (string, required)       │
//   │   [Run ▸]                                 │
//   ├──────────────────────────────────────────────┤
//   │ security-audit                            │
//   │   …                                       │
//   └──────────────────────────────────────────────┘

import { useMemo, useState } from 'react';
import { useWorkflowList, useRpc } from '../../rpc/queries';
import { useMutation, useQueryClient } from '@tanstack/react-query';

export interface WorkflowPickerProps {
  /** When true, the picker is open. */
  open: boolean;
  onClose: () => void;
  /** Optional pre-selected workflow. */
  initialName?: string;
  /** Fired after a successful run. The parent can
   *  navigate to the new session id. */
  onRan?: (result: { ok: boolean; sessionId?: string; name: string; runId?: string }) => void;
}

export function WorkflowPicker({ open, onClose, initialName, onRan }: WorkflowPickerProps) {
  const client = useRpc();
  const qc = useQueryClient();
  const list = useWorkflowList();
  const run = useMutation({
    mutationFn: async (args: { name: string; inputs: Record<string, unknown> }) => {
      return client.call<{ ok: boolean; id: string; title: string; startedAt: number }>('workflow/run', args);
    },
    onSuccess: (data, vars) => {
      qc.invalidateQueries({ queryKey: ['workflow', 'list'] });
      onRan?.({ ok: data.ok, sessionId: data.id, name: vars.name });
    },
  });
  const [query, setQuery] = useState('');
  const [builtinsOnly, setBuiltinsOnly] = useState(false);
  const [selectedName, setSelectedName] = useState<string | null>(initialName ?? null);
  const [inputValues, setInputValues] = useState<Record<string, string>>({});

  const items = list.data ?? [];
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return items.filter((w) => {
      if (builtinsOnly && !w.builtin) return false;
      if (q && !`${w.name} ${w.description}`.toLowerCase().includes(q)) return false;
      return true;
    });
  }, [items, query, builtinsOnly]);

  const selected = useMemo(() => items.find((w) => w.name === selectedName) ?? null, [items, selectedName]);

  if (!open) return null;

  return (
    <div className="workflow-picker-backdrop" role="dialog" aria-modal="true" aria-label="Workflow picker">
      <div className="workflow-picker">
        <div className="workflow-picker-head">
          <div className="workflow-picker-title">Workflows</div>
          <button className="workflow-picker-close" onClick={onClose} title="Close (Esc)">×</button>
        </div>
        <div className="workflow-picker-toolbar">
          <input
            className="workflow-picker-search"
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="search workflows…"
            spellCheck={false}
            aria-label="Search workflows"
          />
          <label className="workflow-picker-builtins">
            <input
              type="checkbox"
              checked={builtinsOnly}
              onChange={(e) => setBuiltinsOnly(e.target.checked)}
            />
            builtins only
          </label>
        </div>
        <div className="workflow-picker-body">
          {list.isLoading ? (
            <div className="workflow-picker-loading">Loading workflows…</div>
          ) : filtered.length === 0 ? (
            <div className="workflow-picker-empty">No workflows match.</div>
          ) : (
            <ul className="workflow-picker-list" role="listbox" aria-label="Workflows">
              {filtered.map((w) => (
                <li
                  key={w.name}
                  className={[
                    'workflow-picker-row',
                    selectedName === w.name ? 'workflow-picker-row-selected' : '',
                  ].filter(Boolean).join(' ')}
                  role="option"
                  aria-selected={selectedName === w.name}
                  onClick={() => setSelectedName(w.name)}
                >
                  <div className="workflow-picker-row-main">
                    <div className="workflow-picker-name">
                      {w.name}
                      {w.builtin && <span className="workflow-picker-builtin-pill">built-in</span>}
                      {w.scope === 'project' && <span className="workflow-picker-scope-pill">project</span>}
                    </div>
                    <div className="workflow-picker-desc">{w.description}</div>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
        {selected && (
          <div className="workflow-picker-inputs">
            <div className="workflow-picker-inputs-title">Inputs for <code>{selected.name}</code></div>
            {(!selected.inputs || selected.inputs.length === 0) ? (
              <div className="workflow-picker-inputs-empty">No inputs declared — ready to run.</div>
            ) : (
              <div className="workflow-picker-inputs-grid">
                {(selected.inputs || []).map((input) => (
                  <label key={input.name} className="workflow-picker-input-row">
                    <span className="workflow-picker-input-name">{input.name}{input.required ? ' *' : ''}</span>
                    <input
                      type="text"
                      value={inputValues[input.name] ?? ''}
                      onChange={(e) => setInputValues((v) => ({ ...v, [input.name]: e.target.value }))}
                      placeholder={input.description ?? `value for ${input.name}…`}
                    />
                  </label>
                ))}
              </div>
            )}
          </div>
        )}
        <div className="workflow-picker-foot">
          <span className="workflow-picker-foot-hint">
            {selected ? `Ready to run ${selected.name}` : 'Pick a workflow to continue'}
          </span>
          <button
            className="workflow-picker-run"
            disabled={!selected || run.isPending}
            onClick={() => {
              if (!selected) return;
              run.mutate({ name: selected.name, inputs: inputValues });
            }}
          >
            {run.isPending ? 'running…' : 'Run ▸'}
          </button>
        </div>
      </div>
    </div>
  );
}
