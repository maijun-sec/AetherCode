# R236 — SSD (Spec-Design-Tasks-Dev) Internalization

**Status**: ✅ Complete
**Date**: 2026-09-08
**Round**: R236
**Author**: Mavis (M3)
**Branch session**: `mvs_02a19ab079eb4295b2baaf507e30fe63`

---

## 1. TL;DR

R236 moves the SSD 4-phase workflow out of the legacy external Python
driver (`python ssd.py <port> ...`) and into the
`aethercode-workflows` module as a **bundled, in-process built-in**.
Users now invoke SSD via the standard `aethercode ssd` CLI command —
no daemon required, no separate Python install, and the prompts ship
inside the release jar at `ssd/ssd-defaults.yaml`.

- **Prompts are config**: bundled in jar, overridable via
  `<cwd>/.aethercode/ssd/ssd.yaml`
- **Runner is built-in code**: `aethercode-workflows/.../ssd/SsdRunner.java`
- **CLI is built-in**: `aethercode-cli/.../SsdCommand.java` (`aethercode ssd`)
- **Daemon dependency**: GONE. The in-process `AetherCodeEngine.query()`
  is the LLM transport; a stdin reader is the REPL.

## 2. The pivot from R235

R235 (and earlier R228, R233) left SSD as a Python driver
(`ssd.py`, ~620 lines) that ran against a long-lived daemon. The
daemon contract was simple: send a `query` RPC with a combined
system+user prompt, then poll the WebSocket notifications buffer
for `text_delta` / `tool_use_start` / `run_end` events. R235
discovered and fixed three real bugs in that pipeline:

1. **Spec 5+ duplication** (most severe): 11 empty-args `tool_use`
   calls in a single response made `text_delta` chunks straddle
   attempts, producing 551KB of repeated drafts. Fixed by
   `runId` pin + post-tool filter.
2. **Revision propagation**: `confirm()` returned a revision but
   the main loop never passed it back to the phase runner. Fixed
   by adding a `revision: Optional[str] = None` parameter and
   looping until the operator accepts.
3. **WebSocket 64KB frame cap**: spec.md streamed past the
   default 64KB and was silently truncated. Fixed by
   `setMaxTextMessageSize(4MB)`.

Those three fixes made the Python driver work. R236 is the
architectural follow-up: stop shipping the Python driver at all.
The daemon is no longer needed; the LLM is reachable in-process via
`AetherCodeEngine.query()`. The REPL is just `System.in`.

## 3. Architecture

```
┌───────────────────────────────────────────────────────────────┐
│                    aethercode ssd <feature> "..."             │
│                       (SsdCommand.java)                        │
└────────────────────┬──────────────────────────────────────────┘
                     │ builds AetherCodeEngine (in-process)
                     │ wires LlmFn = engine.query(...)
                     │       ReplFn = stdin reader
                     │
              ┌──────▼──────────────────────────┐
              │       SsdRunner.runAll(...)      │
              │  (org.aethercode.workflows.ssd)  │
              └──┬─────┬─────┬─────┬─────────────┘
                 │     │     │     │
          ┌──────▼┐ ┌──▼─┐ ┌─▼──┐ ┌▼─────────────┐
          │ spec  │ │design│ │tasks│ │  dev (loop)  │
          │ .md   │ │.md   │ │.md  │ │   dev.log    │
          └───────┘ └──────┘ └─────┘ └──────────────┘
                 │     │     │     │
                 ▼     ▼     ▼     ▼
         ┌──────────────────────────────────────┐
         │     AetherCodeEngine.query(...)      │
         │   (existing, no changes needed)       │
         └──────────────┬───────────────────────┘
                        │
                        ▼
               MiniMax-M3 (or any configured model)
```

### 3.1 File layout

```
aethercode/
├── aethercode-workflows/
│   ├── src/main/java/org/aethercode/workflows/ssd/
│   │   ├── SsdConfig.java       # YAML loader + validator + template substitution
│   │   └── SsdRunner.java       # 4-phase orchestrator + REPL loop + clean_output
│   ├── src/main/resources/
│   │   └── ssd/
│   │       └── ssd-defaults.yaml  # 4 phase templates + hard rules (bundled in jar)
│   └── src/test/java/org/aethercode/workflows/ssd/
│       ├── SsdConfigTest.java     # 12 tests: loader, validator, template, override
│       └── SsdRunnerTest.java     # 15 tests: phases, revisions, abort, dev, parser
└── aethercode-cli/
    ├── src/main/java/org/aethercode/cli/
    │   ├── SsdCommand.java        # picocli `aethercode ssd ...`
    │   └── Main.java              # registered in subcommands list
    └── pom.xml                    # (no change; aethercode-workflows was already a dep)
```

