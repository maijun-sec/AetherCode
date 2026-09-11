# R103: Workflow Executor + R104: @-mention Autocomplete (2026-08-12)

R102 + R102b shipped the workflow **plumbing** — list / read
/ write / run RPCs, an input-bar picker, a 4-section
`SubTaskCard`, and a workflow editor modal. The
`runWorkflow` RPC was a stub that emitted one "pending"
SideNote per declared step and returned. **R103 replaces
that stub with a real step-walker.** R104 adds an
input-bar `@`-mention file autocomplete.

## R103: Real workflow executor

### What runs for real

| Type | R102 | R103 |
|------|------|------|
| `shell` | stub | `ProcessBuilder` (cmd.exe on Windows, bash on macOS/Linux); captures exit code + stdout/stderr tail (8KB cap) |
| `delay` | stub | `Thread.sleep(durationMs)` |
| `parallel` | stub | fans out `branches[]` concurrently; aggregates (any error → parallel error) |
| `gate` | stub | evaluates `when` expression; dispatches `then` or `else_` branch list |
| `skill` | stub | stub — R104 wires the agent-driven executor |
| `agent` | stub | stub — R104 wires the agent spawn |

The executor runs on a daemon thread per `runWorkflow` call.
The RPC returns the run id immediately; the desktop gets
`workflow_step` SideNotes as the executor advances
(pending → running → ok / error) plus a final
`workflow_done` or `workflow_error` SideNote.

### Jinja-subset parameter substitution

```
{{inputs.<key>}}       → inputs map value (string-coerced)
{{steps.<id>.status}}   → "ok" | "error" | "pending" | "running"
{{steps.<id>.exitCode}} → captured exit code (0 = success)
{{steps.<id>.stdout}}   → captured stdout (truncated)
{{steps.<id>.stderr}}   → captured stderr (truncated)
```

Substitution happens at run time. Missing keys resolve to
empty string so `{{steps.missing.exitCode}} == 0` is just
`"" == 0` (false), not a parse error.

### `when` expression parser

Hand-rolled recursive-descent parser. Supports:

- `==` `!=` `>` `<` `>=` `<=`
- `&&` `||`
- bare literals, numbers, single/double quoted strings
- `true` / `false` truthiness (truthy if non-empty, non-zero)
- numeric compare falls back to string compare on parse fail

```
when: "{{steps.test.exitCode}} == 0"   # the common case
when: "0 == 0 && 1 == 1"               # bare literals
when: "{{steps.setup.status}} == \"ok\""  # quoted comparison
```

R104 will swap in a real expression engine if needed; the
current parser is enough for the common patterns.

### Sub-step event format

