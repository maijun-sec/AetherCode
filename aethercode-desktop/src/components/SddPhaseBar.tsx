import { useEffect, useState } from 'react';
import { useStore } from '../store';
import './SddPhaseBar.css';

/**
 * R288 + R292 + R293 — SDD phase progress bar.
 *
 * Lives between the chat list and MessageInput. Renders the
 * eight Spec Kit phases (项目原则 / 需求分析 / 需求澄清 /
 * 详细设计 / 一致性分析 / 任务分析 / 执行实现 / 收敛验证)
 * as a compact 8-chip strip showing each phase's state
 * (idle / running / pending-accept / done / skipped / failed).
 * Optional quality gates (clarify / analyze) and the optional
 * converge loop render half-opacity so the user can tell
 * which phases are required vs opt-in.
 *
 * R293 (interactive SDD): when the daemon reaches a phase
 * boundary it parks at {@code pending-accept} (or
 * {@code clarify-pending} / {@code converge-pending}) and
 * waits for the user to acknowledge. The previous R292 build
 * had no accept/revise buttons — only the close button —
 * so chips flashed by in 4 s and the user had no chance to
 * interact. R293 fixes that by rendering an action bar
 * below the chip strip with:
 *
 *   - **pending-accept**  →  ✅ 接受 / ✏️ 修改 / ⏭️ 跳过
 *   - **clarify-pending** →  输入框 + 📤 发送回答
 *   - **converge-pending** →  ✅ 结束 (accept not-converged) /
 *                             🔁 再迭代一次 (text feedback)
 *
 * The action bar collapses to the close button when no
 * phase is waiting on the user, so the strip stays compact
 * during normal idle / running ticks.
 *
 * R292 layout note: the prior build rendered the full
 * draft preview (up to 4 KB) inside every chip, which made
 * the bar consume the whole viewport when a draft landed.
 * R293 moves the preview to the existing chat-stream system
 * message (the {@code phase-draft} handler in the store
 * already pushes it as a `system` role message) and keeps
 * the chip strip to a single line: marker dot + 中文
 * title + state glyph. The chip-to-full-preview path is
 * gone; the bar is a status indicator, not a viewer.
 */
const STATE_GLYPH: Record<string, string> = {
  'idle':             '○',
  'pending':          '○',
  'running':          '◐',
  'pending-accept':   '⏸',
  'need-content':     '📝',
  'clarify-pending':  '❓',
  'converge-pending': '🔁',
  'done':             '✓',
  'skipped':          '↷',
  'failed':           '✕',
};

const STATE_LABEL: Record<string, string> = {
  'idle':             '待开始',
  'pending':          '待开始',
  'running':          '进行中',
  'pending-accept':   '待确认',
  'need-content':     '待内容',
  'clarify-pending':  '待澄清',
  'converge-pending': '收敛中',
  'done':             '已完成',
  'skipped':          '已跳过',
  'failed':           '失败',
};

// Canonical phase order — Spec Kit 6 phases + 2 optional
// gates, matching the daemon's `PhaseId` order. The
// `phase-list` event from the driver uses these ids and
// titles, so even if the store gets out of sync with the
// driver (e.g. mid-tear-down) we still render all of them.
const PHASE_ORDER = [
  'constitution',
  'specify',
  'clarify',
  'plan',
  'analyze',
  'tasks',
  'implement',
  'converge',
] as const;

// Phase ids that are optional quality gates (render half-opacity).
const OPTIONAL_PHASES = new Set<string>(['clarify', 'analyze', 'converge']);