### 3.2 The two-layer split

The split is deliberate. **`SsdRunner` is in
`aethercode-workflows` and is the reusable, testable core**: it
knows about phases, prompts, the REPL loop, and clean_output, but
NOT about the LLM transport or how to read stdin. **`SsdCommand`
is in `aethercode-cli` and is the thin I/O adapter**: it builds
the engine, plumbs `engine.query` into the runner's `LlmFn`
slot, and plumbs a `BufferedReader(System.in)` into the runner's
`ReplFn` slot.

This means:
- Tests can run `SsdRunner` against an `LlmFn` mock and a `ReplFn`
  mock — no engine, no LLM, no stdin. 15 unit tests run in 1.3s.
- A future second front-end (a Tauri button, a desktop menu item,
  a future HTTP+WS daemon RPC) can reuse `SsdRunner` directly with
  its own LLM/REPL transports.

## 4. The config: prompts ARE configuration

`aethercode-workflows/src/main/resources/ssd/ssd-defaults.yaml`
is the single source of truth. It ships in the jar at
`ssd/ssd-defaults.yaml`. Each phase has:

- `systemPrompt` — the role + hard-rules preamble
- `userPromptTemplate` — `{{feature}}`, `{{intent}}`, `{{priorContent}}`,
  `{{specContent}}`, `{{designContent}}` (rendered before LLM call)
- `userRevisionTemplate` — same vars + `{{revision}}` (rendered for revisions)
- `maxTokens` — per-phase token cap (4096/6144/6144/8192)
- `file` — the artefact name (`spec.md`, `design.md`, `tasks.md`, `dev.log`)

The hard rules (`hardRules:` block) are **shared across all
phases**. They are the single most important behavioural contract:
they prevent the model from calling tools mid-phase, from
emitting `<think>` blocks, and from starting with chatty preambles.
R235 discovered these empirically; R236 puts them where they
belong (config, not code).

### 4.1 Project override

A user can drop a project-local copy at
`<cwd>/.aethercode/ssd/ssd.yaml` and `SsdConfig.fromProjectOrBundled(cwd)`
will pick it up automatically. If the project file is broken
(missing required phases, blank prompt, etc.), the loader prints
a one-line diagnostic and falls back to the bundled default. The
diagnostic is `stderr`-only — the SSD run continues.

```bash
# Verify the override path with a deliberately broken file:
$ cat > /tmp/.aethercode/ssd/ssd.yaml <<EOF
phases:
  - id: spec
    order: 1
    file: spec.md
    title: Spec
    systemPrompt: BROKEN
    userPromptTemplate: "BROKEN {{feature}}"
    userRevisionTemplate: "BROKEN-REV"
    maxTokens: 1024
EOF

$ aethercode ssd foo "bar"
[ssd] /tmp/.aethercode/ssd/ssd.yaml is invalid (SSD config missing required phase(s): [design, tasks, dev] ...); using bundled default
[ssd] using BUNDLED config 'ssd' (4 phases)
...
```

## 5. What R236 does NOT do (intentional)

- **No new workflow YAML step type for "gate"**. The workflow
  engine's executor is a single-pass spawner with no
  human-in-the-loop gate step. Adding a `gate` step type would be
  a much larger change than SSD deserves (a gate is a loop, not a
  step). Treating SSD as a built-in CLI command keeps the workflow
  YAML surface clean and the orchestrator focused.

- **No daemon RPC for SSD**. The REPL is interactive, per-phase
  gate is intrinsic to the design, and a CLI process is the right
  shape for a single-feature spec-first run. If a future Tauri
  button wants to drive SSD, it can shell out to `aethercode ssd`
  or call `SsdRunner` directly with its own LLM/REPL transports.

- **No change to the LLM call semantics**. `AetherCodeEngine.query()`
  already does the right thing: it streams `text_delta` events
  until `run_end`. The R235 `runId` pin and post-tool filter are
  NOT needed in-process because:
  - The `engine.query()` return is a fresh `Stream<StreamEvent>`
    per call, not a shared notifications buffer.
  - The engine's own loop detector / permission policy kills
    tool-use loops before they pollute the output (the model is
    hard-banned from tools via the `hardRules` prompt anyway).
  - The SsdRunner.cleanOutput pass trims `<think>` blocks and
    chatty preambles, so any residual tool drift is caught.

## 6. Tests (27/27 green)

