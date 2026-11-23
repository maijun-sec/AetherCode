# R390 — the session's name must be the user's words, and it must be a fact

> **The symptom the user reported**: while a task was running, the left rail's
> project name / session name / session list were all wrong; and after a LONG
> task finished, the session still showed no name at all.

---

## 1. What was actually wrong

Five separate defects, each of which alone is enough to produce "the session
has no name". They were found by reading the wire against the running daemon
(`http://127.0.0.1:22888`) and the SQLite index it reads from — not by reading
the renderer and guessing.

### 1.1 The desktop read a field the daemon never sent

`AetherCodeMethods.listSessions` has always written `title` to the wire, and
the TUI has always read `title` (`normalizeThreadRow` in `aethercode-tui/src/tui.tsx`).
The desktop's `SessionInfo` declared `name?: string` — a field that has never
existed on the wire.

| reader | read | result |
|---|---|---|
| `components/SessionList.tsx` `sessionLabel()` | `s.name` | always `undefined` → fell through to `preview` |
| `components/TaskSummary.tsx` | `currentSession?.name` | always `undefined` → the "name" pill NEVER rendered |
| `components/CommandPalette.tsx` | `s.name?.trim()` | always `undefined` → `Session <id-tail>` |
| `components/SessionPickerModal.tsx` | `s.name` | always `undefined` |
| `components/Welcome.tsx` | `s.name?.trim()` | always `undefined` |

And the other direction: `components/session/SessionListRow.tsx` and
`components/session/TrashRow.tsx` DO read `s.title` — but those components are
**not on the LeftPanel's render path**. The path is

```
LeftPanel → ProjectGroupList → ProjectGroup → SessionList
```

so fixing `SessionListRow` alone would have changed nothing the user can see.

### 1.2 The title was re-derived from the transcript, and the transcript's first user-role line is often the ENGINE's

`listSessions` did `m.put("title", preview)` — and `preview` came from
`extractSessionPreview()`, which scans for the FIRST `"role":"user"` line.

The engine writes user-role messages of its own:

* `kind=session-anchor` — the R719 anchor,
* `kind=post-compact-context` — the "[PostCompact re-injection]" block,
* the `<system-directive type="todo-continuation">` prompt — **no kind at all**,
* the "[Resuming after user decision]" note — **no kind at all**.

Measured on the live session `de8d1067…` (a compacted one):

```
line 0 = assistant summary
line 1 = "[PostCompact re-injection] The transcript was just compacted. …"
→ the session's name was  "[PostCompact re-injection] The transcript was just compacted…"
```

The original user prompt was not merely deprioritised — it was gone
(`grep` for it in that transcript: 0 hits).

### 1.3 The persisted title and first prompt were being DELETED

`SessionStoreSqlite.upsert` wrote the proposed row's value over the stored one,
unconditionally:

```sql
first_prompt = excluded.first_prompt,
title        = excluded.title,
```

And every caller of `SessionStore.touch` passes `null` for the fields it does
not own — the engine's per-message append path passes
`firstPrompt=null, title=null, worktree=null, model=null`. So **the first
message append wiped the session's first prompt and its title.**

The title was not slow to appear. It was being deleted on every message.

Measured on the live `sessions.db`: 4 of 6 rows had `cwd=NULL`,
`first_prompt=NULL`, `title=NULL`, `message_count=0`.

### 1.4 A new session never got a cwd

The desktop's `createNewSession` (R361) goes through
`rpc.createEngine({sessionId, cwd, worktree, model})`. `createEngine` builds an
engine and nothing else — it does not write a cwd, and it does not write a
first prompt. The SDK's own `createSession(cwd)` DID do an eager
`sessionStore.touch(newId, cwd, …)` — so the eager write was the established
behaviour on one path and the desktop's path was the odd one out.

Result: a brand-new session had no row at all until its first message, and
when it appeared it had no cwd — so it landed in the "未关联项目" bucket
instead of under its project.

### 1.5 Nothing re-read the session list when a run ended

`store/index.ts`'s `case 'run_end'` had no `refreshSessions()` call.
`refreshSessions()` was reachable only from `setCwd`, `switchProject`, the
trash fallbacks, and `SessionList`'s own `useEffect` — which **returns early
when `propItems` is set**, and the LeftPanel always sets it. So the effect
never fired.

The tail of `sendMessage` did call `rpc.listSessions()` — but with a wholesale
`set({ sessions: list })`, replacing the merged list.

