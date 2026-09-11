# R99-R102: UX Polish + Persistence for the R98 Permission Stack

**Status**: SHIPPED 2026-08-18  
**Type**: AetherCode platform-level follow-up to R98  
**Build**: 4112 Java tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS.

## Goal

The R98 stack (per-tool-type permission matrix + skip confirmation
+ design-first workflow) is functional but invisible to the user
unless the user can SEE the state and PERSIST the state across
restarts. R99-R102 close those gaps.

R99: surface skip-confirmation state in the TUI + Desktop status bar.
R100: per-tool "safe" badge so the user knows which tools auto-allow.
R101: live-reload `.aethercode/config.json` so the matrix + workflow
mode take effect without restarting the daemon.
R102: persist the skip-confirmation counter across engine restarts.

## What's new (R99-R102)

### R99 — Skip-confirmation state in status bar

- `QueryEngine.onUserPrompt(Consumer<String>)` hook (R98) is now
  complemented by a `NOTIFY_SKIP_CONFIRMATION` JSON-RPC
  notification fired on every counter change.
- `AetherCodeMethods.setSkipConfirmation` emits the notification
  with `source: "rpc"`.
- `MatrixPermissionPolicy.setOnSkipConsumed(IntConsumer)` fires the
  notification with `source: "consume"` on every consumed round.
- `AetherCodeEngine.setSkipConsumedListener(IntConsumer)` remembers
  the listener so a future `swapPolicy` (config reload) re-applies
  it.
- TUI `StatusBar.tsx` shows `⏩ skip N` next to the mode line when
  the counter is positive. TUI `commands.ts` adds `/skip <N|off>`.
- Desktop `StatusBar.tsx` shows the same badge in the left cluster.
  Store wires `skip_confirmation` notifications to the engine
  state.
- `getState` RPC adds `skipConfirmationRemaining` so the UI can
  paint the initial state without waiting for a notification.

### R100 — Per-tool "safe" badge

- `AetherCodeMethods.listToolActions` RPC. For each tool:
  - `defaultAction` (ALLOW / ASK / DENY) computed by:
    1. If `tool.isReadOnly(emptyInput)` is true → ALLOW (defence in depth).
    2. Otherwise feed a typical sample input through
       `OpKindDetector` to get the default op-kind, then look up
       the matrix.
  - `isSafe` = `defaultAction == ALLOW`.
  - `defaultOpKind` (READ / CREATE / MODIFY / DELETE / EXEC / LIST).
  - `samplePath` (the path used in the lookup, for transparency).
- TUI `/tool-actions` slash command shows the assessment.
- Desktop `EngineState` extended with `skipConfirmationRemaining`;
  `ToolActionInfo` interface + `listToolActions()` typed wrapper.

### R101 — ConfigWatcher (live-reload `.aethercode/config.json`)

- New `org.aethercode.config.ConfigWatcher` (mirrors `RulesWatcher`).
  Watches `<cwd>/.aethercode/` for `ENTRY_CREATE` / `ENTRY_MODIFY`
  / `ENTRY_DELETE` of `config.json`. Filters by file name so
  unrelated files in `.aethercode/` (sessions, rules) do not
  trigger a reload. 100 ms debounce window.
- `AetherCodeEngine` starts a `ConfigWatcher` at boot. On change:
  1. Re-read the config.
  2. If the matrix changed, `MatrixPermissionPolicy.withMatrix(newMatrix)`
     and `swapPolicy`. The remembered skip-consumed listener is
     re-applied to the new policy instance.
  3. Re-render the system prompt (workflow mode may have
     toggled `design-first` → `legacy` or vice versa).
  4. Re-apply the project's default skip count.
- Best-effort semantics: a failing listener logs at warn and the
  cached state stays.

### R102 — Persistent skip-confirmation counter

- `SkipConfirmationRegistry(Path persistenceDir)` — when constructed
  with a non-null dir, every `set` and `consumeOne` call writes
  `<persistenceDir>/<sessionId>/skip-confirmation.json` with
  `{ "remaining": N, "updatedAt": ms }`.
