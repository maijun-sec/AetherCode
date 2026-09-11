# R193-follow-up — Auto-scroll & Style Refresh (2026-09-02)

## User's two complaints (after v0.2.33)

The user reported two new issues with the v0.2.33 build that
shipped 30 minutes earlier:

1. **"完全没有向下滚动"** — the auto-scroll was unreliable
   during a real query. The chat didn't follow the streamed
   tool output.
2. **"样式并不好看"** — the visual style was "ugly / cramped".
   The user attached a screenshot of the desired shape: a
   clean white-background interface where each tool/step is
   a flat single-line trace item ("终端 Get-Content ...",
   "思考过程", "搜索 '**build|tauri'") with a small icon and
   the command inline. No heavy borders, no padding boxes.

## Root causes (post-R193 inspection)

### Auto-scroll unreliability

The v0.2.33 fix already moved the auto-scroll useEffect's
dep from `steps.length` to the `steps` array reference, so
the effect re-ran on every streamed chunk. But it still
called:

```js
bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
```

`scrollIntoView({behavior: 'smooth'})` is **async** — the
browser kicks off a smooth-scroll animation and returns.
When the next `tool_output_delta` lands ~50ms later, a new
smooth-scroll starts and the previous one gets cancelled.
With 5-10 chunks/sec from a streaming `mvn test` run, the
last few smooth-scrolls get cancelled before they finish
and the viewport ends up stuck partway down. The user
sees "the chat didn't follow" even though the effect ran
on every chunk.

### Heavy-card style

Pre-R193-follow-up, the trace items were:
- `.step-card` — `border: 1px solid` + `background: var(--chat-surface)`
  + `box-shadow: 0 1px 2px ...` + `border-radius: 10px`
  → a card, not a trace item.
- `.step-tool` — `background: var(--chat-code-bg)` + `border`
  + `border-radius: 8px` + `padding: 6px 10px` → a terminal
  pill, not a single line of text.
- `.step-live` — bordered, padded, coloured.
- `.step-summary` — bordered, padded, italic.

The user's reference interface is a flat list with no
boxes. Heavy cards in the chat make a 6-tool query look
like a 6-card stack — visually loud and the user has to
parse each border.

## Fixes

### R193-followup-fix-1: auto-scroll via direct scrollTop

`MessageList.tsx`:

```js
useEffect(() => {
  if (pinned && el) {
    requestAnimationFrame(() => {
      if (!listRef.current) return;
      const distance = listRef.current.scrollHeight
        - listRef.current.scrollTop
        - listRef.current.clientHeight;
      // Re-check pinned: user may have scrolled up between
      // the effect firing and rAF running.
      if (distance < 80) {
        listRef.current.scrollTop = listRef.current.scrollHeight;
      }
    });
    lastSeenRef.current = cur;
  } else {
    // bump unseenCount for the jump-to-bottom pill
  }
}, [messages, isStreaming, steps, currentStepId, subTasks.length, currentSubTaskId, pinned]);
```

Why this works:
- `scrollTop = scrollHeight` is **synchronous and
  idempotent**. Every chunk snaps the viewport to the new
  bottom; there's no animation to cancel.
- The rAF wrap ensures the browser has laid out the new
  content (the streamed chunk grew the message list) before
  we measure `scrollHeight`. Without rAF, `scrollHeight`
  reads stale and we under-scroll.
- The re-check of `distance < 80` in rAF handles the
  "user scrolled up between effect-fire and rAF-run" race.
  If they scrolled up, the new `distance` will be >80, we
  skip the scroll, and the unseen counter increments as
  expected.

### R193-followup-fix-2: scroll listener uses pinnedRef

Pre-fix: the scroll listener's useEffect had
`[pinned, messages.length, subTasks.length, steps.length]`
in deps, so the listener was torn down and re-registered
on every `pinned` change. A scroll event that landed in
the ~ms window between teardown and re-registration would
miss the `wasPinned` transition and silently corrupt the
state.

