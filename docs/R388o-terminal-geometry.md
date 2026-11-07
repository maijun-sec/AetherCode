# R388o — the frame must fit the terminal, and the chrome must survive

> **Status**: implemented (geometry gate 15/15 green, console gate 2/2 green, mutation 8/8)
> **User report**: "整体页面渲染得不好看，框大小会变化，位置还会随意动，随着输入内容变化，整个页面感觉都在动"
> (the whole page renders badly — the box size changes, the position moves around, and as the input changes the whole page seems to move)

---

## 1. What the user saw

The screenshot (`cmd.exe`, the classic Windows console host) showed three
symptoms that look like three different bugs:

1. **~12 repeated header rows** — `⌬ MiniMax-M3 · ASK` / `AetherCode v0.2.1`
   stacked down the top of the screen.
2. **The input pill's right border cut off** — `[↵` instead of `↵]`.
3. **The status bar wrapped** — `… jar aethercode-cli-0.1.0-SNA` /
   `PSHOT.jar` on a second line.

Plus the complaint itself: the box size changes, the position moves, and
everything moves as the input changes.

---

## 2. What was actually wrong

**None of the three symptoms had the cause it suggested.** There were
**two** independent defects, and the second one was invisible to every
measurement taken before the Yoga tree was dumped.

### 2.1 The width overflow (the stacked headers)

The status bar's right-hand box carried `flexShrink={0}` — it never
shrank. On an 80-column terminal the whole row was **93 cells** wide.

```
the status bar's row is 13 cells wider than the terminal
        │        ▼ the terminal WRAPS an over-wide row → the frame gains a row
        │        ▼ the frame is now taller than stdout.rows
        │        ▼ Ink switches to its clearTerminal path: ESC[2J ESC[3J ESC[H
        │        ▼ the classic Windows console does not honour ESC[2J
        │        ▼ every render APPENDS a frame instead of replacing one
        │        ▼ TWELVE HEADERS
```

`ESC[2J` is the whole story. On a terminal that honours it the screen is
wiped and one frame is drawn. On one that does not, the frame is appended
below the previous one — and the screenshot shows exactly that.

### 2.2 The flex-basis squeeze (the missing header) — **the real one**

This is the defect the earlier "fix" missed. Pinning the root's height
to the terminal's rows was **necessary but not sufficient**.

Yoga gives a flex item a **BASE SIZE equal to its CONTENT**, and a flex
item only shrinks when the container overflows. So a transcript taller
than the terminal does **not** clip itself — it takes the rows and
**squeezes the chrome**.

Dumping the Yoga tree at 100×30 with a 12-turn transcript:

```
ink-root h=30 w=100
  ink-box h=1  w=100        ← the HEADER's column: ONE row
    ink-box h=0  w=100      ← brand row: height 0 (squashed to nothing)
      ink-text h=1 w=10    ← …but its TEXT still wants a row
    ink-box h=1  w=100      ← status strip: 1 row
    ink-text h=1  w=100     ← the rule
    ink-box h=0  w=100      ← cwd caption: height 0
  ink-box h=29 w=100        ← the transcript column: 29 of 30 rows
```

The header's outer box computed to **one row**. The brand row and the
cwd caption both computed to **height 0** — they were squashed out of
existence. The input pill showed only its **top border**; its content
and its bottom border were gone.

That is the user's screenshot: a bare rule where the header should be,
and a half-drawn input box.

### The fix

A flex-basis chain:

| element | before | after |
| --- | --- | --- |
| **the Scrollback's own column** | `flexGrow={1}` — base size = the transcript's own height | **`flexGrow={1}` + `flexBasis={0}` + `minHeight={0}` + `overflow="hidden"`** |
| the transcript's row + column wrappers in `tui.tsx` | `flexGrow={1}` | `flexGrow={1}` + `flexBasis={0}` + `minHeight={0}` |
| the header | (default) | `flexShrink={0}` |
| the status bar | (default) | `flexShrink={0}` |
| the input pill | `flexGrow={1}` | `flexShrink={0}` |

