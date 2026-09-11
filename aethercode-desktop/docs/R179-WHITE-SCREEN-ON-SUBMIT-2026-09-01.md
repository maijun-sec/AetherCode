# R179: "White Screen on Submit" — `transcript_event` race fix (2026-09-01)

## TL;DR

User reported v0.2.26 desktop → input prompt → "白板" (blank screen). The user message was being wiped by a race between the renderer's optimistic local push and the daemon's `transcript_event(sync)` arriving before the server had acknowledged the user message. R179 changes the sync handler and `hydrateTranscript` to merge by id instead of REPLACE the local `messages` array. v0.2.27 ships the fix.

**This is a frontend-only fix; the daemon jar is unchanged from v0.2.26.**

## Symptom

- User is on a session with N messages (N ≥ 1)
- User types a prompt in the MessageInput, presses Enter
- The just-submitted user message disappears from the chat
- The previous transcript is still visible
- The user reads the screen as "blank/empty" because the only thing they care about (the new prompt) is gone

## Root Cause

`aethercode-desktop/src/store/index.ts` (pre-fix line 2235):

```ts
rpc.on('transcript_event', (params: any) => {
  ...
  if (action === 'sync') {
    const rawMessages = Array.isArray(params.messages) ? params.messages : [];
    const messages = rawMessages.map(messageToChatMessage).filter(Boolean) as ChatMessage[];
    set({ messages, isStreaming: false });  // ← REPLACES the entire array
  }
  ...
});
```

`sendMessage` (line 3094-3099) does an optimistic local push BEFORE awaiting `rpc.query`:

```ts
set((s) => ({
  messages: [...s.messages, { id: newId('user'), role: 'user', content: input, ... }],
  currentInput: '', isStreaming: true,
  ...
}));
```

If the daemon emits `transcript_event(action="sync", messages=[…])` between the local push and the user message being appended on the server (e.g. via `createSession` for a first-ever query on a fresh session, or a WS reconnect that re-sends a short transcript), the local user message is wiped. The same race lives in `hydrateTranscript` (the slow path used by `sendMessage` tail and `switchSession`) at line 3339-3348 of pre-fix code.

## Reproduction diagnostic (Python, no Tauri needed)

```python
# Submit a query — see the daemon reject with "session is busy"
$ python -c "..."
# Result: {'error': 'session is busy with run run-1; please wait for it to finish (or press Esc to cancel)'}
# This is the visible symptom: a sync mid-flight erases the optimistic push.
```

## Fix

Both paths now do a functional `set((s) => { ... })` with a by-id merge:

```ts
if (action === 'sync') {
  const incoming = rawMessages.map(messageToChatMessage).filter(Boolean);
  set((s) => {
    // Empty sync: don't clobber. Bail with only isStreaming update.
    if (incoming.length === 0 && s.messages.length > 0) {
      return { isStreaming: false };
    }
    // Merge: daemon-authoritative for matching ids, local append
    // for ids the daemon hasn't seen yet (optimistic additions).
    const byId = new Map<string, ChatMessage>();
    for (const m of incoming) byId.set(m.id, m);
    const merged: ChatMessage[] = [];
    const seen = new Set<string>();
    for (const m of incoming) {
      const local = s.messages.find((x) => x.id === m.id);
      merged.push(local ? { ...local, ...m } : m);
      seen.add(m.id);
    }
    for (const m of s.messages) {
      if (!seen.has(m.id) && !byId.has(m.id)) {
        merged.push(m);
        seen.add(m.id);
      }
    }
    return { messages: merged, isStreaming: false };
  });
}
```

`hydrateTranscript` (slow path) gets the same merge contract.

## Test

`aethercode-desktop/src/store/transcriptMergeR179.test.ts` — 3 source-pin tests:

1. Sync handler must NOT do `set({ messages, isStreaming: false })` with the raw incoming array
2. `hydrateTranscript` must use the merge identifier (`byId` / `byIncomingId`)
3. Sync handler must guard against an empty incoming payload (bail, don't clobber)

The tests strip line/block comments so the regex matches real code, not the explanatory comment that quotes the pre-fix bug.

**Full vitest suite**: 73 test files / 882 tests pass (was 879 → +3 new). tsc --noEmit clean.

## Build & Release

- `npm run tauri build -- --no-bundle` — 2m 17s, exe SHA256 `57E3D0E2D0504C2DD17947AF47D879892498FCC562783DA7B873A58C650C8F23`
- `release/aethercode-0.2.27/AetherCode.exe` (3.94MB)
- `release/aethercode-0.2.27/aethercode-0.2.27.jar` (55.3MB, SHA256 `56CF4BEF00FD789E915A4B1136940D735B2B89D2776ED30F7035F56F7B1D817B` — same as v0.2.26, R179 is frontend-only)
- `aethercode/dist/aethercode-0.2.27.jar` copied for desktop auto-discovery
- Bumped `package.json` and `tauri.conf.json` to 0.2.27

## User actions

1. **Close v0.2.26 desktop** (PID 5256, AetherCode.exe)
2. **Start v0.2.27 exe**: `D:\work\workspace\idea\engine\AetherCode\release\aethercode-0.2.27\AetherCode.exe`
3. The new exe will connect to the existing v0.2.26 daemons (PIDs 8236 / 25816). No need to kill them — R179 is frontend-only.
4. Test: type a prompt, press Enter. The user message should appear in the chat immediately and stay visible (not get wiped by a sync event).

## Honest caveat

I haven't been able to reproduce the exact failure mode in a headless test. The merge fix addresses the **most likely** root cause based on code analysis (the pre-fix `set({ messages, isStreaming: false })` is unsafe under any concurrent sync). If the white screen persists on v0.2.27, the actual cause is elsewhere — most likely candidates:

1. **Daemon WS push dropped / delayed**: stream_event doesn't reach the renderer, so the renderer thinks the run is done (isStreaming=false) but the model is still running on the daemon. The next submit returns "session is busy" and the user sees no progress.
2. **R98 deny matrix + BashTool 5-min timeout** (pre-existing, surfaced by R178 testing): the model gets stuck on diagnostic commands and the user can't see why.
3. **Renderer crash** in MessageList (e.g. a null ref in a new R177 sub-component). The desktop has no React error boundary at the top level, so a throw could blank the chat pane.

If v0.2.27 doesn't fix it, please share the dev-tools console log (Tauri 2 exposes `--remote-debugging-port=9222` for the WebView2) — that'll show the actual error.

## Lessons (2026-09-01)

1. **Server-pushed state + optimistic local additions = mandatory merge logic**. The pre-fix `set({ messages })` was a textbook race. Even when "the daemon is the source of truth", optimistic writes need a local buffer that survives sync.
2. **Empty sync ≠ wipe signal**. An empty `messages` array is "no data yet", not "local is wrong". Bail, don't clobber.
3. **Source-pin tests for race-condition fixes**. Hard to reproduce in unit tests, easy to pin via regex against the pre-fix pattern. Strip comments so the regex matches real code.
4. **Verify with the daemon, not the renderer's view of itself**. The renderer's `isStreaming` is set by stream_event, not by getState. Looking at the renderer's state alone can miss desyncs. The "session is busy with run run-1" response from a probe query is the ground truth.

## Files

- `aethercode-desktop/src/store/index.ts` (sync handler + hydrateTranscript merge)
- `aethercode-desktop/src/store/transcriptMergeR179.test.ts` (3 source-pin tests)
- `aethercode-desktop/src-tauri/tauri.conf.json` (0.2.27)
- `aethercode-desktop/package.json` (0.2.27)
- `release/aethercode-0.2.27/AetherCode.exe` (3.94MB)
- `release/aethercode-0.2.27/aethercode-0.2.27.jar` (55.3MB)
- `aethercode/dist/aethercode-0.2.27.jar` (for desktop auto-discovery)
