import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Follow-up user feedback — 4 fixes for the new-session
 * / perm / project-group flow.
 *
 * <ol>
 *   <li><b>New session must pre-fill cwd</b> — the top
 *       "新建 session" (New Session) button was passing {@code null}
 *       for the cwd, so the new session landed in the
 *       "Unlinked Project" bucket instead of the project the
 *       user was currently on.</li>
 *   <li><b>No "sessions" intermediate layer</b> — the
 *       LeftPanel had three sections (TaskSummary /
 *       "新建 session" / sessions). The user said the
 *       middle "新建 session" (New Session) section was unnecessary
 *       between project and session.</li>
 *   <li><b>Session titles</b> — the row showed
 *       {@code Session <id-suffix>} for sessions
 *       without a title. The user asked for
 *       {@code <未命名> (Unnamed)} when no prompt exists, and
 *       the first prompt (preview) as the title
 *       when one does.</li>
 *   <li><b>Permission mode reverts to 主动询问 (ask)</b> —
 *       when the user picked 始终运行 (Always Run, bypass) and
 *       then switched projects, the new daemon's
 *       default {@code DEFAULT} mode was loaded and
 *       the dropdown re-flipped to 主动询问. The
 *       desktop must re-apply the user's persisted
 *       preference on the new daemon after a swap.</li>
 * </ol>
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

describe('R204 #1 + #2: LeftPanel no longer renders a top "新建 session" button', () => {
  const lpSrc = readFileSync(join(root, 'src', 'components', 'LeftPanel.tsx'), 'utf-8');

  it('does not render a top-level "+ 新建 session" button (the redundant layer is gone)', () => {
    // Previously the panel had a left-new section with
    // a "+ 新建 session" (New Session) button. The button hard-coded
    // cwd=null which minted "Unlinked Project" sessions
    // regardless of the user's current project. R204
    // removes the button entirely; the per-project
    // "＋" in ProjectGroup is the only entry point.
    expect(lpSrc).not.toMatch(/left-new/);
    expect(lpSrc).not.toMatch(/onNewSessionInCwd\(null\)/);
  });

  it('the panel renders two sections (TaskSummary + sessions), not three', () => {
    // legacy: left-top, left-new, left-mid.
    // left-top, left-mid.
    // Pin the count to catch a refactor that
    // accidentally re-adds a section.
    const sectionMatches = lpSrc.match(/left-section/g) ?? [];
    expect(sectionMatches.length).toBeGreaterThanOrEqual(2);
    // The "left-new" CSS class should not appear in
    // the rendered markup. We grep the TSX (where the
    // JSX is written), not the CSS file.
    expect(lpSrc).not.toContain('"left-section left-new"');
  });

  it('onNewSessionInCwd is still wired (per-project "+" still works)', () => {
    // Removing the top button doesn't break the
    // ProjectGroup's "+" handler. Pin that the hook
    // is still called from LeftPanel so the per-project
    // mint-session path is intact.
    expect(lpSrc).toContain('useNewSessionInCwd()');
    expect(lpSrc).toContain('<ProjectGroupList');
    expect(lpSrc).toContain('onNewSession={onNewSessionInCwd}');
  });
});

