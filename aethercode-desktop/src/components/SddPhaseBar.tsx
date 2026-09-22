import { useEffect, useState } from 'react';
import { useStore } from '../store';
import './SddPhaseBar.css';

/**
 * R315 — SDD 8-chip progress bar (reintroduced).
 *
 * Renders the eight Spec Kit phases (项目原则 / 需求分析 / 需求澄清 /
 * 详细设计 / 一致性分析 / 任务分析 / 执行实现 / 收敛验证) as a
 * compact chip strip showing each phase's state (idle / running /
 * pending-confirm / done / skipped / failed). Optional quality
 * gates (clarify / analyze / converge) render half-opacity.
 *
 * R315 key difference from R292-R311:
 *   - No wire protocol — the actual SDD run is driven by the Mavis
 *     agent via chat (see agents/mavis/skills/sdd). The chip state
 *     is updated by MessageList scanning chat messages for the
 *     "✅ 第 N 阶段完成 — <title>" pattern.
 *   - The ✅ / ✏️ / ⏭️ buttons send chat messages — the SDD skill
 *     interprets them as phase-advance / re-run / skip. There is
 *     no daemon subprocess to receive the commands.
 *
 * Visible states:
 *   - idle           →  ○ (待开始)
 *   - running        →  ◐ (进行中)
 *   - pending-confirm →  ⏸ (待确认)
 *   - done           →  ✓ (已完成)
 *   - skipped        →  ↷ (已跳过)
 *   - failed         →  ✕ (失败)
 */

const STATE_GLYPH: Record<string, string> = {
  'idle':             '○',
  'running':          '◐',
  'pending-confirm':  '⏸',
  'pending-accept':   '⏸', // R320 alias (kept for any in-flight chips)
  'done':             '✓',
  'skipped':          '↷',
  'failed':           '✕',
};

const STATE_LABEL: Record<string, string> = {
  'idle':             '待开始',
  'running':          '进行中',
  'pending-confirm':  '待确认',
  'pending-accept':   '待确认', // R320 alias
  'done':             '已完成',
  'skipped':          '已跳过',
  'failed':           '失败',
};

// Canonical phase order — Spec Kit 6 phases + 2 optional gates.
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

const PHASE_TITLE_ZH: Record<string, string> = {
  constitution: '项目原则',
  specify:      '需求分析',
  clarify:      '需求澄清',
  plan:         '详细设计',
  analyze:      '一致性分析',
  tasks:        '任务分析',
  implement:    '执行实现',
  converge:     '收敛验证',
};

