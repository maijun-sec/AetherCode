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

  // R325: the per-bar revision textarea is gone. Free-text
  // feedback goes into the main chat input below (MessageInput
  // already routes "继续" / "跳过" / plain text as
  // sendSsdCommand calls). The bar now only owns the chip
  // strip + (when waiting) the ABDE button row.

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

      {waitingPhase && (
        <div
          className="sdd-phase-actions"
          data-testid="sdd-phase-actions"
          data-waiting-phase={waitingPhase.id}
          data-waiting-state={waitingPhase.state}
        >
          <div className="sdd-phase-actions-label">
            <strong>
              {waitingPhase.title}
              {' · '}
              ⏸ 待确认
            </strong>
            <span className="sdd-phase-actions-hint">
              {'点 A 接受 / B 修改（在下方输入意见）/ D 重跑 / E 暂停；或在 chat 里直接打字回 agent'}
              {PHASE_ORDER.indexOf(waitingPhase.id) < PHASE_ORDER.length - 1
                ? '；当前阶段后还有可选的 C 跳下一阶段或 F 跳到指定阶段'
                : ''}
            </span>
            {waitingPhase.path && (
              <code className="sdd-phase-actions-path" data-testid="sdd-phase-actions-path">
                {waitingPhase.path}
              </code>
            )}
          </div>

          {/* R322 + R324 + R325 + R328: ABCDEF actions. C-skip-
           *  next appears inline only when the *next* phase is
           *  optional (clarify / analyze / converge); F-jump-to
           *  shows a dropdown of every idle phase ahead of the
           *  current waitingPhase so the user can pick a target
           *  ("完成需求分析后直接到任务分析") without having to
           *  click chip-strip buttons one at a time. R325 dropped
           *  the per-bar textarea — the user types free-text in
           *  the main chat input below (MessageInput's keyword
           *  routing already wires "skip" / "approve" / plain
           *  feedback to sendSsdCommand). */}
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
                // B: focus the main chat input — that's the
                // large textarea below, which MessageInput's
                // keyword routing already wires to sendSsdCommand.
                const el = document.querySelector('.message-input-textarea') as HTMLTextAreaElement | null;
                el?.focus();
              }}
              data-testid="sdd-btn-modify"
              data-sdd-choice="B"
              title="B — 修改当前阶段（聚焦到下方 chat 输入框，直接打字）"
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
            {/* R328: C-skip-next — inline in the action row when
             *  the next phase is optional. (Per-chip ⏭ buttons
             *  are still there for the user who's planning
             *  multiple skips in advance.) */}
            {(() => {
              const waitingIdx = PHASE_ORDER.indexOf(waitingPhase.id);
              const nextId = PHASE_ORDER[waitingIdx + 1];
              const nextPhase = nextId ? sddPhases.find((p) => p.id === nextId) : undefined;
              const nextOptional = nextPhase?.optional ?? OPTIONAL_PHASES.has(nextId ?? '');
              if (!nextId || !nextOptional) return null;
              return (
                <button
                  type="button"
                  className="sdd-btn sdd-btn-ghost"
                  onClick={() => sendSsdCommand('approve')} // approve from current = skip-next + advance
                  data-testid="sdd-btn-skip-next"
                  data-sdd-choice="C"
                  title={`C — 跳过下一阶段（${nextPhase?.title ?? nextId}）`}
                >
                  <span className="sdd-btn-letter">C</span>
                  <span className="sdd-btn-label">跳{(nextPhase?.title ?? nextId)}</span>
                </button>
              );
            })()}
            {/* R328: F-jump-to — pick any idle phase ahead of
             *  current. Always rendered when there's at least
             *  one idle phase; clicking submits sddJumpToPhase. */}
            {(() => {
              const waitingIdx = PHASE_ORDER.indexOf(waitingPhase.id);
              const targets = PHASE_ORDER.slice(waitingIdx + 1)
                .map((id, i) => ({ id, idx: waitingIdx + 1 + i + 1 }))
                .filter(({ id }) => sddPhases.find((p) => p.id === id)?.state === 'idle');
              if (targets.length === 0) return null;
              return (
                <select
                  className="sdd-btn sdd-btn-jump"
                  value=""
                  onChange={(e) => {
                    const v = e.target.value;
                    if (v) {
                      void sddJumpToPhase(Number(v));
                      e.target.value = '';
                    }
                  }}
                  data-testid="sdd-btn-jump-to"
                  data-sdd-choice="F"
                  title="F — 直接进入某一阶段（跳过中间所有 phase）"
                >
                  <option value="" disabled>F 跳到…</option>
                  {targets.map(({ id, idx }) => {
                    const t = sddPhases.find((p) => p.id === id);
                    return (
                      <option key={id} value={String(idx)}>
                        {idx} {t?.title ?? id}
                      </option>
                    );
                  })}
                </select>
              );
            })()}
          </div>
          {/* R325: removed the per-bar textarea. Users type
           *  free-text feedback in the main chat input below,
           *  which is much larger and shares the keyboard
           *  shortcuts the user is already familiar with.
           *  MessageInput's Enter routing recognises "继续" /
           *  "跳过" / "approve" / "skip" / plain feedback as
           *  sendSsdCommand calls. */}
        </div>
      )}
    </section>
  );
}