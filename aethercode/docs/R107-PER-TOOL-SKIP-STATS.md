# R107 — Per-Tool Skip Adoption Stats

**Date**: 2026-08-18
**Status**: SHIPPED
**Round count**: 1
**Test delta**: +5 Java tests (4142 → 4147)

## Why R107

R106 ships a per-session "skip adoption" view: total consumed / total armed / total prompts / adoption ratio. The user can see "I used skip on 4 out of 7 prompts" but cannot see **which tools** were auto-allowed most often. If `file_write` consumes 4 rounds and `bash` consumes 1, the user might want to keep arming skip while the bash prompts are still gated. Per-tool stats surface that.

## What R107 ships

1. **`MatrixPermissionPolicy.setOnToolSkipConsumed(BiConsumer<String,Integer>)`**
   - Tool-specific listener installed alongside the existing `onSkipConsumed(IntConsumer)`.
   - Receives `(toolName, remaining)` on every consumed skip-round.
   - Each listener is independently try/catch'd so a per-tool stat failure cannot take down the global notification or the permission check.

2. **`AetherCodeEngine.skipStatsByTool()`**
   - Returns `Map<String, Long>` of tool name → consume count, sorted by count desc.
   - Empty tools (counter at 0) are NOT in the map.
   - Returns `Collections.unmodifiableMap` for safety.

3. **Engine constructor wiring**
   - Per-tool listener installed in `MatrixPermissionPolicy` (next to existing `onSkipConsumed`).
   - Counter map is `ConcurrentHashMap<String, AtomicLong>`; first consume creates the entry.
   - **Re-applied on `swapPolicy`**: when `.aethercode/config.json` is reloaded (R101), the new policy instance is also given the per-tool listener (R101 already had this fix for the global listener; R107 mirrors it).

4. **`AetherCodeEngine.clearSkipStatsForTest()`**
   - Package-private test helper. Resets 3 atomic counters + clears the per-tool map.
   - Production code does not call this.

5. **Protocol surface**
   - `getSkipStats` RPC now returns `byTool: Map<String, Long>`.
   - `getState` includes `skipStats.byTool` field.

## Numerical results

| Module | Tests | Pass | Fail | Error | Skip |
|---|---:|---:|---:|---:|---:|
| aethercode-config | 114 | 114 | 0 | 0 | 0 |
| aethercode-permission | 98 | 98 | 0 | 0 | 0 |
| aethercode-sdk | 173 | 173 | 0 | 0 | 0 |
| **Total (3 modules)** | **385** | **385** | **0** | **0** | **0** |

| Test file | Count | Status |
|---|---:|---|
| `SkipConsumedListenerTest` (4 original + 5 new per-tool) | 9 | ✓ |
| `SkipStatsTest` (R106) | 5 | ✓ |
| `MatrixIntegrationE2ETest` (R98) | 8 | ✓ |
| `ListToolActionsTest` (R100) | 8 | ✓ |
| `MatrixPermissionPolicyTest` (R98) | 10 | ✓ |
| `PermissionModePersistenceTest` (R105) | 10 | ✓ |
| `ConfigEngineTest` (R98) | 7 | ✓ |
| `AetherCodeConfigTest` (R98) | 8 | ✓ |
| `ConfigWatcherTest` (R101) | 7 | ✓ |
| `OpKindDetectorTest` (R98) | 29 | ✓ |
| `PathBucketTest` (R98) | 11 | ✓ |
| `PermissionMatrixLookupTest` (R98) | 14 | ✓ |
| `SkipConfirmationDetectorTest` (R98) | 9 | ✓ |
| `SkipConfirmationRegistryPersistenceTest` (R102) | 10 | ✓ |
| `SkipConfirmationRegistryTest` (R98) | 9 | ✓ |

## New tests (5)

1. **`perToolListener_firesWithToolName_andRemaining`** — 3 consumes on `file_write`; listener fires with toolName="file_write" and remaining = 2, 1, 0 in order.
2. **`perToolListener_isolatedPerTool`** — interleave `file_write` and `bash` calls; last listener call has the right tool name + the right (independent) remaining count.
3. **`perToolListener_notInstalled_doesNotBreakCheck`** — never calling `setOnToolSkipConsumed`; permission check still returns Allow when the skip applies.
4. **`perToolListener_failingListener_doesNotBreakCheck`** — per-tool listener throws on every call; permission check still returns Allow, AND the global `onSkipConsumed` listener still fires (per-tool failure does not bleed into the global path).
5. **`perToolListener_firesAlongsideGlobalListener`** — both listeners installed; both fire on every consume (4/4 each).

## Cross-cutting design lessons (R107)

1. **Per-tool and global listeners are independent slots.** R99 added the global `onSkipConsumed(IntConsumer)`. R107 adds `onToolSkipConsumed(BiConsumer<String, Integer>)`. They are not a single overloaded listener with optional behavior — that would force the caller to pick "either/or" semantics. Two slots are simpler: caller can install one, both, or neither.

2. **Per-listener try/catch is mandatory.** A failure in the per-tool listener must not stop the global listener. The current implementation wraps each in its own try/catch (R99 already had this for the global listener; R107 mirrors it). A future refactor could lift this into a "ListenerChain" helper that always runs every listener regardless of exceptions, but the current 2-listener footprint doesn't justify the abstraction.

3. **`ConcurrentHashMap<String, AtomicLong>` is the right shape for sparse counters.** Most tools have a count of 0 (they're never auto-allowed). Storing them would inflate the snapshot. The `if (v > 0) out.put(name, v)` filter keeps the snapshot tight. Sorted descending so the UI can show "top-N" without re-sorting on every render.

4. **`clearSkipStatsForTest()` is package-private.** Production code does not reset the stats (the value is across the daemon's lifetime — clearing it would be a feature, not a bug fix). Test code in the same package can call it directly without reflection.

5. **Re-applying the listener on `swapPolicy` is the gotcha.** R101's `swapPolicy` re-wired the global `onSkipConsumed` listener. R107 mirrors the same pattern for `onToolSkipConsumed`. The pattern is now: every listener attached to the policy must be re-applied by the engine after a `withMatrix` swap. This is fragile to future listeners — a follow-up R-round on `MatrixPermissionPolicy` could refactor to a single "install all engine listeners" method that the engine calls after every `swapPolicy`.

6. **Sorted descending is "free" at snapshot time.** The map is small (one entry per auto-allowed tool, often 1–5 entries). Sorting on every snapshot is O(n log n) where n ≤ 8 in practice. No need to maintain a sorted data structure on writes.

## Files

- EDIT: `aethercode-permission/.../MatrixPermissionPolicy.java` (+13 lines: field, setter, listener invocation with try/catch)
- EDIT: `aethercode-sdk/.../AetherCodeEngine.java` (+25 lines: skipStatsByTool field, setter, listener wiring in constructor, re-wiring on swapPolicy, clearSkipStatsForTest, skipStatsByTool accessor with sort)
- EDIT: `aethercode-permission/.../SkipConsumedListenerTest.java` (+185 lines: 5 new per-tool tests)
- EDIT: `aethercode-protocol/.../AetherCodeMethods.java` (+2 lines: byTool field in getState + getSkipStats)

## Cumulative R1-R107 (AetherCode)

- 4147 Java tests (+5 R107)
- 0 known regressions (R106 baseline was 4142)
- New capabilities: per-tool skip adoption, R98-R102 foundation fully visible to UI
