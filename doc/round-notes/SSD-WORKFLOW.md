# SSD (Spec-Design-Tasks-Dev) Workflow

> 4-phase spec-first development pipeline. Each phase produces an
> artefact (`spec.md` / `design.md` / `tasks.md` / `dev.log`) and
> enters a confirmation REPL. The next phase only starts after
> the operator accepts the prior artefact.
>
> **R236 (2026-09-08)**: SSD is now a built-in CLI command —
> `aethercode ssd <feature> "..."` — running in-process. The old
> external `python ssd.py <port> ...` driver is superseded. See
> `aethercode/aethercode-workflows/docs/R236-SSD-INTERNALIZATION.md`
> for the architectural story.
>
> **R237 (2026-09-08)**: SSD operator UX polish. New flags
> `--to-phase N` (stop after phase N), `--show` (dump resolved
> config), `--validate` (validate ssd.yaml), `--clean <file>`
> (test cleanOutput). Also: `promote-jar.py` rewritten to be
> marker-driven (R230 design fix). See
> `aethercode/aethercode-workflows/docs/R237-SSD-UX-PROMOTE-FIX.md`
> for the details.

## Phases

| # | Phase | Artefact | Purpose |
|---|-------|----------|---------|
| 1 | **spec**   | `spec.md`   | Background, goals, non-goals, FR-1..N, NFR-1..N, open questions |
| 2 | **design** | `design.md` | Architecture, module breakdown, data model, API, failure modes, tests |
| 3 | **tasks**  | `tasks.md`  | Multi-level Phase → Milestone → Task with objectively checkable acceptance |
| 4 | **dev**    | `dev.log`   | Implements every task one at a time, with operator confirmation between tasks |

All artefacts live at `<cwd>/.aethercode/ssd/<feature>/`. The
bundled config (4 phase templates + hard rules) lives at
`aethercode/aethercode-workflows/src/main/resources/ssd/ssd-defaults.yaml`
and ships inside the release jar.

## How a phase runs

For every phase, the runner does the same 3 steps:

1. **Draft** — call `AetherCodeEngine.query()` in-process with the
   phase system prompt + the prior artefacts (or the user's
   `intent` for phase 1). The model returns a markdown draft.
2. **Write** — persist the draft to `<cwd>/.aethercode/ssd/<feature>/<artefact>.md`.
3. **Confirm** — print the artefact path and a short summary, then
   enter the REPL:
   - Type `ok` (or `next` / `continue` / `y` / Enter on an empty
     line) to accept and move to the next phase.
   - Type any other text to queue a revision. The runner appends
     each line as `#1`, `#2`, ... in the REPL. When you type `ok`
     the runner re-asks the model with the prior draft + the
     queued revisions, and overwrites the artefact.
   - Type `q` (or `quit` / `abort` / `n`) to abort the run.

`dev.log` is special: instead of one confirmation at the end of
the phase, the runner asks the operator between every task
(use `--auto` to plow through).

## Command

```powershell
aethercode ssd <feature> <intent...> [--auto] [--force] [--from-phase N] [--to-phase N] [--cwd <path>]
aethercode ssd --show             # R237: dump resolved config
aethercode ssd --validate         # R237: validate ssd.yaml
aethercode ssd --clean <file>     # R237: test cleanOutput on a file
```

Positional:

- `feature` — kebab-case name; becomes the directory under
  `.aethercode/ssd/`. Must be unique within a project.
- `intent` — free-form description, one or more words. Becomes the
  seed for `spec.md`.

Flags:

- `--auto` / `-y` — skip the confirmation REPL (every phase is
  auto-accepted). Useful for headless/CI runs.
- `--force` — regenerate even when the artefact exists.
- `--from-phase N` (1..4) — start at phase N; earlier artefacts
  are reused as-is (or regenerated with `--force`).
- `--to-phase N` (1..4) — **R237** — stop after phase N. The
  default is 4 (full run). Use `--to-phase 3` to skip the dev
  phase; the dev phase iterates the entire task list (often
  20-30 LLM calls, ~30-60 min) and is impractical for e2e
  demos. `--from-phase 1 --to-phase 3` is the recommended
  e2e combo: spec + design + tasks in ~3 min.
- `--cwd <path>` — project root (default: current directory).

**Utility flags (R237)** — mutually exclusive with each other;
do not require a `<feature>` or intent:

- `--show` — print the resolved SSD config (source, name, 4
  phase summaries, hard rules) and exit. Does not touch the
  LLM. Use this to confirm which config is in effect (bundled
  vs project override) and what the templates look like.
- `--validate` — validate the resolved config (same checks
  `SsdConfig.fromProjectOrBundled` runs internally, plus a few
  practical sanity checks) and print a per-phase report.
  Returns exit code 0 on OK, 1 on validation errors.
- `--clean <file>` — read `<file>`, run `SsdRunner.cleanOutput`,
  write the result to stdout. Lets operators test the clean
  pass against real LLM output without running a full SSD.
  Useful for debugging "why is my artefact missing the body"
  / "why is there a preamble" issues.

