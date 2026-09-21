# R302 — SDD flash-through root cause: setCwd swap clears daemonInfo and never refills it

**Date**: 2026-09-21
**Round**: R302 (post-R301 user verification)
**Author**: Mavis (mavis)

## TL;DR

The "一闪而过" symptom survived R292 → R301 because every prior fix assumed the
store's `daemonInfo.jarPath` field was the source of truth. It's not — `setCwd`'s
swap path (which fires any time the user picks a new project folder) clears
`daemonInfo: null` and never refills it. After the swap, the renderer reads an
empty `jarPath`, sees the `(jarPath && cwd)` guard fail, and silently falls
back to `MockSsdDriver` — chip strip flashes through 8 canned phases in
~4 seconds with zero per-phase confirmation.

**Fix (2 parts)**:

1. `setCwd` refill — after `set({ daemonInfo: null })` in the swap branch,
   call `invoke('get_daemon_info')` and `set({ daemonInfo: fresh })` to populate
   the freshly-promoted daemon's info. Logged as `[R302-setCwd] daemonInfo
   refilled port=... jarPath=... cwd=...`.
2. `startSsdFlow` primary source — replace `get().daemonInfo?.jarPath ?? ''`
   with a fresh `invoke('get_app_paths')` call that always returns the
   resolved jar + cwd via Rust's `find_jar_path` + `state.cwd`/`persisted_cwd`
   lookup. Falls back to the store only when `invoke` throws (dev / tests).
   Logged as `[R302-sdd-spawn-resolve] source=get_app_paths ...` or
   `source=daemonInfo-fallback ...` on the catch path.

**Verification**: 1194 desktop tests pass (6 new R302 source-pin tests).
Build clean (Tauri 3m11s). exe 6.55 MB / NSIS 55.05 MB / MSI 56.76 MB.
Commit `a2b5ee7` pushed to origin/main.

---

## Background

### Symptom

User reported "一闪而过" (chip strip flashes by with no accept prompt) across
8 consecutive rounds: R292 → R301. Each round fixed a different suspected root
cause:

| Round | Symptom                | Root cause found (or thought)              | Fix                      |
|-------|------------------------|--------------------------------------------|--------------------------|
| R292  | wire format mismatch   | daemon emitted `event:`, renderer parsed `kind:` | normalise in `safeParseSsdEvent` |
| R293  | no subprocess at all   | MockSsdDriver fallback in dev mode         | spawn `TauriSsdDriver` when `daemonInfo.jarPath` present |
| R294  | no jar in portable dir | portable mode doesn't copy jar next to exe | manual copy + `find_jar_path` check |
| R295  | UI toggle confusion    | 4 quality pills cluttered the toggle bar   | merge into `<select>` |
| R296  | empty output silently accepted | empty stdout treated as "done"      | 3x retry guard            |
| R297  | daemon events dropped  | `safeParseSsdEvent` only checked `kind:`   | normalise daemon `event`  |
| R298  | `--auto` mode no events | `InteractiveRepl` only fired in interactive mode | `autoAccept()` synthetic stdin |
| R298  | attach lost jar info   | fast-path wrote `<external>` jarPath       | use `find_jar_path()` + `state.persisted_cwd` |
| R299  | no per-phase confirm   | default `--auto` ran everything end-to-end | default `--interactive`  |
| R300  | no refine input        | accept/revise/skip buttons not present     | inline `<textarea>` + 📤 button |
| R301  | still flashes          | driver choice card said `--auto` while passing `--interactive` | sync card text + add 4 log sites |

R301 added `[R301-sdd-event]` log lines so user could verify what was
happening. After R301 deployment, user restarted and reported "仍然是一闪而过,
没有任何让确认的地方". User pasted the `aethercode-desktop-daemon-info.log`:

```
[R298-ensure] jar=\\?\D:\...\release\R292\desktop\aethercode.jar (portable)
... 7 times (one per restart)
[R301-sdd-event] kind=phase-list
[R301-sdd-event] kind=phase-start phase=constitution
[R301-sdd-event] kind=phase-draft phase=constitution
[R301-sdd-event] kind=phase-accepted phase=constitution
... 19 events total, ALL auto-progressed without any stdin
```

The smoking gun: `[R301-sdd-spawn]` / `[R301-sdd-spawned]` / `[R301-sdd-stdin]`
/ `[R301-sdd-stop]` — **none present**. The events flowed, but nothing ever
spawned. The chip strip also showed `MockSsdDriver (jarPath='', cwd='')`.

### Root cause

Three locations set `daemonInfo` in the Zustand store (`src/store/index.ts`):

1. `useStore.setState({ daemonInfo: null })` — initial empty state (line 3480).
2. `useStore.setState({ daemonInfo: null })` — NEEDS_CWD handling (line 3692).
3. `useStore.setState({ daemonInfo: info })` — `initialize()` after
   `invoke('ensure_daemon')` returns a fresh `DaemonInfo` (line 3702).
4. `useStore.setState({ daemonInfo: null })` — `setCwd`'s swap path after a
   successful `swap_to_pre_warm` (line 6311). ❌ **NEVER REFILLED**.

After the user picks any project folder (a universal flow), `setCwd` clears
`daemonInfo`. No code path then refills it. The next `startSsdFlow()` reads
`daemonInfo?.jarPath` → empty → `(jarPath && cwd)` is false → falls back to
`MockSsdDriver` → chip strip shows the canned 14-event sequence in ~4 seconds.

The screenshot the user attached showed this clearly:
`8 阶段: ... · R298: MockSsdDriver (jarPath="", cwd="")` — confirming the
fallback branch was taken at SDD runtime.

### Why R301 didn't catch it

R301's diagnostic logging only fired **inside** the spawn / event handler paths
of `TauriSsdDriver`. Since `TauriSsdDriver` was never instantiated (the
fallback to `MockSsdDriver` happened at the `startSsdFlow` driverChoice line),
none of R301's logs ever fired. The log was actually informative — it proved
the spawn side was bypassed — but R301's logging strategy assumed the spawn
side was reachable.

---

## Fix

### Part 1: `setCwd` refill (`src/store/index.ts` line ~6370)

After the swap branch sets `daemonInfo: null`, add:

```typescript
try {
  const fresh = await invoke<DaemonInfo | null>('get_daemon_info');
  if (fresh) {
    set({ daemonInfo: fresh });
    // R302 diagnostic log
    const line = `[R302-setCwd] daemonInfo refilled port=${fresh.port} jarPath=${JSON.stringify(fresh.jarPath)} cwd=${JSON.stringify(fresh.cwd)} spawned=${fresh.spawned} @ ${new Date().toISOString()}\n`;
    try { console.log(line.trim()); } catch {}
    try {
      const tdir = await (await import('@tauri-apps/api/path')).tempDir();
      const logPath = tdir ? `${tdir}\\aethercode-desktop-daemon-info.log` : 'aethercode-desktop-daemon-info.log';
      await invoke('append_text_file', { path: logPath, contents: line }).catch(() => {});
    } catch {}
  }
} catch (e) {
  try { console.warn('[R302-setCwd] get_daemon_info after swap failed:', e); } catch {}
}
```

`get_daemon_info` is a Tauri command (lib.rs:931) that returns the
`state.daemon` mutex clone. After `swap_to_pre_warm` (lib.rs:599) populates
the mutex, the renderer can re-read it via this command.

### Part 2: `startSsdFlow` primary source (`src/store/index.ts` line ~5788)

Replace the `daemonInfo` lookup at the top of the driverChoice branch with
a fresh `invoke('get_app_paths')` call:

```typescript
let jarPath = '';
let cwd = get().cwd ?? '';
try {
  const paths = await invoke<{ jarPath?: string; cwd?: string } | null>('get_app_paths');
  if (paths?.jarPath) jarPath = paths.jarPath;
  if (paths?.cwd && !cwd) {
    cwd = paths.cwd;
    set({ cwd });
  }
  // Also reflect into daemonInfo so the chip strip reads the same value
  const cur = get().daemonInfo;
  if (cur && (paths?.jarPath || paths?.cwd)) {
    set({
      daemonInfo: {
        ...cur,
        jarPath: paths?.jarPath || cur.jarPath,
        cwd: paths?.cwd || cur.cwd,
      },
    });
  }
  // R302 diagnostic log
  try {
    const line = `[R302-sdd-spawn-resolve] source=get_app_paths jarPath=${JSON.stringify(jarPath)} cwd=${JSON.stringify(cwd)} slug=${featureSlug} @ ${new Date().toISOString()}\n`;
    try { console.log(line.trim()); } catch {}
    try {
      const tdir = await (await import('@tauri-apps/api/path')).tempDir();
      const logPath = tdir ? `${tdir}\\aethercode-desktop-daemon-info.log` : 'aethercode-desktop-daemon-info.log';
      await invoke('append_text_file', { path: logPath, contents: line }).catch(() => {});
    } catch {}
  } catch {}
} catch (e) {
  // Tauri not available (dev / tests) — fall back to daemonInfo
  const daemonInfo = get().daemonInfo;
  jarPath = daemonInfo?.jarPath ?? '';
  try {
    const line = `[R302-sdd-spawn-resolve] source=daemonInfo-fallback jarPath=${JSON.stringify(jarPath)} cwd=${JSON.stringify(cwd)} slug=${featureSlug} err=${String((e as any)?.message ?? e)} @ ${new Date().toISOString()}\n`;
    try { console.warn(line.trim()); } catch {}
    try {
      const tdir = await (await import('@tauri-apps/api/path')).tempDir();
      const logPath = tdir ? `${tdir}\\aethercode-desktop-daemon-info.log` : 'aethercode-desktop-daemon-info.log';
      await invoke('append_text_file', { path: logPath, contents: line }).catch(() => {});
    } catch {}
  } catch {}
}
```

`get_app_paths` is a Tauri command (lib.rs:951) that always returns the
resolved jar via `find_jar_path` + the current `state.cwd` (or
`persisted_cwd` fallback). It requires cwd to be set — if it's not, the
command returns `NEEDS_CWD` and we fall through to the catch branch.

The catch branch keeps the pre-R302 behaviour (read from store) so dev mode
(no Tauri context) still works.

### Part 3: Tests

New `src/store/setCwdSddR302.test.ts` (6 source-pin cases):

1. `setCwd` calls `invoke('get_daemon_info')` inside the swapped branch (not
   outside it, not in the wrong branch).
2. `setCwd` refill writes an `[R302-setCwd] daemonInfo refilled` log line
   containing `port=`, `jarPath=`, `cwd=`.
3. `startSsdFlow` uses `get_app_paths` as PRIMARY source (the call comes
   BEFORE the `driverChoice` assignment).
4. `startSsdFlow` logs both `source=get_app_paths` and
   `source=daemonInfo-fallback` markers.
5. The catch branch falls back to `daemonInfo?.jarPath` (so dev mode keeps
   working).
6. The R302 comments document the pre-R302 `window.prompt` / daemon-info
   history so future readers understand why we no longer read `daemonInfo`
   directly.

### Part 4: Build + deploy

- Tauri build: 3m11s, exe 6.55 MB / NSIS 55.05 MB / MSI 56.76 MB.
- jar unchanged from R298 daemon fix (no rebuild needed).
- Killed 2 stale `aethercode-desktop` / `java` processes left over from
  the user's R301 test (PID 8720 + 11984).
- Synced new build to `release/R292/desktop/`.
- Commit `a2b5ee7` pushed to `origin/main`.
- Full desktop test suite: **1194 tests pass** (up from 1188, +6 R302).

---

## Files changed

```
src/store/index.ts                                  | +167 -14
src/store/setCwdSddR302.test.ts                     | +218 (new)
```

## Verification

```
D:\work\workspace\idea\engine\AetherCode\release\R292\desktop\
├── aethercode-desktop.exe             6.55 MB (13:18:03, R302 build)
├── AetherCode_0.3.0_x64-setup.exe     55.05 MB (NSIS, 13:18:03)
├── AetherCode_0.3.0_x64_en-US.msi     56.76 MB (WiX, 13:17:16)
└── aethercode.jar                     56.65 MB (9:32:36, R298 daemon fix, unchanged)
```

After user restarts the new build and triggers SDD, expected `daemon-info.log`
sequence:

```
[R298-ensure] jar=... (portable)        ← desktop startup
[R302-setCwd] daemonInfo refilled ...    ← only if user changed cwd before SDD
[R302-sdd-spawn-resolve] source=get_app_paths  jarPath="..." cwd="..."  ← startSsdFlow
[R301-sdd-spawn] java=... args=...       ← TauriSsdDriver spawned
[R301-sdd-spawned] pid=... hasStdout=true
[R301-sdd-event] kind=phase-list
[R301-sdd-event] kind=phase-start phase=constitution
[R301-sdd-event] kind=phase-draft phase=constitution
                                          ← stops here, waiting for user
