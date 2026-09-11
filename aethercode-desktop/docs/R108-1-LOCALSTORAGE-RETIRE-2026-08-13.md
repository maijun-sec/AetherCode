# R108-1: localStorage cache 退役 — daemon 主动推 transcript

**Date:** 2026-08-13
**Status:** SHIPPED
**Impact:** R105-B fallback retired; the daemon's `appState.transcript` is now the single source of truth for the chat log.

## 背景 (Context)

R105-B added a per-session localStorage cache (`aethercode-session:<id>`) so a
Tauri reload or a session switch restored the messages the user was reading.
The reason was that the daemon's `loadSession` was a stub at the time — the
engine's in-memory transcript was the only live view, and a reload would lose
it. R106 made `loadSession` real (SessionStore on disk, `appState.transcript`
populated), but the localStorage layer stayed as a fallback "in case the
daemon is on a different session".

R108-1 retires the fallback. The daemon's `appState.transcript` is now the
source of truth; the renderer hydrates from the daemon (via `getTranscript`)
and follows live updates via `transcript_event`. No more double-write to
localStorage on every message; no more risk of the local copy drifting from
the on-disk JSONL.

This is the second of two pieces of architectural debt R105-B introduced —
the first (R106's `SessionStore`) already shipped. R108-1 is the clean-up.

## 设计 (Design)

### Wire format

`Message.toMap()` produces a JSON-friendly `LinkedHashMap<String, Object>` for
the renderer:

```json
{
  "id": "uuid",
  "role": "user",   // lowercase
  "content": [
    {"type": "text", "text": "..."},
    {"type": "tool_use", "id": "tu-1", "name": "bash", "input": {...}}
  ],
  "timestamp": 1234567890,    // epoch ms
  "metadata": {}
}
```

The `type` discriminator on each content block is preserved by converting the
`List<ContentBlock>` through `ObjectMapper.convertValue(b, Map<String, Object>)`
— the same trick `Transcript.serialize` uses. Without the conversion, Jackson
would emit a list of raw record values with no `type` field, and the
renderer's `messageToChatMessage` would silently drop the block.

### Two notification shapes

The daemon broadcasts via `transcript_event`:

| Action    | Shape                                  | When                              |
|-----------|----------------------------------------|-----------------------------------|
| `append`  | `{action, sessionId, message}`         | Every `appendMessage` call        |
| `sync`    | `{action, sessionId, messages[]}`      | After `loadSession` / `createSession` |

The renderer's `transcript_event` subscriber:
- `append`: **IGNORED** during live streaming (the stream events are
  already building `messages` locally; accepting the daemon's push would
  duplicate). The `id` would never match anyway — the renderer's local ids
  are `assistant-1234` / `tool-…` / `user-…`, the daemon's are UUIDs.
- `sync`: **REPLACES** `messages` (with a sessionId guard so a late sync
  from a previous session can't wipe the new one).

For session switches, the renderer calls `getTranscript` explicitly so the
first paint is correct even if the WS push is delayed.

### Why "ignore append" is the right call

The local `messages` array drives the live UI (user msg bubble, system notes,
`LegacyMessage` component). Stream events build it up incrementally; replacing
it on a daemon push would cause visible flicker every time the daemon's
`appendMessage` fires (every user msg, every assistant turn, every tool
result).

The daemon's `appState.transcript` is the LOG; the renderer's `messages` is
the LIVE VIEW. They agree on content because the same `text_delta` events
that drive the local `messages` also drive the engine's `Message` content.
They differ on id, but id is only used for React key / dedupe — not for
correctness.

For OFFLINE display (session reload), the renderer reads the daemon's
canonical transcript via `getTranscript` and uses that as the new
`messages` array. The `id`s on those entries are the daemon's UUIDs, which
are stable across reloads.

## 改动 (Changes)

### Backend (Java)

- `aethercode-core/src/main/java/org/aethercode/core/message/Message.java`:
  new `toMap()` method. Returns a `LinkedHashMap<String, Object>` with the
  shape above; converts each `ContentBlock` through `ObjectMapper.convertValue`
  to preserve the `type` discriminator.
- `aethercode-sdk/src/main/java/org/aethercode/sdk/AetherCodeEngine.java`:
  new `transcriptPush` field + `setTranscriptPush` setter. The `onMessageAppend`
  listener (installed by `attachSessionStore`) now fires BOTH the on-disk
  write (R106) AND the push. `loadSession` and `createSession` push a `sync`
  event after replacing the in-memory transcript.
- `aethercode-protocol/src/main/java/org/aethercode/protocol/http/HttpJsonRpcServer.java`:
  new `broadcast(String, Object)` public method (wraps the existing private
  `broadcastNotifier`). New `getTranscript` case in the dispatch switch + new
  entry in the `/api/methods` well-known list.
- `aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`:
  new `getTranscript` RPC. Returns `{ok, sessionId, messages[]}` for the
  engine's current session.
- `aethercode-cli/src/main/java/org/aethercode/cli/DaemonRunner.java`:
  `runHttp` now wires `engine.setTranscriptPush(payload -> http.broadcast("transcript_event", payload))`.

### Frontend (TypeScript)

- `aethercode-desktop/src/lib/methods.ts`:
  new `getTranscript()` typed wrapper. The return type is the same shape the
  daemon's `transcript_event` (action "sync") pushes, so the same adapter
  handles both paths.
- `aethercode-desktop/src/store/index.ts`:
  - new `transcript_event` subscriber (handles `sync`, ignores `append`).
  - new `messageToChatMessage` adapter at module scope.
  - new `hydrateTranscript(sessionId)` action — calls `getTranscript`, sets
    `messages`, guards against stale responses (a slow response from a
    previous session can't pollute the new one).
  - `initialize` now sets `messages: []` then awaits `hydrateTranscript` for
    the current session.
  - `switchSession` / `setCurrentSessionId` / `createNewSession` all call
    `hydrateTranscript` instead of `readSessionMessages`.
  - `refreshSessions` no longer merges with localStorage; the daemon's
    `listSessions` is authoritative.
  - `deleteSession` no longer drops a `aethercode-session:<id>` localStorage
    key (the migration handles it).
  - R105-B's `readSessionMessages` / `writeSessionMessages` /
    `listPersistedSessions` / `mergeWithPersisted` / `listChanged` removed.
  - The R105-B `useStore.subscribe` debounced-writer to localStorage removed.
  - new one-time migration `migrateLegacyLocalStorage()`: on the first
    launch of R108-1+, drops every `aethercode-session:*` key (caches the
    fact via `aethercode-r108-migration-v1` flag in localStorage).

### Tests

- `aethercode-core/src/test/java/org/aethercode/core/message/MessageTest.java`:
  +3 tests covering `Message.toMap()` — lowercase role, epoch ms timestamp,
  Jackson wire shape (verifies the `type` discriminator survives), and
  tool_use block preservation.
- `aethercode-core/src/test/java/org/aethercode/core/app/AppStateListenersTest.java`:
  new file, 4 tests covering the `onMessageAppend` listener contract that
  the engine's `transcriptPush` wrapper relies on — single-fire, ordered
  fan-out, throwing listener doesn't break peers, payload shape.

## 验证 (Validation)

- aethercode-core: 25/25 tests pass (3 new MessageTest + 4 new
  AppStateListenersTest + 18 pre-existing).
- aethercode-protocol: 5/5 HttpJsonRpcServerTest pass.
- aethercode-sdk / aethercode-cli: compile clean.
- aethercode-desktop: `npm run build` clean (471.22 KB JS / 77.07 KB CSS,
  same envelope as R107).
- `tsc -b --noEmit`: clean.

## 风险 (Risks) / 已知限制 (Known limitations)

- **Live-streaming dedup**: the renderer relies on stream events for the
  live UI; `transcript_event` is for offline / sync. If a future R-round
  changes the live-streaming code path (e.g. dropping `stream_event` in
  favour of `transcript_event` only), the renderer would need to dedupe
  by `id` — currently the IDs are different (renderer uses synthetic
  `assistant-1234`, daemon uses UUIDs), so a switch would be a one-line
  id-based filter, not a rewrite.
- **Missed events on WS reconnect**: the renderer doesn't currently call
  `getTranscript` on a `daemon.disconnected` → reconnect cycle. A future
  R-round could add this in the reconnect handler so a session that was
  mid-stream at disconnect time doesn't lose any append events. For now,
  the worst case is a missing trailing assistant message after a
  particularly bad network blip — the user can scroll up to see the last
  persisted message.
- **Tool message mapping**: the daemon's `Role.TOOL_RESULT` becomes a
  `role: 'tool'` ChatMessage in the renderer's `messageToChatMessage`
  adapter. The live UI's `tool_use_start` handler ALSO adds `role: 'tool'`
  messages. So a reloaded session shows a tool message for the result,
  but the live UI shows a tool message for the call. They're not the
  same event. This is a known wart; a future R-round could unify them
  (e.g. by having the renderer's tool_use_start handler consume the
  matching tool_result block from the transcript).

## 下一步 (Next)

R108-2: 子 session 事件流到 workflow sink (嵌套进度条). The child session
events are the next layer of the transcript-push pattern — instead of
one transcript per session, the engine emits per-child-session events
that the workflow sink fans out as nested progress UI.
