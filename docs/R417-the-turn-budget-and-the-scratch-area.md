# R417 — the turn budget and the scratch area: two defects R416's live matrix measured

> **Round:** R417 (2026-11-27)
> **Predecessor:** R416 ("the boundary of a turn") put a hard boundary around
> **writes**, a **read scope**, and a **stall detector** — and then ran its
> scenario matrix live. The matrix came back **299 / 302 checks green**, with
> three red ones that were not noise:
>
> 1. **L1's tree ended with nine files the user did not ask for** — the
>    requested test under another name, plus an eight-file verification
>    harness the model invented for itself. Every one of them was a
>    **legal** write: the files were *inside* the tree, so the boundary
>    could not have helped. **The model chose the wrong place.**
> 2. **D4 and K1 did not terminate.** The work was **done** in both cases
>    (D4's design was on disk and correct at 5m21s; the turn then ran
>    another 9m30s and reached **104 calls**. K1 had already answered,
>    after **142 calls**, every one of them new — so the stall detector,
>    which measures *information*, never fired).
>
> R417 closes both, and both closures are of the same kind: **a rule that
> moved out of the model and into the engine.**

★ WHY THE SAME KIND ★ the user's rule: *deterministic logic takes the
deterministic path*. A turn that will not stop because the model forgot to
stop is a prompt-level problem wearing an engine's clothes. A place for the
scaffolding is a problem with the same shape — the engine may *allow* a
scratch area, but unless the model is *told* where it is, it will keep
choosing the user's tree, because that is where it has always chosen.

★ AND WHAT R416 ASKED FOR ★ R416's handover listed the two questions this
round answers, in its own words:

- **item 7** — *"the decision to introduce a session scratch directory — and
  to make the shell's boundary allow exactly that one — belongs to the next
  round, with its own measurement"*;
- **item 8** — *"the design question it leaves open: is the stop signal a
  **budget** (finite by default, with the countdown R416 built) or a **goal
  check** ('the artifact you were asked for exists — converge')? The two
  answer different situations, and picking one by default would break the
  other."*

R417 answers item 8 with a **budget** (and says why the goal check is a
different round), and item 7 with a **session scratch area** that both
channels know about and both channels may write to.

---

## 1. C1 — the turn that will not stop

### ★ WHY A WALL CLOCK AND NOT A GOAL CHECK ★

R416 measured both halves of the failure, and they are different failures:

| | D4 | K1 |
|---|---|---|
| the work | done at 5m21s, on disk, correct | the answer was given |
| the calls | 104 | 142, **every one new** |
| why no detector fired | the design was being *revised* — each call was different | no new information was ever *missing* — every call was new |
| what the user experienced | a turn that kept running for **another 9m30s** | a turn that kept running |

⇒ **"the work is done" is not a quantity the engine has.** The engine does
not know what the user asked for in the user's words; it knows what the
model *did*. Turning "the artifact exists" into a deterministic check means
parsing the request and re-deriving the deliverable — a second, guessing
implementation of the task, living next to the first one. (The industry does
not do this either: the sandboxes and the agent runtimes that bound a turn
bound it in **time**, **tokens** and **money**, never in "is it finished".)

⇒ so R417 bounds the turn in **wall-clock seconds**, which is a quantity the
engine *does* have, and which fails safe: a bound that fires late costs the
user some waiting; a bound that never fires costs the user the session.

★ AND NOT A TURN COUNT ★ R416 already refused the turn count, and R417 does
not reopen it: a user's own long round is hundreds of turns and must not be
cut off, while a stalled turn can be one turn that calls the network 142
times. `--max-turns` still exists and still counts **turns**; this is a
different axis, and both are reachable at the same time.

### ★ TWO PHASES, AND THE DIRECTIVE DOES NOT REPLACE THE JUDGE ★

| phase | when | what happens |
|-------|------|--------------|
| **the countdown** | the last **fifth** of the budget | the prompt gains a wrap-up clause ("you are near the end of your time budget — land what you have") |
| **the hard stop** | the budget is spent | the turn ends: `stopReason = "max_duration: …"`, a `side_note` of kind `max_duration`, and a `run_end` frame |

★ WHY THE COUNTDOWN IS WORTH BUILDING ★ a model that is told "five
seconds left" usually *stops talking*; a model that is silently cut off
usually *keeps talking* in the next turn, because it never learned that its
turn was taken away. The countdown is a courtesy, and the hard stop is the
judge — **the countdown can fail and the turn still ends.**

