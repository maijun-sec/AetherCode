# R388p — the rewind menu must FIT, and every key must DO something

**Status**: implemented (gate 14/14 green, mutation 7/7, Java 5/5)

## The surface

The rewind menu (`/rewind`, `Esc Esc`, and the "Rewind" panel) is Claude Code's
most useful undo affordance: it offers, at each point in the conversation,
"Restore code and conversation" / "Restore conversation" / "Restore code" /
"Summarize from here" / "Summarize up to here" / "Never mind".

R388n gave the menu a real backend. R388p fixes the four defects that remained —
all found by **measurement**, not by reading the code and guessing.

---

## Defect 1 — THE LIST DID NOT FIT

**Measured**: 40 checkpoints rendered **53 frame rows**. The menu's own rows
(caption, six actions, file list, hint) plus 40 checkpoint rows do not fit a
40-row terminal.

The list had **no window at all**. Every checkpoint was rendered, so the panel
grew without bound and ran off the screen — and the entry the user had selected
could be off-screen with it.

```
=== 40 checkpoints (before) ===
  frame lines total: 53
  checkpoint rows rendered: 40
```

**Fixed** by a window over the list (`src/listWindow.ts`), plus a "N earlier
above" / "N later below" marker so a silent truncation does not look like the
list ends there.

```
=== 40 checkpoints (after) ===
  frame lines total: 22
  checkpoint rows rendered: 8
  first: #33 …   last: ▶ #40
```

---

## Defect 2 — THE DOWN ARROW WAS A DEAD KEY

**Measured**: the menu opens with the selection on the **newest** entry, and
the old handler clamped:

```ts
setCheckpointIdx((i) => Math.min(checkpoints.length - 1, i + 1));
```

So on open the selection was already at `length - 1`, and the clamp kept it
there. **The first press of the down arrow did nothing.**

```
=== 40 checkpoints: Tab to the list, then press DOWN 3 times (before) ===
  rows with the ▶ marker: ▶ #40  turn 39 | ▶ 1. Restore code and conversation | ▶ #40  turn 39
```

A key that does nothing is worse than a key that is absent: the user concludes
the feature is broken and never tries the other direction.

**Fixed** by making the selection **wrap** (`moveSelection`): up at the top
reaches the newest, down at the bottom reaches the oldest. Both directions are
always meaningful.

```
=== after ===
  DOWN 3 times → ▶ #3   (wrapped to the oldest and back up)
  UP   3 times → ▶ #37
```

---

## Defect 3 — THE TWO SUMMARIZE ENTRIES READ IDENTICALLY

Both entries carried the same blurb:

> "replace what came before with a summary, keep what follows"

So the user had no way to tell them apart — and the second one was **wrong**
for the action it described.

### ★ WHAT THE DAEMON ACTUALLY DOES ★

This is where the round turned. The first fix claimed the two were
**opposite** outcomes ("from" keeps the tail, "up_to" keeps the head). That
was read off the menu's *names*, not off the daemon — and it was **wrong**.

The engine's `summarizeAtTranscriptIndex` says:

```java
int split = keepAfter ? boundaryIndex : boundaryIndex + 1;
// The head is what gets summarised; the tail is what survives.
// ("summarize from here" summarises the UP-TO part and keeps
//  the FROM part — the naming in the menu is about which end the
//  cursor is at, and the operation is the same.)
```

**Both** actions summarise the head and keep the tail. The only difference is
the boundary: `from` splits *before* the point's own message and `up_to` splits
*after* it, so `up_to` folds that message into the summary.

The blurbs now say that:

| Action | Blurb |
| --- | --- |
| Summarize from here | summarize everything BEFORE this point, keep what follows |
| Summarize up to here | summarize up to AND INCLUDING this point, keep what follows |

**Lesson 1382 — a description of another system's behaviour is a CLAIM, and a
claim cannot verify itself.** The gate now reads the engine's own split rule
and the menu's own blurbs, and checks they agree. A gate that pins a *story*
instead of the *behaviour* is a gate that makes the code lie to match it.

