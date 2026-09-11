# R177 — Compact PreparingCard + Fold-In StaleWarning + Drop [task] Noise (2026-09-01)

**Status**: shipped v0.2.25
**Build**: `release/aethercode-0.2.25/AetherCode.exe` (3.76 MB, SHA256
`E2ECB437D7007170C53AA9D794B958FBE812E442F453C8B2469712794DDAF29B`)
**Daemons**: unchanged (R177 is frontend-only) — jar replicated as
`aethercode-0.2.25.jar` (55.3 MB)

## What the user reported

After v0.2.24 the user ran a real prompt (`在当前目录下生成一个
java maven 项目...`) and shared two screenshots showing three
distinct UX problems in the chat flow:

1. **`[task] task u-7nnrs7fe started`** sat between the user
   message and the first sub-task card. The system-message
   styling made it look like a CLI log line, not a chat
   notification.
2. **"准备中" 准备中卡片** always rendered every step card
   expanded (think text + tool pills). The user couldn't fold
   it away; it dominated the chat from the moment a query
   started until well after the model was done.
3. **Top "已 118 秒无新输出" banner** (the `StaleWarning`
   component) floated above an otherwise empty MessageList,
   visually disconnected from the activity it described
   ("下面什么文件都没有").

## Three coordinated changes

### R177-A: drop the `[task] task u-xxx started` system message

The engine emits a `side_note(kind=task, message="task u-xxx
started")` at the start of every query (R17 — tty/CLI
convention). The desktop chat translated that into a system
info message, which R172 had restyled to a neutral grey.

R177-A inserts a `kind === 'task'` branch in the
`side_note` handler that just refreshes `lastChunkTs` and
does **not** push a system message. The sub-task card
already conveys the same info (header, meta line, task id
in `currentQuery` for the status bar).

```ts
} else if (kind === 'task') {
  // R177-A: dropped — see store/index.ts.
  set({ lastChunkTs: Date.now() });
} else {
  // Catch-all: other side_note kinds still go to chat.
}
```

### R177-B: replace `PreambleSteps` with a compact `PreparingCard`

The pre-fix `PreambleSteps` rendered every step card
expanded by default — the user had no way to fold the
"preparing" section away. R177-B replaces it with a
single-row card:

```
●  准备中 · 完成 README 草案   🧠 3 · 📄 1 · ⚡ 5 · 🔍 1   1m23s   ⏹  ▸
```

While `isStreaming`:
- Dot pulses blue
- Counter chips (🧠 3 · 📄 1 · ⚡ 5 · 🔍 1) refresh in place
  as new events arrive
- Elapsed time ticks every 1s (`1m23s`, not `162362ms`)
- Stop button (⏹) wires to `cancelQuery`
- Click the head to expand the original step cards;
  click again to fold
- Live sub-task goal (`当前 sub-task 内容`) shows in the
  label so the user sees "next step target" inline

When `isStreaming === false`:
- Card collapses to a one-line summary
- Stale indicator freezes on the final value (`总 1m23s`)
- Stop button and chevron disappear
- Colour goes from blue to neutral (var(--chat-surface))

The `PreambleSteps` component is kept as a backwards-compat
alias that routes to the new `PreparingCard` with the
`isStreaming` flag pulled from the store. The old
call site (`<PreambleSteps steps={preambleSteps}
currentStepId={currentStepId} />`) still works.

### R177-C: fold the top-level `StaleWarning` into the card

The pre-fix `StaleWarning` was a separate `<StaleWarning />`
sibling in `App.tsx` that appeared above the MessageList
when no chunk had arrived for 30s+ / 2m / 5m. Visually
it floated over an empty MessageList ("下面什么文件都没有").

R177-C removes it from `App.tsx` and folds the staleness
into the `PreparingCard`:

- 30s+ idle → amber tint (border + dot + elapsed counter)
- 2m+ → orange tint
- 5m+ → red tint (with faster pulse)

The `StaleWarning.tsx` file is preserved on disk (per
the "Don't 乱删" rule — it's a one-line revert if the
inline staleness turns out to be inadequate, and the
CSS continues to apply if some other surface imports
it later).

## Tests

`aethercode-desktop/src/components/preparingCardR177.test.ts`
(10 tests, source-pin + behavioural):

- **R177-A** (1 test): the `side_note` handler drops
  the `[task]` kind (no system message added).
- **R177-B** (6 tests):
  - PreparingCard component exists
  - Root element is `.preparing-card` with collapsed-by-default body
  - Body wrapped in `{expanded && (...)}`
  - Human-readable elapsed (`1m23s` not `162362ms`)
  - Counter chips skip zero buckets
  - CSS defines the 3-tier stale palette
  - Done state hides the chevron (auto-collapse)
- **R177-C** (3 tests):
  - App.tsx no longer imports StaleWarning
  - App.tsx no longer renders `<StaleWarning />`
  - `StaleWarning.tsx` file is preserved

| Suite | Before R177 | After R177 | Δ |
| --- | --- | --- | --- |
| `preparingCardR177.test.ts` | — | 10 | +10 |
| **Full vitest** | **869** | **879** | **+10** |

`tsc --noEmit` clean. `npm run tauri build --no-bundle` produces
`AetherCode.exe` (3.76 MB).

## Files modified

- `aethercode-desktop/src/store/index.ts` — `side_note` handler
  drops the `[task]` kind
- `aethercode-desktop/src/components/MessageList.tsx` —
  `PreparingCard` component (replaces `PreambleSteps` body)
- `aethercode-desktop/src/components/MessageList.css` —
  `.preparing-card` + 3-tier stale palette
- `aethercode-desktop/src/App.tsx` — removed StaleWarning
  import + JSX (file preserved on disk)
- `aethercode-desktop/src/components/preparingCardR177.test.ts`
  (new, 5.9 KB, 10 tests)
- `release/aethercode-0.2.25/AetherCode.exe` (new, 3.76 MB)
- `release/aethercode-0.2.25/aethercode-0.2.25.jar` (new,
  copy of v0.2.24 jar — R177 is frontend-only)
- `aethercode/dist/aethercode-0.2.25.jar` (new) — desktop
  auto-spawn now finds v0.2.25 daemon

## Lessons

1. **A side_note that the UI already surfaces elsewhere
   is noise.** The `task u-xxx started` event was a
   tty/CLI convention from R17. The desktop chat has
   its own richer surfaces (sub-task card, status bar,
   `currentQuery`). Translating the tty line into a
   chat message just clutters the flow.

2. **Indicators belong next to the data they describe.**
   The pre-fix `StaleWarning` banner sat above an empty
   MessageList ("下面什么文件都没有"). Folding it
   into the `PreparingCard` puts the staleness tint
   next to the live counters and the stop button, so
   the user can act on it without context-switching.

3. **Always-expanded cards are anti-patterns for
   long-running work.** The pre-fix `PreambleSteps`
   forced every step card open, leaving the chat
   dominated by an uncollapsible box the user
   couldn't fold. Single-row-by-default + click-to-expand
   is the right default for "in flight" state.

4. **Source-pin tests pin subjective choices.** The
   stale-tier colour values (`rgba(244, 196, 113, 0.45)`,
   `rgba(244, 163, 113, 0.55)`, etc.) are tuned to
   "noticeable on dark surface, not alarmist". A future
   "make it less obtrusive" refactor will trip the
   regression test and force a conscious decision.
