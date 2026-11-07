# R388j — one frame, one width contract, and a lesson about measuring

**Round:** R388j
**Date:** 2026-11-07
**Touches:** `aethercode-tui/src/components/PanelBody.tsx` (new),
`aethercode-tui/src/components/{MemoryPanel,ThreadSelector,AgentSelector,EffortPicker,ModePicker,CwdSwitcher}.tsx`,
`aethercode-tui/src/tui.tsx`, `aethercode-tui/src/state.ts`,
`aethercode-protocol/.../AetherCodeMethods.java`

---

## 1. What the user asked for

> 对，我们 tui 就是 desktop 中间那部分，左侧和右侧都是可以通过 command 切换，
> 比如 desktop 左侧的 session，通过 `/sessions` 就可以显示当前 cwd 的全部历史
> session 等，而且显示要优美，字体大小合适

Three requirements:

1. the TUI **is** the Desktop's centre panel;
2. the Desktop's **left and right surfaces** must be reachable by command
   (`/sessions` → the session rail);
3. the display must be **beautiful**, with an appropriate font size.

## 2. The double frame

`PanelShell` draws the panel's frame. Six panel components ALSO drew one of
their own (`borderStyle="double"`), so the screen showed two nested boxes:

```
╭────────────────────────────────╮
│ ◆ Threads          esc         │
│ ╔════════════════════════════╗ │
│ ║║                          ║ │   ← the border cells DOUBLED
│ ║  ◆ Sessions in AetherCode  ║ │   ← the title ran INTO the border
│ ╚════════════════════════════╝ │
╰────────────────────────────────╯
```

Four visible defects, one cause: two components owned the same frame.

**The rule:** ★ THE SHELL OWNS THE FRAME. THE PANEL OWNS THE CONTENT. ★

A component that is a **card** (a tool card, a permission card, the stop
banner) may still draw its own box — that is the point of a card, and it is
why a card reads as an exception to the flat transcript.

`PanelBody.tsx` is the new shared body: no frame, a width contract, and
`fitRow` — the one place that pads a row to the width or truncates it with
an ellipsis.

## 3. The width contract

```
panelWidth      = min(cols - 4, 100)     // the frame
inner           = panelWidth - 4        // between the border columns
panelContentWidth = panelWidth - 4      // the SAME number, passed down
```

The width is **derived once**, in the host, and handed to every panel as a
prop. A panel that guessed its own width would be right exactly until the
terminal was resized.

## 4. `/sessions` — the scope has to be honest, and it has to have a way out

`/sessions` is scoped to the current directory. A directory that has never
had a session in it shows an empty list — and an empty list that says
"no sessions found" is a **lie**: 50 sessions exist, 48 of them just have
no recorded directory.

So the panel now says what is true (`0 of 50 sessions are recorded under
AetherCode — the other 50 have no recorded directory`) **and offers a
route**:

```
a  show every session (not just this directory)
```

`a` widens the scope **in place** (`widenPanelScope` in `state.ts`): the
panel stays open, the fetch effect re-runs, and the user lands on the full
list. A sentence that says "try another command" is not a route — the user
would have to remember a command's name.

## 5. ★ THE MEASUREMENT WAS WRONG ★

The panel LOOKED ragged — its right border landed on a different column on
every line — and several rounds of work tried to fix a rendering bug that
did not exist.

The measured columns were `17, 26, 43, 2, 12, 74, 51, 63, 100`.

The reason: **Ink writes a line's trailing padding as `ESC[nX` (erase n
characters) + `ESC[nC` (cursor forward n columns)**, not as literal spaces.
A measurement that strips ALL ANSI sequences DELETES those two, so each
line appears 74 cells short and the border appears to wander.

Reconstructing the screen the way a terminal does — honouring the cursor
movements — gives **column 100 on every line**:

```
border@100 :: │ ◆ Threads                                          esc │
border@100 :: │ Sessions in AetherCode                                │
border@100 :: │ D:\work\workspace\idea\engine\AetherCode              │
border@100 :: │ no sessions recorded under AetherCode — press 'a' …   │
```

**The panel was never ragged.**

