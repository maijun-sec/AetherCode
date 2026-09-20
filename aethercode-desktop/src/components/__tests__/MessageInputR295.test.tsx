// @vitest-environment jsdom
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { MessageInput } from '../MessageInput';
import { useStore } from '../../store';

/**
 * R295: SDD toggle (with icon) + quality preset
 * `<select>` are rendered as a single config-group
 * cluster. Replaces the R285 4-pill row design with
 * a dropdown matching the Model select's affordance,
 * because the pills were too cryptic for users to
 * understand without a hover tooltip.
 */
describe('MessageInput R295: SDD 📐 icon + quality <select>', () => {
  beforeEach(() => {
    useStore.setState((s) => ({
      ...s,
      sddEnabled: false,
      isConnected: true,
      isStreaming: false,
      // R285 quality preset state. Default medium
      // so the select's value is the canonical
      // "balanced" preset.
      currentVariant: 'medium',
      activeVariant: {
        name: 'medium',
        description: 'Medium — balanced (default).',
        temperature: 0.7,
        maxTokens: 32_000,
        reasoningBudget: null,
        extendedThinking: false,
      },
      // R282 variant dropdown is fed by modelEntries.
      // Provide at least one entry so the Model
      // select renders, otherwise MessageInput may
      // early-return and skip the SDD group.
      modelEntries: [{ id: 'test-model', label: 'test-model' }],
    }));
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('renders the SDD toggle with the 📐 icon (R288 affordance preserved)', () => {
    render(<MessageInput />);
    const toggle = screen.getByTestId('sdd-toggle');
    expect(toggle).toBeTruthy();
    // R288 had 📐 规格化流程; R294-followup dropped
    // the icon and renamed to bare `SDD`. R295
    // re-introduces the icon while keeping `SDD` as
    // the uppercase label.
    expect(toggle.textContent).toContain('SDD');
    expect(toggle.querySelector('.config-toggle-sdd-icon')?.textContent).toBe('📐');
    expect(toggle.getAttribute('aria-pressed')).toBe('false');
  });

  it('flips aria-pressed when SDD is clicked', () => {
    render(<MessageInput />);
    const toggle = screen.getByTestId('sdd-toggle');
    fireEvent.click(toggle);
    expect(useStore.getState().sddEnabled).toBe(true);
    expect(toggle.getAttribute('aria-pressed')).toBe('true');
  });

  it('renders the quality preset <select> with 4 options + per-option title', () => {
    render(<MessageInput />);
    const select = screen.getByTestId('message-input-quality');
    expect(select.tagName).toBe('SELECT');
    // R285 had 4 buttons. R295 collapses them into
    // 4 <option>s inside one <select>.
    const options = Array.from(select.querySelectorAll('option'));
    expect(options.map((o) => o.value)).toEqual(['low', 'medium', 'high', 'xhigh']);
    // Each option's `title` carries the daemon
    // description so users can hover to read
    // what each preset actually does.
    expect(options[0].title).toContain('0.3');
    expect(options[1].title).toContain('0.7');
    expect(options[2].title).toContain('1.0');
    expect(options[3].title).toContain('think');
  });

  it('reflects the active variant as the <select> value', () => {
    useStore.setState((s) => ({ ...s, currentVariant: 'xhigh' }));
    render(<MessageInput />);
    const select = screen.getByTestId('message-input-quality');
    expect((select as HTMLSelectElement).value).toBe('xhigh');
  });

  it('falls back to "medium" when currentVariant is empty', () => {
    useStore.setState((s) => ({ ...s, currentVariant: null }));
    render(<MessageInput />);
    const select = screen.getByTestId('message-input-quality');
    // empty / null → medium (the bundled default).
    expect((select as HTMLSelectElement).value).toBe('medium');
  });

  it('switching the <select> value calls store.switchVariant', async () => {
    const spy = vi.spyOn(useStore.getState(), 'switchVariant').mockResolvedValue(null);
    render(<MessageInput />);
    const select = screen.getByTestId('message-input-quality');
    fireEvent.change(select, { target: { value: 'high' } });
    expect(spy).toHaveBeenCalledWith('high');
  });

  it('keeps SDD + quality grouped in `.config-group-sdd-quality`', () => {
    render(<MessageInput />);
    const group = screen.getByTestId('sdd-quality-group');
    expect(group.classList.contains('config-group-sdd-quality')).toBe(true);
    // SDD toggle and quality <select> are direct
    // children of the same group.
    expect(group.querySelector('[data-testid="sdd-toggle"]')).toBeTruthy();
    expect(group.querySelector('[data-testid="message-input-quality"]')).toBeTruthy();
  });

  it('shows the active variant details (R285 detail readout still rendered)', () => {
    render(<MessageInput />);
    const detail = screen.getByTestId('message-input-quality-detail');
    // 0.7 / 32k (medium preset).
    expect(detail.textContent).toContain('0.7');
    expect(detail.textContent).toContain('32k');
    // No "think" badge for medium.
    expect(detail.textContent).not.toContain('think');
  });
});