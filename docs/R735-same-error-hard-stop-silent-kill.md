# R735 — `same_error_hard_stop` silent kill + chat stuck-at-top (2026-10-20)

> **Status: SHIPPED.** All items landed in this round.
>
> **Severity**: P0 — both bugs are user-visible and silent.
>
> **Root of report**: user screenshot showing a RAG sub-task at
> "12m · 1 msg" in the left panel while the engine header read
> `● Idle` (green), with two red-X tool errors just before the stop
> and no error banner / retry affordance anywhere. The user also
> reported the chat viewport was "stuck in the middle — I can scroll
> up to see older content but not down".
>
> **Shipped in this round**:
> - `aethercode/aethercode-core/.../engine/QueryEngine.java` — R735-A1
> - `aethercode/aethercode-core/src/test/.../QueryEngineR735Test.java` — R735-A2
> - `aethercode-desktop/src/store/index.ts` — R735-A3
> - `aethercode-desktop/src/components/Header.tsx` — R735-A4
> - `aethercode-desktop/src/components/MessageList.tsx` — R735-B1
> - `aethercode-desktop/src/components/__tests__/MessageListR735AutoScroll.test.tsx` — R735-B2

---

## Part 1 — `same_error_hard_stop` silent kill

### 1.1 Symptom

The desktop was running a RAG sub-task. The LLM produced a long plan,
then a batch of `file_read` / `bash` calls inspecting the workspace for
"abnormal files". Two calls returned errors (red ✕):

- `file_read D:\work\workspace\idea\engine\AetherCode\NUL`
- `bash powershell Get-Item 'NUL'`

After the last `bash powershell Get-ChildItem -Force | Where-Object { $_.Name …`
no further trace events arrived, the header switched to `● Idle`
(green), no error banner appeared, and the "↻ Retry" button on
`EndOfTaskPanel` never showed.

### 1.2 Root cause

`aethercode-core/src/main/java/org/aethercode/core/engine/QueryEngine.java`
— the `same_error` death-spiral hard-stop (added in R181) terminated the
run **without emitting `StreamEvent.RunEnd`**:

```java
// R735-A1 anchor — QueryEngine.java ~line 2142
if (postInfo != null && "same_error".equals(postInfo.kind())
        && postInfo.shouldStop()) {
    LOG.warn("R181: hard-stopping run on same_error death spiral …");
    action.accept(new StreamEvent.SideNote("same_error_hard_stop", …));
    stopReason = "same_error_hard_stop";
    finished = true;
    return false;            // ← BUG: no RunEnd
}
```

Compare the three sibling terminal branches in the same `tryAdvance` body:

| Branch | line | Emits RunEnd? |
|---|---|---|
| `Thread.interrupted()` → `cancelled` | 1565-1575 | ✅ `action.accept(new StreamEvent.RunEnd("cancelled", List.of()))` |
| `loop_detected: empty_tool_input` | 1947-1952 | ✅ `action.accept(new StreamEvent.RunEnd(stopReason, List.of()))` |
| `awaiting_user_decision` | 2189-2201 | ✅ `action.accept(new StreamEvent.RunEnd(stopReason, lastAssistantBlocks))` |
| **`same_error_hard_stop` (R181)** | **2142-2155** | ❌ **missing** |

### 1.3 How the omission cascaded into the UI

`aethercode-desktop/src/store/index.ts` — `case 'run_end':` (line 3207)
is the **single place** that:

- flips `isStreaming: false`,
- writes `lastFailedPrompt` when `stopReason ∈ {error, loop, max_turns}`,
- clears `currentQuery` (line 3340).

Without a `run_end`, none of the three happen. `isStreaming` was
already `false` (the last `tool_result` flipped it), so the Header's
`streamingPhase` computed to `null`, `active = currentQuery ??
activeSubagent` was empty, and the pill fell through to
`return { text: '● Idle', color: 'var(--success)' }` —
`aethercode-desktop/src/components/Header.tsx:161`.

Because `stopReason` was never `"error"` / `"loop"` / `"max_turns"`,
`lastFailedPrompt` stayed `null` and the ↻ retry button never rendered.

### 1.4 The fix (R735-A1)

```java
stopReason = "same_error_hard_stop";
finished = true;
// R735 (2026-10-20): the previous R181 patch forgot to emit
// StreamEvent.RunEnd. … lastAssistantBlocks is the final assistant
// text accumulated for this run (line 1537, updated at 1771).
action.accept(new StreamEvent.RunEnd(stopReason, lastAssistantBlocks));
return false;
```

### 1.5 Regression guard (R735-A2)

`aethercode-core/src/test/java/org/aethercode/core/engine/QueryEngineR735Test.java`
— 2 source-pin tests:

1. **`everyTerminalBranch_emitsRunEnd`** — scans the whole file for
   `stopReason = "…"` literals (skipping the `end_turn` per-turn reset
   seed) and asserts each has a `StreamEvent.RunEnd` emit inside the
   following 1200-char window. Also asserts at least 4 terminal
   flavours exist, so a rename forces re-validation.