★ THE BUDGET IS MEASURED ON A MONOTONIC CLOCK ★ `nanoTime`, not
`currentTimeMillis`: a budget measured on the wall clock can lose (or gain)
minutes in one step when the user's clock is adjusted or the machine sleeps.

### ★ THE KIND IS `max_duration`, AND IT IS A FAILURE ★

R743's rule: the taxonomy lives in **one** place (`StreamEvent`). So
`max_duration` joins `isDeterministicHardStopOf` (a stop the engine decided,
not a crash) and `isErrorOutcomeOf` (**yes** — the turn did not deliver; the
user must know). The kind travels on the wire beside `isError`, exactly
like every other kind since R383/R384:

```
run_end { "stopKind": "max_duration", "isError": true, "error": "max_duration: …" }
```

⇒ the desktop's `failedStopKinds` and the TUI's `ENGINE_ERROR_KINDS` both
carry `max_duration` — **kind for kind** against the engine's set, which is
what R383's gate enforces.

### ★ "NOT SET" AND "UNBOUNDED" MUST BE DIFFERENT THINGS ★

This is the mistake a budget knob invites: `0` means "no limit" in most
knobs, but "the user never touched it" also has to be expressible, because
the desktop must not overwrite a daemon that was started with
`--max-turns 200` when the user only meant to change the *time* axis.

| surface | "not set" | "unbounded" | "set" |
|---------|-----------|-------------|-------|
| CLI flag | `-1` (the default) | `--max-turn-seconds 0` | `--max-turn-seconds 900` |
| env | `AETHERCODE_MAX_TURN_SECONDS` absent | `=0` | `=900` |
| RPC `setTurnBudget` | the field is **absent** ⇒ that axis is untouched | `maxTurnSeconds: 0` | `maxTurnSeconds: 900` |
| SDK builder | `maxTurnSeconds(-1)` (the default) | `maxTurnSeconds(0)` | `maxTurnSeconds(900)` |

⇒ a **negative** value on the RPC is an **error**, not "unbounded": the
daemon answers `-32602` instead of guessing.

★ AND THE DEFAULT IS A BEHAVIOUR CHANGE ★ R416 had no time bound at all;
R417's default is **600 s (ten minutes)**. That is a real change for a user
who ran a turn longer than ten minutes on purpose — so it is stated here
plainly, and the knob is reachable from **all three surfaces** (below), not
only from a command line. The industry default is in the same range
(Claude Code's own turn limit is minutes; Codex and the MCP runtimes bound
by wall clock too).

### ★ REACHABILITY: ALL THREE SURFACES ★

R416's handover item 2 said it plainly: *"a knob the user cannot reach from
the UI they are looking at is a knob they will not find."* R416's
`setLoopDetectorThresholds` is the template, and R417 copies it **whole**:

```
engine   QueryEngine.setMaxTurnMillis(long) / maxTurnMillis()
         + DEFAULT_MAX_TURN_MILLIS = 600_000L
protocol AetherCodeMethods.setTurnBudget(Object)   ← registered in registerAll()
         HttpJsonRpcServer: case "setTurnBudget"   ← the HTTP arm
         getState → maxTurnSeconds (readable back)
cli      Main --max-turn-seconds (default -1) → effectiveMaxTurnSeconds()
sdk      AetherCodeEngine.Builder.maxTurnSeconds(int) (default -1)
desktop  methods.ts  setTurnBudget(opts) + EngineState.maxTurnSeconds
         store/index.ts  setTurnBudget action + initialize() restores ONLY
                         the axis the user actually set
         SettingsPanel.tsx  a "Turn budget" group (seconds 30–3600, turns 1–500)
tui      /turn-budget [minutes|off|turns N] + the bare form shows the state
         ac-tui.ts  the max_duration arm
```

★ THE DESKTOP ONLY PUSHES WHAT THE USER TOUCHED ★ `initialize()` restores
the budget from the saved preferences **per axis** — a daemon started with
`--max-turns 200` must not have its turn count overwritten by a desktop
that has never been told about turns. The SettingsPanel tracks dirtiness per
axis and pushes only the dirty one (250 ms debounce).

★ AND THE DESKTOP'S `EngineState` WAS MISSING TWO FIELDS ★ `maxTurns` and
`maxTurnSeconds` were never declared on the desktop's `EngineState`, so the
value the daemon sent had nowhere to land. A wire field the client does not
declare is a field the client silently drops — the same shape as R383's
`isError: undefined`.

---

## 2. C2 — the nine files in the user's tree

