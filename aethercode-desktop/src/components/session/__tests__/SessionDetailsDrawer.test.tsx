// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
import { createFixture, flush } from '../../../test/testUtils';
import { autoCleanup } from '../../../test/testUtils';
import { SessionDetailsDrawer } from '../SessionDetailsDrawer';
import type { SessionDetail } from '../../../rpc/types';

const detail: SessionDetail = {
  id: 's-1',
  title: 'Demo',
  cwd: '/tmp/proj',
  model: 'm-1',
  state: 'paused',
  startedAt: 1000,
  lastActiveAt: 2000,
  tokensIn: 100,
  tokensOut: 200,
  parentId: 'p-0',
  trashedAt: null,
  effort: 'high',
  messages: [],
  todos: [
    { id: 't-1', title: 'a', status: 'completed' },
    { id: 't-2', title: 'b', status: 'pending' },
  ],
  events: [],
  toolCounters: {},
};

autoCleanup();

describe('Phase 4.1 / T-4-04: SessionDetailsDrawer', () => {
  let fixture = createFixture({ seed: { sessions: [detail] } });

  beforeEach(() => {
    fixture = createFixture({ seed: { sessions: [detail] } });
  });

  it('renders closed when sessionId is null', () => {
    fixture.render(<SessionDetailsDrawer sessionId={null} onClose={() => {}} />);
    const drawer = screen.getByTestId('session-details-drawer');
    expect(drawer.getAttribute('data-open')).toBe('false');
  });

  it('renders the session id + cwd in copyable rows', async () => {
    fixture.render(<SessionDetailsDrawer sessionId="s-1" onClose={() => {}} />);
    await flush();
    expect((await screen.findByTestId('details-id')).textContent).toBe('s-1');
    expect(screen.getByTestId('details-cwd').textContent).toBe('/tmp/proj');
  });

  it('shows model + effort badges', async () => {
    fixture.render(<SessionDetailsDrawer sessionId="s-1" onClose={() => {}} />);
    await flush();
    expect((await screen.findByTestId('details-model')).textContent).toBe('m-1');
    expect(screen.getByTestId('details-effort').textContent).toBe('high');
  });

  it('shows token totals in the tokens row', async () => {
    fixture.render(<SessionDetailsDrawer sessionId="s-1" onClose={() => {}} />);
    await flush();
    expect(screen.getByTestId('details-tokens-in').textContent).toContain('100');
    expect(screen.getByTestId('details-tokens-out').textContent).toContain('200');
  });

  it('shows the parent session id when present', async () => {
    fixture.render(<SessionDetailsDrawer sessionId="s-1" onClose={() => {}} />);
    await flush();
    expect((await screen.findByTestId('details-parent')).textContent).toBe('p-0');
  });

  it('shows the todo completion summary', async () => {
    fixture.render(<SessionDetailsDrawer sessionId="s-1" onClose={() => {}} />);
    await flush();
    expect((await screen.findByTestId('details-todos')).textContent).toContain('1/2');
  });

  it('onClose fires when the close button is clicked', async () => {
    let closed = false;
    fixture.render(<SessionDetailsDrawer sessionId="s-1" onClose={() => { closed = true; }} />);
    await flush();
    fireEvent.click(screen.getByTestId('session-details-close'));
    expect(closed).toBe(true);
  });

  it('onOpenEvents / onOpenFileDiffs are wired to their buttons', async () => {
    let eventsId: string | null = null;
    let diffsId: string | null = null;
    fixture.render(
      <SessionDetailsDrawer
        sessionId="s-1"
        onClose={() => {}}
        onOpenEvents={(id) => { eventsId = id; }}
        onOpenFileDiffs={(id) => { diffsId = id; }}
      />,
    );
    await flush();
    fireEvent.click(screen.getByTestId('details-open-events'));
    fireEvent.click(screen.getByTestId('details-open-diffs'));
    expect(eventsId).toBe('s-1');
    expect(diffsId).toBe('s-1');
  });

  it('renders the error state when the session id is unknown', async () => {
    const fresh = createFixture({ seed: { sessions: [] } });
    fresh.render(<SessionDetailsDrawer sessionId="missing" onClose={() => {}} />);
    await flush();
    expect(await screen.findByTestId('session-details-error')).toBeDefined();
  });

  it('shows the started + last-active timestamps formatted', async () => {
    fixture.render(<SessionDetailsDrawer sessionId="s-1" onClose={() => {}} />);
    await flush();
    const start = screen.getByTestId('details-started').textContent;
    expect(start).toBeTruthy();
    expect(start).not.toBe('—');
  });
});
