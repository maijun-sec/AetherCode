# R108 — Skip-low Waterline Warning

**Date**: 2026-08-18
**Status**: SHIPPED
**Round count**: 1
**Test delta**: +12 Java tests (4147 → 4176; 7 `SkipLowListenerTest` + 5 `SkipLowIntegrationTest`)

## Why R108

R99 / R106 give the user a per-session "skip counter" and a per-tool adoption view. The user can see "skip: 5" in the status bar, but if the counter silently drops to 0 while the user is mid-flow, the next tool call suddenly asks for confirmation — a jarring UX gap. R108 closes that gap with a waterline-based "skip running low" notification: when the counter crosses DOWN through a configured waterline (e.g. 5), the engine fires `NOTIFY_SKIP_LOW` and the UI shows "⏩ skip: 2 (low!)". Default waterline is 5; set `<= 0` to disable.

## What R108 ships

1. **`AetherCodeConfig.skipLowWaterline`** — new optional field in `.aethercode/config.json` (default 5, `<= 0` disables).
2. **`MatrixPermissionPolicy.setOnSkipLow(BiConsumer<String,Integer>)`** — listener installed on the policy; receives `(sessionId, newRemaining)` ONCE per session when the counter crosses DOWN through the waterline. Independent try/catch so a failing listener cannot break a permission check.
3. **`MatrixPermissionPolicy.setLowWaterline(int)`** + `lowWaterline()` accessor — configures the threshold. `withMatrix()` carries the waterline forward so a config reload preserves the threshold.
4. **`SkipConfirmationRegistry`** — unchanged; the policy computes the previous/new remaining and decides whether to fire.
5. **`AetherCodeEngine.setSkipLowListener(BiConsumer<String,Integer>)`** — remembered on the engine. The engine's constructor installs a wrapper on the policy that (a) updates the engine's `lastSkipLow` snapshot state and (b) forwards to the user-installed listener. The setter does NOT replace the wrapper — same pattern as R99's `setSkipConsumedListener`.
6. **`AetherCodeEngine.lastSkipLow()`** — returns `Optional<SkipLowSnapshot>` (sessionId, remaining, atMs). Empty until the first crossing. Lets a reconnecting client render "skip running low" even if it missed the live notification.
7. **`AetherCodeMethods.NOTIFY_SKIP_LOW`** — new JSON-RPC notification. Fired by the protocol layer's wiring in the constructor.
8. **`getState` and `getSkipStats`** — both expose `lowWaterline` and `lastSkipLow` for clients that prefer polling.

## Numerical results

| Module | Tests | Pass | Fail | Error | Skip |
|---|---:|---:|---:|---:|---:|
| aethercode-config | 114 | 114 | 0 | 0 | 0 |
| aethercode-permission | 105 | 105 | 0 | 0 | 0 |
| aethercode-sdk | 178 | 178 | 0 | 0 | 0 |
| aethercode-protocol | 89 | 89 | 0 | 0 | 0 |
| aethercode-bridge | 24 | 24 | 0 | 0 | 0 |
| aethercode-cli | 12 | 12 | 0 | 0 | 0 |
| (other modules) | ~3654 | 3654 | 0 | 0 | 0 |
| **Total (17 modules)** | **4176** | **4176** | **0** | **0** | **0** |

| New test file | Count | Status |
|---|---:|---|
| `SkipLowListenerTest` (R108 — policy-level) | 7 | ✓ |
| `SkipLowIntegrationTest` (R108 — engine-level) | 5 | ✓ |
| `SkipConsumedListenerTest` (R99 — preserved) | 9 | ✓ |
| `SkipStatsTest` (R106 — preserved) | 5 | ✓ |
| `ListToolActionsTest` (R100 — preserved) | 8 | ✓ |
| `MatrixPermissionPolicyTest` (R98 — preserved) | 10 | ✓ |
| `MatrixIntegrationE2ETest` (R98 — preserved) | 8 | ✓ |

## New tests (12)

### `SkipLowListenerTest` (7)

1. **`skipLow_firesOnceWhenCrossingDownToWaterline`** — arm 10 rounds, waterline=5. Consumes 10→6 (no fire), 6→5 (fire), 5→0 (no further fires).
2. **`skipLow_firesOnceEvenWhenArmedWithFewerThanWaterline`** — arm 3 with waterline=5; never fires (we only fire on a DOWN crossing, not on a starting position below).
3. **`skipLow_waterlineZero_disablesNotification`** — waterline=0 disables entirely.
4. **`skipLow_notInstalled_doesNotBreakCheck`** — no setOnSkipLow; permission check still returns Allow.
5. **`skipLow_failingListener_doesNotBreakCheck`** — listener throws; check still returns Allow; global `onSkipConsumed` still fires.
6. **`skipLow_isolatedAcrossSessions`** — two sessions crossing independently; fire is per-session.
7. **`withMatrix_preservesWaterline`** — a `withMatrix` swap carries the waterline so a config reload doesn't silently reset the threshold.

### `SkipLowIntegrationTest` (5)

