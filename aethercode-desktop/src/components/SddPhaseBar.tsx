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
  const setSddEnabled = useStore((s) => s.setSddEnabled);
  const stopSsdFlow  = useStore((s) => s.stopSsdFlow);
  const sendSsdCommand = useStore((s) => s.sendSsdCommand);

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
        <div
          className="sdd-phase-actions"
          data-testid="sdd-phase-actions"
          data-waiting-phase={waitingPhase.id}
          data-waiting-state={waitingPhase.state}
        >
          <div className="sdd-phase-actions-label">
            <strong>{waitingPhase.title}</strong>
            <span className="sdd-phase-actions-hint">
              {'LLM 已生成该阶段的草案，请确认 / 修改 / 跳过（agent 在 chat 里等你回复）'}
            </span>
            {waitingPhase.path && (
              <code className="sdd-phase-actions-path" data-testid={`sdd-phase-path-${waitingPhase.id}`}>{waitingPhase.path}</code>
            )}
          </div>

          {/* R322: top row — structured ABCDE choices. The user
           *  explicitly asked for lettered buttons (instead of
           *  icons) so they don't have to remember which emoji
           *  means what. We render:
           *    A — ✅ 接受并进入下一阶段 (approve)
           *    B — ✏️ 修改当前阶段 (modify; text in textarea)
           *    C — ⏭️ 跳过下一阶段 (skip; optional only)
           *    D — 🔁 重新生成当前阶段 (rerun)
           *    E — ✋ 暂停（agent 不动，等用户进一步指示） (pause)
           *  Below: free-text input ("其他") wired to sendSsdCommand
           *  as a generic modify command. */}
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
              <span className="sdd-btn-label">✅ 接受</span>
            </button>
            <button
              type="button"
              className="sdd-btn sdd-btn-secondary"
              onClick={() => {
                if (reviseText.trim()) {
                  sendSsdCommand('modify', reviseText.trim());
                  setReviseText('');
                } else {
                  // focus the textarea so the user can type
                  const el = document.querySelector('[data-testid="sdd-revise-textarea"]') as HTMLTextAreaElement | null;
                  el?.focus();
                }
              }}
              data-testid="sdd-btn-modify"
              data-sdd-choice="B"
              title="B — 修改当前阶段（在下方输入意见后发送）"
            >
              <span className="sdd-btn-letter">B</span>
              <span className="sdd-btn-label">✏️ 修改</span>
            </button>
            {waitingPhase.optional && (
              <button
                type="button"
                className="sdd-btn sdd-btn-ghost"
                onClick={() => sendSsdCommand('skip')}
                data-testid="sdd-btn-skip"
                data-sdd-choice="C"
                title="C — 跳过下一阶段（仅可选阶段生效；REQUIRED 阶段会反问）"
              >
                <span className="sdd-btn-letter">C</span>
                <span className="sdd-btn-label">⏭️ 跳过</span>
              </button>
            )}
            <button
              type="button"
              className="sdd-btn sdd-btn-ghost"
              onClick={() => sendSsdCommand('rerun')}
              data-testid="sdd-btn-rerun"
              data-sdd-choice="D"
              title="D — 重新生成当前阶段（覆盖现有产物）"
            >
              <span className="sdd-btn-letter">D</span>
              <span className="sdd-btn-label">🔁 重跑</span>
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
              <span className="sdd-btn-label">✋ 暂停</span>
            </button>
          </div>
          {/* bottom row: ✏️ 修改 textarea + 📤 发送修订 */}
          <div className="sdd-phase-actions-revise">
            <textarea
              className="sdd-phase-actions-textarea"
              placeholder="其他意见 — 直接输入你的反馈（agent 会把它当作 phase 修改指令，按 Ctrl/⌘+Enter 发送）"
              value={reviseText}
              onChange={(e) => setReviseText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && reviseText.trim()) {
                  sendSsdCommand('modify', reviseText.trim());
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