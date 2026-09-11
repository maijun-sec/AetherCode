# R176-D — Stuck "等待模型响应…" Placeholder Fix (2026-09-01)

**Status**: shipped v0.2.24
**Build**: `release/aethercode-0.2.24/AetherCode.exe` (3.76 MB, SHA256
`A718F4DB421777CD1AC6A1089E4223DC6CE4362F58725AE6DB4A7E9C966DAB79`)
**Daemons**: unchanged — jar replicated as `aethercode-0.2.24.jar` (55.3 MB)
**Scope**: frontend-only

## What the user reported

After v0.2.23 the user opened the app and saw:
1. The same `c3766213` session they'd used yesterday (the `java maven
   5 sort algorithms` prompt was still in the chat).
2. A "等待模型响应…" text under the YOU message that looked like the
   model was still running — but the header showed green "Idle" and
   nothing was happening.

Two questions:
- Why is this the same session every time I open the app?
- Why is it stuck on "waiting"?

## Question 1: why the same session

**By design.** The daemon is the source of truth for the active
session. `listSessions` returns the active session, `getState`
returns its `sessionId`, and the renderer restores it on launch.
The user's "Maven 5 sorts" session is the only session they've
created, so `currentSessionId` is always `c3766213-e9ec-…`.

The "stuck waiting" was a separate bug.

## Question 2: why "stuck waiting"

**R176-D: the placeholder didn't gate on `isStreaming`.**

`aethercode-desktop/src/components/MessageList.tsx`:

```jsx
{subTasks.length === 0 && preambleSteps.length === 0 && (
  <div className="message-list-empty-steps">等待模型响应…</div>
)}
```

The intent of this text was: "the user just sent a message, the
model is now thinking, no sub-task has been created yet" — a
transient placeholder. The condition was right for a *fresh*
query, but it also fired on a **session restore** (e.g. a user
opens the app after a daemon restart, or switches to a session
that finished yesterday). The user's message is in `messages` but
the sub-task structure isn't restored from the transcript — so
the placeholder showed even though no query was in flight.

The user saw the text and reasonably concluded the model was
working. But the daemon was actually idle.

Verified via `getState` RPC at `localhost:17888/jsonrpc`:

```
{
  "model": "MiniMax-M3",
  "sessionId": "c3766213-e9ec-4db2-9f7f-7d1f1e3af881",
  "isStreaming": null
}
```

`isStreaming` is `null` — the model is NOT in flight. The
"等待模型响应…" text was purely a renderer bug.

The previous run from yesterday actually completed cleanly:
`getMetrics` reports `turnsStarted=1, turnsCompleted=1` and 81
messages in the transcript (user, assistant, tool_result). The
chat is just missing the rendered "done" view because the
sub-task structure isn't restored from the transcript.

**Fix**:

```jsx
{isStreaming && subTasks.length === 0 && preambleSteps.length === 0 && (
  <div className="message-list-empty-steps">等待模型响应…</div>
)}
```

The `isStreaming` gate makes the placeholder a true transient
indicator. After session restore, the user sees:
- The YOU message bubble from the previous run
- (no "等待模型响应…" text)
- A clear input box ready for a new message

The previous run's assistant messages are still in the
transcript (`getTranscript` returns 81 entries) but aren't
re-rendered as sub-task cards — that's a separate design
choice (the chat is for the current/last run; full history
lives in the transcript view).

## Tests

`aethercode-desktop/src/components/messageListEmptyStepsR176.test.ts`
(2 tests, source-pin):

- Asserts the placeholder is wrapped in
  `isStreaming && subTasks.length === 0 && preambleSteps.length === 0`
  with the literal "等待模型响应…" string.
- Asserts there's no top-level
  `subTasks.length === 0 && preambleSteps.length === 0 && …等待模型响应…`
  block (regression guard for the pre-fix pattern).

| Suite | Before R176-D | After R176-D | Δ |
| --- | --- | --- | --- |
| `messageListEmptyStepsR176.test.ts` | — | 2 | +2 |
| **Full vitest** | **867** | **869** | **+2** |

`tsc --noEmit` clean. `npm run tauri build --no-bundle` produces
`AetherCode.exe` (3.76 MB).

## What the user sees on v0.2.24

1. The same `c3766213` session loads (by design — daemon's
   active session).
2. The YOU message from yesterday is in the chat.
3. **No** "等待模型响应…" text (because `isStreaming` is
   `null`).
4. The header shows green "Idle" (correct — no run in
   flight).
5. The input box is ready. The user types a new message and
   the daemon responds normally (verified: `getState`
   confirms daemon alive on port 17888 with 81-message
   transcript intact).

## Files modified

- `aethercode-desktop/src/components/MessageList.tsx` —
  placeholder gated on `isStreaming`
- `aethercode-desktop/src/components/messageListEmptyStepsR176.test.ts`
  (new, 2.9 KB, 2 tests)
- `release/aethercode-0.2.24/AetherCode.exe` (new, 3.76 MB)
- `release/aethercode-0.2.24/aethercode-0.2.24.jar` (new,
  copy of v0.2.23 jar — R176-D is frontend-only)
- `aethercode/dist/aethercode-0.2.24.jar` (new) — desktop
  auto-spawn now finds v0.2.24 daemon

## Lessons

1. **"Active" UI state should always gate on the run-state
   flag, not just on data shape.** The placeholder was
   supposed to show "the model is currently thinking" but
   actually fired on "no sub-tasks yet" — a structural
   condition that holds for both "thinking" and "session
   restore after run completion". Gating on `isStreaming`
   makes the placeholder semantics explicit: "the engine
   is currently running a query".

2. **Session restore is a different code path from new
   query.** `hydrateTranscript` resets `isStreaming=false`
   but the MessageList render assumed any "empty of
   sub-tasks" state was "thinking". The fix is one
   keyword; the test prevents regressions.

3. **Always sanity-check daemon state when the UI looks
   stuck.** `getState.isStreaming=null` immediately
   identified the problem as renderer state, not engine
   state. The user's intuition ("the model is hung") was
   wrong; the actual situation was a stale UI
   placeholder. Without the RPC check we'd be guessing.
