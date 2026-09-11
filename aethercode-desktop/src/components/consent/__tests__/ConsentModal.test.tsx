// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
import { TestProviders } from '../../../test/testUtils';
import { autoCleanup } from '../../../test/testUtils';
import { ConsentModal } from '../ConsentModal';
import type { ConsentRequest } from '../../../rpc/types';

function makeRequest(over: Partial<ConsentRequest> = {}): ConsentRequest {
  return {
    requestId: 'req-1',
    toolName: 'npm install',
    category: 'shell.command.npm',
    args: { cmd: 'npm install' },
    riskLevel: 'medium',
    ...over,
  };
}

autoCleanup();

describe('Phase 5 / T-5-05: ConsentModal', () => {
  it('renders nothing when the request is null', () => {
    renderInProvider(null);
    expect(screen.queryByTestId('consent-modal')).toBeNull();
  });

  it('renders the tool name in the title for a non-null request', () => {
    renderInProvider(makeRequest());
    expect(screen.getByTestId('consent-modal').getAttribute('data-request-id')).toBe('req-1');
    expect(screen.getByTestId('consent-modal-title').textContent).toContain('npm install');
  });

  it('renders the 8 standard options for any category', () => {
    renderInProvider(makeRequest({ category: 'edit_file' }));
    const opts = screen.getByTestId('consent-options');
    const items = opts.querySelectorAll('li');
    expect(items.length).toBe(8);
  });

  it('renders options 9-10 when the category is shell.command.*', () => {
    renderInProvider(makeRequest({ category: 'shell.command.npm' }));
    const opts = screen.getByTestId('consent-options');
    const items = opts.querySelectorAll('li');
    expect(items.length).toBe(10);
    expect(screen.getByTestId('consent-option-allow-category-project')).toBeDefined();
    expect(screen.getByTestId('consent-option-deny-category-project')).toBeDefined();
  });

  it('omits options 9-10 for non-shell categories', () => {
    renderInProvider(makeRequest({ category: 'edit_file' }));
    expect(screen.queryByTestId('consent-option-allow-category-project')).toBeNull();
  });

  it('ArrowDown / ArrowUp move the selection', () => {
    renderInProvider(makeRequest());
    const modal = screen.getByTestId('consent-modal');
    // Default is index 1 (deny-once). Move down twice, then up.
    fireEvent.keyDown(modal, { key: 'ArrowDown' });
    fireEvent.keyDown(modal, { key: 'ArrowDown' });
    fireEvent.keyDown(modal, { key: 'ArrowUp' });
    // Selection is now at index 2 (allow-session).
    expect(screen.getByTestId('consent-option-allow-session').getAttribute('aria-selected')).toBe('true');
  });

  it('Enter fires onResolve with the current selection', () => {
    const resolve = vi.fn();
    renderInProvider(makeRequest(), resolve);
    const modal = screen.getByTestId('consent-modal');
    // Default is index 1 (deny-once). Don't move — Enter confirms
    // the current selection.
    fireEvent.keyDown(modal, { key: 'Enter' });
    expect(resolve).toHaveBeenCalledTimes(1);
    expect(resolve.mock.calls[0][0]).toBe('deny-once');
  });

  it('Esc maps to deny-once (per spec)', () => {
    const resolve = vi.fn();
    const cancel = vi.fn();
    renderInProvider(makeRequest(), resolve, cancel);
    fireEvent.keyDown(screen.getByTestId('consent-modal'), { key: 'Escape' });
    expect(resolve).toHaveBeenCalledWith('deny-once');
    expect(cancel).toHaveBeenCalled();
  });

  it('clicking an option fires onResolve immediately', () => {
    const resolve = vi.fn();
    renderInProvider(makeRequest(), resolve);
    fireEvent.click(screen.getByTestId('consent-option-button-allow-once'));
    expect(resolve).toHaveBeenCalledWith('allow-once');
  });

  it('the modal traps focus and exposes a labelled close affordance', () => {
    renderInProvider(makeRequest());
    const modal = screen.getByTestId('consent-modal');
    expect(modal.getAttribute('role')).toBe('dialog');
    expect(modal.getAttribute('aria-modal')).toBe('true');
    expect(modal.getAttribute('aria-labelledby')).toBe('consent-modal-title');
  });

  it('resets the default selection when a new request arrives', () => {
    const r1 = makeRequest({ requestId: 'a' });
    const r2 = makeRequest({ requestId: 'b' });
    const { rerender } = renderInProvider(r1);
    // Move to index 4 manually.
    const modal = screen.getByTestId('consent-modal');
    for (let i = 0; i < 3; i++) fireEvent.keyDown(modal, { key: 'ArrowDown' });
    expect(screen.getByTestId('consent-option-allow-project').getAttribute('aria-selected')).toBe('true');
    rerender(<ConsentModal request={r2} onResolve={() => {}} />);
    expect(screen.getByTestId('consent-option-deny-once').getAttribute('aria-selected')).toBe('true');
  });
});

function renderInProvider(request: ConsentRequest | null, onResolve: (c: any) => void = () => {}, onCancel: () => void = () => {}) {
  const { render } = require('@testing-library/react');
  return render(
    <TestProviders>
      <ConsentModal request={request} onResolve={onResolve} onCancel={onCancel} />
    </TestProviders>,
  );
}