`flexBasis={0}` makes the transcript's base size **zero**, so it grows
into the rows the chrome leaves instead of taking them. `flexShrink={0}`
on the chrome says the opposite: the chrome keeps its rows whatever the
transcript does.

After the fix the same Yoga dump reads:

```
ink-box h=3  w=100  top=0   ← the header: 3 rows, at the top
ink-box h=3  w=100  top=1
ink-box h=1  w=100  top=3
ink-box h=26 w=100  top=4   ← the transcript: the rows that are left
```

### ★ WHICH OF THOSE CHANGES IS ACTUALLY LOAD-BEARING ★

The change set was measured by removing each change **in turn** and
rendering the frame again:

| removed | result |
| --- | --- |
| **the Scrollback's `flexBasis={0}`** | **BROKEN** — the pill loses its bottom border |
| the Scrollback's `minHeight={0}` | no effect |
| the Scrollback's `overflow="hidden"` (either) | no effect |
| the Scrollback's width expression | no effect |
| the row-budget slice | no effect |
| the header's `flexShrink={0}` | no effect |

**Exactly one change carries the fix.** The rest are cheap guarantees and
are kept — but the gate's mutation anchors the load-bearing one, because
a mutation that does not reproduce a defect is not a mutation
(Lesson 1375).

### ★ THE MEASUREMENT THAT FOUND THIS ★

The first bisect run said "removing `flexBasis={0}` changes nothing" —
and it was **wrong**. The reason: `flexBasis={0}` also appears in that
file's **comments**, and the removal regex matched the comment. **Every
"removal" was a no-op**, so the test measured the fixed code and
concluded the change was unnecessary.

The same class of error hit twice more:

- a variant that rewrote HEAD's box matched on `paddingRight={rightPad}`
  while HEAD says `paddingRight={leftPad}` — so the "HEAD + fix"
  variants were really "HEAD unchanged";
- a variant that measured the header ran while the **Scrollback** was
  still mutated from the previous variant, so it measured the wrong
  component.

→ **Lesson 1380 — a mutation that did not apply is indistinguishable
from a change that does not matter.** Every "removal" must be verified
by reading the file back.

---

## 3. The full change set

| what | before | after |
| --- | --- | --- |
| the status bar's right-hand box | `flexShrink={0}` — never shrank | natural size; the jar name is truncated up front |
| the status bar's rule | `"─".repeat(60)` | `"─".repeat(innerWidth)` |
| the header's rule | `"─".repeat(Math.max(60, cwd.length + 12))` | `"─".repeat(cols)` |
| the header's strip | every item always rendered | items dropped in priority order |
| the transcript's budget | `height={1000}` — a TURN count used as a ROW count | the rows the chrome leaves |
| the TUI root | `flexGrow={1}` and nothing else | `height={rows}` |
| the transcript's flex chain | content-sized base | `flexBasis={0}` + `minHeight={0}` |
| the chrome (header / pill / status) | shrinkable | `flexShrink={0}` |
| the input pill | grew with the text | capped at `INPUT_MAX_LINES` |
| the terminal's size | asked in FOUR places with FOUR fallbacks (120 / 80 / raw / caller) | `terminalGeometry.terminalSize()` — ONE source, ONE honest fallback (100) |
| the wide-char table | copied into `terminalGeometry.ts` | imported from `cellWidth.ts` |

### A separate defect the geometry work exposed

`InputBox` asked `process.stdout` for the terminal's width. That is
wrong in **any** render target that is not `process.stdout` — and it is
also wrong for the *reason* this round exists: the width is a fact the
caller already has. The width is now a **prop** (`width={cols}`), with
`terminalSize().cols` as the fallback. Without it, a 40-column
terminal got a 99-cell input pill.

---

## 4. The gate