---

## Defect 4 — THE STORE GREW WITHOUT BOUND

This is the one that was not a UI annoyance.

`CodeCheckpointStore` kept every checkpoint in a plain `LinkedHashMap` with no
eviction. And **each checkpoint holds a full copy of the content of every file
the session has touched so far**. So the memory a session's checkpoints take is

```
turns x files-touched x file-size
```

— **quadratic** in the session's length, and every turn makes it worse. A long
session would grow the heap without bound and the daemon would eventually die
of `OutOfMemoryError` rather than of anything the user could see or act on.

**Fixed**: `MAX_CHECKPOINTS = 64`, with **FIFO** eviction (the oldest goes
first — the newest are the ones a user rewinds to). The count of evicted
checkpoints goes on the wire (`checkpoints/list` → `evicted`) and the menu says
so, because a list that silently starts at "point 40" looks as though the
daemon lost the session's history.

---

## A fifth defect, found on the way: the console gate was a RACE

The R388o console gate — which runs the shipped bundle in a real
`conhost.exe --headless` — went **red** during this round, with:

> `R388o: the bundle produced no output at all in the console.`

The bundle had not changed by a byte, and it rendered perfectly when run
directly. So the defect was in the **measurement**.

The gate's shim ended the run with `setTimeout(() => process.exit(0), 4000)`.
But `runTui` does:

```ts
await client.request("ping", undefined, { timeoutMs: 30_000 });
app = render(<App ... />, ...);
```

**Ink does not render until the daemon answers.** A cold JVM start takes longer
than 4000 ms, so the process exited **before the first frame was ever drawn** —
and the gate measured an empty screen.

Measured, side by side:

| shim delay | bytes the bundle wrote |
| --- | --- |
| 4000 ms | **0** |
| 20000 ms | **55 738** |

The difference was not the fix — it was **whether the daemon had booted yet**.
The previous round was green because that machine happened to be fast enough.

**Fixed**: the shim now **watches stdout and exits as soon as it has seen a
frame**, with a 45 s ceiling for the case where the daemon never comes up. The
byte count is the signal (the console's own startup clear is a handful of
bytes; a painted frame is kilobytes).

### ★ AND THE GATE WAS PROVED STILL ABLE TO GO RED ★

"Wait longer" is very easy to turn into a **vacuously passing** gate. So the
bundle was replaced with a stub that renders **nothing** (in an OS-temp copy),
and the gate had to go red. It did.

### ★ AND A FIXED GATE IS STILL A GATE ONLY IF IT CAN GO RED ★

**Lesson 1387 — a fixed-delay probe measures "was the machine fast enough", not
the product.** Any probe that waits for an **external process** must wait for
that process's **signal**, not for a duration. And when a timing-dependent gate
is made robust, it must be **re-proved** that it can still fail — otherwise
"more patient" has silently become "blind".

---

## The gate

`scripts/test/r388p-checkpoint-menu.test.mjs` — **14 checks**.

### ★ THE PROBE RENDERS THE REAL COMPONENT AND SENDS REAL KEYS ★

Defect 2 is invisible to a static render: the list looks right until you press
the key. So the probe drives the real component with real keystrokes.

### ★ INK READS ITS INPUT ON THE 'readable' EVENT ★

Ink's `App.js` does `stdin.on('readable', handleReadable)` and then
`while ((chunk = stdin.read()) !== null)`. So a fake stdin must **buffer** the
bytes and hand them back from `read()`. Emitting `'data'` does nothing at all —
Ink never listens for it.

A probe that emits `'data'` measures a menu whose keys do not work, and then
reports "the keys are broken" about a menu that is fine.

### ★ AND `read()` MUST BE OVERRIDDEN AFTER CONSTRUCTION ★

`Writable` has its own `read()` on the prototype, so an object-literal method
is shadowed by the prototype's — and Ink would call the prototype's one, which
returns `null` and delivers nothing.

### The checks

