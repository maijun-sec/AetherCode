import { useEffect, useMemo, useRef, useState } from 'react';

/**
 * R341 — 2-level provider/model picker.
 *
 * <p>Replaces the legacy "single dropdown listing every
 * model in the registry" with a two-row chip-based picker:
 *
 * <pre>
 * ┌─ Provider chips (horizontal row, current highlighted) ─┐
 * │ [glm*] [qwen] [deepseek] [minmax] [anthropic] [+]      │
 * └─────────────────────────────────────────────────────────┘
 * ┌─ Model filter input + model list (filtered) ───────────┐
 * │ filter: [glm-4_______________]                          │
 * │ ○ glm-4-flash     $0.0000 / $0.0000 per 1k  (default)  │
 * │ ● glm-4.5         $0.0006 / $0.0020 per 1k  (current)  │
 * │ ○ glm-4.5-air     $0.0002 / $0.0006 per 1k             │
 * │ ○ glm-4.5-flash   $0.0000 / $0.0000 per 1k             │
 * │ ...                                                    │
 * └─────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>The chip row scrolls if the column width can't fit all
 * providers (a small overflow marker indicates "more →
 * right"). Switching chips resets the filter so the user
 * sees the full model list of the newly-selected brand.
 *
 * <p>The filter input is a plain controlled textbox with
 * a 150 ms debounce — fast enough that the list feels
 * live, slow enough that a 100-keystroke paste doesn't
 * fire a re-filter on every character. The filter
 * matches against the model id case-insensitively
 * (substring, anchored anywhere in the string).
 *
 * <p>Providers with {@code hasApiKey === false} are
 * hidden by default (R282 filter, mirrored in
 * SettingsPanel R286). The optional {@code showAll}
 * toggle surfaces them with a "(no key)" badge so the
 * user can still see them when they explicitly opt in.
 * The picker stays usable either way — the chip still
 * appears in the row, just with a subtle muted style.
 *
 * <p>This component is purely presentational — it does
 * NOT call the daemon directly. {@link #onSwitch} is
 * the single seam; the parent (SettingsPanel /
 * MessageInput) routes the call through
 * {@code switchProvider} on the store. Tests
 * (R341ProviderModelPicker.test.tsx) pin both shapes.
 */
export type ProviderInfo = {
  name: string;
  type?: string;
  baseUrl?: string;
  apiKeyEnv?: string;
  /** R341: `null` and `undefined` both mean "no default";
   * the renderer's "current/default" highlight treats them
   * the same. The wire shape from the daemon's
   * listProviders RPC uses `null` (legacy JSON
   * nullable field); legacy user YAMLs use `undefined`
   * (omitted). */
  defaultModel?: string | null;
  hasApiKey?: boolean;
  enabled?: boolean;
  models?: ModelInfo[];
  headers?: Record<string, string>;
  timeout?: number;
  connectTimeout?: number;
};
export type ModelInfo = {
  id: string;
  inputPer1k?: number;
  outputPer1k?: number;
  context?: number;
  maxOutput?: number;
  default?: boolean;
};

export interface ProviderModelPickerProps {
  providers: readonly ProviderInfo[];
  currentProvider: string | null;
  currentModel: string | null;
  /** Single seam — parent routes through store.switchProvider. */
  onSwitch: (provider: string, model: string) => Promise<void> | void;
  /**
   * Whether to surface providers with no API key. Default
   * false — mirrors the R286 "Show all providers" toggle.
   * When true, unconfigured providers still render as
   * chips but with a muted style.
   */
  showAll?: boolean;
  /**
   * Width hint for the chip row. The component itself
   * scrolls horizontally if chips overflow, but the
   * parent can constrain the layout (e.g. the Settings
   * panel sets a max-width).
   */
  className?: string;
  /**
   * R341 controlled-mode. When set, the picker uses this
   * as the selected chip (instead of its own internal
   * useState). Used by SettingsPanel so the parent's
   * "save" button can batch the provider/model switch
   * with permission / profile changes. When null/undefined,
   * the picker manages its own selectedProvider state
   * (legacy "click → immediately switch" behaviour).
   *
   * <p>When controlled, the picker fires {@link #onSelectProvider}
   * on chip clicks (parent updates state) and
   * {@link #onSwitch} on model clicks (with the controlled
   * provider as the source of truth).
   */
  selectedProvider?: string | null;
  onSelectProvider?: (name: string) => void;
}

/** Internal helper: case-insensitive substring filter. */
function modelMatchesFilter(modelId: string, q: string): boolean {
  if (!q) return true;
  return modelId.toLowerCase().includes(q.toLowerCase());
}

