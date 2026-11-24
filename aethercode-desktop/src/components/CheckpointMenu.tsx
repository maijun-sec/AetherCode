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
import { rpc, type CheckpointPoint } from '../lib/methods';
import { useRpc } from '../rpc/queries';
import { JsonRpcClient } from '../rpc/client';
import './CheckpointMenu.css';

/**
 * R402: the desktop's CHECKPOINT (rewind) menu.
 *
 * <p>★ WHAT THIS IS FOR ★ The daemon grew the whole checkpoint surface
 * in R388n–R403: the points, their file snapshots, the per-file preview,
 * the cancel points, and the on-disk persistence. The TUI has had a menu
 * for it since R388n. The DESKTOP had NOTHING — `checkpoints/` and
 * `rewind` appeared zero times in this package — so the feature was
 * unreachable for the people who use the desktop.
 *
 * <p>★ THE RENDERER DISPLAYS; IT DOES NOT DECIDE ★
 * <ul>
 *   <li>`kind` ('turn' / 'cancel') is the DAEMON's verdict — the renderer
 *       never re-derives it from the summary text (reading prose as a
 *       contract is R378's failure mode);</li>
 *   <li>the preview ("what would this change?") is computed by the
 *       daemon, because the daemon is the only holder of the snapshot;</li>
 *   <li>the evicted counter is the daemon's — a menu that silently
 *       starts at "point 40" looks like the daemon lost the history,
 *       so the count is shown.</li>
 * </ul>
 *
 * <p>★ "I DO NOT KNOW" IS NOT ZERO ★ a preview entry with
 * `known: false` (a binary file, an unreadable path) renders as
 * "不知道", never as "0 行" — a number the user could act on, and it
 * would be wrong.
 */

/**
 * The operations the menu offers.
 *
 * <p>★ ONE list ★ The component renders it, the dispatcher reads it,
 * and the gate reads it. A second copy of "what the menu offers" is
 * the shape where one "list" is really several lists and every one of
 * them drifts.
 *
 * <p>The ids match the TUI's menu (`aethercode-tui/src/components/
 * CheckpointMenu.tsx`) so the two renderers speak the same vocabulary.
 * The two packages cannot share code — they are separate builds — so
 * this IS a second copy of the vocabulary, and the DAEMON's RPC is the
 * contract that keeps them honest.
 */
export const CHECKPOINT_ACTIONS = [
  'restore_code_and_conversation',
  'restore_conversation',
  'restore_code',
  'summarize_from_here',
  'summarize_up_to_here',
] as const;

export type CheckpointAction = (typeof CHECKPOINT_ACTIONS)[number];

/** What each action does, and what the menu says. */
export interface CheckpointActionInfo {
  label: string;
  blurb: string;
  /** Does it change the FILES? */
  touchesCode: boolean;
  /** Does it change the CONVERSATION? */
  touchesConversation: boolean;
}

/**
 * ★ THE `code` / `conversation` FLAGS ARE THE CONTRACT ★
 *
 * "Restore code and conversation" is not a third implementation — it
 * is "restore code" AND "restore conversation". If the menu had a
 * separate handler for it, the two would drift: a fix to the code
 * restore would not reach the combined one.
 */
export const CHECKPOINT_ACTION_INFO: Readonly<Record<CheckpointAction, CheckpointActionInfo>> = {
  restore_code_and_conversation: {
    label: '恢复代码和对话',
    blurb: '把文件和对话一起退回到这个点',
    touchesCode: true,
    touchesConversation: true,
  },
  restore_conversation: {
    label: '恢复对话',
    blurb: '只把对话退回到这个点（文件不动）',
    touchesCode: false,
    touchesConversation: true,
  },
  restore_code: {
    label: '恢复代码',
    blurb: '只把文件退回到这个点（对话不动）',
    touchesCode: true,
    touchesConversation: false,
  },
  summarize_from_here: {
    label: '从这里总结',
    // ★ WHAT ACTUALLY DIFFERS BETWEEN THE TWO SUMMARIZE ACTIONS ★
    //
    // Both summarise the HEAD (everything before the point) and keep
    // the TAIL (everything after it). The daemon's boundary is the
    // point's own message: `from` splits BEFORE it and `up_to` splits
    // AFTER it, so `up_to` folds the point's own message into the
    // summary and `from` keeps it.
    //
    // ★ THIS IS THE THIRD COPY OF THAT RULE ★ the daemon's javadoc
    // used to describe the opposite ("up_to keeps the head") — it was
    // wrong, and the TUI's comment said the right thing. A rule that
    // lives in three places lives in three states; the daemon's test
    // (AetherCodeMethodsSummarizeModeR402Test) is what keeps them
    // honest.
    blurb: '把这个点之前的对话总结掉，这个点和后面的保留',
    touchesCode: false,
    touchesConversation: true,
  },
  summarize_up_to_here: {
    label: '总结到这里',
    blurb: '把这个点（含）为止的对话总结掉，后面的保留',
    touchesCode: false,
    touchesConversation: true,
  },
};