2. **`sameErrorHardStop_branchCarriesLastAssistantBlocks`** — asserts
   the branch emits `RunEnd(stopReason, lastAssistantBlocks)`, not
   `List.of()`, so the trailing assistant body still gets
   `isComplete=true` + markdown re-parse in `MessageList`.

Verified: `Tests run: 2, Failures: 0`.

Note on the regex: `loop_detected: empty_tool_input` is a string
*concatenation* (`"loop_detected: empty_tool_input (" + n + " …)"`),
so the pattern must capture `stopReason\s*=\s*"([^"]*)"` (whole head
literal), not `"([a-zA-Z_]+)"`. The window for the second test is 4000
chars because the R735 explanatory comment block sits between the
SideNote literal and the RunEnd emit.

---

## Part 2 — chat viewport stuck at top, cannot scroll down

### 2.1 Symptom

A session with a long transcript renders with the viewport at
`scrollTop=0`. Scrolling up works (older content is above). Scrolling
down does nothing — the newest content is unreachable without sending a
new prompt.

### 2.2 Root cause — a first-paint state race

`aethercode-desktop/src/components/MessageList.tsx`:

- `pinned` initialises to `true` (line 1069).
- The scroll listener (line 1199, deps `[messages.length, subTasks.length, steps.length]`)
  computes `distance = scrollHeight - scrollTop - clientHeight` and sets
  `pinned = distance < 80`.
- On the **first** listener invocation after mount, the list is already
  taller than the viewport and `scrollTop` is still `0`, so
  `distance > 80` → `setPinned(false)`.
- The auto-scroll `useEffect` (line 1217) gates on
  `if (pinned && el) { … }`. Once `pinned` is `false`, the auto-scroll
  branch **never re-enters for the lifetime of the session**, and the
  `else` branch accumulates `unseenCount` forever.

R275 had already fixed a *related* first-paint case (it removed a
`distance < 80` guard from the auto-scroll branch), but did not address
the listener firing first and flipping `pinned` off.

### 2.3 The fix (R735-B1)

Three coordinated changes:

1. **`useLayoutEffect` pre-paint snap** (new) — snaps
   `el.scrollTop = el.scrollHeight` on mount and on
   `[currentSessionId]` change. `useLayoutEffect` (not `useEffect`)
   runs before the browser paints and before the listener's first
   `onScroll`, so the listener measures `distance ≈ 0` and keeps
   `pinned = true`.
2. **`userScrolledRef`** (new) — distinguishes "user dragged the
   scrollbar away from the bottom" from "a programmatic snap landed".
   The scroll listener sets it `true` only on `distance > 80`; the
   mount-snap and `jumpToBottom` both clear it *before* their
   `scrollTop` assignment so the resulting `onScroll` is not
   mis-classified as a user gesture.
3. **Auto-scroll gate relaxed** — `shouldFollowTail = (pinned ||
   !userScrolledRef.current) && !!el`. Even if `pinned` is somehow
   `false`, auto-scroll still follows the tail as long as the user
   hasn't explicitly scrolled away.

### 2.3a R735-fix1 — the single synchronous snap regressed, now converges over frames

**The user reported immediately after R735 shipped**: "为什么现在又变成任务执行放到 prompt 上面去了啊？" — the newest tool card rendered *above* the user prompt bubble, with the transcript visibly parked mid-container.

Root cause: `useLayoutEffect` fires after React commits the DOM, but
the children's **final height is not yet stable**. Markdown blocks,
`<details>` fold affordances, and the streaming cursor all resolve
their heights in *subsequent* layout passes. A single
`scrollTop = scrollHeight` therefore read a **stale** `scrollHeight`
and landed the viewport mid-transcript. As the real heights grew in
the following frames, the viewport was left straddling the boundary
between the older tool output and the newest prompt + tool card.

The fix is to **converge** rather than snap once:

```ts
const MAX_SNAP_FRAMES = 6;
let frames = 0;
let lastBottom = -1;
const converge = () => {
  const node = listRef.current;
  if (!node) return;
  node.scrollTop = node.scrollHeight;
  const bottom = node.scrollHeight - node.scrollTop - node.clientHeight;
  frames += 1;
  // Converged: at the bottom AND the offset didn't move this frame.
  if (bottom <= 1 && Math.abs(bottom - lastBottom) < 1) return;
  if (frames >= MAX_SNAP_FRAMES) return;
  lastBottom = bottom;
  rafId = requestAnimationFrame(converge);
};
converge();
return () => { if (rafId) cancelAnimationFrame(rafId); };
```

Each frame re-reads `scrollHeight` (now grown to its real value) and
re-snaps. The loop exits when the measured bottom offset stops moving
(content height settled) or the 6-frame budget is exhausted. The
cleanup `cancelAnimationFrame` matters: a session switch mid-converge
must not leave an orphaned rAF writing `scrollTop` against the new
session's list.

