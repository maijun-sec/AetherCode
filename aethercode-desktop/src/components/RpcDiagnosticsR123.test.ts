import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * export the RpcDiagnosticsPanel's current
 * view to a .jsonl file via a Tauri save dialog.
 *
 * <p>Use case: a user debugging "why did the
 * daemon return X" can click Export, pick a file
 * in their downloads folder, and ship the
 * timestamped .jsonl to a colleague (or a future
 * self) who'll grep through it offline. The
 * schema is the same as the in-memory RpcEvent
 * shape, so a `jq '.[] | select(.success ==
 * false)'` against the file gives the user the
 * same view as the panel's `err` status filter.
 *
 * <p>Tests pin: the panel imports the dialog +
 * invoke helpers, the Export button shape, the
 * .jsonl body shape, and the Rust-side
 * write_text_file command.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R123: RpcDiagnosticsPanel imports for export', () => {
  const tsxSrc = readFileSync(join(root, 'src', 'components', 'RpcDiagnosticsPanel.tsx'), 'utf-8');

  it('imports invoke from @tauri-apps/api/core', () => {
    // The actual file write goes through the
    // Tauri command `write_text_file` so the
    // path-validity check + error propagation
    // live in Rust (single source of truth).
    expect(tsxSrc).toMatch(/import\s*\{\s*invoke\s*\}\s*from\s*['"]@tauri-apps\/api\/core['"]/);
  });

  it('imports the save dialog from @tauri-apps/plugin-dialog', () => {
    // The save dialog is the OS-native file
    // picker. Aliasing it to `saveDialog` keeps
    // the call site short.
    expect(tsxSrc).toMatch(/import\s*\{\s*save\s+as\s+saveDialog\s*\}\s*from\s*['"]@tauri-apps\/plugin-dialog['"]/);
  });
});

describe('R123: exportToJsonl helper', () => {
  const tsxSrc = readFileSync(join(root, 'src', 'components', 'RpcDiagnosticsPanel.tsx'), 'utf-8');

  it('declares an async exportToJsonl function', () => {
    // Lives at module scope (not inside the
    // component) so the test can target it
    // without mounting React.
    expect(tsxSrc).toMatch(/async function exportToJsonl\(/);
  });

  it('suggests a timestamped .jsonl filename in the save dialog', () => {
    // The defaultPath uses a YYYY-MM-DD_HH-MM-SS
    // timestamp + the literal "aethercode-rpc-events"
    // prefix so multiple exports in a folder are
    // self-labelling.
    expect(tsxSrc).toContain('`aethercode-rpc-events-${stamp}.jsonl`');
    expect(tsxSrc).toContain('getFullYear()');
  });

  it('filters by .jsonl extension in the save dialog', () => {
    // The save dialog's filter shows
    // "JSON Lines" first; an "All Files" escape
    // hatch is included for power users who
    // want a .ndjson or .log suffix. The
    // regex anchors on the closing `]` of
    // the filters array — we use `[\s\S]*`
    // (greedy across lines) so the inner `]`
    // in `['jsonl']` doesn't terminate the
    // match.
    const block = tsxSrc.match(/filters:\s*\[[\s\S]*?\],\s*\n\s*\}\);/);
    expect(block).toBeTruthy();
    expect(block![0]).toContain("name: 'JSON Lines'");
    expect(block![0]).toContain("extensions: ['jsonl']");
    expect(block![0]).toContain("name: 'All Files'");
  });

  it('silently no-ops when the user cancels the save dialog', () => {
    // save() returns null when the user
    // dismisses. The function must NOT show
    // an error banner — a silent no-op is the
    // expected behaviour for "I changed my
    // mind".
    expect(tsxSrc).toMatch(/if\s*\(!path\)\s*\{[\s\S]*?return;\s*\}/);
  });

  it('builds a JSONL body with one event per line + trailing newline', () => {
    // JSONL spec: one JSON value per line,
    // lines separated by \n, optional trailing
    // newline. The test pins `.join('\n')`
    // and the explicit `+ '\n'` at the end so
    // the file is `cat`-friendly.
    expect(tsxSrc).toContain(".join('\\n')");
    expect(tsxSrc).toContain("+ '\\n'");
  });

  it('JSONL body schema is RpcEvent (method, params, durationMs, success, ts)', () => {
    // The schema must mirror the in-memory
    // RpcEvent so a user who later writes a jq
    // filter gets the same fields they saw in
    // the panel. error is conditional so a
    // successful event doesn't carry an empty
    // error: '' field.
    const body = tsxSrc.match(/const body = events\.map[\s\S]*?\}\);/);
    expect(body).toBeTruthy();
    expect(body![0]).toContain('method: e.method');
    expect(body![0]).toContain('params: e.params');
    expect(body![0]).toContain('durationMs: e.durationMs');
    expect(body![0]).toContain('success: e.success');
    expect(body![0]).toContain('ts: e.ts');
    // error is only present when defined —
    // test the conditional spread, not a hard
    // field. The pattern is
    //   ...(e.error ? { error: e.error } : {})
    // which is what the test pins.
    expect(body![0]).toMatch(/\.\.\.\(e\.error \? \{ error: e\.error \} : \{\}\)/);
  });

  it('invokes the write_text_file Tauri command with the chosen path', () => {
    // The actual write goes through Rust so
    // the path-validity check + error
    // propagation are in a single place.
    expect(tsxSrc).toContain("invoke<void>('write_text_file', { path, contents: body })");
  });

  it('shows a transient "wrote N events" status on success', () => {
    // The status sits in the panel footer so
    // cause → effect is obvious. The plural
    // form is gated on `length === 1` so
    // "1 event" doesn't render as "1 events".
    expect(tsxSrc).toContain("`✓ wrote ${events.length} event");
    expect(tsxSrc).toContain("events.length === 1 ? '' : 's'");
  });

  it('shows an "export failed" status on error', () => {
    // The error message includes the
    // underlying error string (not just a
    // generic "something went wrong") so the
    // user can tell whether the failure was
    // permission, disk-full, or path-not-
    // found.
    expect(tsxSrc).toMatch(/setStatus\(`✗ export failed:/);
    expect(tsxSrc).toContain("e as Error).message ?? String(e)");
  });
});

describe('R123: Export button in the panel footer', () => {
  const tsxSrc = readFileSync(join(root, 'src', 'components', 'RpcDiagnosticsPanel.tsx'), 'utf-8');

  it('renders an Export button with the filtered count in the label', () => {
    // The count in the label is the post-
    // filter count, not the raw 50. The user
    // can tell at a glance "I'll export 8
    // things" before clicking.
    expect(tsxSrc).toContain('Export ({filtered.length})');
  });

  it('Export is disabled when the filtered view is empty', () => {
    // The user can't export an empty file —
    // the button is gated on filtered.length.
    // The Clear button is gated on the raw
    // count, so the two states are
    // independent (Clear could be enabled
    // while Export is disabled if the filter
    // narrows to 0).
    expect(tsxSrc).toMatch(/disabled=\{filtered\.length === 0\}/);
  });

  it('Export is wired to exportToJsonl(filtered, setExportStatus)', () => {
    // The handler passes the filtered view
    // (not recentRpcEvents) so the exported
    // file matches the user's current view.
    expect(tsxSrc).toContain('onClick={() => void exportToJsonl(filtered, setExportStatus)}');
  });

  it('shows the export status in the footer when set', () => {
    // The status message is conditional on
    // exportStatus being non-null. The class
    // is split by is-ok / is-err so a quick
    // glance tells the user whether the
    // export succeeded.
    expect(tsxSrc).toContain("{exportStatus && (");
    expect(tsxSrc).toContain("exportStatus.startsWith('✗') ? 'is-err' : 'is-ok'");
  });
});

describe('R123: CSS for the Export button + status message', () => {
  const cssSrc = readFileSync(join(root, 'src', 'components', 'RpcDiagnosticsPanel.css'), 'utf-8');

  it('defines a .rpc-diag-export button style', () => {
    // The Export button uses the accent
    // colour (blue) to differentiate from
    // the muted Clear button — Export is the
    // "primary" footer action, Clear is the
    // "destructive" one.
    expect(cssSrc).toContain('.rpc-diag-export');
  });

  it('defines a hover state for the Export button', () => {
    expect(cssSrc).toContain('.rpc-diag-export:hover:not(:disabled)');
  });

  it('defines a disabled state for the Export button', () => {
    expect(cssSrc).toContain('.rpc-diag-export:disabled');
  });

  it('defines a .rpc-diag-export-status style with ok / err variants', () => {
    // The status text is monospace + ellipsis
    // truncated so a long "wrote 50 events to
    // /Users/foo/Downloads/..." doesn't push
    // the Clear button off-screen.
    expect(cssSrc).toContain('.rpc-diag-export-status');
    expect(cssSrc).toContain('.rpc-diag-export-status.is-ok');
    expect(cssSrc).toContain('.rpc-diag-export-status.is-err');
    expect(cssSrc).toContain('text-overflow: ellipsis');
  });
});

describe('R123: Rust write_text_file command', () => {
  const rustSrc = readFileSync(join(root, 'src-tauri', 'src', 'lib.rs'), 'utf-8');

  it('declares a #[tauri::command] async fn write_text_file', () => {
    // The Tauri command takes (path, contents)
    // and returns Result<(), String>. The
    // String error lets the TS side render
    // the exact failure reason.
    expect(rustSrc).toMatch(/#\[tauri::command\][\s\S]*?async fn write_text_file\(/);
  });

  it('write_text_file checks that the parent directory exists', () => {
    // The parent-existence check is the
    // cheap pre-write validation that
    // distinguishes "you typo'd the path"
    // from "the OS refused the write". The
    // check is gated on parent being
    // non-empty (the current directory's
    // parent is an empty string on Linux).
    expect(rustSrc).toMatch(/if let Some\(parent\) = p\.parent\(\)/);
    expect(rustSrc).toContain('parent directory does not exist:');
  });

  it('write_text_file uses std::fs::write to actually persist', () => {
    // std::fs::write handles overwrite +
    // create-new in one call. No need for a
    // separate create-then-write dance.
    expect(rustSrc).toContain('std::fs::write(&p, contents)');
  });

  it('write_text_file is registered in the invoke_handler list', () => {
    // The Tauri build wires the command list
    // in the .invoke_handler call. A
    // refactor that adds the command but
    // forgets to register it would
    // build-fail (the macro rejects unknown
    // function names), but the test pins the
    // literal registration line.
    expect(rustSrc).toMatch(/invoke_handler\(tauri::generate_handler!\[[\s\S]*?write_text_file[\s\S]*?\]\)/);
  });
});
