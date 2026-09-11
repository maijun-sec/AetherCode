// Phase 4.2 (T-4-12): ToolCallCard.
//
// Per the spec (§12.2): tool call cards are **collapsed by
// default**, expandable to show tool name, category, risk
// level, args, result, and the user's consent choice (if
// any).
//
// State is local (useState); we don't bother lifting it
// to the parent — the expanded state is purely visual
// and ephemeral. A future "audit log" feature could lift
// it but that's not in this round.
//
// Visual model (collapsed):
//   ┌──────────────────────────────────────────────┐
//   │ 🔧 read_file · file_read · low risk   ▸      │
//   └──────────────────────────────────────────────┘
//
// Visual model (expanded):
//   ┌──────────────────────────────────────────────┐
//   │ 🔧 read_file · file_read · low risk   ▾      │
//   │ ┌────────────────────────────────────────┐   │
//   │ │ Args                                  │   │
//   │ │   file_path: /Users/me/foo.ts         │   │
//   │ │ Result                                 │   │
//   │ │   <first 280 chars of the output>     │   │
//   │ │ Consent: ✓ allow-once                 │   │
//   │ └────────────────────────────────────────┘   │
//   └──────────────────────────────────────────────┘

import { useState, useMemo } from 'react';

export type ToolRiskLevel = 'low' | 'medium' | 'high' | 'critical';

export interface ToolCallCardProps {
  /** Tool name (e.g. `read_file`, `bash`). */
  toolName: string;
  /** Category the daemon tagged the call with. */
  category?: string;
  /** Risk level the consent prompt asked the user about. */
  riskLevel?: ToolRiskLevel;
  /** Args passed to the tool. Rendered as a key:value
   *  list. */
  args?: unknown;
  /** Tool result. Free-form text. Truncated at 280 chars
   *  in the collapsed view; full text in expanded. */
  result?: string;
  /** True when the result is an error message. */
  resultIsError?: boolean;
  /** User's consent choice (if a prompt was shown). */
  consentChoice?: 'allow-once' | 'deny-once' | 'allow-session' | 'deny-session' | 'allow-project' | 'deny-project' | 'allow-user' | 'deny-user' | 'allow-category-project' | 'deny-category-project';
  /** Duration in ms (for the "took 0.4s" footer). */
  durationMs?: number;
  /** When true, the card is initially expanded (e.g.
   *  user is searching for this tool call). */
  defaultExpanded?: boolean;
  /** Fired when the user collapses/expands. */
  onToggle?: (expanded: boolean) => void;
}

const RISK_COLORS: Record<ToolRiskLevel, string> = {
  low: 'var(--risk-low, #4ade80)',
  medium: 'var(--risk-medium, #fbbf24)',
  high: 'var(--risk-high, #fb923c)',
  critical: 'var(--risk-critical, #f87171)',
};

const CONSENT_LABELS: Record<NonNullable<ToolCallCardProps['consentChoice']>, string> = {
  'allow-once': '✓ allow (once)',
  'deny-once': '✗ deny (once)',
  'allow-session': '✓ allow (session)',
  'deny-session': '✗ deny (session)',
  'allow-project': '✓ allow (project)',
  'deny-project': '✗ deny (project)',
  'allow-user': '✓ allow (user)',
  'deny-user': '✗ deny (user)',
  'allow-category-project': '✓ allow category (project)',
  'deny-category-project': '✗ deny category (project)',
};

/** Render an args object as a key:value list. Nested
 *  objects and arrays are stringified with a cap. */
function formatArgs(args: unknown): Array<[string, string]> {
  if (!args) return [];
  if (typeof args !== 'object') return [['value', String(args)]];
  const obj = args as Record<string, unknown>;
  return Object.entries(obj).map(([k, v]) => {
    let s: string;
    if (v == null) s = '';
    else if (typeof v === 'string') s = v;
    else if (typeof v === 'number' || typeof v === 'boolean') s = String(v);
    else {
      try {
        s = JSON.stringify(v);
        if (s.length > 200) s = s.slice(0, 197) + '…';
      } catch {
        s = String(v);
      }
    }
    return [k, s];
  });
}

function formatDuration(ms?: number): string {
  if (ms == null) return '';
  if (ms < 1_000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1_000).toFixed(1)}s`;
  return `${(ms / 60_000).toFixed(1)}m`;
}

const RESULT_PREVIEW = 280;

export function ToolCallCard({
  toolName,
  category,
  riskLevel,
  args,
  result,
  resultIsError,
  consentChoice,
  durationMs,
  defaultExpanded = false,
  onToggle,
}: ToolCallCardProps) {
  const [expanded, setExpanded] = useState(defaultExpanded);
  const argsList = useMemo(() => formatArgs(args), [args]);
  const resultPreview = useMemo(() => {
    if (!result) return '';
    if (result.length <= RESULT_PREVIEW) return result;
    return result.slice(0, RESULT_PREVIEW) + '…';
  }, [result]);
  const resultFull = result ?? '';
  const risk = riskLevel ?? 'low';
  const riskColor = RISK_COLORS[risk];

  const handleToggle = () => {
    const next = !expanded;
    setExpanded(next);
    onToggle?.(next);
  };

  return (
    <div
      className={[
        'tool-call-card',
        expanded ? 'tool-call-card-expanded' : '',
        resultIsError ? 'tool-call-card-error' : '',
      ].filter(Boolean).join(' ')}
      role="group"
      aria-label={`Tool call: ${toolName}`}
    >
      <button
        type="button"
        className="tool-call-card-head"
        onClick={handleToggle}
        aria-expanded={expanded}
      >
        <span className="tool-call-card-icon" aria-hidden>🔧</span>
        <span className="tool-call-card-name">{toolName}</span>
        {category && <span className="tool-call-card-category">{category}</span>}
        <span
          className={`tool-call-card-risk tool-call-card-risk-${risk}`}
          style={{ borderColor: riskColor, color: riskColor }}
          title={`Risk: ${risk}`}
        >{risk}</span>
        <span className="tool-call-card-toggle" aria-hidden>{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && (
        <div className="tool-call-card-body">
          {argsList.length > 0 && (
            <div className="tool-call-card-section">
              <div className="tool-call-card-section-title">Args</div>
              <dl className="tool-call-card-args">
                {argsList.map(([k, v]) => (
                  <div key={k} className="tool-call-card-arg-row">
                    <dt className="tool-call-card-arg-key">{k}</dt>
                    <dd className="tool-call-card-arg-val">{v || <em>—</em>}</dd>
                  </div>
                ))}
              </dl>
            </div>
          )}
          {result !== undefined && result !== null && (
            <div className="tool-call-card-section">
              <div className="tool-call-card-section-title">
                {resultIsError ? 'Error' : 'Result'}
                {durationMs != null && <span className="tool-call-card-duration">took {formatDuration(durationMs)}</span>}
              </div>
              <pre className={['tool-call-card-result', resultIsError ? 'tool-call-card-result-error' : ''].filter(Boolean).join(' ')}>
                {resultFull}
              </pre>
              {resultFull.length > RESULT_PREVIEW && (
                <details className="tool-call-card-result-expand">
                  <summary>show preview only</summary>
                  <pre className="tool-call-card-result">{resultPreview}</pre>
                </details>
              )}
            </div>
          )}
          {consentChoice && (
            <div className="tool-call-card-section tool-call-card-consent">
              <div className="tool-call-card-section-title">Consent</div>
              <span className="tool-call-card-consent-pill">{CONSENT_LABELS[consentChoice]}</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
