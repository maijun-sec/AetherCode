// Phase 4.2 (T-4-14): PlanCard.
//
// Inline plan / TODO card. The assistant emits a structured
// plan as part of its reasoning (the LLM's prompt-engineering
// trick: a `## Plan` heading followed by a bulleted list of
// steps). The card surfaces the plan in the chat transcript
// at the position the assistant inserted it.
//
// Visual model (collapsed):
//   ┌──────────────────────────────────────────────┐
//   │ 🗒 Plan · 4 steps                       ▸    │
//   └──────────────────────────────────────────────┘
//
// Visual model (expanded):
//   ┌──────────────────────────────────────────────┐
//   │ 🗒 Plan · 4 steps                       ▾    │
//   │ 1. Read the README                           │
//   │ 2. Add the login form                        │
//   │ 3. Wire up the API                           │
//   │ 4. Write a test                              │
//   └──────────────────────────────────────────────┘

import { useState, useMemo } from 'react';

export interface PlanCardProps {
  /** Raw plan body (one step per line, may be numbered or
   *  bulleted). */
  body: string;
  /** Pre-parsed plan steps. When provided, the component
   *  skips the regex parse. */
  steps?: string[];
  /** Optional title override. The card derives a default
   *  ("Plan · N steps") when omitted. */
  title?: string;
  /** When true, the card is initially expanded. */
  defaultExpanded?: boolean;
  /** Fired on expand / collapse. */
  onToggle?: (expanded: boolean) => void;
  /** When true, render with a "done" treatment (the
   *  plan finished). */
  done?: boolean;
}

/** Parse a `## Plan` body into steps. Strips leading
 *  numbers (`1. `) and bullets (`- `); tolerates blank
 *  lines and `**bold**` markers. */
export function parsePlanSteps(body: string): string[] {
  if (!body) return [];
  const out: string[] = [];
  for (const line of body.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    if (trimmed.startsWith('#')) continue; // heading
    // Strip a leading "N. " or "- " or "* " marker.
    const stripped = trimmed
      .replace(/^(\d+)[.)]\s+/, '')
      .replace(/^[-*]\s+/, '')
      .replace(/^\[[ xX]\]\s+/, ''); // task-list marker
    if (stripped) out.push(stripped);
  }
  return out;
}

export function PlanCard({
  body,
  steps: stepsProp,
  title,
  defaultExpanded = true,
  onToggle,
  done = false,
}: PlanCardProps) {
  const [expanded, setExpanded] = useState(defaultExpanded);
  const steps = useMemo(() => stepsProp ?? parsePlanSteps(body), [stepsProp, body]);
  const head = title ?? `Plan · ${steps.length} step${steps.length === 1 ? '' : 's'}`;

  const handleToggle = () => {
    const next = !expanded;
    setExpanded(next);
    onToggle?.(next);
  };

  return (
    <div
      className={[
        'plan-card',
        expanded ? 'plan-card-expanded' : '',
        done ? 'plan-card-done' : '',
      ].filter(Boolean).join(' ')}
      role="group"
      aria-label="Plan"
    >
      <button
        type="button"
        className="plan-card-head"
        onClick={handleToggle}
        aria-expanded={expanded}
      >
        <span className="plan-card-icon" aria-hidden>🗒</span>
        <span className="plan-card-title">{head}</span>
        {done && <span className="plan-card-done-pill">done</span>}
        <span className="plan-card-toggle" aria-hidden>{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && (
        <ol className="plan-card-steps">
          {steps.length === 0 ? (
            <li className="plan-card-step plan-card-step-empty">no plan steps</li>
          ) : (
            steps.map((s, i) => (
              <li key={i} className="plan-card-step">
                <span className="plan-card-step-num">{i + 1}.</span>
                <span className="plan-card-step-text">{s}</span>
              </li>
            ))
          )}
        </ol>
      )}
    </div>
  );
}
