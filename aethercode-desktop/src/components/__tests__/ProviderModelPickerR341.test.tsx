// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { ProviderModelPicker, type ProviderInfo } from '../ProviderModelPicker';

/**
 * R341 — ProviderModelPicker unit tests.
 *
 * <p>Pins the post-R341 behaviour for the 2-level picker:
 * <ul>
 *   <li>provider chips render in the chip row, current
 *       provider is highlighted</li>
 *   <li>clicking a chip switches the model list to that
 *       brand's models</li>
 *   <li>filter input narrows the model list (case-
 *       insensitive substring match)</li>
 *   <li>switching providers resets the filter</li>
 *   <li>unconfigured providers (hasApiKey=false) are
 *       hidden by default and shown with a "(no key)"
 *       badge when showAll=true</li>
 *   <li>disabled providers (enabled=false) are always
 *       hidden</li>
 *   <li>clicking a model row calls onSwitch(provider, model)</li>
 *   <li>debounce: 150ms wait before the filter actually
 *       narrows the list</li>
 *   <li>the filter count chip updates with the match
 *       ratio</li>
 * </ul>
 */

const SAMPLE_PROVIDERS: ProviderInfo[] = [
  {
    name: 'minmax',
    defaultModel: 'MiniMax-M3',
    hasApiKey: true,
    models: [
      { id: 'MiniMax-M3', default: true, inputPer1k: 0.001, outputPer1k: 0.008 },
      { id: 'MiniMax-M1', inputPer1k: 0.001, outputPer1k: 0.008 },
    ],
  },
  {
    name: 'glm',
    defaultModel: 'glm-4-flash',
    hasApiKey: true,
    models: [
      { id: 'glm-4-flash', default: true, inputPer1k: 0, outputPer1k: 0 },
      { id: 'glm-4.5', inputPer1k: 0.0006, outputPer1k: 0.002 },
      { id: 'glm-4.5-air', inputPer1k: 0.0002, outputPer1k: 0.0006 },
      { id: 'glm-z1-air', inputPer1k: 0, outputPer1k: 0 },
    ],
  },
  {
    name: 'anthropic',
    defaultModel: 'claude-sonnet-4-5',
    hasApiKey: false,
    models: [
      { id: 'claude-sonnet-4-5', default: true },
      { id: 'claude-opus-4-1' },
    ],
  },
  {
    name: 'hidden',
    defaultModel: 'hidden-1',
    enabled: false,
    hasApiKey: true,
    models: [{ id: 'hidden-1', default: true }],
  },
];

describe('R341: ProviderModelPicker — provider chip row', () => {
  it('renders a chip for every provider with hasApiKey=true', () => {
    render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={() => {}}
      />
    );
    expect(screen.getByTestId('r341-provider-chip-minmax')).toBeInTheDocument();
    expect(screen.getByTestId('r341-provider-chip-glm')).toBeInTheDocument();
  });

  it('hides providers with hasApiKey=false by default', () => {
    render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={() => {}}
      />
    );
    // anthropic has hasApiKey=false — hidden by default.
    expect(screen.queryByTestId('r341-provider-chip-anthropic')).not.toBeInTheDocument();
  });

  it('shows unconfigured providers with (no key) badge when showAll=true', () => {
    render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={() => {}}
        showAll={true}
      />
    );
    const chip = screen.getByTestId('r341-provider-chip-anthropic');
    expect(chip).toBeInTheDocument();
    expect(chip).toHaveAttribute('data-has-key', '0');
    expect(chip.textContent).toContain('(no key)');
  });

  it('hides providers with enabled=false unconditionally', () => {
    // The "hidden" provider is enabled=false but hasApiKey=true.
    // Even with showAll=true, it must NOT show up — that's the
    // R341 constitution "Disabled state visibility" rule:
    // a model the user explicitly disabled is invisible.
    render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={() => {}}
        showAll={true}
      />
    );
    expect(screen.queryByTestId('r341-provider-chip-hidden')).not.toBeInTheDocument();
  });

  it('marks the daemon-current provider with data-current="1"', () => {
    render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="glm"
        currentModel="glm-4-flash"
        onSwitch={() => {}}
      />
    );
    expect(screen.getByTestId('r341-provider-chip-glm')).toHaveAttribute('data-current', '1');
    expect(screen.getByTestId('r341-provider-chip-minmax')).toHaveAttribute('data-current', '0');
  });

  it('renders an empty-state hint when no providers are visible', () => {
    // Every provider has hasApiKey=false → the picker is
    // empty (a fresh box with no API keys configured).
    const allUnconfigured = SAMPLE_PROVIDERS.map((p) => ({ ...p, hasApiKey: false }));
    render(
      <ProviderModelPicker
        providers={allUnconfigured}
        currentProvider={null}
        currentModel={null}
        onSwitch={() => {}}
      />
    );
    expect(screen.getByTestId('r341-provider-empty')).toBeInTheDocument();
  });
});