| # | Check |
| --- | --- |
| 1 | 40 checkpoints do not make a 53-row frame |
| 2 | the frame does not grow as the list grows (and a short list shows itself in full) |
| 3 | the selected checkpoint is always on screen |
| 4 | the menu says how many points are hidden above and below |
| 5 | the down arrow is not a dead key |
| 6 | both arrows move the selection, in opposite directions |
| 7 | the selection wraps — the arrows are never dead at the ends |
| 8 | the two summarize actions do not read identically |
| 9 | the summarize blurbs describe what the daemon really does |
| 10 | the daemon really does keep the tail for both actions (reads the engine's split rule) |
| 11 | the daemon's checkpoint store is bounded |
| 12 | `listWindow` keeps the selection visible (every size x every selection) |
| 13 | `listWindow` survives an empty list |
| 14 | `moveSelection` wraps — it never leaves the user on a dead key |

### The mutation test

`scripts/test/r388p-checkpoint-menu-mutation.test.mjs` — **7 mutations, all
caught**:

1. the list stops being windowed
2. the selection is allowed to leave the window
3. the down arrow goes back to clamping
4. the up arrow goes back to clamping
5. the menu stops saying how many points are hidden
6. the two summarize entries read identically again
7. the store's bound is removed

Each mutation **reads the file back** after the replacement and asserts the
target text is *gone* — because a replacement that matched nothing changes
nothing, and the mutation would then measure the unmutated code and report
"the fix does not matter" (Lesson 1380). The copy goes to the **OS temp dir**,
because a junction resolves back to the original tree (Lesson 1373).

---

## Lessons

**Lesson 1382 — a description of another system's behaviour is a CLAIM, and a
claim cannot verify itself.** The first fix for defect 3 was read off the menu's
*names* ("from" vs "up to") and was simply wrong: the daemon summarises the
head and keeps the tail in both cases. A gate that pins a *story* instead of
the *behaviour* is a gate that makes the code lie to match it. Cross-check the
description against the implementation's own source.

**Lesson 1383 — a key that does nothing is worse than a key that is absent.**
The down arrow was bound, consumed the keystroke, and changed nothing. The user
cannot tell "this does not apply" from "this is broken", so they stop trying
the feature. Every list selection should **wrap**, so no end of the list is a
dead end.

**Lesson 1384 — a fake stdin must implement the READ side, not the write side.**
Ink reads `stdin.read()` on the `'readable'` event; it never listens for
`'data'`. A probe that emits `'data'` measures a component whose keys do not
work — and then reports the component's keys as broken. And `read()` must be
overridden **after** construction, because `Writable`'s prototype method
shadows an object-literal one.

**Lesson 1385 — "the list is bounded" is a claim about the ROWS, not about the
FRAME.** The windowed menu is two rows TALLER than the unwindowed one for a
short list, because it adds the "N earlier above" / "N later below" markers.
The first version of the gate measured the frame and failed against correct
code.

**Lesson 1386 — an unbounded store of snapshots is a quadratic leak, and it is
not a UI defect.** Each checkpoint held a full copy of every touched file, so
the store's size grew as turns x files x file-size. The bound belongs in the
STORE, not in the renderer — and the count of what was dropped has to reach the
user, because a history that silently starts at "point 40" looks like data
loss.

**Lesson 1387 — a fixed-delay probe measures "was the machine fast enough", not
the product.** The console gate's shim ended the run after 4000 ms; the bundle
does not render until the daemon answers its `ping`; a cold JVM start takes
longer. So the gate measured an empty screen and called it a defect. Any probe
that waits for an **external process** must wait for that process's
**signal**. And a timing-dependent gate that is made more patient must be
**re-proved** that it can still fail — otherwise "more patient" has silently
become "blind".

**Lesson 1388 — a gate that pins a STORY instead of the BEHAVIOUR makes the
code lie to match it.** (Restated from 1382, because it cost this round a
wrong fix.) The first fix for the two summarize entries asserted they were
**opposite** operations — read off the menu's *names*, not off the daemon. The
engine summarises the head and keeps the tail in **both** cases. The gate now
reads the engine's own split rule and checks the menu's blurbs agree.
