# R172 — Daemon stability + Stream-UX (2026-08-31)

A single round of fixes after the user reported "daemon is so unstable"
on a complex multi-tool task ("generate a Java Maven project with at
least 5 sort algorithms"). Three independent bugs all surfaced at once
and looked like one problem; they're listed below in the order they
were triaged.

## 1. CSP / wire-name misroute (the "Disconnected" + blank-window bug)

This was the bug from the *previous* R172 turn (Aug 30); the fix
already shipped in v0.2.18. Recap so the doc stands alone:

- `aethercode-desktop/src/lib/methods.ts:582` was calling
  `'listMemory'` (R127 entry-based, returns `{ok, entries, count}`) for
  `listMemoryFiles()` (R92 file-based, returns `{files, count}`).
- `MemoryPanel.tsx:87` did `setFiles(f => ({...f, [scope]: r.files}))`,
  so `files[scope]` became `undefined` on every refresh.
- `MemoryPanel.tsx:188` then did
  `files.USER.length + files.PROJECT.length + files.LOCAL.length` in
  a `useMemo`, which threw on the first undefined slot.
- The exception took the right panel down; the user saw a
  permanently-stuck "Disconnected" header. The "CSP" was a red
  herring — the actual response CSP header was correct, the console
  error was stale from a previous build.

Fix: wire-name corrected (`listMemory` → `listMemoryFiles`, etc.),
plus defensive `r.files ?? []` and `files.X?.length ?? 0` so a
future shape drift can't take the whole panel down.

## 2. SessionManager dual-register (the "no engine for sessionId" bug)

- `aethercode-cli/src/main/java/org/aethercode/cli/DaemonRunner.java::buildSessionManager`
  was registering the default engine under the literal key
  `SessionManager.DEFAULT_SESSION_ID` ("default").
- But `AetherCodeEngine.Builder.sessionId` defaults to
  `UUID.randomUUID().toString()`, so the engine's
  `appState().sessionId()` was a fresh UUID.
- `getState` returned that UUID; the desktop stored it in
  `currentSessionId`; the next `bindSessionCwd({cwd, sessionId})`
  routed to `switchProject` → `resolveRpcTarget(<UUID>)` →
  `sm.get(<UUID>)` → null → "no engine for sessionId: <UUID>".

Fix: dual-register. `buildSessionManager` now registers the default
engine under both the literal key and the engine's real sessionId,
and calls `setActive(realId)` so the active pointer is in sync.
The desktop's `currentSessionId` resolves correctly through either
key path. `DaemonRunnerTest` updated to expect size=2 and
activeSessionId=realId, plus two new tests
(`buildSessionManager_dualRegister_*`) covering the explicit
behaviour and the edge case where the engine's real id IS
"default".

## 3. JVM heap for complex tasks (the "Stream stale" / GC bug)

- `aethercode-desktop/src-tauri/src/lib.rs::spawn_daemon` was
  calling `java -jar aethercode-cli.jar` with no JVM args. The
  default heap on a 16 GB Windows machine is ~1 GB.
- A complex multi-tool task (Maven project, 5 sort algorithms,
  many file writes) inflates the heap past 1 GB. The JVM spends
  most of its time in full GC.
- During a full GC the WS stops emitting chunks for >30s. The
  desktop's stream-stale watchdog fired a red
  "[Stream stale] Daemon stopped responding for 30s" banner,
  even though the engine was happily working.
- The user couldn't tell that the daemon was actually OK — the
  red banner looked like a hard failure.

Fix: `daemon_jvm_args()` helper returns
`["-Xms1g", "-Xmx4g", "-XX:+UseG1GC", "-XX:MaxMetaspaceSize=256m"]`
by default, applied in `spawn_daemon` so both the primary and
the pre-warm daemon get them. Override via
`AETHERCODE_DAEMON_JVM_OPTS` env var (replaces defaults entirely;
the env var path is for power users who want a different
collector). Metaspace cap is always-on so a runaway classloader
can't eat virtual memory before getting a clear OOM in the log.

Verified via `Get-CimInstance Win32_Process` — both daemon
processes (primary on 17888, pre-warm on 18889) carry the args
in their command line.

## 4. Stream-stale watchdog (the false-positive)

- `STREAM_STALE_MS` was 30_000 — too aggressive for the new
  4 GB heap + the complex-task pattern.
- The watchdog didn't know about pending permission prompts. If
  the user is reading a 200-line diff before clicking "Allow",
  the engine is deliberately silent — that's not a daemon stall.

Fix: bumped default `STREAM_STALE_MS` from 30s to 90s, with
`AETHERCODE_STREAM_STALE_MS` env var override (5s–10min bound).
The watchdog also skips while `pendingPermissions.length > 0`,
so a user who walks away from a permission prompt doesn't get
a red error after 90s — the timer just pauses until the prompt
resolves.

## 5. System message UI (the "banners jammed together" bug)

- The chat had a single `.message-system` class that was always
  red-on-red, regardless of `isError`. A benign
  "[task] task u-abc started" event looked identical to a real
  "[Stream stale]" alert.
- Two consecutive system messages used the same 16px gap as
  any other message, so when both were red they read as one
  block.

Fix:
- `MessageList.tsx::LegacyMessage` now applies
  `message-system-info` or `message-system-error` based on
  `m.isError`.
- `MessageList.css` adds a neutral grey palette for the info
  variant (`var(--chat-text-muted)` text on a soft grey pill),
  keeping the same shape so the chat rhythm doesn't shift.
- Consecutive `.message-system-error` siblings get a +8px margin
  and a thin top-border divider so the eye tracks them as
  separate events. The first / last system messages in a run
  still use the parent's 16px gap, so the chat doesn't grow
  taller overall.

## Files touched

- `aethercode-desktop/src/lib/methods.ts` — wire-name fix
- `aethercode-desktop/src/components/MemoryPanel.tsx` — defensive refresh + useMemo
- `aethercode-desktop/src-tauri/src/lib.rs` — `daemon_jvm_args()` + applied in spawn
- `aethercode-desktop/src/store/index.ts` — bumped STREAM_STALE_MS, permission-aware watchdog
- `aethercode-desktop/src/components/MessageList.tsx` — info vs error class
- `aethercode-desktop/src/components/MessageList.css` — info palette + consecutive spacing
- `aethercode-cli/src/main/java/org/aethercode/cli/DaemonRunner.java` — dual-register
- `aethercode-cli/src/test/java/org/aethercode/cli/DaemonRunnerTest.java` — updated + 2 new tests

## Tests

- 843/843 desktop vitest pass.
- 10/10 `DaemonRunnerTest` pass.
- 19 modules build success.
- 0 regressions.

## Release

- `D:\work\workspace\idea\engine\AetherCode\aethercode\dist\aethercode-0.2.18.jar` (55 MB)
- `D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.18\AetherCode.exe` (3.9 MB)
- `D:\work\workspace\idea\engine\AetherCode\AetherCode.exe` (3.9 MB)
