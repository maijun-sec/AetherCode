# R389 — The chat column is the desktop's document, drawn in a terminal

> Round: R389
> Date: 2026-11-20
> Touches: `aethercode-tui/src/chat/blocks.ts` (new),
> `aethercode-tui/src/components/ChatBlocks.tsx` (new),
> `aethercode-tui/src/components/Scrollback.tsx`,
> `aethercode-tui/src/components/chat/SummaryFooter.tsx`,
> `aethercode-tui/src/state.ts`,
> `aethercode-tui/src/fileChanges.ts`,
> `aethercode-tui/src/tui.tsx`,
> `aethercode-tui/scripts/test/r389-chat-column.test.mjs` (new),
> `aethercode-tui/scripts/test/fixtures/r389-blocks-probe.ts` (new),
> `aethercode-tui/scripts/test/r388r-file-card-actions.test.mjs`,
> `aethercode-tui/scripts/test/phase-6-t6-14.test.mjs`,
> `aethercode-desktop/src-tauri/tauri.conf.json`

---

## The ask

> "主要是你现在的 TUI 太丑了，我需要的是 TUI 和现在的 desktop 的中间部分一样，
> 很多业界的 cli 都是这个样子的。"

"I don't think it looks good. I'd rather it look like the desktop's
middle section. I've used several products that all do it that way."

R388h already moved the TUI's **chrome** towards the desktop's shape:
the transcript became a flat document, the user turn got an accent
bar, the empty state became one line. What it did NOT move was the
**content structure** — and that is what the user was still seeing.

## What the TUI was still doing

Three pieces of chrome per turn, and a missing hierarchy:

```
Assistant                      ← a label above every reply
<the reply>

Thinking                       ← a label above every think
<the think>

┌──────────────────────────┐   ← a BOX around every tool call
│ ✓ ⚒ file_read  src/App.tsx │
└──────────────────────────┘
```

A forty-turn session read like a stack of crates. The desktop's
centre column has none of those: its tool calls are a one-line
`<summary>` row, its think is a folded `<details>`, and its reply is
plain prose.

And the STRUCTURE was missing entirely. The daemon emits
`sub_task_start` / `sub_task_end` — the model's own statement of "this
is the task I am working on" — and the TUI **dropped them on the
floor**: zero occurrences of `sub_task` in the whole source tree. The
desktop renders them as a `##` heading with a `结果` footer.

## The desktop's shape, and why it is portable

The desktop's centre column is not a stack of cards. It is a flat
markdown document, and it is built in two layers:

| Layer | Where | DOM? |
|---|---|---|
| the block model — `buildBlocks()` → `Block[]` | `MessageList.tsx:631` | no |
| the fold policy — `defaultOpenFor()` | `MessageList.tsx:693` | no |
| the diff — `summarizeDiff()` / `buildEditSnippetFromInput()` | `MessageList.tsx:305` / `:395` | no |
| the renderer — `<ReactMarkdown>` + `<details>` | `MessageList.tsx:711` | **yes** |

Three of the four layers are DOM-free. That is what made this round
cheap: the TUI could take the desktop's **rules** and write its own
renderer, instead of re-inventing the rules and hoping they match.

## The new shape

```
▌ 帮我把 chat 列改成和 desktop 一样

读懂 desktop 中间栏的结构
▾ 思考 计划 1. 先读 desktop 的 MessageList 2. 抽出纯函数
  计划
     1. 先读 desktop 的 MessageList
     2. 抽出纯函数
▸ ✓ ○  file_read aethercode-desktop/src/components/MessageList.tsx
▸ ✓ ▷  bash npm test

  读完了。desktop 中间那栏其实是 markdown 优先的：
    ▸ 一个 `##` 标题代表一个子任务
▾ 结果 completed
  desktop 是 markdown 优先的：标题 + 可折叠块 + 结果
```

* **no labels** — the reply is prose, the think is a folded row, the
  tool call is a one-line row;
* **no boxes** — the only framed things left are the stop banner, the
  error card and the file-changes summary, which are exceptions on
  purpose;
* **a `##` heading per sub-task**, and a `结果` footer when it closes.

## The rules

### 1. The block model is PURE — `src/chat/blocks.ts`

No Ink, no DOM, no terminal. It decides what the transcript **IS**;
the renderer decides how a block **looks**. That split is what lets
the desktop import this module later without moving it.

