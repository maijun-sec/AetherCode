import { useEffect, useState } from 'react';
import { useStore } from '../store';
import './AgentEditor.css';

// prior round: agent CRUD editor. The
// Settings panel's "Agents" tab uses this to
// create / edit / delete agents. The list view
// on the left picks the active agent; the
// editor on the right shows frontmatter fields
// (name / description / displayName / model)
// plus a free-form body. prior round adds the
// `model:` field so a workflow's `kind: agent`
// step can use a different model per agent.

interface AgentEditorProps {
  /** prior round: editor mode. "create" lets
   *  the user pick a fresh name; "edit" locks
   *  the name (the path on disk is fixed).
   *  Same component, two flows. */
  mode: 'create' | 'edit';
  /** Initial values for the editor. Required
   *  for "edit"; optional for "create" (the
   *  user fills in everything). */
  initial?: {
    name: string;
    description: string;
    displayName: string;
    model: string;
    body: string;
  };
  onClose: () => void;
  onSaved: () => void;
}

export function AgentEditor({ mode, initial, onClose, onSaved }: AgentEditorProps) {
  const { createAgent, updateAgent, deleteAgent, availableProviders, currentProvider } = useStore();
  const [name, setName] = useState(initial?.name ?? '');
  const [description, setDescription] = useState(initial?.description ?? '');
  const [displayName, setDisplayName] = useState(initial?.displayName ?? '');
  // model binding. The empty string
  // means "use the engine's default model";
  // an explicit "provider/model" picks that
  // combination when the workflow executor
  // spawns this agent.
  const [model, setModel] = useState(initial?.model ?? '');
  const [body, setBody] = useState(initial?.body ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // build the model options from the
  // provider registry. We flatten the
  // provider/model list into "provider/model"
  // strings so the user picks from the same
  // list the engine itself uses. The empty
  // option is "engine default".
  const modelOptions: { value: string; label: string }[] = [];
  modelOptions.push({ value: '', label: '(engine default)' });
  for (const p of (availableProviders ?? []) as any[]) {
    for (const m of (p.models ?? []) as any[]) {
      modelOptions.push({
        value: `${p.name}/${m.id}`,
        label: `${p.name}/${m.id}${m.default ? ' (default)' : ''}`,
      });
    }
  }

  // a smart default for the model
  // field on a fresh create — pick the
  // engine's current provider/model so the
  // user gets a useful starting point.
  useEffect(() => {
    if (mode === 'create' && !model && currentProvider) {
      const p = (availableProviders ?? []).find((p: any) => p.name === currentProvider);
      if (p?.defaultModel) setModel(`${currentProvider}/${p.defaultModel}`);
    }
  }, [mode, model, currentProvider, availableProviders]);

  const onSave = async () => {
    setSaving(true); setError(null);
    try {
      const opts = {
        name: name.trim(),
        description: description.trim(),
        displayName: displayName.trim(),
        model: model.trim(),
        body,
      };
      if (!opts.name) {
        setError('name is required');
        setSaving(false);
        return;
      }
      if (mode === 'create') {
        await createAgent(opts);
      } else {
        await updateAgent(opts);
      }
      onSaved();
      onClose();
    } catch (e: any) {
      setError(e?.message ?? String(e));
    } finally {
      setSaving(false);
    }
  };

  const onDelete = async () => {
    if (!initial?.name) return;
    if (!window.confirm(`Delete agent "${initial.name}"? This removes the on-disk agent.md.`)) return;
    setSaving(true); setError(null);
    try {
      await deleteAgent(initial.name);
      onSaved();
      onClose();
    } catch (e: any) {
      setError(e?.message ?? String(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="agent-editor-overlay" onClick={onClose}>
      <div className="agent-editor" onClick={(e) => e.stopPropagation()}>
        <div className="agent-editor-header">
          <h2>{mode === 'create' ? 'New Agent' : `Edit Agent: ${initial?.name}`}</h2>
          <button className="agent-editor-close" onClick={onClose}>×</button>
        </div>
        <div className="agent-editor-body">
          <label className="agent-editor-field">
            <span>Name</span>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={mode === 'edit'}
              placeholder="kebab-case-agent-name"
            />
            <small className="settings-hint">kebab-case, no path separators, max 64 chars</small>
          </label>
          <label className="agent-editor-field">
            <span>Display name</span>
            <input
              type="text"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder="Human-readable label"
            />
          </label>
          <label className="agent-editor-field">
            <span>Description</span>
            <input
              type="text"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Short summary for the agent picker"
            />
          </label>
          {/* model binding. The dropdown
              is built from the provider registry
              so the user can pick any
              provider/model combination. The
              empty option is "engine default" —
              the workflow executor uses the
              engine's currently configured
              model. An explicit "provider/model"
              overrides per-agent. */}
          <label className="agent-editor-field">
            <span>Model</span>
            <select value={model} onChange={(e) => setModel(e.target.value)}>
              {modelOptions.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
            <small className="settings-hint">
              对应历史 round: per-agent model. Pick a provider/model — the workflow executor's
              kind: agent step uses this when spawning the agent as a child session.
            </small>
          </label>
          <label className="agent-editor-field">
            <span>Body (markdown)</span>
            <textarea
              className="agent-editor-body-text"
              value={body}
              onChange={(e) => setBody(e.target.value)}
              rows={14}
              placeholder="You are a..."
            />
          </label>
          {error && <div className="settings-error">{error}</div>}
        </div>
        <div className="agent-editor-footer">
          {mode === 'edit' && (
            <button className="agent-editor-delete" onClick={onDelete} disabled={saving}>Delete</button>
          )}
          <div className="agent-editor-footer-right">
            <button onClick={onClose}>Cancel</button>
            <button className="primary" onClick={onSave} disabled={saving || !name.trim()}>
              {saving ? 'Saving…' : 'Save'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
