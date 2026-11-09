# R388r — the card's advertised keys must DO something (and the gate must be able to prove it)

**Round:** R388r
**Surface:** AetherCode TUI (Ink)
**Trigger:** R388q shipped the file-changes card with three advertised actions. The
user was asked what they should do and chose: **撤销 → open the rewind menu**,
**审查 → expand the file list**.

---

## 1. The defect

R388q shipped this:

```
╭────────────────────────────────────────────────────────────────╮
│ ✎ 已编辑 19 个文件 +3097 -14 │  查看文件 f  [撤销 u] [审查 r]     │
╰────────────────────────────────────────────────────────────────╯
```

and **none of the three advertised keys did anything.** Two independent defects:

1. **`Scrollback` passed no callbacks.** The card's `onUndo` / `onReview` were
   `undefined`, so even a click could not have acted.
2. **The card's `focused` was internal `useState(false)`.** Nothing ever set it to
   `true`, so the card's own key handler was dead code.

> This is R388p's defect class: **a key that does nothing is worse than a key that is
> absent** — the user concludes the feature is broken, and then works around it.

---

## 2. ★ THE MEASUREMENT THAT CHANGED THE DESIGN ★

Ink's `useInput` delivers a keystroke to **every mounted hook**. Measured with a probe
carrying two hooks:

```
events: ["text:d","cmd:d"]
```

