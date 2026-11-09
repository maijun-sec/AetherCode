# R388s — the rewind menu shows what restoring a point would DO, before you choose it

**Round:** R388s
**Surface:** AetherCode TUI (Ink) + the daemon's checkpoint surface

---

## 1. The defect

The rewind menu (reached with `/rewind` or `Esc Esc`) let the user pick a point and an
action. What it did **not** do was tell the user what a point would undo.

The file list was at the **bottom** of the panel, after the action list, and it showed
only the **selected** point. So:

- the preview was only visible **after** the user had moved the selection onto a point —
  but the preview is what helps them **choose** a point;
- and what it showed was *which files the session touched*, not *what a restore would do*.

The daemon had everything needed. `CodeCheckpointStore` keeps a **full copy of every
file's content** at each checkpoint (`FileState.content`) — the "before" state. But
`checkpoints/list` put only the file **names** on the wire, so the renderer could not
even see the other half of the comparison.

---

## 2. The design, and the one thing that was NOT done

The useful preview is a **diff**: checkpoint-content vs the file as it is now.

**A diff was not implemented, and that was deliberate.** A diff needs a line-matching
algorithm. The daemon already has one — `org.aethercode.code.DiffUtils` — but the TUI is
TypeScript and cannot import it. Porting the LCS to TS would create **two
implementations of one algorithm in two languages**, which is the shape that drifts
(Lesson 1207's shape, one step further out).

So the preview reports the honest, computable fact instead: **how many LINES differ**
between the snapshot and the file now. The label says "lines", not "diff", because it
is not one.

> If a real diff is wanted later, the daemon must own it: send the hunks on the wire.
> Do not grow a second diff engine in the renderer.

---

## 3. What was built

### 3.1 The daemon computes it — it is the only place that can

`CodeCheckpointStore` holds **both sides** of the comparison: the snapshot it took at
that point, and the live file on disk. So:

```java
public List<FilePreview> preview(Checkpoint cp);
public record FilePreview(String name, int linesNow, int linesDelta, boolean created, String note);
```

The engine exposes it (`previewCodeCheckpoint(id)`), and `checkpoints/list` puts it on
the wire:

| field | meaning |
| --- | --- |
| `preview` | the per-file facts |
| `changedFiles` | how many files a restore would change |
| `changedLines` | how many lines, summed |
| `previewComplete` | is every file's preview known? |

### 3.2 ★ "UNKNOWN" IS NOT ZERO ★

A binary file, an unreadable path, a file that is now a directory — the restore
**skips** those. So the honest answer is *"I do not know"*, and `FilePreview` carries a
`note` saying why.

A menu that said "0 lines" for a file it could not read would be handing the user a
number it does not have — and the user may act on it.

### 3.3 The footprint goes ON THE ROW

The preview is what helps you **choose** a point, so it cannot live in a pane that only
appears after the choice. Every row now carries its own:

```
 ▶ #12  refactor the checkpoint store
      3 files · ~12 lines
   #11  add the preview RPC
      1 file · ~2 lines
   #10  earlier work
      no change
```

The footprint is on **its own line** under the summary: the summary is the user's own
words and can be long, and putting the two on one line would truncate one of them.

### 3.4 The detail pane says what a restore would DO

```
 what restoring this point would do:
   a.ts — ~12 lines differ (now 40)
   b.ts — unchanged
   logo.png — unknown (not restorable)
   new.ts — would be deleted (30 lines)
```

### 3.5 ★ THE COUNTING RULE IS PINNED ON BOTH SIDES ★

The daemon computes the numbers and the renderer displays them, so the **rule for
counting lines** has to be the same rule in both languages — or the user sees two
different numbers for the same file.

The rule: an empty string is **zero** lines, and a trailing newline does not start a
new one — so `"a\n"` is **one** line, not two. Both sides implement it, and the gate
pins **both** (and pins the daemon's CRLF normalisation, so a Windows file does not
count differently from the TUI's count).

---

## 4. The gate

### 4.1 `r388s-checkpoint-preview.test.mjs` — 9 assertions, all MEASURED

The gate renders the **real** menu and reads the **frame**. A grep for `linesDelta`
proves the string is in the source; it proves nothing about what the terminal shows.

It measures:

1. **every** point shows its own footprint — and the other two points' numbers are not
   standing in for it;
2. a point with **no** preview shows **no zero**;
3. a point where nothing would change says **"no change"** in words;
4. an **incomplete** preview says so — "no change" must not hide what we cannot tell;
5. the detail pane says what would **happen**, per file;
6. an unknown file says **unknown** — never "unchanged";
7. a **created** file says it would be **deleted**;
8. the renderer and the daemon **count lines the same way** (both pinned);
9. the gate is **not vacuous** — with no preview, the footprint is absent.

### 4.2 ★ THE MUTATION GATE HIT THE R388r LESSON AGAIN ★

The first version of the mutation gate had the sandbox's `node_modules` hardcoded into
the gate's probe builder:

```js
require(join(ROOT, "node_modules", "esbuild"))   // ← the SANDBOX's, which does not exist
```

So the probe could not build — `Cannot find module '...\node_modules\esbuild'` — and
**every** mutation went red **on a build failure**. A gate red on a build error is red
on any change, including a correct one: it had a tripwire, not teeth.

This is the **same defect** R388r found one round earlier, in a different file. The
fix is the same one: the builder's location comes from the environment, and the caller
hands over the **real** tree's path.

> **Lesson 1404 — ★ A sandbox without `node_modules` turns every mutation into a build
> failure ★ — and a build failure is a red that carries no information. The gate must
> take the builder's location from the environment, exactly as it takes `nodePaths`.**

---

## 5. Lessons

- **Lesson 1404 — a sandbox without `node_modules` turns every mutation into a build
  failure.** And a build failure is a red that carries no information. The gate must
  take the builder's location from the environment.
- **Lesson 1405 — a preview that only appears AFTER the selection cannot help the
  selection.** The preview's job is to inform the choice, so it belongs on the row.
- **Lesson 1406 — "unknown" is not zero.** A file the daemon cannot read is skipped by
  the restore, so the honest answer is "I do not know". A zero is a number the user may
  act on.
- **Lesson 1407 — ★ A diff in two languages is two truths ★.** The daemon had a diff
  engine and the renderer could not import it, so the alternative was a SECOND one in
  TypeScript. The preview reports line counts instead — the computable fact — and says
  "lines", not "diff". When a real diff is wanted, the daemon must own it and send the
  hunks.
- **Lesson 1408 — a rule that two languages both implement must be PINNED on both
  sides.** The daemon computes the numbers and the renderer displays them; a
  disagreement about how to count lines would show the user two numbers for one file.

---

## 6. Files

| file | what changed |
| --- | --- |
| `aethercode/aethercode-core/.../CodeCheckpointStore.java` | `preview(Checkpoint)` + the `FilePreview` record + the shared counting rule |
| `aethercode/aethercode-sdk/.../AetherCodeEngine.java` | `previewCodeCheckpoint(id)` |
| `aethercode/aethercode-protocol/.../AetherCodeMethods.java` | `checkpoints/list` carries `preview` / `changedFiles` / `changedLines` / `previewComplete` |
| `aethercode-tui/src/checkpointPreview.ts` | **new** — the preview's shape and its wording |
| `aethercode-tui/src/components/CheckpointMenu.tsx` | the footprint on every row; the detail pane says what would happen |
| `aethercode-tui/src/tui.tsx` | the row normaliser reads the preview |
| `aethercode-tui/scripts/test/r388s-checkpoint-preview.test.mjs` | the gate — 9 measured assertions |
| `aethercode-tui/scripts/test/r388s-checkpoint-preview-mutation.test.mjs` | the mutation gate — 7 mutations + a green baseline + a "right reason" check |