`scripts/test/r388o-geometry.test.mjs` — **15 checks**, all green:

1. the frame is never wider than the terminal (40/50/60/80/100/120/160)
2. the frame is never taller than the terminal (12/20/30/50)
3. the frame's height does not change as the input grows (including
   **newlines** — an 8-line input must not add a row)
4. the status bar does not wrap onto a second row
5. the status bar's state label is never clipped away
6. the status bar shows its full set of items when there is room
7. the header's identity is never clipped away
8. the header shows its full set of items when there is room
9. the input pill's borders are drawn on every row
10. **the chrome survives a LONG transcript** (40 turns)
11. **the transcript's flex chain cannot squeeze the chrome**
12. **the transcript's row budget is BOUNDED**
13. **the status bar's items do not OVERLAP each other**
14. **the input pill shows at most `INPUT_MAX_LINES` lines**
15. there is exactly ONE wide-char table in the TUI

### ★ The probe renders the REAL components ★

The probe imports the real `Header`, `StatusBar`, `InputBox` and
`Scrollback`, lays them out in the product's tree, and renders them
through Ink into a stream whose `columns`/`rows` it controls.

It does **not** compute its own transcript budget: it reads
`const transcriptBudget = …` and the `<Scrollback height={…} />` usage
**out of `src/tui.tsx`**, so a mutation of the product's budget is in
front of the gate.

### ★ The measurement knows the terminal's width ★

`reconstructScreen(raw, cols)` folds an over-wide row the way a real
terminal does. The gate ALSO measures the frame's **own** line widths
(before folding), because that is the ground truth: a line longer than
the terminal is a line the terminal will wrap.

### ★ The gate runs the SHIPPED bundle in a REAL console too ★

`scripts/test/r388o-console.test.mjs` — **2 checks**. It runs
`dist/ac-tui.js` (the bytes the user gets) inside
`conhost.exe --headless`, which is a real TTY, and asserts on what the
console received: the `ESC[2J` count is at most 1 (a frame that fits
never takes Ink's clearTerminal path), the header is there, the input
pill's **right border** is there (the user's screenshot showed `[↵`
instead of `↵]`), and the status bar is there.

It is **skipped**, not failed, when the bundle has not been built or
`conhost.exe` is not where it is expected.

---

## 5. The mutation test

`scripts/test/r388o-geometry-mutation.test.mjs` — **8 mutations**, all
detected. A gate that cannot fail proves nothing (Lesson 1219).

| # | mutation | catches |
| --- | --- | --- |
| 1 | ~~the status bar's right box stops shrinking~~ | **not a defect** — asserted to stay GREEN (see below) |
| 2 | the status bar stops dropping its items | the state label's context disappears |
| 3 | the header stops dropping its items | the model name is cut in half |
| 4 | **the jar name stops being truncated** | **the row overflows (measured: 71 cells in a 40-column terminal)** |
| 5 | the transcript's row budget goes back to 1000 | the budget is no longer bounded |
| 6 | **the input pill's height cap is removed** | **the pill shows 8 rows instead of 3** |
| 7 | **the Scrollback's flex basis loses its zero** | **the header and the pill are squeezed out** |
| 8 | **the input pill stops using the width it is given** | **a 99-cell pill in a 40-column terminal** |

The mutated tree lives in the OS temp directory (a junctioned
`node_modules` resolves back to the original tree — Lesson 1373), and
the gate prints `probe read the MUTATED tree` as its evidence.

### ★ MUTATION #1 IS NOT A DEFECT ★

The status bar's right-hand box used to carry `flexShrink={0}` and
never shrank, and the overflow was blamed on it. Measured, by turning
each change off in turn:

| turned off | result |
| --- | --- |
| the jar name is not truncated | **OVERFLOW** — 71 cells in a 40-column terminal |
| the right box never shrinks | OK |
| both | **OVERFLOW** |