Post-fix:
```js
const pinnedRef = useRef(pinned);
useEffect(() => { pinnedRef.current = pinned; }, [pinned]);

useEffect(() => {
  const el = listRef.current;
  if (!el) return;
  const onScroll = () => {
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
    const wasPinned = pinnedRef.current;     // ref, not closure
    const nowPinned = distance < 80;
    if (!wasPinned && nowPinned) { setUnseenCount(0); ... }
    pinnedRef.current = nowPinned;
    setPinned(nowPinned);
  };
  el.addEventListener('scroll', onScroll, { passive: true });
  return () => el.removeEventListener('scroll', onScroll);
}, []);  // mount-once
```

The listener reads `pinned` via `pinnedRef` (a ref that
mirrors the state via a one-line effect) and the useEffect
is mount-once. No teardown churn, no missed transitions.

### R193-followup-fix-3: flat-trace style

`MessageList.css`:
- `.step-card`: `border: none`, `background: transparent`,
  `border-radius: 0`, `box-shadow: none`, no top border.
  The only visual cue is the step head's hover state and
  a subtle background tint on `.step-active`
  (`rgba(16, 185, 129, 0.04)`) so the user can find "the
  row that's working right now" without it being a card.
- `.step-tool`: `border: none`, `background: transparent`,
  `border-radius: 0`, `padding: 0`, `padding-left: 4px`.
  Hover: `background: rgba(255, 255, 255, 0.025)`. Tool
  name in muted text (was accent), inline command in the
  primary text colour, copy button only visible on hover.
  Output: a 2px left border instead of a top border, no
  background fill — looks like a quote block, not a card.
- `.step-head`: `padding: 4px 6px` (was `9px 12px`),
  `min-height: 26px`, no surface-2 background.
- `.step-body`: `padding: 2px 6px 6px 24px` (was
  `10px 14px 14px 14px`), no top border, gap 2px between
  tool items.
- `.step-summary`: italic, no border, no padding, just a
  1-line muted footer.

Result: each step reads as a flat trace item. The SubTaskCard
above still has its border (it groups the steps), but the
items inside it are flat.

### R193-followup-fix-4: auto-expand active tool

`MessageList.tsx` `ToolEventPill` now accepts `autoExpand`:
- `useState(autoExpand && ev.output != null)` so the latest
  tool in the active step starts expanded.
- The StepCard passes `autoExpand={isActive && i === arr.length - 1}`
  so only the **last** tool in the **active** step is
  auto-expanded. Earlier tools stay collapsed to keep the
  trace compact.

This is the second part of the "can't see the output"
complaint. Even with the streamed text landing in the
tool's `output` field, the user had to click the pill to
reveal it. Auto-expanding the active tool fixes that.

### R193-followup-fix-5: visible jump-to-bottom button

`MessageList.css` `.jump-to-bottom`:
- Bumped `bottom: 24px` → `32px` (above the input box).
- Larger `border-radius: 22px` + more padding (8px 14px).
- Stronger box-shadow (12px vs 8px blur).
- `display: inline-flex; gap: 6px;` so the icon and text
  are inline (was a flat pill).

The button now reads as a clear "↓ N 条新消息 / ↓ 正在生成…"
affordance at the bottom of the chat, matching the
white-background example.

## Tests

- **9 R193 source-pin tests** in
  `src/store/toolOutputDeltaR193.test.ts` (was 5, +4 for
  the follow-up):
  - (5 R193) tool_output_delta wiring, appendToolOutput,
    auto-scroll dep on `steps`, .step-card `width: 100%`
    no `max-width: 760px`, buildStepSummary + .step-summary
  - (1 R193-followup) auto-scroll uses direct `scrollTop`,
    inside `requestAnimationFrame`, not `scrollIntoView`
  - (1 R193-followup) latest tool in active step has
    `autoExpand={isActive && i === arr.length - 1}`
  - (1 R193-followup) `.step-card` has `border: none` and
    `background: transparent` (flat-trace style)
  - (1 R193-followup) `.step-tool` has `border: none` and
    `background: transparent` (flat-trace style)
