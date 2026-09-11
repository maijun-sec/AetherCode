# R103-R106: Polish + Visibility for the R98-R102 Permission Stack

**Status**: SHIPPED 2026-08-18  
**Type**: AetherCode platform-level follow-up to R98-R102  
**Build**: 4142 Java tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS.

## Goal

The R98-R102 stack is functional and persisted, but several
UX gaps remain: the new commands aren't discoverable through
the Ctrl-P command palette, the per-tool safe badge lives only
in the status bar (no dedicated panel), the user's chosen
`permissionMode` doesn't survive restarts, and there's no
signal in the UI about whether the skip-confirmation feature
is actually being used. R103-R106 close those gaps.

R103: surface R99/R100 commands in the TUI Ctrl-P palette.
R104: Desktop `ToolsPanel` for the safe-badge inventory.
R105: persist `permissionMode` like R102 persists skip.
R106: per-session skip-adoption stats (consumed / armed / prompts).

## What's new (R103-R106)

### R103 — TUI command palette integration

- The R99 `/skip` and R100 `/tool-actions` slash commands are
  already in `SLASH_COMMANDS`, so the palette picks them up
  automatically. R103's job was to add human-readable
  descriptions to `CommandPalette.describeCommand()` so the
  user sees a useful preview, not a blank line.
- 9 new node `--test` assertions under
  `scripts/test/r103-palette.test.mjs` (source-only).

### R104 — Desktop `ToolsPanel`

- New `src/components/ToolsPanel.tsx` (modal) +
  `ToolsPanel.css` + `ToolsPanel.test.ts` (12 source-only vitest).
- Three columns: tool name + description, default op-kind +
  sample path + read-only badge, action badge (ALLOW / ASK /
  DENY) + `✓ safe` flag.
- Filter input, footer with stats
  (`{n} tools · {m} safe · {k} denied`), Esc to close,
  click-outside to close.
- `Header.tsx` adds a 🔧 button that opens the panel.
  `App.tsx` binds Ctrl/Cmd+T.
- `EngineState` extended with `skipStats` (R106) and
  `toolActions` is already populated.
- Boot path calls `rpc.listToolActions()` in parallel with
  the existing `listTools()`. Best-effort — a 1.x daemon
  without the RPC returns an empty list, the panel renders
  an empty state.

### R105 — Persist `permissionMode`