Top-level steps emit `step N/M <status>: <id> (<type>)`.
Sub-steps (parallel branches / gate sub-steps) emit
`<status>: <id> (<type>)` (no N/M — they don't appear in
the desktop's progress bar, only the parent does). The
desktop's existing `side_note` handler in the store
recognises both formats and routes them to `runningWorkflow`.

### WorkflowReader fix

R103 added gate / parallel nesting, which exposed a bug
in R102's regex step parser — it was treating nested
`- id:` entries (4+ spaces indent) as top-level
steps. The fix narrows the top-level step regex to
exactly 2 leading spaces (`  - id:`), so nested steps
are left in the chunk for the executor to parse
locally. Same fix applied to `STEP_ID_LINE`.

### Test coverage

`WorkflowExecutorTest` — 10 cases pass:

- shell success / shell non-zero exit
- delay (asserts minimum elapsed)
- parallel fan-out (both branches ok)
- parallel aggregates error (one branch fails → parent error)
- gate `then` branch fires when true
- gate `else_` branch fires when false (other branch silent)
- shell input substitution (`{{inputs.greet}}`)
- when parser: comparisons, logical ops, substitutions

## R104: @-mention file autocomplete

### Behaviour

The input bar listens for an `@<query>` token (the
substring from the most recent whitespace boundary up to
the cursor, when it starts with `@`). The token opens a
dropdown above the input showing cwd files whose path
contains the substring (case-insensitive). The user
navigates with ↑↓ / Tab / Shift+Tab, picks with Enter
or click, and the `@<query>` is replaced with
`@<full-path>` in place.

After pick, focus returns to the input and the cursor
sits at the end of the inserted `@<path> `. The user
keeps typing naturally; the dropdown doesn't reopen
unless the next keystroke produces a fresh `@`.

### RPC

```
listCwdFiles({ query, max })
  → { files: string[], count, dir }
```

- depth-limited walk (max 6 levels)
- skips common noise dirs: `node_modules`, `.git`,
  `target`, `build`, `.idea`, `dist`, `out`,
  `__pycache__`, `.next`
- forward-slash normalised paths
- sorted, capped at `max` (default 50, max 200)
- case-insensitive substring filter on `query`

### Files

- `aethercode-core/.../workflow/WorkflowExecutor.java` (new, 27KB)
- `aethercode-core/src/test/.../workflow/WorkflowExecutorTest.java` (new, 10 cases)
- `aethercode-core/.../workflow/WorkflowReader.java` (R103 fix: nested-step regex)
- `aethercode-protocol/.../methods/AetherCodeMethods.java`
  - `runWorkflow` rewritten to spawn executor on a daemon thread
  - `listCwdFiles` (R104)
  - `registerAll` updated
- `aethercode-protocol/.../http/HttpJsonRpcServer.java` — dispatch + `/api/methods`
- `aethercode-desktop/src/lib/methods.ts` — `listCwdFiles` wrapper
- `aethercode-desktop/src/components/MessageInput.tsx` — `@`-mention dropdown
- `aethercode-desktop/src/components/MessageInput.css` — `.mention-dropdown` styles

## Build / deploy

- Backend: `aethercode-0.2.7.jar` (39.6 MB) at 3 locations.
  Daemon PID 3780 on port 17888.
- Frontend: 450.9 KB JS / 71.0 KB CSS (R102b 448.7 / 69.4;
  +2.2 / +1.6 KB for the executor wrapper + mention UI).
- Tests: 10/10 `WorkflowExecutorTest` pass, 9/9
  `WorkflowReaderTest` pass, 0 regression in module tests.

## WS roundtrip (wstest_r104.cjs, 6 checks all pass)

```
[1] listCwdFiles registered: true
[2] listCwdFiles({}) -> count: 100, first 5 include 3 sample workflows
[3] listCwdFiles({query: pom}) -> 20+ matches (aethercode/pom.xmls etc.)
[4] listCwdFiles({query: aethercode/src}) -> 0 (aethercode-desktop/src is below cwd=engine root)
[5] listCwdFiles({max: 3}) -> count: 3, cap respected
[6] listCwdFiles({query: workflow}) -> 13 matches (workflows + docs + others)
```

## WS roundtrip for executor (wstest_exec.cjs)

```
[1] writeWorkflow -> {ok, path, name}
[2] runWorkflow -> {ok, runId: wf-1, stepCount: 3, accepted: true, note: "R103 executor running on background thread"}
[3] captured 12 side_notes, 5 ok transitions, 0 errors
    done event: "workflow r103-exec-smoketest ok (5 steps)"
```

The 5 steps include the top-level 3 (`hello`, `pause`,
`decide`) plus 2 sub-steps (one for each branch of the
gate's `then` / `else_` — only one fired in the smoke
test). The progress bar shows the top-level 3 with
green pills, the user sees the workflow_done event land.

## Trade-offs

- **`skill` / `agent` step types stay stubs.** R103 only
  wires `shell` / `delay` / `parallel` / `gate` — these
  are what the workflow YAMLs in R102 ship. R104 will
  wire the `skill` executor to load the named skill
  (R102's `skill-creator` is itself a candidate) and
  the `agent` executor to spawn the named agent via the
  existing `task` tool.
- **`continue_on_error` not yet honoured.** R103 always
  continues to the next step on error. R104 will read
  the per-step flag and stop the run if the user asked
  for it.
- **`wait: any | majority` on parallel not yet wired.**
  R103 only does `wait: all`. R104 can add the other
  two for early-exit on first-success.
- **@-mention `query` is case-insensitive substring
  match, not fuzzy.** The substring search keeps the
  dropdown predictable; fuzzy ranking (e.g. fzf-style)
  would speed up long lists but is overkill for ≤ 50
  entries.
- **@-mention path normalisation** converts Windows
  backslashes to forward slashes so the inserted
  `@<path>` round-trips cleanly through the model.

## Future (R105+)

- **R105: `skill` / `agent` step types** — wire the
  executor to invoke the named skill / agent. The skill
  path can re-use the existing `skill()` tool surface;
  the agent path uses `task({ agent_name, ... })`.
- **R105: per-step `continue_on_error` + `wait` policy.**
- **R105: 4-section `SubTaskCard` toggle animation.**
  The CSS for the counter row is in place; the
  `expanded` state currently just toggles a `display`
  change. A 200ms `max-height` transition would be a
  cleaner fade.
- **R105: session persistence to disk.** The current
  session list is in-memory; reloading a session from
  a previous daemon start is not yet wired.