- `loadFromDisk(sessionId)` reads the file back. Missing /
  malformed / `remaining: 0` → 0 (no exception).
- `AetherCodeEngine` derives the persistence root from the
  `SessionStore.dir()` (R106). When the engine has a session
  store, the counter survives restarts.
- `clear()` persists `remaining: 0` (tombstone) so a future
  load doesn't accidentally resurrect a stale value.

## Resolution order (final)

```
For each tool call (R99-R102 cumulative):

1. OpKindDetector.detect(tool, input, projectRoot) -> OpKind
2. extractPath(tool, input) -> path
3. matrix.lookup(tool, path, opKind) -> Action
4. tool.isReadOnly(input) -> ALLOW (defence in depth)
5. matrix == DENY -> DENY; emit nothing extra
6. matrix == ALLOW -> ALLOW; emit nothing extra
7. matrix == ASK
   7a. skipRegistry.consumeOne(sid)
        -> ALLOW; emit NOTIFY_SKIP_CONFIRMATION (source=consume)
        -> persist remaining to disk (R102)
   7b. Else fall through to ProjectPermissionPolicy.check:
        i.   deny rules
        ii.  ask rules
        iii. allow rules
        iv.  mode fallback

Cross-cutting flows:
- setSkipConfirmation RPC  -> set + emit notify (source=rpc) + persist
- config.json on disk      -> ConfigWatcher -> swap matrix
                                         -> re-render prompt
                                         -> re-apply default skip
```

## Test counts

| Module | R99-R102 delta | Cumulative |
|---|---|---:|
| aethercode-config | +7 (ConfigWatcher) + 10 (persistence) = +17 | 111 |
| aethercode-permission | +4 (SkipConsumedListener) + 8 (ListToolActions) = +12 | 97 |
| aethercode-sdk | 0 | 168 |
| aethercode-prompts | 0 | 106 |
| All others | 0 | unchanged |
| **Total** | **+29 R99-R102** | **4112** |

(Last full reactor test run: 4112 / 0 fails / BUILD SUCCESS)

## Files added/modified (R99-R102)

### New

- `aethercode/aethercode-config/.../ConfigWatcher.java` + test (7)
- `aethercode/aethercode-config/.../SkipConfirmationRegistry.java` (extended, persistence added) + persistence test (10)
- `aethercode/aethercode-permission/.../SkipConsumedListenerTest.java` (4)
- `aethercode/aethercode-permission/.../ListToolActionsTest.java` (8)
- `aethercode/docs/R99-R102-UX-AND-PERSISTENCE.md` (this file)

### Modified

- `aethercode/aethercode-permission/.../MatrixPermissionPolicy.java`
  — added `setOnSkipConsumed(IntConsumer)` + listener fire in check()
- `aethercode/aethercode-permission/.../ProjectPermissionPolicy.java`
  — already had `rules()` accessor from R98; no change
- `aethercode/aethercode-protocol/.../AetherCodeMethods.java` —
  `NOTIFY_SKIP_CONFIRMATION` constant; `setSkipConfirmation` emits
  notification; `getState` adds `skipConfirmationRemaining`;
  constructor wires `engine.setSkipConsumedListener` to a
  notifier; new `listToolActions` RPC + `intOrThrow` helper
- `aethercode/aethercode-protocol/.../HttpJsonRpcServer.java` —
  HTTP allowlist + switch dispatch include `listToolActions`
- `aethercode/aethercode-sdk/.../AetherCodeEngine.java` —
  `setSkipConsumedListener` remembers; `skipConfirmationRegistry`
  is now constructed with a persistence root (from
  `SessionStore.dir()`); loads on boot; constructor wires
  `ConfigWatcher`; on change, swaps matrix / re-renders prompt /
  re-applies default skip; `matrixEquals` helper
- `aethercode/aethercode-tui/.../state.ts` — `skipConfirmationRemaining`
  field, action + reducer case
