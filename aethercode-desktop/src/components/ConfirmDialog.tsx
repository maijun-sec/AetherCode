/**
 * T-254 / T-450 / spec.md §3.8 / design.md §5.3:
 * the desktop (Tauri/Web) drop-down consent dialog.
 *
 * <p>Same 10-option matrix the TUI renders (T-250), but in
 * a DOM-friendly <select> drop-down instead of an arrow-key
 * list. Used as the canonical "modal that blocks the
 * underlying TUI command until decision" surface for the
 * desktop app.
 *
 * <p>Layout (matches {@code aethercode-permission/prompt/ConsentOptionMatrix.java}):
 * <pre>
 *   1. Allow (this once)
 *   2. Deny (this once)
 *   3. Allow for the rest of this session
 *   4. Deny for the rest of this session
 *   5. Allow for this project, future sessions
 *   6. Deny for this project, future sessions
 *   7. Allow for me, in all projects
 *   8. Deny for me, in all projects
 *   9. Allow all &lt;sub-category&gt; (this project)   [wildcard]
 *  10. Deny all &lt;sub-category&gt; (this project)    [wildcard]
 * </pre>
 *
 * <p>Options 9-10 are only rendered when the categorizer
 * produced a sub-category. The component does NOT decide
 * which options to show — the {@code options} prop carries
 * the full matrix built by the Java side. The component
 * is a pure renderer + click handler.
 */

import { useState, useEffect, useRef } from 'react';
import './ConfirmDialog.css';

export type RiskLevel = 'low' | 'medium' | 'high';

export type OptionKind = 'once' | 'standard' | 'wildcard';

export type GrantScope = 'session' | 'project' | 'user';

export type GrantDecision = 'allow' | 'deny';

export interface ConsentOption {
  index: number;
  kind: OptionKind;
  label: string;
  hotkey: string;
  scope: GrantScope;
  decision: GrantDecision;
  wildcardSubCategory?: string;
}

export interface CategoryResult {
  categories: string[];
  risk: RiskLevel;
  matchedRules: string[];
}

export interface ConfirmDialogProps {
  /** The categorisation result (risk + categories + matched rules). */
  category: CategoryResult;
  /** The 10-option matrix built server-side. */
  options: ConsentOption[];
  /** User-facing call summary, e.g. `bash "rm -rf /"`. */
  callSummary: string;
  /** Fired when the user picks an option. */
  onSelect: (option: ConsentOption) => void;
  /** Fired when the user dismisses the dialog without picking. */
  onCancel: () => void;
  /** Cosmetic project id (used in the option label). */
  projectId?: string;
}

const RISK_COLOR: Record<RiskLevel, string> = {
  low: '#2ea043',
  medium: '#d29922',
  high: '#f85149',
};

const RISK_LABEL: Record<RiskLevel, string> = {
  low: 'LOW',
  medium: 'MEDIUM',
  high: 'HIGH',
};

/**
 * Modal drop-down. The dialog blocks the page until the
 * user picks an option (a click on a button in the
 * standard + once columns) or dismisses it (the
 * "Don't ask again for this category" toggle + cancel).
 *
 * <p>The visual is:
 * <ol>
 *   <li>A header band: tool summary, risk level, categories.</li>
 *   <li>A "Don't ask again for this category" checkbox.
 *       Toggling it changes the default-selected option to the
 *       matching wildcard (option 9 / 10) so a single click
 *       commits the user's intent.</li>
 *   <li>A two-column button grid: 8 standard buttons in
 *       2×4 layout (allow / deny on the left, scope on the
 *       right).</li>
 *   <li>Cancel / Esc dismisses the dialog without committing.
 * </ol>
 */
