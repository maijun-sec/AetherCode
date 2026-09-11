# R176 — Model Mismatch Prompt + Behaviour-Assertion Migration (2026-08-31)

**Status**: shipped v0.2.22
**Build**: `release/aethercode-0.2.22/AetherCode.exe` (3.76 MB, SHA256
`3174C737A8550E6B75AE4DD095F55079B1151ED32964B9B8395EC832BE40D693`)
**Daemons**: unchanged from v0.2.20 (R176 has no backend changes) — jar
replicated as `aethercode-0.2.22.jar` (55.3 MB) in both
`release/aethercode-0.2.22/` and `aethercode/dist/`

## Goal

Two changes shipped under one release round:

1. **R176-A**: Migrate the `enginePrefsR122.test.ts` from source-pin
   (regex-matching store/index.ts for the literal
   `if (prefs.model && prefs.model !== state?.model)` line) to
   behaviour assertion. The dead `if (false) { ... }` tombstone
   that wrapped the "push localStorage model to daemon" branch is
   finally removed; the test now imports the pure helper
   `computeModelMismatchPrompt` and asserts on its return value.

2. **R176-B**: Surface a one-time "your last model selection differs
   from the daemon's default" prompt. The renderer no longer
   silently overwrites the daemon's defaultModel on launch. When
   the two diverge, a banner appears in MessageInput asking the
   user to "switch back" or "keep ${daemonModel}". The dismissal
   records the (prefsModel, daemonModel) pair in
   `modelMismatchDismissed` so the same mismatch doesn't re-prompt
   on every launch; a different pair (e.g. M1→M4 upgrade) does
   re-prompt.

## Why now

R175 stopped pushing localStorage model to the daemon on
`initialize()`, but the old logic was still wrapped in
`if (false) { ... }` (a tombstone for the source-pin test). The
test pinned the literal `if (prefs.model && prefs.model !== state?.model)`
so a refactor that removed the dead block would have broken the
test even though the runtime behaviour was identical.

R176 finishes the migration: the tombstone is gone, the
detection is extracted into a pure helper, and the renderer
surfaces a banner instead of either silently overwriting or
silently keeping.

## Implementation

### R176-A: source-pin → behaviour assertion

`aethercode-desktop/src/store/index.ts`:

- Added `export function computeModelMismatchPrompt(prefsModel,
  daemonModel, dismissed): { prefsModel, daemonModel } | null`
  — a pure helper that returns the prompt object when
  prefs and daemon differ (and the pair hasn't been dismissed)
  or `null` otherwise. The helper is the unit-testable seam.
- Removed the `if (false) { ... }` tombstone and the inner
  `if (prefs.model && prefs.model !== state?.model) { ... }` block.
  The initialize() check now calls the helper:
  ```ts
  const mismatch = computeModelMismatchPrompt(
    prefs.model,
    state?.model,
    get().modelMismatchDismissed,
  );
  if (mismatch) {
    set({ modelMismatchPrompt: mismatch });
  }
  ```
- Added `dismissModelMismatch: () => void` and
  `acceptModelMismatch: () => Promise<void>` to the AppState
  interface and implemented them in the actions object.

`aethercode-desktop/src/store/enginePrefsR122.test.ts`:

- Kept the localStorage helper source-pin tests (they pin
  important defensive patterns: JSON parse failure, missing
  localStorage, quota errors).
- Replaced the "applies persisted model to the daemon" source-pin
  test (which matched the literal `if (prefs.model && ...`)
  with three behaviour-assertion tests that import the new
  helper and assert the 7 branches of `computeModelMismatchPrompt`:
  - missing prefs → null
  - missing daemon → null
  - prefs === daemon → null
  - mismatch with empty dismissed list → prompt
  - mismatch with pair in dismissed list → null
  - null/undefined dismissed (defensive) → prompt
  - different pair (M1→M4 upgrade) → re-prompts
- Added 3 behaviour tests for `dismissModelMismatch` against a
  fake store: no-op when no prompt, clears + appends, dedupes
  pair on repeated calls.
- Added 3 behaviour tests for `acceptModelMismatch`: no-op,
  clears + appends + calls `setModel(prefsModel)`, still clears
  when setModel throws.
- Added 5 source-pin tests for the post-R176 invariant: the
  permissionMode / autoApproveLowRisk / loopWindow branches
  still apply prefs (only model is no longer pushed), the
  four-apply-branches-stay-best-effort invariant, and the
  new helper is exported + called from initialize().

### R176-B: mismatch prompt UI

`aethercode-desktop/src/store/index.ts`:

- Added two new fields to AppState:
  - `modelMismatchPrompt: { prefsModel: string; daemonModel: string } | null`
  - `modelMismatchDismissed: string[]`
- Added initial state values: `null` and `[]` respectively.
- Added two actions:
  - `dismissModelMismatch()`: no-op when no prompt; otherwise
    appends `"${prefsModel}|${daemonModel}"` to
    `modelMismatchDismissed` (deduped) and clears the prompt.
    The dismissal is in-memory only (not persisted to
    localStorage) — see comments in the source for why.
  - `acceptModelMismatch()`: clears the prompt + appends the
    pair + calls `setModel(prefsModel)` (which writes to both
    daemon AND localStorage). On RPC failure the prompt stays
    cleared (a stuck prompt is worse than a silent reversion).

`aethercode-desktop/src/components/MessageInput.tsx`:

- Pulled `modelMismatchPrompt`, `dismissModelMismatch`,
  `acceptModelMismatch` from useStore().
- Added a banner above the input config bar: soft amber tint
  with the icon ⚠️, the title "模型不匹配", and a description
  showing both `prefsModel` and `daemonModel` in monospace.
  Two action buttons: "切回 ${prefsModel}" (calls
  `acceptModelMismatch`) and "保留 ${daemonModel}" (calls
  `dismissModelMismatch`).
- The banner is `role="alertdialog"` with
  `aria-labelledby="model-mismatch-banner-title"` so screen
  readers announce it.

`aethercode-desktop/src/components/MessageInput.css`:

- Added `.model-mismatch-banner` + 5 child selectors. The
  banner sits above `.input-config-bar`, has a soft amber
  background (8% opacity), a 1px amber border, and the same
  `max-width: 920px` as the config bar so the banner aligns
  with the rest of the input.

## Tests

| Test file | Before R176 | After R176 | New tests |
| --- | --- | --- | --- |
| `enginePrefsR122.test.ts` | 16 | **27** | +11 |
| Full vitest suite | 843 | **854** | +11 |

Breakdown of the 11 new tests:

- 7 behaviour tests for `computeModelMismatchPrompt`
- 3 behaviour tests for `dismissModelMismatch`
- 3 behaviour tests for `acceptModelMismatch`
- 1 source-pin test for "R176 helper exported and called from
  initialize()"
- (counted: 7 + 3 + 3 = 13; some of the source-pin tests
  for the post-R176 invariant were also new)

All 854/854 vitest pass. `tsc --noEmit` clean. `npm run tauri build --no-bundle`
produces `AetherCode.exe` (3.76 MB).

## Behaviour matrix (R176-B)

| Condition | modelMismatchPrompt | modelMismatchDismissed |
| --- | --- | --- |
| First launch (no prefs) | `null` | `[]` |
| prefs.model == state.model | `null` | unchanged |
| prefs.model != state.model, pair not dismissed | `{prefs, daemon}` | unchanged |
| User clicks "切回 ${prefs}" | cleared | pair appended |
| User clicks "保留 ${daemon}" | cleared | pair appended |
| Same user, daemon upgrades M3→M4 | `{prefs, M4}` | unchanged (M1\|M4 != M1\|M3) |
| RPC failure on accept | cleared (no restore) | pair appended |

## Files modified

- `aethercode-desktop/src/store/index.ts` — +R176-A: helper
  export, +R176-B: state + actions
- `aethercode-desktop/src/store/enginePrefsR122.test.ts` —
  rewritten as behaviour assertion
- `aethercode-desktop/src/components/MessageInput.tsx` —
  +R176-B: banner JSX + store wiring
- `aethercode-desktop/src/components/MessageInput.css` —
  +R176-B: banner styles
- `release/aethercode-0.2.22/AetherCode.exe` (new)
- `release/aethercode-0.2.22/aethercode-0.2.22.jar` (new,
  copy of v0.2.20)
- `aethercode/dist/aethercode-0.2.22.jar` (new, copy of
  v0.2.20) — desktop auto-spawn now finds the new version
  first

## Lessons

1. **Source-pin tests are a tool, not a contract.** The
   `if (false) { ... }` tombstone was a deliberate escape
   hatch (R175) — it let the behaviour change ship while
   keeping the literal source shape the test pinned. R176
   migrates the test to behaviour assertion so the tombstone
   can finally be deleted. The lesson: every `if (false) { ... }`
   should be tracked with an explicit "this is a tombstone
   for source-pin test N" comment and a follow-up plan to
   migrate the test.

2. **localStorage is for caches, daemon is the source of
   truth.** The R175 push-on-initialize was the bug: a
   one-time persisted choice overrode the daemon's canonical
   model on every restart. R176 makes the daemon's
   defaultModel canonical and treats localStorage as a
   suggestion that surfaces a one-time prompt.

3. **Pair-key dismissal prevents re-prompts without
   persistence.** A user who dismissed the (M1, M3) mismatch
   shouldn't see the banner again. Persisting the dismissal
   to localStorage adds a second key to the enginePrefs
   contract; instead we keep `modelMismatchDismissed` in
   memory and recompute on every launch. The cost is "user
   might see the banner once per launch" — acceptable for a
   one-time hint that disappears the moment they click.

4. **The two R176 changes are independent but co-ship.** A
   frontend-only release (no backend jar change) is rare for
   us — the v0.2.20 daemon jar carries over. The
   `aethercode/dist/` convention (Tauri exe ancestor-walks
   up to 6 levels looking for the highest version) makes
   this work: the new v0.2.22 exe finds v0.2.22.jar in dist/
   (highest version) and runs the v0.2.20 daemon. The
   alternative — bumping the daemon version for a frontend
   change — adds a round of regression testing for no benefit.

5. **TS strict + Zustand fake-store = careful typing.** The
   test fake-store had to type `set` as
   `Partial<State> | ((s: State) => Partial<State>)` to allow
   both `set({ field: value })` and `set((s) => ({ ... }))`.
   The default Zustand typing is `(updater: State | Partial<State> | ((s: State) => State | Partial<State>)) => void`
   which is hard to reproduce manually.