Note the contrast with the **auto-scroll** effect (line 1354), which
was always correct: it already wrapped its assignment in a single
`requestAnimationFrame`, which is enough because it re-runs on every
`messages` / `steps` change and therefore always re-measures against
settled layout. Only the mount / session-switch snap needed the
multi-frame treatment.

### 2.4 Regression guard (R735-B2)

`aethercode-desktop/src/components/__tests__/MessageListR735AutoScroll.test.tsx`
— 7 source-pin tests, each with an explanatory assertion message:

- imports `useLayoutEffect` from react
- defines `userScrolledRef = useRef(false)`
- `useLayoutEffect` snaps `scrollTop = scrollHeight` on `[currentSessionId]`
- `userScrolledRef.current = false` appears **before** the `scrollTop` assignment
- auto-scroll gates on `(pinned || !userScrolledRef.current)`
- scroll listener sets `userScrolledRef.current = true` on `distance > 80`
- `jumpToBottom` clears `userScrolledRef` before snapping

Verified: `Tests 7 passed (7)`.

---

## Part 3 — defence in depth (R735-A3 / A4)

### 3.1 Store: failure reason + timestamp

`aethercode-desktop/src/store/index.ts`:

- New fields `lastFailedStopReason: string | null` and
  `lastFailedAt: number | null` on `AppState`.
- `failedStopKinds` extended from `{error, loop, max_turns}` to also
  include `same_error_hard_stop` and `awaiting_user_decision`.
- Successful `stop` / `end_turn` clears all three fields.
- `retryLastFailedPrompt` clears all three before re-dispatching.

### 3.2 Header: `● Failed` and `● Sub-task stuck`

`aethercode-desktop/src/components/Header.tsx`:

- New fourth status bucket, evaluated **after** `stale` and
  **before** `running`:
  ```ts
  if (lastFailedStopReason && lastFailedAt && (now - lastFailedAt) < 60_000) {
    return { text: `● Failed (${lastFailedStopReason}) · ${ago}s ago`,
             color: 'var(--error)' };
  }
  ```
  A 1-second `setInterval` ticker (only armed while inside the 60s
  window, self-clearing) keeps the "Ns ago" counter live and makes the
  pill fall back to `● Idle` after the window.
- New stuck-sub-task warning, also before `running`:
  ```ts
  if (active && runElapsedMs > 180_000
      && (!lastChunkTs || elapsedSinceChunk > 30_000)) {
    return { text: `● Sub-task stuck · ${stuckSec}s (no response in ${…}s)`,
             color: 'var(--warning)' };
  }
  ```
  This catches the exact shape from the screenshot: a query alive for
  12 minutes whose last chunk was long ago.

### 3.3 Stream-stale watchdog now produces a Failed state

`aethercode-desktop/src/store/index.ts` — the existing 90s stale
watchdog (line 8361) now:

- **skips** if a failure was already recorded inside the stale window
  (avoids double-badging an already-failed run);
- on force-end, sets
  `lastFailedStopReason: 'stale_force_end'`, `lastFailedAt: Date.now()`,
  and `lastFailedPrompt` — falling back to the **last user message** in
  the chat when `currentInput` is empty, so ↻ retry always has something
  to fire;
- the toast copy now says "The run was force-ended. You can retry the
  prompt from the End-of-task panel."

---

## Verification log

| Check | Result |
|---|---|
| `mvn compile -DskipTests` | BUILD SUCCESS (63s) |
| `mvn test -pl aethercode-core -Dtest=QueryEngineR735Test` | 2/2 pass |
| `npx vitest run MessageListR735AutoScroll` | 8/8 pass (7 + 1 R735-fix1 convergence case) |
| `npm run typecheck` (`tsc -b --noEmit`) | clean |
| `npm run build` (`tsc -b && vite build`) | ✓ built in 7.73s, `dist/assets/index-CDmknzQa.js` 783.71 kB |
| `mvn package -DskipTests` | BUILD SUCCESS (74s) |
| shaded jar → `src-tauri\target\release\aethercode.jar` | 56,850,189 bytes, mtime touched |
| daemon restart | desktop auto-hook relaunched PID 22700 on :22888 |
| `POST /rpc memory/list` | `{"result":{"ok":true,…}}` |
| `POST /rpc getState` | full state payload, 35 tools |

Note: `QueryEngineAnchorR726Test` fails on this checkout for reasons
unrelated to R735 (it reads `docs/R7*.md` files from a temp dir and is
sensitive to which docs exist at the time). It was red before this
round's changes.

---

## Part 4 — R735a: upstream 500 → silent kill (the user reported "任务直接死了" right after R735 shipped)

The same `● Idle` symptom from a different trigger. The LLM API
(`api.minimaxi.com`) returned **500 Internal Server Error**;
`SpringAiChatClient` retried 3 times per `R697-retry`, all failed,
and the stream threw `WebClientResponseException`. The exception
escaped `tryAdvance` at `currentLlmIt.next()` — the Spliterator
went into its "dead" state with **no RunEnd ever emitted** to the
`stream_event` channel. Desktop fell into the green `● Idle` with no
banner and no retry.

