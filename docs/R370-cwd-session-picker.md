# R370 — `/sessions`: this project's sessions, and a continuation that works

**Date:** 2026-10-23
**Scope:** `aethercode-tui/src` only. No Java changed. No desktop changed.

## The request

> add a `/sessions` command that shows the session list for the current cwd,
> and lets me pick one to keep working

## What was there

`/sessions` existed and did something, which is why nobody had filed it as
missing. It returned `{ rpcMethod: "listSessions" }`, and the host pretty-printed
the answer. So `/sessions` was the **raw wire dump** — `id`, `lastUsedAt`,
`sizeBytes`, `trashedAt`, `messageCount`, `preview`, one per line, unformatted,
with no way to select anything.

`/threads` had been a real picker since T-429 (`ThreadSelector.tsx`, 209 lines).
Two commands, two completely different levels of product, and the one with the
discoverable name (`sessions`) was the worse one.

Two further defects surfaced while wiring it up. Both were pre-existing and both
were invisible:

### 1. `line.ts` sent a key the daemon has never accepted

```ts
// line.ts, before
const res = await probe(ctx, "loadSession", { id: picked } as RpcValue);
```

`AetherCodeMethods.loadSession` (`aethercode-protocol/.../AetherCodeMethods.java:5945`)
opens with:

```java
String sessionId = stringOrThrow(p, "sessionId");
```

So every cmd.exe thread switch died on `-32602 invalid params` — **after** printing
the table and inviting the user to pick from it. Ink sent `{ sessionId }` all
along, which is why only the non-TTY path was broken and why this survived since
T-429.

This is the third instance of one mistake: a name copied instead of read off the
handler. R364 was `setCwd` (a Tauri IPC command used as a JSON-RPC method),
R366 was `workflow/list` (a `SupervisorRpcServer` method), R370 is `id` vs
`sessionId`. The gate now **derives** the key out of the Java source rather than
restating it — see "The gate" below.

### 2. `updatedAt` was structurally incapable of being non-empty

`normalizeThreadRow` read `r.updatedAt ?? r.lastActiveAt ?? ""`. `listSessions`
sends neither. It sends `lastUsedAt`, as **epoch millis** from
`SessionInfo.lastModified()`. So the field was `""` on every row, in every run,
forever — and `ThreadSelector` never rendered `updatedAt` at all, so the blank was
invisible rather than ugly. `line.ts:2110` had the identical dead reader
(`["updatedAt", "lastActiveAt", "ts"]`).

## The design: a flag, not a second panel

`/threads` lists everything and offers `switchProject` when the pick is from
another cwd. `/sessions` means "the sessions of THIS project". The two differ by
exactly one thing.

So there is no second `PanelId`, no second fetch, no second component, no second
`loadSession` call:

| | `/threads` | `/sessions` |
|---|---|---|
| sentinel | `__THREAD_SELECTOR__` | `__SESSION_PICKER__` |
| intent id | `thread-selector` | `session-picker` |
| `PANEL_BY_INTENT` | `"threads"` | `"threads"` |
| component | `ThreadSelector.tsx` | `ThreadSelector.tsx` |
| cwd-scoped | no | **yes** |

The single difference rides on `State.panelCwdOnly`, written by `openPanel` from
`CWD_SCOPED_INTENTS` and cleared by `closePanel`. One owner, so the flag cannot
disagree between the fetch and the heading.

### The comparison had to stop being string equality

`row.cwd === currentCwd` is wrong in four ways, each of which empties the list on a
real machine:

1. `D:\work\AetherCode` vs `d:\work\aethercode` — same directory on a
   case-insensitive filesystem.
