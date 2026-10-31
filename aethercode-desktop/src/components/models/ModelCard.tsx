// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

// Phase 5: ModelCard (T-5-11) — single model card.

import type { ModelInfo } from '../../rpc/types';

export interface ModelCardProps {
  model: ModelInfo;
  selected?: boolean;
  onSelect?: (id: string) => void;
  showLastUsed?: boolean;
}

function fmtPrice(perM: number | undefined): string {
  if (perM === undefined || perM === null) return '—';
  if (perM === 0) return 'free';
  if (perM < 1) return `$${perM.toFixed(2)}/M`;
  return `$${perM.toFixed(2)}/M`;
}

export function ModelCard({ model, selected, onSelect, showLastUsed = true }: ModelCardProps) {
  return (
    <article
      className={`model-card ${selected ? 'selected' : ''}`}
      data-testid={`model-card-${model.id}`}
      data-provider={model.provider}
      data-tier={model.tier}
      aria-selected={!!selected}
    >
      <header className="model-card-header">
        <h3 className="model-card-name" data-testid="model-card-name">{model.name}</h3>
        <span className="model-card-provider" data-testid="model-card-provider">{model.provider}</span>
        <span className="model-card-tier" data-testid="model-card-tier">{model.tier}</span>
      </header>
      <dl className="model-card-stats">
        <div><dt>Context</dt><dd data-testid="model-card-context">{(model.contextWindow / 1000).toFixed(0)}k</dd></div>
        <div><dt>Max out</dt><dd data-testid="model-card-maxout">{(model.maxOutput / 1000).toFixed(0)}k</dd></div>
        <div><dt>Input</dt><dd data-testid="model-card-input">{fmtPrice(model.pricing?.inputPerM)}</dd></div>
        <div><dt>Output</dt><dd data-testid="model-card-output">{fmtPrice(model.pricing?.outputPerM)}</dd></div>
        {model.pricing?.cachedPerM !== undefined && (
          <div><dt>Cached</dt><dd data-testid="model-card-cached">{fmtPrice(model.pricing.cachedPerM)}</dd></div>
        )}
      </dl>
      <ul className="model-card-badges" data-testid="model-card-badges">
        {model.capabilities.vision && <li data-testid="model-card-badge-vision">vision</li>}
        {model.capabilities.tools && <li data-testid="model-card-badge-tools">tools</li>}
        {model.capabilities.json && <li data-testid="model-card-badge-json">json</li>}
      </ul>
      <footer className="model-card-footer">
        {showLastUsed && model.lastUsedAt && (
          <span className="model-card-last-used" data-testid="model-card-last-used">last used</span>
        )}
        {onSelect && (
          <button
            type="button"
            data-testid={`model-card-select-${model.id}`}
            className="model-card-select"
            disabled={!!selected}
            onClick={() => onSelect(model.id)}
          >
            {selected ? 'Active' : 'Use'}
          </button>
        )}
      </footer>
    </article>
  );
}
