import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * markdown report export. The R123
 * JSONL export is for grep; the R125 markdown
 * report is for *reading*. Same write path
 * (Tauri save dialog + write_text_file
 * command), different content shape (a
 * formatted report with summary, percentiles,
 * and a top-errors table) and a different
 * audience — the user who wants to *understand*
 * the activity, not parse it.
 *
 * <p>Tests pin: the panel button shape, the
 * helper that builds the markdown body
 * (deterministic, no I/O), the section
 * structure, and the percentile math.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R125: RpcDiagnosticsPanel Report button', () => {
  const tsxSrc = readFileSync(join(root, 'src', 'components', 'RpcDiagnosticsPanel.tsx'), 'utf-8');

  it('renders a Report button next to Export', () => {
    // The two buttons sit side-by-side in
    // the footer. Same disabled gate (the
    // filtered view being empty) so a
    // user who can't export JSONL can't
    // export a report either.
    expect(tsxSrc).toContain('className="rpc-diag-export-report"');
    expect(tsxSrc).toMatch(/>\s*Report\s*<\/button>/);
  });

  it('Report is disabled when the filtered view is empty', () => {
    expect(tsxSrc).toMatch(/onClick=\{\(\) => void exportMarkdownReport\(filtered, setExportStatus\)\}[\s\S]*?disabled=\{filtered\.length === 0\}/m);
  });

  it('exportMarkdownReport is wired to write_text_file + OS save dialog', () => {
    // Same plumbing as R123 — the Tauri
    // command + save dialog (already
    // tested by R123) — but the default
    // filename has a .md extension and
    // the filter shows "Markdown".
    expect(tsxSrc).toContain('async function exportMarkdownReport(');
    expect(tsxSrc).toContain("invoke<void>('write_text_file', { path, contents: body })");
  });

  it('save dialog filters by .md extension', () => {
    // The dialog's filter shows "Markdown"
    // first; the .jsonl filter from R123
    // doesn't appear here.
    const block = tsxSrc.match(/title:\s*'Export RPC report[\s\S]*?\}\);/);
    expect(block).toBeTruthy();
    expect(block![0]).toContain("name: 'Markdown'");
    expect(block![0]).toContain("extensions: ['md']");
  });

  it('defaultPath uses a timestamped .md filename', () => {
    // Same YYYY-MM-DD_HH-MM-SS scheme as
    // R123's .jsonl, so a user exporting
    // both gets a coherent pair in their
    // downloads folder.
    expect(tsxSrc).toContain('aethercode-rpc-report-${stamp}.md');
  });
});