- `aethercode/aethercode-tui/.../tui.tsx` — `skip_confirmation`
  notification handler; seeds initial counter from getState
- `aethercode/aethercode-tui/.../commands.ts` — `/skip` + `/tool-actions`
  slash commands + SLASH_HELP entries
- `aethercode/aethercode-tui/.../components/StatusBar.tsx` — `⏩ skip N`
  badge next to mode
- `aethercode/aethercode-desktop/.../lib/methods.ts` — `EngineState`
  adds `skipConfirmationRemaining`; `ToolActionInfo` interface;
  `listToolActions()` typed wrapper
- `aethercode/aethercode-desktop/.../store/index.ts` — `skip_confirmation`
  notification handler patches `engineState`
- `aethercode/aethercode-desktop/.../components/StatusBar.tsx` — skip
  badge in left cluster
- `aethercode/aethercode-desktop/.../components/StatusBar.css` — `.status-skip`
  style

## Cross-cutting design lessons (R99-R102)

1. **Listener-on-policy is brittle; remember the listener on the
   engine.** `MatrixPermissionPolicy.setOnSkipConsumed` accepts a
   listener, but `withMatrix` produces a new policy instance and
   the old listener is gone. R101's fix: `AetherCodeEngine` keeps
   `skipConsumedListener` and re-applies it after a `swapPolicy`.
   Without this, the protocol's NOTIFY_SKIP_CONFIRMATION would
   stop firing the moment the user edited `.aethercode/config.json`.

2. **Windows file-system watcher quirks are real.** The
   `createAndModifyConfig_firesChangeEvent` test originally
   asserted two separate fires (create + modify) but Windows
   coalesces them inside the 100 ms debounce window. The
   relaxed assertion ("first fire is the critical one") matches
   the RulesWatcher pattern: the watcher is best-effort, the
   engine survives a missed event, the user can re-save.

3. **Persistence is a constructor flag, not a runtime toggle.**
   `SkipConfirmationRegistry(Path persistenceDir)` is set once
   at engine construction. Mid-flight toggling would be a
   footgun (the in-memory state diverges from the on-disk
   state). The single-arg `SkipConfirmationRegistry()` is the
   in-memory default; tests that need persistence pass the
   dir explicitly.

4. **"Safe" is a UI hint, not a contract.** `listToolActions`
   computes `isSafe` from the tool's TYPICAL call shape; a
   user who calls `file_write` with a path the matrix says
   ASK is still prompted. The badge helps users understand
   the matrix; it does not replace the per-call check.

5. **Tombstone the cleared counter, don't delete the file.**
   `clear()` persists `remaining: 0` so a future `loadFromDisk`
   doesn't see a missing file and assume "never had a counter"
   (which would let a session inadvertently inherit a
   default-applied counter from a fresh config).

6. **The ConfigWatcher filters by file name.** A naive
   ENTRY_CREATE/MODIFY listener would fire on every write to
   `sessions/`, `rules/`, etc. inside `.aethercode/`. Filtering
   by `config.json` (and ignoring everything else) means the
   engine only re-renders when the actual config changes.

7. **`engine.swapPolicy` is not enough; the engine must also
   re-wire the listener.** A subtle gotcha: after `swapPolicy`,
   the live policy is a new instance, but the protocol-side
   listener was attached to the old instance. R99's `setSkipConsumedListener`
   saves the listener on the engine and re-applies it on every
   config reload.

## Open follow-ups (post-R102)

- **R103**: TUI command palette integration — `/skip` and
  `/tool-actions` should be reachable via Ctrl+P.
- **R104**: Desktop "safe" badge in the ToolsPanel (currently
  the Desktop shows the badge only in the StatusBar; a per-tool
  list view would be more discoverable).
- **R105**: persist `permissionMode` changes across restarts
  (currently session-scoped only).
- **R106**: AetherCode server-side statistics — the daemon
  could report "this session used skip-confirmation 4 times
  out of 7 total prompts" so the user can see whether the
  feature is being used.
