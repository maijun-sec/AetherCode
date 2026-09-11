# R199 — Multi-daemon cwd switching (cwd pick = spawn new daemon + swap)

## TL;DR
Switching cwd in the desktop now spawns a fresh daemon rooted at the new cwd
and promotes it to primary, instead of minting a session on the current daemon.
Pre-R199, the cwd pick reached the WS layer first — the WS was bound to the
current primary daemon (e.g. the abc_1 daemon), so `createSession({cwd: abc_2})`
wrote the new transcript to `abc_1/.aethercode/sessions/<id>.jsonl` and bound
the engine's cwd to abc_2 — but the engine *process* was still the abc_1 JVM,
with all its cwd-bound state (loaded files, model context). The user saw
"I picked abc_2 but it operated on abc_1" — captured in the screenshot where
`cd /d D:\tmp\abc_1 && dir` ran while the CWD pill said `D:\tmp\abc_2`.

## The fix
R199 makes the cwd pick a multi-step structural change, not just a session
swap. The flow now is:

1. `pre_warm_daemon(newCwd)` — spawn a fresh JVM in `newCwd`, wait for
   `/health` to come up. Returns a `DaemonInfo` for the pre-warm slot.
2. `swap_to_pre_warm()` — kill the current primary, promote the pre-warm to
   primary, re-open the WS.
3. RPC `createSession({cwd: newCwd})` on the new primary — gives the engine a
   clean session id + transcript rooted at `newCwd`, with no leakage from
   the old session.

All three steps live in the Rust `set_cwd_daemon` function, called from the
Tauri `set_cwd` command. The renderer no longer drives the swap itself — it
just calls `invoke('set_cwd', { path })` and gets back `{cwd, sessionId,
swapped}`.

## Code changes

### `aethercode-desktop/src-tauri/src/lib.rs` (R199)
- `set_cwd` is now a thin wrapper that delegates to `set_cwd_daemon` (with
  full R199 docstring explaining the structural reason).
- New `set_cwd_daemon` function (~100 lines, internal helper, not registered
  as a Tauri command — the public surface stays as `set_cwd`):
  - Persists `desktop-state.json` BEFORE swapping (crash-safe).
  - If no daemon is up yet, just updates the slot and returns
    `{cwd, sessionId: null, swapped: false}`.
  - Otherwise: pre_warm → swap → createSession, returns
    `{cwd, sessionId, swapped: true}`.
- Removed dead `kill_running_daemon` helper (pre-R171 path).

### `aethercode-desktop/src/lib/methods.ts` (R199)
- `setCwd` return type changed from `Promise<string>` to
  `Promise<{cwd: string; sessionId: string|null; swapped: boolean}>`.

### `aethercode-desktop/src/store/index.ts` (R199)
- `setCwd` action now calls `rpc.setCwd(path)` (the Rust Tauri command) instead
  of `rpc.createSession({cwd: path})` (the WS-layer RPC that hit the wrong
  daemon).
- Short-circuits when `curCwd === newCwd` (same path normalization as R197:
  trailing-separator-stripped, case-insensitive).
- The `swapped` flag drives the post-swap UI flow:
  - `swapped=true`: clear `currentSessionId`/`messages`, set the new id from
    the response, set `daemonInfo=null` so the WS re-init path kicks in,
    refresh sessions/tools/providers, and pre-warm a sibling.
  - `swapped=false`: no daemon was up yet, so just update the cwd slot and
    trigger `initialize()` (which calls `ensure_daemon` to spawn a fresh
    daemon rooted at the new cwd).
- Fixed `resetDaemon` call site to use `real.cwd` (the new object shape)
  instead of treating the response as a string.

### `aethercode-desktop/src/components/ProjectGroup.tsx` (R199)
- `useNewSessionInCwd` now distinguishes "user clicks + on the project
  they're already on" from "user clicks + on a different project":
  - Same cwd: `createNewSession()` (cheap, no daemon swap). This avoids the
    1-2s JVM startup cost of the swap dance on every + click in the current
    project.
  - Different cwd: `setCwd(cwd)` (full swap dance).

## Tests
- 909 → 913 tests (+4 source-pin tests).
- `R197 + R199` describe block: the renderer-side call site is pinned to
  `rpc.setCwd(path)` so a refactor can't fall back to the WS-layer
  `rpc.createSession({cwd})` (which is the Layer-2 bug).
