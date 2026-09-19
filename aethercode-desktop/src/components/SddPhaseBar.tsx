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
  // R288 MVP: phases are seeded on toggle-on. The
  // next iteration wires SsdDriver / daemon events
  // to drive transitions (running → pending-accept → done).
  const [phases, setPhases] = useState<PhaseState[]>(PHASE_TEMPLATE);

  // when toggled OFF, reset phases to idle. When toggled ON, run
  // the canned demo sequence so the user sees the layout.
  useEffect(() => {
    if (!sddEnabled) {
      setPhases(PHASE_TEMPLATE.map((p) => ({ ...p, state: 'idle', preview: undefined })));
      return;
    }
    // canned sequence: each phase moves through running →
    // pending-accept → done, with a 1.5s delay so the user can
    // watch it animate. Replace this with real SsdDriver event
    // wiring in the next round.
    let cancelled = false;
    const seq: PhaseState['id'][] = ['spec', 'design', 'tasks', 'dev'];
    const delays = [600, 1500, 2400, 3300];
    const timers: number[] = [];
    seq.forEach((id, idx) => {
      const t1 = window.setTimeout(() => {
        if (cancelled) return;
        setPhases((prev) => prev.map((p) => p.id === id ? { ...p, state: 'running' as const } : p));
      }, delays[idx]);
      const t2 = window.setTimeout(() => {
        if (cancelled) return;
        setPhases((prev) => prev.map((p) => p.id === id
          ? { ...p, state: 'pending-accept' as const, preview: `## ${p.title}\n\n(模拟生成的 ${p.id} 草案...)` }
          : p));
      }, delays[idx] + 600);
      const t3 = window.setTimeout(() => {
        if (cancelled) return;
        setPhases((prev) => prev.map((p) => p.id === id ? { ...p, state: 'done' as const } : p));
      }, delays[idx] + 900);
      timers.push(t1, t2, t3);
    });
    return () => {
      cancelled = true;
      timers.forEach((t) => window.clearTimeout(t));
    };
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
          当前是 MVP：阶段是 canned 演示。下一轮接到 SsdDriver 上，让 daemon
          真的驱动 running → 待确认 → 已完成 流转。
        </small>
      </footer>
    </section>
  );
}