import { useEffect, useState } from 'react';
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
 * This is an MVP: phases are UI-only state seeded with the mock
 * event sequence so the user can see the layout. Wiring to the
 * actual SsdDriver / daemon flow is R288 follow-up.
 */
interface PhaseState {
  id: 'spec' | 'design' | 'tasks' | 'dev';
  title: string;
  description: string;
  state: 'idle' | 'running' | 'pending-accept' | 'done' | 'skipped' | 'failed';
  preview?: string;
}

const PHASE_TEMPLATE: PhaseState[] = [
  { id: 'spec',   title: '需求分析',   description: '从你的 prompt 提取核心需求，生成可验收的需求列表',     state: 'idle' },
  { id: 'design', title: '详细设计',   description: '基于需求设计架构、模块边界、关键数据结构',                state: 'idle' },
  { id: 'tasks',  title: '任务分析',   description: '把设计拆成可执行的子任务，标注依赖与验收条件',           state: 'idle' },
  { id: 'dev',    title: '开发实现',   description: '按任务列表逐项实现；每项完成后回写到 <cwd>/.aethercode/ssd/<slug>/', state: 'idle' },
];

const STATE_LABEL: Record<PhaseState['state'], string> = {
  'idle':           '○ 待开始',
  'running':        '● 进行中',
  'pending-accept': '⏸ 待确认',
  'done':           '✓ 已完成',
  'skipped':        '↷ 已跳过',
  'failed':         '✕ 失败',
};

export function SddPhaseBar() {
  const sddEnabled = useStore((s) => s.sddEnabled);
  const setSddEnabled = useStore((s) => s.setSddEnabled);
  // R288: phases always start in idle. Real transitions
  // (idle → running → pending-accept → done) are driven
  // by SsdDriver events pushed from the daemon. Until that
  // wiring lands in R289, opening the 📐 toggle just
  // shows the 4 phases parked in `idle` and the chat
  // input is the actual entry point — sending a message
  // kicks off the spec flow.
  const [phases, setPhases] = useState<PhaseState[]>(PHASE_TEMPLATE);

  // Reset phases to idle whenever the user toggles SDD off
  // and back on, so the next SSD run starts clean. The
  // `running` / `pending-accept` / `done` transitions are
  // owned by the SsdDriver event stream — never by a
  // local timer.
  useEffect(() => {
    if (sddEnabled) {
      setPhases(PHASE_TEMPLATE.map((p) => ({ ...p, state: 'idle' as const, preview: undefined })));
    }
  }, [sddEnabled]);

  if (!sddEnabled) return null;

  return (
    <section className="sdd-phase-bar" aria-label="规格化流程阶段进度" data-testid="sdd-phase-bar">
      <header className="sdd-phase-bar-header">
        <div className="sdd-phase-bar-title">
          <span className="sdd-phase-bar-icon">📐</span>
          <h3>规格化流程</h3>
          <span className="sdd-phase-bar-subtitle">4 阶段：需求 → 设计 → 任务 → 实现</span>
        </div>
        <button
          type="button"
          className="sdd-phase-bar-close"
          onClick={() => setSddEnabled(false)}
          title="退出规格化流程"
          data-testid="sdd-phase-bar-close"
        >
          ✕
        </button>
      </header>
      <ol className="sdd-phase-list">
        {phases.map((p, i) => (
          <li key={p.id} className={`sdd-phase-item sdd-phase-${p.state}`} data-testid={`sdd-phase-${p.id}`}>
            <div className="sdd-phase-marker">
              <span className="sdd-phase-num">{i + 1}</span>
              <span className="sdd-phase-state-label">{STATE_LABEL[p.state]}</span>
            </div>
            <div className="sdd-phase-body">
              <div className="sdd-phase-title">{p.title}</div>
              <div className="sdd-phase-desc">{p.description}</div>
              {p.preview && p.state === 'pending-accept' && (
                <pre className="sdd-phase-preview">{p.preview}</pre>
              )}
            </div>
          </li>
        ))}
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