### ★ A PERMISSION IS NOT AN INSTRUCTION ★

R416's write scope **worked**: L1's nine leftovers were legal writes inside
the tree. Tightening the boundary cannot fix that — a *legal* write is not a
*wrong* write. What was missing is a **place**, and a **name for it**:

- without a place, the model builds its harness where it has always built
  it: in the tree the user pointed it at;
- without a name, the model cannot find the place either.

⇒ R417 gives the engine a **session scratch area** and tells the model
where it is. This is the same shape as the industry reference for a
sandbox: the writable set is "the working directory **plus one temporary
directory**", and the tools are told about the temporary directory through
the environment so that "tools that write temporary files work without
extra configuration".

### ★ WHERE IT LIVES ★

```
<AETHERCODE_HOME | ~/.aethercode>/scratch/<sessionId>/
```

★ ONE ANSWER ★ the engine's own area is asked for in two places — the
**offload root** (the R717-L1 large-tool-result files, whose root R416
moved under the engine home) and the **scratch root** — and they are
siblings under one home. Two copies of "where does the engine keep its
files" drift, and the drift shows up as a directory the user cannot find.
`SessionPaths.engineHome()` is that one answer.

★ NOT THE MACHINE'S `%TEMP%` ★ `TMPDIR`/`TEMP` are shared with every other
program on the box; redirecting them silently is not an option. The product
has its own variable name, its own directory, and it is per session.

★ PER SESSION, NOT PER TURN ★ a turn's leftovers are often meant to be read
by the **next turn of the same session** (that is usually the point: the
script written to verify step 1 is used by step 2) and must be invisible to
every other session.

★ THE DIRECTORY IS CREATED EAGERLY ★ the file tools create the parent
directories they need; **the shell does not**. `echo x >
$AETHERCODE_SCRATCH/x.txt` fails when the directory is missing — and a model
told about a directory that does not exist will **make one in the user's
tree instead**, which is the exact failure this mechanism exists to prevent.
⇒ the engine creates the session's scratch area when the turn starts.

### ★ TWO CARRIERS, ONE VALUE ★

| carrier | who reads it | what it says |
|---------|--------------|--------------|
| the **prompt** (one line, every turn) | the model | where the scratch area is, and that it is *not* in the user's tree |
| the **environment variable** `AETHERCODE_SCRATCH` | the shell | the same path, for a command that needs it without being told |

★ WHY THE PROMPT LINE IS NOT ENOUGH ★ the prompt names the path; the
environment variable is what makes `cd "$AETHERCODE_SCRATCH" && …` work in a
shell the model writes blind. Neither carrier replaces the other.

★ AND THE PROMPT SAYS WHERE IT IS RELATIVE TO THE TREE ★ the directive ends
with *"The user's tree holds only what the user asked for; this area is the
engine's, it is per session, and it goes away with the session."* — because
the failure was not "the model could not find a scratch area", it was "the
model was never told that the user's tree is **only** for deliverables".

### ★ BOTH CHANNELS, ONE RULE ★

The file tools and the shell are **two channels**, and R416 already learned
this lesson (a boundary that only one channel knows about is a boundary one
channel walks through):

| channel | rule |
|---------|------|
| `file_write` / `file_edit` / `notebook_edit` | `WriteScope`'s **W0** admits the scratch root, **before** W1's "inside the tree" rule and **before** W2's tool-directory rule |
| `file_read` / `grep` / `glob` | `ReadScope`'s **R2b** admits the scratch root, and R3's symlink branch resolves the scratch's *real* path |
| `bash` | `BashTool.checkScopeEscape` takes the scratch root and admits it for the **three** boundary questions it asks (a `cd`, a redirect, an absolute path) — through one shared helper, `insideBoundary(target, engineCwd, scratchRoot)` |

★ W2 STILL APPLIES INSIDE THE SCRATCH ★ the scratch area is the engine's
area, not a hole in the boundary: the tool-directory rules (no writing into
the tool's own source tree) still hold there.

★ THE SHELL GETS THE PATH AS AN ENVIRONMENT VARIABLE ★ `BashTool.shellEnv`
injects `AETHERCODE_SCRATCH` into the subprocess environment — and it is
injected as an **absolute** path, and it is **absent** (not empty) when
there is no scratch area, so a shell script can test for it.

### ★ THE BENCH GETS ITS OWN ENGINE HOME ★

The scenario matrix runs a **real daemon** per scenario. With
`AETHERCODE_HOME` unset, that daemon's scratch area (and its offload
directory) would be the **user's real `~/.aethercode`** — the matrix would
be littering the machine it measures, and every scenario's scratch would
accumulate in one shared place.