describe('R125: renderMarkdownReport builds the expected sections', () => {
  const tsxSrc = readFileSync(join(root, 'src', 'components', 'RpcDiagnosticsPanel.tsx'), 'utf-8');

  it('declares a pure renderMarkdownReport function (testable in isolation)', () => {
    // The I/O wrapper is split from the
    // content builder. The test pins the
    // literal `function renderMarkdownReport`
    // so a future refactor that inlines the
    // body back into exportMarkdownReport
    // gets caught.
    expect(tsxSrc).toMatch(/function renderMarkdownReport\(events: RpcEvent\[\]\): string/);
  });

  it('produces a top-level H1 with the report title', () => {
    // The header is the first thing the
    // user reads when they open the .md in
    // a preview. "AetherCode RPC report"
    // is self-explanatory.
    const block = tsxSrc.match(/function renderMarkdownReport\([\s\S]*?^\s{2}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toContain("lines.push('# AetherCode RPC report')");
  });

  it('emits a Summary section with OK/Error split', () => {
    // The summary is the first analytical
    // section. The OK percentage gives
    // the user a one-glance health read.
    expect(tsxSrc).toContain("lines.push('## Summary')");
    expect(tsxSrc).toMatch(/lines\.push\(`- \*\*OK\*\*: \$\{okCount\}/);
    expect(tsxSrc).toMatch(/lines\.push\(`- \*\*Errors\*\*: \$\{errCount\}/);
  });

  it('emits a Duration section with mean + p50/p95/p99 percentiles', () => {
    // The p50 is the median response time;
    // p95 / p99 are the tail latencies.
    // The exact-percentile math runs on
    // the full sorted duration array, not
    // a sample.
    expect(tsxSrc).toContain("lines.push('## Duration')");
    expect(tsxSrc).toContain("lines.push(`- **p50**: ${formatDuration(p(0.50))}`)");
    expect(tsxSrc).toContain("lines.push(`- **p95**: ${formatDuration(p(0.95))}`)");
    expect(tsxSrc).toContain("lines.push(`- **p99**: ${formatDuration(p(0.99))}`)");
    expect(tsxSrc).toContain("lines.push(`- **Mean**: ${formatDuration(Math.round(mean))}`)");
    expect(tsxSrc).toContain("lines.push(`- **Max**: ${formatDuration(durations[durations.length - 1])}`)");
  });

  it('emits a Slowest 5 calls table', () => {
    // A markdown table with the 5 slowest
    // events. The duration column is the
    // headline; the time column lets the
    // user correlate with their own
    // activity log.
    expect(tsxSrc).toContain("lines.push('## Slowest 5 calls')");
    expect(tsxSrc).toMatch(/lines\.push\('\| Method \| Duration \| Status \| Time \|'\)/);
    expect(tsxSrc).toMatch(/sort\(\(a, b\) => b\.durationMs - a\.durationMs\)/);
  });

  it('emits a Top errors table (bucketed by method + error message)', () => {
    // The bucket key is method + error
    // message, so 50 instances of "the same
    // thing" surface as 1 row. The count
    // tells the user the burst size; the
    // sample message is the "what" they
    // need to debug.
    expect(tsxSrc).toContain("lines.push('## Top errors')");
    expect(tsxSrc).toMatch(/`\$\{e\.method\}\\u0001\$\{e\.error \?\? '\(no error message\)'\}`/);
    expect(tsxSrc).toContain('| Method | Count | Last seen | Sample error |');
  });

  it('joins lines with \\n and returns the full body', () => {
    expect(tsxSrc).toContain("return lines.join('\\n')");
  });
});

describe('R125: formatDuration helper', () => {
  const tsxSrc = readFileSync(join(root, 'src', 'components', 'RpcDiagnosticsPanel.tsx'), 'utf-8');

  it('renders sub-second durations as ms', () => {
    // The "stats page" convention. A 250ms
    // call is more useful as "250ms" than
    // as "0.25s" (the trailing zero is noise).
    expect(tsxSrc).toMatch(/function formatDuration\(ms: number\): string/);
    expect(tsxSrc).toContain('if (ms < 1000) return `${ms}ms`');
  });

  it('renders second-or-larger durations with 2 decimals', () => {
    expect(tsxSrc).toContain('(ms / 1000).toFixed(2)');
    expect(tsxSrc).toContain("return `${(ms / 1000).toFixed(2)}s`");
  });
});

describe('R125: CSS for the Report button', () => {
  const cssSrc = readFileSync(join(root, 'src', 'components', 'RpcDiagnosticsPanel.css'), 'utf-8');

  it('defines a .rpc-diag-export-report button style', () => {
    // The Report button uses a softer
    // colour (text-dim border, no accent
    // tint) so it doesn't compete with
    // the JSONL Export for visual
    // primacy. Both are valid actions;
    // the hierarchy signals "Export
    // first, Report as the read-only
    // alternative".
    expect(cssSrc).toContain('.rpc-diag-export-report');
  });

  it('defines a hover state', () => {
    expect(cssSrc).toContain('.rpc-diag-export-report:hover:not(:disabled)');
  });

  it('defines a disabled state', () => {
    expect(cssSrc).toContain('.rpc-diag-export-report:disabled');
    expect(cssSrc).toMatch(/\.rpc-diag-export-report:disabled\s*\{[\s\S]*?cursor: not-allowed/);
  });
});