### 1.6 A side finding: the list got slower as the session got longer

`extractLastAgentEvent` called `Files.readAllLines(info.file())` — the WHOLE
transcript, as a `List<String>`. Measured on the live daemon, same daemon,
same data:

| call | latency |
|---|---|
| `listSessions` `withPreview=true` | **297–455 ms** |
| `listSessions` `withPreview=false` | 24–30 ms |

on a 16 MB transcript. The cost was proportional to the session's AGE, for a
value that lives in the last few lines.

---

## 2. The fix

### 2.1 One truth for the title — and it is a persisted fact

The daemon now resolves a session's title in a fixed order:

1. **the user's own rename** (`session/rename` → `SessionStoreSqlite.setTitle`)
2. **the prompt the session started from** (`first_prompt`, captured by the
   engine when the first USER-AUTHORED message is appended)
3. **the transcript scan** — a fallback, for rows written before
   `first_prompt` was persisted

The transcript is now the LAST resort, not the first.

### 2.2 The store must not let a NULL destroy a persisted value

`upsert` now resolves the two families in OPPOSITE directions, and the
difference is not arbitrary — it follows from **who writes the column**:

```sql
cwd         = COALESCE(excluded.cwd,         cwd)          -- last non-null wins
worktree    = COALESCE(excluded.worktree,    worktree)
model       = COALESCE(excluded.model,       model)
title       = COALESCE(excluded.title,       title)
first_prompt= COALESCE(first_prompt,         excluded.first_prompt)  -- FIRST non-null wins
```

* `cwd` / `worktree` / `model` / `title` are set **explicitly** by a caller
  that knows the new value (`setCwd()` replaces the cwd; a rename replaces
  the title) ⇒ the LAST non-null value wins, and a `null` — which means
  "this caller does not know" — keeps what is stored.
* `first_prompt` is FILLED ONCE: the engine offers **every** user message
  as a candidate (it cannot know which one was first), so the winner has to
  be decided by the store ⇒ the FIRST non-blank value wins, forever.

Resolving `first_prompt` in SQL rather than in the caller makes it atomic:
two concurrent appends cannot both win the "first prompt" slot.

★ The same rule is applied twice, on purpose: in `SessionStore.touch` (which
keeps the stored value when the caller passes null and adopts a
`firstPrompt` candidate only into an empty slot) AND in the SQL. The Java
half makes the intent readable; the SQL half makes it race-free. ★

### 2.3 One rule for "did the user write this?"

`org.aethercode.core.transcript.UserAuthoredText` owns the rule, and BOTH
callers use it:

* the engine (SDK) — when it offers a message as the session's first prompt;
* the daemon (protocol) — when it scans a raw JSONL line for the preview.

A user-role message is USER-AUTHORED iff it carries **no `kind` metadata**
(the user never sets a kind) **and** its text does not start with one of the
engine's markers (`<system-directive`, `[Resuming after user decision]`,
`[R719 session-anchor]`, `[PostCompact re-injection]`).

★ This is deliberately NOT `CodeCheckpointStore.isRealUserTurn`. ★ That method
asks a different question — "may a rewind land here?" — and errs the other way:
an **unknown** kind still counts as a turn, because dropping a turn shifts every
rewind target after it. This rule errs towards "machine-written", because
showing a machine message as the session's name is a lie the user cannot act
on. Two questions, two conservative directions, documented side by side.

### 2.4 The engine persists what it knows

`AetherCodeEngine.attachSessionStore()` now writes the session row **at attach
time**, with the engine's cwd. And the per-message append listener offers the
message as the session's first prompt — but only when it is user-authored.

### 2.5 The scan is bounded, and the tail is read instead of the whole file

* `extractSessionPreview` stops after 400 lines / 256 KB, and SKIPS the
  engine's user-role messages.
* `extractLastAgentEvent` reads the last 256 KB (`readTailLines`), not the
  whole file. A partial first line (the read started mid-line) and a partial
  last line (a message still being written) are both dropped.

### 2.6 The scan REPAIRS the index — the title becomes persisted

A session created before R390 has no `first_prompt` in the index, so the
daemon has to scan the transcript for it. When the scan finds it, the daemon
now WRITES it back (`SessionStore.backfillFirstPrompt` → one column, only
when the column is empty).

That makes the title durable for the sessions that already exist:

* it survives the next compaction — which is exactly what destroyed it before
  (a compacted transcript no longer carries the original prompt);
