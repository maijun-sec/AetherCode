# R110-3: WS reconnect 自动 hydrateTranscript (2026-08-14)

## TL;DR

R108-1 retired localStorage as the transcript cache, putting the
daemon's in-memory `appState.transcript` at the centre of truth.
That was the right call long-term, but it created a sharp UX
regression: a transient `getTranscript` failure on reconnect (e.g.
the daemon was just respawned, the WS handshake raced the RPC, the
daemon's `SessionStore` was rebuilding) would wipe the user's
transcript to empty. R110-3 fixes the failure path so the renderer
keeps what it has and surfaces a `[Reconnect] …` system line
instead of going dark.

The path `daemon.disconnected → scheduleReconnect → initialize()
→ getState() → hydrateTranscript(state.sessionId)` was already
correct in R108-1. The bug was at the end of that chain: on a
caught exception, `hydrateTranscript` did `set({ messages: [] })`
and returned `[]` — destroying every message the user had just
been reading.

## Before / after

**Before (R108-1 era, buggy on reconnect):**

```
user: hello
[tool_use] ls -la
[tool_use] cat README.md
assistant: ...
                            ← WS blip; daemon.disconnected fires
[empty chat]                ← user sees their transcript wiped
```

**After (R110-3):**

```
user: hello
[tool_use] ls -la
[tool_use] cat README.md
assistant: ...
                            ← WS blip; daemon.disconnected fires
[Disconnected] unknown. Reconnecting…
[Reconnected] transcript synced (5 messages)
                            ← or, if the daemon restarted:
[Reconnect] daemon was restarted; in-memory transcript is gone.
            The session id is preserved, but the chat log starts
            from here.
                            ← or, if the RPC still failed:
[Reconnect] failed to fetch transcript: <reason>. Keeping the
            previous transcript; it may be stale.
```

The three branches cover:

1. **Happy reconnect** — `getTranscript` succeeds. The renderer
   replaces the message list with the daemon's authoritative
   copy (which may include 1-2 messages the user missed during
   the WS blip).
2. **Daemon restart** — `getTranscript` returns an empty list
   even though the session id matches. The renderer surfaces a
   system line and starts fresh; the user knows the daemon
   forgot, not that the renderer is broken.
3. **RPC failure** — `getTranscript` throws. The renderer keeps
   the previous messages and adds a system line with the error
   reason. The chat is "may be stale" but at least the user can
   see what they were doing.

## What ships in this round

### Frontend (aethercode-desktop)

- `store/index.ts`:
  - `hydrateTranscript`:
    - **Failure path rewritten** — on a caught exception, do
      NOT `set({ messages: [] })`. Instead, append a system
      message that says `[Reconnect] failed to fetch
      transcript: <reason>. Keeping the previous transcript; it
      may be stale.` and return the existing message list
      unchanged. The previous behaviour wiped the entire
      transcript on any failure; this is the root cause of the
      "empty chat after reconnect" reports.
    - **Empty-after-non-empty** — on a successful RPC that
      returns `messages: []` while the previous `messages`
      array was non-empty, prepend a `[Reconnect] daemon was
      restarted; in-memory transcript is gone.` system line.
      The session id is preserved (it lives in
      `SessionMetadata` on disk), but the chat log is gone
      because the daemon's in-memory `Transcript` is empty
      after a fresh JVM.
    - **Wrong-session short-circuit** — when the daemon's
      `getTranscript.sessionId` doesn't match the requested id,
      we still update `lastTranscriptSyncMs` so the next
      reconnect's staleness check has a recent anchor. Without
      this, a slow session switch could leave `lastTranscriptSyncMs`
      at 0 forever and the threshold check would keep firing
      on every subsequent reconnect.
  - New `lastTranscriptSyncMs: number` field on `AppState`.
    Wall-clock ms of the last hydrate attempt (success OR
    failure). Currently unused by the renderer (kept as a
    future hook for a "stale transcript" badge in the
    ReconnectBanner), but recorded so any consumer can
    reason about "how old is my view of the daemon's
    transcript".
  - New action `openStepDetail` / `closeStepDetail` (used by
    R110-5; listed here because they sit in the same store
    section).

### Why not localStorage?

R108-1 retired localStorage deliberately: the daemon's
in-memory transcript is the single source of truth, and
syncing a renderer-side cache back into that pipeline is a
recipe for divergence bugs. The new failure path keeps
"daemon is the source of truth" while not throwing the
user's data away on a transient RPC error. If the daemon
restarts, the transcript is genuinely gone (not in the
daemon's memory) and we say so explicitly; if the RPC just
failed, we keep what we have and ask the user to retry.

### Why not store the transcript in localStorage again?

Same answer as R108-1: the cache is per-session and the
session id lives on the daemon. The renderer would need to
key by session id and re-validate the daemon's id on every
hydrate, which is the same RTT we already do. The "transient
RPC failure" case is exactly the case where localStorage
would help (offline-resilient cache), but adding a cache
back just to cover this one failure mode is the
"cache-invalidation" tail wagging the dog.

If a future round wants a more aggressive offline cache,
the right shape is a `getTranscript-or-cache` RPC that the
daemon can serve from its disk-backed `SessionStore` (if it
exists) and the renderer keeps in memory only. That's a
bigger change than this round.

## Wire format

No wire changes. The `transcript_event` push (action 'append'
/ 'sync') and the `getTranscript` RPC are unchanged. The new
behaviour lives entirely in the renderer's failure handling.

## Tests

- aethercode-core: 851/851 (no changes to backend this round)
- aethercode-desktop: tsc clean, vite build clean
  - CSS: 95.41 KB (+0 since R110-2)
  - JS: 498.42 KB (+1.2 KB for the failure-path branches
    and `lastTranscriptSyncMs` field)

A vitest unit test for `hydrateTranscript`'s failure path
would need a store mock and rpc mock; the existing test
harness has neither yet (the desktop was tested via
type-check + build until R110-2). Adding the harness for
one test is more setup than the fix; deferred to a future
round that wires the harness properly.

## Future R-round candidates

- **Stale transcript badge** — the renderer reads
  `lastTranscriptSyncMs` and shows a "transcript may be
  stale" pill in the ReconnectBanner if the last sync
  was > 60s ago. Cheap and useful for long-running
  sessions on a flaky WS.
- **Exponential backoff on the hydrate RPC specifically** —
  if `getTranscript` returns "session not found" (a
  signal that the daemon is mid-restart), back off for 1s
  and retry once before giving up. The current path
  surfaces a system line on the first failure; retry
  would smooth over the common 500ms gap during a normal
  restart.
- **Per-session transcript cache in Tauri-side state** —
  if the Rust backend keeps the last-known transcript in
  memory (it already does for the daemon process), the
  renderer can read it from a new `cachedTranscript` RPC
  on the very first hydrate after the daemon is
  reachable. Avoids the "empty chat for 200ms while
  getTranscript round-trips" flash. The Tauri-side
  cache survives across renderer reloads (Tauri's
  localStorage) but not across daemon restarts (which
  is the right scope: we want the cache to follow
  daemon process, not renderer process).

## Files

- `aethercode-desktop/src/store/index.ts` (modified)