The fix (R735a) wraps the **entire** `tryAdvance` body in
`try { … } catch (Throwable t) { … }`. On catch:

```java
finished = true;
stopReason = "runtime_exception: " + t.getClass().getSimpleName()
            + ": " + (t.getMessage() == null ? "" : t.getMessage());
LOG.warn("R735a: LLM stream threw, ending run with {}", stopReason, t);
try {
    action.accept(new StreamEvent.RunEnd(stopReason, List.of()));
} catch (Exception ignored) {
    // downstream may already be closed (WS dropped, cancelled run)
}
return false;
```

Stop-reason extension to the desktop's `failedStopKinds` set was
already in scope — `failedStopKinds` (R735) now includes
`runtime_exception` because the literal in the catch handler is a
substring of any stopReason we emit. The R735 Header's `● Failed
(reason)` pill + ↻ retry button now surface every chat-stream
exception as a real failure, not a green Idle.

Regression guard: `QueryEngineR735Test.tryAdvance_hasOuterTryCatchRuntimeException`
— asserts the try/catch wrapper is present AND `runtime_exception`
appears in the body. Future refactors that strip the safety net
fail the build.

---

## Part 5 — R735b: the actual root cause (why R735 AND R735a both failed to fix it)

R735 shipped the `same_error_hard_stop` RunEnd fix. R735a shipped
the `tryAdvance` try/catch. The user reported "还是不行，还是一样"
twice. The real bug was in neither place.

### 5.1 Evidence

```
14:43:34.000 WARN  SpringAiChatClient - R697-retry: chat call attempt 1 failed: status=500 ...
14:43:36.248 WARN  SpringAiChatClient - R697-retry: chat call attempt 2 failed: status=500 ...
14:43:39.112 WARN  SpringAiChatClient - R697-retry: chat call attempt 3 failed: status=500 ...
14:43:42.114 ERROR SpringAiChatClient - streaming call failed: 500 Internal Server Error
```

Zero `R735a: LLM stream threw` lines. So the exception **never
reached** the `tryAdvance` catch — it was already handled one layer
up.

### 5.2 Root cause

`aethercode-engine-springai/src/main/java/org/aethercode/engine/springai/SpringAiChatClient.java:323-328`:

```java
} catch (Exception e) {
    LOG.error("streaming call failed: {}", e.getMessage(), e);
    queue.offer(new StreamEvent.RunEnd(
            "error: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
            List.of()));
    return;
}
```

The chat client **catches** the exception and emits its own RunEnd
whose `stopReason` is a **decorated** string:

```
"error: WebClientResponseException$InternalServerError: 500 Internal Server Error from POST https://api.minimaxi.com/v1/chat/completions"
```

That's a ~120-character string, not the bare enum `"error"`.

Meanwhile the desktop's run_end handler classified failures with:

```ts
const failedStopKinds = new Set(['error', 'loop', 'max_turns', …]);
const isFailedRun = stopReason ? failedStopKinds.has(stopReason) : false;
```

`Set.has("error: WebClientResponseException$InternalServerError: 500 ...")`
returns **false**. The run was classified as a *success*.
`lastFailedPrompt` stayed `null`, so the ↻ retry button never
rendered, and `lastFailedStopReason` stayed `null`, so the Header
fell through to the green `● Idle` bucket.

**R735 and R735a were both correct fixes for OTHER silent-kill
paths.** The actual trigger for this user's symptom lives entirely
in the classification logic. Two rounds of engine-side work could
never have fixed it, because the engine was already doing the right
thing — it emitted a terminal RunEnd, with the right kind of
information. The renderer just refused to read it.

### 5.3 The fix (R735b)

`aethercode-desktop/src/store/index.ts`:

```ts
const failedStopKinds = [
  'error', 'loop', 'max_turns',
  'same_error_hard_stop', 'awaiting_user_decision',
  'runtime_exception', 'stale_force_end', 'cancelled',
] as const;
const failedKind = stopReason
  ? failedStopKinds.find((k) => stopReason === k
      || stopReason.startsWith(`${k}:`) || stopReason.startsWith(`${k} `))
  : undefined;
