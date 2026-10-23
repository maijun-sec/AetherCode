# R372 — replay the session's transcript, so "continue" means continue

**Date:** 2026-10-24
**Scope:** `aethercode-tui/src`, TUI gates. No Java changed.

## The gap this closes

R370 made `/sessions` a real picker. Picking a session switched the
daemon's active transcript, and the next prompt genuinely continued
there — the TUI sends `query { prompt }` with no `sessionId`, so the
daemon's active session is the one that answers.

What the user got was an **empty scrollback** and the sentence
`history is not replayed here`.

That sentence was honest, and it was also the symptom. The reason
nothing could be replayed is that this renderer had no messages→turns
converter at all. The daemon had the data the whole time: `getTranscript`
(`AetherCodeMethods:7310`, routed on HTTP at `HttpJsonRpcServer:1018`)
answers `{ok, sessionId, messages}`, and nothing read it.

## The wire shapes

`Message.toMap()` (`core/message/Message.java`):

```
{ id, role, content: ContentBlock[], timestamp, metadata }
```

`ContentBlock` is a sealed interface serialised by Jackson's
`@JsonTypeInfo(Id.NAME, property="type")`:

```
{ type: "text",        text }
{ type: "tool_use",    id, name, input }
{ type: "tool_result", toolUseId, content, isError }
```

`Role` is `USER | ASSISTANT | SYSTEM | TOOL_RESULT`. A `tool_result`
lives in the message **after** the one carrying its `tool_use` (the
protocol's synthetic user message), so pairing takes two passes.

## `src/transcriptHydrate.ts`

A pure function, `replayTranscript(raw, startId, limit?)`, returning
`{turns, totalMessages, replayedMessages}`.

### The three things it refuses to invent

1. **Durations.** A replayed tool call has no start and no end.
   `toolStartedAt` stays `undefined` and `ToolCard.computeDuration`
   already renders `—` for that. A made-up duration is a fabricated
   measurement, which is exactly what R371's "no usage reported yet"
   refused to be.
2. **Results for calls that have none.** A `tool_use` with no matching
   `tool_result` is an interrupted run. It renders as
   `(no recorded result — the run was interrupted)`, never as an empty
   success.
3. **A complete history.** Long sessions hold thousands of messages;
   only the tail is replayed (200 by default) and the count left out is
   reported in the toast.

### Pairing, and the orphan rule

`tool_result` is matched to its `tool_use` **by id**. Two rules around
it, and the second one is the one a naive implementation gets wrong:

- an orphan `tool_result` (no matching call) produces **no turn**;
- and it does not **contaminate** the call before it. "Attach to the
  last turn" is the obvious implementation and it silently overwrites a
  real result with an orphan's.

The R736 lesson — the provider's contract is an *adjacency-preserving
batch*, not id-existence — applies to what we **send**. Here we are only
rendering what is already on disk, so id matching is both sufficient
and the only thing available.

## The switch and the replay land in ONE action

`adoptSession` gained an optional `turns`. It is one action rather
than two (clear, then fill) on purpose: two would leave a window in
which the scrollback is empty and the header names the new session, and
a user who types in that window is talking to a conversation they cannot
see. The replacement is atomic.

With no `turns` the behaviour is R370's — clear. That path is still
real: it is what happens when the daemon cannot give us a transcript,
and clearing is the honest answer there. The toast distinguishes the
two (`replayed N turns` vs `its history could not be read`).

Line mode gets the same replay, projected down to its
`LineTurn` (`role` + `text` + `ts`): a tool call becomes a
`[tool] name` line with its result.

## Two gate failures that were the gates being wrong

**R370's "the switch toast says what actually happened"** pinned a
SENTENCE — `history is not replayed here`. True then, false from R372
on. A test that names the wording of a message stops measuring the
message and starts measuring the wording. It now asserts the
**property**: the toast names the session the user is now in, and does
not claim something the code did not do.

**The mutation battery found a real hole**: deleting the failure-path
`adoptSession` left the gate green, because the only assertion was
"adoptSession appears in the block" and the *other* one satisfied it.
"Appears somewhere" and "appears on both paths" are different claims.
The gate now checks the catch path separately.

## Gate

`scripts/test/r372-transcript-replay.test.mjs` — 21 assertions, mostly
**behavioural** rather than source pins: a source pin cannot see "the
tool result became its own turn", only running it can.

`scripts/test/r372-mutate.mjs` — 25 cases, all caught.

Three of the mutations were **bad cases** rather than bad code, and
fixing them is half the value:

- "an orphan tool_result is rendered anyway" aimed at a transcript
  whose only message is the orphan — where `turns[turns.length - 1]` is
  `undefined` and the behaviour is unchanged, so it passed against
  broken code. It now aims at the case that can actually differ: an
  orphan **after** a real call.
- "the Anthropic content-block array is not read" added a guard on
  `Array.isArray(part)` — but `part` is an *element* of the array and
  never an array itself, so the guard could never fire. It now
  short-circuits the array branch where it actually is.
- "GATE: neuter the no-invented-duration check" neutered the
  `toolStartedAt` assertion and then introduced a defect only the
  `toolEndedAt` assertion could see — a single point of failure **by
  construction**, so the case proved nothing. It now aims at the orphan
  rule, which has two independent assertions.

Two mutations in the **R370** battery went NO-OP because R372 changed
the code they targeted (the reducer's shape and the toast's wording).
The no-op detector caught both, which is the reason it exists: a
mutation aimed at text that no longer exists measures nothing and looks
like coverage. One of them also turned out to test **R372's** behaviour
while running the **R370** gate — it was moved to the R372 battery,
where the gate can actually see it.

## Verification

| Check | Result |
|---|---|
| `npx tsc -p tsconfig.json --noEmit` | 0 errors |
| focused gates R362–R372 + T-441 + R196 (13 files) | **181 / 181** (was 158 at R371) |
| mutation batteries | r362 **4**, r363 **10**, r364 **12**, r365 **8**, r366 **25**, r369 **11**, r370 **27**, r371 **25**, r372 **25** — all caught |
| full `npm test` | see §Verification below |

## Follow-ups

1. `/workflow` and `/update` remain `not-wired` on Ink — real UI tasks
   with a working backend, unlike `/context`'s placeholder.
2. The 7 `BACKEND_MISSING` methods are honest but absent.
3. The replay drops everything before the last 200 messages. A
   "load the whole thing" affordance is a product decision, not a bug.