/** The RPC calls the menu needs, resolved per environment. */
interface CheckpointRpc {
  checkpointsList: typeof rpc.checkpointsList;
  checkpointsRestore: typeof rpc.checkpointsRestore;
  checkpointsSummarize: typeof rpc.checkpointsSummarize;
}

/**
 * Resolve the client the menu talks to. Tests inject a
 * mock-instrumented client via {@link JsonRpcClientContext}; production
 * falls through to the {@link rpc} singleton (Tauri invoke). This is
 * the same dual-path shim the R284 SnapshotModal uses.
 */
function useCheckpointRpc(): CheckpointRpc {
  const client: JsonRpcClient | undefined = useRpc();
  if (client) {
    return {
      checkpointsList: (opts) =>
        client.call('checkpoints/list', { sessionId: opts?.sessionId ?? null }) as ReturnType<
          typeof rpc.checkpointsList
        >,
      checkpointsRestore: (opts) =>
        client.call('checkpoints/restore', {
          sessionId: opts?.sessionId ?? null,
          id: opts.id,
          scope: opts.scope,
        }) as ReturnType<typeof rpc.checkpointsRestore>,
      checkpointsSummarize: (opts) =>
        client.call('checkpoints/summarize', {
          sessionId: opts?.sessionId ?? null,
          checkpointId: opts.checkpointId,
          mode: opts.mode,
        }) as ReturnType<typeof rpc.checkpointsSummarize>,
    };
  }
  return {
    checkpointsList: rpc.checkpointsList,
    checkpointsRestore: rpc.checkpointsRestore,
    checkpointsSummarize: rpc.checkpointsSummarize,
  };
}

/** What a restore actually did — the daemon's own report. */
interface RestoreReport {
  restored: string[];
  deleted: string[];
  skipped: string[];
  failed: string[];
}

export interface CheckpointMenuProps {
  /** The modal renders only when open. It DEFAULTS to true because the
   *  App mounts this modal conditionally (`{p.showCheckpoints && …}`),
   *  like every other Shell-level modal — the other half of that
   *  convention is that the component still has to be able to render
   *  nothing, so `open={false}` is a legal state and a test asserts it. */
  open?: boolean;
  /** the session whose points are listed. Omitted = the daemon's active session. */
  sessionId?: string;
  onClose: () => void;
}