if (failedKind) {
  set({
    lastFailedPrompt: get().currentInput || get().lastFailedPrompt,
    lastFailedStopReason: failedKind,   // the BARE KIND, not the decorated string
    lastFailedAt: Date.now(),
  });
}
```

Two changes, both load-bearing:

1. **Prefix matching** (`startsWith`) instead of `Set.has`. Every
   decorated stopReason the engine emits on an error path starts
   with its canonical kind followed by `": "`. All three
   `SpringAiChatClient` error sites (`line 135`, `line 275-279`,
   `line 326`) satisfy this.
2. **Store the bare kind, not the decorated string.** The decorated
   value embeds a full URL and would blow out the Header pill. The
   pill now renders `● Failed (error)`.

`runtime_exception` was already added by R735a and is matched by the
same prefix rule, so R735a stays wired. `cancelled` is new — a hard
cancel that races a real failure now surfaces `● Failed (cancelled)`
instead of silently clearing the retry slot.

### 5.4 Regression guard

`aethercode-desktop/src/store/__tests__/failedStopReasonR735b.test.ts`
— 5 source-pin tests:

1. rejects `failedStopKinds.has(` outright; requires `.find()` + `startsWith`
2. requires BOTH the `` `${k}:` `` and `` `${k} ` `` delimiters
3. requires all 8 kinds present in the list
4. requires `lastFailedStopReason: failedKind` and rejects `lastFailedStopReason: stopReason`
5. requires the success branch to stay exact-match (`'stop' || 'end_turn'`)

### 5.5 What this cost

Two full rounds (R735's engine half, R735a's engine half) that
compiled, tested green, deployed, and were verified byte-for-byte
inside the shaded jar — all correct, none of them the fix. The
lesson is in the Lessons section (1052): **when a user reports
"your fix didn't work", the fix may be in a different layer than
where you were looking. Grep the runtime log for your own marker
string before assuming the code path was reached.**

---

## Lessons

- **Lesson 1046 (R735) — every terminal branch in a
  `tryAdvance` / iterator body MUST end with a terminal event emit.**
  Audit every `stopReason = "…"; finished = true;` line: if the next
  statement is `return false;` and there is no
  `action.accept(new StreamEvent.RunEnd(...))` within a few lines, it
  is a bug. R181 was added in a hurry and slipped past R720's
  systematic repair because R720 audited the L5 compact path, not the
  loop-detector path. The `QueryEngineR735Test` source-pin now makes
  the whole class of bug a build failure.

- **Lesson 1047 (R735) — a status pill that only encodes
  "busy / not busy" will eventually lie.** `● Idle` meant
  `!isStreaming && !currentQuery && !activeSubagent`; a dead engine
  satisfies all three. The pill needed a **fourth** bucket carrying the
  failure reason, plus a timestamp so it can expire. When a UI element
  is the user's only health signal, it must distinguish
  *healthy-resting* from *dead*.

- **Lesson 1048 (R735) — first-paint races in scroll/observer code
  need a `useLayoutEffect` snap, not a `useEffect` guard.** The
  observer listener (registered once on mount) fires before any
  effect-driven correction, so any state it derives from
  (`distance < 80 → pinned`) is garbage on the first tick. Fix by
  snapping the DOM state pre-paint AND by tagging programmatic scrolls
  so the observer can tell them from user gestures. A plain boolean
  state is not enough — the observer needs to distinguish *who* moved
  the scroll.

- **Lesson 1048a (R735-fix1) — `useLayoutEffect` guarantees DOM
  *commit*, not final *layout*.** The first R735 fix snapped
  `scrollTop = scrollHeight` exactly once inside a `useLayoutEffect`,
  which fixed the original "stuck at top" bug — and immediately
  introduced a worse one ("newest tool card renders above the user
  prompt"), because markdown blocks, `<details>` folds, and the
  streaming cursor only resolve their heights in *later* layout
  passes, so the single read was against a stale `scrollHeight`.
  Any "scroll to bottom after the content changes shape" fix must
  **converge over a bounded number of animation frames** and exit when
  the measured bottom offset stops moving — plus `cancelAnimationFrame`
  on cleanup so a session switch mid-converge can't leave an orphaned
  rAF writing `scrollTop` against the new session's list. Note the
  asymmetry: the *auto-scroll* effect only needed a single rAF (it
  re-runs on every content change and always re-measures settled
  layout), while the *mount / session-switch* snap needs the full
  multi-frame convergence. "Use a layout effect" is not the same as
  "the layout is done".

- **Lesson 1049 (R735) — source-pin tests are a legitimate
  regression guard for terminal-event contracts, not just for
  component structure.** A full `Stream<StreamEvent>` integration test
  for the `same_error` hard-stop would need a stub ChatClient, a stub
  StreamingToolExecutor, and a ProgressLoopDetector forced to a
  specific tier — and would break every time the engine's internal
  Spliterator grew a field. The 2-test source-pin covers the same
  contract with no fixture coupling. (Corollary: the pattern must
  capture whole string literals including concatenations, and windows
  must be sized to clear the explanatory comments a fix adds.)

- **Lesson 1050 (R735) — a `● Idle` symptom report should always
  trigger both a state-machine audit AND a render audit.** The same
  screenshot produced two independent P0s in two layers (daemon engine
  terminal-event contract; renderer first-paint scroll state). Neither
  was visible from the other's code. When a user reports "it just
  stopped / it looks stuck", audit the whole stack before concluding
  a single root cause.

- **Lesson 1051 (R735a) — `currentLlmIt.next()` inside `tryAdvance`
  needs a top-level `catch (Throwable t) {` that emits RunEnd.** The
  Spliterator's tryAdvance does not get a second chance: any throw
  escaping it kills the Spliterator with no terminal event ever
  delivered to the consumer, and the renderer falls into the green
  `● Idle` state with no error banner. The fix is to wrap the entire
  tryAdvance body in try/catch and translate every throw into a
  RunEnd with `stopReason="runtime_exception: <class>: <message>"`.
  Without this, every "upstream 500 from the chat provider that
  escaped the retry loop" produces a silent kill. The new case the
  user reported immediately after R735 shipped ("任务直接死了,
  没有任何输出" — LLM API 500, 3 retries failed, no run_end
  ever emitted, sprite sitting in `● Idle`) was this exact pattern
  in a different trigger: an HTTP exception instead of a tool-loop
  detector. Add `failedStopKinds` to cover it, pin the contract with
  `tryAdvance_hasOuterTryCatchRuntimeException`, and add a 1-line
  runtime_exception entry to the user-visible stopReason set.

- **Lesson 1052 (R735b) — when the user reports "your fix didn't
  work", the bug may be in a different layer than the one you
  patched.** Two full engine-side rounds (R735's RunEnd emit,
  R735a's tryAdvance catch) compiled, tested green, deployed, and
  were verified byte-for-byte inside the shaded jar — and neither
  was the fix. The engine was already doing the right thing: it
  emitted a terminal `RunEnd`. The renderer refused to read it,
  because `failedStopKinds.has(stopReason)` was an **exact-match
  Set lookup** and the engine's decorated stopReason
  (`"error: WebClientResponseException$InternalServerError: 500
  Internal Server Error from POST https://api.minimaxi.com/…"`) is
  a ~120-char string, not the bare enum `"error"`. **Two concrete
  techniques that would have found this in round one:** (a) grep
  the runtime log for a marker string unique to your own fix —
  `Select-String 'R735a' $env:TEMP\aethercode-daemon-port22888.log`
  returning zero hits proved the catch was never reached, and that
  single grep invalidated the whole R735a theory; (b) read the
  producer's `stopReason` construction before writing the
  consumer's matcher — the decorated-string shape was visible at
  `SpringAiChatClient.java:325` in one grep. **Corollary: an exact
  Set lookup across a wire boundary is a latent bug whenever the
  producer can decorate the value.** Any stopReason / status /
  error-code comparison that crosses a process or protocol boundary
  should be prefix- or substring-tolerant, and should store the
  bare kind for display rather than the decorated payload.

- **Lesson 1053 (R735b) — "BUILD SUCCESS" from `mvn package` is not
  evidence the patch shipped.** The R735a deployment reported
  `BUILD SUCCESS in 64.94s` and a synced jar of the correct byte
  length (56,850,509) — and the jar did **not** contain the patch.
  Maven's incremental compiler compared mtimes, judged the change
  to be formatting-only (the Python edit that wrapped `tryAdvance`
  in a try block re-indented ~42,000 characters, so the diff was
  near-total), and skipped class regeneration. The shaded jar
  faithfully repackaged the *stale* `QueryEngine$1.class`.
  **The only reliable verification is to extract the class from the
  final artifact and grep its constant pool:**
  ```powershell
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
  $e = $zip.Entries | Where-Object { $_.FullName -eq 'org/aethercode/core/engine/QueryEngine$1.class' }
  [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $tmp, $true)
  $t = [System.Text.Encoding]::UTF8.GetString([System.IO.File]::ReadAllBytes($tmp))
  $t.Contains('R735a')   # must be True
  ```
  When a deploy "doesn't take", run `mvn clean package` — not
  `mvn package`.
---

## Part 6 — R736: `\u4efb\u52a1\u6302\u6b7b` — dangling / alien tool_use (2026-10-20)

### 6.1 Symptom

The desktop fell into a long-running recovery loop: every new prompt —
even the user's "继续执行任务" — **immediately returned a 500 from
`api.minimaxi.com`** with the bare body
`{"type":"error","error":{"type":"bad_request_error","message":"invalid params, tool result's tool id(call_XXX) not found (2013)","http_code":"400"}}`
and the GPU returned a `500 unknown error (1000)` on the parallel
fallback path. The transcript on disk was
`D:\work\workspace\idea\engine\RAG\.aethercode\sessions\default.jsonl`
(70 messages, ~234 KB) — recovered from a prior session that had been
mid-task. The header pill read `● Failed (error)` (thanks to R735b) but
the retry button would only generate another identical 400, because the
**transcript shape itself** was the upstream problem.

### 6.2 What we proved against the live endpoint

Replaying ad-hoc bodies to `POST https://api.minimaxi.com/v1/chat/completions`
with the same model (`MiniMax-M3`), the same 35 tools, the same key:

| Body | Status |
|---|---|
| `tools + user "ping"` | `200` |
| `tools + user "ping" + streaming=true` | `200` |
| `35 tools + assistant(tool_calls=[c1]) + tool(c1)` | `200` |
| `35 tools + assistant(tool_calls=[c1,c2]) + tool(c1)+tool(c2)` | `200` |
| `no-tools transcript of 150K chars` | `200` |
| `35 tools + assistant(tool_calls=[c1,c2]) + tool(c1)` | **`400 "tool result's tool id(call_01a1579…) not found (2013)"`** |
| `35 tools + assistant(tool_calls=[c1,c2,c3]) + tool(c1)+tool(c3)` | **`400`** |
| `35 tools + assistant(tool_calls=[c1]) + tool(c1)+tool(c2)` | **`400`** |

The provider's contract is **adjacency-preserving batch**: an assistant
that declares N tool_calls must be immediately followed by **exactly N
tool_results**, whose ids are exactly the declared set in some order,
with no intervening user/system/assistant messages and no
"out-of-batch" tool_results pulled in from elsewhere.

### 6.3 Three rounds of repair, all wrong

The original R716 rule (drop tool_results whose id has no preceding
declaration) was insufficient — every survivor of R716 in this transcript
was a perfectly valid id reference to a perfectly valid declaration.
The invisible defect was the **missing sibling**.

- **R736 rev1** — per-id existence check. A tool_use is dangling iff
  no tool_result references it. Implemented as `Map<id, owner>` plus a
  forward pass that records which assistants "answered" each id.
  - Test cases all green, including a 9-case source-pin suite
    (`QueryEngineStripOrphanR736Test`).
  - End-to-end failed: the transcript
    `assistant(tc=[c1,c2,c3,c4]) + tool(c1) + tool(c2)` passed the
    filter (c3 and c4 stripped from the assistant) but the live API
    still 400ed — the next round of the same transcript still had
    cross-batch contamination.

- **R736 rev2** — adjacency check. Open an "assistant batch" at each
  assistant with declared ids; collect every immediately-following
  tool_result into a set; close at the first non-tool-result message;
  declared ids absent from answered set → dangling (strip), answered
  ids absent from declared set → alien (drop).
  - 15-case test green, including the "real RAG transcript shape"
    `assistant(tc=[a,b,c,d]) + tool(a) + tool(b)` and the
    cross-batch-intruder shape.
  - End-to-end failed: after the first round, the second still 400ed
    with a different orphan tool_id. Because the surviving
    transcript's TR(d71) had its **owner declared** (the assistant had
    declared d71 originally, even though d71's tool_use was already
    stripped this pass), the orphan-after-self-stripped case slipped
    past the alien check.

- **R736 rev3** — extend rev2 with a survivor check. After deciding
  which tool_use blocks each assistant KEEPS (declared minus
  dangling), build a per-assistant "surviving use ids" set; for any
  TR whose id is not in its owner's surviving set → drop with a
  dedicated warn line:
  `owner assistant at index 26 no longer carries a tool_use for this
  id — its own pass stripped it as dangling. Leaving the tool_result
  in place would produce the same 400 the strip was meant to avoid.`
  - 15 tests pass + 5 new R736rev2/rev3 cases
    (`realRagTranscriptShape_dropsUnansweredSiblingsOfABatch`,
    `crossBatchAlienToolResult_isDropped`,
    `orphanAfterSelfStrippedToolUse_alsoDropped`,
    `multipleBatchesInSequence_areProcessedIndependently`,
    `idempotenceRev2`).
  - End-to-end **passes**: the same user prompt that produced `400
    tool result's tool id (call_01a1579caf09767abfdddd71) not found`
    on every prior round produced a healthy tool-call chain (`bash`,
    `file_read`, `grep`, …), L4 head-trim continues dropping oldest
    messages every ~7s, transcriptSize grows from 73 → 118 → 127 →
    133 in steady state.

### 6.4 Where the culprit shapes come from

Two transcript pathologies compete to produce the 400:

1. **Cross-batch alien TR** (rev2 catches it). When an assistant A1
   declares `tool_calls=[a,b]`, only `tool(a)` lands contiguously, and
   `tool(c)` from a DIFFERENT earlier assistant happens to sit there
   instead. rev2 sees `c ∉ declared(A1)` and marks it alien. Log:
   ```
   R736: dropping tool_result message at index 29 (tool_use_id=call_…df):
   id=…df answered a DIFFERENT assistant (index 26) than its batch —
   providers reject cross-batch tool_results with a bare 500
   ```

2. **Self-stripped orphan TR** (rev3 catches it). The most common
   shape in the polluted RAG session: an assistant declared 4 calls,
   none of them got answered contiguously (the user typed
   "继续执行任务" four times in a row, breaking adjacency), and the
   transcript chunks that survive the L4 head-trim happen to include
   ONE of those tool_results near the surviving but tool_use-stripped
   assistant. rev2 by itself does not see this — the orphan
   tool_result has a valid id reference; rev3 does, by checking that
   the owner's surviving tool_use set still includes the id.

### 6.5 What changed

- `aethercode/aethercode-core/.../engine/QueryEngine.java:859`
  `stripOrphanToolResults(List<Message>)` — rewritten rev3 (Phase 1:
  declaredIds; Phase 2: per-batch adjacency → danglingIdxs +
  alienIdx; Phase 3: survivingUseIds + triple drop branch: R716
  no-owner / R736 alien / R736 self-stripped).
- `aethercode/aethercode-core/src/test/.../QueryEngineStripOrphanR736Test.java`
  — 15 source-pin tests, including the five rev2/rev3 cases above
  and the proven-good `partiallyAnsweredBatch_dropsTheUnansweredCall`
  carry-over.

### 6.6 Three new lessons (Lessons 1054–1056)

- **Lesson 1054 (R736) — the provider's tool-call contract is
  adjacency-preserving batch, not id-existence.** "Every tool_use
  has a matching tool_result, and every tool_result has a matching
  tool_use" is necessary but NOT sufficient; the surviving
  tool_use ids must be exactly the set of ids declared by the
  IMMEDIATELY PRECEDING assistant, with no non-tool messages in
  between. The 500 / 400 errors both the OpenAI-compat endpoint
  return for these shapes (the bare `500 unknown error (1000)` and
  the more informative `400 invalid params, tool result's tool id
  (call_X) not found (2013)`) are simply the validator saying "this
  message cannot be the n-th reply to that batch". Pre-R736 the
  filter only checked id-existence; that's an id-graph invariant
  that any contamination from L4 head-trim, mid-session tool
  retries, or unassisted tool-execution timeouts will silently
  break.

- **Lesson 1055 (R736 rev3) — when you strip a declaration in one
  pass, you owe a second pass to drop its now-orphaned tool_result.**
  rev2 caught cross-batch aliens but missed the case where the
  orphan tool_result's id still pointed at an owner that HAD
  declared it — the owner just stripped the declaration in the same
  pass. The fix is a `survivingUseIds[assistantIdx]` map computed
  from `declaredIds[i] − danglingIdxs[i]` BEFORE phase 3, and a
  third drop branch in the TR loop that compares each TR's id
  against its owner's surviving set. Watch the daemon log for the
  marker line `no longer carries a tool_use for this id` to
  confirm this branch fired.

- **Lesson 1056 (R736 deploy) — for transcript-repair work the
  unit tests are necessary but not sufficient.** All three rounds
  had passing unit tests against idealized shapes (paired
  user/assistant/ToolUseBlock/ToolResultBlock). The transcripts on
  disk after L4 head-trim and mid-session interruption contain
  shapes the unit tests don't enumerate — interleaved user
  prompts, anchor messages, ToolResultBlock-toolUse mismatches
  that survived schema migrations. The deployment-time check
  pattern that worked: emit a unique log marker in each repair
  branch, then `Select-String 'R736:.*self-strip\|no longer carries\|no preceding assistant\|answered a DIFFERENT' $env:TEMP\aethercode-daemon-port22888.log`
  right after a `query` call, and confirm both the count and the
  shape match the expected transcript pathology. If R736 logs a
  clean "no changes" line and the API still 400s, the live shape is
  one the unit tests don't model — fall back to dump-then-patch.

### 6.7 Deploy evidence (this round)

- `mvn clean package -DskipTests`: BUILD SUCCESS, shaded jar 56,852,573 bytes.
- `mvn test -Dtest=QueryEngineStripOrphanR736Test,QueryEngineR735Test`: 18 / 18 green.
- Jar verification (Lesson 1053): extracted `QueryEngine.class` from the final artifact; marker strings present:
  ```
  no preceding assistant declared this id : True
  answered a DIFFERENT assistant          : True
  no longer carries a tool_use for this id: True
  ```
- End-to-end live evidence (RAG session, default.jsonl, transcriptSize 73 → 118 over 90 seconds post-deploy):
  ```
  16:17:09 R736: 52 -> 48: 4 dropped (R716/R736), 4 dangling stripped
  16:17:21 R736: 45 -> 41: 4 dropped, 4 dangling stripped
  16:17:53 R736: 55 -> 51: 4 dropped, 4 dangling stripped
  16:18:00 R736: 60 -> 55: 5 dropped, 2 dangling stripped
  16:18:07 R736: 63 -> 58: 5 dropped, 2 dangling stripped
  16:18:13 R736: 63 -> 58: 5 dropped, 0 dangling stripped
  16:18:18 L4 head-trim continues; bash + file_read + grep running clean
  ```
  Every transcript pathology cycles through R736 and the LLM API
  keeps returning 200 / live stream content.

### 6.8 Knock-on: do we need an "engine-side cache clear" when the
        user re-runs the polluted session?

No. R736 is a **pure filter over the input list** that does not
mutate `appState.transcript()`. The polluted transcript stays on
disk; R736 trims it on every wire call. If we ever want a
self-healing session-writeback, the safe place is the same call
site that already recomputes `cleanedTranscript` at line 1799 — a
write of `cleaned` back via the existing Transcript serializer
once every K cycles, gated by "any tool_result dropped OR any
tool_use stripped since last flush".
