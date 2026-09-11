# R237 — SSD UX polish + R230 promote-jar.py design fix

**Status**: ✅ Complete
**Date**: 2026-09-08
**Round**: R237
**Branch session**: `mvs_02a19ab079eb4295b2baaf507e30fe63`

---

## 1. TL;DR

R237 is a polish round on top of R236. It ships two distinct improvements:

1. **R230 design debt closed (fifth time lucky)**: `promote-jar.py`
   is now driven by the **marker** (`aethercode-0.2.1.jar`) as the
   single source of truth. The versioned snapshot is derived from
   the marker, not the other way around. The script detects when
   marker ≠ versioned (the R236 bug pattern) and prints a clear
   diagnostic, AND refuses to promote a suspiciously-small marker
   (the "mvn test interrupted" case). Auto-fix is not done — the
   marker is always the more recent of the two.

2. **SSD operator UX**: three new flags + one new affordance
   make SSD debugging and e2e demos tractable.
   - `--to-phase N` (1..4): stop after phase N (skip dev).
     **The e2e demo now fits in 5 minutes** because we can stop
     after tasks (3 phases × ~30s = ~90s vs 4 phases × ~30s × 25
     tasks = ~50 min).
   - `--show`: dump the resolved config (source, name, 4 phase
     summaries, hard rules) without touching the LLM.
   - `--validate`: validate ssd.yaml end-to-end and print a
     per-phase report.
   - `--clean <file>`: read a file, run `SsdRunner.cleanOutput`,
     write to stdout. Tests the clean pass without running SSD.

The R236 R235 R229 R-MEM-1 R-MEM-2 R-MEM-3 R-MEM-4 R-MEM-5 history
of "promote-jar.py silently shipped the wrong jar" stops here.

## 2. R230 — the promote-jar.py redesign

### 2.1 The bug pattern (R229, R-MEM-1/2/3/4/5, R236)

The original `promote-jar.py` had two responsibilities that
should never have been coupled:

1. **Bump the version**: read tauri.conf.json's `version` field,
   increment the patch, write it back.
2. **Copy the previous versioned jar to the new versioned jar**:
   `aethercode-{cur_v}.jar → aethercode-{new_v}.jar`.

The bug: when the operator rebuilt the mvn output and copied it
to the **wrong** file (e.g. `aethercode-0.2.1.jar` instead of
`aethercode-0.2.{cur_v}.jar`), the script picked up the **old**
versioned file and shipped it as the new release. R229, R-MEM-1,
R-MEM-2, R-MEM-3, R-MEM-4, R-MEM-5, and R236 all hit this. Each
time, the user (or the next round) asked "R230 必修" but the
script was never actually fixed.

The root cause is structural: the script trusted the versioned
file to be the canonical mvn output. It isn't. **The marker
`aethercode-0.2.1.jar` is the canonical mvn output** (always
overwritten by the build pipeline); the versioned file is a
derived snapshot.

### 2.2 The new contract

The R237 `promote-jar.py` makes the marker the single source of
truth and treats the versioned file as a derived cache:

```python
src_jar = marker_path              # ALWAYS the marker
shutil.copy2(marker_path, new_jar) # promote marker → new version
```

The script also adds two safety nets that the original lacked:

- **Suspiciously-small marker (hard error)**: if the marker is
  < 1 MB, the script refuses to promote and tells the operator
  to re-run `mvn -pl aethercode-cli -am clean install -DskipTests`.
  This catches the "mvn test interrupted" / "wrong source file
  copied" case immediately, instead of shipping a 9-byte jar
  called "release".

- **Marker ≠ versioned (one-line diagnostic)**: if the marker's
  SHA-256 doesn't match the current versioned file, the script
  prints a clear "R236 bug pattern" message and proceeds with
  the marker. This is a soft warning, not a hard error, because
  the marker is by definition the more recent of the two
  (it gets overwritten on every build).

  The script also **syncs the versioned file to match the
  marker** after a successful promote, so future promotes
  don't re-warn. This is the "promote cascade" — each promote
  leaves the dist directory in a self-consistent state.

