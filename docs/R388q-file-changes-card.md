# R388q — the FILE-CHANGES card: one card for the turn, with the counts that answer the question

**Round:** R388q
**Surface:** AetherCode TUI (Ink)
**Trigger:** the user supplied a screenshot of the desktop's end-of-turn card and said
「我希望 TUI 和这个框展示类似，大差不差，不要随便几个线条就给糊弄过去了」

---

## 1. The defect

The desktop's card says one thing:

```
╭────────────────────────────────────────────────────────────────────╮
│ ⊕  已编辑 19 个文件  +2235 -36   │  查看文件        [撤销] [审查]   │
╰────────────────────────────────────────────────────────────────────╯
```

Seven parts, each carrying information: an icon in its own cell, a bold title whose
headline is the **file count**, the diff counts in colour (green `+N`, red `-N` — the only
meaningful colours in the card), a separator, a muted link, and two pressable actions.

The Ink TUI had **no such card**, and — the reason this round exists — it could not have
one: **every tool card was a nearly empty box.**

### The root cause is a FIELD NAME, not a style

The daemon's `eventToMap` (`AetherCodeMethods.java`) builds the wire map like this:

```java
} else if (ev instanceof StreamEvent.ToolUseStart tu) {
    r.put("type",  "tool_use_start");
    r.put("id",    tu.id());
    r.put("name",  tu.name());
    r.put("input", tu.input());          // ← "input"
} else if (ev instanceof StreamEvent.ToolResult tr) {
    r.put("type",    "tool_result");
    r.put("id",      tr.id());
    r.put("content", tr.content());      // ← "content"
    r.put("isError", tr.isError());
}
```

The Ink renderer read **`ev.args`** and **`ev.result`** — fields the daemon **never
sends**. The line renderer (`src/line.ts`) read `ev.input` correctly, which is why only
the Ink path was broken.

Measured — the daemon's real events fed to the real `ToolCard`:

```
│ - ✎ file_write                       13ms  ·  Tab: collapse  ·  d: details │
```

No path. No arguments. No result. No diff counts. A near-empty box.

★ This is the same shape as R383's lesson on the other side of the wire: **the verdict
must come from the daemon, and so must the data.** A client that re-parses a decorated
string writes its own parser, and every parser is wrong.

---

## 2. What was built

### 2.1 `src/fileChanges.ts` — the counts, derived from the tool's own input

A pure module (no React, so a gate script can import it):

| function | what it answers |
| --- | --- |
| `countLines(s)` | how many lines a string spans |
| `diffStatsFor(tool, input)` | `{added, removed}` for ONE tool call, or `null` |
| `fileChangeFor(tool, input)` | the `FileChange` (path, name, counts, tool) |
| `summarizeFileChanges(changes)` | the aggregate: file count + summed counts |

★ **Why the counts are computed client-side.** The daemon already has diff machinery
(`ToolWidgets.DiffStats`, `DiffRender`) — but it serves the **line renderer's** approval
widget, not the Ink transcript. The Ink TUI already receives the tool's `input` map on the
wire, and that map carries exactly what the counts need:

- `file_write` — `content` is the whole new file ⇒ additions are its line count;
- `file_edit` — `old_string` is what is removed, `new_string` is what is added.

★ **And the counts come from the tool's own input, not from the file on disk.** The file
may have changed since; the input is what the tool actually did.

★ **`null` ≠ "zero changes".** A bash command that touched nothing is not a file change;
a file change with no line movement still is one.

★ **`countLines("")` is 0, not 1.** `"".split("\n")` yields `[""]` — one empty line —
which would report a write of an empty file as `+1`.

### 2.2 `src/components/FileChangesCard.tsx` — the card

```
╭────────────────────────────────────────────────────────────────╮
│ ✎ 已编辑 19 个文件 +3097 -14 │  查看文件 f  [撤销 u] [审查 r]  │
╰────────────────────────────────────────────────────────────────╯
```

**★ ONE CARD FOR MANY FILES.** Nineteen files, ONE card. A card per file would be a wall
of noise and would not answer "what did this turn change?" — the per-file detail lives
behind the expander.

