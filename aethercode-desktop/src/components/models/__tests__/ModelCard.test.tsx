// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
import { TestProviders } from '../../../test/testUtils';
import { autoCleanup } from '../../../test/testUtils';
import { ModelCard } from '../ModelCard';
import type { ModelInfo } from '../../../rpc/types';

const model: ModelInfo = {
  id: 'm-1',
  name: 'MiniMax-M3',
  provider: 'MiniMax',
  tier: 'pro',
  contextWindow: 200_000,
  maxOutput: 16_000,
  capabilities: { vision: true, tools: true, json: true },
  pricing: { inputPerM: 3, outputPerM: 15, cachedPerM: 0.3 },
  lastUsedAt: 1000,
};

autoCleanup();

describe('Phase 5 / T-5-11: ModelCard', () => {
  it('renders the name, provider and tier', () => {
    renderInProvider({ model, onSelect: vi.fn() });
    expect(screen.getByTestId('model-card-name').textContent).toBe('MiniMax-M3');
    expect(screen.getByTestId('model-card-provider').textContent).toBe('MiniMax');
    expect(screen.getByTestId('model-card-tier').textContent).toBe('pro');
  });

  it('formats context + max output in k', () => {
    renderInProvider({ model, onSelect: vi.fn() });
    expect(screen.getByTestId('model-card-context').textContent).toBe('200k');
    expect(screen.getByTestId('model-card-maxout').textContent).toBe('16k');
  });

  it('formats pricing in $X/M', () => {
    renderInProvider({ model, onSelect: vi.fn() });
    expect(screen.getByTestId('model-card-input').textContent).toBe('$3.00/M');
    expect(screen.getByTestId('model-card-output').textContent).toBe('$15.00/M');
    expect(screen.getByTestId('model-card-cached').textContent).toBe('$0.30/M');
  });

  it('renders capability badges', () => {
    renderInProvider({ model, onSelect: vi.fn() });
    expect(screen.getByTestId('model-card-badge-vision').textContent).toBe('vision');
    expect(screen.getByTestId('model-card-badge-tools').textContent).toBe('tools');
    expect(screen.getByTestId('model-card-badge-json').textContent).toBe('json');
  });

  it('renders the "last used" badge when lastUsedAt is present', () => {
    renderInProvider({ model, onSelect: vi.fn() });
    expect(screen.getByTestId('model-card-last-used')).toBeDefined();
  });

  it('hides the "last used" badge when lastUsedAt is absent', () => {
    renderInProvider({ model: { ...model, lastUsedAt: undefined }, onSelect: vi.fn() });
    expect(screen.queryByTestId('model-card-last-used')).toBeNull();
  });

  it('onSelect is invoked with the model id when the button is clicked', () => {
    const onSelect = vi.fn();
    renderInProvider({ model, onSelect });
    fireEvent.click(screen.getByTestId('model-card-select-m-1'));
    expect(onSelect).toHaveBeenCalledWith('m-1');
  });

  it('disables the select button when selected is true', () => {
    renderInProvider({ model, selected: true, onSelect: vi.fn() });
    expect(screen.getByTestId('model-card-select-m-1').hasAttribute('disabled')).toBe(true);
  });
});

function renderInProvider(props: React.ComponentProps<typeof ModelCard>) {
  const { render } = require('@testing-library/react');
  return render(
    <TestProviders>
      <ModelCard {...props} />
    </TestProviders>,
  );
}
