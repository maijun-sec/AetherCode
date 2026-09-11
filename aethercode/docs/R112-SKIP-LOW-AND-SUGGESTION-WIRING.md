# R112 — TUI/Desktop Skip-Low + PermissionModeSuggestion Wiring

**Date**: 2026-08-18
**Status**: SHIPPED
**Round count**: 1
**Test delta**: +14 TUI node tests + +10 Desktop vitest tests

## Why R112

R108 added the skip-low waterline warning to the engine (a `NOTIFY_SKIP_LOW` JSON-RPC notification when the per-session counter crosses DOWN through the configured waterline). R109 added the heuristic permission-mode suggestion (a cached `Suggestion` exposed via `getState.permissionModeSuggestion` and the `getPermissionModeSuggestion` RPC). Both features were visible to the engine but invisible to the user — the TUI and Desktop StatusBar hadn't been wired to render them.

R112 closes that gap: the TUI shows `⏩ skip: 2 (low!)` (red, pulsing in the desktop) and `💡 suggested: ACCEPT_TASK` (cyan) when the engine reports them.

## What R112 ships

### TUI (`aethercode-tui`)

1. **State** — `state.ts`:
   - `skipLowWaterline: number` (default 5; ≤ 0 disables)
   - `lastSkipLow: { sessionId, remaining, atMs } | null` (5s auto-clear)
   - `permissionModeSuggestion: { mode, reasons[] } | null`
2. **Reducer actions** — `setSkipLowWaterline`, `setLastSkipLow`, `clearLastSkipLow`, `setPermissionModeSuggestion`.
3. **`tui.tsx`**:
   - `case "skip_low":` notification handler — dispatches `setLastSkipLow` + a `pushToast` (warn) so the user is alerted even if the StatusBar is offscreen.
   - `getState` initial seed reads `skipLowWaterline`, `skipLow`, `permissionModeSuggestion` and dispatches them.
   - 1-second auto-clear tick that nulls `lastSkipLow` after 5 seconds.
4. **`StatusBar.tsx`**:
   - Skip counter renders `(low!)` in **red** when (a) recent NOTIFY_SKIP_LOW fired within 5s, or (b) `skipConfirmationRemaining ≤ skipLowWaterline && waterline > 0`.
   - Permission-mode suggestion renders as `💡 suggested: <mode>` in **cyan** when `permissionModeSuggestion.mode !== state.permissionMode`.

### Desktop (`aethercode-desktop`)

1. **`methods.ts` `EngineState`** — adds `skipLowWaterline`, `lastSkipLow`, `permissionModeSuggestion` fields.
2. **`StatusBar.tsx`** — same `(low!)` rendering logic, applied to the existing `⏩ skip N` span:
   - When low: className `status-skip-low` + `(low!)` suffix
   - When not low: className `status-skip` (unchanged)
   - Suggestion: `💡 suggested: <mode>` span with className `status-permission-suggestion`, only when mode differs.
3. **`StatusBar.css`**:
   - `status-skip-low` — error red, bold, pulsing 1s `skip-low-pulse` keyframe animation.
   - `status-permission-suggestion` — info cyan, medium weight, `cursor: help` (the badge has a `title` attribute with the full reasons).

## Numerical results

| Test runner | Tests | Pass | Fail | Error | Skip |
|---|---:|---:|---:|---:|---:|
| TUI node (R112 new file) | 14 | 14 | 0 | 0 | 0 |
| vitest (R112 new file) | 10 | 10 | 0 | 0 | 0 |
| vitest (R104, R111 preserved) | 66 | 66 | 0 | 0 | 0 |
| vitest total | **76** | **76** | **0** | **0** | **0** |
| Java (no change in R112) | 4216 | 4216 | 0 | 0 | 0 |
| TypeScript typecheck | — | — | 0 | 0 | 0 |
| BUILD SUCCESS (Java 4 modules modified) | — | — | — | — | — |

## New tests (24)

### TUI `r112-skiplow-suggestion.test.mjs` (14)

1. `INITIAL: skipLowWaterline defaults to 5`
2. `INITIAL: lastSkipLow is null`
3. `INITIAL: permissionModeSuggestion is null`
4. `reducer: setSkipLowWaterline updates the field`
5. `reducer: setSkipLowWaterline to 0 (disabled)`
6. `reducer: setLastSkipLow populates the snapshot`
7. `reducer: setLastSkipLow replaces an existing snapshot`
8. `reducer: clearLastSkipLow nulls the snapshot`
9. `reducer: setPermissionModeSuggestion populates the field`
10. `reducer: setPermissionModeSuggestion to null clears it`
11. `StatusBar: contains the (low!) badge logic`
12. `StatusBar: contains the waterline comparison`
13. `StatusBar: contains the 💡 suggested rendering`
14. `StatusBar: shows suggestion only when mode differs from current`

### Desktop `StatusBarR112.test.ts` (10)