There is a second, structural fact worth recording: Ink's `<Box
width={n}>` **stretches its children to the full width**. The text is
written into a pre-filled grid of cells, so a short row leaves a gap
*inside* the frame but does **not** move the border. The border column is
fixed by the LAYOUT, not by the text.

### → Lesson 1311

**A measurement that deletes the thing it measures cannot see the thing it
measures.** An ANSI stripper is fine for text; a terminal is not a text
file.

### → Lesson 1312

**The measurement and the fix must be the same artefact.** Every fix
attempt was validated by the same broken tool, so the tool was never
questioned. When a defect refuses to be fixed by a change that *should*
fix it, the instrument is the suspect.

### → Lesson 1313

★ A Java source file cannot contain a literal backslash-u sequence, EVEN
IN A COMMENT. ★ Java's lexer processes unicode escapes before the parser
sees the file, so `{@code \uXXXX}` in a javadoc is a **compile error**
("illegal unicode escape"), not a documentation string. The same trap in a
string literal silently becomes the character itself.

## 6. The gate

`aethercode-tui/scripts/test/r388j-panel-frame.test.mjs` (8 cases):

- no panel body draws a frame of its own;
- the panel bodies render through `PanelBody`;
- the content width is derived once, in the host, from the panel width;
- the shell computes its content width from the frame width;
- `fitRow` pads to the width and truncates past it;
- **the RENDERED frame has ONE right border column**;
- **every rendered row fills the frame's interior**;
- the session panel widens its own scope.

The two render cases are the ones that matter. They render the **real**
`PanelShell` + the **real** `PanelBody` through Ink under a **real TTY**
(ConPTY) and reconstruct the screen with a terminal emulator
(`r388j-render-frame.mjs`).

**Both mutations were proven to go red:**

| mutation | result |
| --- | --- |
| `fitRow` stops padding | `fitRow must pad to the width` — RED |
| `ThreadSelector` draws its own `borderStyle="double"` | `draws its own border` — RED |

A gate that can only pass proves nothing (Lesson 1219).

### The probe's temp directory

The probe bundle is ESM with `react`/`ink` external, so Node must find
them at RUN time. A temp directory under the OS temp dir has no
`node_modules` above it, so the import fails and the gate reports a broken
probe instead of a broken panel. The probe directory therefore lives
**inside the package** (`dist/.r388j-probe-*`) and is removed afterwards —
and, per the R388d rule, never inside a checked-in source directory.

## 7. The preview extractor

`AetherCodeMethods.extractSessionPreview` found the closing quote with
`indexOf('"', quoteIdx + 1)` — the first quote of ANY kind, escaped or
not. A user message of `say "hi" now` produced the title `say \`.

Fixed by scanning for an **unescaped** quote (counting the preceding
backslashes — an even count closes, an odd count escapes) and by
unescaping the JSON escapes so the title reads as the user typed it.

`AetherCodeMethodsR388jPreviewTest` (6 cases) pins it. With the old code
**4 of the 6 go red** — exactly reproducing the reported defect.

## 7b. Three keyboard-ownership defects, found by driving the real TUI

The gate proves the FRAME is right. Driving the real TUI through a
ConPTY found three more defects, all with the same root cause:

### ★ INK'S `useInput` IS NOT FOCUS-SCOPED. ★

**Every mounted hook receives every keystroke.** There is no focus
concept. Three components each had their own `useInput`, and each one
acted on the same key:

| component | what it did with `a` |
| --- | --- |
| `ThreadSelector` | widened the scope (correct) |
| the filter row's `TextInput` | typed `a` into the filter |
| the `InputBox` | typed `a` into the prompt |

So pressing `a` in the session panel widened the scope AND left `a` in
two text fields. The user sees a panel that filtered itself by a
character they never typed.

### Fix 1 — the filter swallows the widen key

`ink-text-input` has no "disabled" prop, so the character always
arrives. A **latch** swallows exactly that one character, whichever
order the two handlers run in:

```ts
const swallowNextFilterChar = useRef(false);
// ...
if (input === "a" && onShowAll) {
  swallowNextFilterChar.current = true;
  setFilter("");
  onShowAll();
  return;
}
// ...
onChange: (v: string) => {
  if (swallowNextFilterChar.current) {
    swallowNextFilterChar.current = false;
    setFilter("");
    return;
  }
  setFilter(v);
},
```

A widen is a MODE CHANGE (this cwd → every session), not a refinement of
the current filter, so the filter must be empty afterwards either way.

### Fix 2 — an open panel owns the keyboard

The App-level handler already returned early when a panel was open. The
`InputBox` had its own `useInput` and did not, so it kept typing. It
now gates on `state.activePanel` too — the draft is preserved, it just
does not receive the panel's keystrokes.

### Fix 3 — Enter on a complete command must RUN it

The autocomplete dropdown owns Enter while it is open: it accepts the
suggestion and stops. So `/sessions` + Enter filled the input with
`/sessions ` and **did nothing** — the user had to press Enter a SECOND
time to run the command.

Two Enters for one command is a trap, and it is exactly the shape a user
hits when they type a command they know by heart.

So the input box now distinguishes the two cases:

```ts
// commands.ts — the CATALOG owns the answer (R380's discipline)
export function isCompleteSlashCommand(text: string): boolean { ... }
```

When the text is already a complete command, the completion is cosmetic
(it spells the command canonically) and the command runs in the SAME
keystroke. The canonical text is threaded to the submit branch through
`submitText`, because the store's `state.input` is still the
pre-completion value inside the same handler.

## 7c. A probe race is not a product defect

The first live attempts at the `a` key were **flaky**: sometimes the
panel opened, sometimes it did not. The cause was the PROBE, not the
product — the suggestion list is populated by a `useEffect` after the
last keystroke, so an Enter that arrived too early saw a different state
than the user would see.

→ **Lesson 1314 — a flaky measurement is not evidence of a flaky
system.** Before chasing a defect, prove the MEASUREMENT is
deterministic: run it three times and see whether it agrees with
itself.

→ **Lesson 1315 — a component that renders a text field has its own
`useInput`, and it will receive your keystrokes too.** "I returned from
my handler" is not "the key did nothing".

## 8. What was verified

- the TUI typecheck: `tsc -p tsconfig.json --noEmit` → 0 errors;
- the R388j gate: 11/11 green, three mutations proven red;
- the R388j preview test: 6/6 green, the old code proven red;
- the Java suite: 856 tests, 0 failures;
- the Desktop suite and typecheck (the Desktop's `aethercode-themes`
  resolution was fixed with a tsconfig `paths` mapping, NOT a
  `package.json` dependency — see below);
- the live screen under a real TTY: the panel frame's right border is on
  **one** column, and the whole `/sessions` → `a` flow was driven end to
  end through a ConPTY.

### The Desktop's `aethercode-themes` resolution

The Desktop's typecheck failed with `Cannot find module
'aethercode-themes'`. The obvious fix — adding a `file:../aethercode-themes`
dependency — **contradicts an explicit architectural decision**: the
`ThemeSettings.test.tsx` gate says *"aethercode-themes is no longer wired
as a dependency in package.json (R209)"*, because the sibling package
never shipped and the R209 implementation went lightweight.

The import is TYPE-ONLY, so the fix belongs in the compiler's config:

```jsonc
// aethercode-desktop/tsconfig.json
"baseUrl": ".",
"paths": { "aethercode-themes": ["../aethercode-themes/src/index.ts"] }
```

No install-time dependency, no `package.json` change, and the R209 gate
stays green.

## 9. Lessons

- **1311** — A measurement that deletes the thing it measures cannot see
  the thing it measures. An ANSI stripper is fine for text; a terminal is
  not a text file.
- **1312** — The measurement and the fix must be the same artefact. When a
  defect refuses to be fixed by a change that *should* fix it, the
  instrument is the suspect.
- **1313** — A Java source file cannot contain a literal backslash-u
  sequence, even in a comment. Java's lexer processes unicode escapes
  before the parser sees the file.
- **1314** — A flaky measurement is not evidence of a flaky system. Prove
  the measurement is deterministic before chasing a defect.
- **1315** — A component that renders a text field has its own
  `useInput`, and it receives your keystrokes too. "I returned from my
  handler" is not "the key did nothing".
