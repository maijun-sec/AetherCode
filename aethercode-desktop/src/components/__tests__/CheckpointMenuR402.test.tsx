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
import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { createFixture } from '../../test/testUtils';
import {
  CheckpointMenu,
  CHECKPOINT_ACTIONS,
  CHECKPOINT_ACTION_INFO,
} from '../CheckpointMenu';
import { parseSlash, SLASH_COMMANDS } from '../commandCommands';

/**
 * R402: the desktop's CHECKPOINT (rewind) menu.
 *
 * <p>★ WHAT IS UNDER TEST ★ Not "does a modal render" — the menu was
 * the desktop's MISSING half of a daemon feature that has existed since
 * R388n, so the questions that matter are:
 *
 * <ol>
 *   <li>does it show the DAEMON's points, in an order the user can use;</li>
 *   <li>does it show the DAEMON's verdict (the kind, the preview) instead
 *       of re-deriving them;</li>
 *   <li>does an action call the RIGHT RPC with the RIGHT scope;</li>
 *   <li>does it tell the user what actually happened.</li>
 * </ol>
 */

const TURN_POINT = {
  id: 'cp-1',
  userMessageIndex: 1,
  transcriptIndex: 7,
  summary: 'refactor the parser',
  kind: 'turn',
  files: [{ path: '/w/Parser.java', name: 'Parser.java', restorable: true, created: false }],
  preview: [{ name: 'Parser.java', linesNow: 40, linesDelta: 12, created: false, known: true, note: null }],
  changedFiles: 1,
  changedLines: 12,
  previewComplete: true,
};

const CANCEL_POINT = {
  id: 'cp-cancel-1',
  userMessageIndex: 2,
  transcriptIndex: 13,
  // ★ THE SUMMARY IS DELIBERATELY MISLEADING ★ it reads like an
  // ordinary turn. If the renderer derived the kind from the prose it
  // would show this as a turn boundary — the whole point of R403's
  // `kind` on the wire is that it must not.
  summary: 'a turn like any other',
  kind: 'cancel',
  files: [{ path: '/w/New.java', name: 'New.java', restorable: true, created: true }],
  preview: [
    { name: 'New.java', linesNow: 0, linesDelta: 0, created: true, known: true, note: null },
    { name: 'blob.bin', linesNow: -1, linesDelta: -1, created: false, known: false, note: 'binary file' },
  ],
  changedFiles: 1,
  changedLines: 0,
  previewComplete: false,
};

function seed() {
  return {
    sessionId: 'sess-1',
    checkpoints: { 'sess-1': [TURN_POINT, CANCEL_POINT] },
    checkpointsEvicted: 3,
  };
}