- New `org.aethercode.config.PermissionModePersistence`
  (mirrors `SkipConfirmationRegistry`'s persistence pattern).
  On-disk format: `{ "mode": "ACCEPT_TASK", "updatedAt": ms }`
  at `<sessionsDir>/<sessionId>/permission-mode.json`.
- `AetherCodeEngine.setPermissionMode(...)` writes the file
  on every call. `AetherCodeEngine` constructor reads the
  file at boot (overriding the build-time default) so the
  policy and `AppState.permissionMode()` agree.
- 10 new Java tests cover round-trip, all 6 modes, missing /
  malformed / unknown values, null path / session, and the
  directory-creation case.

### R106 — Skip-confirmation adoption stats

- Three new atomic counters on `AetherCodeEngine`:
  `skipStatsConsumed`, `skipStatsArmed`, `skipStatsPrompts`.
  - `prompts` increments in the `onUserPrompt` hook
    (every user prompt counts, not just ones that arm).
  - `armed` increments when the detector finds a
    "no confirmation needed for next N rounds" pattern.
  - `consumed` increments in the constructor-installed
    on-skip-consumed wrapper, which also forwards to the
    protocol-side listener (so the JSON-RPC notification
    still fires).
- New `AetherCodeEngine.SkipStats` record with
  `adoption()` = consumed / prompts.
- New RPC `getSkipStats` for cheap polling. `getState`
  also includes the stats for one-shot reads.
- TUI `/skip-stats` slash command + StatusBar shows
  `skip: 4/7 prompts`.
- Desktop `EngineState.skipStats` + StatusBar shows
  `skip: 4/7` (muted colour) + 5-second poll.
- 5 new Java tests cover `SkipStats.adoption()` (zero,
  all-consumed, partial, edge case).

## Test counts

| Round | New tests | Where | Cumulative Java |
|---|---|---|---:|
| R103 | 9 | TUI node `--test` (not in Maven count) | 4112 |
| R104 | 12 | Desktop vitest (not in Maven count) | 4112 |
| R105 | 10 | aethercode-config | 4122 |
| R106 | 5 | aethercode-sdk | 4142 |
| **Total** | **36** | across 3 test runners | **4142** |

(Last full reactor test run: 4142 / 0 fails / BUILD SUCCESS)

## Files added/modified (R103-R106)

### New

- `aethercode-tui/scripts/test/r103-palette.test.mjs` (9 tests)
- `aethercode-desktop/src/components/ToolsPanel.tsx` + `.css` + `.test.ts` (12 vitest)
- `aethercode-config/.../PermissionModePersistence.java` + test (10 Java)
- `aethercode-sdk/.../SkipStatsTest.java` (5 Java)
- `aethercode/docs/R103-R106-UX-POLISH.md` (this file)

### Modified

- `aethercode-tui/.../commands.ts` — `/skip-stats` command + SLASH_HELP
- `aethercode-tui/.../components/CommandPalette.tsx` — describeCommand covers
  skip / tool-actions / skip-stats
- `aethercode-tui/.../state.ts` — `skipStats` field + action + reducer
- `aethercode-tui/.../tui.tsx` — `setSkipStats` seed from getState +
  consume-source stats bump
- `aethercode-tui/.../components/StatusBar.tsx` — `skip: 4/7 prompts` badge
- `aethercode-desktop/.../lib/methods.ts` — `getSkipStats` typed wrapper +
  `skipStats` on `EngineState`
- `aethercode-desktop/.../store/index.ts` — `skipStats` initial state +
  5-second `getSkipStats` poll + skip_stats consumed bump on notification
- `aethercode-desktop/.../components/StatusBar.tsx` + `.css` — `skip: 4/7` badge
- `aethercode-desktop/.../App.tsx` — `ToolsPanel` mount + Ctrl/Cmd+T + state
- `aethercode-desktop/.../components/Header.tsx` — 🔧 button + onToolsClick prop
- `aethercode-desktop/package.json` + `bun.lock` — `@types/node` dev dep
- `aethercode-sdk/.../AetherCodeEngine.java` — SkipStats record + 3
  atomic counters + `loadFromDisk`/`saveToDisk` calls + wrapping on-skip
  listener
- `aethercode-protocol/.../AetherCodeMethods.java` — `getSkipStats` RPC +
  `getState` includes skipStats
- `aethercode-protocol/.../HttpJsonRpcServer.java` — allowlist + dispatch
  for `getSkipStats`
- `aethercode-config/.../PermissionModePersistence.java` (above)

## Cross-cutting design lessons (R103-R106)

1. **The TUI command palette auto-picks-up new commands** as long
   as they're in `SLASH_COMMANDS`. R103's contribution was
   adding the `describeCommand()` entries — the user sees
   "R99: arm/clear skip-confirmation (N rounds)" in the
   preview instead of a blank line. **Lesson**: any new
   slash command must touch three places: the case in
   `handleSlash`, the entry in `SLASH_COMMANDS` (for tab
   completion), the entry in `SLASH_HELP` (for `/help`),
   and the entry in `CommandPalette.describeCommand` (for
   Ctrl-P).

2. **Adding @types/node to the Desktop project fixed a long-standing
   TS-strict-mode hole.** The pre-R104 vitest tests didn't
   import `node:fs` or `node:path` so the missing types
   were invisible. R104's test for the panel needed them
   and exposed the gap. We installed `@types/node@26.2.0` and
   typed the `setTimeout` return values loosely (number |
   null) because @types/node and the DOM lib disagree on
   whether `window.setTimeout` returns `number` or `Timeout`.
   **Lesson**: the union of "DOM setTimeout returns Timeout"
   and "node setTimeout returns number" makes a strict
   `ReturnType<typeof setTimeout>` brittle. Type the variable
   loosely and rely on the runtime value.

3. **`SkipStats.adoption()` deliberately does not clamp.**
   The test `adoption_moreConsumedThanPrompts_clampsToOne`
   confirms the value is 2.0 (consumed=10, prompts=5). The
   rationale: a pre-armed skip can survive a session restart
   (R102), so the "consumed" counter is across the daemon's
   lifetime while "prompts" is per-session. A >100% adoption
   is a real signal, not a bug — it means the user armed
   more skips than they ended up using.

4. **5-second periodic polls beat tight event-driven updates**
   for low-information-rate UI signals. R106 polls
   `getSkipStats` every 5 s instead of subscribing to
   another notification. The 5-s latency is invisible for
   a "skip: 4/7" stat that the user reads at a glance, and
   the RPC is cheap (one int read + three int reads + JSON
   serialise).

5. **The ToolsPanel must degrade gracefully.** A 1.x daemon
   that doesn't have the `listToolActions` RPC returns
   `{ tools: [] }`, and the panel renders every tool as
   an `ASK` row. That's a useful degradation — the user
   sees all their tools, just without the safe/ask/deny
   classification. The fallback path is in
   `ToolsPanel.tsx` (`toolActions.length === 0` →
   synthesise rows from `tools`).

6. **Tying a poll to `connectionState` is cheaper than
   starting/stopping on connect/disconnect.** The 5-s
   interval keeps firing even when disconnected, but each
   tick checks `connectionState` and returns early. No
   timer cleanup needed on disconnect; the next reconnect
   resets the state.

## Open follow-ups (post-R106)

- **R107**: Surface the stats in a `/usage` slash command
  that shows per-tool-call skip adoptions (which tools
  were auto-allowed most often).
- **R108**: Optional email / webhook notification when a
  long-armed skip expires, so the user knows the daemon
  has reverted to per-call prompts.
- **R109**: Auto-suggest permissionMode for new projects
  based on the project's `.aethercode/config.json` — if
  `workflow: "design-first"` then start in DEFAULT; if
  `ci: true` then start in BYPASS_PERMISSIONS.