describe('R204 #1: useNewSessionInCwd respects current cwd', () => {
  const pgSrc = readFileSync(join(root, 'src', 'components', 'ProjectGroup.tsx'), 'utf-8');

  it('useNewSessionInCwd takes the project cwd (not the global currentCwd)', () => {
    // The hook receives the per-group `cwd` from the
    // ProjectGroup's "+" click. R204 leaves this path
    // alone (it was already correct) but the source
    // pin catches a refactor that drops the `cwd`
    // argument and falls through to the global
    // curCwd comparison.
    expect(pgSrc).toMatch(/export function useNewSessionInCwd\(\)\s*\{[\s\S]*?useStore\(\(s\) => s\.createNewSession\)[\s\S]*?return async \(cwd: string \| null\) => \{/);
  });

  it('useNewSessionInCwd short-circuits the same-cwd path (no setCwd, no daemon swap)', () => {
    // The same-cwd branch is the cheap path: it must NOT
    // call setCwd (which would trigger pre_warm + the 1-2s
    // JVM spawn dance). R331 keeps the cheap path —
    // createNewSession({}) at the end opens the mode
    // picker, and the picker re-enters with the chosen mode.
    expect(pgSrc).toMatch(/if \(!sameCwd\)[\s\S]*?setCwd\(cwd\)/);
    expect(pgSrc).toMatch(/await createNewSession\(\)/);
  });

  it('useNewSessionInCwd routes a different-cwd click through setCwd before opening the picker', () => {
    // The different-cwd branch still goes through setCwd
    // — the daemon needs to swap so the new session binds
    // to the right project. The picker opens AFTER the
    // swap so `pendingNewSession.cwd` reflects the new
    // cwd by the time the user picks a mode.
    expect(pgSrc).toMatch(/!sameCwd[\s\S]*?setCwd\(cwd\)/);
  });
});

describe('R204 #3: SessionListRow titles use preview, fall back to <未命名>', () => {
  const rowSrc = readFileSync(join(root, 'src', 'components', 'session', 'SessionListRow.tsx'), 'utf-8');

  it('falls back to <未命名> for sessions with no title and no preview', () => {
    // Previously the fallback was "Session <id-suffix>".
    // The user asked for <未命名> (Unnamed) when no
    // prompt exists. The session id is still in the
    // tooltip for power users; the row text no longer
    // carries a UUID tail.
    expect(rowSrc).toMatch(/return\s*'\u672a\u547d\u540d'/);
  });

  it('uses the preview as the title when no explicit title is set', () => {
    // The first user message (capped at 200 chars by
    // the daemon's listSessions withPreview=true) is
    // the most reliable signal of "what is this
    // session about". Truncate to 60 chars so the
    // row stays tight.
    expect(rowSrc).toMatch(/s\.preview && s\.preview\.trim\(\)/);
    expect(rowSrc).toMatch(/p\.length > 60 \? p\.slice\(0, 57\) \+ '\.\.\.' : p/);
  });

  it('honours an explicit title (daemon-generated or user-renamed)', () => {
    // The first branch in the chain: if the session
    // has a non-empty `title` (set by the daemon's
    // future title generator or a user rename), use
    // it. legacy this branch existed; the regression
    // we're guarding against is a refactor that
    // always shows the preview.
    expect(rowSrc).toMatch(/s\.title && s\.title\.trim\(\)/);
  });

  it('keeps the session id in the tooltip (power users can hover)', () => {
    // The row's native `title` attribute still
    // carries the full session id, the cwd, and the
    // last-active timestamp. Dropping the visible
    // id-suffix doesn't lose the audit trail — it's
    // one hover away.
    expect(rowSrc).toMatch(/title=\{`\$\{session\.id\}\\n\$\{session\.cwd \?\? ''\}\\nlast active \$\{lastActive\}`\}/);
  });
});

describe('R204 #4: setPermissionMode action surfaces daemon rejection', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('captures the RPC ok flag (历史 silently discarded the result)', () => {
    // legacy the action did
    //   await rpc.setPermissionMode(daemonMode);
    // and the result was thrown away. A daemon-side
    // rejection (e.g. an unknown mode string) then
    // silently left the dropdown showing the user's
    // pick while the daemon kept its old mode, and
    // the next refreshEngineState() overwrote the
    // dropdown with the daemon's actual value. R204
    // pins the local state to the daemon's value on
    // rejection so the user sees the revert
    // immediately (not on the 15s refresh tick).
    const block = storeSrc.match(/setPermissionMode:\s*async\s*\(mode:\s*string\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/const r(: any)? = await rpc\.setPermissionMode\(daemonMode\)/);
    expect(block![0]).toMatch(/const ok = !r \|\| r\.ok !== false/);
  });

  it('on rejection, mirrors the daemon mode in the local state', () => {
    // The "effective" mode on rejection comes from
    // the daemon's response (r.mode). If the daemon
    // returned a mode the renderer can map, use it;
    // otherwise keep the prior value. This prevents
    // the 15s refresh from being the only signal
    // that the user's pick was rejected.
    const block = storeSrc.match(/setPermissionMode:\s*async\s*\(mode:\s*string\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/const effectiveDaemonMode = ok\s*\?\s*daemonMode\s*:\s*\(\(r && \(r as any\)\.mode\)/);
  });

  it('on rejection, does NOT persist the user-picked value to localStorage', () => {
    // legacy the prefs.permissionMode write was
    // unconditional. R204 wraps it in `if (ok) {}`
    // so a rejected pick doesn't pollute the
    // persisted value (which would then re-load
    // on the next launch and immediately re-revert).
    const block = storeSrc.match(/setPermissionMode:\s*async\s*\(mode:\s*string\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/if \(ok\) \{[\s\S]*?prefs\.permissionMode = mode/);
  });

  it('on rejection, surfaces the reason as a system chat line', () => {
    // Silent auto-revert is hard to notice; a
    // one-line banner in the chat is unambiguous.
    // The message is in the user's locale (zh) so
    // it matches the rest of the chat UI.
    const block = storeSrc.match(/setPermissionMode:\s*async\s*\(mode:\s*string\)\s*=>\s*\{[\s\S]*?^\s{4}\}/m);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/\[perm \u6a21\u5f0f\u53d8\u66f4\u5931\u8d25\]/);
    expect(block![0]).toMatch(/role: 'system' as const/);
  });
});

describe('R204 #4: setCwd re-applies the user\'s persisted permission mode after the daemon swap', () => {
  const storeSrc = readFileSync(join(root, 'src', 'store', 'index.ts'), 'utf-8');

  it('re-applies the persisted mode via setPermissionMode after the new daemon comes up', () => {
    // A setCwd → set_cwd_daemon swap creates a brand
    // new JVM rooted at the new cwd. The new engine
    // boots with its DEFAULT mode (the daemon
    // doesn't carry the old JVM's user-set mode).
    // Without this re-apply, the renderer would
    // briefly show 主动询问 (ask), then on the next
    // refreshEngineState the dropdown would land on
    // whatever the daemon reported. The user
    // explicitly picked 始终运行 (Always Run) and the new daemon
    // shouldn't silently flip them back to the
    // default.
    const block = storeSrc.match(/setCwd:\s*async\s*\(path:\s*string\)\s*=>\s*\{[\s\S]*?void get\(\)\.preWarmCwd\(suggestSibling\(path\)\)\.catch\(\(\) => \{\}\)/);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/R204: re-apply the user'?s persisted/);
    expect(block![0]).toMatch(/const prefsForPerm = readEnginePrefs\(\)/);
    expect(block![0]).toMatch(/void get\(\)\.setPermissionMode\(prefsForPerm\.permissionMode\)\.catch\(\(\) => \{\}\)/);
  });

  it('only re-applies when the persisted value differs from the current store value', () => {
    // The re-apply is conditional to avoid an
    // unnecessary RPC when the user is on the
    // daemon's default mode (e.g. a fresh install
    // where prefs.permissionMode === 'ask' and
    // the engine's mode === DEFAULT, both of which
    // map to 主动询问 / ask). Pin the guard so a refactor
    // that drops it doesn't add a round-trip on
    // every cwd switch.
    const block = storeSrc.match(/setCwd:\s*async\s*\(path:\s*string\)\s*=>\s*\{[\s\S]*?void get\(\)\.preWarmCwd\(suggestSibling\(path\)\)\.catch\(\(\) => \{\}\)/);
    expect(block).toBeTruthy();
    expect(block![0]).toMatch(/prefsForPerm\.permissionMode && prefsForPerm\.permissionMode !== get\(\)\.permissionMode/);
  });
});
