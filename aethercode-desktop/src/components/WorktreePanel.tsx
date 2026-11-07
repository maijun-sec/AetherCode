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

import { useEffect, useState } from 'react';
import { useStore } from '../store';
import './WorktreePanel.css';

/**
 * R388c: the worktree panel.
 *
 * <p>A worktree is an isolated git checkout on its own
 * branch. Creating one is how the user gets a place to work
 * without disturbing the other sessions on the same repo.
 *
 * <p>★ THE VERDICT COMES FROM THE DAEMON ★
 * Each row shows the daemon's {@code isolated} flag. A
 * worktree with {@code isolated: false} is a plain directory
 * that only LOOKS like isolation — the daemon could not
 * create a real git worktree, and the row shows the reason.
 * The renderer must NOT re-derive isolation from the absence
 * of a branch (R383's lesson: the verdict comes from the
 * daemon, not from the client's own parsing).
 *
 * <p>★ WHY THE PANEL SAYS SO LOUDLY WHEN ISOLATION IS
 * UNAVAILABLE ★
 * "The worktree feature did nothing" is worse than "the
 * worktree feature is not configured". A user who asked for
 * isolation and got a plain directory must be told, with the
 * reason and the fix.
 */
export function WorktreePanel() {
  const {
    worktrees,
    worktreeStatus,
    selectedWorktree,
    setSelectedWorktree,
    refreshWorktrees,
    createWorktree,
    deleteWorktree,
    mergeWorktree,
  } = useStore();

  const [newName, setNewName] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ kind: 'ok' | 'warn' | 'err'; text: string } | null>(null);
  const [targetBranch, setTargetBranch] = useState('');

  useEffect(() => { void refreshWorktrees(); }, [refreshWorktrees]);

  const onCreate = async () => {
    const name = newName.trim();
    if (!name) return;
    setBusy(name);
    setNotice(null);
    const r = await createWorktree(name);
    setBusy(null);
    if (!r.ok) {
      setNotice({ kind: 'err', text: r.error ?? 'worktree create failed' });
      return;
    }
    // R388c: a stub worktree comes back ok:true AND
    // isolated:false. The user asked for isolation and did
    // not get it — say so, with the reason.
    if (r.isolated === false) {
      setNotice({
        kind: 'warn',
        text: `"${name}" was created as a plain directory, NOT git isolation. `
          + (r.reason ?? 'the daemon could not create a git worktree.'),
      });
    } else {
      setNotice({ kind: 'ok', text: `worktree "${name}" created` });
    }
    setNewName('');
  };

  const onMerge = async (name: string) => {
    setBusy(name);
    setNotice(null);
    const r = await mergeWorktree(name, targetBranch.trim() || null);
    setBusy(null);
    if (!r.ok) {
      // The daemon's reason is the useful part — it says
      // WHY (e.g. "has uncommitted changes").
      setNotice({ kind: 'err', text: r.message ?? 'merge failed' });
      return;
    }
    setNotice({ kind: 'ok', text: r.message ?? `merged "${name}"` });
  };

  const onDelete = async (name: string) => {
    setBusy(name);
    setNotice(null);
    const r = await deleteWorktree(name, true);
    setBusy(null);
    if (!r.ok) {
      setNotice({ kind: 'err', text: r.error ?? 'worktree remove failed' });
      return;
    }
    setNotice({ kind: 'ok', text: `worktree "${name}" removed` });
  };

  const isolationUnavailable = worktreeStatus !== null && !worktreeStatus.available;

  return (
    <div className="worktree-panel" data-testid="worktree-panel">
      <div className="worktree-panel-header">
        <span>Worktrees ({worktrees.length})</span>
        <button
          type="button"
          className="worktree-panel-refresh"
          onClick={() => void refreshWorktrees()}
          title="Refresh worktrees"
          data-testid="worktree-panel-refresh"
        >
          ⟳
        </button>
      </div>

      {isolationUnavailable && (
        <div
          className="worktree-panel-warning"
          data-testid="worktree-panel-warning"
        >
          <strong>Git isolation unavailable.</strong>{' '}
          {worktreeStatus?.reason ?? 'The daemon is not configured for worktrees.'}
          {' '}Start the daemon with <code>--worktree-source-repo &lt;path&gt;</code>,
          or set <code>AETHERCODE_WORKTREE_SOURCE_REPO</code>.
        </div>
      )}

      {notice && (
        <div
          className={`worktree-panel-notice worktree-panel-notice-${notice.kind}`}
          data-testid="worktree-panel-notice"
        >
          {notice.text}
        </div>
      )}

      <div className="worktree-panel-create">
        <input
          className="worktree-panel-input"
          placeholder="worktree name"
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') void onCreate(); }}
          data-testid="worktree-panel-name-input"
        />
        <button
          type="button"
          className="worktree-panel-create-btn"
          onClick={() => void onCreate()}
          disabled={!newName.trim() || busy !== null}
          data-testid="worktree-panel-create"
        >
          Create
        </button>
      </div>

      <div className="worktree-panel-merge-target">
        <input
          className="worktree-panel-input"
          placeholder="merge target branch (default: current)"
          value={targetBranch}
          onChange={(e) => setTargetBranch(e.target.value)}
          data-testid="worktree-panel-target-input"
        />
      </div>

      {worktrees.length === 0 ? (
        <div className="worktree-panel-empty">
          No worktrees yet. Create one to work on an isolated branch.
        </div>
      ) : (
        <ul className="worktree-panel-list">
          {worktrees.map((w) => (
            <li
              key={`${w.name}:${w.path}`}
              className={`worktree-panel-row${selectedWorktree === w.name ? ' worktree-panel-row-selected' : ''}`}
              data-testid="worktree-panel-row"
            >
              <div className="worktree-panel-row-top">
                <span
                  className="worktree-panel-row-name"
                  onClick={() => setSelectedWorktree(selectedWorktree === w.name ? null : w.name)}
                  title={w.path}
                >
                  {w.name}
                </span>
                {/* R388c: the verdict badge. The daemon
                    decides — the renderer only displays it. */}
                <span
                  className={`worktree-panel-badge worktree-panel-badge-${w.isolated ? 'ok' : 'warn'}`}
                  data-testid="worktree-panel-badge"
                >
                  {w.isolated ? 'git' : 'plain dir'}
                </span>
              </div>
              <div className="worktree-panel-row-branch">
                {w.branch ?? '(no branch)'}
              </div>
              {!w.isolated && w.reason && (
                <div
                  className="worktree-panel-row-reason"
                  data-testid="worktree-panel-row-reason"
                >
                  {w.reason}
                </div>
              )}
              <div className="worktree-panel-row-actions">
                <button
                  type="button"
                  onClick={() => void onMerge(w.name)}
                  disabled={!w.isolated || busy !== null}
                  title="Merge this branch back"
                  data-testid="worktree-panel-merge"
                >
                  Merge
                </button>
                <button
                  type="button"
                  onClick={() => void onDelete(w.name)}
                  disabled={busy !== null}
                  title="Remove the worktree and its branch"
                  data-testid="worktree-panel-delete"
                >
                  Remove
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {selectedWorktree && (
        <div className="worktree-panel-selected" data-testid="worktree-panel-selected">
          Next new session binds to <strong>{selectedWorktree}</strong>.
        </div>
      )}
    </div>
  );
}