### 2.3 The Bash test

```
$ python scripts/promote-jar.py R237
R237: promoting 0.2.56 -> 0.2.57
  note: marker (7b41a116981c...) differs from aethercode-0.2.56.jar (a33e5cf889db...).
        This is the R236 bug pattern — the marker has the new build, the versioned
        file has the old one. Promoting from the marker.
  dist: copied aethercode-0.2.1.jar -> aethercode-0.2.57.jar
  resources: copied -> aethercode-desktop/src-tauri/resources/aethercode.jar
  tauri.conf.json: version 0.2.56 -> 0.2.57
  dist: synced aethercode-0.2.56.jar to match the new marker
```

The new `0.2.57.jar` is the new mvn output (correctly), not the
old `0.2.56.jar` content (the original R236 behaviour). The
`0.2.56.jar` is also updated to match, so the next promote from
a freshly-built marker will not re-warn.

The small-marker hard error was tested by writing 9 bytes of
`TRUNCATED` to the marker; the script refused with an
actionable diagnostic.

## 3. SSD operator UX (`--to-phase`, `--show`, `--validate`, `--clean`)

### 3.1 `--to-phase N`

The dev phase iterates through every task in `tasks.md` and runs
an LLM call per task. A typical `tasks.md` has 20-30 tasks, so
the dev phase takes 30-60 minutes — well past any reasonable
bash timeout, and impractical for e2e demos.

R237 adds `--to-phase N` (1..4) to stop after phase N. The
default is 4 (full run). For e2e demos and CI smoke tests, use
`--to-phase 3` to stop after tasks:

```
$ aethercode ssd r237-to3 "R237: add a --to-phase flag" --auto --to-phase 3
[ssd] using BUNDLED config 'ssd' (4 phases)
[ssd] feature: r237-to3
[ssd] intent:  r237-to3 R237: add a --to-phase flag to aethercode ssd
[ssd] stopping after phase 3 (--to-phase; dev phase skipped)
[ssd] feature dir: ...
[ssd] phase 1 (initial)
[ssd] wrote spec.md (7783 chars)
[ssd] auto-accept on spec.md
[ssd] phase 2 (initial)
[ssd] wrote design.md (11517 chars)
[ssd] auto-accept on design.md
[ssd] phase 3 (initial)
[ssd] wrote tasks.md (12857 chars)
[ssd] auto-accept on tasks.md
[ssd] stopping at phase 3 (dev skipped) — --to-phase=3
```

Total: ~3 minutes. R236's bash-5min-timeout demo now finishes
comfortably.

The implementation lives in `SsdRunner.runAll(cwd, feature,
intent, fromPhase, force, auto, llm, repl, log, toPhase)`. The
runner iterates `config.orderedPhases()`; phases with
`order < fromPhase` are skipped, phases with `order > toPhase`
break the loop. Out-of-range values throw
`IllegalArgumentException` (covered by 2 new unit tests).

### 3.2 `--show`

Dumps the resolved config without touching the LLM. Useful for
debugging "which config is actually being used" — the bundled
default or a project override, and what the templates look like.

```
$ aethercode ssd --show
source:        BUNDLED
name:          ssd
artefactRoot:  .aethercode/ssd
maxWaitMs:     180000
idleEndMs:     2500
hardRules:
  | [HARD RULES FOR THIS TURN]
  |   - Do NOT call any tools ...
  |   - Do NOT include <think>...</think> blocks.
  |   ...
phases:
  - spec (order=1, file=spec.md, maxTokens=4096)
    systemPrompt: You are running the SSD (Spec-Design-Tasks-Dev) workflow where
SSD = a 4-phase spec-first developmen...
    userPromptTemplate[0..100]: CONTEXT: This is the AetherCode project. The feature '{{feature}}' ...
  - design (order=2, file=design.md, maxTokens=6144)
    ...
```

### 3.3 `--validate`

Validates the resolved config end-to-end. Runs the same checks
the loader runs internally (4 required phase ids, non-blank
prompts) plus a few practical sanity checks the loader doesn't
do (no duplicate ids, no duplicate order, every phase has all
required fields).