* the list stops scanning the transcript on every call.

It fills an EMPTY slot only, so the first prompt still wins and a rename is
never overwritten; and it touches one column, so it does not bump
`last_used_at` — a back-filled session must not jump to the top of the list.

### 2.7 The desktop reads `title`, and re-reads the list when a run ends

* `SessionInfo.name?` → `SessionInfo.title?`, and every reader updated
  (`SessionList`, `TaskSummary`, `CommandPalette`, `SessionPickerModal`,
  `Welcome`, plus four `name:` literals in the store).
* `case 'run_end'` now calls `void get().refreshSessions()`.
* `sendMessage`'s tail goes through `refreshSessions()` instead of a wholesale
  replace — so a session belonging to another project's daemon is not dropped.

---

## 3. The tests

| test | what it pins |
|---|---|
| `aethercode-core …/SessionStoreR390Test` | a NULL never destroys a persisted value; the FIRST prompt wins; a rename survives a later touch; `setCwd` still replaces the cwd; the message count is the real count |
| `aethercode-core …/UserAuthoredTextR390Test` | the classifier, in both the `Message` form and the raw-line form |
| `aethercode-protocol …/AetherCodeMethodsR390SessionTitleTest` | the wire: the title skips the anchor / the post-compact block / the todo-continuation directive; the rename wins; the persisted first prompt wins; the real message count |
| `aethercode-desktop …/__tests__/sessionTitleR390.test.ts` | `SessionInfo` declares `title` and NOT `name`; every reader reads `title`; `run_end` refreshes the session list; `sendMessage` merges instead of replacing |

---

## 4. The lessons

★ **Lesson 1420 — a field the sender never sends is not a "fallback branch";
it is dead code that looks like a feature.** `s.name` was read in five places
and was `undefined` in all of them. The `if (s.name)` guards read as
"prefer the engine-provided name" — a comment that described a capability
nobody had.

★ **Lesson 1421 — "prefer X, fall back to Y" hides a third possibility: Y is
what actually happens, always.** The session row's title was the transcript
scan; the rename was never consulted. A fallback chain that never reaches its
first branch should be read as "the first branch does not exist".

★ **Lesson 1422 — a persisted fact that a NULL-overwrite can delete is not
persisted.** The title WAS in the database, and it was erased by the next
message. "It is in the DB" and "it is still in the DB" are different claims,
and only the second one is a feature.

★ **Lesson 1423 — the direction of a COALESCE follows from WHO WRITES THE
COLUMN.** "Last non-null wins" is right for a column an explicit writer
replaces (`cwd`, `title`); "first non-null wins" is right for a column that is
FILLED ONCE from a stream of candidates (`first_prompt`). Writing
`excluded.col` for both was the bug; picking ONE direction for both would
have been a different bug — and the existing `upsertIsIdempotent` test
correctly refused the second one (a rename must be replaceable).

★ **Lesson 1424 — the first user-role LINE is not the first user turn.** The
engine writes user-role messages of its own. Any code that means "the user"
must ask the shared rule, not the wire shape.

★ **Lesson 1425 — a rule that answers two questions needs two answers, not
one compromise.** `isRealUserTurn` (rewind anchor) and `isUserAuthored`
(session title) disagree about an UNKNOWN kind, and both are right — they
answer different questions and each errs in the safe direction for its own.

★ **Lesson 1426 — a scan that reads the whole file to find the LAST thing in
it is a performance bug that only shows up on the sessions that matter.** The
long-running task is exactly the transcript that is too big to read.

★ **Lesson 1427 — a refresh hook that the parent disables is not a refresh
hook.** `SessionList`'s `useEffect` returns early when `propItems` is set —
and the LeftPanel always sets it. The effect existed, was correct, and never
ran.

★ **Lesson 1428 — the render path is the only path that counts.** Two session-row
components existed; the one that read the right field was not on the LeftPanel's
render path. Fixing the visible-looking one changes nothing the user can see.

★ **Lesson 1429 — a read path that LEARNS a fact should be allowed to write it
down, when the write is narrow and one-shot.** The daemon's scan knows the
session's first prompt; the index does not. Leaving that knowledge in the
transcript meant paying for it on every call AND losing it at the next
compaction. The write must be narrow (one column), one-shot (only while the
column is empty) and side-effect-free (no `last_used_at` bump) — otherwise a
"repair" becomes a second source of truth.★

---

**Doc**: `docs/R390-session-title-truth.md`