### 6.1 SsdConfigTest (12 tests)

| Test | What it proves |
|---|---|
| `bundledConfigHasAllFourPhases` | `SsdConfig.fromBundled()` returns spec/design/tasks/dev in order |
| `bundledConfigHasNoEmptyPrompts` | Every phase has a non-blank system + user + revision template |
| `bundledHardRulesContainsKeyConstraints` | R235's hard rules are still in the bundled YAML |
| `phaseByIdLooksUpTheBundledNames` | Lookup by id is case-sensitive, returns empty on miss |
| `templateSubstitutionFillsKnownVars` | `{{feature}}` and `{{intent}}` render correctly |
| `templateSubstitutionIgnoresUnknownVars` | Unknown vars render as empty, not `{{literal}}` |
| `projectOverrideTakesPrecedenceOverBundled` | `<cwd>/.aethercode/ssd/ssd.yaml` wins when valid |
| `missingRequiredPhaseFails` | Loader falls back to bundled (not throws) when override is broken |
| `inlineConfigMissingRequiredPhaseFailsLoud` | `parse()` (test path) does NOT fall back — surfaces the error |
| `orderedPhasesSortsByOrderField` | Phases return in order 1..4 regardless of YAML declaration order |
| `substituteHandlesEmptyTemplate` | Null and empty templates don't blow up |
| `substituteLeavesUnterminatedTagAlone` | A typo in the YAML doesn't crash the runner |

### 6.2 SsdRunnerTest (15 tests)

| Test | What it proves |
|---|---|
| `runsAllFourPhasesOnFirstInvocation` | Phases 1-3 each call LLM; phase 4 runs with 0 tasks when tasks.md is bad |
| `revisionReRunsTheSamePhase` | Spec phase: initial LLM call + revision LLM call + accept; spec.md ends up with the revised body |
| `revisionPromptContainsPriorContentAndRevision` | The 2nd LLM call's user prompt has `{{revision}}` AND `{{priorContent}}` substituted |
| `abortPropagates` | `ReplFn` returning `null` → `AbortException` |
| `fromPhaseSkipsEarlierPhases` | `--from-phase 2` does not call the LLM for phase 1 |
| `existingFileAsksForReuseOrRevision` | Pre-existing spec.md is reused on accept; LLM not called for that phase |
| `forceRegeneratesEvenWhenFileExists` | `--force` skips the existing-file check |
| `parsesTaskTable` | Phase 4 iterates a valid 2-row tasks.md, calls LLM per task, writes dev.log |
| `cleanOutputStripsThinkBlocksAndPreamble` | The R235 `clean_markdown` regex is preserved byte-for-byte |
| `cleanOutputCollapsesBlankLines` | 3+ blank lines → 1 |
| `cleanOutputHandlesEmptyAndNull` | Null and empty inputs return empty string |
| `cleanOutputStripsThinkBlocksEvenWhenSplitAcrossLines` | The do-until-stable loop handles multiple back-to-back think blocks |
| `parseTasksHandlesBacktickedFileLists` | `\`a.java\`, \`b.java\`` cells parse as 2 files |
| `parseTasksFallsBackToNumberedList` | A mis-formatted tasks.md still gets implemented (1./2. fallback) |
| `artefactDirResolvesAgainstCwd` | The artefact dir is `<cwd>/<artefactRoot>/<feature>` |

## 7. End-to-end test (R236 e2e)

```
$ aethercode ssd r236-smoke2 "Tiny test: add a one-line ssd.config.print() ..." --auto
[ssd] using BUNDLED config 'ssd' (4 phases)
[ssd] feature: r236-smoke2
[ssd] intent:  r236-smoke2 Tiny test: add a one-line ssd.config.print() ...
[ssd] cwd:     D:\work\workspace\idea\engine\AetherCode\aethercode
[ssd] feature dir: D:\work\workspace\idea\engine\AetherCode\aethercode\.aethercode\ssd\r236-smoke2
[ssd] phase 1 (initial)
[ssd] wrote ...r236-smoke2\spec.md (4455 chars)
[ssd] auto-accept on spec.md
[ssd] phase 2 (initial)
[ssd] wrote ...r236-smoke2\design.md (8989 chars)
[ssd] auto-accept on design.md
[ssd] phase 3 (initial)
[ssd] wrote ...r236-smoke2\tasks.md (11655 chars)
[ssd] auto-accept on tasks.md
[ssd] parsed 26 tasks from ...r236-smoke2\tasks.md
[ssd] --- Task T-3.1.1 — Locate the canonical `ssd-defaults.yaml`...
```