```
$ aethercode ssd --validate
source: BUNDLED, name: ssd
OK: 4 phases, all required ids present, no duplicates, all prompts non-blank
```

A broken project override returns exit code 1 with a list of
issues. This is the same loud-fallback behaviour as the loader
itself — a typo in `ssd.yaml` is surfaced, not silently fixed.

### 3.4 `--clean <file>`

Reads `<file>`, runs `SsdRunner.cleanOutput`, writes the result
to stdout. Lets operators test the clean pass against real LLM
output without running a full SSD. Useful for debugging "why is
my artefact missing the body" / "why is there a preamble"
issues — paste the raw LLM stream into a file, run
`aethercode ssd --clean <file>`, see exactly what the runner
would write.

```
$ cat raw3.md
<think>more thinking</think>Draft: I will start
Sure, here's the spec

# Real Header
body content line 1

body content line 2

$ aethercode ssd --clean raw3.md
# Real Header
body content line 1

body content line 2
```

The think block, "Draft:" preamble, and "Sure, here's the spec"
chatty line are stripped. The H1 and the body survive.

## 4. Architecture: why utility flags, not subcommands

The R237 utility flags (`--show`, `--validate`, `--clean`) are
deliberately top-level flags, not picocli subcommands like
`aethercode ssd validate`. Reasoning:

1. **Subcommands break the existing CLI.** The R236
   `aethercode ssd <feature> "..."` syntax is the documented
   way to run SSD. Subcommands would force every script that
   invokes SSD to be updated to `aethercode ssd run ...`,
   breaking the demo, the docs, and any user muscle memory.

2. **The utility flags are mutually exclusive with the
   positional args.** When you set `--show`, you don't need a
   `<feature>` or an intent. The `arity = "0..1"` /
   `arity = "0..*"` change lets the same command parse both
   modes.

3. **Picocli's "default subcommand" pattern is awkward.** You'd
   need an `UnmatchedArgumentHandler` to detect "no subcommand
   given" and dispatch to the run subcommand. That's more code
   than the utility flags cost.

4. **Help text stays flat.** `aethercode ssd --help` shows all
   four flags in one place; the operator doesn't have to
   remember whether `validate` is a subcommand or a flag.

The dispatch lives in `SsdCommand.call()`: the three utility
flags are mutually exclusive (more than one set → exit 2), and
each dispatches to its own `doXxx()` method. The default (no
utility flag) is `doRun()`, which is the R236 behaviour.

## 5. Tests (55/55 green)

The SsdRunnerTest grew by 2 tests for `toPhase`:

| Test | What it proves |
|---|---|
| `toPhaseStopsAfterDesign` | `--to-phase 2` runs only spec + design; tasks.md is not written; LLM is called exactly twice |
| `toPhaseValidatesRange` | `fromPhase > toPhase` and `toPhase > 4` throw `IllegalArgumentException` |

The SsdConfigTest and other tests are unchanged. The CLI is
covered by the e2e demo (next section) since picocli command
classes don't have meaningful unit tests in this codebase.

## 6. E2E with `--to-phase 3`

```
$ aethercode ssd r237-to3 'R237: add a --to-phase flag to aethercode ssd' --auto --to-phase 3
[ssd] using BUNDLED config 'ssd' (4 phases)
[ssd] feature: r237-to3
[ssd] intent:  r237-to3 R237: add a --to-phase flag to aethercode ssd
[ssd] stopping after phase 3 (--to-phase; dev phase skipped)
[ssd] wrote r237-to3/spec.md    (7783 chars)
[ssd] wrote r237-to3/design.md  (11517 chars)
[ssd] wrote r237-to3/tasks.md   (12857 chars)
[ssd] stopping at phase 3 (dev skipped) — --to-phase=3
```

Three clean artefacts in ~3 minutes. R236's demo fit the same
artefacts into a 5-minute bash timeout only by being cut off
mid-dev-phase; R237 finishes cleanly with the `--to-phase 3`
shortcut.

## 7. Files changed

