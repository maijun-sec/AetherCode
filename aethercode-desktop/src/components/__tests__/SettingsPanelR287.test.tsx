// @vitest-environment jsdom
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

// Mock the store so SettingsPanel can pull provider/model state
// without going through the real backend.
const storeState = {
  engineState: { model: 'MiniMax-M3', sessionId: 'abc' },
  availableProviders: [
    { name: 'glm', models: [{ id: 'glm-4-flash', name: 'glm-4-flash', inputPer1k: 0.0007, outputPer1k: 0.0002 }] },
  ],
  currentProvider: 'glm',
  currentVariant: null,
  setPermissionMode: vi.fn(),
  setProvider: vi.fn(),
  setModel: vi.fn(),
  switchVariant: vi.fn(),
  refreshProviders: vi.fn(),
  saveSettings: vi.fn(),
  setConcurrencyProfile: vi.fn(),
};

vi.mock('../store', () => ({
  useStore: () => storeState,
}));

import { SettingsPanel } from '../SettingsPanel';

function renderPanel(onClose = vi.fn()) {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <SettingsPanel onClose={onClose} />
    </MemoryRouter>
  );
}

describe('SettingsPanel R287-fix deep-link buttons', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders Open SDD / Models / Workflows deep-link buttons in the footer', () => {
    renderPanel();
    expect(screen.getByTestId('settings-open-sdd')).toBeTruthy();
    expect(screen.getByTestId('settings-open-models')).toBeTruthy();
    expect(screen.getByTestId('settings-open-workflows')).toBeTruthy();
  });

  it('clicking Open SDD closes the popup', () => {
    const onClose = vi.fn();
    renderPanel(onClose);
    fireEvent.click(screen.getByTestId('settings-open-sdd'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('Open SDD button is enabled (not disabled by saving state)', () => {
    renderPanel();
    const btn = screen.getByTestId('settings-open-sdd') as HTMLButtonElement;
    expect(btn.disabled).toBe(false);
  });

  it('all three deep-link buttons are siblings inside settings-footer-links', () => {
    const { container } = renderPanel();
    const links = container.querySelector('.settings-footer-links');
    expect(links).toBeTruthy();
    const buttons = within(links as HTMLElement).getAllByRole('button');
    expect(buttons.length).toBe(3);
    expect(buttons.map((b) => b.getAttribute('data-testid'))).toEqual([
      'settings-open-sdd',
      'settings-open-models',
      'settings-open-workflows',
    ]);
  });

  it('Cancel and Save buttons still exist alongside the deep-link group', () => {
    renderPanel();
    const footer = screen.getByText('Cancel').closest('.settings-footer');
    expect(footer).toBeTruthy();
    expect(within(footer as HTMLElement).getByText('Save')).toBeTruthy();
  });
});