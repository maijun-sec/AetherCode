# R267: clicking a historical session now shows its message history

## Trigger
After installing 0.2.67 (R266d + R266g + R266h), the user
clicked the 12h / 85 msg session in the left rail and saw... the
empty AetherCode welcome page. The left rail updated (the
project's "+", the active-session stripe, the right panel's
TaskSummary id all flipped to the historical session), but the
chat area stayed blank.

## Root cause
The `switchSession` action in `aethercode-desktop/src/store/index.ts`
performed the daemon round-trip BEFORE flipping `currentSessionId`:

```ts
// pre-fix order
try { await rpc.loadSession(sessionId); } catch { /* … */ }
const cached = await get().hydrateTranscript(sessionId);  // (1)
void cached;
set({
  currentSessionId: sessionId,  // (2) flipped too late
  isStreaming: false,
  currentInput: readDraft(sessionId),
});
```

Two guards downstream both check `currentSessionId` and bail when
it doesn't match the incoming sessionId:

- `hydrateTranscript` (line ~3876):
  `if (t.sessionId !== sessionId || t.sessionId !== get().currentSessionId)`
  — when the guard runs at (1), `currentSessionId` is still
  `OLD`, so `t.sessionId !== get().currentSessionId` is true
  and the function returns the OLD `messages` array (line
  3885: `return get().messages`).
- `rpc.on('transcript_event')` (line ~2556): `if (sid !==
  get().currentSessionId) return;` — the daemon's sync push
  arrives with `sid === NEW` while `get().currentSessionId`
  is still `OLD`, so the WS message is dropped on the floor.

The session "switched" (left rail + TaskSummary updated because
those read `currentSessionId` directly) but no transcript ever
landed in `state.messages`. The user saw the welcome page
because `messages.length === 0` triggers it.

## Fix
- `store/index.ts: switchSession` — flip `currentSessionId`,
  clear `messages: []`, and load the new draft synchronously
  BEFORE the daemon round-trip. The round-trip + WS push now
  see the matching `currentSessionId` and apply the transcript.
- `setCurrentSessionId` (a different code path used by the
  subagent view switch) was already correct — it committed
  `currentSessionId` and `messages: []` synchronously and only
  fired the async hydrate after, so no change needed there.

## Why `messages: []` on the synchronous commit
Without it, the OLD session's transcript would still be in
`state.messages` for the ~200ms between the sync commit and
the daemon's transcript push landing — a confusing "this
session is selected but the chat log is from the previous
session" flash. Clearing the array synchronously matches the
behaviour of `setCurrentSessionId` (which already does this)
and gives the user a one-frame empty state instead of a stale
one.

## Tests
- existing `store/transcriptMergeR179.test.ts` source-pin
  tests still pass (the merge contract is unchanged; the
  fix is purely about WHEN the apply happens, not WHAT
  the merge looks like).
- existing `store/sideNoteR208.test.ts`,
  `store/memoryR127.test.ts`, etc. all pass (1042/1042).
- new test in a follow-up round if the user wants a
  regression pin (R267 source-pin would be: "switch
  session A → B → A leaves messages matching A's
  transcript, not a stale snapshot of A or an empty
  array"). Skipped for now to keep this round focused on
  the order fix; a source-pin can be added if the same
  regression recurs.

## Verification
- `npm run typecheck` — exit 0
- `npm run test` — 82 files / 1042 tests pass, 0 fail
- desktop rebuild — TBD (3-5 min cargo build)

## Risk
low. The fix is purely an ordering change in
`switchSession`. The downstream guards
(`hydrateTranscript` line 3876, `transcript_event` line
2556) are unchanged and still protect against genuine
stale pushes from the previous session.