`buildTimeline()` produces the timeline (user / system / plan /
agent-group, interleaved by timestamp — the desktop once grouped by
type and the engine's notes floated to the bottom), `resolveOpen()`
is the fold policy, and `summarizeDiff()` /
`buildEditSnippetFromInput()` produce a REAL +/- diff for a
`file_edit` — built from the tool's own `old_string` / `new_string`,
not from its "edited /path (3 replacements)" one-liner.

### 2. The fold policy is the desktop's

* the heading, the result and the file-changes summary are always
  open (they are the anchors);
* the **final think** is always open (R276: it is the model's
  concluding reasoning);
* while the run is live, the **last block** is open (new output
  arrives visible);
* everything else is folded.

★ THE TERMINAL'S ADDITION ★ A terminal has no `<details>`, so the
fold has to live somewhere — and the TUI already had a place: the
turn's own `collapsed` flag. So the user's choice wins, except for
the two blocks the desktop refuses to hide. `Ctrl-E` now folds the
last **foldable** block (a think or a tool), not the last turn —
which used to be the answer, i.e. the one block that is never
supposed to hide.

### 3. The file-change grouping is ONE algorithm, three callers

The R388q card groups consecutive file-changing turns; the app finds
the card a key means; and now the chat column collapses the same runs
into one summary row. Three callers, one rule:

```ts
fileChangeRuns(turns)          // the scan — fileChanges.ts
  ├── fileChangeGroupIdAt()    // the APP's targeting
  ├── groupTurns()             // the CARD's grouping (Scrollback)
  └── buildTimeline()          // the chat column's grouping
```

Before R389 `fileChangeGroupIdAt` walked BACKWARDS from a turn to find
its run's head, and `groupTurns` walked FORWARDS — two algorithms
that happened to agree. Now there is one.

### 4. The sub-task events reach the screen

`handleStreamEvent` gained the two cases; the reducer grew
`subTasks` / `currentSubTaskId`; and every turn the reducer creates
is stamped with the open sub-task's id. That stamp is what lets the
renderer draw the `##` heading and nest the tool calls under it.

## The gates

`scripts/test/r389-chat-column.test.mjs` runs the **real** module on
a **real** transcript and asserts on the **values** — the block
kinds, the heading, the fold decisions, the previews, the diff
segments. A grep for `buildBlocks` would prove the string is in the
file and nothing else.

Two older gates were updated rather than deleted, because the
**invariant** did not change — only its location:

* `phase-6-t6-14` read "the Scrollback renders the SummaryFooter";
  it now reads "the chat column renders it" (Lesson 1233: a pin that
  names a LOCATION goes red on a REFACTOR).
* `r388r` read "the Scrollback hands `onShowFiles` the app's
  callback"; it now checks the **chain** — Scrollback → ChatBlocks →
  the card — because the card moved one hop down.

## Lesson 1409 — a renderer never decides on its own what the transcript IS

The desktop's rules and the TUI's rules were two copies of one
truth, and the copies had already drifted: the TUI had no
sub-task grouping at all, and no fold policy to speak of. The fix is
not "make the TUI's copy better" — it is to put the rule somewhere
both can read. `src/chat/blocks.ts` is that somewhere, and it is
written so the desktop can import it.

## Lesson 1410 — the gate's fixture has to be able to FAIL the rule

The first version of the R389 fixture had ONE think in a sub-task —
and that think is, by definition, the final think, which the rule
keeps open. The gate therefore asserted "a think is folded" against a
fixture where every think is open, and the fixture was the thing that
was wrong. A fixture with one instance of the discriminating case
cannot tell two rules apart; it needs an instance of the
non-discriminating case too. (The first fixture's gate run failed
for exactly this reason, and the fix was the fixture, not the rule.)

## Lesson 1411 — "it looks like the desktop" is a SHAPE, and a shape can be measured

The complaint was visual ("太丑了"), which is the kind of complaint
that is easy to agree with and hard to act on. What made it actable
was noticing that the desktop's centre column is built from a
DOM-free rule layer — so "the same look" reduced to "the same rules
plus a different renderer". The rules are now measured by a gate; the
renderer is checked by looking at it (`scripts/preview-r389.mjs`
renders the real Scrollback with a realistic transcript, so the
question has a picture attached instead of a guess).

## What is still open

* The desktop does not import `chat/blocks.ts` yet. When it does, the
  two renderers will read one rule — but that is a separate round,
  and it touches the desktop's store.
* The `## Summary` block is still extracted into a summary footer
  rather than left in the markdown document as the desktop leaves
  it. The footer is now UNBOXED (R389 removed the yellow border), so
  it reads as part of the document, but the extraction itself is
  still the TUI's T-6-13 convention.
* The "⚠ auto-summary pending" line still appears on every reply
  without a `## Summary` block. It is transient (the runtime injects
  one within ~1s) but it is noise the desktop does not have.

---

# R389g — What the suite was hiding

The full TUI suite reported **61 failures** after R389. Sixty-one is
a number that invites the wrong conclusion — "the change broke the
world" — and the way to settle it is not to argue but to **measure
the baseline**.

## The baseline

A pristine `git worktree` at HEAD (with `node_modules` junctioned
in) ran the same gate files:

```powershell
git worktree add D:\work\workspace\idea\engine\_ac_base HEAD --detach
New-Item -ItemType Junction -Path "$base\aethercode-tui\node_modules" `
  -Target '...\AetherCode\aethercode-tui\node_modules'
```

**58 of the 61 are BASELINE failures** — red on an untouched tree,
red before this round started:

| group | count | what it is |
|---|---|---|
| `docs/*` | 15 | the gates want a `docs/` tree inside `aethercode-tui/`; it does not exist |
| StatusBar (R44/R48/R112/R165/R167) | 10 | the component's fields moved; the gates still read the old ones |
| R77 / R78 / R79 | 13 | backend `MetricsCollector` / `TraceRecorder` are not where the gates look |
| R94-E / R95-E/F/G | 5 | `commands.ts` moved |
| R33 / R35 | 4 | `Header.tsx` pills, `line.ts` categorisation |
| `line-runend`, `r194-end-to-end`, `T-457` | 3 | end-to-end probes |
| R169 | 1 | `EffortPicker.tsx` |

**3 were R389's own** — and all 3 had the same shape.

★ AND THE BASELINE RUN FOUND ONE MORE ★ On the pristine tree, the
`r388q` mutation battery failed **three** more — `LINK`, `undo
ACTION`, `review ACTION` — with "the anchor occurs 0 times". Those
had been blind since R388r: the battery was not running the mutation
at all, and it said so. R389g re-points them.

### The result

After R389g, a second full-suite run compared against the first:

* **13 failures FIXED** — the prettier gate, the 5 `R388n` flakes, the
  4 `r388q` anchors, the `r388q` java-contract race, the 2 `r388r`
  anchors, and the `phase-6-t6-14` reflow.
* **3 failures NEW** — and all three were the 300-second timeout
  (§4 below), now fixed.
* **58 unchanged** — the pre-existing baseline failures, untouched.

## The shape: a mutation gate whose anchor MOVED

```
R388q: the anchor for src/components/FileChangesCard.tsx occurs 0 times, not once
```

R388r had rewritten the card to read its labels from
`FILE_CARD_ACTION_INFO`, so the literal
`<CardAction label="撤销" keyHint="u" active={focused} />` that
`r388q`'s mutation replaced **no longer existed**. The mutation gate
had been blind since R388r — it was not "red for the wrong reason", it
was **not running the mutation at all**.

The fix re-points the anchor at the defect's NEW home and keeps the
mutation's INTENT:

```js
// the intent: "the run never breaks"
mutate(dir, ["src", "fileChanges.ts"],
       "    close();\n  }\n  close();",
       "    // R388q mutation: the run is never broken\n  }\n  close();");
```

★ AND THE RULE ITSELF MOVED ★ `groupTurns` (the card) used to carry
its own forward scan while `fileChangeGroupIdAt` (the app) walked
backwards — two algorithms that happened to agree. R389's doc claimed
"one algorithm, three callers"; the code said two. So `groupTurns`
now reads the shared `fileChangeRuns`, and the claim became true.

★ AND THE GATE STOPPED GREPPING ★ The old assertion read the
Scrollback's SOURCE and looked for `pending = null;`. A lookup can
be satisfied without the rule holding. It now calls the real function:

```js
const runs = changes.fileChangeRuns(turns);
assert.deepEqual(runs.map((r) => r.turnIds), [[1, 2], [4]]);
```

(`fileChanges.ts` is a pure module with no imports, so Node loads it
directly — no bundler, and the mutation sandbox's copy is the one
that gets loaded, so a mutation IS measured.)

## Two more defects the suite was hiding

### 1. A mutation test that edited the working tree

`r388n-auto-mode-mutation` mutated the REAL
`src/permissionComment.ts` / `tui.tsx` / `line.ts` /
`Scrollback.tsx` and restored them afterwards. Two consequences:

* **It could eat uncommitted work.** If the process died between the
  mutation and the restore — a timeout, a Ctrl-C — the mutation is
  what was left on disk, and nobody learns the source was rewritten.
* **It made the suite flaky.** The suite runs test FILES in parallel,
  and this one was rewriting `permissionComment.ts` **while**
  `r388n-auto-mode` was reading it. The reader caught the mutated
  file and reported `'deny' !== 'allow'` against a perfectly good
  tree. The failure looked like a feature defect; it was a harness
  defect.

It now mutates a SANDBOX copy — the whole `src/` tree, the gate file
itself, and the daemon's Java `src/` (the wire-contract assertion
reads `AetherCodeMethods.java` one level above the root) — with
`node_modules` as a **junction**, because the DEPS are shared and the
MUTATED FILES must not be.

### 2. Two sandboxes, one mutable path

`r388q` and `r388s` both put their sandbox's sibling tree at
`<tmp>/aethercode`, and `r388s` **deletes** that path when it is done.
So `r388q`'s wire-contract assertion died with `ENOENT` — red for the
wrong reason, and only when the two files happened to run in parallel.
Each sandbox now has a parent of its own.

### 3. And prettier, blinding a gate one more time

`phase-6-t6-14` matched the literal `Tab to expand`, and the
formatter reflowed the line to `Tab\n        to expand`. The
invariant is "the collapsed plan says you can expand it with Tab",
so the needle is now `Tab\s+to\s+expand`.

### 4. A timeout that is close to the runtime is a coin flip

The full-suite re-run came back with **3 new failures** — and all
three were the same shape:

```
✖ R388o mutation: the status bar stops dropping its items (302002ms)
  AssertionError: R388o: the mutation did not apply.
```

★ THE MESSAGE IS A LIE ★ "The mutation did not apply" means the
regex did not match the copied source. It did not mean that: the
gate had been **KILLED** by the mutation battery's 300-second
`spawnSync` timeout, and `run.status` was `null`, which the code
turned into `-1` — the "mutation did not apply" sentinel. So the
first thing to check is the DURATION, not the regex.

The durations say it plainly. Across two full-suite runs:

| mutation | run 1 | run 2 | bound |
|---|---|---|---|
| the status bar's right box starts shrinking | 287s | **302s** | 300s |
| the status bar stops dropping its items | 294s | **302s** | 300s |
| the header stops dropping its items | — | **301s** | 300s |
| `R388q: the gate is GREEN against the pristine sources` | 157s | **301s** | 300s |

The bound was sitting ON the runtime. Run 1 squeaked under it; run 2
did not. The gates were not broken — the timer was.

★ WHY IT IS 300s AT ALL ★ The bound exists to catch a HUNG gate,
not a slow one. The `r388o` gate bundles the WHOLE app once per
frame it renders, so a run legitimately needs minutes. The bound is
now 600s in `r388o` / `r388q` / `r388r` / `r388s` / `r388p` — twice
the observed worst case, and still a bound.

## Lesson 1412 — "the anchor moved" is the honest half of the message

A mutation gate that fails with *"the anchor occurs 0 times, not
once"* is reporting a **stale gate**, not a broken product. The other
half of the message ("a mutation that lands on the wrong occurrence
proves nothing") is the rule; the first half is the diagnosis.

## Lesson 1413 — a gate that greps is a LOOKUP, and a lookup can be satisfied

`pending = null;` in a source file says nothing about whether the
group breaks. The gate now calls the rule and asserts on the runs.

## Lesson 1414 — "one algorithm, three callers" in a doc is an ASSERTION

The R389 doc claimed one grouping algorithm; the code had two. A doc
is a list of claims, and a claim that is not checked is a claim that
drifts.

## Lesson 1415 — a mutation test that edits the working tree is a landmine in BOTH directions

It can destroy uncommitted work, and it makes every other test's
result depend on the scheduler. Mutate a copy.

## Lesson 1416 — a shared MUTABLE path between two test files is a race

Give each sandbox a unique parent.

## Lesson 1417 — a needle must survive the formatter

`Tab to expand` → `Tab\s+to\s+expand`. The invariant is the phrase,
not the line's spelling.

## Lesson 1418 — a "right reason" needle is a needle too

`r388r`'s mutation battery does not only ask "did the gate go red?" —
it asks "did it go red for the reason this mutation breaks?". That
second question is answered by a REGEX over the gate's output, and the
regex is a literal like any other:

```js
// the regex said "the way the card does"
/does not group the turns the way the card does|…/
// the gate's message says "the way the rule says"
"r388r: the card's own grouping does not group the turns the way the rule says — got [1]"
```

So the mutation went red for **exactly** the right reason, and the
battery called it the WRONG reason. The needle was stale, not the
gate. It now matches the message's own words.

## Lesson 1419 — a timeout bound near the runtime is a coin flip, and its error message lies

`spawnSync(..., { timeout: 300_000 })` kills the child and returns
`status: null`. Any code that maps `null` onto a "the mutation did
not apply" sentinel reports a **killed gate** as a **failed regex** —
and sends the reader looking in the wrong place for an hour.

Two rules:

* **A bound must sit well above the observed runtime.** Twice the
  worst case measured is a bound; exactly the worst case is a coin
  flip. The observed worst case here was 302s against a 300s bound —
  so the bound was not a bound, it was a race.
* **The DURATION is the first diagnostic.** A failure whose message
  says "the thing was not found" but whose duration is exactly the
  timeout was killed, not missed.