export function CheckpointMenu(props: CheckpointMenuProps) {
  const { open = true, sessionId, onClose } = props;
  const { checkpointsList, checkpointsRestore, checkpointsSummarize } = useCheckpointRpc();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [points, setPoints] = useState<CheckpointPoint[] | null>(null);
  const [evicted, setEvicted] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [report, setReport] = useState<RestoreReport | null>(null);

  // ★ ONE fetch path ★ the open effect and the post-restore refresh
  // call the same function — two copies of "read the points" would
  // drift (and one of them would forget the evicted counter).
  const fetchPoints = async (isStale: () => boolean) => {
    const resp = await checkpointsList({ sessionId });
    if (isStale()) return;
    if (!resp?.ok) {
      setError(resp?.error ?? 'checkpoints/list failed');
      setPoints([]);
      return;
    }
    setPoints(resp.checkpoints ?? []);
    setEvicted(resp.evicted ?? 0);
  };

  // fetch when the modal opens. Re-issued on every open so the user
  // sees the points the daemon has NOW — including the ones a cancel
  // just created.
  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    const isStale = () => cancelled;
    setLoading(true);
    setError(null);
    setReport(null);
    setSelected(null);
    fetchPoints(isStale)
      .catch((e) => {
        if (isStale()) return;
        setError(e?.message ?? 'failed to load checkpoints');
        setPoints([]);
      })
      .finally(() => {
        if (!isStale()) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, sessionId]);

  // ESC to close.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  const run = async (point: CheckpointPoint, action: CheckpointAction) => {
    const info = CHECKPOINT_ACTION_INFO[action];
    setBusy(true);
    setError(null);
    setReport(null);
    try {
      if (action === 'summarize_from_here' || action === 'summarize_up_to_here') {
        const mode = action === 'summarize_from_here' ? 'from' : 'up_to';
        const resp = await checkpointsSummarize({ sessionId, checkpointId: point.id, mode });
        if (!resp?.ok) {
          // ★ STAY OPEN, AND READ BOTH ERROR FIELDS ★ the menu is where
          // the error is rendered; a close here would unmount the
          // message before the user could read it, and a failed
          // summarise that looks like a successful one is the worst
          // possible answer. `reason` = the operation was refused,
          // `error` = the session could not be resolved.
          setError(resp?.reason ?? resp?.error ?? 'summarize failed');
          return;
        }
        // The daemon rewrote the transcript; the menu has nothing left
        // to say about it.
        onClose();
        return;
      }
      const scope = info.touchesCode && info.touchesConversation
        ? 'both'
        : info.touchesCode ? 'code' : 'conversation';
      const resp = await checkpointsRestore({ sessionId, id: point.id, scope });
      if (!resp?.ok) {
        // ★ THE DAEMON HAS TWO ERROR FIELDS ★ `reason` when the operation
        // was refused, `error` when the session could not be resolved.
        // Reading only one of them turns half the refusals into a
        // generic message — and the user cannot tell a refused request
        // from a lost session.
        setError(resp?.reason ?? resp?.error ?? 'restore failed');
        return;
      }
      setReport({
        restored: resp.restored ?? [],
        deleted: resp.deleted ?? [],
        skipped: resp.skipped ?? [],
        failed: resp.failed ?? [],
      });
      // the conversation may have moved — re-read the list so the menu
      // does not offer a point that is gone.
      await fetchPoints(() => false);
    } catch (e) {
      setError((e as Error)?.message ?? 'the operation failed');
    } finally {
      setBusy(false);
    }
  };

  if (!open) return null;

  // ★ NEWEST FIRST ★ the daemon returns them oldest-first (its own
  // insertion order); the menu's job is "go back a bit", and the most
  // recent point is the one the user usually wants. The row keeps the
  // daemon's ordinal so the two can be correlated.
  const rows = points ? [...points].reverse() : [];

  return (
    <div
      className="checkpoint-modal-backdrop"
      role="dialog"
      aria-modal="true"
      aria-label="回退到之前的点"
      data-testid="checkpoint-modal"
      onClick={onClose}
    >
      <div
        className="checkpoint-modal-card"
        onClick={(e) => e.stopPropagation()}
        data-testid="checkpoint-modal-card"
      >
        <header className="checkpoint-modal-header">
          <div className="checkpoint-modal-title-row">
            <span className="checkpoint-modal-icon" aria-hidden>↺</span>
            <h2 className="checkpoint-modal-title">回退到之前的点</h2>
          </div>
          <button
            type="button"
            className="checkpoint-modal-close"
            onClick={onClose}
            aria-label="Close"
            data-testid="checkpoint-modal-close"
          >
            ×
          </button>
        </header>

        {evicted > 0 && (
          <div className="checkpoint-modal-meta" data-testid="checkpoint-modal-evicted">
            最早的 {evicted} 个点已经被丢弃（上限 64 个），无法再回到那里。
          </div>
        )}

        <section className="checkpoint-modal-body">
          {loading && (
            <div className="checkpoint-modal-status" data-testid="checkpoint-modal-loading">
              loading…
            </div>
          )}
          {error && (
            <div className="checkpoint-modal-status checkpoint-modal-error" data-testid="checkpoint-modal-error">
              {error}
            </div>
          )}
          {!loading && !error && rows.length === 0 && (
            <div className="checkpoint-modal-status" data-testid="checkpoint-modal-empty">
              这个会话还没有可回退的点。
            </div>
          )}
          {!loading && rows.length > 0 && (
            <ul className="checkpoint-modal-list" data-testid="checkpoint-modal-list">
              {rows.map((p) => (
                <li
                  key={p.id}
                  className={`checkpoint-modal-point${selected === p.id ? ' selected' : ''}`}
                  data-testid={`checkpoint-point-${p.id}`}
                >
                  <button
                    type="button"
                    className="checkpoint-modal-point-head"
                    onClick={() => setSelected(selected === p.id ? null : p.id)}
                    data-testid={`checkpoint-point-head-${p.id}`}
                  >
                    {/* ★ THE KIND IS THE DAEMON'S VERDICT ★ */}
                    <span
                      className={`checkpoint-modal-kind checkpoint-modal-kind-${p.kind}`}
                      data-testid={`checkpoint-kind-${p.id}`}
                    >
                      {p.kind === 'cancel' ? '停在这里' : '轮次边界'}
                    </span>
                    <span className="checkpoint-modal-ordinal">第 {p.userMessageIndex + 1} 轮</span>
                    <span className="checkpoint-modal-summary">{p.summary || '(无摘要)'}</span>
                    <span className="checkpoint-modal-files">
                      {p.files.length > 0 ? `${p.files.length} 个文件` : '无文件改动'}
                    </span>
                  </button>

                  {selected === p.id && (
                    <div className="checkpoint-modal-detail" data-testid={`checkpoint-detail-${p.id}`}>
                      {/* ★ THE PREVIEW IS THE DAEMON'S ANSWER ★ and an
                          unknown entry says so — it is not zero. */}
                      {p.preview.length > 0 && (
                        <ul className="checkpoint-modal-preview" data-testid={`checkpoint-preview-${p.id}`}>
                          {p.preview.map((f) => (
                            <li key={f.name}>
                              <span className="checkpoint-modal-preview-name">{f.name}</span>
                              <span className="checkpoint-modal-preview-delta">
                                {!f.known
                                  ? `不知道${f.note ? `（${f.note}）` : ''}`
                                  : f.created
                                    ? '将删除'
                                    : f.linesDelta > 0
                                      ? `将改动 ${f.linesDelta} 行`
                                      : '无改动'}
                              </span>
                            </li>
                          ))}
                        </ul>
                      )}
                      {!p.previewComplete && (
                        <p className="checkpoint-modal-incomplete" data-testid={`checkpoint-incomplete-${p.id}`}>
                          有些文件算不出差异（不可读或二进制），所以这不是一份完整的预览。
                        </p>
                      )}
                      <div className="checkpoint-modal-actions">
                        {CHECKPOINT_ACTIONS.map((a) => (
                          <button
                            key={a}
                            type="button"
                            className="checkpoint-modal-action"
                            title={CHECKPOINT_ACTION_INFO[a].blurb}
                            disabled={busy}
                            onClick={() => void run(p, a)}
                            data-testid={`checkpoint-action-${a}-${p.id}`}
                          >
                            {CHECKPOINT_ACTION_INFO[a].label}
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
          {report && (
            <div className="checkpoint-modal-report" data-testid="checkpoint-modal-report">
              {report.restored.length > 0 && <div>已恢复：{report.restored.join('、')}</div>}
              {report.deleted.length > 0 && <div>已删除：{report.deleted.join('、')}</div>}
              {report.skipped.length > 0 && <div>已跳过：{report.skipped.join('、')}</div>}
              {report.failed.length > 0 && (
                <div className="checkpoint-modal-report-failed">失败：{report.failed.join('、')}</div>
              )}
              {report.restored.length === 0 && report.deleted.length === 0
                && report.skipped.length === 0 && report.failed.length === 0 && (
                <div>这个点没有可恢复的文件改动。</div>
              )}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
