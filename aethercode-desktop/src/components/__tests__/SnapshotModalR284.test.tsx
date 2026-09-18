// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { createFixture } from '../../test/testUtils';
import { SnapshotModal } from '../SnapshotModal';

const SEED_SNAPSHOT = {
  compactionIndex: 0,
  originalMessageCount: 3,
  keptMessageCount: 2,
  createdAt: '2026-09-18T10:00:00Z',
  fileName: 'sess-1__0.json',
  summary: 'the model summarised the conversation here',
  messages: [
    {
      id: 'm1', role: 'user', timestamp: 1000,
      content: [{ type: 'text', text: 'hello' }],
    },
    {
      id: 'm2', role: 'assistant', timestamp: 2000,
      content: [{ type: 'text', text: 'world' }],
    },
    {
      id: 'm3', role: 'user', timestamp: 3000,
      content: [{ type: 'text', text: 'foo bar baz' }],
    },
  ],
};

/**
 * R284: SnapshotModal contract. The modal is mounted from
 * MessageList when the user clicks "View original" on a
 * compaction-summary message. We exercise the open /
 * load / close lifecycle plus the not-found branch and
 * the "no metadata → empty messages" branch.
 */
describe('SnapshotModal R284', () => {
  it('renders nothing when closed', () => {
    const fixture = createFixture({
      seed: {
        sessionId: 'sess-1',
        snapshots: { 'sess-1': [SEED_SNAPSHOT] },
      },
    });
    const { container } = fixture.render(
      <SnapshotModal open={false} compactionIndex={0} onClose={() => {}} />,
    );
    expect(container.querySelector('[data-testid="snapshot-modal"]')).toBeNull();
  });

  it('opens, fetches the snapshot, renders the messages', async () => {
    const fixture = createFixture({
      seed: {
        sessionId: 'sess-1',
        snapshots: { 'sess-1': [SEED_SNAPSHOT] },
      },
    });
    fixture.render(
      <SnapshotModal
        open
        sessionId="sess-1"
        compactionIndex={0}
        summaryPreview="the model summarised here"
        onClose={() => {}}
      />,
    );
    // the summary preview is shown at the top of the modal
    expect(screen.getByTestId('snapshot-modal-summary').textContent)
        .toContain('the model summarised here');
    // loading state shows briefly, then the messages render
    await waitFor(() => {
      const list = screen.getByTestId('snapshot-modal-messages');
      expect(list.children.length).toBe(3);
    });
    // the role pills + first text bodies are rendered verbatim
    const list = screen.getByTestId('snapshot-modal-messages');
    expect(list.textContent).toContain('hello');
    expect(list.textContent).toContain('world');
    expect(list.textContent).toContain('foo bar baz');
  });

  it('shows not-found message when compactionIndex misses', async () => {
    const fixture = createFixture({
      seed: {
        sessionId: 'sess-1',
        snapshots: { 'sess-1': [SEED_SNAPSHOT] },
      },
    });
    fixture.render(
      <SnapshotModal
        open
        sessionId="sess-1"
        compactionIndex={99}
        onClose={() => {}}
      />,
    );
    await waitFor(() => {
      const err = screen.getByTestId('snapshot-modal-error');
      expect(err.textContent).toContain('not found');
    });
  });

  it('ESC closes the modal', async () => {
    const fixture = createFixture({
      seed: {
        sessionId: 'sess-1',
        snapshots: { 'sess-1': [SEED_SNAPSHOT] },
      },
    });
    const onClose = vi.fn();
    fixture.render(
      <SnapshotModal
        open
        sessionId="sess-1"
        compactionIndex={0}
        onClose={onClose}
      />,
    );
    await waitFor(() => {
      // wait for the open transition to install the keydown listener
      screen.getByTestId('snapshot-modal-card');
    });
    act(() => {
      fireEvent.keyDown(document, { key: 'Escape' });
    });
    expect(onClose).toHaveBeenCalled();
  });

  it('backdrop click closes the modal', async () => {
    const fixture = createFixture({
      seed: {
        sessionId: 'sess-1',
        snapshots: { 'sess-1': [SEED_SNAPSHOT] },
      },
    });
    const onClose = vi.fn();
    fixture.render(
      <SnapshotModal
        open
        sessionId="sess-1"
        compactionIndex={0}
        onClose={onClose}
      />,
    );
    await waitFor(() => {
      screen.getByTestId('snapshot-modal-card');
    });
    act(() => {
      fireEvent.click(screen.getByTestId('snapshot-modal'));
    });
    expect(onClose).toHaveBeenCalled();
  });

  it('close button fires onClose', async () => {
    const fixture = createFixture({
      seed: {
        sessionId: 'sess-1',
        snapshots: { 'sess-1': [SEED_SNAPSHOT] },
      },
    });
    const onClose = vi.fn();
    fixture.render(
      <SnapshotModal
        open
        sessionId="sess-1"
        compactionIndex={0}
        onClose={onClose}
      />,
    );
    await waitFor(() => {
      screen.getByTestId('snapshot-modal-close');
    });
    act(() => {
      fireEvent.click(screen.getByTestId('snapshot-modal-close'));
    });
    expect(onClose).toHaveBeenCalled();
  });

  it('falls back to defaultSessionId when sessionId omitted', async () => {
    const fixture = createFixture({
      seed: {
        sessionId: 'sess-1',
        snapshots: { 'sess-1': [SEED_SNAPSHOT] },
      },
    });
    fixture.render(
      <SnapshotModal
        open
        compactionIndex={0}
        onClose={() => {}}
      />,
    );
    await waitFor(() => {
      const list = screen.getByTestId('snapshot-modal-messages');
      expect(list.children.length).toBe(3);
    });
  });
});