⇒ the bench gives **every scenario its own engine home**, outside the bench
root, and tells the daemon about it:

```js
const scenarioHome = join(BENCH_HOME, sc.id);      // one home per scenario
env.AETHERCODE_HOME = scenarioHome;
```

★ WHY THE HOME MUST BE OUTSIDE THE BENCH ROOT ★ the contamination guard
flags any path **under the bench root** that is not the scenario's own. A
scratch area inside the root would make every scratch write look like "the
scenario saw the bench's own artifacts" — **the guard firing on the
product working**.

★ AND THE BENCH NOW REPORTS THE SCRATCH ★ a matrix that only counted the
litter in the tree would measure the **absence of harm** without measuring
the **mechanism** that prevents it. Each scenario now prints its scratch
contents and carries one more invariant:

```
★ the session has a scratch area
```

---

## 3. ★ THE COMPILED ARTIFACT IS PART OF THE FIXTURE ★

This round produced the sharpest methodological finding of the series, and
it is worth more than the feature it interrupted.

### ★ WHAT HAPPENED ★

The mutation harness (10 mutations, each one → red → restore) reported
**10/10 green** with every source file restored **byte-exactly** (SHA
verified). Yet after the harness finished, one R417 test was **red**:

```
QueryEngineScratchR417Test.theTurnsPromptNamesTheScratchArea
  the prompt must name the scratch area, got: <>
```

### ★ THE MEASUREMENT THAT NAMED IT ★

`javap -c` on the compiled class, against the source on disk:

```
the source  : buildSystemForTurn() calls scratchDirective()
the bytecode: buildSystemForTurn() does NOT call scratchDirective()
              (the only reference to it is its own declaration)
the mtimes  : QueryEngine.java   10:40:01   ← restored (pre-mutation)
              QueryEngine.class  10:50:09   ← compiled FROM the mutation
```

⇒ the restore wrote the source back **with its original timestamp** (that
is what "byte-identical" meant), the mutant was compiled at 10:50, and
**Maven's incremental compiler compared the two timestamps and decided the
class was up to date** — so every run after the harness measured **the
mutant**, not the product.

⇒ and the second test in the same class, *"the scratch area exists"*, was
**green** — because the directory was already on disk from an earlier run.
**A test that passes because the world already contains its answer** is the
second half of the same trap.

### ★ THE RULE ★

> **Restoring the source is not restoring the product. The build output is
> part of the fixture, and "byte-identical source" says nothing about it.**

⇒ and the sharper form, which is the one that cost the round:

> **A restore that preserves the original timestamp makes the NEXT build
> skip the file.** The compiler's freshness test is a *timestamp* test, not
> a *content* test. ⇒ after any mutation round: **`mvn clean` and rebuild
> from scratch**, then re-run the whole battery — not "restore and re-run".

⇒ every R417 reading in this document was taken **after** `mvn clean` +
rebuild. The numbers in section 4 are the ones from the clean tree.

★ AND THE GENERAL FORM ★ this is the same shape as R416's "the restore
verification is a relative check": a self-check that compares the after-state
with the before-state proves only that **nothing changed** — it cannot prove
that the before-state was right, and it cannot prove that the *thing the
harness actually loads* was restored. ⇒ the discipline for a mutation
battery is: **restore the sources, rebuild the artifacts, re-run the
battery, and take the readings from the rebuilt tree.**

---

## 4. What R417 changed, and how it was verified

### ★ THE SURFACE ★