A letter typed into the prompt **also** reaches the app's command handler. So a
bare-letter command fires *while the user is typing* — and the app's existing `d` key
(expand a tool turn's details) has exactly that behaviour today.

The card's keys are therefore **command-mode keys**: they fire only when the prompt is
**EMPTY**. That is the REPL convention, it is what a label reading `[撤销 u]` promises,
and it never takes a keystroke the user meant as text.

> This is a pre-existing collision in the app (`d`). R388r does **not** copy it, and it
> does not fix it either — fixing the app's `d` is a separate round.

---

## 3. What was built

### 3.1 `src/fileCardActions.ts` — the ONE action table

```ts
export const FILE_CARD_ACTIONS = ["showFiles", "undo", "review"] as const;

export const FILE_CARD_ACTION_INFO: Readonly<Record<FileCardAction, FileCardActionInfo>> = {
  showFiles: { label: "查看文件", key: "f", desc: "list the files this turn changed" },
  undo:      { label: "撤销",    key: "u", desc: "open the rewind menu to undo the change" },
  review:    { label: "审查",    key: "r", desc: "show the per-file detail" },
};
```

The card renders it, the app dispatches on it, and the gate reads it. A second copy of
"what the card can do" is Lesson 1207's shape.

### 3.2 ★ THE EFFECT IS A VALUE ★

The app's `useInput` handler cannot be mounted in isolation, so a gate cannot drive it.
The first version of the gate therefore **grepped the app's source** — and a grep is not
a check (see §4).

So the *semantics* were lifted into a pure, testable module:

```ts
export type FileCardEffect =
  | { kind: "toggleFiles"; turnId: number }
  | { kind: "openRewind" }
  | { kind: "none" };

export function fileCardEffect(action: FileCardAction, turnId: number | undefined): FileCardEffect;
export function fileCardEffectFor(key, promptEmpty, turnId): FileCardEffect | null;
```

The app is now a **translator**: it resolves an effect and carries it out in ONE place.

```ts
const applyFileCardEffect = (effect: FileCardEffect): void => {
  if (effect.kind === "openRewind") dispatch({ type: "openPanel", id: "checkpoints" });
  else if (effect.kind === "toggleFiles") dispatch({ type: "toggleTurn", id: effect.turnId });
};
```

Both the keyboard **and** the card's own callbacks go through it, so the card's key and
the card's button cannot mean different things.

### 3.3 The card is PURE PRESENTATION

R388q's card owned a `process.stdin` listener and an internal focus flag. Both are gone.
The card takes `focused` and `actionsLive` as **props**, and an action that cannot fire
is **DIMMED** — an affordance the user cannot trigger must not look triggerable.

### 3.4 撤销 opens the rewind menu; it does not guess

The daemon's restore is **checkpoint-granular** — one restore puts back a batch of files
*and* a stretch of conversation — while the card is **turn-granular**. So the card does
not pick a point: it opens the menu and the user chooses. (The same path `Esc Esc` uses.)

---

## 4. ★★ THE MUTATION GATE WAS HOLLOW ★★

This is the round's real finding, and it took two passes to see.

### 4.1 The first version "proved" the gate had teeth — for the wrong reason

The mutation gate copies the sources into a temp sandbox and mutates them there. But the
sandbox has **no `node_modules`**, and the gate's probe bundles Ink and React. The
probe builder hardcoded

```js
nodePaths: [join(ROOT, "node_modules")]   // ← the SANDBOX's, which does not exist
```

so the probe could not resolve `ink`:

```
X [ERROR] Could not resolve "ink"
```

**Every mutation therefore went red — on a BUILD failure.** The gate would have been red
on *any* change, including a correct one. It had no teeth at all; it had a *tripwire*.

R388q's gate already solved this: it reads `process.env.R388Q_NODE_PATHS` and the caller
hands it the **real** tree's path. R388r's gate did not — the mechanism was copied, not
the fix.

### 4.2 ★ AND WITH THE PROBE FIXED, 5 OF 9 MUTATIONS LEFT THE GATE GREEN ★

The build failure had been **masking** the real state of the assertions. With the probe
actually building:

| mutation | result | why |
| --- | --- | --- |
| the card installs its own stdin listener | RED | correct |
| the app stops resolving the card's actions | RED | correct |
| the prompt guard is removed | RED | correct |
| the app passes an empty stub | RED | correct |
| **the Scrollback stops passing the undo callback** | **GREEN** | the gate only grepped the **prop name** |
| **the undo action stops opening the rewind menu** | **GREEN** | the grep for `openPanel", id: "checkpoints"` is **also satisfied by the `Esc Esc` handler** |
| **the review action stops toggling the file list** | **GREEN** | `toggleTurn` appears elsewhere |
| **the card keeps its own copy of the labels** | **GREEN** | the gate only checked that `FILE_CARD_ACTION_INFO` appears |
| **the Scrollback keeps its own grouping rule** | **GREEN** | the gate only checked that `fileChangeOfTurnParts` appears |

Every one of those five is the same shape: **a presence check that a lookup satisfies.**
The string is in the file — and the string is also somewhere else, or the name is still
there while the wiring behind it is gone.

### 4.3 The fix: assert on VALUES, and CALL the code under test

- The action semantics became a **value** (`fileCardEffect`), so the gate asserts on the
  value. Changing what `u` does changes the value.
- The app's translation became a **pinned mapping**, checked in both directions.
- The Scrollback's wiring is checked as **wiring** (`onUndo={() => onUndoFileCard?.(…)}`),
  not as a prop name.
- The card's and the app's grouping are **run** on the same turns and compared — and
  `groupTurns` / `fileChangeOf` had to be **exported** so the probe could call them
  instead of re-implementing them.

> **Lesson 1217 — a needle that is not unique lands on the wrong occurrence and pretends
> to have tested something; a check a LOOKUP can satisfy is no check at all.**
> **Lesson 1401 — a probe that re-implements the rule it is testing measures the probe,
> not the product.**

### 4.4 ★ AND THE PROBE'S OWN FIXTURE WAS WRONG ★

The grouping probe reported **no file changes at all**. The fixture used `path` where
the daemon's tool input uses **`file_path`** — so `diffStatsFor` returned `null` for
every turn.

That failure looked exactly like "the shared rule is broken". It was not: the *fixture*
was broken. **A probe's own bug and the defect it hunts produce the same symptom.**

> **Lesson 1402 — a probe's fixture is part of the measurement. When a probe reports
> "no data", suspect the probe before the product.**

---

## 5. Lessons

- **Lesson 1397 — a dead key is worse than no key.** The user is told the feature
  exists. A missing key is an absence; a dead key is a lie.
- **Lesson 1398 — Ink's `useInput` delivers a keystroke to every mounted hook.** A
  bare-letter command fires while the user types. Bare letters must be scoped to an
  empty prompt.
- **Lesson 1399 — a gate that is red on a BUILD failure is red for the wrong reason.**
  It would be red on any change, including a correct one. A mutation gate must assert
  *which* assertion went red.
- **Lesson 1400 — a check that a LOOKUP satisfies is no check at all.** Grepping for
  `openPanel", id: "checkpoints"` passes while the `Esc Esc` handler satisfies the same
  grep. Assert on the VALUE, or CALL the code.
- **Lesson 1401 — a probe that re-implements the rule it is testing measures the probe,
  not the product.** Export the function and call it.
- **Lesson 1402 — a probe's fixture is part of the measurement.** "No data" usually
  means the fixture is wrong.
- **Lesson 1403 — ★ A mutation gate that is green proves nothing until you watch it go
  red for the RIGHT reason ★.** And the first thing to check when it goes red for the
  wrong reason is whether it went red at all — a build failure is a red that carries no
  information.

---

## 6. Files

| file | what changed |
| --- | --- |
| `aethercode-tui/src/fileCardActions.ts` | **new** — the ONE action table, the effect model |
| `aethercode-tui/src/components/FileChangesCard.tsx` | pure presentation; `focused` / `actionsLive` props |
| `aethercode-tui/src/components/Scrollback.tsx` | the wiring; `groupTurns` / `fileChangeOf` exported |
| `aethercode-tui/src/fileChanges.ts` | the shared grouping rule |
| `aethercode-tui/src/tui.tsx` | the key handler; the effect translator |
| `aethercode-tui/src/keybindings.ts` | three registry entries (`f` / `u` / `r`) |
| `aethercode-tui/scripts/test/r388r-file-card-actions.test.mjs` | the gate — 13 assertions |
| `aethercode-tui/scripts/test/r388r-file-card-actions-mutation.test.mjs` | the mutation gate — 11 mutations + a green baseline + a "right reason" check |
| `aethercode-tui/scripts/test/fixtures/r388r-routing-probe.tsx` | the routing / rendering probe |
| `aethercode-tui/scripts/test/fixtures/r388r-grouping-probe.ts` | the grouping probe |
| `aethercode-tui/scripts/test/r378-keybindings.test.mjs` | the registry-driven extractor accepts both entry points |