describe('CheckpointMenu R402', () => {
  it('renders nothing when closed', () => {
    const fixture = createFixture({ seed: seed() });
    const { container } = fixture.render(<CheckpointMenu open={false} onClose={() => {}} />);
    expect(container.querySelector('[data-testid="checkpoint-modal"]')).toBeNull();
  });

  it('lists the daemon\'s points, newest first', async () => {
    const fixture = createFixture({ seed: seed() });
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-modal-list')).toBeTruthy();
    });
    const rows = screen.getByTestId('checkpoint-modal-list').children;
    expect(rows.length).toBe(2);
    // the daemon returns oldest-first; the menu reverses it, because
    // "go back a bit" means the most recent point.
    expect(rows[0].getAttribute('data-testid')).toBe('checkpoint-point-cp-cancel-1');
    expect(rows[1].getAttribute('data-testid')).toBe('checkpoint-point-cp-1');
  });

  it('★ shows the DAEMON\'s kind, not one derived from the summary', async () => {
    const fixture = createFixture({ seed: seed() });
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-kind-cp-cancel-1')).toBeTruthy();
    });
    // the cancel point's summary reads like an ordinary turn — the
    // badge must still say it is the place the run stopped.
    expect(screen.getByTestId('checkpoint-kind-cp-cancel-1').textContent).toContain('停在这里');
    expect(screen.getByTestId('checkpoint-kind-cp-1').textContent).toContain('轮次边界');
  });

  it('★ an unknown preview says "不知道", never zero', async () => {
    const fixture = createFixture({ seed: seed() });
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-point-head-cp-cancel-1')).toBeTruthy();
    });
    act(() => {
      fireEvent.click(screen.getByTestId('checkpoint-point-head-cp-cancel-1'));
    });
    const preview = screen.getByTestId('checkpoint-preview-cp-cancel-1');
    expect(preview.textContent).toContain('不知道');
    expect(preview.textContent).not.toContain('0 行');
    // and the menu says the preview is not complete
    expect(screen.getByTestId('checkpoint-incomplete-cp-cancel-1')).toBeTruthy();
  });

  it('shows the evicted counter — the menu is not the whole history', async () => {
    const fixture = createFixture({ seed: seed() });
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-modal-evicted')).toBeTruthy();
    });
    expect(screen.getByTestId('checkpoint-modal-evicted').textContent).toContain('3');
  });

  it('shows the empty state when the session has no points', async () => {
    const fixture = createFixture({ seed: { sessionId: 'sess-1' } });
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-modal-empty')).toBeTruthy();
    });
  });

  it('★ each action calls the right RPC with the right scope', async () => {
    const fixture = createFixture({ seed: seed() });
    const calls: Array<{ method: string; params: unknown }> = [];
    const orig = fixture.client.call.bind(fixture.client);
    vi.spyOn(fixture.client, 'call').mockImplementation((method: string, params?: unknown) => {
      calls.push({ method, params });
      return orig(method as never, params as never);
    });
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-point-head-cp-1')).toBeTruthy();
    });
    act(() => {
      fireEvent.click(screen.getByTestId('checkpoint-point-head-cp-1'));
    });
    await act(async () => {
      fireEvent.click(screen.getByTestId('checkpoint-action-restore_code-cp-1'));
    });
    await waitFor(() => {
      expect(calls.some((c) => c.method === 'checkpoints/restore')).toBe(true);
    });
    const restore = calls.find((c) => c.method === 'checkpoints/restore');
    expect((restore?.params as { scope?: string }).scope).toBe('code');

    calls.length = 0;
    await act(async () => {
      fireEvent.click(screen.getByTestId('checkpoint-action-restore_conversation-cp-1'));
    });
    await waitFor(() => {
      expect(calls.some((c) => c.method === 'checkpoints/restore')).toBe(true);
    });
    expect((calls.find((c) => c.method === 'checkpoints/restore')?.params as { scope?: string }).scope)
      .toBe('conversation');

    calls.length = 0;
    await act(async () => {
      fireEvent.click(screen.getByTestId('checkpoint-action-restore_code_and_conversation-cp-1'));
    });
    await waitFor(() => {
      expect(calls.some((c) => c.method === 'checkpoints/restore')).toBe(true);
    });
    expect((calls.find((c) => c.method === 'checkpoints/restore')?.params as { scope?: string }).scope)
      .toBe('both');
  });

  it('★ the combined action is the FLAGS of the two simple ones', () => {
    // Not a third implementation: if it were, a fix to "restore code"
    // would not reach it, and the two would drift.
    expect(CHECKPOINT_ACTION_INFO.restore_code_and_conversation.touchesCode).toBe(true);
    expect(CHECKPOINT_ACTION_INFO.restore_code_and_conversation.touchesConversation).toBe(true);
    expect(CHECKPOINT_ACTION_INFO.restore_code.touchesCode).toBe(true);
    expect(CHECKPOINT_ACTION_INFO.restore_code.touchesConversation).toBe(false);
    expect(CHECKPOINT_ACTION_INFO.restore_conversation.touchesCode).toBe(false);
    expect(CHECKPOINT_ACTION_INFO.restore_conversation.touchesConversation).toBe(true);
  });

  it('every action in the list has an entry in the table', () => {
    for (const a of CHECKPOINT_ACTIONS) {
      expect(CHECKPOINT_ACTION_INFO[a]).toBeTruthy();
      expect(CHECKPOINT_ACTION_INFO[a].label.length).toBeGreaterThan(0);
    }
  });

  it('reports what the restore actually did — restored AND deleted', async () => {
    const fixture = createFixture({ seed: seed() });
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-point-head-cp-cancel-1')).toBeTruthy();
    });

    // the TURN point's file existed before the session touched it ⇒
    // restoring it puts the content BACK.
    act(() => {
      fireEvent.click(screen.getByTestId('checkpoint-point-head-cp-1'));
    });
    await act(async () => {
      fireEvent.click(screen.getByTestId('checkpoint-action-restore_code-cp-1'));
    });
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-modal-report')).toBeTruthy();
    });
    let report = screen.getByTestId('checkpoint-modal-report').textContent ?? '';
    expect(report).toContain('已恢复');
    expect(report).toContain('Parser.java');

    // the CANCEL point's file was CREATED by the session ⇒ restoring
    // it DELETES the file. A restore that wrote it back with empty
    // content would leave a zero-byte file where there was none — so
    // the report has to say "deleted", not "restored".
    act(() => {
      fireEvent.click(screen.getByTestId('checkpoint-point-head-cp-cancel-1'));
    });
    await act(async () => {
      fireEvent.click(screen.getByTestId('checkpoint-action-restore_code-cp-cancel-1'));
    });
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-modal-report').textContent).toContain('已删除');
    });
    report = screen.getByTestId('checkpoint-modal-report').textContent ?? '';
    expect(report).toContain('New.java');
  });

  it('ESC closes the modal', async () => {
    const fixture = createFixture({ seed: seed() });
    const onClose = vi.fn();
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={onClose} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-modal-card')).toBeTruthy();
    });
    act(() => {
      fireEvent.keyDown(document, { key: 'Escape' });
    });
    expect(onClose).toHaveBeenCalled();
  });

  it('backdrop click closes the modal', async () => {
    const fixture = createFixture({ seed: seed() });
    const onClose = vi.fn();
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={onClose} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-modal-card')).toBeTruthy();
    });
    act(() => {
      fireEvent.click(screen.getByTestId('checkpoint-modal'));
    });
    expect(onClose).toHaveBeenCalled();
  });

  it('the /rewind command exists and asks for the menu', () => {
    const cmd = SLASH_COMMANDS.find((c) => c.id === 'rewind');
    expect(cmd).toBeTruthy();
    expect(parseSlash('/rewind')).toBeTruthy();
    const seen: string[] = [];
    const handler = () => seen.push('open');
    window.addEventListener('aethercode:open-checkpoint-menu', handler);
    try {
      void cmd!.run('');
    } finally {
      window.removeEventListener('aethercode:open-checkpoint-menu', handler);
    }
    expect(seen).toEqual(['open']);
  });

  // ------------------------------------------------------------------
  // the two SUMMARIZE actions
  // ------------------------------------------------------------------

  it('★ each summarize action sends the mode the daemon understands', async () => {
    const fixture = createFixture({ seed: seed() });
    const calls: Array<{ method: string; params: unknown }> = [];
    const orig = fixture.client.call.bind(fixture.client);
    vi.spyOn(fixture.client, 'call').mockImplementation((method: string, params?: unknown) => {
      calls.push({ method, params });
      return orig(method as never, params as never);
    });
    const onClose = vi.fn();
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={onClose} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-point-head-cp-1')).toBeTruthy();
    });
    act(() => {
      fireEvent.click(screen.getByTestId('checkpoint-point-head-cp-1'));
    });

    // ★ THE MODE IS THE DIFFERENCE ★ 'from' keeps the point's own
    // message, 'up_to' folds it into the summary. The daemon decides
    // which is which -- the menu only has to name them.
    await act(async () => {
      fireEvent.click(screen.getByTestId('checkpoint-action-summarize_from_here-cp-1'));
    });
    await waitFor(() => {
      expect(calls.some((c) => c.method === 'checkpoints/summarize')).toBe(true);
    });
    let params = calls.find((c) => c.method === 'checkpoints/summarize')?.params as
      | { mode?: string; checkpointId?: string }
      | undefined;
    expect(params?.mode).toBe('from');
    expect(params?.checkpointId).toBe('cp-1');
    // the daemon rewrote the transcript -- the menu has nothing left to say
    expect(onClose).toHaveBeenCalled();

    calls.length = 0;
    await act(async () => {
      fireEvent.click(screen.getByTestId('checkpoint-action-summarize_up_to_here-cp-1'));
    });
    await waitFor(() => {
      expect(calls.some((c) => c.method === 'checkpoints/summarize')).toBe(true);
    });
    params = calls.find((c) => c.method === 'checkpoints/summarize')?.params as
      | { mode?: string }
      | undefined;
    expect(params?.mode).toBe('up_to');
  });

  /**
   * ★ A FAILED SUMMARISE MUST NOT LOOK LIKE A SUCCESSFUL ONE ★
   *
   * <p>The menu is where the error is rendered. If it closes on failure,
   * the error message is unmounted before the user can read it -- and the
   * conversation was NOT summarised, so the user believes it was.
   */
  it('★ a failed summarise keeps the menu open and says why', async () => {
    const fixture = createFixture({ seed: seed() });
    const orig = fixture.client.call.bind(fixture.client);
    vi.spyOn(fixture.client, 'call').mockImplementation((method: string, params?: unknown) => {
      if (method === 'checkpoints/summarize') {
        return Promise.resolve({ ok: false, reason: 'the summariser refused' }) as never;
      }
      return orig(method as never, params as never);
    });
    const onClose = vi.fn();
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={onClose} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-point-head-cp-1')).toBeTruthy();
    });
    act(() => {
      fireEvent.click(screen.getByTestId('checkpoint-point-head-cp-1'));
    });
    await act(async () => {
      fireEvent.click(screen.getByTestId('checkpoint-action-summarize_up_to_here-cp-1'));
    });
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-modal-error')).toBeTruthy();
    });
    expect(screen.getByTestId('checkpoint-modal-error').textContent).toContain('the summariser refused');
    expect(onClose).not.toHaveBeenCalled();
  });

  /**
   * ★ THE DAEMON HAS TWO ERROR FIELDS ★
   *
   * <p>Measured on the REAL daemon (R402 live): an unknown point answers
   * `{ok:false, reason:"no such checkpoint: …"}` but an unknown SESSION
   * answers `{ok:false, error:"no such sessionId: …"}`. A client that
   * reads only one of them turns half the refusals into a generic
   * fallback — and the user cannot tell a refused request from a lost
   * session.
   */
  it('★ reads BOTH of the daemon\'s error fields', async () => {
    const fixture = createFixture({ seed: seed() });
    const orig = fixture.client.call.bind(fixture.client);
    vi.spyOn(fixture.client, 'call').mockImplementation((method: string, params?: unknown) => {
      if (method === 'checkpoints/restore') {
        return Promise.resolve({ ok: false, error: 'no such sessionId: gone' }) as never;
      }
      return orig(method as never, params as never);
    });
    const onClose = vi.fn();
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={onClose} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-point-head-cp-1')).toBeTruthy();
    });
    act(() => {
      fireEvent.click(screen.getByTestId('checkpoint-point-head-cp-1'));
    });
    await act(async () => {
      fireEvent.click(screen.getByTestId('checkpoint-action-restore_code-cp-1'));
    });
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-modal-error')).toBeTruthy();
    });
    expect(screen.getByTestId('checkpoint-modal-error').textContent).toContain('no such sessionId: gone');
  });
});
