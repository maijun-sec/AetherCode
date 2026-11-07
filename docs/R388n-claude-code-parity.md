# R388n — four Claude Code features, and the rules they exposed

**Round:** R388n
**Date:** 2026-11-07
**Reference:** Claude Code's `/resume`, `/rewind`, mode indicator and permission prompt (per the R388k audit, Appendices A and B)
**Touches:**
- `aethercode-tui/src/components/CheckpointMenu.tsx` (new)
- `aethercode-tui/src/components/ThreadSelector.tsx`, `src/components/Header.tsx`, `src/components/ModePicker.tsx`, `src/components/Scrollback.tsx`
- `aethercode-tui/src/permissionComment.ts`, `src/permissionModes.ts`, `src/state.ts`, `src/tui.tsx`, `src/line.ts`, `src/commands.ts`, `src/panelIntents.ts`, `src/componentKeys.ts`, `src/keybindings.ts`
- `aethercode-core/.../context/GitBranchReader.java` (new), `.../transcript/CodeCheckpointStore.java` (new), `.../transcript/SessionStore.java`, `.../transcript/SessionStoreSqlite.java`, `.../engine/QueryEngine.java`, `.../compact/Compactor.java`
- `aethercode-compact/.../StructuredCompactor.java`
- `aethercode-sdk/.../AetherCodeEngine.java`
- `aethercode-protocol/.../methods/AetherCodeMethods.java`, `.../http/HttpJsonRpcServer.java`

---

## 1. The four items

The R388k audit listed ten Claude Code behaviours worth copying. The user
chose all four of the top group, and this round implements them:

| # | Feature | What it replaces |
| - | ------- | ---------------- |
| 1 | `/resume` picker: branch, size, rename, preview, filters | a list of ids |
| 2 | `/rewind` checkpoint menu: six operations, real backends | `/rewind <n>` and a blind `Ctrl+Z` |
| 3 | A LITERAL mode indicator ("auto mode on" / "ask every call mode on") | a mode badge with no plain-language meaning |
| 4 | "Yes, and switch to auto mode" on the permission card | approve, then separately discover `/mode` |

---

## 2. Feature 2 is the one with a real backend

Items 1, 3 and 4 are client-side surfaces over capabilities the daemon
already had. Item 2 was **not**: two of Claude Code's six rewind operations
had no implementation at all.

```
restore code and conversation   ← no backend
restore conversation            ← existed (the `rewind` RPC)
restore code                    ← no backend
summarize from here             ← no backend
summarize up to here            ← no backend
never mind                      ← trivial
```

So R388n added, on the daemon:

- **`CodeCheckpointStore`** (new, `aethercode-core`) — the FILES side of a
  checkpoint. It snapshots the content of the files the session has
  written, and it restores them.
- **`Compactor.summarizeNow`** (new default method) — an UNCONDITIONAL
  summarisation. The existing `compact()` is gated on the context window,
  which is right for automatic compaction and wrong for a user who asked
  for a summary.
- **`AetherCodeEngine.restoreConversationTo(checkpointId)`** and
  **`AetherCodeEngine.summarizeAtCheckpoint(checkpointId, mode)`** — the
  two operations, anchored on a checkpoint.
- **Three RPCs**: `checkpoints/list`, `checkpoints/restore`,
  `checkpoints/summarize`, registered on stdio **and** routed on HTTP.

### 2.1 The files are snapshotted, not taken from git

A git-based restore would need a commit per turn, and it would restore
files the **model never touched** — including the user's own uncommitted
work, which is the one thing a user least wants a "restore" to throw
away.

The store snapshots **only the files the tools wrote**, so the restore is
exactly scoped to what the session did. The list of touched files is
**derived from the transcript's tool calls** (`file_write` / `file_edit`),
so there is no second bookkeeping path that could disagree with what
actually happened.

Three rules fall out of that:

| case | what the restore does | why |
| ---- | --------------------- | --- |
| the file existed | writes back the saved content | the normal case |
| the session **created** it | **deletes** it | writing an empty file leaves a zero-byte file where the user's tree had none |
| the file is **binary** | **skips** it, and says so | writing mangled bytes back is worse than not restoring |

### 2.2 The snapshot has to be taken at the TURN BOUNDARY

A checkpoint taken *after* the turn's tools have run captures the "after"
state and restores nothing. A checkpoint taken *before* the prompt is
appended does not know which turn it belongs to.

So `QueryEngine` grew a `setOnTurnBoundary` hook, fired once per user
turn — after the pre-flight compact (which may rewrite the transcript) and
before the prompt is appended. The SDK installs it and takes the
checkpoint.

> **A second setter, not a second argument.** `setOnUserPrompt` already
> existed and the SDK already used it. Adding the checkpoint to it would
> have meant one installer silently replacing the other. Two setters for
> two independent concerns.

### 2.3 An ordinal and a position are different addresses

This is the defect that would have shipped.

The engine writes several `Role.USER` messages the user never typed:

```
kind=session-anchor          (R719's periodic anchor)
kind=compaction-side-note    ("[compaction] …")
kind=transcript-trim-side-note
kind=todo-ask-llm            (the controller's own question)
kind=user-decision-resume
```

The pre-existing `rewind(target)` counts **every** `Role.USER` message.
So after a compaction notice, "rewind to turn 3" lands on the notice
instead of the user's third message — and the feature looks like it
worked right up until it corrupted the conversation.

R388n's fix is **one counting rule, in one place**:

```java
CodeCheckpointStore.userTurnIndices(transcript)   // the shared list
CodeCheckpointStore.isRealUserTurn(message)       // the shared test
```

Both the checkpoint producer and the summarise path resolve their boundary
through it. And the checkpoint-anchored operations
(`restoreConversationTo`, `summarizeAtCheckpoint`) do **not** re-derive a
boundary at all — they use the checkpoint's **recorded transcript
position**, because the menu said "at this point" and that point is a
position, not an ordinal.

> **Lesson 1361 — an ordinal and a position are different addresses; a
> restore that re-derives its own boundary is a restore that can land
> somewhere else.**

### 2.4 "Restore code and conversation" is a FLAG, not a third operation

The menu offers six actions. "Restore code and conversation" is not a
separate implementation — it is the code restore **AND** the conversation
restore. A separate branch for it would let the two drift: a fix to one
would not reach the other, and the user would get code restored in one
menu entry and not the other.

So each action carries two flags:

```ts
interface CheckpointActionInfo {
  label: string;
  blurb: string;
  touchesCode: boolean;
  touchesConversation: boolean;
}
```

and the dispatcher reads the flags. The combined action is
`touchesCode: true, touchesConversation: true`.

The scope goes on the wire as **one** request:

```
checkpoints/restore { id, scope: "code" | "conversation" | "both" }
```

Two requests would mean the code restore could succeed while the
conversation restore failed, and the user would be told "done" with half
the work undone.

> **Lesson 1360 — "both" is a FLAG, not a third implementation.**

### 2.5 Esc Esc

Claude Code opens the rewind menu with `Esc Esc`. Esc already means "close
the overlay / stop the run / drop the queue" in this TUI, so a shortcut
that grabbed the **first** Esc would make every panel need two Escs to
close.

The rule is therefore: the **second** Esc within 600 ms opens the menu;
the first falls through to the rest of the ladder.

> **Lesson 1362 — a shortcut must not take a meaning away from the key it
> shares.**

---

## 3. Feature 3: a LITERAL mode indicator

The mode badge said `ACCEPT_TASK`. That is the daemon's enum constant, and
it means nothing to a user who has not read the source.

R388n gives every mode a plain-language phrase:

| mode | indicator |
| ---- | --------- |
| `DEFAULT` | `⏸ manual mode on` |
| `ASK_BEFORE_TOOL` | `⏸ ask every call mode on` |
| `ACCEPT_TASK` | `⏵⏵ auto mode on` |
| `ACCEPT_EDITS` | `⏵⏵ accept edits on` |
| `PLAN` | `⏸ plan mode on` |
| `READ_ONLY` | `⏵⏵ auto mode on (reads only)` |
| `BYPASS` | `⏵⏵ bypass permissions on` |

The glyph carries the one fact a user needs at a glance: `⏸` = it will
still ask, `⏵⏵` = it will not.

**An unknown mode is treated as "still asks".** A mode the daemon sends
that the TUI does not know about must fail on the SAFE side — the user
should believe they will be asked.

The phrase lives in the ONE catalog (`src/permissionModes.ts`) and is read
by the header badge, the line renderer's status line and the mode picker,
so the three surfaces cannot describe the same mode differently.

---

## 4. Feature 4: "Yes, and switch to auto mode"

Claude Code's permission prompt offers, as one option, "yes, and switch to
auto mode": the call is approved **and** the session stops asking for this
kind of call.

The TUI's permission card offered six answers (A / T / P / U / D / N) and
**none** of them changed the session's permission mode. The trap: `P` and
`U` *look* like they do — they install a rule for the **tool/target**, and
they do not touch `PermissionMode`. A user who reads "allow for this
project" and presses `P` believes the session has stopped asking, and it
has not.

R388n adds a fifth numbered answer:

```
[M] 5 — allow and switch to auto mode     (mode = ACCEPT_TASK)
```

### 4.1 The mode travels on the wire, and the reply does not depend on it

The reply and the mode change are two different concerns:

```
permissionResponse { requestId, decision: "allow", mode: "ACCEPT_TASK" }
setPermissionMode  { mode: "ACCEPT_TASK" }        ← sent SEPARATELY, after
```

The mode change is issued as its **own** RPC after the reply, not chained
onto it: the reply is what unblocks the model, and a failing mode change
must not delay or fail the approval the user already gave.

> **Lesson 1357 — an APPROVAL must not depend on a cosmetic follow-up.**

### 4.2 The label lookup has to match on three things

