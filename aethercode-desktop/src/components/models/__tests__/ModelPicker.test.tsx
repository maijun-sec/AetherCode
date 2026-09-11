// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
import { createFixture, flush } from '../../../test/testUtils';
import { autoCleanup } from '../../../test/testUtils';
import { ModelPicker } from '../ModelPicker';
import type { ModelInfo } from '../../../rpc/types';

const models: ModelInfo[] = [
  { id: 'm-1', name: 'MiniMax-M3', provider: 'MiniMax', tier: 'pro', contextWindow: 200_000, maxOutput: 16_000, capabilities: { vision: true, tools: true, json: true }, pricing: { inputPerM: 3, outputPerM: 15 } },
  { id: 'm-2', name: 'GPT-4o', provider: 'OpenAI', tier: 'flagship', contextWindow: 128_000, maxOutput: 8_000, capabilities: { vision: true, tools: true, json: true }, pricing: { inputPerM: 5, outputPerM: 15 } },
  { id: 'm-3', name: 'Claude Opus', provider: 'Anthropic', tier: 'flagship', contextWindow: 200_000, maxOutput: 8_000, capabilities: { vision: false, tools: true, json: true }, pricing: { inputPerM: 15, outputPerM: 75 } },
];

autoCleanup();

describe('Phase 5 / T-5-10: ModelPicker', () => {
  let fixture = createFixture({ seed: { models, lastUsedModelId: 'm-1' } });
  beforeEach(() => { fixture = createFixture({ seed: { models, lastUsedModelId: 'm-1' } }); });

  it('renders nothing when closed', () => {
    fixture.render(<ModelPicker open={false} onClose={() => {}} />);
    expect(screen.queryByTestId('model-picker')).toBeNull();
  });

  it('renders a group per provider when open', async () => {
    fixture.render(<ModelPicker open onClose={() => {}} />);
    await flush();
    expect(await screen.findByTestId('model-picker-group-MiniMax')).toBeDefined();
    expect(screen.getByTestId('model-picker-group-OpenAI')).toBeDefined();
    expect(screen.getByTestId('model-picker-group-Anthropic')).toBeDefined();
  });

  it('groups are sorted alphabetically by provider', async () => {
    fixture.render(<ModelPicker open onClose={() => {}} />);
    await flush();
    // Group testids are `model-picker-group-<provider>`. Read
    // the section's data-testid directly.
    const groups = Array.from(document.querySelectorAll('section[data-testid^="model-picker-group-"]'));
    const names = groups
      .map((g) => g.getAttribute('data-testid') ?? '')
      .map((id) => id.replace('model-picker-group-', ''));
    expect(names).toEqual(['Anthropic', 'MiniMax', 'OpenAI']);
  });

  it('filter input narrows the list by name / provider / tier', async () => {
    fixture.render(<ModelPicker open onClose={() => {}} />);
    await flush();
    fireEvent.change(screen.getByTestId('model-picker-search'), { target: { value: 'gpt' } });
    await flush();
    expect(screen.getByTestId('model-card-m-2')).toBeDefined();
    expect(screen.queryByTestId('model-card-m-1')).toBeNull();
  });

  it('clicking a model card calls model/set with the chosen id', async () => {
    fixture.render(<ModelPicker open onClose={() => {}} />);
    await flush();
    fireEvent.click(screen.getByTestId('model-card-select-m-2'));
    await flush();
    const log = fixture.server.callLog.filter((c) => c.method === 'model/set');
    expect(log.length).toBeGreaterThan(0);
    const called = log[0];
    const params = (called?.params ?? {}) as { id?: string; model?: string };
    expect(params.id ?? params.model).toBe('m-2');
  });

  it('onClose fires when the close button is clicked', async () => {
    let closed = false;
    fixture.render(<ModelPicker open onClose={() => { closed = true; }} />);
    await flush();
    fireEvent.click(screen.getByTestId('model-picker-close'));
    expect(closed).toBe(true);
  });

  it('onClose fires when the backdrop is clicked', async () => {
    let closed = false;
    fixture.render(<ModelPicker open onClose={() => { closed = true; }} />);
    await flush();
    const backdrop = document.querySelector('.model-picker-backdrop')!;
    fireEvent.click(backdrop);
    expect(closed).toBe(true);
  });

  it('shows the empty state when no models match the filter', async () => {
    fixture.render(<ModelPicker open onClose={() => {}} />);
    await flush();
    fireEvent.change(screen.getByTestId('model-picker-search'), { target: { value: 'nonexistent' } });
    await flush();
    expect(await screen.findByTestId('model-picker-empty')).toBeDefined();
  });

  it('marks the active model with the selected class', async () => {
    fixture.render(<ModelPicker open activeModelId="m-2" onClose={() => {}} />);
    await flush();
    const card = screen.getByTestId('model-card-m-2');
    expect(card.classList.contains('selected')).toBe(true);
  });
});
