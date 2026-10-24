# R373 — the `/workflow` picker on Ink, and the two lies the contract was hiding

**Date:** 2026-10-24
**Scope:** `aethercode-tui/src` (Ink + line mode), `aethercode-core` (workflow
reader/paths), `aethercode-protocol` (`listWorkflows` response).

## The gap this closes

`/workflow` had been advertised in `/help` since forever and had a real frame
in **line mode** (`line.ts` `panelWorkflow`). It had **nothing** on Ink. The
registry row said so honestly for three rounds:

- **R362** declared both renderers `unavailable` — a guess, not a check.
- **R365** re-derived the columns from the renderers' source and found line
  mode HAS a real frame, so `ink: 'unavailable'` / `line: 'rendered'` /
  `blocker: 'not-wired'` was the accurate row.
- **R366** found the deeper bug: the line frame called `workflow/list` and
  `workflow/run`, which are registered on **`SupervisorRpcServer`** — a
  separate CLI the TUI never starts. The daemon the TUI spawns
  (`DaemonRunner` → `AetherCodeMethods`) registers the flat `listWorkflows` /
  `runWorkflow`.

So by R373 the backend contract was right and only the Ink surface was
missing. R373 mounts it — and, in mounting it, found two places where the
contract was quietly lying.

## The transport, verified before anything was written

The TUI spawns the daemon itself, over **stdio**:

- `src/jsonrpc.ts:176` — `spawn(this.javaBinary, args, { stdio: [...] })`,
  no `--http-port`.
- `DaemonRunner.java:196` — `methods.registerAll(server.dispatcher())`.
- `AetherCodeMethods.java:1768,1770` — `dispatcher.register("listWorkflows",
  …)` and `dispatcher.register("runWorkflow", …)`.

The HTTP+WS daemon (the desktop's) routes both at
`HttpJsonRpcServer.java:996,998`.

Both transports serve both methods. The gate pins this, because a
stdio-only registration is the R720 / R367 / R371 shape all over again.

## Lie #1 — the picker could not start what it showed

`runWorkflow` resolves a **file**:

```java
// WorkflowPaths.java
public static Path workflowFile(Path cwd, String name) { … }
```

`workflowFile(cwd, "ship-it")` resolves `ship-it.yaml`. But `listWorkflows`
published only the `name:` the YAML **declares**, and a workflow's declared
name is not required to be its file name:

```
.aethercode/workflows/deploy.yaml   ->   name: ship-it
```

That workflow is listed as "ship-it" and **cannot be started under that
name**. Every client hit this: the desktop sends `activeWorkflow.name`
(`aethercode-desktop/src/store/index.ts:5244`), and the line-mode picker
sent `nameOf(w)` — the display name — to `runWorkflow`.

R373 makes the daemon publish the addressable id:

```java
// AetherCodeMethods.listWorkflows
m.put("id", e.fileName());     // "deploy.yaml"
r.put("skipped", listing.skipped());
```

`id` is the workflow **file's name, with the extension**. The extension is
deliberate: it is what keeps a directory holding both `foo.yaml` and
`foo.yml` unambiguous. With a bare stem, both rows would publish the id
`foo` and picking the second would silently launch the first.

`getWorkflow` publishes the same `id`, so the two methods do not answer
with two spellings of the same thing.

### The `.yml` hole, found by the test

The first version of the R373 test asserted that every published id
resolves. It failed on `beta.yml`:

```
published id must resolve: beta ==> expected: <true> but was: <false>
```

`WorkflowReader.list` accepts **both** `.yaml` and `.yml`; `workflowFile`
only ever produced `.yaml`. So a workflow stored as `foo.yml` was listed but
could not be **started, written to, or deleted** — all three went through
that one function and all three missed it.

`workflowFile` now resolves an existing `.yaml` first, then an existing
`.yml`, and only falls back to the canonical `.yaml` when neither exists
(so a *new* workflow is created under the name it will be addressed by). A
name that carries its own extension is used verbatim.

The tie-break is pinned by `workflowFilePrefersYamlWhenBothExtensionsExist`,
so the listing and the resolver cannot disagree about which file a name
means.

## Lie #2 — an empty list could not say why it was empty

`WorkflowReader.list` dropped workflow files it could not read, silently.
So "there are no workflows here" and "you cannot read anything here" were
the **same answer**, and the picker rendered the first one.

R373 publishes `skipped` — the file names the daemon could not read — and
the panel renders the two differently:

| situation                            | what the user sees                       |
| ------------------------------------ | ---------------------------------------- |
| the directory has no workflows       | `no workflows defined` + **where** to put one |
| every file was unreadable            | `N workflow files could not be read:` + the names |
| the daemon has not said where it scans | `the daemon has not said which directory it scans yet` |

The first case used to say "drop a .yaml under **the workflows directory**" —
a path no one had checked. Now it says the daemon's actual `dir`.

**The empty state is a shared pure function**, used by BOTH renderers, so
the Ink panel and the line-mode frame cannot drift apart.

It is split in two on purpose:

- `workflowEmptyCopy(skipped, dir)` — the **copy**. The line-mode frame
  needs it to fill a panel's `empty:` slot (which is only rendered when
  there are no rows).
