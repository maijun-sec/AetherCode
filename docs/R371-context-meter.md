# R371 — `/context`: the row R362 shipped as a placeholder

**Date:** 2026-10-23
**Scope:** `aethercode-protocol`, `aethercode-cli`, `aethercode-tui/src`, TUI gates.

## The shape of this defect

The context meter was not "unfinished". It was **finished three times over,
in three places that never touched each other**, and each one looked
complete on its own:

| Round | What landed | What it did not do |
|---|---|---|
| **T-441** | `ContextMeter.tsx` (251 lines), three State fields, six source-pin assertions | never imported the component into `StatusBar`; nothing ever WROTE the three fields |
| **T-193** | `CompactMethods` with a `registerAll` hook and four method constants | no production code ever instantiated it |
| **R362** | a `context-meter` row in `panelIntents.ts` | nothing emitted `__CONTEXT_METER__`; `/context` fell through to "unknown slash command" |

Three of T-441's six assertions have been **red in the full suite ever
since**. That is the only reason this was found at all — the failure was
loud, unlike the two halves that were silent.

## The gate that could not fail

`CrossCuttingMethodsT500Test.compactMethodsRegisterAllFour` asserts that all
four `compact/*` names are on the dispatcher:

```java
JsonRpcDispatcher d = newDispatcher();
CompactMethods c = new CompactMethods();
c.registerAll(d);
assertTrue(d.hasMethod("compact/run"));
// ... and the other three
```

It builds the object under test, then asks whether it registered itself. The
assertion is real, it is green, and it is **structurally incapable of
failing**: no production code had ever called `CompactMethods.registerAll`,
so `compact/status`, `compact/run`, `compact/reset` and `compact/history`
answered `-32601 method not found` on every daemon, and
`src/compact-rpc.ts`'s remote mode had been dead since it was written. Its
own module doc says "The status bar's `<ContextMeter>` calls into this
module" — the component that was never mounted, calling a surface that
never existed.

This is the gate-shape sibling of R1121 (an `anchor_fn` matching a
substring on an unrelated finding): **the check matched, but nothing asked
whether the wiring happened in production.** The gate now asks exactly
that.

## What changed

### Java (two transports, both)

`DaemonRunner.runInternal` constructs and registers `CompactMethods` on the
stdio dispatcher; `runHttp` constructs one and hands it to
`http.setCompactMethods(...)`. `HttpJsonRpcServer` gained a `compactMethods`
field, `setCompactMethods`, a `routeCompact` helper and four case arms.

`routeCompact` **throws** when unwired rather than returning `{ok:false}`,
for the same reason `routePermission` throws: an envelope would let the TUI
render a confident "auto-compact is on" that nothing verified.

Both transports are handed the engine's real compactor, not `null` — a
null compactor and a real one would disagree about whether auto-compact is
on, and the panel reports one of them.

### TypeScript

- `State` gains `contextInfo` plus two actions, `setContextInfo` and
  `setCompactStatus`. **T-441 declared the three scalar fields and gave
  them no writer at all**; they sat at their `INITIAL` values forever, so
  the bar would have measured against a hard-coded `200_000` regardless of
  the real model.
- `lastCompactTs` only moves **forward**. A failed or stale status poll
  must not rewind the meter's "↓ compacted" hint and re-pin it.
- A 15 s poll in `tui.tsx` reads `getContextInfo` (real window) and
  `compact/status` (circuit breaker + last event). It is best-effort and
  silent: a status-bar widget that raises an error toast is worse than one
  that is briefly stale.
- `StatusBar` embeds `<ContextMeter width={24}>` on its own row.
- `/context` (and `/ctx`) open a real panel on **both** renderers, and the
  registry row moves from `blocker: 'no-command' / advertised: false` to a
  genuine `mounted` / `rendered`.

### Three states, not two

`ContextInfo.inputTokens` became `number | null`, and the meter renders
`—  (no usage reported yet)` for it.