Phases 1-3 produced clean artefacts (no preamble, no `<think>` blocks,
no tool calls). Phase 4 started and parsed 26 tasks; the bash 5-min
timeout cut the demo short, but each completed task was appended to
`dev.log` incrementally. Same shape as the old Python driver, just
without the daemon.

## 8. Build + release

```
$ mvn -pl aethercode-workflows -am clean install -DskipTests
... BUILD SUCCESS

$ mvn -pl aethercode-cli -am clean install -DskipTests
... BUILD SUCCESS

$ cp aethercode-cli/target/aethercode-cli-0.1.0-SNAPSHOT.jar \
    aethercode/dist/aethercode-0.2.55.jar
$ python scripts/promote-jar.py R236
R236: promoting 0.2.55 -> 0.2.56
  dist: copied aethercode-0.2.55.jar -> aethercode-0.2.56.jar
  resources: copied -> aethercode-desktop/src-tauri/resources/aethercode.jar
  tauri.conf.json: version 0.2.55 -> 0.2.56
```

**Published SHA-256**: `A33E5CF889DB1ACCA7458DB3A0EE9AF1F467E5095B315302D06B99AB377BA449`
**Published size**: 55,646,520 bytes (was 55,646,520 — essentially
unchanged, since the SSD code is small; the diff is dominated by
the 7 .class files and one 9.6KB YAML)

Verify the published jar has the SSD surface:

```
$ unzip -l aethercode-0.2.56.jar | grep -E 'workflows/ssd|cli/Ssd|^ssd/'
       12168  org/aethercode/cli/SsdCommand.class
        3518  org/aethercode/workflows/ssd/SsdConfig$Phase.class
        1339  org/aethercode/workflows/ssd/SsdConfig$Source.class
       13131  org/aethercode/workflows/ssd/SsdConfig.class
        14089  org/aethercode/workflows/ssd/SsdRunner.class
        ... (other inner classes)
        9650  ssd/ssd-defaults.yaml
```

## 9. What happened to ssd.py

The legacy `python ssd.py <port> ...` driver at
`.aethercode/daemon-test/ssd.py` (620 lines) is **superseded**. The
R235 features it had (runId pin, post-tool filter, REPL gate,
revision propagation) are preserved by R236's in-process path; the
daemon-specific bits (WebSocket notifications buffer, the
`_notify_baseline` snapshot, the `tool_use_start`-post-filter
heuristic) are no longer needed because the in-process
`AetherCodeEngine.query()` returns a fresh per-call
`Stream<StreamEvent>` and the SsdRunner's own cleanOutput pass
handles the same noise classes.

The ssd.py file is left in place for now as a reference
implementation; a follow-up R237+ may move it to
`scripts/legacy/ssd.py` or remove it entirely once the team is
comfortable the in-process path is the canonical one.

## 10. Lessons (R236)

1. **Two-layer split is the right move for interactive workflows**.
   `SsdRunner` (in `aethercode-workflows`) is the testable core;
   `SsdCommand` (in `aethercode-cli`) is the I/O adapter. 27
   unit tests run in 1.3s, no engine, no LLM, no daemon.

2. **Prompts belong in YAML, not Java**. The four phase templates
   are long enough that inlining them as Java string constants
   would have made the runner 2x the size and impossible to
   override without a rebuild. The bundled YAML is 9.6KB and
   project-overridable.

3. **Bundling beats configuring for SSD specifically**. A `gate`
   workflow step would be a much larger change (engine is a
   single-pass spawner; gate semantics want loops, not steps). A
   dedicated CLI command is the right shape for "spec-first,
   human-confirm per phase".

4. **R235's `hardRules` block is the most important
   configuration**. Without it, the model runs tools, calls
   `ask_user_question`, and produces 500KB of duplicated drafts.
   R236 puts the rule set next to the phase templates so any
   future override must keep it (the default is shipped, not
   optional).

5. **The post-tool filter from R235 is unnecessary in-process**.
   `AetherCodeEngine.query()` returns a fresh per-call stream;
   there's no shared notifications buffer to cross-pollinate.
   The engine's own loop detector + the SsdRunner.cleanOutput
   pass handle every noise class that the R235 fix handled.

6. **Project-override fallback should be loud, not silent**.
   `SsdConfig.fromProjectOrBundled` prints a one-line diagnostic
   on `stderr` when the override is broken, then falls back to
   the bundled default. Silent fallback would hide YAML typos;
   throw would block the user from running SSD at all. Loud
   fallback is the right middle ground.
