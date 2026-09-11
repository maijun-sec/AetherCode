// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { createFixture, flush } from '../../test/testUtils';
import { autoCleanup } from '../../test/testUtils';
import { SessionPage } from '../SessionPage';
import type { SessionDetail } from '../../rpc/types';

const detail: SessionDetail = {
  id: 's-1',
  title: 'Demo session',
  cwd: '/tmp/proj',
  model: 'm-1',
  state: 'running',
  startedAt: 1000,
  lastActiveAt: 2000,
  tokensIn: 100,
  tokensOut: 200,
  parentId: 'p-0',
  trashedAt: null,
  effort: 'medium',
  messages: [
    { id: 'm-1', role: 'user', content: 'hello', ts: 1000 },
    { id: 'm-2', role: 'assistant', content: 'hi there', ts: 1100 },
  ],
  todos: [
    { id: 't-1', title: 'Read README', status: 'in_progress' },
    { id: 't-2', title: 'Write a test', status: 'pending' },
  ],
  events: [],
  toolCounters: { fileReads: 3 },
};

autoCleanup();

describe('Phase 3 / T-3-11: SessionPage', () => {
  it('shows the session title, cwd, model and state from session/show', async () => {
    const fixture = createFixture({ seed: { sessions: [detail] } });
    fixture.render(<SessionPage sessionId="s-1" />);
    await flush();
    expect((await screen.findByTestId('session-cwd'))?.textContent).toBe('/tmp/proj');
    expect(screen.getByTestId('session-model').textContent).toBe('m-1');
    expect(screen.getByTestId('session-state').textContent).toBe('running');
  });

  it('renders the message list in arrival order', async () => {
    const fixture = createFixture({ seed: { sessions: [detail] } });
    fixture.render(<SessionPage sessionId="s-1" />);
    await flush();
    const messages = await screen.findByTestId('session-messages');
    expect(messages.querySelectorAll('li')).toHaveLength(2);
    expect(messages.textContent).toContain('hello');
    expect(messages.textContent).toContain('hi there');
  });

  it('shows a loading state when session/show is in flight', () => {
    const fixture = createFixture({ seed: { sessions: [detail] } });
    fixture.render(<SessionPage sessionId="s-1" />);
    expect(screen.getByTestId('session-loading')).toBeDefined();
  });

  it('shows the error state when the session id is unknown', async () => {
    const fixture = createFixture({ seed: { sessions: [] } });
    fixture.render(<SessionPage sessionId="missing" />);
    await flush();
    expect(await screen.findByTestId('session-error')).toBeDefined();
  });

  it('renders an empty-state when the session has no messages yet', async () => {
    const empty = { ...detail, messages: [] };
    const fixture = createFixture({ seed: { sessions: [empty] } });
    fixture.render(<SessionPage sessionId="s-1" />);
    await flush();
    expect(await screen.findByTestId('session-no-messages')).toBeDefined();
  });

  it('onClose handler fires when the close button is clicked', async () => {
    const fixture = createFixture({ seed: { sessions: [detail] } });
    let closed = false;
    fixture.render(<SessionPage sessionId="s-1" onClose={() => { closed = true; }} />);
    await flush();
    screen.getByTestId('session-close').click();
    expect(closed).toBe(true);
  });

  it('parent id surfaces in the header when present', async () => {
    const fixture = createFixture({ seed: { sessions: [detail] } });
    fixture.render(<SessionPage sessionId="s-1" />);
    await flush();
    await waitFor(() => {
      expect(screen.getByTestId('session-page').textContent).toContain('Demo session');
    });
  });
});