The command builds a fresh `AetherCodeEngine` in-process (no
daemon required) and consumes its `Stream<StreamEvent>` directly.

## Example

```powershell
PS D:\work\workspace\idea\engine\AetherCode> aethercode ssd add-sidenote `
    "Add a SideNote stream-event kind that the model can emit mid-tool-call so the TUI shows a progress chip."

[ssd] using BUNDLED config 'ssd' (4 phases)
[ssd] feature: add-sidenote
[ssd] intent:  Add a SideNote stream-event kind ...

[ssd] phase 1 (initial)
[ssd] wrote .aethercode/ssd/add-sidenote/spec.md (4.3 KB)
============================================================
Phase: Spec
File:  .aethercode/ssd/add-sidenote/spec.md  (4374 chars)
Reply with:
  ok / next / continue  — accept and move on
  q / quit / abort      — abort the whole SSD run
  <any text>           — revision; you can paste several lines,
                          they all queue until you type `ok`
============================================================
> add an NFR-3 about audit logging every SideNote
  (revision #1 queued; type 'ok' to apply)
> ok
  applying revision to spec.md: 'add an NFR-3 about audit logging every SideNote'
[ssd] phase 1 (revision #1)
[ssd] wrote .aethercode/ssd/add-sidenote/spec.md (4.7 KB)
...
============================================================
SSD complete. 4 phase(s) produced artefacts:
  - spec:    .aethercode/ssd/add-sidenote/spec.md    (revised 1x)
  - design:  .aethercode/ssd/add-sidenote/design.md
  - tasks:   .aethercode/ssd/add-sidenote/tasks.md
  - dev:     .aethercode/ssd/add-sidenote/dev.log
```

## Files

| File | Purpose |
|------|---------|
| `aethercode/aethercode-workflows/src/main/resources/ssd/ssd-defaults.yaml` | Bundled 4-phase templates + hard rules; shipped in the jar |
| `aethercode/aethercode-workflows/src/main/java/org/aethercode/workflows/ssd/SsdConfig.java` | YAML loader + validator + template substitution |
| `aethercode/aethercode-workflows/src/main/java/org/aethercode/workflows/ssd/SsdRunner.java` | 4-phase orchestrator + REPL loop + clean_output |
| `aethercode/aethercode-cli/src/main/java/org/aethercode/cli/SsdCommand.java` | `aethercode ssd ...` CLI entry point |
| `<cwd>/.aethercode/ssd/ssd.yaml` | (optional) project-local override of the bundled config |
| `<cwd>/.aethercode/ssd/<feature>/spec.md` | Phase 1 artefact |
| `<cwd>/.aethercode/ssd/<feature>/design.md` | Phase 2 artefact |
| `<cwd>/.aethercode/ssd/<feature>/tasks.md` | Phase 3 artefact |
| `<cwd>/.aethercode/ssd/<feature>/dev.log` | Phase 4 log (per-task model output, in order) |
| `.aethercode/daemon-test/ssd.py` | (legacy) external Python driver — superseded by R236, kept for reference |

## Overriding the bundled config

Drop a project-local file at `<cwd>/.aethercode/ssd/ssd.yaml`
with the same shape as `ssd-defaults.yaml`. The required phase
ids are `spec`, `design`, `tasks`, `dev` (any subset of the four
templating knobs may be customised). If the project file is
malformed (missing required phases, blank prompt, etc.), the
runner prints a one-line diagnostic to stderr and falls back to
the bundled default. The SSD run continues.

```yaml
# Example: change spec-phase max tokens and inject a domain rule.
version: 1
name: ssd-team-foo
phases:
  - id: spec
    order: 1
    file: spec.md
    title: Spec
    systemPrompt: |
      You are running SSD for the Foo team. The project lives
      at /opt/foo and uses Java 21 + Maven. Always reference
      Foo-style package names (com.foo.*, not com.example.*).
    userPromptTemplate: |
      feature = {{feature}}
      intent  = {{intent}}
      Phase 1 of 4: ...
    userRevisionTemplate: |
      ...
    maxTokens: 8192
  - id: design
    ...
  - id: tasks
    ...
  - id: dev
    ...
hardRules: |
  [HARD RULES FOR THIS TURN]
    - Do NOT call any tools ...
    ...
maxWaitMs: 240000
artefactRoot: .aethercode/ssd
```

## Relationship to the rest of the AetherCode workflow system

The bundled `ssd-defaults.yaml` is intentionally **not** a
runnable `kind: <step>` workflow in
`aethercode-workflows/WorkflowExecutor`. The 4-phase pipeline
requires a human in the loop on every gate (the design decision
between two valid approaches cannot be made by the model), and
`WorkflowExecutor.runStep` is a single-process, single-thread
executor that has no REPL. The runner is the right place for
that REPL: it consumes `AetherCodeEngine.query()` directly
(an in-process stream), not the workflow executor.

If/when a future round adds `kind: gate` to the workflow schema
(with a stdin/editor pause), the YAML can be re-pointed at the
executor and the runner becomes a thin shim.