| File | Change |
|---|---|
| `scripts/promote-jar.py` | **Rewritten** — marker-driven source, SHA comparison, small-marker hard error, --force + --marker flags, utf-8-sig JSON load, post-promote sync. 9398 bytes (was 4139). |
| `aethercode-workflows/.../ssd/SsdRunner.java` | `runAll()` adds `int toPhase` parameter; loop breaks on `order > toPhase`; out-of-range throws. |
| `aethercode-cli/.../SsdCommand.java` | Adds `--to-phase`, `--show`, `--validate`, `--clean`; arity loosened to `0..1` / `0..*` so utility mode works without a feature/intent; new `doShow/doValidate/doClean` methods. |
| `aethercode-workflows/.../ssd/SsdRunnerTest.java` | 2 new tests for `toPhase`; existing 13 tests updated for the new `toPhase` parameter (4 is the default). |

## 8. Build + release

```
$ mvn -pl aethercode-workflows test
... 55/55 pass
$ mvn -pl aethercode-cli -am clean install -DskipTests
... BUILD SUCCESS
$ cp aethercode-cli/target/aethercode-cli-0.1.0-SNAPSHOT.jar \
    aethercode/dist/aethercode-0.2.1.jar
$ python scripts/promote-jar.py R237
R237: promoting 0.2.56 -> 0.2.57
  note: marker (7b41a116981c...) differs from aethercode-0.2.56.jar (a33e5cf889db...).
        This is the R236 bug pattern — the marker has the new build, the versioned
        file has the old one. Promoting from the marker.
  dist: copied aethercode-0.2.1.jar -> aethercode-0.2.57.jar
  resources: copied -> aethercode-desktop/src-tauri/resources/aethercode.jar
  tauri.conf.json: version 0.2.56 -> 0.2.57
  dist: synced aethercode-0.2.56.jar to match the new marker
```

**Published SHA-256**: `7B41A116981CD77355AFB34AA9110428E3203BF73285EEB95F454FE3DA65A82A`
**Published size**: 55,684,910 bytes (was 55,675,785 — +9KB for the
utility flags)
**Tauri version**: bumped 0.2.56 → 0.2.57

## 9. Lessons (R237)

1. **"R{N} 必修" comments in memory are a smell, not a plan.**
   The R230 promote-jar.py debt was flagged 5+ times in agent
   memory. Each round the user said "R230 必修" but no one
   actually did the fix. The fix is structural: make the
   marker the source of truth, period. Once the architecture
   is right, the bug pattern becomes impossible.

2. **`--to-phase` is a hack that exposes a real product
   question.** The reason we need it is that the dev phase
   runs 25+ LLM calls and is impractical in a demo. The real
   fix is to make phase 4 actually execute code (or hand the
   task list to an AI agent that does) instead of just dumping
   the model's suggestions into a log. R237 puts the band-aid
   on; the proper product direction is "dev phase becomes
   agentic".

3. **Utility flags, not subcommands, when the existing CLI
   shape is the public contract.** Picocli's subcommand
   pattern is clean for greenfield tools, but it would have
   broken every existing `aethercode ssd <feature> "..."`
   invocation in the codebase. Mutually-exclusive flags + a
   tiny dispatch in `call()` keeps the migration zero-cost.

4. **The R236 hard rules work; the dev phase is the next
   thing to harden.** The e2e demos show spec/design/tasks
   are clean even with the model in the loop. The dev phase
   is the only place where the model might call tools (and
   it does, by design). For R238+, the priority is making
   the dev phase either (a) actually execute code in a
   sandbox, or (b) split into per-task sub-commands the
   operator can run individually.

5. **PowerShell `Set-Content` adds a BOM by default.** This
   bit me twice (tauri.conf.json lost its first round of
   changes, SsdRunnerTest compile errored at line 13). The
   fix is `[System.IO.File]::WriteAllText(path, content,
   New-Object System.Text.UTF8Encoding $false)` — the
   `$false` argument explicitly disables the BOM. This is
   documented but easy to forget; R237's
   `promote-jar.py` uses `encoding='utf-8'` (not 'utf-8-sig')
   for the same reason.
