// Phase 4.2 (T-4-11): SummaryFooter.
//
// The mandatory "## Summary" block at the end of every
// assistant turn. Per the spec:
//
//   > every assistant turn ends with a 1-3 line summary
//   > that names what the assistant did and what it intends
//   > to do next. This summary is **never collapsed**, and is
//   > the user's quick way to know what happened without
//   > expanding cards.
//
// Implementation: a regex over the assistant message body
// that extracts the `## Summary` heading and its body. The
// regex is intentionally permissive:
//
//   - matches `## Summary` (case-insensitive, optional
//     trailing colon / space / newline)
//   - captures everything up to the next `## ` heading, the
//     end of the message, or a `---` horizontal rule
//   - trims trailing whitespace
//
// If no summary block is found, the component renders an
// empty card with a "⚠ auto-summary pending" badge. The
// TranscriptEnricher (T-4-17) wires the fallback: when the
// daemon emits a `summary_missing` event, the enricher
// re-asks the model to summarise and re-renders the card.

import { useMemo } from 'react';

export interface SummaryFooterProps {
  /** Full assistant message body. */
  body: string;
  /** When true, the engine emitted a `summary_missing`
   *  event and the auto-summary is in flight. The card
   *  shows a "pending" badge so the user knows it's
   *  coming. */
  isPending?: boolean;
  /** When true, the engine tried and failed to produce
   *  an auto-summary. The card shows an "auto-summary
   *  failed" badge and falls back to the last assistant
   *  message tail. */
  fallbackFailed?: boolean;
  /** Optional className passed through to the wrapper. */
  className?: string;
}

const SUMMARY_HEADING_RE = /^#{1,6}\s*summary[^\n]*$/im;
const NEXT_HEADING_RE = /\n#{1,6}\s+[^\n]+/g;
const HR_RE = /\n-{3,}\s*(\n|$)/g;

export interface ParsedSummary {
  /** The text after the `## Summary` heading. */
  text: string;
  /** Where in the body the summary was extracted from. */
  startIndex: number;
  /** Where the summary ended (so the rest of the body
   *  is "post-summary" content). */
  endIndex: number;
  /** When true, no `## Summary` was found. */
  missing: boolean;
}

export function extractSummary(body: string): ParsedSummary {
  if (!body) return { text: '', startIndex: 0, endIndex: 0, missing: true };
  const match = SUMMARY_HEADING_RE.exec(body);
  if (!match) return { text: '', startIndex: 0, endIndex: 0, missing: true };
  const start = match.index + match[0].length;
  // Search for the next heading or horizontal rule from
  // `start` onwards.
  const tail = body.slice(start);
  const nextHeading = NEXT_HEADING_RE.exec(tail);
  const nextHr = HR_RE.exec(tail);
  let end: number;
  if (!nextHeading && !nextHr) {
    end = body.length;
  } else {
    const nexts = [nextHeading, nextHr]
      .filter(Boolean)
      .map((m) => (m as RegExpExecArray).index);
    end = start + Math.min(...nexts);
  }
  const text = body.slice(start, end).replace(/^\s+|\s+$/g, '');
  return { text, startIndex: start, endIndex: end, missing: false };
}

/** A short, 1-line preview of the last user message; used
 *  by the fallback "what the assistant did" hint when no
 *  summary is present. */
function lastLine(body: string): string {
  if (!body) return '';
  const lines = body.trim().split(/\r?\n/).filter((l) => l.trim().length > 0);
  if (lines.length === 0) return '';
  const last = lines[lines.length - 1].trim();
  return last.length > 120 ? last.slice(0, 117) + '…' : last;
}

export function SummaryFooter({
  body,
  isPending = false,
  fallbackFailed = false,
  className,
}: SummaryFooterProps) {
  const parsed = useMemo(() => extractSummary(body), [body]);
  const fallbackTail = useMemo(() => lastLine(body), [body]);

  // Status badge text. The "pending" state takes priority
  // because it's the most actionable for the user.
  const badge = (() => {
    if (isPending) return { kind: 'pending' as const, text: '⚠ auto-summary pending' };
    if (parsed.missing) {
      return fallbackFailed
        ? { kind: 'failed' as const, text: '⚠ auto-summary failed' }
        : { kind: 'missing' as const, text: '⚠ no summary' };
    }
    return null;
  })();

  return (
    <div
      className={[
        'summary-footer',
        parsed.missing ? 'summary-footer-missing' : '',
        className ?? '',
      ].filter(Boolean).join(' ')}
      role="note"
      aria-label="Assistant turn summary"
    >
      <div className="summary-footer-head">
        <span className="summary-footer-icon" aria-hidden>📝</span>
        <span className="summary-footer-title">Summary</span>
        {badge && <span className={`summary-footer-badge summary-footer-badge-${badge.kind}`}>{badge.text}</span>}
      </div>
      <div className="summary-footer-body">
        {parsed.missing ? (
          // No `## Summary` was found. The TranscriptEnricher
          // will fire a `summary_missing` request and either
          // patch the body (success) or set `fallbackFailed`
          // (failure). In the failure case, we show the
          // tail of the message so the user still has
          // something to read.
          fallbackFailed ? (
            <span className="summary-footer-fallback">{fallbackTail || '(no content)'}</span>
          ) : (
            <span className="summary-footer-pending">Awaiting summary…</span>
          )
        ) : (
          <span className="summary-footer-text">{parsed.text}</span>
        )}
      </div>
    </div>
  );
}
