# R284 — pre-compaction snapshot store + View original (2026-09-18)

## TL;DR

Every compaction now writes the pre-compact transcript to disk as
a per-session JSON file. The desktop MessageList renders a
dedicated row on compaction-summary messages with a "View original
(N msgs)" affordance that opens a modal showing the full
pre-compact context. The user can finally audit what the model saw
before summarising, and compare it with the summary that replaced
it.

---

## What changed

### SnapshotStore reshape

The previous `label` / UUID-id shape didn't match the UI's mental
model — the MessageList wants "snapshots for session X, ordered
by compaction event". R284 reshaped the store around
`sessionId` + monotonic `compactionIndex`:

- `Snapshot(sessionId, compactionIndex, createdAt,
  originalMessageCount, keptMessageCount, summary, messages)`
- `saveForSession(sessionId, originalCount, summary, messages)`
  — allocates the next index, persists
  `<sessionId>__<N>.json`
- `bySession(sessionId)` / `getByIndex(sessionId, index)` /
  `delete(sessionId, index)` / `listSessions()`
- `reload()` recovers the monotonic index per session from the
  filename even after a crash

`SnapshotStoreTest` (21 tests, all pass) covers the contract
including the crash-recovery edge case where the next save must
continue from the highest seen index rather than reuse a slot.

### QueryEngine integration

`runPreFlightCompact` now:
1. Calls `compactor.compact(messages)` to get the spliced
   summary message
2. Extracts the bare summary text from the spliced first message
   (strips the `[Conversation compacted — earlier turns replaced
   by the summary below]\n\n` prefix)
3. Calls `snapshotStore.saveForSession(currentSessionId, n,
   summary, originalMessages)`
4. Attaches `{kind: "compaction-summary", compactionIndex,
   snapshotPath, originalCount}` to the spliced summary
   message's metadata
5. Replaces the live transcript

`snapshotStore` is `volatile` so the daemon can hot-swap it; null
disables snapshotting (used by tests).

### Engine plumbing

`AetherCodeEngine` exposes:
- `Builder.snapshotStore(...)` (default null)
- `setSnapshotStore(...)` post-construction setter
- `snapshotStore()` accessor (used by the new RPCs)
- `loadSession()` and `createSession()` push the new
  `sessionId` into `QueryEngine.setSnapshotStore` so a session
  switch flips the snapshot attribution on the very next
  compact