/**
 * Format the per-1k input/output price as "X.XXXX / Y.YYYY"
 * (4 decimal places, matches the R286 SettingsPanel style).
 * Returns the empty string when both rates are 0 or
 * undefined (e.g. a free-tier model with no published
 * pricing) — keeps the row visually balanced.
 */
function formatRate(inputPer1k?: number, outputPer1k?: number): string {
  const inR = inputPer1k ?? 0;
  const outR = outputPer1k ?? 0;
  if (inR === 0 && outR === 0) return '';
  return `$${inR.toFixed(4)}/$${outR.toFixed(4)} per 1k`;
}

export function ProviderModelPicker(props: ProviderModelPickerProps) {
  const {
    providers,
    currentProvider,
    currentModel,
    onSwitch,
    showAll = false,
    className,
    selectedProvider: controlledSelected,
    onSelectProvider,
  } = props;
  // R341: controlled-mode support. When the parent passes
  // `selectedProvider`, use it as the source of truth
  // (SettingsPanel batches the switch through Save).
  // Otherwise manage it internally (uncontrolled — used
  // by MessageInput for an immediate-switch flow).
  const isControlled = controlledSelected !== undefined;
  const [internalSelected, setInternalSelected] = useState<string | null>(
    currentProvider ?? providers[0]?.name ?? null
  );
  const selectedProvider = isControlled ? controlledSelected : internalSelected;
  const setSelectedProvider = (next: string) => {
    if (isControlled) {
      onSelectProvider?.(next);
    } else {
      setInternalSelected(next);
    }
  };
  // Filter text — debounced so a fast paste doesn't fire
  // on every keystroke.
  const [filterInput, setFilterInput] = useState('');
  const [filter, setFilter] = useState('');
  const filterTimerRef = useRef<number | null>(null);
  useEffect(() => {
    if (filterTimerRef.current != null) {
      window.clearTimeout(filterTimerRef.current);
      filterTimerRef.current = null;
    }
    filterTimerRef.current = window.setTimeout(() => {
      filterTimerRef.current = null;
      setFilter(filterInput);
    }, 150);
    return () => {
      if (filterTimerRef.current != null) {
        window.clearTimeout(filterTimerRef.current);
        filterTimerRef.current = null;
      }
    };
  }, [filterInput]);
  // When the daemon's currentProvider flips from outside
  // (e.g. a switchProvider via another window), re-sync
  // the local chip selection. We track the previous
  // value via a ref so the effect doesn't fire on
  // internal state changes (which would create a feedback
  // loop where clicking a chip → state change → effect
  // fires → state reset → click appears no-op).
  const prevCurrentProviderRef = useRef<string | null | undefined>(undefined);
  useEffect(() => {
    if (
      prevCurrentProviderRef.current !== currentProvider &&
      currentProvider &&
      currentProvider !== selectedProvider
    ) {
      prevCurrentProviderRef.current = currentProvider;
      if (isControlled) {
        // In controlled mode, the parent owns the state.
        // The parent's re-render with the new value will
        // flow through the controlledSelected prop. We
        // just clear the filter here so the user sees the
        // new brand's full model list.
        setFilterInput('');
        setFilter('');
      } else {
        setSelectedProvider(currentProvider);
        setFilterInput('');
        setFilter('');
      }
    } else if (prevCurrentProviderRef.current === undefined) {
      // First render — seed the ref so subsequent re-renders
      // can tell external changes from internal ones.
      prevCurrentProviderRef.current = currentProvider;
    }
    // currentModel doesn't change the chip but might want
    // to scroll the list into view — handled by the
    // model row's `data-current` attribute + CSS.
  }, [currentProvider, isControlled]);
  // Filtered provider list — hide unconfigured unless
  // showAll. enabled=false always hides.
  const visibleProviders = useMemo(
    () =>
      providers.filter((p) => {
        if (p.enabled === false) return false;
        if (!showAll && p.hasApiKey === false) return false;
        return true;
      }),
    [providers, showAll]
  );
  // The selected provider's models. Defensive: when the
  // daemon removed the brand between renders, fall back
  // to the first visible provider's models.
  const selectedProviderInfo = useMemo(
    () => visibleProviders.find((p) => p.name === selectedProvider) ?? visibleProviders[0] ?? null,
    [visibleProviders, selectedProvider]
  );
  const modelsForSelected = useMemo(
    () => selectedProviderInfo?.models ?? [],
    [selectedProviderInfo]
  );
  const filteredModels = useMemo(
    () => modelsForSelected.filter((m) => modelMatchesFilter(m.id, filter)),
    [modelsForSelected, filter]
  );
  const onPickProvider = (name: string) => {
    setSelectedProvider(name);
    // R341: switching provider resets the filter so the
    // user sees the new brand's full list, not stale
    // filtered results from the previous brand.
    setFilterInput('');
    setFilter('');
  };
  const onPickModel = async (modelId: string) => {
    if (!selectedProviderInfo) return;
    await onSwitch(selectedProviderInfo.name, modelId);
  };
  return (
    <div className={['r341-picker', className].filter(Boolean).join(' ')}>
      {/* Provider chip row. Horizontal scroll if it
          overflows. Each chip is a real <button> so
          keyboard / screen-reader users get the affordance
          for free. The "current" provider gets a small
          filled dot prefix; "(no key)" badges mark
          providers the user can't actually call when
          showAll is on. */}
      <div className="r341-picker-provider-row" data-testid="r341-provider-row">
        {visibleProviders.length === 0 ? (
          <div className="r341-picker-empty" data-testid="r341-provider-empty">
            (no providers — daemon offline? or toggle "Show all")
          </div>
        ) : (
          visibleProviders.map((p) => {
            const isSelected = p.name === selectedProvider;
            const isCurrent = p.name === currentProvider;
            const hasKey = p.hasApiKey !== false;
            return (
              <button
                key={p.name}
                type="button"
                role="tab"
                aria-selected={isSelected}
                data-provider-name={p.name}
                data-current={isCurrent ? '1' : '0'}
                data-has-key={hasKey ? '1' : '0'}
                className={[
                  'r341-picker-chip',
                  isSelected ? 'r341-picker-chip-selected' : '',
                  isCurrent ? 'r341-picker-chip-current' : '',
                  hasKey ? '' : 'r341-picker-chip-no-key',
                ].filter(Boolean).join(' ')}
                onClick={() => onPickProvider(p.name)}
                data-testid={`r341-provider-chip-${p.name}`}
              >
                <span className="r341-picker-chip-dot" aria-hidden="true">
                  {isCurrent ? '●' : '○'}
                </span>
                <span className="r341-picker-chip-name">{p.name}</span>
                {!hasKey ? (
                  <span className="r341-picker-chip-badge">(no key)</span>
                ) : null}
              </button>
            );
          })
        )}
      </div>
      {/* Model filter input. Plain controlled textbox
          with a 150ms debounce. Live region announces
          the match count to screen-reader users (a
          detail that makes a 30-model dropdown much
          less hostile). */}
      <div className="r341-picker-filter-row">
        <label className="r341-picker-filter-label" htmlFor="r341-picker-filter">
          Filter
        </label>
        <input
          id="r341-picker-filter"
          type="text"
          placeholder="type to filter models..."
          value={filterInput}
          onChange={(e) => setFilterInput(e.target.value)}
          disabled={modelsForSelected.length === 0}
          className="r341-picker-filter-input"
          data-testid="r341-model-filter"
        />
        <span className="r341-picker-filter-count" aria-live="polite" data-testid="r341-picker-filter-count">
          {filter ? `${filteredModels.length} / ${modelsForSelected.length}` : `${modelsForSelected.length}`}
        </span>
      </div>
      {/* Model list. Plain <button> rows (so they get
          focus / click semantics for free). The "(current)"
          row's class lights up so the user can see
          which model is currently active without
          opening anything. */}
      <div className="r341-picker-model-list" data-testid="r341-model-list">
        {modelsForSelected.length === 0 ? (
          <div className="r341-picker-empty" data-testid="r341-model-empty">
            (no models for this provider)
          </div>
        ) : filteredModels.length === 0 ? (
          <div className="r341-picker-empty" data-testid="r341-model-no-match">
            (no models match "{filter}")
          </div>
        ) : (
          filteredModels.map((m) => {
            const isCurrent =
              selectedProvider === currentProvider && m.id === currentModel;
            const isDefault =
              m.default === true &&
              (selectedProviderInfo?.defaultModel == null ||
                m.id === selectedProviderInfo.defaultModel);
            return (
              <button
                key={m.id}
                type="button"
                data-model-id={m.id}
                data-current={isCurrent ? '1' : '0'}
                data-default={isDefault ? '1' : '0'}
                className={[
                  'r341-picker-model',
                  isCurrent ? 'r341-picker-model-current' : '',
                ].filter(Boolean).join(' ')}
                onClick={() => void onPickModel(m.id)}
                data-testid={`r341-model-${selectedProvider}-${m.id}`}
              >
                <span className="r341-picker-model-id">{m.id}</span>
                {isDefault ? (
                  <span className="r341-picker-model-badge">(default)</span>
                ) : null}
                {isCurrent ? (
                  <span className="r341-picker-model-badge r341-picker-model-badge-current">
                    (current)
                  </span>
                ) : null}
                {formatRate(m.inputPer1k, m.outputPer1k) ? (
                  <span className="r341-picker-model-rate">
                    {formatRate(m.inputPer1k, m.outputPer1k)}
                  </span>
                ) : null}
              </button>
            );
          })
        )}
      </div>
    </div>
  );
}