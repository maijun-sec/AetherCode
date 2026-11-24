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
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, act, fireEvent } from '@testing-library/react';
import { Header } from '../Header';
import { useStore } from '../../store';
import { rpc } from '../../lib/methods';

/**
 * R401 — the intent / profile chip in the Header.
 *
 * The chip is the whole point of R401 on the renderer side: the daemon
 * decides what kind of turn this is (and therefore which tools it may
 * touch), the user SEES that decision, and the user can CORRECT it with
 * one click.
 *
 * ★ The renderer does not decide anything. It renders the daemon's
 * verdict (the R385 rule) and sends the daemon's own profile name back
 * — so the vocabulary lives in exactly one place.
 */

const { invokeMock } = vi.hoisted(() => ({
  invokeMock: vi.fn(async (_cmd: string, args: any): Promise<any> => {
    if (args?.method === 'setProfile') {
      return {
        ok: true,
        sessionId: 's1',
        profile: args?.params?.profile === 'auto' ? null : args?.params?.profile,
        profiles: [
          { name: 'builder', label: '写代码', description: '实现需求' },
          { name: 'researcher', label: '查资料', description: '先查后答' },
        ],
      };
    }
    if (args?.method === 'listSessions') return { sessions: [] };
    return {};
  }),
}));

vi.mock('@tauri-apps/api/core', () => ({
  invoke: invokeMock,
}));
vi.mock('@tauri-apps/api/event', () => ({
  listen: vi.fn(async () => () => {}),
  emit: vi.fn(),
}));

beforeEach(() => {
  invokeMock.mockClear();
  useStore.setState({
    messages: [],
    isStreaming: false,
    currentSessionId: 's1',
    activeRunId: null,
    notifications: [],
    notificationHistory: [],
    lastFailedPrompt: null,
    lastFailedStopReason: null,
    lastFailedAt: null,
    currentQuery: null,
    currentStepId: null,
    steps: [],
    subTasks: [],
    pendingFollowUp: null,
    currentActivity: null,
    intentKind: null,
    intentLabel: null,
    profileName: null,
    profileLabel: null,
    profileOverride: null,
    profiles: [],
  });
});

function emitRunStart(extra: Record<string, unknown>) {
  rpc.__test_dispatchNotification('stream_event', {
    runId: 'run-7',
    sessionId: 's1',
    event: { type: 'run_start', runId: 'run-7', model: 'stub', ...extra },
  });
}

describe('R401 — the Header chip', () => {
  it('shows nothing until the daemon has sent a verdict', () => {
    render(<Header onSettingsClick={() => {}} />);
    expect(screen.queryByTestId('header-intent-chip')).toBeNull();
  });

  it('shows the daemon verdict', () => {
    emitRunStart({
      intent: 'research', intentLabel: '概念搜索',
      profile: 'researcher', profileLabel: '查资料',
    });
    render(<Header onSettingsClick={() => {}} />);
    const chip = screen.getByTestId('header-intent-chip');
    expect(chip.textContent).toContain('概念搜索');
    expect(chip.textContent).toContain('查资料');
  });

  it('the picker lists the DAEMON\'s profiles and sends one back', async () => {
    emitRunStart({
      intent: 'research', intentLabel: '概念搜索',
      profile: 'researcher', profileLabel: '查资料',
    });
    // the daemon published the vocabulary
    await act(async () => {
      await useStore.getState().setProfileOverride('researcher');
    });

    render(<Header onSettingsClick={() => {}} />);
    fireEvent.click(screen.getByTestId('header-intent-chip'));

    const auto = screen.getByText('自动（按意图判定）');
    expect(auto, 'the picker must offer "auto"').toBeTruthy();
    const item = screen.getByText('查资料');
    expect(item, 'the picker must render the daemon\'s labels').toBeTruthy();

    invokeMock.mockClear();
    await act(async () => {
      fireEvent.click(item);
    });

    const call = invokeMock.mock.calls.find((c: any[]) => c[1]?.method === 'setProfile');
    expect(call, 'picking a profile must reach the daemon').toBeTruthy();
    // the NAME goes back, not the label — the daemon owns the vocabulary.
    expect((call as any[])[1].params).toMatchObject({ profile: 'researcher' });
  });

  it('an active override is marked as locked', () => {
    emitRunStart({
      intent: 'research', intentLabel: '概念搜索',
      profile: 'researcher', profileLabel: '查资料',
    });
    useStore.setState({ profileOverride: 'researcher' });
    render(<Header onSettingsClick={() => {}} />);
    expect(screen.getByTestId('header-intent-chip').className).toContain('is-overridden');
  });
});
