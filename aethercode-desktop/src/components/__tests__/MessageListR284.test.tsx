// @vitest-environment jsdom
import { describe, expect, it, beforeEach } from 'vitest';
import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach } from 'vitest';
import { createFixture } from '../../test/testUtils';
import { useStore } from '../../store';
import { MessageList } from '../MessageList';

afterEach(() => cleanup());

/**
 * R284: compaction-summary message routing. When the
 * daemon emits a transcript message tagged with
 * {@code metadata.kind === "compaction-summary"} the
 * MessageList renders it as a dedicated row with a
 * "View original" affordance, NOT as a user bubble.
 * The affordance opens a modal that fetches the
 * snapshot via {@code compact/getSnapshot}.
 *
 * <p>Every render goes through {@link TestFixture.render}
 * (NOT the bare {@code @testing-library/react} render)
 * so the {@code RpcProvider} wraps the tree with the
 * mock-instrumented JsonRpcClient. Without it,
 * {@code useRpc()} falls back to the default singleton
 * whose Tauri invoke would fail in jsdom and the
 * modal would render a generic error instead of the
 * snapshot. */
describe('MessageList R284 compaction-summary', () => {
  it('renders a compaction-summary row with a View original button', () => {
    const fixture = createFixture({
      seed: {
        sessionId: 'sess-1',
        snapshots: {
          'sess-1': [{
            compactionIndex: 0,
            originalMessageCount: 5,
            keptMessageCount: 2,
            summary: 'summarised',
            messages: [
              { id: 'o1', role: 'user', timestamp: 1,
                content: [{ type: 'text', text: 'original-1' }] },
            ],
          }],
        },
      },
    });
    useStore.setState({
      currentSessionId: 'sess-1',
      messages: [{
        id: 'cs-1',
        role: 'user',
        content: '[Conversation compacted — earlier turns replaced by the summary below]\n\nfinal summary',
        timestamp: 1000,
        metadata: {
          kind: 'compaction-summary',
          compactionIndex: 0,
          originalCount: 5,
          snapshotPath: 'sess-1__0.json',
        },
      }],
      steps: [], subTasks: [],
    });
    fixture.render(<MessageList />);
    // the compaction-summary row is rendered
    expect(screen.getByTestId('compaction-summary')).toBeTruthy();
    // the View original affordance is present
    const btn = screen.getByTestId('compaction-summary-view-original');
    expect(btn.textContent).toContain('View original');
    expect(btn.textContent).toContain('5 msgs');
    // legacy user bubble is NOT rendered — the
    // compaction row replaces it
    expect(screen.queryByText('you')).toBeNull();
  });

  it('plain user messages still render via the legacy path', () => {
    const fixture = createFixture({ seed: { sessionId: 'sess-1' } });
    useStore.setState({
      currentSessionId: 'sess-1',
      messages: [{
        id: 'u-1', role: 'user', content: 'hi there',
        timestamp: 1000,
      }],
      steps: [], subTasks: [],
    });
    fixture.render(<MessageList />);
    // no compaction-summary row
    expect(screen.queryByTestId('compaction-summary')).toBeNull();
    // the legacy user bubble IS rendered
    expect(screen.getByText('hi there')).toBeTruthy();
    expect(screen.getByText('you')).toBeTruthy();
  });

  it('clicking View original opens the modal with snapshot data', async () => {
    const fixture = createFixture({
      seed: {
        sessionId: 'sess-1',
        snapshots: {
          'sess-1': [{
            compactionIndex: 0,
            originalMessageCount: 5,
            keptMessageCount: 2,
            summary: 'summarised',
            messages: [
              { id: 'o1', role: 'user', timestamp: 1,
                content: [{ type: 'text', text: 'original-1' }] },
              { id: 'o2', role: 'assistant', timestamp: 2,
                content: [{ type: 'text', text: 'original-2' }] },
            ],
          }],
        },
      },
    });
    useStore.setState({
      currentSessionId: 'sess-1',
      messages: [{
        id: 'cs-2',
        role: 'user',
        content: '[Conversation compacted — earlier turns replaced by the summary below]\n\nfinal summary',
        timestamp: 1000,
        metadata: {
          kind: 'compaction-summary',
          compactionIndex: 0,
          originalCount: 5,
        },
      }],
      steps: [], subTasks: [],
    });
    fixture.render(<MessageList />);
    fireEvent.click(screen.getByTestId('compaction-summary-view-original'));
    await waitFor(() => {
      const list = screen.getByTestId('snapshot-modal-messages');
      expect(list.children.length).toBe(2);
      expect(list.textContent).toContain('original-1');
      expect(list.textContent).toContain('original-2');
    });
  });
});