The session id is the same string the daemon passes to
`appendMessage` (the engine's `appState.sessionId()`), so the
on-disk snapshot directory is shared across sessions but each
session gets its own monotonic index sequence.

### RPC + HTTP routing

- `compact/listSnapshots({sessionId?})` → `{ok, sessionId,
  snapshots: [{compactionIndex, originalMessageCount,
  keptMessageCount, createdAt, fileName, summary}]}`
- `compact/getSnapshot({sessionId?, compactionIndex})` → `{ok,
  snapshot: {…same fields…, messages: [...]}}` or
  `{ok: false, error: "not-found"}` for missing indices
- `HttpJsonRpcServer` routes both via the case-statement
  dispatch
- The default `sessionId` (when the renderer omits it) is the
  engine's `appState.sessionId()` — the desktop MessageList
  already knows this from the store, so the modal can omit it
  and the daemon still finds the right snapshot

`AetherCodeMethodsR284Test` (7 tests, all pass) covers the wire
contract.

### Desktop UI

- `ChatMessage.metadata?: Record<string, unknown>` — pass-through
  of the daemon's metadata map (shallow clone)
- `messageToChatMessage` reads `raw.metadata` so a
  `kind === "compaction-summary"` survives the
  transcript-event round-trip
- `MessageList` detects the new metadata kind and routes to a
  dedicated `CompactionSummaryMessage` row (NOT the legacy user
  bubble). The row carries:
  - the bare summary text (prefix stripped)
  - a "View original (N msgs)" button
  - the on-disk filename as a small monospace pill
- `SnapshotModal` is a centred card with a dark backdrop
  showing:
  - the summary the model saw (top section)
  - the original messages (one row each, role + body)
  - ESC + backdrop-click + × button all close
- `MockRpcServer` now accepts `seed.snapshots` so tests can
  populate per-session fixtures without a real daemon
- `SnapshotModalR284.test.tsx` (7 tests) +
  `MessageListR284.test.tsx` (3 tests) — total 10 R284 desktop
  tests, all pass
- Full desktop suite: 1147/1147 pass

---

## Tests

- `SnapshotStoreTest` — 21 tests (was 18; +3 for monotonic
  separate-sessions, separate-files-monotonic-after-crash,
  filename-convention)
- `QueryEnginePreFlightCompactR283Test` — 7 tests still pass
  (no regression on the R283 gate plumbing)
- `AetherCodeEngineR178Test` — 5 tests still pass
- `AetherCodeMethodsR284Test` — 7 new tests
- `SnapshotModalR284.test.tsx` — 7 new tests
- `MessageListR284.test.tsx` — 3 new tests

---

## Deploy

- **jar**: 56,620,814 B (R284, +4,579 vs R283)
- **exe**: 4,161,024 B (unchanged — R284 doesn't touch the
  Tauri shell)
- **zip**: SHA `BC0ACBC9871A62CA8F85C823631602D1210E3C9CA084AC42FD9568D9223B2F91`,
  112,354,407 B (+7,682 vs R283)
- **verify_jar_r277_fix.py**: 39/39 bytecode markers pass
  (3 R277 + 8 R280 + 5 R281 + 4 R282 + 11 R283 + 8 R284)

---

## Key decisions

1. **SnapshotStore is per-session via sessionId, not per-label
   via UUID** — the renderer thinks in sessions, and
   `compactionIndex` is monotonic per session (one compaction
   event = one snapshot file). The previous UUID-label shape
   couldn't answer "what was the N-th compaction on this
   session?" without scanning.
2. **Snapshot goes AFTER the compactor runs** — we wait for the
   summary text so the snapshot file carries both the original
   context AND the synthesis that replaced it. Auditing
   requires both, and the per-message `originalCount` field
   lets the UI show "this summary replaced N msgs" without a
   second round-trip.
3. **Summary message metadata is the bridge between
   engine → renderer** — instead of inventing a new wire
   channel, the engine tags the existing spliced summary
   message with `kind=compaction-summary` + `compactionIndex` +
   `snapshotPath` + `originalCount` in the same metadata map
   the daemon already emits. The renderer's MessageList picks
   it up on the standard transcript_event sync — no extra RPC
   for "which messages are summary markers".
4. **Snapshot is best-effort** — failures are logged at WARN
   and the turn proceeds without a snapshot. A disk full or a
   permission error on `<workspace>/.aethercode/snapshots/`
   must NOT break the user's turn.
5. **`saveForSession` reuses freed indices** is a deliberate
   non-decision — the file is `delete()`d but the next save
   still allocates `N+1`. Auditability ("compaction #0 of
   session X") wins over disk-space recovery; sessions don't
   get a million compactions anyway.
6. **`compact/getSnapshot` returns
   `{ok: false, error: "not-found"}` rather than throwing** —
   the renderer's modal shows a friendly "snapshot missing"
   pill rather than a red error page. The exception path is
   reserved for "store not wired at all" (daemon
   misconfiguration).

---

## Lessons (R284)

- **Tests must use `fixture.render`, not bare `render`** — bare
  render skips the harness that installs the mock
  `JsonRpcClient` into the `RpcProvider` context, so the
  modal's `useRpc()` falls back to the production singleton
  whose Tauri invoke fails in jsdom. The first 2 MessageList
  tests passed because they didn't trigger the RPC; the 3rd
  (which actually clicks "View original") failed until we
  switched to `fixture.render`.
- **Inverted-pair guards belong in the YAML entry-point
  record, not the runtime record** — `CompactSpec` validates
  `compactAt > contextWindow` up-front so a misconfigured
  providers.yaml fails at boot rather than at the first
  compact call. (`CompactConfig` also validates, as
  belt-and-suspenders, but the boot-time error is what the
  user sees first.)
- **`saveForSession` reuses freed index is not what we want** —
  the deleted slot stays in the per-session id list so the
  next save allocates `N+1`, never reuses `N`. Makes the
  audit trail linear and unambiguous.
- **`SummaryMessage` (pre-existing in CompactGate.spliceSummary)
  already tags metadata.kind=summary: true** — R284 layered a
  `kind=compaction-summary` on top because the two ideas are
  different (one marks "this message is a summary"; the other
  marks "this message is a snapshot pointer"). A future
  cleanup could merge them into one enum, but for now they
  serve distinct code paths.

---

## What's next (R285+)

- **R285**: model variants — claude-code-style
  low/medium/high/xhigh + opencode-style alias. `ModelSpec`
  gets `List<Variant> variants`, `switchProvider` accepts a
  variant parameter, `AETHERCODE_SUBAGENT_VARIANT` env var,
  agent.md frontmatter variant field, `/model y:y` syntax in
  the picker.
- **R286+**: Settings "Show all providers" toggle (R282
  follow-up), per-model pricing display, providers.yaml in-app
  editor, live env-var refresh button.
- **R287**: Tauri shell plugin driver for the SsdPanel — R281's
  MockSsdDriver stands in for now; real subprocess wiring is
  the next round.