describe('R341: ProviderModelPicker — model list & filter', () => {
  // R341: the filter input has a 150ms debounce so a fast
  // paste doesn't re-filter on every keystroke. Tests that
  // change the input must use vi.useFakeTimers() +
  // vi.advanceTimersByTime(150) to advance the debounce
  // deterministically; otherwise waitFor times out at 500ms
  // because the timer never fires in jsdom under
  // fireEvent.change (React 18 batches state updates and
  // the real-timer callback is queued AFTER the test's
  // waitFor completes).
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('renders models of the daemon-current provider on mount', () => {
    render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="glm"
        currentModel="glm-4-flash"
        onSwitch={() => {}}
      />
    );
    // All glm models render in the model list.
    expect(screen.getByTestId('r341-model-glm-glm-4-flash')).toBeInTheDocument();
    expect(screen.getByTestId('r341-model-glm-glm-4.5')).toBeInTheDocument();
    expect(screen.getByTestId('r341-model-glm-glm-z1-air')).toBeInTheDocument();
    // min models do NOT render — different provider.
    expect(screen.queryByTestId('r341-model-minmax-MiniMax-M3')).not.toBeInTheDocument();
  });

  it('clicking a chip switches the model list to that brand', () => {
    render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="glm"
        currentModel="glm-4-flash"
        onSwitch={() => {}}
      />
    );
    fireEvent.click(screen.getByTestId('r341-provider-chip-minmax'));
    // The model list should now show minmax, not glm.
    expect(screen.getByTestId('r341-model-minmax-MiniMax-M3')).toBeInTheDocument();
    expect(screen.queryByTestId('r341-model-glm-glm-4-flash')).not.toBeInTheDocument();
  });

  it('filter input narrows the model list (substring, case-insensitive)', async () => {
    render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="glm"
        currentModel="glm-4-flash"
        onSwitch={() => {}}
      />
    );
    fireEvent.change(screen.getByTestId('r341-model-filter'), {
      target: { value: 'AIR' },
    });
    // Advance fake clock past the 150ms debounce.
    act(() => { vi.advanceTimersByTime(200); });
    // The matching model renders; the others are filtered out.
    expect(screen.getByTestId('r341-model-glm-glm-4.5-air')).toBeInTheDocument();
    expect(screen.queryByTestId('r341-model-glm-glm-4-flash')).not.toBeInTheDocument();
    expect(screen.queryByTestId('r341-model-glm-glm-4.5')).not.toBeInTheDocument();
  });

  it('filter debounces ~150ms before applying (rapid typing)', async () => {
    render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="glm"
        currentModel="glm-4-flash"
        onSwitch={() => {}}
      />
    );
    const input = screen.getByTestId('r341-model-filter');
    fireEvent.change(input, { target: { value: 'a' } });
    // Immediately after (debounce not yet elapsed), all models still render.
    expect(screen.getByTestId('r341-model-glm-glm-4-flash')).toBeInTheDocument();
    // Advance fake clock — the debounce timer fires.
    act(() => { vi.advanceTimersByTime(200); });
    // After the debounce, the non-matching models are gone.
    expect(screen.queryByTestId('r341-model-glm-glm-4.5')).not.toBeInTheDocument();
  });

  it('switching providers resets the filter', async () => {
    render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="glm"
        currentModel="glm-4-flash"
        onSwitch={() => {}}
      />
    );
    fireEvent.change(screen.getByTestId('r341-model-filter'), {
      target: { value: 'air' },
    });
    act(() => { vi.advanceTimersByTime(200); });
    expect(screen.getByTestId('r341-model-glm-glm-4.5-air')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('r341-provider-chip-minmax'));
    // Filter cleared, full minmax list shows.
    expect(screen.getByTestId('r341-model-filter')).toHaveValue('');
    expect(screen.getByTestId('r341-model-minmax-MiniMax-M3')).toBeInTheDocument();
    expect(screen.getByTestId('r341-model-minmax-MiniMax-M1')).toBeInTheDocument();
  });

  it('shows (no models match) when the filter is too narrow', async () => {
    render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="glm"
        currentModel="glm-4-flash"
        onSwitch={() => {}}
      />
    );
    fireEvent.change(screen.getByTestId('r341-model-filter'), {
      target: { value: 'zzzz-no-match' },
    });
    act(() => { vi.advanceTimersByTime(200); });
    expect(screen.getByTestId('r341-model-no-match')).toBeInTheDocument();
  });

  it('filter count shows "<matches> / <total>" when filtering', async () => {
    render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="glm"
        currentModel="glm-4-flash"
        onSwitch={() => {}}
      />
    );
    // glm has 4 models. Filter "air" matches 2 (glm-4.5-air,
    // glm-z1-air) — the substring match is case-insensitive
    // AND matches anywhere in the id, so both -air models
    // qualify. The "<matches> / <total>" format lets the
    // user see how aggressive their filter is.
    fireEvent.change(screen.getByTestId('r341-model-filter'), {
      target: { value: 'air' },
    });
    act(() => { vi.advanceTimersByTime(200); });
    expect(screen.getByTestId('r341-picker-filter-count').textContent).toMatch(/2\s*\/\s*4/);
  });

  it('filter input is disabled when the selected provider has no models', () => {
    render(
      <ProviderModelPicker
        providers={[{ name: 'empty', defaultModel: 'e', hasApiKey: true, models: [] }]}
        currentProvider="empty"
        currentModel={null}
        onSwitch={() => {}}
      />
    );
    expect(screen.getByTestId('r341-model-filter')).toBeDisabled();
    expect(screen.getByTestId('r341-model-empty')).toBeInTheDocument();
  });
});