**The overflow was never the `flexShrink`.** The jar name's LENGTH was,
and the fix was to truncate it UP FRONT to a measured budget
(`jarBudget`). With the name truncated, the box's natural width always
fits, and whether it may shrink is irrelevant.

The first draft of this file asserted that this mutation makes the gate
red. It does not — and the honest response to that is to **remove the
mutation**, not to bend the gate until it agrees. The test now asserts
that the gate stays GREEN, and fails loudly if that ever changes
(it would mean the mutation IS a defect and the premise is wrong).

→ **Lesson 1375**.

### ★ THE ANCHORS MUST TARGET THE CODE, NOT THE PROSE ★

Three of the eight mutations initially stayed GREEN — and the reason was
the same each time: **the anchor matched a COMMENT or a DECLARATION
rather than the code that does the work**.

- `flexBasis={0}` is written in `Scrollback.tsx`'s comments, and a
  `replace(/flexBasis=\{0\}/)` hits the comment and leaves the code
  alone;
- `const drop = new Set<string>();` → `... void drop;` changes nothing:
  the `drop.add(...)` calls below it still run, so the items are still
  dropped. The anchor has to be the CALL;
- the row budget's contract is on its VALUE, not on the frame: once the
  transcript's column carries `flexBasis={0}`, the frame's height no
  longer depends on the budget, so the gate asserts on the number.

→ **Lesson 1380 — read the file back after every mutation. A mutation
that did not apply is a silent no-op, and the conclusion it supports
("this change does not matter") is exactly the conclusion that must
never be reached without evidence.**

### ★ TWO CONTRACTS THE FRAME-HEIGHT CHECK CANNOT SEE ★

Two of the eight mutations are invisible to any check that looks at the
FRAME, because the flex chain makes the frame's height the TERMINAL's
height whatever those two do:

- **the row budget** — the contract is on its VALUE (a turn count used
  as a row count is unbounded layout work), not on the frame;
- **the input pill's height cap** — the contract is on the PILL (it must
  show at most `INPUT_MAX_LINES` lines and scroll the rest), not on the
  frame.

Both needed their own check, and both are now measured from the
product's own values (`terminalGeometry.INPUT_MAX_LINES` is imported by
the gate, not hard-coded).

### ★ A mutation that does not reproduce a DEFECT is not a mutation ★

The first draft also mutated the header's and the status bar's rules
back to a fixed 60 cells, and asserted that the gate went red. It did
**not** — and it should not have. A rule is a `<Text>` inside a Box
that Yoga already sized, and a `<Text>` **wraps**: a 60-cell rule
inside an 80-cell Box occupies 60 cells of ONE row. It never
overflows.

Asserting that the gate goes red for a change that was never broken
tests nothing, and the "failure" it reports is noise that hides a real
one. → **Lesson 1375**.

---

## 6. Verification in the REAL bundle

The shipped bundle (`dist/ac-tui.js`, 2405.5 KB) run inside a real
Windows console host (`conhost.exe --headless`), at 120×30:

```
clearTerminal (ESC[2J) count : 1        ← the frame fits → in-place redraw
final frame:
  ⌬ MiniMax-M3 ·  ASK ⏸ manual mode on            default · ● · now
  ──────────────────────────────────────────────────────────────────
  ↳ .../AetherCode/aethercode-tui
                    Start a conversation…
  ╭────────────────────────────────────────────────────────────╮
  │ ▌ › type a prompt — Enter to send, shift+Enter for newline… ││
  │                                                            ↵ ││
  ╰────────────────────────────────────────────────────────────╯
  ──────────────────────────────────────────────────────────────
  ▌ ● ✓ ready   · ctx [░░░░░░░░░░] · in — · out — · — · — · jar aethercode-0.1.0-SNAPSHOT.jar
```

The pill's **right border is there** (`↵ ]`), the status bar is on
**one row**, and the header is **whole**.

---

## 7. Lessons

