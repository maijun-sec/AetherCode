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
import { describe, it, expect, vi } from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
import { TestProviders } from '../../../test/testUtils';
import { autoCleanup } from '../../../test/testUtils';
import { SubagentSpawnCard } from '../SubagentSpawnCard';
import type { SessionEvent } from '../../../rpc/types';

function makeEvent(over: Partial<SessionEvent> = {}): SessionEvent {
  return {
    id: 'e-1',
    type: 'subagent_spawn',
    ts: 1000,
    subagentId: 'sag-1',
    data: { role: 'researcher', description: 'find the bug', status: 'spawned' },
    ...over,
  };
}

autoCleanup();

describe('Phase 4.2 / T-4-15: SubagentSpawnCard', () => {
  it('renders the role + subagent id in the header', () => {
    const onOpen = vi.fn();
    renderInProvider(makeEvent(), onOpen);
    expect(screen.getByTestId('subagent-spawn-role').textContent).toBe('researcher');
    expect(screen.getByTestId('subagent-spawn-id').textContent).toBe('sag-1');
    expect(screen.getByTestId('subagent-spawn-card').getAttribute('data-subagent-id')).toBe('sag-1');
  });

  it('starts collapsed (body hidden)', () => {
    renderInProvider(makeEvent(), vi.fn());
    expect(screen.queryByTestId('subagent-spawn-body')).toBeNull();
  });

  it('expands the body on header click', () => {
    renderInProvider(makeEvent(), vi.fn());
    fireEvent.click(screen.getByTestId('subagent-spawn-toggle'));
    expect(screen.getByTestId('subagent-spawn-body')).toBeDefined();
    expect(screen.getByTestId('subagent-spawn-description').textContent).toBe('find the bug');
  });

  it('collapses again on a second click', () => {
    renderInProvider(makeEvent(), vi.fn());
    const header = screen.getByTestId('subagent-spawn-toggle');
    fireEvent.click(header);
    expect(screen.getByTestId('subagent-spawn-body')).toBeDefined();
    fireEvent.click(header);
    expect(screen.queryByTestId('subagent-spawn-body')).toBeNull();
  });

  it('expands on Enter / Space keyboard', () => {
    renderInProvider(makeEvent(), vi.fn());
    const header = screen.getByTestId('subagent-spawn-toggle');
    fireEvent.keyDown(header, { key: 'Enter' });
    expect(screen.getByTestId('subagent-spawn-body')).toBeDefined();
  });

  it('renders the status pill from event.data.status', () => {
    renderInProvider(makeEvent({ data: { role: 'r', status: 'running' } }), vi.fn());
    expect(screen.getByTestId('subagent-spawn-status').textContent).toBe('running');
  });

  it('falls back to a default id when subagentId is missing', () => {
    const ev: SessionEvent = {
      id: 'e-1',
      type: 'subagent_spawn',
      ts: 1000,
      data: { role: 'r' },
    };
    renderInProvider(ev, vi.fn());
    expect(screen.getByTestId('subagent-spawn-id').textContent).toBe('subagent');
  });

  it('clicking "View subagent" fires onOpen with the subagent id', () => {
    const onOpen = vi.fn();
    renderInProvider(makeEvent(), onOpen);
    fireEvent.click(screen.getByTestId('subagent-spawn-toggle'));
    fireEvent.click(screen.getByTestId('subagent-spawn-link'));
    expect(onOpen).toHaveBeenCalledWith('sag-1');
  });

  it('hides the "View subagent" link when onOpen is not provided', () => {
    renderInProvider(makeEvent(), undefined);
    fireEvent.click(screen.getByTestId('subagent-spawn-toggle'));
    expect(screen.queryByTestId('subagent-spawn-link')).toBeNull();
  });
});

function renderInProvider(event: SessionEvent, onOpen?: (id: string) => void) {
  const { render } = require('@testing-library/react');
  return render(
    <TestProviders>
      <SubagentSpawnCard event={event} {...(onOpen ? { onOpen } : {})} />
    </TestProviders>,
  );
}
