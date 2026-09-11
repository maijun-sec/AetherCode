# R201 — Second UX feedback round (5 fixes from one screenshot)

## TL;DR
The user pasted one screenshot + 5 observations. Two were genuine bugs
(#1 cwd-switch reconnect loop, #4 listSessions still empty) that R200
missed. The other three are UX defaults (#2 missing-command banner
already shipped in R200, #3 long preamble fold, #5 bottom drawer
visibility).

## Per-issue

### #1 — "切换 cwd 的时候，又在重新连接 daemon"
- **Root cause**: `set_cwd_daemon` (Rust) does pre_warm → swap →
  createSession. The swap step kills the OLD primary's child
  process. The OLD primary's WS task (still running because the
  kill is async from the WS task's perspective) sees the socket
  close and emits `daemon.disconnected` over the
  `ws-notify` channel. The renderer's `daemon.disconnected`
  handler invokes the `disconnect` Tauri command, which
  clears `ws_tx` + `daemon` state on the Rust side. Then
  `scheduleReconnect` calls `ensure_daemon` — and the
  brand-new primary's `ws_tx` gets wiped. The user sees
  the "reconnecting" spinner every time they switch cwd.
- **Fix (R201)**:
  1. `AppState` gets a new field `swapping: Arc<AtomicBool>`.
  2. `set_cwd_daemon` flips it true at the top of the
     function (when a daemon is actually alive) and a
     tiny `ScopingGuard` (RAII) flips it back to false
     on drop (normal or error path).
  3. `open_ws` takes `swapping: Arc<AtomicBool>` (cloned
     into the WS task), and the task's disconnect
     notification goes through a closure that consults
     the flag: `if swapping { return; }` — i.e. the OLD
     daemon's WS close during the swap is swallowed.
- **Why an `Arc<AtomicBool>` not just a field on
  `AppState`**: the WS task is a `tokio::spawn` closure
  that outlives the `open_ws` call. We can't `State<'_,
  AppState>` into a `Send + 'static` future, so we
  clone the flag and move it into the task. The swap
  guard's set/clear happens via the same Arc clone
  held in `AppState.swapping`.

### #2 — empty bash
- This is model behaviour (the model emits a `bash` tool
  call with an empty `command` field). R200 already
  added a per-tool hint in the permission banner
  (`missing \`command\` parameter`) and a tighter
  error-output cap (200 chars). R201 leaves the
  display in place but doesn't try to stop the model
  at the renderer layer — that's a system-prompt or
  tool-schema problem on the daemon side. We've
  flagged it for the next round.

### #3 — "输出仍然没有折叠的支持"
- Pre-R201 the in-app folding (`<details>`) only
  worked when the agent emitted `## ` / `### `
  headings (R195's `parseAgentSections`). The user's
  screenshot showed a 1 KB preamble (numbered list +
  "支持类型" + "让我开始创建 todo list..." prose) with
  zero fold affordance — the parser returned an empty
  sections array, and the renderer dumped the whole
  markdown into the chat scrollable.
- **Fix**: `AgentMarkdownMessage` now detects a
  long preamble (`markdown.length > 500` chars AND
  no parseable sections) and wraps it in a
  default-closed `<details>`. The summary shows the
  first 80 chars of prose (heading marks + formatting
  stripped) so the user can recognise the message
  without expanding it. The fold respects the same
  chevron / hover / tooltip patterns as the
  R195 sections.

### #4 — "任务执行时，左侧仍然是 0 session"
- **Root cause**: R200 fixed the wire name
  (`session/list` → `listSessions`) so the call
  succeeded, but the daemon still returned
  8 sessions with **no `cwd` field** for any of
  them. The `useSessionList` query in TanStack
  Query succeeded (no error) but the session
  list was useless to the renderer — the
  `ProjectGroup` couldn't group them and the
  `SessionListFilter`'s `totalCount` widget
  rendered "0 sessions" because TanStack Query
  had cached a stale "session/list failed"
  result with `totalCount: 0`.
- **Why no cwd**: R198 added the listSessions-
  side `memoryStore.sessionStore().getSession(id)`
  lookup but never closed the loop on the
  createSession side. `createSession` RPC
  creates the engine session + sets the engine's
  cwd, but it never wrote the (sessionId → cwd)
  row to the SQLite-backed memory store. The
  lookup then returned `null` and the field
  was omitted.
- **Fix**: `AetherCodeMethods.createSession` now
  calls `memoryStore.sessionStore().upsertSession(
  newId, resolvedCwd, null)` after the
  `engine.setCwd` call. The row is idempotent
  (upsertSession is a no-op if the session
  already has cwd recorded), so switchProject's
  existing path is unaffected.

### #5 — "最下面的 session detail 是什么？"
- The `SessionDetailsDrawer` was using
  `const open = sessionId !== null`, which is
  essentially always true once a session is
  active. The Continue / Pause / Stop bar was
  therefore permanently visible at the bottom
  of the page — and the user had no idea what
  it was for, because they'd never explicitly
  opened it.
- **Fix**: `SessionDetailsDrawer` now accepts
  an explicit `open` prop. `App.tsx` passes
  `open={p.detailsDrawerOpen}`, which is
  toggled by the Ctrl+Shift+D shortcut. The
  default is closed. Legacy callers (e.g.
  SessionPage that mounts the drawer directly)
  fall back to `openProp ?? (sessionId !==
  null)` so they still work.

## Code changes (summary)

| File | R201 changes |
| --- | --- |
| `aethercode-desktop/src-tauri/src/lib.rs` | `AppState.swapping: Arc<AtomicBool>` + `ScopingGuard` RAII; `open_ws` takes the Arc and consults it in `notify_disconnect`; `set_cwd_daemon` lifts the guard for the swap |
| `aethercode/aethercode-protocol/.../AetherCodeMethods.java` | `createSession` writes the (sessionId → cwd) row to the memory store |
| `aethercode-desktop/src/components/MessageList.tsx` | `AgentMarkdownMessage` auto-wraps long preambles in a `<details>` (`PREAMBLE_AUTO_COLLAPSE_CHARS = 500`, `PREAMBLE_SUMMARY_CHARS = 80`) |
| `aethercode-desktop/src/components/session/SessionDetailsDrawer.tsx` | New `open` prop with legacy fallback |
| `aethercode-desktop/src/App.tsx` | Passes `open={p.detailsDrawerOpen}` to `SessionDetailsDrawer` |

## Tests
- 922 → 926 tests (+4 R201 source-pin):
  1. `set_cwd_daemon` flips a swap guard
  2. `createSession` writes the cwd binding
  3. `SessionDetailsDrawer` uses the parent's open flag
  4. Long preamble auto-collapses

## Build artifacts (v0.2.44)

| File | Size | SHA256 |
| --- | --- | --- |
| `AetherCode.exe` | 3,955,712 | `58E722B42616A0FC5A505FBFE3F65569847A3A8BF3D2466D6549AB3706A35461` |
| `aethercode-0.2.44.jar` | 55,311,094 | `F9B3BB7EE652945060F14A8F8CCA4DA3E272E2160DBAE93D21DFD75D521756D2` |
| `AetherCode_0.2.44-setup.exe` (NSIS) | 2,109,166 | `52A5FDAC2074459B0C5330948002AC870999ABC56A8C9AA6B8C9C97DD2F58098` |
| `AetherCode_0.2.44.msi` (MSI) | 2,592,768 | `AF85B7219932A619F428B6CBF864D7DB9E1DD990453296C485D908AE29F220F5` |

- exe: 3,954,688 → 3,955,712 (+1,024 bytes for the swap guard +
  ScopingGuard + open_ws signature change + preamble collapse)
- jar: 55,311,019 → 55,311,094 (+75 bytes for the createSession
  upsertSession block)

## Location
`D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.44\`.
`dist/aethercode-0.2.44.jar` is also deployed.

## Daemons killed
All running daemons and AetherCode.exe were terminated before
deploying v0.2.44. The user must re-launch
`release\aethercode-0.2.44\AetherCode.exe`.

## Lessons
1. **"0 sessions" can be a stale cache, not just a missing
   field.** R200 fixed the wire name so the call worked,
   but TanStack Query had a cached `totalCount: 0` from
   the R200-era `session/list METHOD_NOT_FOUND` result.
   Cached zero-states are sticky. The fix at the daemon
   layer (write the cwd row) closed the loop; the
   renderer-side fix would be a query `staleTime: 0` +
   explicit `invalidate` on focus, but that's a band-aid.
2. **Cross-async-task state needs `Arc<AtomicBool>`**, not
   `State<'_, AppState>`. The WS task is `'static`, so it
   can't hold a `State` reference. The swap guard had to
   be a clonable, Send-able shared flag. Easy mistake: try
   to pass `state.swapping` into the task and fail to
   compile for 10 minutes.
3. **RAII guards > manual reset.** The ScopingGuard's
   `Drop` impl means even a `?` early-return from
   set_cwd_daemon still flips the flag back. The
   alternative — `flag.store(false)` before every
   `return` — is one missed line away from a deadlock.
4. **"Show specifics" needs a "default"** for the case
   when the model doesn't emit headings. R195 only folded
   when the agent wrote `##` / `###`. R201 folds any
   preamble over 500 chars. The threshold is small
   enough that prose balloons but not so small that
   short agent messages get folded unnecessarily.
5. **"Always-open when sessionId is set" is a footgun**
   in any drawer / panel / modal. A prop that derives
   visibility from a related state (e.g. sessionId)
   is convenient in tests but surprises users in
   production. The user's "最下面的 session detail 是
   什么?" is the canonical symptom. R201 makes the
   drawer explicitly opt-in via the parent's
   `detailsDrawerOpen` flag.