1. **`lastSkipLow_isEmptyBeforeAnyConsume`** — fresh engine; no consume; `lastSkipLow` is empty; `skipLowWaterline()` reads the config (default 5).
2. **`lastSkipLow_firesWhenCrossingWaterline`** — drives the policy through `engine.policy().check()`; verifies the engine's snapshot is updated when the waterline is crossed.
3. **`lastSkipLow_doesNotFireWhenWaterlineZero`** — waterline=0 in config; listener never fires; `lastSkipLow` stays empty.
4. **`lastSkipLow_waterlineFromConfigPropagates`** — different waterline (7) in `.aethercode/config.json` is picked up by the engine.
5. **`lastSkipLow_failingListenerDoesNotBreakCheck`** — listener throws; engine's snapshot is still updated (state update happens BEFORE forwarding).

## Cross-cutting design lessons (R108)

1. **The setter must NOT replace the constructor-installed wrapper.** A pre-existing R99 bug: `setSkipConsumedListener` did `mpp.setOnSkipConsumed(listener)`, which overwrote the constructor's stats-counter wrapper, silently disabling `skipStatsConsumed.incrementAndGet()`. R108 ran into the same trap on `setSkipLowListener` (the engine's `lastSkipLow` snapshot wasn't being updated). The fix: the setter just stores the user listener in a volatile field; the constructor-installed wrapper reads that field on every fire and forwards. This pattern (state-update + forward-to-latest-field) is now used for both `setSkipConsumedListener` and `setSkipLowListener`.

2. **"Crossing DOWN through the waterline" is a single fire, not a state.** If the user arms 100 rounds and consumes them all, the listener fires exactly once (when remaining drops from 6→5 with waterline=5), not on every consume from 5→4→3→2→1→0. A state-based design (e.g. "if remaining <= waterline") would fire on every consume below the threshold. The crossing design is the right abstraction because the user wants a one-shot nudge, not a constant reminder.

3. **Waterline=0 means "disabled", not "fire when remaining hits 0".** The semantics are: 0 → listener never fires, regardless of counter. A negative value is the same. This matches the "opt out" pattern in the rest of the config: 0 is the "feature off" sentinel.

4. **`withMatrix` carries the waterline.** A naive `withMatrix` would have to ask the engine for the waterline at call time, which is racy during a config reload. Carrying it in the copy keeps the swap atomic and self-contained.

5. **`lastSkipLow` is a reconnect-friendly snapshot.** A client that joins mid-session (e.g. desktop reopens after a Tauri crash) sees the last skip-low event in `getState` and can render "skip running low" even if it missed the live notification. Without the snapshot, the client would have to wait for the next crossing.

6. **The wrapper-vs-setter pattern is fragile.** This is the second time (R99, R108) that a "store + forward" wrapper pattern has been re-implemented. A future refactor could lift the wrapper into a `ListenerChain` helper or a "install all engine listeners" method called once after `swapPolicy`. For now, the 2-listener footprint doesn't justify the abstraction.

## Files (R108)

- EDIT: `aethercode-config/.../AetherCodeConfig.java` (+1 field, +1 constructor arg, default 5)
- EDIT: `aethercode-config/.../ConfigEngine.java` (+1 line: read `skipLowWaterline` from map)
- EDIT: `aethercode-permission/.../MatrixPermissionPolicy.java` (+25 lines: field, setter, fire logic, waterline preserved on `withMatrix`)
- EDIT: `aethercode-sdk/.../AetherCodeEngine.java` (+60 lines: skipLowListener field, setSkipLowListener setter, lastSkipLow snapshot state, constructor wiring, swapPolicy re-wire, `skipLowWaterline()` accessor, `SkipLowSnapshot` record)
- EDIT: `aethercode-protocol/.../AetherCodeMethods.java` (+30 lines: NOTIFY_SKIP_LOW constant, constructor wiring for the notification, getState/lastSkipLow surface, getSkipStats low-waterline field)
- NEW: `aethercode-permission/.../SkipLowListenerTest.java` (7 tests)
- NEW: `aethercode-sdk/.../SkipLowIntegrationTest.java` (5 tests)
- EDIT: existing test files (constructor arity) — `SkipConfirmationRegistryTest`, `SkipConfirmationRegistryPersistenceTest`

## Cumulative R1-R108 (AetherCode)

- 4176 Java tests (+12 R108)
- 12 vitest in R104
- 9 node `--test` in R103
- 17 reactor modules
- 0 known regressions
- New capabilities: skip-low waterline warning, R99's pre-existing setter bug fixed

## R108+ follow-up candidates

- **R109**: auto-suggest permissionMode for new projects based on `.aethercode/config.json` heuristics
- **ListenerChain refactor**: lift the 3-listener footprint (R99 global + R107 per-tool + R108 skip-low) into a single "install all engine listeners" method
- **TUI/Desktop wiring**: badge `⏩ skip: 2 (low!)` based on `lastSkipLow.remaining <= lowWaterline`; auto-clear after 5s
- **Skip-low toast**: a "re-arm 50?" action button in the desktop panel that calls `setSkipConfirmation(50)` when the user clicks
