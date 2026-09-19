import { useStore } from '../store';
import './SddPhaseBar.css';

/**
 * R288 + R292 — SDD phase progress bar.
 *
 * Lives between the chat list and MessageInput. Renders the
 * eight Spec Kit phases (项目原则 / 需求分析 / 需求澄清 /
 * 详细设计 / 一致性分析 / 任务分析 / 执行实现 / 收敛验证)
 * with a chip per stage showing its state (idle / running /
 * pending-accept / done / skipped / failed). Optional quality
 * gates (clarify / analyze) and the optional converge loop
 * render half-opacity so the user can tell which phases are
 * required vs opt-in.
 *
 * R292 (Spec Kit integration): phase ids are now the Spec Kit
 * kebab-case form (constitution / specify / clarify / plan /
 * analyze / tasks / implement / converge), no longer the R236
 * SSD 4-phase form (spec / design / tasks / dev). Wire
 * compatibility: the `phase-list` event from the daemon still
 * arrives as a sequence of `{id, order, title, optional}`
 * objects; we render them in `PHASE_ORDER` so the chip strip
 * is stable even if the daemon reorders them.
 *
 * When `sddEnabled` is off in the store, returns null —
 * MessageInput's 📐 pill controls the toggle.
 *
 * R289 (live): the chip states are driven by `store.ssdPhases`,
 * which `startSsdFlow` populates from the SsdDriver's event
 * stream. The component is fully reactive — flipping the 📐
 * toggle off clears ssdPhases via `stopSsdFlow` (called from
 * setSddEnabled).
 */
const STATE_LABEL: Record<string, string> = {
  'idle':           '○ 待开始',
  'pending':        '○ 待开始',
  'running':        '● 进行中',
  'pending-accept': '⏸ 待确认',
  'clarify-pending':'⏸ 待澄清',
  'converge-pending':'⏸ 收敛中',
  'done':           '✓ 已完成',
  'skipped':        '↷ 已跳过',
  'failed':         '✕ 失败',
};

const PHASE_DESC: Record<string, string> = {
  constitution:    '建立项目治理原则 (一次性, 复用跨 feature)',
  specify:         '从你的 prompt 提取核心需求, 生成可验收的需求列表',
  clarify:         '澄清需求中的模糊点 (可选质量门)',
  plan:            '基于需求设计架构、模块边界、关键数据结构',
  analyze:         '跨 spec/plan/tasks 一致性检查 (可选质量门)',
  tasks:           '把设计拆成可执行的子任务, 标注依赖与验收条件',
  implement:       '按任务列表逐项实现, 每项输出写到 logs/implement.log',
  converge:        '后置 review 循环: model 报告 converged 才结束 (可选)',
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

  if (!sddEnabled) return null;

  // Index the phases by id for rendering. If the store's
  // ssdPhases is empty (toggle just flipped on, no run started
  // yet) we render an idle template so the user still sees the
  // 8 phases.
  const byId = new Map<string, typeof ssdPhases[number]>();
  for (const p of ssdPhases) byId.set(p.id, p);

  return (
    <section className="sdd-phase-bar" aria-label="规格化流程阶段进度" data-testid="sdd-phase-bar">
      <header className="sdd-phase-bar-header">
        <div className="sdd-phase-bar-title">
          <span className="sdd-phase-bar-icon">📐</span>
          <h3>规格化流程</h3>
          <span className="sdd-phase-bar-subtitle">
            {ssdActive && ssdSlug
              ? `8 阶段：原则 → 需求 → 澄清 → 设计 → 一致性 → 任务 → 实现 → 收敛（slug: ${ssdSlug}）`
              : '8 阶段：原则 → 需求 → 澄清 → 设计 → 一致性 → 任务 → 实现 → 收敛'}
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
          title={ssdActive ? '取消当前 SDD 流程' : '退出规格化流程'}
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
          const desc = phase?.preview && (state === 'pending-accept' || state === 'clarify-pending')
            ? phase.preview
            : PHASE_DESC[id];
          return (
            <li
              key={id}
              className={`sdd-phase-item sdd-phase-${state}${optional ? ' sdd-phase-optional' : ''}`}
              data-testid={`sdd-phase-${id}`}
            >
              <div className="sdd-phase-marker">
                <span className="sdd-phase-num">{i + 1}</span>
                <span className="sdd-phase-state-label">{STATE_LABEL[state] ?? state}</span>
              </div>
              <div className="sdd-phase-body">
                <div className="sdd-phase-title">
                  {title}
                  {optional && <span className="sdd-phase-optional-tag" title="可选质量门">可选</span>}
                </div>
                <div className="sdd-phase-desc">{desc}</div>
                {phase?.path && (state === 'pending-accept' || state === 'clarify-pending') && (
                  <div className="sdd-phase-path" data-testid={`sdd-phase-path-${id}`}>
                    <code>{phase.path}</code>
                  </div>
                )}
                {phase?.preview && (state === 'pending-accept' || state === 'clarify-pending') && (
                  <pre className="sdd-phase-preview">{phase.preview}</pre>
                )}
              </div>
            </li>
          );
        })}
      </ol>
      <footer className="sdd-phase-bar-footer">
        <small className="sdd-phase-bar-hint">
          开启规格化流程后，输入框发送的第一条消息会自动进入「项目原则」阶段。
          每个阶段完成后会在对话流里出 assistant 卡片让你确认 / 修改，确认后再进入下一阶段，
          daemon 同时把内容写到 <code>&lt;cwd&gt;/.specify/specs/&lt;NNN-slug&gt;/</code> 下对应的 .md 文件。
          可选质量门 (澄清 / 一致性 / 收敛) 默认开启，可用 <code>--no-clarify</code> / <code>--no-analyze</code> / <code>--no-converge</code> 跳过。
        </small>
      </footer>
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