/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { createFixture } from '../../test/testUtils';
import { CheckpointMenu } from '../CheckpointMenu';

/**
 * R406: the rewind menu says WHAT ITS FILE LIST COVERS.
 *
 * <p>★ WHY THE MENU HAS TO SAY IT ★ the file list is what a rewind can
 * undo. It is derived from the tool calls that NAME a file — so a file
 * the session wrote through the shell (`echo x > f`) is not in it, and
 * the rewind leaves that file modified.
 *
 * <p>A menu that lists files without saying so implies "these are all
 * the files that changed". The user rewinds, finds the shell-written
 * file still modified, and concludes the feature is broken — when it is
 * doing exactly what it says.
 *
 * <p>★ THE FACTS ARE THE DAEMON'S ★ `coveredTools` and `shellCovered`
 * come off the wire. The renderer only LOCALISES the sentence — it never
 * keeps its own list of "the tools we cover" (a second list drifts).
 */

const POINT = {
  id: 'cp-1',
  userMessageIndex: 1,
  transcriptIndex: 7,
  summary: 'refactor the parser',
  kind: 'turn',
  files: [{ path: '/w/Parser.java', name: 'Parser.java', restorable: true, created: false }],
  preview: [
    { name: 'Parser.java', linesNow: 40, linesDelta: 12, created: false, known: true, note: null },
  ],
  changedFiles: 1,
  changedLines: 12,
  previewComplete: true,
};

function seed(extra: Record<string, unknown> = {}) {
  return {
    sessionId: 'sess-1',
    checkpoints: { 'sess-1': [POINT] },
    checkpointsEvicted: 0,
    ...extra,
  };
}

describe('CheckpointMenu R406 — the file list\'s coverage', () => {
  it('★ states that the list only covers the write tools', async () => {
    const fixture = createFixture({ seed: seed() });
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-modal-list')).toBeTruthy();
    });
    const line = screen.getByTestId('checkpoint-modal-coverage');
    // the covered tools are the DAEMON's, named one by one
    expect(line.textContent).toContain('file_write');
    expect(line.textContent).toContain('file_edit');
    expect(line.textContent).toContain('notebook_edit');
    // and the gap is stated, not implied
    expect(line.textContent).toContain('shell');
    expect(line.textContent).toContain('只覆盖');
  });

  it('★ never claims the shell is covered', async () => {
    const fixture = createFixture({ seed: seed() });
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-modal-coverage')).toBeTruthy();
    });
    // the shell is NOT in the covered list — that is the daemon's fact
    // and the renderer must not dress it up as covered
    const line = screen.getByTestId('checkpoint-modal-coverage').textContent ?? '';
    expect(line).not.toContain('file_write、bash');
    expect(line).not.toContain('bash、');
  });

  it('★ an OLDER daemon (no coverage) does not crash the menu', async () => {
    // a daemon older than R406 does not send the field at all
    const fixture = createFixture({ seed: seed({ checkpointsFilesCoverage: null }) });
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-modal-list')).toBeTruthy();
    });
    // the menu still works...
    expect(screen.getByTestId('checkpoint-point-cp-1')).toBeTruthy();
    // ...and it does NOT INVENT a coverage the daemon never claimed
    expect(screen.queryByTestId('checkpoint-modal-coverage')).toBeNull();
  });

  it('★ the coverage comes off the wire, not from the renderer', async () => {
    // a daemon that DOES cover the shell must be reported as such — the
    // renderer must not have a hard-coded sentence that contradicts it
    const fixture = createFixture({
      seed: seed({
        checkpointsFilesCoverage: {
          coveredTools: ['bash', 'file_edit', 'file_write'],
          shellCovered: true,
          note: 'the file list covers every write channel the session used',
        },
      }),
    });
    fixture.render(<CheckpointMenu open sessionId="sess-1" onClose={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('checkpoint-modal-coverage')).toBeTruthy();
    });
    const line = screen.getByTestId('checkpoint-modal-coverage').textContent ?? '';
    expect(line).toContain('bash');
    // the "the shell is not covered" wording must be GONE
    expect(line).not.toContain('只覆盖');
  });
});
