import { useEffect, useState } from 'react';
import { useStore } from '../store';
import { AgentEditor } from './AgentEditor';
import './AgentsPanel.css';

// list + create / edit / delete
// agents. Pairs with the AgentEditor modal.
// The "+" button opens a fresh create editor;
// each row's "edit" button opens the editor
// with the agent's metadata preloaded. The
// "delete" button on each row is a quick path
// (the editor's delete button is the
// authoritative path because it requires a
// confirm() — this is the fast path for
// callers who want to wipe a stale agent
// without opening the editor).
//
// each row shows the agent's model
// binding (a small badge on the right).
// Empty / missing model = "engine default";
// we render a muted "(default)" label so the
// user can tell at a glance which agents
// override the model and which inherit.

export function AgentsPanel() {
  const { agents, refreshAgents } = useStore();
  const [editor, setEditor] = useState<
    | { mode: 'create' }
    | { mode: 'edit'; name: string }
    | null
  >(null);

  useEffect(() => { void refreshAgents(); }, [refreshAgents]);

  const onCreate = () => setEditor({ mode: 'create' });
  const onEdit = (name: string) => setEditor({ mode: 'edit', name });
  const onClose = () => {
    setEditor(null);
    void refreshAgents();
  };

  return (
    <div className="agents-panel">
      <div className="agents-panel-header">
        <span>Agents ({agents.length})</span>
        <button className="agents-panel-add" onClick={onCreate} title="Create agent">+</button>
      </div>
      {agents.length === 0 ? (
        <div className="agents-panel-empty">
          No agents yet. Click + to create one.
        </div>
      ) : (
        <ul className="agents-panel-list">
          {agents.map((a: any) => (
            <li key={a.name} className="agents-panel-row" onClick={() => onEdit(a.name)}>
              <div className="agents-panel-row-top">
                <div className="agents-panel-row-name">{a.displayName || a.name}</div>
                {/* model badge. When the agent has
                    a `model:` frontmatter we render it as a
                    "provider/model" pill; otherwise we show
                    a muted "(default)" hint. Long ids get
                    truncated by the CSS. */}
                <span
                  className={'agents-panel-row-model' + (a.model ? '' : ' agents-panel-row-model-default')}
                  title={a.model ? `Model: ${a.model}` : 'Model: engine default'}
                >
                  {a.model || '(default)'}
                </span>
              </div>
              <div className="agents-panel-row-meta">
                <span className="agents-panel-row-id">{a.name}</span>
                {a.description ? <span className="agents-panel-row-desc">{a.description}</span> : null}
              </div>
            </li>
          ))}
        </ul>
      )}
      {editor && (
        <AgentEditorLazy
          mode={editor.mode}
          name={editor.mode === 'edit' ? editor.name : undefined}
          onClose={onClose}
        />
      )}
    </div>
  );
}

// thin async wrapper that fetches the
// agent body (on edit) and passes it to the
// AgentEditor. Centralised so the editor
// receives a stable shape (initial props)
// regardless of whether the body fetch is
// pending, succeeded, or failed.
//
// getAgentBody now returns the full
// frontmatter meta (description, displayName,
// model) alongside the body. We prefill all
// four fields so the editor opens the agent
// in its existing state — the user can edit
// any field and save without a manual
// reload. The create-mode fallback still
// uses empty strings; AgentEditor's own
// useEffect sets a smart default for the
// model field.
function AgentEditorLazy(props: {
  mode: 'create' | 'edit';
  name?: string;
  onClose: () => void;
}) {
  const { getAgentBody } = useStore();
  const [initial, setInitial] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (props.mode === 'edit' && props.name) {
      (async () => {
        try {
          const r = await getAgentBody(props.name!);
          if (!r.ok) {
            setError(r.error ?? 'agent not found');
            return;
          }
          // prefill description / displayName /
          // model from the daemon's getAgentBody
          // response. The daemon assembles these from
          // the on-disk frontmatter; missing fields
          // come back as empty strings.
          setInitial({
            name: r.name,
            description: r.description ?? '',
            displayName: r.displayName ?? '',
            model: r.model ?? '',
            // R286: surface the per-agent
            // quality preset from the
            // daemon's getAgentBody
            // response. Empty string means
            // the agent has no binding —
            // AgentEditor shows
            // "(inherit)" and the daemon's
            // writeAgent helper omits the
            // frontmatter line on save.
            variant: r.variant ?? '',
            body: r.body ?? '',
          });
        } catch (e: any) {
          setError(e?.message ?? String(e));
        }
      })();
    } else {
      setInitial({
        name: '',
        description: '',
        displayName: '',
        model: '',
        variant: '',
        body: '',
      });
    }
  }, [props.mode, props.name, getAgentBody]);

  if (error) return (
    <div className="agents-panel-error">
      {error}
      <button onClick={props.onClose}>close</button>
    </div>
  );
  if (!initial) return (
    <div className="agents-panel-loading">Loading agent…</div>
  );
  return <AgentEditor mode={props.mode} initial={initial} onClose={props.onClose} onSaved={props.onClose} />;
}