2. `D:\work\AetherCode\` vs `D:\work\AetherCode` — one trailing separator.
3. `D:/work/AetherCode` vs `D:\work\AetherCode` — separator style.
4. A row with no `cwd` at all. `listSessions` **omits** the key when it cannot
   attribute a session (`AetherCodeMethods.java:5592` — `cwd` is only `put` when
   non-null, after SQLite → `.cwd` sidecar → shared memory store).

`src/cwdMatch.ts` owns the answer. One detail worth recording: stripping the
trailing separator off `C:\` yields `C:`, which Windows reads as *the current
directory on drive C* — a different path with a different meaning. The strip
therefore refuses to shorten a bare drive root.

`ThreadSelector`'s `cwd≠` badge and `chooseCwd` were on the same string
comparison, so a row differing only in case was labelled "will move your project"
and the host would then correctly decline to move it.

### Unattributed rows are counted, not dropped

For (4), `partitionByCwd` returns both halves. `/sessions` lists `mine` and says:

> **!** 4 of 21 sessions are recorded under AetherCode — the other 17 have no
> recorded directory. Use /threads to see everything.

A panel that reports "0 sessions" while `/threads` reports 21 reads as a broken
panel. The honest sentence reads as an answer.

### The continuation, and what it does not do

`loadSession` swaps the daemon's in-memory transcript, and the TUI sends
`query { prompt }` with **no** `sessionId` — so the next prompt genuinely
continues in the picked session. That part works.

What does not work is the visible scrollback. **There is no messages → `Turn`
converter in this renderer.** The desktop has one; the TUI's `init` action only
carries `sessionId` / `model` / `mode`. So after the switch:

- keeping the old turns would show session A's history while the model answers in
  session B — a lie with a UI on it;
- `adoptSession` therefore clears `turns`, `todos`, `viewingSubagentId`,
  `viewHistory` and the stop-reason, and takes the id off the **response** rather
  than assuming the request landed where it was aimed;
- the toast says what actually happened:
  `now working in <id> — the next prompt continues it (history is not replayed here)`.

**Known gap (not fixed in R370):** transcript hydration. `getTranscript` is
registered on `HttpJsonRpcServer:1018` and returns
`{ok, sessionId, messages: Message[]}`, but nothing in the TUI consumes it.
Wiring it means a `Message[]` → `Turn[]` converter (roles, tool_use/tool_result
cards, reasoning). That is its own round; it is recorded here rather than
half-built.

## Also fixed, same code path

- `ThreadSelector` was rendered with `title=""`, which drew a bare `◆ ` with
  nothing after it. With two commands on one panel the heading has to say which
  one is open. `/threads` → "All sessions"; `/sessions` →
  "Sessions in <leaf>" plus the absolute path as a subtitle (two checkouts can
  share a leaf name).
- The picker's zero-rows sentence is now a prop. `/sessions` empty because
  nothing is attributed here is a different fact from "the daemon has no
  sessions", and the host can tell those apart.
- `updatedAt` is rendered.

## The gate

`scripts/test/r370-session-picker.test.mjs` — 23 assertions.
`scripts/test/r370-mutate.mjs` — 28 cases, all caught.

Three things about it are worth keeping:

**The daemon's contract is read, not restated.** The gate extracts
`stringOrThrow(p, "…")` out of `AetherCodeMethods.loadSession`'s body (comment-
stripped, brace-matched) and asserts both renderers send *that* key. Change the
daemon and the gate goes red before either renderer does.

**Scope every extraction.** The first version scanned the whole file and took the
first `request("loadSession", …)` match — which is `viewPrimary`'s at
`tui.tsx:2398`, 600 lines before the picker. Reverting the *picker* to `{ id }`
went green. Same failure, sixth occurrence (R362's regex, R368's `matchAll`,
R1114's unreachable branch): an extraction that silently resolves to a different
instance of what it is looking for. Every bounded read here uses brace matching
(`block()`) or `matchAll` with an explicit floor.

**Bounded by structure, not by a guessed width.** The first draft located things
with `[\s\S]{0,400}?` windows. Two of the three were wrong, for reasons that had
nothing to do with the code under test. A guessed threshold fails by going green.
Lesson 1138, applied to a gate instead of a threshold.

### Gate mutations are redundancy proofs

The battery's `GATE:` cases edit the gate and pair it with a real product defect;
the case passes when **another** assertion still catches it. They cannot be
written as "edit the gate alone and expect red" — weakening a check and then
asking the weakened check to complain is a category error, which is why the first
draft reported three phantom holes.

One case was deleted rather than fixed: "neuter the renderers' params-key
assertion" reported a hole, and it was not one. That assertion is the only thing
in the file that asks *"do WE send the daemon's key"*; the Java cross-check asks
*"what does the daemon want"*. Demanding redundancy there is demanding a second
copy of the same question.

## Two test-design defects this round found

Both were in the gate, and both were found by the gate failing on **correct**
code:

1. **"Every panel must have a fetch branch."** False. `todo-board` legitimately
   has none — `TodoBoard` is fed by `todo_update` notifications off the event
   stream. The blanket claim failed on correct code, so it is now scoped to the
   panel under test.
2. **A bare `.exec` read the wrong instance.** The threads fetch dispatches
   `setThreads` twice — `threads: []` on the error path and the real one on
   success. The assertion took the first match, i.e. the error path. Now
   `matchAll` with `length >= 2` as the floor.

## Verification

| Check | Result |
|---|---|
| `npx tsc -p tsconfig.json --noEmit` | 0 errors |
| `r370-session-picker.test.mjs` | 23 / 23 |
| focused gates R362–R370 (9 files) | 126 / 126 |
| mutation batteries | r362 **4**, r363 **10**, r364 **12**, r365 **9**, r366 **25**, r369 **11**, r370 **28** — all caught |
| `aethercode-protocol` | 370 / 370 (unchanged; no Java edited) |
| full `npm test` | see §Verification below |

`javaCode` moved out of `r366-rpc-names.test.mjs` into
`scripts/test/java-strip.mjs` so the R370 gate reuses it instead of keeping a
second untested copy. The two r366 `javaCode` BYPASS mutations went NO-OP on the
day it moved — the same lesson as `BACKEND_MISSING`, second occurrence. They now
edit the new file.

## Follow-ups

1. **Transcript hydration** (the real gap): a `Message[]` → `Turn[]` converter
   fed by `getTranscript`, so picking a session shows where you left off. Until
   then the switch is honest but lossy.
2. `/context` is still a product decision: mount the existing 251-line
   `ContextMeter`, or delete the placeholder intent.
3. The 7 `BACKEND_MISSING` methods are honest but absent. R369 made them refuse;
   it did not make them work.