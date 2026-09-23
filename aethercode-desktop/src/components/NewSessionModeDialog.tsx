import { useEffect } from 'react';
import { useStore } from '../store';
import './NewSessionModeDialog.css';

/**
 * R331: mode picker that fires before a new session is
 * created. Replaces R321's keyword auto-detect — instead of
 * inferring SDD-mode from chat input patterns, the user
 * explicitly picks 普通 / SDD 规范化 / Workflow at
 * session-creation time.
 *
 * Driven by `useStore.pendingNewSession` — when the property is
 * non-null, the dialog mounts. The picker resolves by calling
 * `createNewSession({ mode })` with the chosen mode, or by
 * setting `pendingNewSession: null` on cancel.
 *
 * Mount this once at the App tree root. It manages its own
 * visibility via the store flag, so no `open` prop is needed.
 */
export function NewSessionModeDialog() {
  const pending = useStore((s) => s.pendingNewSession);
  const createNewSession = useStore((s) => s.createNewSession);

  // Close on Esc.
  useEffect(() => {
    if (!pending) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        useStore.setState({ pendingNewSession: null });
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [pending]);

  if (!pending) return null;

  const pick = (mode: 'normal' | 'sdd' | 'workflow') => {
    // Re-call createNewSession with the chosen mode. The
    // branch in the store sees opts.mode and skips re-opening
    // the picker.
    void createNewSession({ mode });
  };
  const cancel = () => {
    useStore.setState({ pendingNewSession: null });
  };

  return (
    <div className="new-session-mode-dialog-backdrop" onClick={cancel} role="presentation">
      <div
        className="new-session-mode-dialog"
        role="dialog"
        aria-label="选择新会话模式"
        data-testid="new-session-mode-dialog"
        onClick={(e) => e.stopPropagation()}
      >
        <header className="new-session-mode-dialog-header">
          <h2>新建会话</h2>
          <p className="new-session-mode-dialog-subtitle">
            选择这个会话的执行模式。每个会话独立绑定一个模式，整个生命周期保持一致。
          </p>
        </header>
        <ul className="new-session-mode-dialog-list">
          <li>
            <button
              type="button"
              className="new-session-mode-option"
              onClick={() => pick('normal')}
              data-testid="new-session-mode-option-normal"
              data-mode="normal"
            >
              <div className="new-session-mode-option-title">普通</div>
              <div className="new-session-mode-option-desc">
                直接对话 + agentic loop。适合小改动、问答、调试，不走规范化流程。
              </div>
            </button>
          </li>
          <li>
            <button
              type="button"
              className="new-session-mode-option new-session-mode-option-sdd"
              onClick={() => pick('sdd')}
              data-testid="new-session-mode-option-sdd"
              data-mode="sdd"
            >
              <div className="new-session-mode-option-title">SDD 规范化</div>
              <div className="new-session-mode-option-desc">
                走 Spec Kit 8 阶段（项目原则 → 需求 → 澄清 → 设计 → 一致性 → 任务 → 实现 → 收敛）。每个阶段产出独立文件，逐阶段确认。
              </div>
            </button>
          </li>
          <li>
            <button
              type="button"
              className="new-session-mode-option"
              onClick={() => pick('workflow')}
              data-testid="new-session-mode-option-workflow"
              data-mode="workflow"
            >
              <div className="new-session-mode-option-title">Workflow</div>
              <div className="new-session-mode-option-desc">
                绑定一个已有的 Workflow 模板，按模板步骤序列推进。
              </div>
            </button>
          </li>
        </ul>
        <footer className="new-session-mode-dialog-footer">
          <button
            type="button"
            className="new-session-mode-cancel"
            onClick={cancel}
            data-testid="new-session-mode-cancel"
          >
            取消
          </button>
        </footer>
      </div>
    </div>
  );
}