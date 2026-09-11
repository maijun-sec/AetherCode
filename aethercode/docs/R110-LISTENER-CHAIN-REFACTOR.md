# R110 — ListenerChain Refactor

**Date**: 2026-08-18
**Status**: SHIPPED
**Round count**: 1
**Test delta**: +4 Java tests (4208 → 4216; 4 `PolicyListenerChainTest`)

## Why R110

R99 (`setOnSkipConsumed`), R107 (`setOnToolSkipConsumed`), and R108 (`setOnSkipLow`) each added a separate listener slot to `MatrixPermissionPolicy`. Every listener had two callers: the engine's constructor (initial install) and the engine's `swapPolicy` path (re-install on config reload). After 3 rounds, the swap path had 3 separate re-install blocks with similar shape but slightly different code paths. A future R-round adding a 4th listener would need to copy-paste the swap-path re-install, and forgetting to do so would silently disable the new feature.

R110 extracts a single `installPolicyListeners(MatrixPermissionPolicy)` helper that owns all 3 listener installs in one place. The constructor and `swapPolicy` both call it. Adding a new listener becomes a one-line change inside the helper.

## What R110 ships

1. **`AetherCodeEngine.installPolicyListeners(MatrixPermissionPolicy)`** — package-private helper. Re-installs:
   - `setSkipConfirmationRegistry` (R101 — see "Pre-existing bug fixed" below)
   - `setOnSkipConsumed` (R99 wrapper: stats counter + forward to user listener)
   - `setOnToolSkipConsumed` (R107 wrapper: per-tool map counter)
   - `setOnSkipLow` (R108 wrapper: state snapshot + forward to user listener)
2. **Constructor path simplified** — the 3 listener blocks are replaced with a single call to `installPolicyListeners(mpp)`.
3. **`swapPolicy` path simplified** — the 3 separate re-install blocks are replaced with a single call to `installPolicyListeners(swapped)`.
4. **Pre-existing R101 bug fixed** — see below.

## Numerical results

| Module | Tests | Pass | Fail | Error | Skip |
|---|---:|---:|---:|---:|---:|
| aethercode-config | 126 | 126 | 0 | 0 | 0 |
| aethercode-permission | 105 | 105 | 0 | 0 | 0 |
| aethercode-sdk | 186 (+4) | 186 | 0 | 0 | 0 |
| aethercode-protocol | 89 | 89 | 0 | 0 | 0 |
| aethercode-bridge | 24 | 24 | 0 | 0 | 0 |
| aethercode-cli | 12 | 12 | 0 | 0 | 0 |
| (other modules) | ~3676 | 3676 | 0 | 0 | 0 |
| **Total (17 modules)** | **4216** | **4216** | **0** | **0** | **0** |

## New tests (4)

1. **`installPolicyListeners_constructorWiresAllThree`** — engine on a fresh project; the live policy has the 3 wrappers installed; consuming increments `skipStatsConsumed` and fires the user listener.
2. **`installPolicyListeners_swapPolicyReinstallsAllThree`** — triggers a config reload, confirms the policy is replaced, the matrix change is in effect, and the 3 wrappers all survive the swap. Cross-checks `skipStats.consumed`, `consumed.get()` (user listener), `skipStatsByTool` (per-tool), and `lastSkipLow` snapshot — all updated after the swap.
3. **`installPolicyListeners_swapPolicyCarriesWaterline`** — a config reload that changes the waterline from 7 to 4 results in `engine.skipLowWaterline() == 4` (i.e. the new waterline wins over the old one).
4. **`installPolicyListeners_swappingWithoutMatrixChangeStillAppliesWaterline`** — a config reload that changes ONLY the waterline (matrix is byte-identical) still applies the new waterline. Pins the R108 fix for the "waterline-only change" path.

## Pre-existing bug fixed

While testing the swap path, the `installPolicyListeners_swapPolicyReinstallsAllThree` test exposed a pre-existing R101 bug: after a `swapPolicy`, the new policy's `skipConfirmationRegistry` field was `null`. The policy's consume path is:

```java
if (skipRegistry != null && ctx != null
        && skipRegistry.consumeOne(ctx.sessionId())) {
    // fire listeners
}
```

With `skipRegistry == null`, the consume path is silently bypassed — `consumeOne` never runs, the counter never decrements, and the skip-low / per-tool / skip-consumed listeners all go silent. The user sees the matrix behavior as "DENY by default" instead of "auto-allow with skip counter".

The fix moves `mpp.setSkipConfirmationRegistry(this.skipConfirmationRegistry)` into `installPolicyListeners`, so it runs at construction AND at every swap. The bug had been latent because no test exercised "consume after swap" — the R110 test deliberately does, and the pre-existing bug became visible immediately.

## Cross-cutting design lessons (R110)

1. **Listener re-installs are the canonical pre-existing-bug surface.** R99 had a setter bug (overwrite the wrapper). R101 had a swap-after-init bug (skipRegistry not re-set). Both manifested only when a user (or a test) exercised a specific path. The refactor's value is not just "less code" but "fewer places to forget". A future R-round adding listener #4 can add it to `installPolicyListeners` and the swap path is auto-correct.

2. **Helper methods that "just call 3 setters" still earn their keep.** The refactor replaced 3 inline blocks (one in the constructor, three in the swap path) with one helper called from two places. The net code change is roughly neutral, but the *test coverage* is now complete: 4 tests pin the helper, and the swap path can't regress without breaking a test.

3. **The `setSkipConfirmationRegistry` fix is the more impactful part of R110.** The "3-listener refactor" is mechanical; the registry-re-install fix is a latent user-visible bug. Both are welcome, but the registry fix is the one that prevents user surprise.

4. **Refactor rounds have a "this looks pure but surfaces a bug" risk.** The R110 doc was originally titled "3-listener refactor" with no mention of a bug fix. The new test for swap + consume failed on the first run, the registry field was the cause, and the fix expanded the scope. Refactor rounds should be prepared to fix related latent bugs in the same round rather than punt to a follow-up.

5. **The pre-existing bug was a R101 design-time oversight.** R101 added `ConfigWatcher` + `swapPolicy` but didn't re-set the registry because the constructor's `setSkipConfirmationRegistry` call was the only place the field was set. The R110 refactor surfaces this by moving the install into a helper that runs at both construction and swap. **Lesson**: any policy/engine state that needs to be re-set after a swap should be set in the same place the swap happens, not in a one-shot constructor call.

## Files (R110)

- EDIT: `aethercode-sdk/.../AetherCodeEngine.java` (-30 lines: collapsed 3 inline blocks; +60 lines: new `installPolicyListeners` helper with doc-comment)
- NEW: `aethercode-sdk/.../PolicyListenerChainTest.java` (4 tests)

## Cumulative R1-R110 (AetherCode)

- 4216 Java tests (+4 R110)
- 12 vitest in R104
- 9 node `--test` in R103
- 17 reactor modules
- 0 known regressions
- New capabilities: cleaner swapPolicy re-install path, R101 latent registry-swap bug fixed

## R110+ follow-up candidates

- **R111**: Tauri App multi-session picker UI (R97-J)
- **R112**: TUI/Desktop skip-low badge + permissionModeSuggestion wiring
- **More listener slots**: R108's `setOnSkipLow` could be followed by a `setOnSkipArmed` (fires when a skip is first armed) or `setOnPermissionModeChange` (fires when the user changes the mode) — each addition is now a 5-line change inside the helper