- `R197 + R199`: the `setCwd` return type is pinned to include `sessionId`
  and `swapped` so a refactor that drops these breaks the test.
- `R197 + R199`: the Rust `set_cwd` is pinned to delegate to `set_cwd_daemon`
  with the exact `(path, app, state)` argument shape.
- `R197 + R199`: the three R199 calls in `set_cwd_daemon` are pinned in order
  — `pre_warm_daemon` < `swap_to_pre_warm` < `rpc_call("createSession")`. A
  refactor that re-orders them (e.g. createSession before swap) would
  re-introduce the Layer-2 bug and break this test.
- `R199: ProjectGroup "＋" on the current cwd skips the daemon-swap dance`:
  `useNewSessionInCwd` is pinned to short-circuit to `createNewSession` when
  `curCwd === cwd`, so a refactor that always calls `setCwd` would break it.

## Build artifacts (v0.2.42)

| File | Size | SHA256 |
| --- | --- | --- |
| `AetherCode.exe` | 3,952,128 | `D100B335D804506154353032A8FA2CC01EA6F5C67F75CD3C9564566BE6470693` |
| `aethercode-0.2.42.jar` | 55,310,943 | `60C9FA247704E116F7E6A29EC7D7461D9522D5AFCCD35B40317560532959CD7B` |
| `AetherCode_0.2.42-setup.exe` (NSIS) | 2,105,207 | `F75E3A2878DECC8846AD358960AE3961BFC6F23B47D98B9F63BB9B8CC2F88786` |
| `AetherCode_0.2.42.msi` (MSI) | 2,588,672 | `6AC151F5B24DD1D2322A5CC199A4B3FB5D2DAF9DC5A5F1BCC6AD2BCFF29567AC` |

- exe: 3,949,056 → 3,952,128 (+3,072 bytes for the new `set_cwd_daemon`
  helper + the extra `app: AppHandle` plumbing).
- jar: same size as v0.2.41 (no Java changes; R199 is desktop-only).

## Location
All artifacts at `D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.42\`.
The `dist/aethercode-0.2.42.jar` is also deployed to
`D:\work\workspace\idea\engine\AetherCode\aethercode\dist\` so the desktop's
`find_jar_path` ancestor-walk picks it up first (and not the stale
`dist/aethercode-0.2.41.jar`).

## Daemons killed
All running daemons (port 17888 abc_1 + port 18889 default) and AetherCode.exe
were terminated before deploying v0.2.42. The user must re-launch
`release\aethercode-0.2.42\AetherCode.exe` to pick up the new binary + jar.

## Lessons
1. **"Set cwd" in a multi-daemon world is not a session op — it's a daemon
   op.** The session/cwd binding is per-daemon (sessions live in
   `<cwd>/.aethercode/sessions/`), so the only way to get a daemon that
   "lives" in the new cwd is to spawn one rooted there. Calling
   `createSession` on the wrong daemon is not a no-op — it actively corrupts
   the session store (transcript lands in the wrong dir) and creates a
   ghost engine state (cwd = abc_2, model context = abc_1).
2. **The renderer's "createSession" path is a Layer-2 trap.** Pre-R199 the
   renderer called `rpc.createSession({cwd})` on the WS, which was always
   bound to the current primary. The whole point of `pre_warm` + `swap` is
   to make the new daemon primary BEFORE the renderer issues any
   project-specific commands. Don't expose the layer-2 path to the
   renderer; have the renderer go through one Tauri command and let Rust
   own the swap.
3. **"Same cwd" + "is the user picking a new cwd" must be checked at the
   top of setCwd.** Pre-R199 the renderer did this normalization, but the
   daemon side did NOT — it always went through createSession. With
   R199+R197 normalization at the renderer, the user clicking the same
   folder in the picker short-circuits, no swap, no new session. The
   same-cwd + button goes through `createNewSession` directly.
4. **Always check the post-build SHA256 on BOTH the source path and the
   "dist" mirror.** Pre-R199, the v0.2.41 jar in `aethercode/dist/` was
   stale (size-matched but content was v0.2.34's) and the desktop picked
   it up via ancestor-walk. R199's first build step must copy to dist/ as
   well as release/.
