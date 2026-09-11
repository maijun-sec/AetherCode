// Phase 4.2 (T-4-13): FileDiffCard.
//
// Per the spec (§12.2): file diff cards are **collapsed by
// default**, expandable to show the full diff. This component
// renders the diff as a side-by-side or inline view (inline
// for now — the desktop has plenty of horizontal space; a
// side-by-side mode is a future enhancement).
//
// The diff is rendered as a series of lines, each with a
// type prefix (`+` / `-` / ` `). The user can copy the full
// diff via the toolbar.
//
// Visual model (collapsed):
//   ┌──────────────────────────────────────────────┐
//   │ 📄 src/foo.ts (+12 −3)                 ▸     │
//   └──────────────────────────────────────────────┘
//
// Visual model (expanded):
//   ┌──────────────────────────────────────────────┐
//   │ 📄 src/foo.ts (+12 −3)                 ▾     │
//   │ @@ -1,3 +1,4 @@                              │
//   │  unchanged line                              │
//   │ -removed line                                │
//   │ +added line                                  │
//   │ +another added                               │
//   │ [copy]                                       │
//   └──────────────────────────────────────────────┘

import { useMemo, useState, useCallback } from 'react';

export interface FileDiffCardProps {
  /** Path of the file being changed. */
  filePath: string;
  /** Raw unified diff. Optional — when omitted, only the
   *  summary header is rendered. */
  diff?: string;
  /** Pre-computed line counts. When provided, the
   *  component skips the diff parse. */
  additions?: number;
  deletions?: number;
  /** When true, the card is initially expanded. */
  defaultExpanded?: boolean;
  /** Fired when the user expands/collapses. */
  onToggle?: (expanded: boolean) => void;
}

interface DiffLine {
  kind: 'add' | 'del' | 'ctx' | 'meta';
  text: string;
}

/** Parse a unified diff into typed lines. Tolerant of
 *  malformed input — the renderer falls back to "ctx"
 *  for lines that don't match the standard prefixes. */
function parseDiff(diff: string): DiffLine[] {
  if (!diff) return [];
  return diff.split(/\r?\n/).map((line) => {
    if (line.startsWith('+++') || line.startsWith('---')) {
      return { kind: 'meta', text: line };
    }
    if (line.startsWith('@@')) return { kind: 'meta', text: line };
    if (line.startsWith('+')) return { kind: 'add', text: line };
    if (line.startsWith('-')) return { kind: 'del', text: line };
    return { kind: 'ctx', text: line };
  });
}

/** Compute line counts from a diff (when the caller
 *  didn't pre-compute them). */
function countDiff(diff: string): { additions: number; deletions: number } {
  if (!diff) return { additions: 0, deletions: 0 };
  let additions = 0;
  let deletions = 0;
  for (const line of diff.split(/\r?\n/)) {
    if (line.startsWith('+++') || line.startsWith('---')) continue;
    if (line.startsWith('+')) additions++;
    else if (line.startsWith('-')) deletions++;
  }
  return { additions, deletions };
}

export function FileDiffCard({
  filePath,
  diff,
  additions: addProp,
  deletions: delProp,
  defaultExpanded = false,
  onToggle,
}: FileDiffCardProps) {
  const [expanded, setExpanded] = useState(defaultExpanded);
  const [copied, setCopied] = useState(false);

  const computedCounts = useMemo(() => countDiff(diff ?? ''), [diff]);
  const additions = addProp ?? computedCounts.additions;
  const deletions = delProp ?? computedCounts.deletions;
  const lines = useMemo(() => parseDiff(diff ?? ''), [diff]);
  const fileName = useMemo(() => {
    const parts = filePath.split('/');
    return parts[parts.length - 1] || filePath;
  }, [filePath]);

  const handleToggle = useCallback(() => {
    const next = !expanded;
    setExpanded(next);
    onToggle?.(next);
  }, [expanded, onToggle]);

  const handleCopy = useCallback(async () => {
    if (!diff) return;
    try {
      await navigator.clipboard.writeText(diff);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1_500);
    } catch {
      // Clipboard API may be unavailable in older browsers;
      // silently fall back to no-op (the user can still
      // highlight + ⌘C).
    }
  }, [diff]);

  return (
    <div
      className={['file-diff-card', expanded ? 'file-diff-card-expanded' : ''].filter(Boolean).join(' ')}
      role="group"
      aria-label={`File diff: ${filePath}`}
    >
      <button
        type="button"
        className="file-diff-card-head"
        onClick={handleToggle}
        aria-expanded={expanded}
      >
        <span className="file-diff-card-icon" aria-hidden>📄</span>
        <span className="file-diff-card-path" title={filePath}>{fileName}</span>
        {additions > 0 && <span className="file-diff-card-add">+{additions}</span>}
        {deletions > 0 && <span className="file-diff-card-del">−{deletions}</span>}
        <span className="file-diff-card-toggle" aria-hidden>{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && (
        <div className="file-diff-card-body">
          {lines.length === 0 ? (
            <div className="file-diff-card-empty">no diff body</div>
          ) : (
            <pre className="file-diff-card-pre" tabIndex={0}>
              {lines.map((line, i) => (
                <div key={i} className={`file-diff-card-line file-diff-card-line-${line.kind}`}>
                  {line.text || ' '}
                </div>
              ))}
            </pre>
          )}
          <div className="file-diff-card-toolbar">
            <button
              type="button"
              className="file-diff-card-copy"
              onClick={handleCopy}
              disabled={!diff}
              aria-label="Copy full diff to clipboard"
            >
              {copied ? '✓ copied' : 'copy'}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