**★ ROUNDED BORDERS.** `borderStyle="round"`, like the input pill and the markdown
blocks — the app's idiom for a FRAMED SURFACE. A square frame reads as a table cell.

**★ THE SEVEN PARTS**, each measured by the gate:

| part | form |
| --- | --- |
| icon | `✎` in its own cell, in the write colour |
| title | bold `已编辑 19 个文件` — the count is the headline |
| counts | green `+N`, red `-N` — the only meaningful colours |
| separator | `│` |
| link | muted `查看文件 f` |
| actions | `[撤销 u]` and `[审查 r]` |

### 2.3 The renderer — both spellings accepted

`tui.tsx` now reads `ev.input ?? ev.args` and `ev.content ?? ev.result`. A daemon that
predates the fix sends the other spelling; a card that shows nothing is worse than one
that shows the wrong field.

### 2.4 `Scrollback.tsx` — the grouping

Consecutive file-changing tool turns collapse into ONE card. A **non**-file turn
(a bash command) **breaks** the group — otherwise a bash command in the middle of a batch
of writes silently merges the writes on either side of it into one summary, and the
summary claims to be one batch when it is two.

---

## 3. ★ THE THREE MEASURED DEFECTS ★

Every one of these was found by MEASUREMENT (rendering the real component and reading
the frame), not by reading the source.

### 3.1 A bordered Box cannot be inlined — the card was 4 rows

The desktop draws its two actions as **outlined boxes**. A terminal cannot: a bordered
Ink Box occupies **two** rows (a top border row and a bottom border row), so putting one
inside a row makes the row two rows tall and the card four.

Measured, width 96:

```
│ ✎ 已编辑 19 个文件 +3097 -14 │  查看文件                             ╭──────────╮ ╭──────────╮ │
│                                                                      ╰ 撤销 u   ╯ ╰ 审查 r   ╯ │
```

Four rows for one line of information.