| layer | file | change |
|-------|------|--------|
| engine | `core/engine/QueryEngine.java` | `maxTurnMillis` + `setMaxTurnMillis` + the countdown directive + the hard stop + the scratch directive + `ensureScratchDirectory()` |
| engine | `core/stream/StreamEvent.java` | `max_duration` in `isDeterministicHardStopOf` + `isErrorOutcomeOf` |
| engine | `core/scope/SessionPaths.java` | `engineHome()`, `scratchRoot(...)`, `safeDirName(...)` |
| engine | `core/scope/WriteScope.java` | the scratch root (W0) |
| engine | `core/scope/ReadScope.java` | the scratch root (R2b) + the symlink branch |
| engine | `tools/shell/BashTool.java` | `AETHERCODE_SCRATCH` in the shell env + `insideBoundary(...)` for the three boundary questions |
| protocol | `protocol/methods/AetherCodeMethods.java` | `setTurnBudget` + `getState.maxTurnSeconds` |
| protocol | `protocol/http/HttpJsonRpcServer.java` | `case "setTurnBudget"` |
| cli | `cli/Main.java` | `--max-turn-seconds` + `effectiveMaxTurnSeconds()` |
| sdk | `sdk/AetherCodeEngine.java` | `Builder.maxTurnSeconds(int)` |
| desktop | `src/lib/methods.ts`, `src/store/index.ts`, `src/components/SettingsPanel.tsx` | the RPC, the action, the `max_duration` arm, the settings group |
| tui | `src/commands.ts`, `src/tui.tsx`, `src/line.ts`, `src/ac-tui.ts`, `src/stopKind.ts` | `/turn-budget`, the `max_duration` arm, the stop kind |
| bench | `scripts/bench-scenarios.mjs` | the per-scenario engine home, the scratch report, the scratch invariant |

★ AND ONE LIE FIXED ON THE WAY ★ `maxTurnsPerQuery`'s javadoc claimed it
was a no-op; R416 had made it real and the doc had not been updated. A
comment that describes a mechanism which does not exist is a comment that
will be read as a promise.

### ★ THE TESTS ★

| suite | file | cases |
|-------|------|-------|
| engine — the budget | `QueryEngineTurnDurationR417Test` | 4 |
| engine — the scratch | `QueryEngineScratchR417Test` | 2 |
| engine — the paths | `SessionPathsScratchR417Test` | 7 |
| engine — the scopes | `ScopeScratchR417Test` | 9 |
| tools — the shell | `BashToolScratchR417Test` | 5 |
| protocol — the wire | `TurnBudgetRpcR417Test` | 8 |
| cli — the flag | `MainMaxTurnSecondsR417Test` | 7 |
| sdk — the builder | `AetherCodeEngineR417Test` | 4 |
| **Java total** | | **46** |
| desktop | `src/store/turnBudgetR417.test.ts` | 11 |
| tui | `scripts/test/r417-turn-budget.test.mjs` | 11 |

### ★ THE MUTATION WITNESSES ★

Ten mutations, one per behaviour, each with its own dedicated witness. Every
one of them: **mutate → the witness goes RED → restore → the source is
byte-identical (SHA) → rebuild → the witness is GREEN again**.

| # | the mutation | the witness that caught it |
|---|---------------|----------------------------|
| 1 | the budget check short-circuits | `QueryEngineTurnDurationR417Test` |
| 2 | `safeDirName` stops sanitising | `SessionPathsScratchR417Test` |
| 3 | the write scope's W0 is closed | `ScopeScratchR417Test` |
| 4 | the read scope's R2b is closed | `ScopeScratchR417Test` |
| 5 | the scratch directive is blanked | `QueryEngineScratchR417Test` |
| 6 | `AETHERCODE_SCRATCH` is not injected | `BashToolScratchR417Test` |
| 7 | `insideBoundary` falls back to the old rule | `BashToolScratchR417Test` |
| 8 | the HTTP arm's method name changes | `TurnBudgetRpcR417Test` |
| 9 | the desktop's `max_duration` arm is renamed | the desktop gate |
| 10 | the TUI reads minutes as seconds | the TUI gate |

★ AND THE FINDING THAT MADE #5 MISLEAD ME ★ mutation #5 is what the stale
artifact hid: with the mutant's class still in `target/classes`, the
witness that should have been **green after the restore** was **red** — and
the first reading of that red was "the product has a bug in the scratch
directive". It did not. §3 is the whole story.

### ★ THE VERIFICATION SUMMARY ★