export function ConfirmDialog({
  category,
  options,
  callSummary,
  onSelect,
  onCancel,
  projectId,
}: ConfirmDialogProps) {
  const [highlight, setHighlight] = useState(0);
  const [dontAskAgain, setDontAskAgain] = useState(false);
  const dialogRef = useRef<HTMLDivElement | null>(null);

  // Pick a sensible default selection: the first
  // matching wildcard option when "don't ask again" is
  // on, otherwise the first standard allow (so Enter
  // doesn't accidentally deny).
  useEffect(() => {
    if (dontAskAgain) {
      const wildcard = options.find((o) => o.kind === 'wildcard');
      if (wildcard) {
        const idx = options.findIndex((o) => o.index === wildcard.index);
        if (idx >= 0) setHighlight(idx);
        return;
      }
    }
    // Default: first allow-once (option 1) so a single
    // Enter on a freshly opened dialog allows the call.
    const allow = options.find((o) => o.decision === 'allow');
    if (allow) {
      const idx = options.findIndex((o) => o.index === allow.index);
      if (idx >= 0) setHighlight(idx);
    }
  }, [dontAskAgain, options]);

  // Esc dismisses; Enter confirms the highlight. Capture
  // at the document level so the dialog works even when
  // the user hasn't focused the wrapper yet.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault();
        onCancel();
        return;
      }
      if (e.key === 'Enter') {
        e.preventDefault();
        const opt = options[highlight];
        if (opt) onSelect(opt);
        return;
      }
      if (e.key === 'ArrowDown' || e.key === 'j') {
        e.preventDefault();
        setHighlight((h) => (h + 1) % options.length);
        return;
      }
      if (e.key === 'ArrowUp' || e.key === 'k') {
        e.preventDefault();
        setHighlight((h) => (h - 1 + options.length) % options.length);
        return;
      }
    }
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [highlight, options, onSelect, onCancel]);

  // Autofocus the dialog so the keyboard handler takes
  // effect immediately.
  useEffect(() => {
    dialogRef.current?.focus();
  }, []);

  const riskColor = RISK_COLOR[category.risk] ?? '#d29922';
  const riskLabel = RISK_LABEL[category.risk] ?? category.risk.toUpperCase();

  return (
    <div className="confirm-dialog-backdrop" role="dialog" aria-modal="true" aria-label="Permission required">
      <div
        ref={dialogRef}
        className="confirm-dialog"
        tabIndex={-1}
        style={{ borderColor: riskColor }}
      >
        <div className="confirm-dialog-header" style={{ background: riskColor }}>
          <span className="confirm-dialog-header-icon">⚠</span>
          <span className="confirm-dialog-header-title">CONFIRM REQUIRED</span>
          <span className="confirm-dialog-header-risk">[{riskLabel} risk]</span>
        </div>

        <div className="confirm-dialog-body">
          <div className="confirm-dialog-row">
            <span className="confirm-dialog-label">Tool:</span>
            <span className="confirm-dialog-value confirm-dialog-mono">{callSummary}</span>
          </div>
          <div className="confirm-dialog-row">
            <span className="confirm-dialog-label">Categories:</span>
            <span className="confirm-dialog-value">
              {category.categories.length > 0
                ? category.categories.join(', ')
                : '(uncategorized)'}
            </span>
          </div>
          {projectId ? (
            <div className="confirm-dialog-row">
              <span className="confirm-dialog-label">Project:</span>
              <span className="confirm-dialog-value">{projectId}</span>
            </div>
          ) : null}

          <label className="confirm-dialog-checkbox">
            <input
              type="checkbox"
              checked={dontAskAgain}
              onChange={(e) => setDontAskAgain(e.target.checked)}
            />
            <span>Don't ask again for this category</span>
          </label>

          <div className="confirm-dialog-grid">
            {options.map((opt, i) => {
              const isAllow = opt.decision === 'allow';
              const isHighlighted = i === highlight;
              const cls = [
                'confirm-dialog-button',
                isAllow ? 'confirm-dialog-button-allow' : 'confirm-dialog-button-deny',
                isHighlighted ? 'confirm-dialog-button-highlight' : '',
                opt.kind === 'wildcard' ? 'confirm-dialog-button-wildcard' : '',
                opt.kind === 'once' ? 'confirm-dialog-button-once' : '',
              ].filter(Boolean).join(' ');
              return (
                <button
                  key={opt.index}
                  className={cls}
                  onClick={() => onSelect(opt)}
                  onMouseEnter={() => setHighlight(i)}
                  type="button"
                  data-option-index={opt.index}
                >
                  <span className="confirm-dialog-button-num">{opt.index}.</span>
                  <span className="confirm-dialog-button-label">{opt.label}</span>
                  <span className="confirm-dialog-button-hotkey">[{opt.hotkey}]</span>
                </button>
              );
            })}
          </div>

          <div className="confirm-dialog-help">
            <details>
              <summary>Why is this risky?</summary>
              {category.matchedRules.length === 0 ? (
                <p className="confirm-dialog-help-empty">
                  No specific rule matched — default risk applied.
                </p>
              ) : (
                <ul className="confirm-dialog-help-rules">
                  {category.matchedRules.map((r, i) => (
                    <li key={i}>{r}</li>
                  ))}
                </ul>
              )}
            </details>
          </div>
        </div>

        <div className="confirm-dialog-footer">
          <button
            type="button"
            className="confirm-dialog-cancel"
            onClick={onCancel}
          >
            Cancel (Esc)
          </button>
        </div>
      </div>
    </div>
  );
}

export default ConfirmDialog;