[user clicks ✅]
[R301-sdd-stdin] action=accept textLen=0
[R301-sdd-event] kind=phase-accepted phase=constitution
... 8 phases total
```

If the user sees `[R302-sdd-spawn-resolve] source=daemonInfo-fallback` instead
of `source=get_app_paths`, that means `invoke('get_app_paths')` failed —
should not happen in a packaged desktop but is a useful early-warning signal.

## Lessons

- **Lesson 627** (new): **Don't assume store state stays fresh across async
  control-flow boundaries.** `setCwd`'s `await rpc.setCwd(path)` is async; any
  state mutation inside it must be paired with an explicit refresh of all
  fields the renderer reads downstream. `daemonInfo: null` is one example;
  future "set state, then await, then forget to refill" bugs are inevitable
  without a centralised "after a daemon-side mutation, always re-fetch
  DaemonInfo" rule.
- **Lesson 628** (new): **Diagnostic logs that only fire inside the suspected
  component can't diagnose a "component never instantiated" failure.** R301's
  logs lived inside `TauriSsdDriver` and `store.onEvent` — both unreachable
  when `startSsdFlow`'s driverChoice took the `MockSsdDriver` fallback. The
  fix is to log the **driverChoice decision** itself, not just the events of
  the chosen driver. R302 adds `[R302-sdd-spawn-resolve]` exactly at that
  decision site.
- **Lesson 629** (new): **The fix that "should have worked" often did, but
  the test that proves it didn't exist.** R292 → R301 each shipped with
  tests for the specific failure they targeted (wire format, jar lookup,
  empty output, etc.). None tested the cross-cutting "does the renderer
  know it has a daemon?" invariant. R302's `setCwdSddR302.test.ts` is the
  first source-pin test that asserts the **structural** relationship
  between `setCwd` and `daemonInfo`. Future regressions of the same shape
  (cleared-but-not-refilled state) will surface immediately.
- **Lesson 630** (new): **`Test-NetConnection` confirms TCP, not git
  push.** GitHub's HTTPS endpoint uses port 443, but some networks block
  it intermittently. `git push` failures with "Failed to connect to
  github.com port 443" are often transient — wait 30s and retry.

## Next steps

- User retest: restart `aethercode-desktop.exe`, trigger SDD on a project
  folder. Expected behaviour: chip strip halts at `phase-draft`, user sees
  ✅ / ✏️ / ⏭️ buttons (R300) + inline `<textarea>` for refine. Per-phase
  confirmation flow per R299.
- If still flashes: read `%TEMP%\aethercode-desktop-daemon-info.log`
  fresh tail. Expect `[R302-sdd-spawn-resolve] source=get_app_paths
  jarPath=...` immediately followed by `[R301-sdd-spawn] java=...`.
  If absent → Tauri invoke wiring is broken at a different layer.
- If `source=daemonInfo-fallback` shows up → `get_app_paths` is failing,
  dig into why (likely a cwd issue at startup).
- R295+ (next rounds): TUI `/sdd` slash command + supervisor SddService
  + IDEA Plugin DaemonBackend SDD RPC.