describe('R341: ProviderModelPicker — onSwitch contract', () => {
  it('clicking a model row calls onSwitch(providerName, modelId)', () => {
    const onSwitch = vi.fn();
    render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="glm"
        currentModel="glm-4-flash"
        onSwitch={onSwitch}
      />
    );
    fireEvent.click(screen.getByTestId('r341-model-glm-glm-4.5'));
    expect(onSwitch).toHaveBeenCalledWith('glm', 'glm-4.5');
  });

  it('onSwitch is called with the SELECTED provider (not the daemon-current)', async () => {
    // The user clicks glm chip then minmax — the call should
    // route to (glm, ...) NOT (minmax, ...). This is the
    // "the chip itself is the source of truth" rule — the
    // user might be planning a switch and the click confirms.
    const onSwitch = vi.fn();
    render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"   // daemon is on minmax
        currentModel="MiniMax-M3"
        onSwitch={onSwitch}
      />
    );
    fireEvent.click(screen.getByTestId('r341-provider-chip-glm'));
    await waitFor(() => {
      expect(screen.getByTestId('r341-model-glm-glm-4.5')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('r341-model-glm-glm-4.5'));
    expect(onSwitch).toHaveBeenCalledWith('glm', 'glm-4.5');
  });

  it('marks the daemon-current model with data-current="1"', () => {
    render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="glm"
        currentModel="glm-4-flash"
        onSwitch={() => {}}
      />
    );
    expect(screen.getByTestId('r341-model-glm-glm-4-flash')).toHaveAttribute('data-current', '1');
    expect(screen.getByTestId('r341-model-glm-glm-4.5')).toHaveAttribute('data-current', '0');
  });
});

describe('R341: ProviderModelPicker — daemon → picker sync', () => {
  it('switching the daemon-current provider from outside updates the chip selection', () => {
    const { rerender } = render(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="glm"
        currentModel="glm-4-flash"
        onSwitch={() => {}}
      />
    );
    // Initially glm is selected.
    expect(screen.getByTestId('r341-provider-chip-glm')).toHaveAttribute('aria-selected', 'true');
    // The daemon's current flips to deepseek (re-render).
    rerender(
      <ProviderModelPicker
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={() => {}}
      />
    );
    expect(screen.getByTestId('r341-provider-chip-minmax')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('r341-provider-chip-glm')).toHaveAttribute('aria-selected', 'false');
  });
});