- **Total: 896 desktop tests pass** (75 → 76 test files,
  887 → 896 tests).
- Java side: `mvn -pl aethercode-core,aethercode-protocol -am
  compile` BUILD SUCCESS (no Java-side changes in this
  follow-up).

## Build

Desktop: `npx tauri build` — exe 3,953,664 bytes (was
3,952,640 in v0.2.33; +1KB for the new auto-expand +
scrollTop changes), SHA256
`52702CF1A60C5F49C90EFD371FE6264D5F8BC357AFA34D58840D06A580B47A24`.

## Release

- `release/aethercode-0.2.34/AetherCode.exe`
  (3,953,664 bytes, SHA256 `52702CF1A60C5F49C90EFD371FE6264D5F8BC357AFA34D58840D06A580B47A24`)
- `release/aethercode-0.2.34/aethercode-0.2.34.jar`
  (55,310,537 bytes — same as v0.2.33, no Java changes)
- `aethercode/dist/aethercode-0.2.34.jar`

## Files touched

- `aethercode-desktop/src/components/MessageList.tsx`
  - auto-scroll: `scrollTop = scrollHeight` inside
    `requestAnimationFrame`, re-check pinned in rAF
  - scroll listener: `pinnedRef`, mount-once
  - `ToolEventPill`: `autoExpand` prop
  - StepCard: pass `autoExpand={isActive && i === arr.length - 1}`
- `aethercode-desktop/src/components/MessageList.css`
  - `.step-card`: borderless, transparent, no shadow
  - `.step-tool`: borderless, transparent, no padding
  - `.step-head`: lighter padding, no surface-2 background
  - `.step-body`: lighter padding, no top border
  - `.step-summary`: italic, no border
  - `.jump-to-bottom`: bigger, more shadow, inline icon+text
- `aethercode-desktop/src/store/toolOutputDeltaR193.test.ts`
  - +4 R193-followup source-pin tests
- `aethercode-desktop/docs/R193-FOLLOWUP-SCROLL-STYLE-2026-09-02.md`
  (this file)

## Lessons learned (2026-09-02)

1. **`scrollIntoView({behavior: 'smooth'})` is the wrong
   primitive for "follow live output"** — it's async, gets
   cancelled by the next call, and leaves the viewport
   stuck. Use direct `scrollTop = scrollHeight` inside
   `requestAnimationFrame` so the new content is laid out
   before you measure. The "smooth" is a UX nicety for
   single navigation events; for "follow every chunk" it's
   actively wrong.
2. **Mount-once is the right default for scroll/key/etc.
   listeners** — deps like `[pinned, ...]` cause the
   listener to tear down and re-register on every change,
   creating a tiny race window where events can be lost.
   Use a `useRef` to mirror the state and listen once.
3. **"User can't see the output" is two problems at once**:
   (a) the auto-scroll doesn't reach the new content, AND
   (b) the output is hidden behind a collapsed tool pill.
   v0.2.33 fixed (a) but not (b). Always check: when the
   user says "I can't see X", trace X through the entire
   render path: emit → store → component → visible element.
4. **Heavy borders everywhere = "cramped"** — when the
   user shows you a flat-trace example, take it seriously.
   Stripping the borders from `.step-card` and `.step-tool`
   (and the surface-2 background from the head) was a
   CSS-only change that took 5 minutes but completely
   changed the feel of the chat. The cards were trying
   too hard to "group" the trace; the example shows
   grouping is unnecessary when each item is self-evident.
5. **Auto-expand the in-flight thing** — the active tool's
   output is the one the user is most likely to want to
   see right now. Defaulting it to expanded is a one-line
   change that closes the "I had to click to see it" gap.
   The user's other tools stay collapsed; they can still
   open any of them with a click.