`armedLabel` used to find the entry by **decision** alone. Two answers now
share the `allow` decision (`a` and `m`), so that lookup showed "allow
once" for a pick that switches the mode — the header described a
**different answer** from the one about to be sent.

> **Lesson 1355 — two answers that produce the SAME decision are two
> answers.**

### 4.3 The digit is EXPLICIT data

There are five answers, and `t` / `y` are synonyms of `a` / `always`. So
the numbering cannot be derived from the array order — deriving it would
produce a sixth and seventh option that do not exist. Each entry carries
its own `number` field.

### 4.4 The handler is registry-driven

The decision keys lived in `DECISION_KEYS` (R388l), but the Ink app's key
handler was a **hand-written if-chain** — a second list of the same keys.
The new `M` key would have lived in the registry (so the card could
advertise it) while the if-chain did not know about it (so pressing it
would do nothing). The key would look alive and be dead.

> **Lesson 1356 — a key that is advertised but not wired is worse than a
> key that is absent.**

---

## 5. Feature 1: the `/resume` picker

| addition | what it does |
| -------- | ------------ |
| `gitBranch` per session | the daemon reads `.git/HEAD` — a file read, **not** a subprocess |
| size | `sizeBytes` was already on the wire; the TUI never read it |
| `Ctrl+R` rename | `session/rename` — writes the `title` column **only** |
| `Space` preview | shows the session's first prompt in full |
| `Ctrl+B` | only this branch |
| `Ctrl+A` / `Ctrl+W` | all projects / all worktrees |

### 5.1 The branch is read from a FILE

Fifty rows × one `git` subprocess is fifty processes. `GitBranchReader`
reads `.git/HEAD` directly, follows a `.git` **file** (worktrees and
submodules), and caches for two seconds per git directory.

A **detached HEAD**, a remote ref and a tag all return `null`. A wrong
label is worse than no label — the user would look for a branch that does
not exist.

### 5.2 A rename must not MOVE the session

`SessionStore.touch()` writes `cwd` as well as the title, and it is the
call the session list already used. Using it for a rename would have
**re-attributed the session to a different directory** — the session would
vanish from the project it belonged to.

So `setTitle(id, title)` updates **one column**.

---

## 6. The gates

| gate | cases | what it holds |
| ---- | ----- | ------------- |
| `r388n-checkpoint-menu.test.mjs` | 23 | one action list; the flags are the contract; `/rewind` opens the menu; Esc-Esc is the SECOND press; both transports; the ordinal rule is shared; the checkpoint producer is wired |
| `r388n-checkpoint-menu-mutation.test.mjs` | 11 | the gate above **goes red** on 11 real defects |
| `r388n-resume-picker.test.mjs` | 19 | the branch/size/rename/filter surface |
| `r388n-mode-indicator.test.mjs` | 11 | one phrase per mode; unknown modes are safe |
| `r388n-auto-mode.test.mjs` | 23 | the `M` answer; the registry-driven handler; the independent `setPermissionMode` |
| `r388n-auto-mode-mutation.test.mjs` | 9 | the gate above goes red |
| `CodeCheckpointStoreR388nTest` (Java) | 19 | the ordinal rule; restore / delete / skip / fail |
| `AetherCodeMethodsR388nTest` (Java) | 12 | the RPC surface; the scopes; the honest failures |

### 6.1 The mutation test found a real gate bug

The mutation test's eleventh case — "comment the turn-boundary call out"
— **survived**. The gate read the **raw** source, and a commented-out
call leaves the text in the file, so "the hook must fire" was satisfied by
a comment.

Every "must contain this call" check now reads the **comment-stripped**
source.

> **Lesson 1363 — a "must contain" check that reads the raw source is
> satisfied by a commented-out line. Read the stripped source.**

And a second mutation exposed Lesson 1319 in the mutation file itself:
the needle `const info = CHECKPOINT_ACTION_INFO[action];` occurs **twice**
in `tui.tsx` — once in the JSX callback and once nowhere else — and the
mutation hit the wrong occurrence, changing nothing the gate looked at.

> **Lesson 1319 — a needle that is not unique lands on the wrong
> occurrence and pretends to have tested something.**

---

## 7. Lessons

- **1355** — two answers that produce the SAME decision are two answers.
- **1356** — a key that is advertised but not wired is worse than a key
  that is absent.
- **1357** — an APPROVAL must not depend on a cosmetic follow-up.
- **1358** — a test runner's child must not inherit `NODE_TEST_CONTEXT`,
  or it exits 0 whatever the child test does.
- **1359** — a menu entry that names an OPERATION can be checked; a menu
  entry that names a LABEL cannot.
- **1360** — "both" is a FLAG, not a third implementation.
- **1361** — an ordinal and a position are different addresses; a restore
  that re-derives its own boundary is a restore that can land somewhere
  else.
- **1362** — a shortcut must not take a meaning away from the key it
  shares.
- **1363** — a "must contain" check that reads the raw source is satisfied
  by a commented-out line. Read the stripped source.
