// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, fireEvent, cleanup } from '@testing-library/react';
import { NewSessionModeDialog } from '../NewSessionModeDialog';
import { useStore } from '../../store';

/**
 * R331: NewSessionModeDialog — visible when the store has
 * `pendingNewSession: { cwd }` set. Three buttons (普通 /
 * SDD 规范化 / Workflow) each call `createNewSession({ mode })`
 * with the corresponding mode. Cancel + Escape + clicking the
 * backdrop close without creating.
 */
describe('R331 NewSessionModeDialog', () => {
  beforeEach(() => {
    // reset the store flag so previous tests' state doesn't
    // bleed in.
    useStore.setState({ pendingNewSession: null });
  });
  afterEach(() => {
    cleanup();
    useStore.setState({ pendingNewSession: null });
  });

  it('renders nothing when pendingNewSession is null', () => {
    const { container } = render(<NewSessionModeDialog />);
    expect(container.querySelector('[data-testid="new-session-mode-dialog"]')).toBeNull();
  });

  it('renders the dialog when pendingNewSession is set', () => {
    useStore.setState({ pendingNewSession: { cwd: 'D:/tmp/foo' } });
    const { getByTestId } = render(<NewSessionModeDialog />);
    expect(getByTestId('new-session-mode-dialog')).not.toBeNull();
    expect(getByTestId('new-session-mode-option-normal')).not.toBeNull();
    expect(getByTestId('new-session-mode-option-sdd')).not.toBeNull();
    expect(getByTestId('new-session-mode-option-workflow')).not.toBeNull();
    expect(getByTestId('new-session-mode-cancel')).not.toBeNull();
  });

  it('picking SDD calls createNewSession({ mode: "sdd" })', () => {
    useStore.setState({ pendingNewSession: { cwd: 'D:/tmp/foo' } });
    const createNewSession = vi.fn();
    // Spy on the store's createNewSession.
    const original = useStore.getState().createNewSession;
    useStore.setState({ createNewSession: createNewSession as any });
    try {
      const { getByTestId } = render(<NewSessionModeDialog />);
      fireEvent.click(getByTestId('new-session-mode-option-sdd'));
      expect(createNewSession).toHaveBeenCalledWith({ mode: 'sdd' });
    } finally {
      useStore.setState({ createNewSession: original });
    }
  });

  it('picking normal calls createNewSession({ mode: "normal" })', () => {
    useStore.setState({ pendingNewSession: { cwd: null } });
    const createNewSession = vi.fn();
    const original = useStore.getState().createNewSession;
    useStore.setState({ createNewSession: createNewSession as any });
    try {
      const { getByTestId } = render(<NewSessionModeDialog />);
      fireEvent.click(getByTestId('new-session-mode-option-normal'));
      expect(createNewSession).toHaveBeenCalledWith({ mode: 'normal' });
    } finally {
      useStore.setState({ createNewSession: original });
    }
  });

  it('picking workflow calls createNewSession({ mode: "workflow" })', () => {
    useStore.setState({ pendingNewSession: { cwd: null } });
    const createNewSession = vi.fn();
    const original = useStore.getState().createNewSession;
    useStore.setState({ createNewSession: createNewSession as any });
    try {
      const { getByTestId } = render(<NewSessionModeDialog />);
      fireEvent.click(getByTestId('new-session-mode-option-workflow'));
      expect(createNewSession).toHaveBeenCalledWith({ mode: 'workflow' });
    } finally {
      useStore.setState({ createNewSession: original });
    }
  });

  it('cancel button closes the dialog without creating', () => {
    useStore.setState({ pendingNewSession: { cwd: 'D:/tmp/x' } });
    const { getByTestId } = render(<NewSessionModeDialog />);
    fireEvent.click(getByTestId('new-session-mode-cancel'));
    expect(useStore.getState().pendingNewSession).toBeNull();
  });

  it('Escape key closes the dialog', () => {
    useStore.setState({ pendingNewSession: { cwd: 'D:/tmp/x' } });
    render(<NewSessionModeDialog />);
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(useStore.getState().pendingNewSession).toBeNull();
  });

  it('clicking the backdrop closes the dialog', () => {
    useStore.setState({ pendingNewSession: { cwd: 'D:/tmp/x' } });
    const { container } = render(<NewSessionModeDialog />);
    const backdrop = container.querySelector('.new-session-mode-dialog-backdrop');
    expect(backdrop).not.toBeNull();
    fireEvent.click(backdrop!);
    expect(useStore.getState().pendingNewSession).toBeNull();
  });
});