import { useStore } from '../store';
import './SddPhaseBar.css';

/**
 * R288: SDD phase progress bar.
 *
 * Lives between the chat list and MessageInput. Renders the four
 * 阶段 (需求分析 / 详细设计 / 任务分析 / 开发实现) with a chip
 * per stage showing its state (idle / running / pending-accept /
 * done / skipped / failed). When `sddEnabled` is off in the store,
 * returns null — MessageInput's 📐 pill controls the toggle.
 *
 * R289 (live): the chip states are now driven by `store.ssdPhases`,
 * which `startSsdFlow` populates from the MockSsdDriver's event
 * stream. The component is fully reactive — flipping the 📐 toggle
 * off clears ssdPhases via `stopSsdFlow` (called from setSddEnabled).
 */
const STATE_LABEL: Record<string, string> = {
  'idle':           '○ 待开始',
  'running':        '● 进行中',
  'pending-accept': '⏸ 待确认',
  'done':           '✓ 已完成',
  'skipped':        '↷ 已跳过',
  'failed':         '✕ 失败',
};

const PHASE_DESC: Record<string, string> = {
  spec:   '从你的 prompt 提取核心需求，生成可验收的需求列表',
  design: '基于需求设计架构、模块边界、关键数据结构',
  tasks:  '把设计拆成可执行的子任务，标注依赖与验收条件',
  dev:    '按任务列表逐项实现；每项完成后回写到 <cwd>/.aethercode/ssd/<slug>/',
};

// Canonical phase order — the `phase-list` event from the driver
// uses these ids and titles, so even if the store gets out of sync
// with the driver (e.g. mid-tear-down) we still render all four.
const PHASE_ORDER = ['spec', 'design', 'tasks', 'dev'] as const;

export function SddPhaseBar() {
  const sddEnabled = useStore((s) => s.sddEnabled);
  const ssdPhases  = useStore((s) => s.ssdPhases);
  const ssdActive  = useStore((s) => s.ssdActive);
  const ssdSlug    = useStore((s) => s.ssdSlug);
  const setSddEnabled = useStore((s) => s.setSddEnabled);
  const sendSsdCommand = useStore((s) => s.sendSsdCommand);

  if (!sddEnabled) return null;

  // Index the phases by id for rendering. If the
  // store's ssdPhases is empty (toggle just flipped
  // on, no run started yet) we render an idle
  // template so the user still sees the 4 phases.
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
              ? `4 阶段：需求 → 设计 → 任务 → 实现（slug: ${ssdSlug}）`
              : '4 阶段：需求 → 设计 → 任务 → 实现'}
          </span>
        </div>
        <button
          type="button"
          className="sdd-phase-bar-close"
          onClick={() => {
            if (ssdActive) {
              // Mid-run close sends quit so the driver
              // tears down cleanly. After quit fires
              // the event handler will set sddEnabled=false
              // via the store action.
              sendSsdCommand({ action: 'quit' });
            } else {
              setSddEnabled(false);
            }
          }}
          title={ssdActive ? '取消当前 SSD 流程' : '退出规格化流程'}
          data-testid="sdd-phase-bar-close"
        >
          {ssdActive ? '取消' : '✕'}
        </button>
      </header>
      <ol className="sdd-phase-list">
        {PHASE_ORDER.map((id, i) => {
          const phase = byId.get(id);
          const state = phase?.state ?? 'idle';
          const title = phase?.title ?? (
            id === 'spec'   ? '需求分析' :
            id === 'design' ? '详细设计' :
            id === 'tasks'  ? '任务分析' :
                              '开发实现'
          );
          const desc = phase?.preview && state === 'pending-accept'
            ? phase.preview
            : PHASE_DESC[id];
          return (
            <li key={id} className={`sdd-phase-item sdd-phase-${state}`} data-testid={`sdd-phase-${id}`}>
              <div className="sdd-phase-marker">
                <span className="sdd-phase-num">{i + 1}</span>
                <span className="sdd-phase-state-label">{STATE_LABEL[state] ?? state}</span>
              </div>
              <div className="sdd-phase-body">
                <div className="sdd-phase-title">{title}</div>
                <div className="sdd-phase-desc">{desc}</div>
                {phase?.path && state === 'pending-accept' && (
                  <div className="sdd-phase-path" data-testid={`sdd-phase-path-${id}`}>
                    <code>{phase.path}</code>
                  </div>
                )}
                {phase?.preview && state === 'pending-accept' && (
                  <pre className="sdd-phase-preview">{phase.preview}</pre>
                )}
              </div>
            </li>
          );
        })}
      </ol>
      <footer className="sdd-phase-bar-footer">
        <small className="sdd-phase-bar-hint">
          开启规格化流程后，输入框发送的第一条消息会自动进入「需求分析」阶段。
          每个阶段完成后会在对话流里出 assistant 卡片让你确认 / 修改，确认后再进入下一阶段，
          daemon 同时把内容写到 <code>&lt;cwd&gt;/.aethercode/ssd/&lt;slug&gt;/</code> 下对应的 .md 文件。
        </small>
      </footer>
    </section>
  );
}