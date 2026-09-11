# R176-C — Model Mismatch Banner Visibility + Dropdown Value Mismatch (2026-09-01)

**Status**: shipped v0.2.23
**Build**: `release/aethercode-0.2.23/AetherCode.exe` (3.76 MB, SHA256
`46A8EE66A104DD043692CD856299412ECF21810C89182415F1F03CA6D0869264`)
**Daemons**: unchanged (R176-C is frontend-only) — jar replicated as
`aethercode-0.2.23.jar` (55.3 MB) in both `release/aethercode-0.2.23/` and
`aethercode/dist/`

## What the user reported

After v0.2.22 shipped, the user opened the new exe and saw:
- The config-bar model dropdown showing `minmax / MiniMax-M1` (but the
  engine card on the left and the status bar both showed `MiniMax-M3`).
- A "flash" of something at the top of the input area that disappeared.
- The same `c3766213` session they used yesterday still had the
  `java maven 5 sort algorithms` prompt sitting in the chat.

Two real bugs were hiding under those symptoms:

## Bug 1: dropdown value never matches any option (R113-era)

`aethercode-desktop/src/components/MessageInput.tsx`:

```jsx
<select value={model ?? ''} ...>
  {modelEntries.map((e) => (
    <option key={e.id} value={e.id}>{e.label}</option>
  ))}
</select>
```

The option `value` is `${providerName}/${modelId}` (e.g.
`minmax/MiniMax-M3`), but the `<select>`'s `value` was just `model`
(e.g. `MiniMax-M3`). They never match. When the browser can't find
a matching option it falls back to the **first option in the list**
(which sorts alphabetically — for the `minmax` provider, `M1` comes
before `M3`, so M1 was always the "default"). The user saw M1 in
the dropdown even though the daemon was M3.

This bug pre-dated R176 — it shipped with the R113 multi-provider
model dropdown. R176 didn't cause it, but the R176 banner
made it more visible: the banner correctly reported the M1/M3
mismatch but the dropdown "agreed" with M1, so the UI looked
self-consistent and the banner felt wrong.

**Fix** (`src/components/modelDropdownValue.ts` + MessageInput.tsx):

```ts
export function computeModelDropdownValue(
  model: string | null | undefined,
  entries: readonly ModelEntry[],
): string {
  if (!model) return '';
  const match = entries.find((e) => e.id.endsWith('/' + model));
  if (match) return match.id;
  return model;
}
```

The select now uses the matched option's full id, so `M3` →
`minmax/MiniMax-M3` and the dropdown actually shows the daemon's
current model. The `endsWith('/' + model)` anchor (not `includes`)
is a regression guard for the future "M10" / "M1" overlap case.

## Bug 2: banner too subtle to see

`src/components/MessageInput.css`:

The v0.2.22 banner used:
- `background: rgba(245, 158, 11, 0.08)` — 8% opacity amber
- `border: 1px solid rgba(245, 158, 11, 0.35)` — 35% opacity border