★ **Fix:** the actions are **single-line bracket labels** — `[撤销 u]` — which is the
app's existing idiom for an option (the permission modal writes `1 [A] allow this call
once`). The key is part of the label, so the action is still driveable with the keyboard
alone, and the card is back to three rows: border, content, border.

### 3.2 The budget was measured in CHARACTERS — the counts were truncated

The title is CJK: `已编辑 19 个文件` is **10 characters but 15 cells**. The width budget
used `title.length`, so it thought the row was 5 cells wider than it was. The row
overflowed, Ink wrapped it, and the counts were clipped.

Measured, width 40, before the fix:

```
│ ✎已编辑 19 个文件 +3097 -1 │  查看文件  │
```

★ **`-14` became `-1`.** A card that says `+3097 -1` where the truth is `+3097 -14` is
**worse than no card at all** — the counts ARE the answer.

★ **Fix:** every width in the budget is measured with `cellWidth` (the app's ONE width
rule, R388m), and the parts are dropped in **priority order**: the actions first, then
the link, and the title's tail only as a last resort. The counts are never truncated.

### 3.3 The narrow card dropped the link too late

The same character-vs-cell bug made the card keep a link it had no room for, so the row
wrapped and the card grew to four rows.

Measured, 11 widths, after the fix:

| width | rows | border cells | mismatched rows |
| --- | --- | --- | --- |
| 96 | 3 | 98 | 0 |
| 80 | 3 | 82 | 0 |
| 60 | 3 | 62 | 0 |
| 50 | 3 | 52 | 0 |
| 45 | 3 | 47 | 0 |
| 40 | 3 | 42 | 0 |
| 35 | 3 | 37 | 0 |
| 30 | 3 | 32 | 0 |
| 24 | 3 | 26 | 0 |

★ **Every row's cell count matches the border's at every width** — the card never
overflows, and `-14` survives at all of them.

---

## 4. The gate

`scripts/test/r388q-file-changes.test.mjs` — 13 assertions.

★ **The gate RENDERS the real component and measures the frame.** A grep for
`borderStyle="round"` proves that the string is in the source; it proves nothing about
what the terminal shows. So the gate renders `FileChangesCard` through Ink and measures
the rows, the cells and the colours.

★ **The measurement is in CELLS, not characters** — the gate imports the same width
rule the renderer uses, because a character-based check cannot see an overflow.

The gate pins:

1. the SEVEN parts, measured in the rendered frame;
2. the frame is ROUNDED;
3. the card is THREE rows at eight widths;
4. every row's cells match the border's at nine widths;
5. the counts are never truncated at nine widths;
6. a card with no files renders NOTHING;
7. NINETEEN files make ONE card (at 1, 3 and 19 files);
8. the expansion lists the per-file detail;
9. the renderer reads the daemon's REAL field names — **on both sides**: the daemon's own
   `r.put("input", …)` chain AND the renderer's read, so a rename on either side turns
   the gate red instead of the cards going blank;
10. the counts are derived from the tool's own input;
11. an empty file is zero lines;
12. the Scrollback groups the turns, and a bash turn breaks the group.

### 4.1 The mutation gate

`scripts/test/r388q-file-changes-mutation.test.mjs` — ★ **a gate that cannot go red is
not a gate.** Each of **12** mutations breaks one defect in a **copy** of the sources in
the OS temp directory, and the gate must go red for each.

The mutations: square frame, removal count dropped, addition count dropped, link removed,
undo action removed, review action removed, separator removed, title dropped, budget
measured in characters again, the renderer reading `ev.args` again, an empty file
counting as one line again, and the group no longer broken by a non-file turn.

★ **And the red must be for the RIGHT reason** — the last test asserts the output
mentions the rounded-frame assertion, not a crash. A gate that dies on a build error
proves nothing about the assertion it was supposed to make.

★ **Every mutation is RE-READ after it is applied.** A `replace` that matches nothing
returns the original string, so the "mutation" measures the UNMUTATED code — which
passes, which looks like "the gate has no teeth", and is a statement about the mutation
and not about the gate.

★ **The anchor must be UNIQUE.** A needle that is not unique lands on the wrong
occurrence and pretends to have tested something (R381's Lesson 1217). The mutation
helper asserts the anchor occurs exactly once before it mutates.

★ **The sandbox must MIRROR the real layout.** The gate computes its root as `../..`
from its own file, so the copy must keep the `scripts/test/` shape or the gate reads a
different tree than the one it was given. The sibling Java tree comes along too, because
the gate reads the daemon's own `AetherCodeMethods.java` to pin the wire contract.

★ **The bundle is SELF-CONTAINED.** Ink needs ESM (bundling it to CJS breaks on
`require("assert")` inside its own dependencies), but a fully bundled ESM output fails at
run time with "Dynamic require of X is not supported" — esbuild rewrites `require` to a
stub. The fix is the documented interop shim:

```js
banner: { js: "import { createRequire as __cr } from 'node:module';const require = __cr(import.meta.url);" }
```

★ **A LITERAL ESCAPE BYTE IN A SOURCE FILE IS INVISIBLE.** The frame boundary is the
last `ESC[2K` in the raw stream, and the first version of the gate wrote that as a
literal byte — which editors, formatters and copy-paste eat. The file then contained the
text `[2K`, the boundary never matched, and every measurement was taken against the
whole accumulated stream. The escape is now written as ``.

### 4.2 ★ THE MUTATION GATE CAUGHT A VACUOUS ASSERTION ★

★ **This is the whole reason the mutation gate exists.** The first run of the mutation
gate was **11 of 12**: the mutation that removed the **separator** left the whole gate
**green**.

The reason: the separator assertion was

```js
assert.match(row, /│/, "the card has no separator");
```

where `row` is the content row **including its border columns**. The card's own frame
puts a `│` at both ends of every row — so the assertion was satisfied by the **BORDER**,
and it stayed green with the separator deleted. ★ **A check that a LOOKUP can satisfy is
no check at all** (R381's Lesson 1217, in a new costume): here the "lookup" was the
frame.

The fix measures the row's **INTERIOR** — the border columns removed — and asserts the
separator **positionally**: it must sit *after the counts* and *before the link*, which
is what "a separator between the counts and the actions" means. The border's `│`
columns are at the two ends of the row, so they cannot satisfy a positional assertion.

★ **A green gate is not evidence. A gate that has been shown to go red is.**

---

## 5. Lessons

★ **Lesson 1390 — a client that re-parses the wire writes its own parser, and the
parser is wrong.** The Ink renderer invented the field names `args` and `result`; the
daemon sends `input` and `content`. Nothing failed loudly: the cards simply came out
empty. **A field name that no server sends is a silent, total loss of information.**
The gate now pins the contract on BOTH sides, so a rename on either one turns it red.

★ **Lesson 1391 — a terminal cannot inline a bordered Box.** A bordered Ink Box occupies
two rows, so "an outlined button inside a row" is not a styling choice — it is
impossible. The actions became single-line bracket labels, which is the app's existing
idiom for an option. ★ **Before drawing a desktop widget in a terminal, ask what the
terminal's primitives can actually do.**

★ **Lesson 1392 — a width budget measured in CHARACTERS is a width budget that is
wrong.** `已编辑 19 个文件` is 10 characters and 15 cells. The 5-cell error overflowed
the row, Ink wrapped it, and the diff counts were clipped to `-1`. ★ **CJK text breaks
every `String.length` budget.** The app has ONE width rule (`cellWidth`, R388m) and the
budget must use it — and so must the gate, or the gate cannot see the defect either.

★ **Lesson 1393 — the counts are the answer, so the counts are what survive.** When the
row does not fit, the parts are dropped in **priority order**: the actions, then the
link, and the title's tail only as a last resort. ★ **A layout that truncates its
headline is worse than a layout that drops its decorations.**

★ **Lesson 1394 — a formatter reflow can silently break a gate's anchors.** Prettier
reformatted the card and the gates in this round. The mutation anchors are exact
whitespace strings, and a reflow can break one — leaving a mutation that does not
apply, which measures the unmutated code and reports "the gate has no teeth". The
anchors were re-verified after formatting (all 12 still unique), and the mutation helper
now asserts uniqueness itself. (This is R376's lesson applied a second time.)

★ **Lesson 1395 — a gate that dies on a build error is red for the wrong reason.** The
sandbox copy had to carry the whole `src/` tree (the card imports `../theme.js` and
`../cellWidth.js`) and the sibling Java tree (the gate reads the daemon's own map), or
the probe died on a missing file — and "red" would have meant "the harness is broken",
not "the assertion caught the defect". The last mutation test asserts the red output
names the assertion that failed.

★ **Lesson 1396 — the frame can satisfy an assertion about its own CONTENT.**
The "the card has a separator" check was `assert.match(row, /│/)`, where `row` was the
content row *including its border columns* — and the frame puts a `│` at both ends of
every row. So the assertion was satisfied by the **BORDER**, and the mutation that
deleted the separator left the gate **green**. It was the **mutation gate** that caught
it (11 of 12 on the first run). ★ **A check that a LOOKUP can satisfy is no check at
all** — and the frame is a lookup the assertion never meant to consult. The fix measures
the row's **INTERIOR** and asserts the separator **positionally** (after the counts,
before the link), which the border's end-of-row bars cannot satisfy.
★ **A green gate is not evidence. A gate that has been SHOWN to go red is.**

---

## 6. Files

| file | change |
| --- | --- |
| `src/fileChanges.ts` | NEW — the diff counts and the aggregation |
| `src/components/FileChangesCard.tsx` | NEW — the card |
| `src/components/Scrollback.tsx` | the grouping; the card replaces the per-tool cards |
| `src/tui.tsx` | `ev.input ?? ev.args`, `ev.content ?? ev.result` |
| `scripts/test/r388q-file-changes.test.mjs` | NEW — the gate (13 assertions) |
| `scripts/test/r388q-file-changes-mutation.test.mjs` | NEW — the mutation gate (12 defects) |