export function SddPhaseBar() {
  const sddEnabled   = useStore((s) => s.sddEnabled);
  const sddPhases    = useStore((s) => s.sddPhases);
  const sddActive    = useStore((s) => s.sddActive);
  const sddSlug      = useStore((s) => s.sddSlug);
  const sddCurrentPhase = useStore((s) => s.sddCurrentPhase);
  const setSddEnabled = useStore((s) => s.setSddEnabled);
  const stopSsdFlow  = useStore((s) => s.stopSsdFlow);
  const sendSsdCommand = useStore((s) => s.sendSsdCommand);
  const sddSkipPhase = useStore((s) => s.sddSkipPhase);
  const sddJumpToPhase = useStore((s) => s.sddJumpToPhase);

  // The single phase waiting on the user — serial, not concurrent.
  const waitingPhase = sddPhases.find((p) => p.state === 'pending-confirm');

  // Revision text input. Cleared when the phase advances so the
  // next route's textarea is empty.
  const [reviseText, setReviseText] = useState('');
  useEffect(() => {
    setReviseText('');
  }, [waitingPhase?.id]);

  // tick once a second to refresh runningFor — cheap re-render
  // of a single number, fine.
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!sddPhases.find((p) => p.state === 'running')) return;
    const id = window.setInterval(() => setTick((n) => n + 1), 1000);
    return () => window.clearInterval(id);
  }, [sddPhases.find((p) => p.state === 'running')?.id]);

  // R315: IMPORTANT — the early-return for sddEnabled MUST come
  // after all hooks (Rules of Hooks: hooks must be called in the
  // same order every render). Placing `return null` before a
  // hook causes React to throw "Rendered fewer hooks than
  // expected" the next time sddEnabled flips false→true, which
  // crashes the whole tree → blank screen. Move it after every
  // hook below.
  if (!sddEnabled) return null;

  const byId = new Map(sddPhases.map((p) => [p.id, p]));

  // Progress counter + currently-running phase.
  const completedCount = sddPhases.filter(
    (p) => p.state === 'done' || p.state === 'skipped',
  ).length;
  const totalCount = sddPhases.length || 8;
  const runningPhase = sddPhases.find((p) => p.state === 'running');
  const runningFor = runningPhase?.startedAt
    ? Math.max(0, Math.round((Date.now() - runningPhase.startedAt) / 1000))
    : 0;

  return (
    <section
      className="sdd-phase-bar"
      aria-label="SDD 阶段进度"
      data-testid="sdd-phase-bar"
    >
      <header className="sdd-phase-bar-header">
        <div className="sdd-phase-bar-title">
          <h3>SDD</h3>
          <span className="sdd-phase-bar-subtitle">
            {sddActive && sddSlug
              ? `8 阶段（${sddSlug}）`
              : '8 阶段：原则 → 需求 → 澄清 → 设计 → 一致性 → 任务 → 实现 → 收敛'}
            {sddActive && sddPhases.length > 0 && (
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
          </span>
        </div>
        <button
          type="button"
          className="sdd-phase-bar-close"
          onClick={() => {
            if (sddActive) {
              // Mid-run close — agent writes abort.md, collapses bar.
              void stopSsdFlow();
            } else {
              setSddEnabled(false);
            }
          }}
          title={sddActive ? '取消当前 SDD 流程' : '退出 SDD 面板'}
          data-testid="sdd-phase-bar-close"
        >
          {sddActive ? '取消' : '✕'}
        </button>
      </header>

      <ol className="sdd-phase-list">
        {PHASE_ORDER.map((id, i) => {
          const phase = byId.get(id);
          const state = phase?.state ?? 'idle';
          const title = phase?.title ?? PHASE_TITLE_ZH[id] ?? id;
          const optional = phase?.optional ?? OPTIONAL_PHASES.has(id);
          const isWaiting = waitingPhase?.id === id;
          const isRunning = state === 'running';
          const durSec = (phase?.startedAt && phase?.endedAt)
            ? Math.max(0, Math.round((phase.endedAt - phase.startedAt) / 1000))
            : null;
          // R324: skip / jump-to affordances are decided per-chip,
// based on the chip's role (running / pending-confirm /
// future / done / skipped). The skip / jump-to buttons
// live inside each <li> so the user can plan ahead —
// e.g. while phase 2 is running, they can already mark
// phase 3 (clarify, optional) as skipped, or jump
// straight to phase 6 (tasks).
          const canSkipThisPhase =
            sddActive && (state === 'idle' || state === 'running') && optional;
          const canJumpToThisPhase =
            sddActive && (state === 'idle') &&
            (sddCurrentPhase != null && (i + 1) > sddCurrentPhase);
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
              {optional && state === 'idle' && (
                <span className="sdd-phase-optional-tag" title="可选质量门">可选</span>
              )}
              {/* R324: chip-level skip / jump-to actions. These
               *  let the user plan the run *before* the agent
               *  reaches each phase — the previous design only
               *  surfaced the skip button when the current phase
               *  completed, which was too late (the user already
               *  decided at phase N-1 whether phase N should run). */}
              {canSkipThisPhase && (
                <button
                  type="button"
                  className="sdd-phase-chip-action sdd-phase-chip-skip"
                  onClick={(e) => {
                    e.stopPropagation();
                    void sddSkipPhase(id);
                  }}
                  data-testid={`sdd-chip-skip-${id}`}
                  title={`标记第 ${i + 1} 阶段（${title}）为已跳过 — agent 跑到这里时直接跳过`}
                  aria-label={`跳过 ${title}`}
                >
                  ⏭
                </button>
              )}
              {canJumpToThisPhase && (
                <button
                  type="button"
                  className="sdd-phase-chip-action sdd-phase-chip-jump"
                  onClick={(e) => {
                    e.stopPropagation();
                    void sddJumpToPhase(i + 1);
                  }}
                  data-testid={`sdd-chip-jump-${id}`}
                  title={`跳过中间的 phase，直接进入第 ${i + 1} 阶段（${title}）`}
                  aria-label={`直达 ${title}`}
                >
                  ⏩
                </button>
              )}
            </li>
          );
        })}
      </ol>

      {sddActive && (
        <div
          className="sdd-phase-actions"
          data-testid="sdd-phase-actions"
          data-waiting-phase={waitingPhase?.id ?? sddPhases.find((p) => p.state === 'running')?.id ?? ''}
          data-waiting-state={waitingPhase?.state ?? (sddPhases.find((p) => p.state === 'running')?.state ?? 'idle')}
        >
          <div className="sdd-phase-actions-label">
            <strong>
              {waitingPhase
                ? waitingPhase.title
                : (sddPhases.find((p) => p.state === 'running')?.title ?? 'SDD 流程')}
              {' · '}
              {waitingPhase
                ? '⏸ 待确认'
                : (sddPhases.find((p) => p.state === 'running')
                    ? '◐ 进行中'
                    : '○ 空闲')}
            </strong>
            <span className="sdd-phase-actions-hint">
              {waitingPhase
                ? 'LLM 已生成该阶段的草案，请选择 ABCDE（agent 在 chat 里等你回复）'
                : (sddPhases.find((p) => p.state === 'running')
                    ? 'agent 正在跑这一阶段；可在跑完后点 A/B/C/D/E 或输入其他意见'
                    : 'SDD 流程已就绪 — 可点 D 重跑当前阶段 / E 暂停')}
            </span>
            {(waitingPhase?.path ?? sddPhases.find((p) => p.state === 'running')?.path) && (
              <code className="sdd-phase-actions-path" data-testid="sdd-phase-actions-path">
                {waitingPhase?.path ?? sddPhases.find((p) => p.state === 'running')?.path}
              </code>
            )}
          </div>

          {/* R322 + R324: top row — ABDE actions (C-skip moved to the
   *  chip strip itself; the user pre-persales which optional
   *  phases to skip before they run). Buttons are compact,
   *  single-letter badges + emoji + label, all on one row.
   *  Layout: A (primary) / B (modify) / D (rerun) / E (pause).
   *  Skip is no longer a global button — it's per-chip (R324).
   *
   *  R323: drop the `waitingPhase` guard. The buttons should
   *  always be visible when an SDD run is active so the user
   *  has a clear affordance regardless of the chip state
   *  machine. The state machine still matters for the chip
   *  colours / glyphs (⏸待确认 / ◐进行中 / ✓已完成), but the
   *  buttons are the user's primary interface. */}
          <div className="sdd-phase-actions-buttons" data-testid="sdd-phase-actions-buttons">
            <button
              type="button"
              className="sdd-btn sdd-btn-primary"
              onClick={() => sendSsdCommand('approve')}
              data-testid="sdd-btn-accept"
              data-sdd-choice="A"
              title="A — 接受草案，进入下一阶段"
            >
              <span className="sdd-btn-letter">A</span>
              <span className="sdd-btn-label">接受</span>
            </button>
            <button
              type="button"
              className="sdd-btn sdd-btn-secondary"
              onClick={() => {
                if (reviseText.trim()) {
                  sendSsdCommand('modify', reviseText.trim());
                  setReviseText('');
                } else {
                  const el = document.querySelector('[data-testid="sdd-revise-textarea"]') as HTMLTextAreaElement | null;
                  el?.focus();
                }
              }}
              data-testid="sdd-btn-modify"
              data-sdd-choice="B"
              title="B — 修改当前阶段（在下方输入意见后发送）"
            >
              <span className="sdd-btn-letter">B</span>
              <span className="sdd-btn-label">修改</span>
            </button>
            <button
              type="button"
              className="sdd-btn sdd-btn-ghost"
              onClick={() => sendSsdCommand('rerun')}
              data-testid="sdd-btn-rerun"
              data-sdd-choice="D"
              title="D — 重新生成当前阶段（覆盖现有产物）"
            >
              <span className="sdd-btn-letter">D</span>
              <span className="sdd-btn-label">重跑</span>
            </button>
            <button
              type="button"
              className="sdd-btn sdd-btn-ghost"
              onClick={() => sendSsdCommand('pause')}
              data-testid="sdd-btn-pause"
              data-sdd-choice="E"
              title="E — 暂停（agent 不动，等用户进一步指示）"
            >
              <span className="sdd-btn-letter">E</span>
              <span className="sdd-btn-label">暂停</span>
            </button>
          </div>
          {/* bottom row: large textarea for free-text input. R324:
           *  taller (min-height 56px / default 3 rows) so users
           *  can comfortably write multi-line feedback. Auto-
           *  grows via the autoSizeTextarea helper. */}
          <div className="sdd-phase-actions-revise">
            <textarea
              className="sdd-phase-actions-textarea"
              placeholder="其他意见 — 直接输入你的反馈（agent 会把它当作 phase 修改指令，按 Ctrl/⌘+Enter 发送）"
              value={reviseText}
              onChange={(e) => {
                setReviseText(e.target.value);
                // Auto-grow: shrink-to-fit when content is
                // short, grow up to max-height when content is
                // long. Cheap (one reflow per keystroke).
                const el = e.currentTarget;
                el.style.height = 'auto';
                el.style.height = `${Math.min(el.scrollHeight, 200)}px`;
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && reviseText.trim()) {
                  sendSsdCommand('modify', reviseText.trim());
                  setReviseText('');
                }
              }}
              rows={3}
              data-testid="sdd-revise-textarea"
            />
            <button
              type="button"
              className="sdd-btn sdd-btn-secondary"
              disabled={!reviseText.trim()}
              onClick={() => {
                if (!reviseText.trim()) return;
                sendSsdCommand('modify', reviseText.trim());
                setReviseText('');
              }}
              data-testid="sdd-btn-send-revise"
              title="Ctrl/⌘+Enter 也能发送"
            >
              📤 发送修订
            </button>
          </div>
        </div>
      )}
    </section>
  );
}