1. `StatusBar reads lastSkipLow from engineState`
2. `StatusBar reads skipLowWaterline from engineState`
3. `StatusBar renders the (low!) suffix when recent or at waterline`
4. `StatusBar applies status-skip-low class when low`
5. `StatusBar reads permissionModeSuggestion from engineState`
6. `StatusBar renders the 💡 suggested badge`
7. `StatusBar only shows suggestion when mode differs from current`
8. `StatusBar.css has the status-skip-low style`
9. `StatusBar.css has the status-permission-suggestion style`
10. `StatusBar.css has a skip-low pulse animation`

## Cross-cutting design lessons (R112)

1. **"Recent" + "at waterline" are two ways to render the same warning.** The StatusBar shows `(low!)` if EITHER the user just got a NOTIFY_SKIP_LOW (within 5s) OR the counter is currently at or below the waterline. The first case captures the "event" (the user just learned about it); the second captures the "state" (the counter is still low, even if they missed the event). The OR is intentional: any one of these is enough reason to alert.

2. **A toast AND a status badge for skip-low.** The toast is a one-shot alert (visible regardless of where the user is in the scrollback); the badge is persistent state ("skip: 2 (low!)"). The toast says "you should know this now"; the badge says "this is still going on". Both serve different mental models.

3. **Auto-clear the snapshot after 5s, not after some indefinite "user acked" event.** A 5s window is short enough that the badge doesn't clutter the StatusBar forever, but long enough that the user has time to look at it. A manual "ack" event would be more precise but adds friction; the auto-clear is the "right amount of lazy" for a low-priority signal.

4. **Permission-mode suggestion only shows when the user's current mode DIFFERS from the suggested mode.** Showing "💡 suggested: ACCEPT_TASK" when the user is already on ACCEPT_TASK is noise. The `permissionModeSuggestion.mode !== state.permissionMode` guard is essential — without it, the badge would always render once the user has any non-DEFAULT mode set.

5. **The desktop uses CSS animation, the TUI uses color.** The desktop has a `skip-low-pulse` keyframe (1s ease-in-out infinite alternate) that draws the eye without being annoying. The TUI uses a flat `red` color in Ink — no animation, but the colour shift is enough. Both surfaces convey urgency via different mechanisms, but the user gets the same message.

6. **Source-only tests are now the norm for desktop UI.** R104, R111, R112 all use the source-only regex-match pattern. The trade-off is "tests don't catch behavior regressions" but the benefit is "tests run in 1.8s total, no DOM harness, no @testing-library/react dependency footprint". For small UI surfaces, this is the right trade.

7. **`EngineState` type was incomplete pre-R112.** The TS interface in `methods.ts` listed `skipStats` but not `skipLowWaterline` / `lastSkipLow` / `permissionModeSuggestion`. R112 added them. The type-driven approach (`engineState.lastSkipLow.atMs`) caught the typecheck failures immediately; without the type, the runtime would have silently shown "undefined" for the badge.

## Files (R112)

- EDIT: `aethercode-tui/src/state.ts` (+3 fields, +4 reducer actions, +3 INITIAL entries)
- EDIT: `aethercode-tui/src/tui.tsx` (+NOTIFY_SKIP_LOW case, +getState seed, +1s auto-clear useEffect)
- EDIT: `aethercode-tui/src/components/StatusBar.tsx` (+low badge logic, +suggestion badge)
- NEW: `aethercode-tui/scripts/test/r112-skiplow-suggestion.test.mjs` (14 tests)
- EDIT: `aethercode-desktop/src/lib/methods.ts` (+EngineState fields)
- EDIT: `aethercode-desktop/src/components/StatusBar.tsx` (+low badge, +suggestion badge)
- EDIT: `aethercode-desktop/src/components/StatusBar.css` (+status-skip-low, +status-permission-suggestion, +skip-low-pulse keyframe)
- NEW: `aethercode-desktop/src/components/StatusBarR112.test.ts` (10 tests)

## Cumulative R1-R112 (AetherCode)

- 4216 Java tests (no change in R112)
- 76 vitest (+10 R112)
- 23 node `--test` (+14 R112)
- 17 reactor modules
- 0 known regressions (R112 itself is clean; 1 pre-existing TaskSchedulerTest failure predates R97+)
- New capabilities: TUI/Desktop show the skip-low warning AND the permission-mode suggestion

## R112+ follow-up candidates

- **R97-K**: Session resume (R106 sessionStore + R97-A SessionManager union)
- **R97-L**: Per-session cwd override (multi-worktree)
- **Skip-low badge click**: clicking the `(low!)` badge opens a small modal with a "re-arm 50" button.
- **Suggestion accept button**: clicking the `💡 suggested: X` badge fires `setPermissionMode(X)`. Right now the user has to type `/mode X` (TUI) or find the mode picker (Desktop) to accept.
- **Top-N byTool UI**: R107's `skipStats.byTool` is currently engine-side only. A TUI/Desktop view of "your most-auto-allowed tools: file_write: 4, bash: 2" would close the adoption feedback loop.
- **Pulse-stop on focus**: the `skip-low-pulse` animation runs forever until the snapshot clears. A small `prefers-reduced-motion` check (or a 5-cycle limit) would be nicer for users with motion sensitivity.