- `workflowEmptyState(workflows, skipped, dir)` — the copy, or `null`
  when the list is not empty. The Ink panel needs to know whether to
  render the empty state *at all*.

One copy, two callers, and the question "is the list empty?" is not
conflated with "what do I say when it is?".

### A branch that no test could reach

The "skipped" path was, for a while, untestable: the obvious way to make a
file unreadable is to lock it, and **file locks are advisory on Linux**. A
lock-based fixture would have skipped itself on half the platforms and the
branch would never have been exercised anywhere but one developer's Windows
box.

`WorkflowReader.scan(cwd, reader)` takes an injected reader, so the branch is
reachable from a unit test everywhere. A branch that no test can reach is a
branch that rots.

## The Ink surface

- `src/components/WorkflowPicker.tsx` — the panel. Prop-driven, like the
  other ten.
- `src/workflowRows.ts` — the **pure** part: the row type, the normaliser,
  the envelope parser, the empty-state copy. A leaf module with no imports,
  so the gate can compile and RUN it.
- `src/state.ts` — `PanelId` gains `"workflows"`; `State` gains
  `workflows` / `workflowDir` / `workflowSkipped`; the `setWorkflows`
  reducer.
- `src/tui.tsx` — `PANEL_BY_INTENT["workflow-picker"] = "workflows"`, the
  `PANEL_META` chrome, the fetch effect, and the `case "workflows":` render
  branch.
- `src/panelIntents.ts` — the row becomes `ink: 'mounted'` / `line:
  'rendered'`, with no `blocker`.

### The run reports "accepted", not "finished"

`runWorkflow` answers `{ok: true, runId, name, stepCount, accepted: true}`.
The workflow runs on a **daemon thread** and reports through the stream, so
the RPC says "accepted" and nothing more. The toast says exactly that:

```
workflow deploy.yaml accepted (run wf-3)
```

A toast that said "started" or "finished" would be claiming an outcome the
call did not report — the R369 class of defect, one layer up.

### Inputs are shown, not collected

The daemon's `runWorkflow` accepts an `inputs` map. The TUI has no prompt
surface for it, so the picker shows the declared input names and says the
run starts with no values:

```
needs: project_path, notify  ·  runs with no values
```

Showing them without collecting them would be a lie of the
"successful-but-empty" kind, so the row says what will happen.

## The line-mode frame was broken too

`line.ts` `panelWorkflow` had the same addressability bug: it picked
`nameOf(w)` and sent that to `runWorkflow`. It now sends the `id`, with a
fallback to `name` so an older daemon still works.

## The gate

`scripts/test/r373-workflow-picker.test.mjs` — **27 assertions**.

Most of it is **behavioural**: `workflowRows.ts` is compiled with `tsc` and
imported, then run. A source pin can only see that the code mentions a
field; running it shows that the id survives.

What it pins:

- both renderers claim `__WORKFLOW_PICKER__`;
- the Ink panel fetches with the daemon's **real** method name;
- the run sends the **addressable id**, never the display name;
- the run is reported as *accepted*;
- the empty state distinguishes three different facts;
- the daemon registers both methods on **both** transports;
- the daemon publishes `id` and `skipped`;
- the row type is declared **once**.

### Two derivations of "the right method name"

The source pins ask "does the TUI say `listWorkflows`?". A separate
cross-layer test asks "**does the daemon have a method by that name**",
and it asks about the names the code *actually calls* rather than the
names a human wrote down:

```js
for (const m of called) {
  assert.ok(new RegExp(`dispatcher\\.register\\("${m}"`).test(methodsCode), …);
}
```

A wrong name (`workflow/list`, `workflow/run`) is registered on
`SupervisorRpcServer`, so it is not on the daemon and this test goes red.
That gives the property two independent derivations, which is what makes
the GATE mutations below meaningful.

### The mutation battery

`scripts/test/r373-mutate.mjs` — **35 cases, all caught**.

Two of them are GATE mutations: they neuter an assertion and introduce a
real defect, and the gate must **still** go red because a second assertion
sees it. A neutered assertion whose defect only *it* could see proves
nothing — it proves the assertion was the only one — so those are deleted
rather than shipped.

Two holes were found and fixed while building it:

1. **`/workflowEmptyState/` matched the IMPORT.** The assertion "both
   renderers share the empty-state copy" was green even after a renderer
   stopped calling the function, because the unused import still
   contains the name. The open paren is what distinguishes a call from an
   import. (Same family as AGENTS.md's "a source pin cannot see a dead
   branch".)
2. **The first `enclosingObject` extraction read the WRONG row.** The
   registry row's `id:` sits *inside* its object literal, so "the first `{`
   after the header" is the **next** row. The extractor walks *back* to the
   nearest opening brace instead.

## Also fixed: a load-sensitive flake in `aethercode-core`

`WorkflowExecutorR375Test.parallelism_three_fansOut_andAggregates` was a
known flake — it passed alone and failed under reactor load. The cause is a
race **in the test's own helper**:

```java
calls.incrementAndGet();          // the value it returns is DISCARDED
…
return "out-" + calls.get();      // the shared counter, read again
```

The three replicas run concurrently, so by the time the first one reads the
counter the other two have already bumped it — the outputs come back as
`out-3`, `out-3`, `out-3` with no `out-1` anywhere. The fix is to use the
value `incrementAndGet` returned:

```java
int n = calls.incrementAndGet();
…
return "out-" + n;
```

The counter is still incremented exactly once per replica; only the VALUE
used for the label is now the one that replica actually claimed.

This is unrelated to R373 (the executor never touches
`WorkflowPaths.workflowFile`), but it was the one red in every core build,
so it was worth removing while the round was open.

## Verification

| check                                       | result |
| ------------------------------------------- | ------ |
| `tsc --noEmit` (TUI)                        | 0 errors |
| `r373-workflow-picker.test.mjs`             | 27/27 |
| `r373-mutate.mjs`                           | 35/35 caught |
| `WorkflowReaderTest` (core)                 | 16/16 (was 9) |
| `AetherCodeMethodsWorkflowR373Test` (protocol) | 7/7 |
| `aethercode-core` (whole module)            | 1538/1538 |
| mutation batteries r362–r372                | all caught |
| `aethercode-protocol`                       | 377/377 |
| full `npm test`                             | 1278 tests / 46 failures — the SAME 46 as the R372 baseline |

### A note on the two "new" failures that were not new

The first full `npm test` run of this round reported **48** failures against
a baseline of 46. Both extra failures passed in isolation. The cause was
**this round's own verification procedure**: the mutation batteries were run
in the foreground while `npm test` ran in the background, and the batteries
**rewrite the product sources in place** — `r363-mutate.mjs:41` deletes
exactly the `const runId = resp.runId ?? …` line the r363 gate reads.

Re-run serially: 46 failures, and the failing set is **byte-identical** to
the R372 baseline (no new failures, none disappeared).

Two lessons, both recorded in AGENTS.md and memory:

- **The mutation batteries must never run concurrently with the test
  suite.** They are not read-only.
- **"N new failures" is a hypothesis, not a conclusion.** The test is
  whether they pass *in isolation*. Here they did, which pointed at the
  harness rather than the change. The dangerous direction is the opposite
  one — a battery that makes the sources look *healthier* would hide a real
  regression.

## Known, out of scope

- **The desktop still sends the display name.** `aethercode-desktop/src/store/index.ts:5244`
  calls `rpc.runWorkflow(activeWorkflow.name, …)`. With the `id` field
  published, the desktop could switch to it — but desktop UI is out of
  scope for TUI work, so it is left alone and recorded here. The `.yml`
  case IS fixed for the desktop, because that was the `workflowFile` fix.
- **`/update` on Ink** is the next row in the registry with
  `ink: 'unavailable'`, and it needs two entry points (`/update` and
  `/update-deps` are different decisions).
- **The seven `BACKEND_MISSING` methods** still refuse honestly (R369).
  They need new daemon RPCs, not UI.