On the dark `--chat-surface` (#252526) those values compute to
roughly `#2c2925` (background) and a 35% amber outline — both
barely distinguishable from the surrounding surface. The user saw
"a flash" because the banner did render, but it looked like a
slight background shift, not a notification.

**Fix** (R176-C):

- `background: var(--chat-warning-soft)` — 18% opacity amber
  (the canonical warning tint already used by
  `.workflow-import-result-partial`).
- `border: 1px solid var(--chat-warning)` — solid amber border.
- `border-left: 4px solid var(--chat-warning)` — thicker left
  accent so the banner is unmistakable even at a glance.
- `box-shadow: 0 2px 8px rgba(0, 0, 0, 0.18)` — soft drop
  shadow for depth.
- Title text is now `--chat-warning` (was muted grey).
- `MiniMax-M1` / `MiniMax-M3` model-id chips are amber on
  dark — they pop out as the actionable info.
- Icon bumped 18px → 22px with a 1px drop shadow for
  readability.

## Why R176 didn't catch these

The R176 tests cover the **store logic** (mismatch detection,
dismiss/accept flows, helper exports). The visibility issues
are in the **CSS** (opacity values) and the **dropdown binding**
(which is a UI-only concern that didn't have a test surface).
The source-pin tests for the CSS and the dropdown value would
have caught the regressions if they'd existed, but R176 added
the banner + dropdown as new code, not refactors of existing
tested code.

R176-C adds:

- `src/components/modelDropdownValue.test.ts` (8 tests) —
  behaviour assertion of the helper covering empty / matching /
  cross-provider / not-in-list / exact-suffix / multiple-providers
  cases.
- `src/components/modelMismatchBannerR176.test.ts` (5 tests) —
  source-pin tests pinning the post-R176-C visibility contract:
  the banner uses `--chat-warning-soft`, no 8% / 35% rgba amber,
  title text uses `--chat-warning`, model-id chips use
  `--chat-warning`.

## Files modified

- `aethercode-desktop/src/components/MessageInput.tsx` —
  dropdown uses `computeModelDropdownValue(model, modelEntries)`
- `aethercode-desktop/src/components/MessageInput.css` — banner
  visibility bumped (8% → 18%, 35% → solid, +left accent,
  +shadow, +icon size)
- `aethercode-desktop/src/components/modelDropdownValue.ts`
  (new, 1.8 KB) — pure helper
- `aethercode-desktop/src/components/modelDropdownValue.test.ts`
  (new, 5.1 KB, 8 tests)
- `aethercode-desktop/src/components/modelMismatchBannerR176.test.ts`
  (new, 3.4 KB, 5 tests)
- `release/aethercode-0.2.23/AetherCode.exe` (new, 3.76 MB)
- `release/aethercode-0.2.23/aethercode-0.2.23.jar` (new, copy
  of v0.2.22 jar — R176-C is frontend-only)
- `aethercode/dist/aethercode-0.2.23.jar` (new) — desktop
  auto-spawn now finds the v0.2.23 daemon

## Tests

| Suite | Before R176-C | After R176-C | Δ |
| --- | --- | --- | --- |
| `enginePrefsR122.test.ts` | 27 | 27 | 0 |
| `modelDropdownValue.test.ts` | — | 8 | +8 |
| `modelMismatchBannerR176.test.ts` | — | 5 | +5 |
| **Full vitest** | **854** | **867** | **+13** |

`tsc --noEmit` clean. `npm run tauri build --no-bundle` produces
`AetherCode.exe` (3.76 MB).

## Lessons

1. **CSS opacity values are subjective and untested by
   default.** 8% vs 18% is a visibility cliff on a dark surface
   that no TypeScript check or unit test can catch. The
   `modelMismatchBannerR176.test.ts` source-pin test pins
   the post-fix opacity (`--chat-warning-soft`) so a "make
   it less obtrusive" refactor trips a regression test
   instead of slipping through.

2. **Browser `<select>` value-mismatch falls back to the
   first option — silently.** The R113 multi-provider
   dropdown shipped with `value={model}` while options
   had provider-prefixed ids. The bug stayed hidden
   because the alphabetical sort put the "expected"
   model (M1) at the top of the list, so the fallback
   looked correct. R176-C's `computeModelDropdownValue`
   helper makes the lookup explicit + testable.

3. **The user's "the page is getting worse" was actually
   "the page is correct but a long-standing bug is
   confusing me."** The v0.2.18 → v0.2.22 sequence
   shipped real changes (R172 stream stale, R173
   create-agent, R174 empty-input hard-stop, R175 UI
   cleanup, R176 mismatch prompt), but the *user-visible*
   difference between v0.2.18 and v0.2.23 is mostly the
   bug fixes. R176-C restores the user's confidence by
   making the dropdown match reality.

4. **Always sanity-check daemon state vs UI state on
   user report.** `getState` confirmed
   `model=MiniMax-M3` on both running daemons while the
   user thought the app was on M1. The mismatch was
   purely in the renderer.
