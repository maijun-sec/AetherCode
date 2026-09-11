// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
import { TestProviders } from '../../../test/testUtils';
import { autoCleanup } from '../../../test/testUtils';
import { ConsentOption } from '../ConsentOption';

autoCleanup();

describe('Phase 5 / T-5-06: ConsentOption', () => {
  it('renders the label and hint when provided', () => {
    renderInProvider({
      option: { id: 'allow-once', label: 'Allow', hint: 'once' },
      selected: false, index: 0, onSelect: () => {}, onActivate: () => {},
    });
    expect(screen.getByTestId('consent-option-allow-once').textContent).toContain('Allow');
    expect(screen.getByTestId('consent-option-allow-once').textContent).toContain('once');
  });

  it('marks the option as selected when selected=true', () => {
    renderInProvider({
      option: { id: 'allow-once', label: 'A' },
      selected: true, index: 0, onSelect: () => {}, onActivate: () => {},
    });
    const li = screen.getByTestId('consent-option-allow-once');
    expect(li.classList.contains('selected')).toBe(true);
    expect(li.getAttribute('aria-selected')).toBe('true');
  });

  it('clicking the option fires onActivate with the option id', () => {
    const onActivate = vi.fn();
    renderInProvider({
      option: { id: 'allow-once', label: 'A' },
      selected: false, index: 0, onSelect: () => {}, onActivate,
    });
    fireEvent.click(screen.getByTestId('consent-option-button-allow-once'));
    expect(onActivate).toHaveBeenCalledWith('allow-once');
  });

  it('hovering the option updates the selection via onSelect', () => {
    const onSelect = vi.fn();
    renderInProvider({
      option: { id: 'allow-once', label: 'A' },
      selected: false, index: 0, onSelect, onActivate: () => {},
    });
    fireEvent.mouseEnter(screen.getByTestId('consent-option-button-allow-once'));
    expect(onSelect).toHaveBeenCalledWith('allow-once');
  });

  it('Enter on the option button fires onActivate', () => {
    const onActivate = vi.fn();
    renderInProvider({
      option: { id: 'allow-once', label: 'A' },
      selected: false, index: 0, onSelect: () => {}, onActivate,
    });
    fireEvent.keyDown(screen.getByTestId('consent-option-button-allow-once'), { key: 'Enter' });
    expect(onActivate).toHaveBeenCalledWith('allow-once');
  });
});

function renderInProvider(props: React.ComponentProps<typeof ConsentOption>) {
  const { render } = require('@testing-library/react');
  return render(
    <TestProviders>
      <ConsentOption {...props} />
    </TestProviders>,
  );
}
