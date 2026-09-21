// @vitest-environment jsdom
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * R302 (2026-09-21) — true root cause of the "一闪而过"
 * symptom that survived R292-R301. After the desktop spawns
 * a daemon via `ensure_daemon`, the renderer stores the
 * returned `DaemonInfo` in the Zustand `daemonInfo` field
 * and reads `jarPath`/`cwd` off it inside `startSsdFlow`
 * to decide whether to wire `TauriSsdDriver` or fall back
 * to `MockSsdDriver`.
 *
 * The bug: `setCwd`'s swap path (when `swap_to_pre_warm`
 * succeeds) sets `daemonInfo: null` at the top of the new
 * daemon's lifecycle and never refills it. After the user
 * picks a new project folder (a near-universal flow), the
 * next `startSsdFlow` call reads an empty `jarPath` from
 * the store, sees `(jarPath && cwd)` evaluate to false, and
 * silently falls back to the canned `MockSsdDriver` —
 * chip strip flashes through 8 canned phases in ~4s with
 * zero per-phase confirmation.
 *
 * <p>Two-part fix:
 * <ol>
 *   <li><b>setCwd refill</b>: after `set({ daemonInfo: null
 *       })` in the swap branch, call
 *       <code>invoke('get_daemon_info')</code> and
 *       <code>set({ daemonInfo: fresh })</code> so the
 *       freshly-promoted daemon's info populates the
 *       store. Logged via
 *       <code>[R302-setCwd] daemonInfo refilled ...</code>
 *       to <code>%TEMP%\aethercode-desktop-daemon-info.log</code>.</li>
 *   <li><b>startSsdFlow primary source</b>: replace
 *       <code>get().daemonInfo?.jarPath ?? ''</code> with a
 *       fresh <code>invoke('get_app_paths')</code> call so
 *       the spawn resolver doesn't depend on store state.
 *       Falls back to the pre-R302 store path when
 *       <code>invoke</code> throws (dev / tests). Logged via
 *       <code>[R302-sdd-spawn-resolve] source=get_app_paths ...</code>
 *       (or <code>source=daemonInfo-fallback</code> on
 *       fallback).</li>
 * </ol>
 *
 * <p>This file is a source-pin test that confirms the two
 * fixes are present in `src/store/index.ts`. A runtime test
 * would need to mock Tauri's `invoke` for `get_app_paths`
 * and the dynamic-import driver modules; source-pinning the
 * two `invoke` call sites + the two log lines is enough to
 * catch regressions in future rounds.
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function readSrc(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R302: setCwd refill + startSsdFlow get_app_paths primary source', () => {
  const storeSrc = readSrc('src/store/index.ts');

  it('setCwd refills daemonInfo via invoke("get_daemon_info") after swap', () => {
    // The refill must come AFTER the `set({ daemonInfo: null })`
    // block in the swapped branch — anything before that set()
    // is part of the OLD daemon's lifecycle and wouldn't
    // refilled the freshly-promoted one.
    const refillIdx = storeSrc.indexOf("invoke<DaemonInfo | null>('get_daemon_info')");
    expect(refillIdx, 'R302 setCwd refill must call invoke("get_daemon_info")').toBeGreaterThan(0);

    // Anchor on the swapped branch's `daemonInfo: null` line by
    // including the preceding `lastSessionSummary: null,` to
    // disambiguate from later occurrences (resetDaemon also
    // clears daemonInfo to null). Use \r\n because the
    // project file is CRLF on Windows (LF normalisation would
    // also work but the raw marker is faster).
    const setNullMarker = 'lastSessionSummary: null,\r\n            daemonInfo: null,';
    const setNullIdx = storeSrc.indexOf(setNullMarker);
    expect(setNullIdx, 'swapped branch daemonInfo: null anchor must exist').toBeGreaterThan(0);
    // The refill must come AFTER the set({...}) closing brace
    // of the swapped branch but BEFORE the next `} else {`.
    const setNullEnd = storeSrc.indexOf('});', setNullIdx) + 3;
    const elseIdx = storeSrc.indexOf('} else {', setNullEnd);
    expect(elseIdx, 'else branch must follow swapped branch').toBeGreaterThan(setNullEnd);
    expect(refillIdx, 'refill must be after swapped set({...})').toBeGreaterThan(setNullEnd);
    expect(refillIdx, 'refill must come before the else branch').toBeLessThan(elseIdx);
  });

  it('setCwd refill writes an R302 diagnostic log line', () => {
    // The fix must emit `[R302-setCwd] daemonInfo refilled ...`
    // so the user can verify the swap populated a non-empty jarPath.
    expect(storeSrc).toContain('[R302-setCwd] daemonInfo refilled');
    expect(storeSrc).toContain('append_text_file');
    // The log must include the resolved fields the chip needs.
    const block = storeSrc.slice(
      storeSrc.indexOf('[R302-setCwd] daemonInfo refilled'),
      storeSrc.indexOf('[R302-setCwd] daemonInfo refilled') + 800,
    );
    expect(block).toContain('port=');
    expect(block).toContain('jarPath=');
    expect(block).toContain('cwd=');
  });

  it('startSsdFlow uses get_app_paths as PRIMARY source of jarPath/cwd', () => {
    // The fix must call `invoke<{ jarPath?: string; cwd?: string }>('get_app_paths')`
    // BEFORE deciding between TauriSsdDriver and MockSsdDriver.
    // Note: startSsdFlow already used `invoke('append_text_file')` in R298; we
    // specifically look for the `get_app_paths` call.
    expect(storeSrc).toContain("invoke<{ jarPath?: string; cwd?: string } | null>('get_app_paths')");
    // The resolve call must come BEFORE the driverChoice assignment.
    const resolveIdx = storeSrc.indexOf("'get_app_paths')");
    const driverChoiceIdx = storeSrc.indexOf('driverChoice = (jarPath && cwd)');
    expect(resolveIdx, 'get_app_paths must be called').toBeGreaterThan(0);
    expect(driverChoiceIdx, 'driverChoice assignment must exist').toBeGreaterThan(0);
    expect(resolveIdx, 'get_app_paths must come BEFORE driverChoice').toBeLessThan(driverChoiceIdx);
  });

  it('startSsdFlow logs [R302-sdd-spawn-resolve] source=get_app_paths ...', () => {
    expect(storeSrc).toContain('[R302-sdd-spawn-resolve] source=get_app_paths');
    expect(storeSrc).toContain('[R302-sdd-spawn-resolve] source=daemonInfo-fallback');
  });

  it('startSsdFlow still falls back to daemonInfo jarPath when invoke throws', () => {
    // The catch branch must read `daemonInfo?.jarPath` so dev
    // mode (Tauri not available) still works without the
    // canned MockSsdDriver events breaking. The fallback
    // expression lives in the `catch` block — its position is
    // BEFORE the diagnostic log line, so we anchor on the
    // log line and look BACKWARDS rather than forwards.
    const fallbackLogIdx = storeSrc.indexOf('source=daemonInfo-fallback');
    expect(fallbackLogIdx).toBeGreaterThan(0);
    const fallbackBlock = storeSrc.slice(Math.max(0, fallbackLogIdx - 1200), fallbackLogIdx);
    expect(fallbackBlock).toContain("daemonInfo?.jarPath ?? ''");
    expect(fallbackBlock).toContain("get().daemonInfo");
  });

  it('R302 comments reference the pre-R302/window-prompt history', () => {
    // The decision-rule comment block must mention R302 + get_app_paths
    // so future readers understand why we no longer read daemonInfo
    // directly.
    expect(storeSrc).toContain('R302 fix');
    expect(storeSrc).toContain('clears to null and never refills');
  });
});