| what | how | result |
|------|-----|--------|
| the whole Java reactor | `mvn test` **after `mvn clean` + rebuild** | **705 suites / 6637 tests / 0 failures / 0 errors** (527.87 s) |
| the new R417 cases | 8 classes | **46** (22 core + 5 tools + 8 protocol + 7 cli + 4 sdk) |
| the desktop | `vitest run` | **149 files / 1553 tests**, all green (85.92 s) |
| the TUI | the affected gates + the format gate | green (§4's table) |
| the TUI | the **full** gate suite (144 files) | **no new failure** — see below |
| the mutation witnesses | 10 mutations | **10/10** — every mutant red, every restore byte-identical |
| the live matrix | 26 scenarios, 2 segments | A **152/0**, B **188/1** → the one red fixed by the instruction change and re-run **16/0** |

★ EVERY READING IN THIS DOCUMENT COMES FROM THE REBUILT TREE ★ §3 is not a
warning about a hypothetical hazard: it is what happened, and the numbers
above were taken after `mvn clean`.

★ AND THE TUI'S EXISTING REDS, MEASURED RATHER THAN ASSUMED ★ the TUI
suite carries a set of **pre-existing** failures (the docs structure the
repository does not have, a `StatusBar` gate, a few `R33`/`R35` source
pins, …). R417's first reading of them was "31" — and that number came
from a categorisation, not from a measurement.

⇒ so the round **measured** it: the full suite was run, every failing gate
file was collected, and the same files were then run **on the R416
baseline** (the round's changes stashed away):

```
current tree : 49 distinct failures
R416 baseline : 49 distinct failures
NEW in R417 (0):
GONE in R417 (0):
IDENTICAL: true
```

⇒ **R417 adds no TUI failure, and removes none.** The honest baseline is
**49 distinct failures**, not 31 — and the difference between the two
numbers is itself the lesson of the round: *a count that comes from a
categorisation is not a measurement.* (The two runs also agree on the
**identity** of the failures, not just their number — which matters: a
swap of one red for another would have the same count.)

---

## 5. The live matrix

The scenario matrix is the round's **live** evidence: a real daemon per
scenario, a real model, the real RPC the desktop talks to. R416's run
measured the two defects this round closes; R417's run has to show them
closed — and it has to show the CLOSURE, not the absence of the symptom.

### ★ THE MEASUREMENT GAP THE MATRIX HAD ★

The matrix printed a scenario's **stop reason** only when a check FAILED.
So a turn that ended on its budget and a turn that ended normally produced
**the same line in the log** — and the round's headline mechanism was, by
construction, invisible in the matrix's own output.

⇒ R417's matrix records, for **every** turn:

```
....  stopReason=max_duration: the turn reached its time budget of 30 seconds (39s)
```

(the reason, and the wall clock it took), and the summary carries
`stopReason`, `stopKind`, `isError` and `wallClockMs` per row.

★ AND A SCENARIO THAT FORCES THE MECHANISM ★ waiting for a model to
misbehave is a measurement of the model's mood: in R417's first A-segment
run, **D4 finished on its own in 4m30s** and the budget never fired — the
same scenario that ran for 14 minutes in R416. A mechanism that is only
exercised when the model happens to run away is a mechanism whose evidence
is a coin toss.

⇒ the matrix grew a scenario that **forces** it: **T1**, a daemon started
with `--max-turn-seconds 30` and a task that takes minutes. Five things
are measured, and each is a claim a client depends on:

| check | what it proves |
|-------|----------------|
| ★ the turn ENDED on its budget | the whole point — R416's two runaways never did, and only the bench's 900 s wait stopped them |
| ★ the wire carries the kind `max_duration` | a client classifies on the **kind**, never on the decorated reason (R383/R384) |
| ★ the wire says it was a **FAILURE** | a cut turn has NOT delivered; a client that shows it as a green "Idle" is lying |
| ★ the verdict reached the client | the engine computed it, and a client that drops it leaves a truncated answer with no explanation |
| ★ the wall clock was bounded | the budget has to actually CUT the turn, not merely announce that it exists |

★ T1's measured run ★

```
....  stopReason=max_duration: the turn reached its time budget of 30 seconds (39s)
PASS  T1: ★ the turn ENDED on its budget
PASS  T1: ★ the wire carries the kind max_duration
PASS  T1: ★ the wire says it was a FAILURE
PASS  T1: ★ the verdict reached the client
PASS  T1: ★ the wall clock was bounded
== scenario matrix: 16 passed / 0 failed ==
```

★ AND TWO THINGS THIS SCENARIO TAUGHT THE BENCH ★

1. **the prompt steers the classifier, and the bench checks the verdict on
   every scenario** — so a word that has nothing to do with the budget can
   turn the budget's own measurement red. The first two versions of T1's
   prompt said "异常处理" (⇒ the classifier answered *diagnose/debugger*,
   "failure symptom: 异常") and "状态迁移表" (⇒ *code_change/builder*,
   "implementation verb: 迁移"). T1's prompt is now **D4's, verbatim**: its
   verdict is already pinned by the matrix, and its turn takes minutes, so
   a 30 s budget is guaranteed to cut it.
2. **a scenario that measures a mechanism must not also measure
   everything else.** "an answer arrived" is skipped for the budget mode —
   a turn that was CUT has, by definition, not delivered an answer, so
   demanding one would turn the mechanism's success into a failure.

### ★ THE SCRATCH AREA, MEASURED ★

Every scenario now carries one more invariant — **"the session has a
scratch area"** — and prints what is in it. The tree says what the user
has to delete by hand; the scratch says where it went instead. A matrix
that only counted the litter in the tree would measure the **absence of
harm** without measuring the **mechanism** that prevents it.

★ AND HERE IS THE MECHANISM, LIVE ★ the K7 scenario ("write a TEST") asks
the model to write and run a test. In R416, a sibling scenario (L1) left
**nine files in the user's tree** for exactly this kind of work — and
**eight of them were the model's own verification harness** (a copy of the
class, three compilations, two mutants).

⇒ R417's K7 run:

```
....  created=[test/LruCacheTest.java]
....  scratch=[2026-11-27T04-18-06…_32ce069e/out/demo/LruCache$1.class,
              2026-11-27T04-18-06…_32ce069e/out/demo/LruCache.class,
              2026-11-27T04-18-06…_32ce069e/out/demo/LruCacheTest.class]
```

**The deliverable is in the tree. The compile output is in the scratch.**
Three `.class` files that in R416 would have been garbage in the user's
project are now in the engine's own area, and the tree carries only what
the user asked for.

★ AND WHY THE MODEL PUTS IT THERE ★ because the engine **told it** — one
line in the prompt every turn, naming the directory, plus
`$AETHERCODE_SCRATCH` in the shell's environment.

### ★ THE SEGMENT THAT MEASURED THE CLOSURE ★

R417 re-ran the whole matrix with the new instrumentation. The A segment
(12 scenarios) came back **152 passed / 0 failed**, and every turn
carries its own ending:

| scenario | wall clock | how it ended |
|----------|-----------|--------------|
| Q1 a plain question | 11 s | `stop` |
| Q2 a question about this repository | 31 s | `stop` |
| C1 pure creation | 18 s | `stop` |
| C2 creation on disk | 20 s | `stop` |
| D1 a design doc (no destination) | 236 s | `stop` |
| D2 a design doc on disk | 139 s | `stop` |
| D3 a design (no destination) | 76 s | `stop` |
| **D4 a design on disk** | **372 s** | `stop` |
| V1 a code change | 60 s | `stop` |
| G1 troubleshooting | 163 s | `stop` |
| G2 troubleshooting + the fix | 87 s | `stop` |
| O1 build / run / verify | 40 s | `stop` |

★ AND THE HONEST READING OF THAT TABLE ★ **no turn in the A segment hit
the 600 s budget** — and that is not a failure, it is the point: the budget
is a **bound**, not a policy, and a turn that finishes in 11 s should not
be cut at 11 s. The longest turn (D4, 372 s) is exactly the scenario that
ran for **14+ minutes** in R416, and this time it finished on its own.

⇒ which is also the reason **T1 exists**: the runaway is **model variance**,
not a rule, so the mechanism has to be measured where it is forced. (D4 ran
14+ min in R416, 4m30s in R417's first A run, and 6m12s in this one — the
same prompt, three different endings.)

### ★★★ THE ONE THAT MATTERS MOST: K1 ★★★
**K1** ("a lookup about the WORLD") is the scenario R416 recorded as *"the
turn did not finish"* — 142 calls, every one of them new, the answer
already given, and the turn kept going. That is the shape the stall
detector cannot catch, because it measures whether a **result** was seen
before, and K1 kept producing results it had not seen.

⇒ R417's B segment, same prompt, same daemon, default budget:

```
....  stopReason=max_duration: the turn reached its time budget of 600 seconds (603s)
```

**The turn ended.** Not because the engine understood the task, and not
because the model behaved — because the engine has a **bound** and the
bound is a quantity the engine can actually measure. That is the whole
argument for a wall-clock budget over a goal check, stated in one line of
live output:

| | R416 | R417 |
|---|------|------|
| K1 | 142 calls, never stopped | 173 transcript entries, **stopped at 603 s** |
| K9 | fetched the same document from twenty mirrors, never stopped | **stopped at 601 s** |
| D4 | 104 calls, kept going for 9m30s after the work was done | finished on its own in 372 s (and the budget was there if it had not) |
| the bench's 900 s wait | the only thing that stopped them | a backstop, not the mechanism |

⇒ the B segment (14 scenarios, including T1) came back **188 passed / 1
failed**, and that one failure is the most instructive reading of the whole
round — so it gets its own section.

### ★★★ THE ONE RED, AND WHAT IT WAS ★★★

L3 ("the write scope: no INVENTED build system") failed on **"★ no file
nobody asked for"**, and the list was this:

```
extra=[%SCRATCH%/classes/demo/LruCache$1.class, %SCRATCH%/classes/demo/LruCache.class,
       %SCRATCH%/junit-standalone.jar, %SCRATCH%/mut/demo/LruCache.java, …]
```

★ **A directory literally named `%SCRATCH%`, inside the user's tree.** The
model had done the right thing — it was putting its verification harness
"in the scratch directory" — and it had done it into a directory whose name
is a **literal, unexpanded** environment variable.

⇒ the cause, from the transcript:

```
set SCRATCH=%AETHERCODE_SCRATCH% & mkdir "%SCRATCH%\classes" & …
```

★ **cmd expands the WHOLE line before it runs it.** `%SCRATCH%` is
expanded at parse time, when `SCRATCH` is still undefined — and cmd does
**not** error on an undefined variable, it leaves the **text** as it was
written. ⇒ the command created a directory named `%SCRATCH%`.

⇒ and the first suspicion was the product: *"the env var was never
injected"*. That was **wrong**, and the transcript says so — L1's own `set`
output:

```
AETHERCODE_HOME=D:\work\tmp\r417-bench-home\L1
AETHERCODE_SCRATCH=D:\work\tmp\r417-bench-home\L1\scratch\2026-11-27T04-34-49…
```

⇒ the variable **was** there, and the per-scenario engine home works. The
fault was the model's **command idiom**, not the product's plumbing.

★ BUT THE ENGINE CANNOT REWRITE THE MODEL'S COMMAND ★ so the fix had to be
in the instruction, and the instruction now says it explicitly:

> *"Use that path — or the variable itself — directly. Do not alias it
> inside a single command line (on Windows, `set X=%AETHERCODE_SCRATCH% & …
> %X% …` expands %X% before `set` runs, and the literal text ends up as a
> directory name in the user's tree)."*

⇒ L3 re-run with the new instruction:

```
....  created=[test/demo/LruCacheTest.java]
....  scratch=[…/junit-console.jar, …/mut/LruCache.java, …/out/demo/*.class, …/out-mut/demo/*.class]
PASS  L3: ★ no file nobody asked for
== scenario matrix: 16 passed / 0 failed ==
```

**The deliverable in the tree. The harness in the scratch. The literal
`%SCRATCH%` directory gone.**

★ AND THE SHAPE OF THE FIX ★ this is the one place in R417 where the
instruction had to change, and the reason is worth stating: **the engine
can own the rule but not the model's syntax.** Everything else in this
round — the bound, the kind, the scratch's location, the environment
variable — is the engine's, and the engine is where it belongs. The
cmd one-liner idiom is the model's, and the only thing the engine can do
about it is **say what works**.

### ★ THE TWO SEGMENTS, TOGETHER ★

| segment | scenarios | result |
|---------|-----------|--------|
| A (Q1…O1) | 12 | **152 passed / 0 failed** |
| B (T1, K1…K9, L1…L4) | 14 | **188 passed / 1 failed** (the one red above) |
| L3 re-run after the instruction fix | 1 | **16 passed / 0 failed** |

and both segments ended with the bench's own two guards green: **the user's
agent registry is byte-identical**, and **the user's working tree is
byte-identical** (every tracked file's hash, before and after the whole
run).

---

## 6. What is still open

1. **The goal check is still a different round.** R417 bounds the turn in
   time, because "the work is done" is not a quantity the engine has. A
   **converge** signal — "the artifact you were asked for exists" — would
   stop a turn *earlier* than the budget, and it is worth building, but it
   needs a deterministic reading of the request that does not exist yet.
2. **The scratch area is per session, not per turn.** A long session's
   scratch grows; nothing sweeps it. It lives under the engine's home, so
   it is not the user's problem *by default*, but it is still disk.
3. **The desktop's `EngineState` and the daemon's `getState` are two
   declarations of the same state**, and they drift (that is how
   `maxTurns`/`maxTurnSeconds` went missing on the desktop). A generated
   type would close it; R417 closed the two fields by hand.
4. **The shell channel is still unbounded for READS** (R415's item 1, still
   open): `cat ../secret` is not a path a scope can constrain. It needs an
   OS-level sandbox.
5. **The stall detector's false-positive profile on a long, legitimate
   round** is still unmeasured (R416's item 3, still open).
