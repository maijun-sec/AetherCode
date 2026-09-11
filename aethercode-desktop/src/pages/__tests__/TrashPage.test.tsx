// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { createFixture, flush } from '../../test/testUtils';
import { autoCleanup } from '../../test/testUtils';
import { TrashPage } from '../TrashPage';
import type { SessionDetail } from '../../rpc/types';

const trashed: SessionDetail = {
  id: 's-1',
  title: 'Old login flow',
  cwd: '/tmp/proj',
  model: 'm-1',
  state: 'completed',
  startedAt: 1000,
  lastActiveAt: 2000,
  tokensIn: 100,
  tokensOut: 200,
  parentId: null,
  trashedAt: 1500,
  effort: 'medium',
  messages: [],
  todos: [],
  events: [],
  toolCounters: {},
};
const live: SessionDetail = {
  ...trashed,
  id: 's-2',
  title: 'Active session',
  trashedAt: null,
};

autoCleanup();

describe('Phase 3 / T-3-10 + T-4-08: TrashPage', () => {
  let fixture = createFixture({ seed: { sessions: [trashed, live] } });
  beforeEach(() => {
    fixture = createFixture({ seed: { sessions: [trashed, live] } });
  });

  it('renders only the trashed sessions', async () => {
    fixture.render(<TrashPage />);
    await flush();
    expect(await screen.findByTestId('trash-row-s-1')).toBeDefined();
    expect(screen.queryByTestId('trash-row-s-2')).toBeNull();
  });

  it('shows an empty message when no trashed sessions exist', async () => {
    const fresh = createFixture({ seed: { sessions: [live] } });
    fresh.render(<TrashPage />);
    await flush();
    expect(await screen.findByTestId('trash-empty-msg')).toBeDefined();
  });

  it('restore button calls session/restore and removes the row', async () => {
    fixture.render(<TrashPage />);
    await flush();
    fireEvent.click(screen.getByTestId('trash-restore-s-1'));
    await flush();
    await waitFor(() => {
      expect(screen.queryByTestId('trash-row-s-1')).toBeNull();
    });
    const called = fixture.server.callLog.find((c) => c.method === 'session/restore');
    expect(called).toBeDefined();
  });

  it('delete-forever calls session/delete', async () => {
    fixture.render(<TrashPage />);
    await flush();
    fireEvent.click(screen.getByTestId('trash-delete-s-1'));
    await flush();
    const called = fixture.server.callLog.find((c) => c.method === 'session/delete');
    expect(called).toBeDefined();
  });

  it('empty-trash button shows the confirm dialog and triggers session/trash on confirm', async () => {
    fixture.render(<TrashPage />);
    await flush();
    fireEvent.click(screen.getByTestId('trash-empty'));
    expect(screen.getByTestId('trash-confirm')).toBeDefined();
    fireEvent.click(screen.getByTestId('trash-confirm-ok'));
    await flush();
    const called = fixture.server.callLog.find((c) => c.method === 'session/trash');
    expect(called).toBeDefined();
    expect((called?.params as { empty: boolean }).empty).toBe(true);
  });

  it('empty-trash cancel keeps the row', async () => {
    fixture.render(<TrashPage />);
    await flush();
    fireEvent.click(screen.getByTestId('trash-empty'));
    fireEvent.click(screen.getByTestId('trash-confirm-cancel'));
    await flush();
    expect(screen.getByTestId('trash-row-s-1')).toBeDefined();
  });

  it('onClose is called when the close button is clicked', () => {
    let closed = false;
    fixture.render(<TrashPage onClose={() => { closed = true; }} />);
    fireEvent.click(screen.getByTestId('trash-close'));
    expect(closed).toBe(true);
  });
});