`null` means the daemon has not reported usage yet. That is a **different
fact** from "zero tokens used", and collapsing them makes an empty bar look
like a measurement — a confident `0%` against a fallback window the user
did not choose. The same reasoning guards the poll: an `ok:true` answer
carrying `contextWindow: 0` is rejected rather than divided by.

Both panels also state that the token count is the daemon's estimate
(`transcriptChars / 4`, which its own comment calls "rough"). Rendering it
as a precise count would be the panel's first lie.

## Two assertions that died with the code they described

Fixing the product turned three pre-existing gate assertions red. Each was
correct about the past and wrong about the rule behind it:

**r365 — "helpByGroup excludes non-advertised rows and keeps the rest"**
asserted `!cmds.includes("/context")`. A general rule ("a row whose command
does not exist must not be advertised") written as a special case, named
after the one row that had the problem. It is now written against
`blocker === "no-command"`, and a new positive assertion checks every
advertised row actually answers.

**r362 — "if every intent is advertised the `advertised: false` escape hatch
is dead code"** asserted `total < PANEL_INTENTS.length`. That is a
statement about *today's table*, not about the filter, and it goes red the
moment the codebase is healthy. Replaced with a synthetic row that proves
the filter works, plus a control that the same row without the flag is
included.

**r365-mutate — "re-advertise /context"** could no longer be expressed at
all: there was no row left whose command did not exist. Retargeting it at a
`not-wired` row **survived**, because hiding a genuinely-unavailable row from
`/help` is correct behaviour, not a bug. The case was deleted rather than
bent, and the mechanism is covered by the synthetic row in the R371 gate.

## Gate

`scripts/test/r371-context-meter.test.mjs` — 18 assertions.
`scripts/test/r371-mutate.mjs` — 25 cases, all caught.

The battery attacks all three layers, plus the gate's own extractions,
because **seven of the fixes in this round were to the test, not the
product**:

- `javaMethod` gained an `occurrence` parameter after this gate failed
  twice on the same thing: `DaemonRunner.runHttp` has three overloads and
  two are one-line delegates into the third, so `indexOf` returned a
  72-character body with no wiring in it and the gate reported a defect
  that did not exist. Same lesson as R370's `viewPrimary` sitting 600 lines
  above the picker — an extraction that succeeds while reading the wrong
  thing.
- `routeCompact`'s assertion is **positional**, not "the file mentions
  `throw`": `if (false) { throw … }` keeps the token and passes while the
  unwired daemon answers `null`. A source pin cannot see a dead branch, so
  the gate asserts the null guard still exists and the throw is within
  reach behind it.
- The HTTP case-arm check has **two derivations**: four literal names, and
  every name `CompactMethods.registerAll` exposes. Deleting the literal
  loop was caught, because the derived one still ran — and the derived
  form also catches a fifth method being added and forgotten on HTTP.
- `StatusBar`'s nullable pass-through is asserted. `inputTokens: state.
  inputTokens ?? 0` is the invented measurement this round exists to
  remove, and the battery found its absence from the gate.

## Verification

| Check | Result |
|---|---|
| `npx tsc -p tsconfig.json --noEmit` | 0 errors |
| focused gates R362–R371 + T-441 + R196 (12 files) | **158 / 158** (was 126 at R370) |
| `t441-statusbar-contextmeter.test.mjs` | 6 / 6 — **3 red for months, now green** |
| mutation batteries | r362 **4**, r363 **10**, r364 **12**, r365 **8**, r366 **25**, r369 **11**, r370 **28**, r371 **25** — all caught |
| `aethercode-protocol` | see below |
| full `npm test` | see below |

## Follow-ups

1. **Transcript hydration** (from R370): switching sessions does not replay
   history. `getTranscript` is registered and still unused by the TUI.
2. `/workflow` and `/update` remain `not-wired` on Ink — real UI tasks with
   a working backend, unlike `/context`'s placeholder.
3. The 7 `BACKEND_MISSING` methods are honest but absent.