export function SddPhaseBar() {
  const sddEnabled = useStore((s) => s.sddEnabled);
  const ssdPhases  = useStore((s) => s.ssdPhases);
  const ssdActive  = useStore((s) => s.ssdActive);
  const ssdSlug    = useStore((s) => s.ssdSlug);
  const setSddEnabled = useStore((s) => s.setSddEnabled);
  const sendSsdCommand = useStore((s) => s.sendSsdCommand);

  // Locate the phase currently waiting on the user. There
  // can only be one — the daemon parks serially, not in
  // parallel — so finding the first one is enough.
  const waitingPhase = ssdPhases.find((p) =>
    p.state === 'pending-accept' ||
    p.state === 'need-content' ||
    p.state === 'clarify-pending' ||
    p.state === 'converge-pending',
  );

  // Local state for the revise / clarify / converge-iterate
  // text inputs. Cleared whenever the phase that owned them
  // advances, so the next route's input is empty.
  const [reviseText, setReviseText] = useState('');
  const [clarifyAnswer, setClarifyAnswer] = useState('');
  const [convergeText, setConvergeText] = useState('');
  // R309: need-content input — the rendered markdown body
  // for the current phase. The user pastes LLM-generated
  // content here; the daemon writes it to disk and emits
  // phase-draft so the user can still accept/revise.
  const [needContentText, setNeedContentText] = useState('');
  useEffect(() => {
    setReviseText('');
    setClarifyAnswer('');
    setConvergeText('');
    setNeedContentText('');
  }, [waitingPhase?.id, waitingPhase?.state]);

  if (!sddEnabled) return null;

  // Index the phases by id for rendering. If the store's
  // ssdPhases is empty (toggle just flipped on, no run started
  // yet) we render an idle template so the user still sees the
  // 8 phases.
  const byId = new Map<string, typeof ssdPhases[number]>();
  for (const p of ssdPhases) byId.set(p.id, p);

  // R306: progress summary in the header. Counts done /
// skipped phases; the rest are still in flight (idle /
// running / pending-accept / clarify-pending /
// converge-pending) or failed. Render "X/8 完成" so the
// user can see overall progress without reading every
// chip.
const completedCount = ssdPhases.filter(
  (p) => p.state === 'done' || p.state === 'skipped',
).length;
const totalCount = ssdPhases.length || 8;
const runningPhase = ssdPhases.find((p) => p.state === 'running');
const runningFor = runningPhase?.startedAt
  ? Math.max(0, Math.round((Date.now() - runningPhase.startedAt) / 1000))
  : 0;

return (
    <section className="sdd-phase-bar" aria-label="SDD 阶段进度" data-testid="sdd-phase-bar">
      <header className="sdd-phase-bar-header">
        <div className="sdd-phase-bar-title">
          <h3>SDD</h3>
          <span className="sdd-phase-bar-subtitle">
            {ssdActive && ssdSlug
              ? `8 阶段（${ssdSlug}）`
              : '8 阶段：原则 → 需求 → 澄清 → 设计 → 一致性 → 任务 → 实现 → 收敛'}
            {ssdActive && ssdPhases.length > 0 && (
              <>
                {' · '}
                <strong className="sdd-phase-bar-progress" data-testid="sdd-phase-bar-progress">
                  {completedCount}/{totalCount} 完成
                </strong>
                {runningPhase && (
                  <span className="sdd-phase-bar-running" data-testid="sdd-phase-bar-running">
                    {' · '}{runningPhase.title} {runningFor}s
                  </span>
                )}
              </>
            )}
            {' · R298: '}
            <code className="sdd-phase-bar-debug">
              {(() => {
                // Surface whether we're driving a real
                // subprocess or the in-process mock. Lets
                // the user distinguish "real daemon ran
                // and the LLM just answered fast" from
                // "stub fell back because no jar was
                // available". Both end up looking like
                // "chips flipped" otherwise.
                const info = useStore.getState().daemonInfo;
                const jarPath = info?.jarPath ?? '';
                const cwd = info?.cwd ?? '';
                if (jarPath) {
                  // TauriSsdDriver branch — also show the
                  // truncated jar path so the user can
                  // verify the desktop resolved the same
                  // release/R292/desktop/aethercode.jar
                  // the standalone pipeline test used.
                  const tail = jarPath.split(/[\\/]/).pop() || jarPath;
                  return `TauriSsdDriver (${tail}, cwd=${cwd.split(/[\\/]/).pop() || cwd})`;
                }
                // R298: bare "no jar" was leaving the
                // user in the dark. Show whatever the
                // store actually has so we can see if
                // jarPath is empty because the desktop
                // picked up an external daemon (jarPath =
                // "<external>") or because the desktop
                // never attached one (jarPath = "").
                return `MockSsdDriver (jarPath="${jarPath}", cwd="${cwd}")`;
              })()}
            </code>
          </span>
        </div>
        <button
          type="button"
          className="sdd-phase-bar-close"
          onClick={() => {
            if (ssdActive) {
              // Mid-run close sends quit so the driver tears
              // down cleanly. After quit fires the event
              // handler will set sddEnabled=false via the store
              // action.
              sendSsdCommand({ action: 'quit' });
            } else {
              setSddEnabled(false);
            }
          }}
          title={ssdActive ? '取消当前 SDD 流程' : '退出 SDD 面板'}
          data-testid="sdd-phase-bar-close"
        >
          {ssdActive ? '取消' : '✕'}
        </button>
      </header>

      <ol className="sdd-phase-list">
        {PHASE_ORDER.map((id, i) => {
          const phase = byId.get(id);
          const state = phase?.state ?? 'idle';
          const title = phase?.title ?? phaseTitleZh(id);
          const optional = phase?.optional ?? OPTIONAL_PHASES.has(id);
          const isWaiting = waitingPhase?.id === id;
          const isRunning = state === 'running';
          // R306: per-phase duration for finished phases.
          // "constitution: ✓ 12s" lets the user see which
          // phase took how long at a glance.
          const durSec = (phase?.startedAt && phase?.endedAt)
            ? Math.max(0, Math.round((phase.endedAt - phase.startedAt) / 1000))
            : null;
          return (
            <li
              key={id}
              className={`sdd-phase-item sdd-phase-${state}${optional ? ' sdd-phase-optional' : ''}${isWaiting ? ' sdd-phase-waiting' : ''}${isRunning ? ' sdd-phase-active' : ''}`}
              data-testid={`sdd-phase-${id}`}
              data-state={state}
              title={isWaiting ? `${title} · ${STATE_LABEL[state] ?? state}` : title}
            >
              <span className="sdd-phase-glyph" aria-hidden="true">{STATE_GLYPH[state] ?? '○'}</span>
              <span className="sdd-phase-num">{i + 1}</span>
              <span className="sdd-phase-title">{title}</span>
              {durSec !== null && state === 'done' && (
                <span className="sdd-phase-duration" data-testid={`sdd-phase-duration-${id}`}>{durSec}s</span>
              )}
              {optional && <span className="sdd-phase-optional-tag" title="可选质量门">可选</span>}
            </li>
          );
        })}
      </ol>

      {waitingPhase && (
        <div className="sdd-phase-actions" data-testid="sdd-phase-actions" data-waiting-phase={waitingPhase.id} data-waiting-state={waitingPhase.state}>
          <div className="sdd-phase-actions-label">
            <strong>{waitingPhase.title}</strong>
            <span className="sdd-phase-actions-hint">
              {waitingPhase.state === 'pending-accept' && 'LLM 已生成该阶段的草案，请确认 / 修改 / 跳过'}
              {waitingPhase.state === 'need-content' && 'daemon 在等生成内容 — 请把 LLM 生成的内容粘贴到下方后点 📤 发送（也支持 ⌨/Ctrl+Enter）'}
              {waitingPhase.state === 'clarify-pending' && 'LLM 提出澄清问题，请输入你的回答后发送'}
              {waitingPhase.state === 'converge-pending' && '后置 review 报告 — 可结束或再迭代'}
            </span>
            {waitingPhase.path && waitingPhase.state !== 'clarify-pending' && (
              <code className="sdd-phase-actions-path" data-testid={`sdd-phase-path-${waitingPhase.id}`}>{waitingPhase.path}</code>
            )}
          </div>

          {waitingPhase.state === 'pending-accept' && (
            <>
              {/* R300: top row — accept / skip. The user-driven
                  refine flow lives in the textarea below, so
                  "accept" is the only path that closes the
                  phase; "skip" stays in the row for optional
                  quality gates (clarify / analyze). */}
              <div className="sdd-phase-actions-buttons">
                <button
                  type="button"
                  className="sdd-btn sdd-btn-primary"
                  onClick={() => sendSsdCommand({ action: 'accept' })}
                  data-testid="sdd-btn-accept"
                  title="接受草案，进入下一阶段"
                >
                  ✅ 接受
                </button>
                <button
                  type="button"
                  className="sdd-btn sdd-btn-ghost"
                  onClick={() => sendSsdCommand({ action: 'skip' })}
                  data-testid="sdd-btn-skip"
                  title="跳过此阶段（仅可选质量门生效）"
                >
                  ⏭️ 跳过
                </button>
              </div>
              {/* R300: inline revision textarea. The user types
                  their refinement prompt here; pressing 📤 发送
                  (or Ctrl/⌘+Enter) sends `{action:"revise",
                  text:...}` to the daemon subprocess's stdin.
                  The daemon re-runs the same phase's LLM call
                  with the user's text appended as a "previous
                  reviewer's notes" block, emits a fresh
                  `phase-draft`, and re-enters `pending-accept`
                  so the user can iterate again or accept. This
                  keeps the per-phase confirm interactive
                  instead of forcing the user to drop into a
                  `window.prompt()` dialog. */}
              <div className="sdd-phase-actions-revise">
                <textarea
                  className="sdd-phase-actions-textarea"
                  placeholder="输入修改意见（可多次迭代）— LLM 会基于当前内容重新生成"
                  value={reviseText}
                  onChange={(e) => setReviseText(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && reviseText.trim()) {
                      sendSsdCommand({ action: 'revise', text: reviseText.trim() });
                      setReviseText('');
                    }
                  }}
                  rows={2}
                  data-testid="sdd-revise-textarea"
                />
                <button
                  type="button"
                  className="sdd-btn sdd-btn-secondary"
                  disabled={!reviseText.trim()}
                  onClick={() => {
                    if (!reviseText.trim()) return;
                    sendSsdCommand({ action: 'revise', text: reviseText.trim() });
                    setReviseText('');
                  }}
                  data-testid="sdd-btn-send-revise"
                  title="Ctrl/⌘+Enter 也能发送"
                >
                  📤 发送修订
                </button>
              </div>
            </>
          )}

          {/* R309: need-content pane — daemon is asking for
              the rendered markdown body of a phase. The user
              pastes content generated by their preferred LLM
              (Mavis chat / any external tool) and presses 📤
              发送 (or Ctrl/⌘+Enter). We forward it as a
              phase-content command; the daemon writes it to
              disk and emits phase-draft so the user can still
              accept/revise after. Mirrors the pending-accept
              revise textarea so the muscle memory is the
              same. */}
          {waitingPhase.state === 'need-content' && (
            <div className="sdd-phase-actions-buttons sdd-phase-actions-need-content">
              <textarea
                className="sdd-phase-actions-textarea"
                placeholder="粘贴 LLM 生成的内容（markdown 全文）— Ctrl/⌘+Enter 发送"
                value={needContentText}
                onChange={(e) => setNeedContentText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && needContentText.trim()) {
                    sendSsdCommand({ action: 'phase-content', content: needContentText });
                    setNeedContentText('');
                  }
                }}
                rows={6}
                data-testid="sdd-need-content-textarea"
              />
              <button
                type="button"
                className="sdd-btn sdd-btn-primary"
                disabled={!needContentText.trim()}
                onClick={() => {
                  if (!needContentText.trim()) return;
                  sendSsdCommand({ action: 'phase-content', content: needContentText });
                  setNeedContentText('');
                }}
                data-testid="sdd-btn-send-need-content"
                title="Ctrl/⌘+Enter 也能发送"
              >
                📤 发送内容
              </button>
            </div>
          )}

          {waitingPhase.state === 'clarify-pending' && (
            <div className="sdd-phase-actions-buttons sdd-phase-actions-clarify">
              <input
                type="text"
                className="sdd-phase-actions-input"
                placeholder="输入回答..."
                value={clarifyAnswer}
                onChange={(e) => setClarifyAnswer(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && clarifyAnswer.trim()) {
                    // R292 wire: clarify-answer carries both
                    // `id` (the daemon's question token) and
                    // `answer` (the user's reply). The
                    // currently-waiting phase's metadata
                    // holds the id; we surface it via
                    // `phase-preview` only as a string
                    // header. The actual id is stored on
                    // the last system message tagged
                    // `sdd-clarify-question`. For the MVP
                    // we send the most recent id verbatim
                    // — the daemon's `askClarify` matches
                    // by id so a stale one will be
                    // discarded, but in practice the only
                    // pending id is the one we just
                    // parked on.
                    const recent = (s => s.messages
                      .slice()
                      .reverse()
                      .find((m: any) => m?.metadata?.kind === 'sdd-clarify-question'))(useStore.getState());
                    const id = (recent as any)?.metadata?.id ?? 'q1';
                    sendSsdCommand({ action: 'clarify-answer', id, answer: clarifyAnswer.trim() } as any);
                    setClarifyAnswer('');
                  }
                }}
                data-testid="sdd-clarify-input"
              />
              <button
                type="button"
                className="sdd-btn sdd-btn-primary"
                disabled={!clarifyAnswer.trim()}
                onClick={() => {
                  const recent = (s => s.messages
                    .slice()
                    .reverse()
                    .find((m: any) => m?.metadata?.kind === 'sdd-clarify-question'))(useStore.getState());
                  const id = (recent as any)?.metadata?.id ?? 'q1';
                  sendSsdCommand({ action: 'clarify-answer', id, answer: clarifyAnswer.trim() } as any);
                  setClarifyAnswer('');
                }}
                data-testid="sdd-btn-clarify-send"
                title="发送回答"
              >
                📤 发送回答
              </button>
            </div>
          )}

          {waitingPhase.state === 'converge-pending' && (
            <>
              <div className="sdd-phase-actions-buttons">
                <button
                  type="button"
                  className="sdd-btn sdd-btn-primary"
                  onClick={() => sendSsdCommand({ action: 'converge-iterate', text: '' } as any)}
                  data-testid="sdd-btn-converge-accept"
                  title="接受当前 review 结论，结束整个 SDD"
                >
                  ✅ 结束
                </button>
              </div>
              {/* R300: same inline-iterate pattern as pending-accept —
                  type review feedback, press 📤 发送 (or Ctrl/⌘+Enter)
                  to push another review iteration round-trip. */}
              <div className="sdd-phase-actions-revise">
                <textarea
                  className="sdd-phase-actions-textarea"
                  placeholder="review 反馈 — 可多次迭代"
                  value={convergeText}
                  onChange={(e) => setConvergeText(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && convergeText.trim()) {
                      sendSsdCommand({ action: 'converge-iterate', text: convergeText.trim() } as any);
                      setConvergeText('');
                    }
                  }}
                  rows={2}
                  data-testid="sdd-converge-textarea"
                />
                <button
                  type="button"
                  className="sdd-btn sdd-btn-secondary"
                  disabled={!convergeText.trim()}
                  onClick={() => {
                    if (!convergeText.trim()) return;
                    sendSsdCommand({ action: 'converge-iterate', text: convergeText.trim() } as any);
                    setConvergeText('');
                  }}
                  data-testid="sdd-btn-send-converge"
                  title="Ctrl/⌘+Enter 也能发送"
                >
                  📤 发送反馈
                </button>
              </div>
            </>
          )}
        </div>
      )}
    </section>
  );
}

function phaseTitleZh(id: string): string {
  switch (id) {
    case 'constitution': return '项目原则';
    case 'specify':      return '需求分析';
    case 'clarify':      return '需求澄清';
    case 'plan':         return '详细设计';
    case 'analyze':      return '一致性分析';
    case 'tasks':        return '任务分析';
    case 'implement':    return '执行实现';
    case 'converge':     return '收敛验证';
    default:             return id;
  }
}