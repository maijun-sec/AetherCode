// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { ModelPickerPopover } from '../ModelPickerPopover';
import type { ProviderInfo } from '../ProviderModelPicker';

/**
 * R342 — ModelPickerPopover unit tests.
 *
 * <p>Pins the post-R342 behaviour for the MessageInput
 * model picker:
 * <ul>
 *   <li>the trigger button shows {@code provider/model}
 *       and is closed by default</li>
 *   <li>clicking the trigger opens a popover with the
 *       full R341 chip row + filter + model list</li>
 *   <li>click-outside closes the popover</li>
 *   <li>Escape closes the popover</li>
 *   <li>clicking a model fires {@link #onSwitch} and
 *       closes the popover</li>
 *   <li>switching chips in the popover stays internal
 *       (no onSwitch until a model is picked)</li>
 *   <li>the trigger button greys out when disabled</li>
 *   <li>chip-row filter narrows the model list (case-
 *       insensitive substring match)</li>
 * </ul>
 */

const SAMPLE_PROVIDERS: ProviderInfo[] = [
  {
    name: 'minmax',
    defaultModel: 'MiniMax-M3',
    hasApiKey: true,
    models: [
      { id: 'MiniMax-M3', default: true, inputPer1k: 0.001, outputPer1k: 0.008 },
      { id: 'MiniMax-Text-01', inputPer1k: 0.001, outputPer1k: 0.008 },
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
    ],
  },
  {
    name: 'deepseek',
    defaultModel: 'deepseek-chat',
    hasApiKey: true,
    models: [
      { id: 'deepseek-chat', default: true },
      { id: 'deepseek-reasoner' },
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
];

describe('R342: ModelPickerPopover — trigger + popover shell', () => {
  it('renders the trigger button with the current provider/model label', () => {
    render(
      <ModelPickerPopover
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={() => {}}
      />
    );
    const trigger = screen.getByTestId('r342-model-picker-trigger');
    expect(trigger).toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-haspopup', 'dialog');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(trigger.textContent).toMatch(/minmax/);
    expect(trigger.textContent).toMatch(/MiniMax-M3/);
  });

  it('does not render the popover when closed', () => {
    render(
      <ModelPickerPopover
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={() => {}}
      />
    );
    expect(screen.queryByTestId('r342-model-picker-popover')).toBeNull();
  });

  it('opens the popover when the trigger is clicked', () => {
    render(
      <ModelPickerPopover
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={() => {}}
      />
    );
    fireEvent.click(screen.getByTestId('r342-model-picker-trigger'));
    expect(screen.getByTestId('r342-model-picker-popover')).toBeInTheDocument();
    expect(screen.getByTestId('r342-model-picker-trigger')).toHaveAttribute('aria-expanded', 'true');
  });

  it('toggles the popover closed when the trigger is clicked twice', () => {
    render(
      <ModelPickerPopover
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={() => {}}
      />
    );
    const trigger = screen.getByTestId('r342-model-picker-trigger');
    fireEvent.click(trigger);
    expect(screen.getByTestId('r342-model-picker-popover')).toBeInTheDocument();
    fireEvent.click(trigger);
    expect(screen.queryByTestId('r342-model-picker-popover')).toBeNull();
  });

  it('does not open the popover when disabled', () => {
    render(
      <ModelPickerPopover
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={() => {}}
        disabled
      />
    );
    const trigger = screen.getByTestId('r342-model-picker-trigger');
    expect(trigger).toBeDisabled();
    fireEvent.click(trigger);
    expect(screen.queryByTestId('r342-model-picker-popover')).toBeNull();
  });
});

describe('R342: ModelPickerPopover — chip row inside popover', () => {
  it('renders a chip for every hasApiKey=true provider', () => {
    render(
      <ModelPickerPopover
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={() => {}}
      />
    );
    fireEvent.click(screen.getByTestId('r342-model-picker-trigger'));
    // minmax/glm/deepseek all have hasApiKey=true;
    // anthropic is filtered out by hasApiKey=false.
    expect(screen.getByTestId('r341-provider-chip-minmax')).toBeInTheDocument();
    expect(screen.getByTestId('r341-provider-chip-glm')).toBeInTheDocument();
    expect(screen.getByTestId('r341-provider-chip-deepseek')).toBeInTheDocument();
    expect(screen.queryByTestId('r341-provider-chip-anthropic')).toBeNull();
  });

  it('hides providers with hasApiKey=false from the chip row', () => {
    // even when the parent passes unfiltered providers,
    // the inner ProviderModelPicker should filter.
    render(
      <ModelPickerPopover
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={() => {}}
      />
    );
    fireEvent.click(screen.getByTestId('r342-model-picker-trigger'));
    expect(screen.queryByTestId('r341-provider-chip-anthropic')).toBeNull();
  });

  it('switching chips in the popover does not fire onSwitch', () => {
    const onSwitch = vi.fn();
    render(
      <ModelPickerPopover
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={onSwitch}
      />
    );
    fireEvent.click(screen.getByTestId('r342-model-picker-trigger'));
    // Click the glm chip — should switch the model list
    // but NOT call onSwitch (no model picked yet).
    fireEvent.click(screen.getByTestId('r341-provider-chip-glm'));
    expect(onSwitch).not.toHaveBeenCalled();
    // The model list now shows glm models.
    expect(screen.getByTestId('r341-model-list')).toBeInTheDocument();
    expect(screen.getByTestId('r341-model-glm-glm-4-flash')).toBeInTheDocument();
  });
});

describe('R342: ModelPickerPopover — model selection', () => {
  it('clicking a model fires onSwitch(provider, model) and closes the popover', () => {
    const onSwitch = vi.fn();
    render(
      <ModelPickerPopover
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={onSwitch}
      />
    );
    fireEvent.click(screen.getByTestId('r342-model-picker-trigger'));
    // Switch to glm chip first.
    fireEvent.click(screen.getByTestId('r341-provider-chip-glm'));
    // Click glm-4-flash.
    fireEvent.click(screen.getByTestId('r341-model-glm-glm-4-flash'));
    expect(onSwitch).toHaveBeenCalledWith('glm', 'glm-4-flash');
    expect(screen.queryByTestId('r342-model-picker-popover')).toBeNull();
  });

  it('the popover closes optimistically before onSwitch settles (no stuck-open state)', () => {
    // Pin that the popover closes synchronously on model
    // click, regardless of how long the RPC takes. We use
    // a slow-but-resolving mock to verify the popover is
    // gone BEFORE the promise resolves — i.e. the UI
    // doesn't wait for the daemon. The parent surfaces
    // errors via the engineState refresh on the next tick,
    // so a failing RPC doesn't keep the popover stuck.
    const onSwitch = vi.fn().mockImplementation(
      () => new Promise<void>((resolve) => { setTimeout(resolve, 1000); })
    );
    render(
      <ModelPickerPopover
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={onSwitch}
      />
    );
    fireEvent.click(screen.getByTestId('r342-model-picker-trigger'));
    fireEvent.click(screen.getByTestId('r341-model-minmax-MiniMax-Text-01'));
    // Popover closes synchronously — we don't wait for
    // the 1000ms mock to resolve.
    expect(screen.queryByTestId('r342-model-picker-popover')).toBeNull();
    expect(onSwitch).toHaveBeenCalledWith('minmax', 'MiniMax-Text-01');
  });
});

describe('R342: ModelPickerPopover — popover dismissal', () => {
  it('closes the popover when clicking outside', () => {
    render(
      <div>
        <ModelPickerPopover
          providers={SAMPLE_PROVIDERS}
          currentProvider="minmax"
          currentModel="MiniMax-M3"
          onSwitch={() => {}}
        />
        <div data-testid="outside">outside element</div>
      </div>
    );
    fireEvent.click(screen.getByTestId('r342-model-picker-trigger'));
    expect(screen.getByTestId('r342-model-picker-popover')).toBeInTheDocument();
    // mousedown on the outside element triggers the close
    // handler (document-level mousedown listener).
    fireEvent.mouseDown(screen.getByTestId('outside'));
    expect(screen.queryByTestId('r342-model-picker-popover')).toBeNull();
  });

  it('closes the popover when Escape is pressed', () => {
    render(
      <ModelPickerPopover
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={() => {}}
      />
    );
    fireEvent.click(screen.getByTestId('r342-model-picker-trigger'));
    expect(screen.getByTestId('r342-model-picker-popover')).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByTestId('r342-model-picker-popover')).toBeNull();
  });

  it('does NOT close when clicking inside the popup (chip / model / filter)', () => {
    render(
      <ModelPickerPopover
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={() => {}}
      />
    );
    fireEvent.click(screen.getByTestId('r342-model-picker-trigger'));
    // Click the glm chip — outside the trigger but inside
    // the popover root. The click-outside detector should
    // NOT close the popover.
    fireEvent.mouseDown(screen.getByTestId('r341-provider-chip-glm'));
    expect(screen.getByTestId('r342-model-picker-popover')).toBeInTheDocument();
  });
});

describe('R342: ModelPickerPopover — filter input', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('the filter input narrows the model list', () => {
    render(
      <ModelPickerPopover
        providers={SAMPLE_PROVIDERS}
        currentProvider="minmax"
        currentModel="MiniMax-M3"
        onSwitch={() => {}}
      />
    );
    fireEvent.click(screen.getByTestId('r342-model-picker-trigger'));
    // minmax is the current provider; minmax models
    // render with the testid prefix `r341-model-minmax-*`.
    fireEvent.click(screen.getByTestId('r341-provider-chip-minmax'));
    const filterInput = screen.getByTestId('r341-model-filter');
    fireEvent.change(filterInput, { target: { value: 'M1' } });
    // 150ms debounce.
    act(() => { vi.advanceTimersByTime(200); });
    // Only MiniMax-M1 matches "M1" — MiniMax-M3 / Text-01
    // are filtered out.
    expect(screen.getByTestId('r341-model-minmax-MiniMax-M1')).toBeInTheDocument();
    expect(screen.queryByTestId('r341-model-minmax-MiniMax-M3')).toBeNull();
    expect(screen.queryByTestId('r341-model-minmax-MiniMax-Text-01')).toBeNull();
  });
});