- **Lesson 1364 — the shape of a symptom is not its cause.** "The page
  moves" looked like a height problem; the height was fine. The width
  was wrong, and then the flex chain was wrong.
- **Lesson 1365 — the coverage of a contract is part of the contract.**
  The first gate tested 80/100/120 columns. A hard-coded 60-cell rule
  FITS at 80 and only overflows below 60, so the mutation stayed green
  and the gate was blind to a whole class of defect. 40 is the floor.
- **Lesson 1366 — a fix that is necessary is not thereby sufficient.**
  Pinning the root's height was correct, and it did not fix the missing
  header. "The frame fits" and "the chrome survives" are two different
  contracts and need two different checks.
- **Lesson 1367 — a fixed `height` on a flex child steals its siblings'
  rows.** The input pill and the status bar disappeared entirely.
- **Lesson 1368 — squeezing is not truncating.** `flexShrink` makes Ink
  shrink the BOX; the Text nodes inside it then overlap each other
  (measured: `jaaethercode-cli-0.1.0-SNAP`). A mangled row is worse
  than a truncated one.
- **Lesson 1369 — the truncation budget must be MEASURED.** An
  estimated jar-name budget was 8 cells wrong and overflowed silently.
- **Lesson 1370 — on a narrow terminal, DROP items; do not squeeze
  them.** A dropped item is absent. A squeezed item is WRONG.
- **Lesson 1371 — a probe must not render a crash page.** Ink's
  `useInput` throws when stdin is not a TTY, and Ink renders the error
  as the frame. The probe was measuring the error page and calling it
  "the status bar is missing".
- **Lesson 1372 — a probe must not reproduce the defect it looks
  for.** A probe that renders its own mirror of the layout cannot see
  a defect in the product's layout.
- **Lesson 1373 — a path that traverses a junction resolves to the
  original.** The mutation's copy must live outside the package.
- **Lesson 1374 — the measurement must know what it measures.**
  `reconstructScreen` used to swallow over-wide rows; the gate now
  folds them AND measures the frame's own widths.
- **Lesson 1375 — a mutation that does not reproduce a DEFECT is not a
  mutation.**
- **Lesson 1376 — the probe must not MIRROR the product.** The probe
  now renders the real components in the real tree and reads the real
  expressions out of `src/tui.tsx`.
- **Lesson 1377 — Yoga's BASE SIZE is the CONTENT.** A flex item's base
  size is its content's height, so a tall transcript does not clip
  itself — it takes the rows from its siblings. `flexBasis={0}` +
  `minHeight={0}` is the chain that makes the content yield instead.
- **Lesson 1378 — a component that asks `process.stdout` for the size
  is wrong in every render target that is not `process.stdout`.** The
  width is a fact the caller already has; pass it as a prop.
- **Lesson 1379 — dump the layout tree, do not reason about it.** Ten
  rounds of "the height is fine / the width is the problem" reasoning
  missed the flex-basis defect completely. Thirty lines of Yoga dump
  found it in one run.
- **Lesson 1380 — a mutation that did not apply is indistinguishable
  from a change that does not matter.** The `flexBasis={0}` removal
  regex matched the file's own **comment** about `flexBasis={0}`, so
  every "removal" was a no-op and the measurement said "this change
  does not matter". It was the load-bearing one. Two more variants
  failed the same way (an anchor on `paddingRight={rightPad}` while
  the file said `paddingRight={leftPad}`; a variant that measured the
  header while the Scrollback was still mutated from the previous
  variant). **Every removal must be verified by reading the file back,
  and every variant must start from the restored baseline.**
- **Lesson 1381 — a defensive prop that is not load-bearing is still
  worth keeping, but the GATE must anchor on the load-bearing one.**
  Seven of the eight changes in this round are cheap guarantees; only
  the Scrollback's `flexBasis={0}` carries the fix. A gate anchored on
  a non-load-bearing prop goes green forever and proves nothing.
