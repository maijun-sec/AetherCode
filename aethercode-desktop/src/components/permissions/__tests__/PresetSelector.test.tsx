// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { createFixture, flush } from '../../../test/testUtils';
import { autoCleanup } from '../../../test/testUtils';
import { PresetSelector } from '../PresetSelector';

autoCleanup();

describe('Phase 5 / T-5-07: PresetSelector', () => {
  let fixture = createFixture({});
  beforeEach(() => { fixture = createFixture({}); });

  it('renders the three preset cards', () => {
    fixture.render(<PresetSelector />);
    expect(screen.getByTestId('preset-card-permissive')).toBeDefined();
    expect(screen.getByTestId('preset-card-cautious')).toBeDefined();
    expect(screen.getByTestId('preset-card-strict')).toBeDefined();
  });

  it('marks the active preset with the active class', () => {
    fixture.render(<PresetSelector activePreset="strict" />);
    const root = screen.getByTestId('preset-selector');
    expect(root.getAttribute('data-active')).toBe('strict');
    expect(screen.getByTestId('preset-card-strict').classList.contains('active')).toBe(true);
  });

  it('clicking a card fires grants/setPreset with the chosen preset', async () => {
    fixture.render(<PresetSelector activePreset="cautious" />);
    fireEvent.click(screen.getByTestId('preset-card-strict'));
    await flush();
    const called = fixture.server.callLog.find((c) => c.method === 'grants/setPreset');
    expect(called).toBeDefined();
    expect((called?.params as { preset: string }).preset).toBe('strict');
  });

  it('onApplied fires after a successful set', async () => {
    const onApplied = vi.fn();
    fixture.render(<PresetSelector activePreset="cautious" onApplied={onApplied} />);
    fireEvent.click(screen.getByTestId('preset-card-permissive'));
    await waitFor(() => {
      expect(onApplied).toHaveBeenCalledWith('permissive');
    });
  });

  it('updates the AppContext slice optimistically', async () => {
    fixture.render(<PresetSelector />);
    fireEvent.click(screen.getByTestId('preset-card-strict'));
    await flush();
    const card = screen.getByTestId('preset-card-strict');
    expect(card.classList.contains('active')).toBe(true);
  });

  it('disables cards while the mutation is in flight', async () => {
    const { render } = fixture;
    // Defer the response so the in-flight window is observable.
    fixture.server.handle('grants/setPreset', () => new Promise((r) => setTimeout(() => r({ ok: true }), 50)));
    render(<PresetSelector activePreset="cautious" />);
    fireEvent.click(screen.getByTestId('preset-card-permissive'));
    // Immediately after click, the card should be disabled.
    expect(screen.getByTestId('preset-card-permissive').hasAttribute('disabled')).toBe(true);
    await flush();
    await new Promise((r) => setTimeout(r, 80));
    await flush();
  });
});

import { vi } from 'vitest';
