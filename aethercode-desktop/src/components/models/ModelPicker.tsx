// Phase 5: ModelPicker (T-5-10) — modal that lists every available
// model, grouped by provider. Each card surfaces the model's name,
// tier, context window, max output, capability badges, pricing
// ($/M input / output / cached), and a "last used" badge when
// applicable.

import { useMemo, useState } from 'react';
import { useModelList } from '../../rpc/queries';
import { useSetModel } from '../../rpc/mutations';
import { useApp } from '../../state/AppContext';
import { ModelCard } from './ModelCard';
import type { ModelInfo } from '../../rpc/types';

export interface ModelPickerProps {
  open: boolean;
  onClose: () => void;
  /** Override the active model id. Defaults to AppContext. */
  activeModelId?: string | null;
}

export function ModelPicker({ open, onClose, activeModelId }: ModelPickerProps) {
  const models = useModelList();
  const setModel = useSetModel();
  const { selectedModelId, setSelectedModelId } = useApp();
  const [query, setQuery] = useState('');

  const grouped = useMemo(() => {
    const all: ModelInfo[] = models.data ?? [];
    const filtered = query
      ? all.filter((m) =>
          [m.name, m.provider, m.tier].some((s) => s.toLowerCase().includes(query.toLowerCase())),
        )
      : all;
    const map = new Map<string, ModelInfo[]>();
    for (const m of filtered) {
      const list = map.get(m.provider) ?? [];
      list.push(m);
      map.set(m.provider, list);
    }
    return Array.from(map.entries()).sort(([a], [b]) => a.localeCompare(b));
  }, [models.data, query]);

  if (!open) return null;
  const active = activeModelId ?? selectedModelId;
  return (
    <div
      className="model-picker-backdrop"
      role="presentation"
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div
        className="model-picker"
        role="dialog"
        aria-modal="true"
        aria-labelledby="model-picker-title"
        data-testid="model-picker"
      >
        <header className="model-picker-header">
          <h2 id="model-picker-title">Pick a model</h2>
          <input
            type="search"
            data-testid="model-picker-search"
            placeholder="Filter by name / provider / tier"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <button
            type="button"
            data-testid="model-picker-close"
            onClick={onClose}
            aria-label="Close"
          >
            ×
          </button>
        </header>
        {models.isLoading ? (
          <p data-testid="model-picker-loading">Loading…</p>
        ) : grouped.length === 0 ? (
          <p data-testid="model-picker-empty">No models match.</p>
        ) : (
          <div className="model-picker-body" data-testid="model-picker-body">
            {grouped.map(([provider, list]) => (
              <section
                key={provider}
                className="model-picker-group"
                data-testid={`model-picker-group-${provider}`}
              >
                <h3 className="model-picker-group-name" data-testid={`model-picker-group-name-${provider}`}>
                  {provider}
                </h3>
                <ul className="model-picker-list">
                  {list.map((m) => (
                    <li key={m.id}>
                      <ModelCard
                        model={m}
                        selected={m.id === active}
                        onSelect={(id) => {
                          setSelectedModelId(id);
                          setModel.mutate({ id });
                        }}
                      />
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
