# SSD Demo Run — End-to-end 4-phase spec-first workflow

**Date**: 2026-09-08
**Driver**: `.aethercode/daemon-test/ssd.py` (R235)
**Daemon**: `localhost:17966` (jar `aethercode-0.2.54`, SHA `13c6e46b…`)
**Feature**: `ssd-demo` (a hypothetical `/review` slash command for the AetherCode daemon)

This document records the live end-to-end run of the SSD workflow against a
real daemon + real LLM. It also captures the one bug that surfaced and the
fix that landed in the same round.

---

## 1. The 4-phase pipeline

| # | Phase | Artefact | Trigger | Gate |
|---|---|---|---|---|
| 1 | **Spec**     | `.aethercode/ssd/<feature>/spec.md`   | free-form `intent`     | operator types `ok` to continue |
| 2 | **Design**   | `.aethercode/ssd/<feature>/design.md` | spec.md                | operator types `ok` to continue |
| 3 | **Tasks**    | `.aethercode/ssd/<feature>/tasks.md`  | spec.md + design.md    | operator types `ok` to continue |
| 4 | **Dev**      | `.aethercode/ssd/<feature>/dev.log`   | tasks.md (parsed row by row) | per-task operator review |

- **No tools invoked by the LLM in any of the 4 phases.** The driver hard-bans
  tool calls in the prompt preamble and the daemon enforces single-turn
  (`maxTurns=1`).
- **No double-drafts.** The driver collects only `text_delta` events whose
  `runId` matches the one returned by the query RPC, and (if a `tool_use_start`
  slipped through) keeps only the post-tool chunks.
- **No YAML automation.** The workflow YAML in
  `aethercode-workflows/resources/workflows/ssd-workflow.yaml` is a
  reference document — the actual run is driven by `ssd.py` because
  `WorkflowExecutor.runStep` is single-threaded and has no REPL support
  for the confirm gate.

## 2. What the run produced

```
.aethercode/ssd/ssd-demo/
├── spec.md      156 lines, 1 H1,  clean (Background/Goals/Non-goals/FRs/NFRs/Open-Qs)
├── design.md    329 lines, 1 H1,  clean (Architecture/Modules/Data Model/API/Failure/Tests)
├── tasks.md      80 lines, 1 H1,  27 tasks across 10 milestones, all FR/NFR-linked
└── dev.log      387 lines, 10 H1, 2/27 tasks completed (T-4.1.1 + T-4.1.2)
```

The dev phase was truncated by a 5-minute shell timeout in the demo
harness (27 tasks × ~1.5 min/task ≈ 40 min wall time). The driver is
**idempotent and resumable** — re-running `python ssd.py 17966 ssd-demo "…"`
continues from the next un-implemented task. For real use, either
increase the wall budget or run `ssd.py` in a `Start-Process` with a
PID file (see `R233` lessons in agent memory).

### spec.md (excerpt)

```markdown
# ssd-demo — Spec

## Background
The AetherCode daemon is the long-running conversational agent. Today the
user can ask it to review code, but the result is whatever prose the model
produces — different sessions produce different review shapes, the findings
live only in the chat scrollback, and there is no durable artifact the
user can hand to a teammate or diff against later.

## Goals
- Add a `/review` slash command to the daemon …
- Produce a **structured** code-review pass — fixed shape, not free-form prose.
- By default, run offline (no LLM, deterministic, fast).
- With `--ai`, allow an LLM to add commentary on top …
- Write the report to `.aethercode/reviews/<timestamp>.md` …
## Non-goals
- Not a replacement for a linter or static analyzer.
- Not a code-quality scorer — does not emit a single number.
…
## Functional Requirements   (FR-1 … FR-11)
## Non-Functional Requirements (NFR-1 … NFR-8)
## Open Questions   (7 items, each tagged Defer or Default proposal)
```

### design.md (excerpt)

```markdown
# ssd-demo — Design
> Companion to: spec.md
> Phase: 2 of 4 — Explore / Design
> Per-requirement design — file layout, module shapes, data flow, API contracts

## Architecture Overview
## Module Breakdown        (errors.py / parser.py / walker.py / scanner.py
                           / ruleset.py / ai_commentary.py / render.py /
                           writer.py / session_log.py / command.py)
## Data Model              (dataclass shapes: FileEntry, ScanResult, Finding, AiCommentary)
## API Surface             (parse_review_argv / scan_directory / offline_ruleset
                           / render_report / render_and_write / session_log_append)
## Failure Modes & Recovery
## Test Strategy
```

### tasks.md (excerpt)

```markdown
# ssd-demo — Tasks
## Phase 4 — Implementation
### M-4.1 — Foundation: errors + argparse + per-cwd lock
| ID | Title | Files | Est. min | Acceptance |
| --- | --- | --- | --- | --- |
| T-4.1.1 | Define the Review error hierarchy | `…/review/errors.py` | 10 | … |
| T-4.1.2 | Implement the `/review` argparse | `…/review/parser.py` | 25 | … |
| T-4.1.3 | Implement the per-cwd concurrency lock | `…/review/walker.py` | 20 | … |
…
| T-4.10.2 | Add a perf benchmark (gated, not on default CI) | `…/tests/perf/test_perf_scan.py` | 25 | … |
```

Every row references design.md file paths verbatim and ties its
acceptance to a spec FR/NFR id.

### dev.log (excerpt — T-4.1.1)

```markdown
## T-4.1.1 — Define the Review error hierarchy

# T-4.1.1 — Define the Review error hierarchy

## Scope
Single new file: `aethercode/daemon/commands/review/errors.py`. No edits
to any other file. The hierarchy matches design.md §`errors.py` exactly.

## The file
`aethercode/daemon/commands/review/errors.py`:
```python
"""Single-line user-facing error hierarchy for the /review command.
…
class ReviewError(Exception):
    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message
    def __str__(self) -> str:
        return self.message or self.__class__.__name__

class ReviewArgvError(ReviewError): …
class ReviewCwdError(ReviewError): …
class ReviewSymlinkError(ReviewError): …
class ReviewConflictError(ReviewError): …
class ReviewWriteError(ReviewError): …
class ReviewAiTimeoutError(ReviewError): …
```

## Why this shape
## Test command
TASK DONE
```

## 3. Bug found during the demo: spec.md duplicated 5+ times

### Symptom (first run, before fix)

`spec.md` came out at **551 KB, 6760 lines**, with five (5) `# ssd-demo — Spec`
headers stitched together. The first three were progressively truncated
mid-word (`…long-running conversat# ssd-demo — Spec…`); the last one was
the only complete copy. The cause was the model emitting **11
`tool_use_start` events** in a single response (`bash`, `file_read`,
`ask_user_question` — all with empty arguments), each followed by a
fresh draft of the same spec.

Daemon log evidence (R225 warnings on the empty-args tool_use blocks):

```
14:45:38 WARN  SpringAiChatClient - R225: empty-args tool_use detected: tool=bash, id=call_01a07fc3d9e371b3a6557297
14:45:45 WARN  SpringAiChatClient - R225: empty-args tool_use detected: tool=bash, id=call_01a07fc3f65873d29727336a
14:45:50 WARN  SpringAiChatClient - R225: empty-args tool_use detected: tool=bash, id=call_01a07fc40c397ff2b8368e9e
14:45:55 WARN  SpringAiChatClient - R225: empty-args tool_use detected: tool=bash, id=call_01a07fc41f8d712394157b03
14:47:23 WARN  SpringAiChatClient - R225: empty-args tool_use detected: tool=ask_user_question, id=call_01a07fc54ead79619faccd51
14:47:31 WARN  SpringAiChatClient - R225: empty-args tool_use detected: tool=ask_user_question, id=call_01a07fc5856d7f6294f5818b
14:47:41 WARN  SpringAiChatClient - R225: empty-args tool_use detected: tool=ask_user_question, id=call_01a07fc5a8fe72108789ad60
14:48:00 WARN  SpringAiChatClient - R225: empty-args tool_use detected: tool=bash, id=call_01a07fc5fe557ca0829cd531
14:48:03 WARN  SpringAiChatClient - R225: empty-args tool_use detected: tool=file_read, id=call_01a07fc6142b72b18cb997e8
14:48:07 WARN  SpringAiChatClient - R225: empty-args tool_use detected: tool=file_read, id=call_01a07fc623327db18cb27fdb
14:48:12 WARN  SpringAiChatClient - R225: empty-args tool_use detected: tool=file_read, id=call_01a07fc636987f33ab3e960f
```

The model was apparently trying to "explore the codebase" before writing
the spec — but the empty-args tool_use blocks all failed at the chat
client (R225 parseJsonArgs) and were streamed to the driver as
`tool_use_start` events, sandwiched between repeated `text_delta`
chunks.

### Root cause

The `llm_query` helper was filtering on `ev.get("type") == "text_delta"`
and accumulating every chunk into a single string, with no notion of
"this chunk came before a tool_use_start" vs "this chunk came after the
last tool_use_start". Background notifications from prior runs also
leaked in (the buffer was never reset between calls).

### Fix (driver-side, landed in this round)

Two complementary changes, both in `ssd.py` / `rpc_client.py`:

1. **Run-id pin in `llm_query`.** Every `stream_event` notification
   now has its `params.runId` compared to the `runId` returned by the
   query RPC response. Anything that doesn't match is ignored. This
   also closes a latent cross-run leak in the shared
   `rpc.notifications` buffer.

2. **Post-tool filter.** When a `tool_use_start` event arrives, the
   driver records the current chunk index. After `run_end`, the
   assembled text is sliced to keep only the chunks **after the last
   `tool_use_start`**. The reasoning is: the model's "first attempt"
   prose appears before the first tool_use, but the genuine "after
   deciding not to call any tool" prose appears after the last
   tool_use — that's the part we want.

Plus a prompt-level hard rule (already in place from a prior round)
that bans tool calls entirely:

```
[HARD RULES FOR THIS TURN]
  - Do NOT call any tools (no bash, no file_read, no glob, no grep, no ask_user_question).
  - Do NOT include <think>...</think> blocks.
  - Do NOT start with 'Draft:' / 'Note:' / 'I will' / 'Let me' / 'Sure,' / 'Okay,'.
  - Output ONLY the markdown content (start with the first `#` header).
```

### Verification (after fix)

```
=== spec.md : 156 lines, 1 h1 ===
=== design.md : 329 lines, 1 h1 ===
=== tasks.md : 80 lines, 1 h1 ===
=== dev.log : 387 lines, 10 h1 ===     (dev phase was truncated by shell timeout; 2/27 tasks done)
```

`spec.md` shrank from **551 KB → 11.5 KB** with **6760 lines → 156
lines** and **5+ duplicate headers → 1**. The model honored the
no-tools rule (no further `R225: empty-args tool_use` warnings in the
daemon log after the fix).

## 3.1 Confirmed end-to-end: revision propagates spec → design → tasks

To prove the confirm loop is real (not a stub), I ran the driver in
stdin mode with a single revision in the spec.md review:

```bash
# stdin plan (one line per REPL input):
add NFR-9: offline cache — the renderer must first write the report to
   .aethercode/cache/reviews/<ts>.tmp.md, then atomically rename to
   .aethercode/reviews/<ts>.md so a crash mid-render never leaves a
   partial report visible.
ok   # accept revised spec
ok   # accept design
ok   # accept tasks
q    # abort dev phase
```

Driver transcript (key lines):

```
=== Phase 1/4 — spec ===
  wrote …/spec.md (11211 chars)
>   applying revision to spec.md: 'add NFR-9: offline cache — …'
  wrote …/spec.md (12054 chars)        ← REVISION APPLIED
=== Phase 2/4 — design ===
  wrote …/design.md (20681 chars)      ← picks up NFR-9
=== Phase 3/4 — tasks ===
  wrote …/tasks.md (10729 chars)       ← adds NFR-9-linked tasks
> Aborted by operator.
```

NFR-9 propagation across artefacts:

| file | NFR-9 references | what it added |
|---|---|---|
| `spec.md`   | 1  | new `### NFR-9 — Offline cache` requirement |
| `design.md` | 20 | `writer.py` redesigned to two-step cache → atomic-rename; failure-mode table, test strategy, Open-Q8 cleanup all reference NFR-9 |
| `tasks.md`  | 3  | `T-4.6.2` (cache-staged atomic writer), `T-4.7.2` (ReviewCommand orchestrator with cache step), `T-4.8.7` (test the cache-staged writer with happy / crash-mid-write / collision-exhaustion cases) |

This is exactly the SSD flow the user asked for: the user wrote
**one sentence** in the spec.md review, and the model propagated it
all the way through design and tasks with the right detail at each
level.

## 4. How to use the driver

```bash
# one-shot, all 4 phases, auto-confirm (no stdin):
python .aethercode/daemon-test/ssd.py 17966 ssd-demo \
  "Add a /review slash command to the AetherCode daemon" \
  --no-confirm --force

# interactive — pauses after each phase for review/revision:
python .aethercode/daemon-test/ssd.py 17966 ssd-demo \
  "Add a /review slash command to the AetherCode daemon"

# resume from a specific phase (skip earlier artefacts):
python .aethercode/daemon-test/ssd.py 17966 ssd-demo "..." \
  --from-phase 3 --no-confirm

# pipe revisions as if you were the operator:
printf 'add NFR-9 for offline cache\nok\n' | \
  python .aethercode/daemon-test/ssd.py 17966 ssd-demo "..."
```

### REPL commands

| input | effect |
|---|---|
| `ok` / `next` / `continue` / `y` / `yes` | accept current artefact, advance |
| (empty line) | accept current artefact, advance |
| any other text | queued as a **revision request**; the driver re-asks the LLM with the prior draft + the revision as context. Multiple revisions can be queued (one per line) before the next `ok`. |
| `q` / `quit` / `abort` / `n` / `no` | abort the workflow |
| `Ctrl-D` (EOF) | accept and advance (useful in `printf '…\n' | python …` flows) |

## 5. Files

- `.aethercode/daemon-test/ssd.py` — driver (~600 lines, 4 phase runners + REPL)
- `.aethercode/daemon-test/rpc_client.py` — WS client (added `_notify_baseline` for the run-id pin)
- `aethercode/aethercode-workflows/resources/workflows/ssd-workflow.yaml` — reference YAML
- `doc/项目文档/SSD-WORKFLOW.md` — operator-facing guide
- `.aethercode/ssd/ssd-demo/{spec,design,tasks}.md` + `dev.log` — this run's artefacts
- `.aethercode/ssd/ssd-demo.bak-20260908-155832/` — pre-fix polluted artefacts (for diff)

## 6. Cross-references

- **R234 daemon test report**: `doc/项目文档/R234-DAEMON-TEST/report.md` (the RPC-surface testing that gave us the green-light to wire SSD on top)
- **R233 memory lessons**: `~/.minimax/agents/mavis/memory/MEMORY.md` (L4 batch-edit, PowerShell `Start-Process` + PID file pattern for the long dev phase)
- **Mavis `cron self` pattern**: needed if you want to fire-and-forget the dev phase and have the session resume when done

## 7. Lessons from this round

1. **Don't trust `text_delta` accumulation alone.** When the LLM is
   allowed to emit tool_use blocks (even with empty args), the text
   between tool_uses is "draft noise". The driver must either (a)
   hard-ban tools and trust the prompt, or (b) drop text before the
   last `tool_use_start`. We do both. (b) is the durable safety net.
2. **The shared `rpc.notifications` buffer is a footgun.** Every
   `llm_query` call MUST snapshot the baseline and only consume
   events that arrive after the query was sent. Pin the `runId` too
   so background tasks can't pollute the text accumulator.
3. **Confirm-loop revisions are a real driver concern, not a
   prompt concern.** The model knows how to revise; the driver has
   to actually wire the revision message back into the next LLM
   call. The first version of `ssd.py` collected revisions and
   dropped them — caught it by piping stdin in a test.
4. **Stdout-pipe for `python` REPL on Windows works via
   `Get-Content … | python …`.** `Start-Process -RedirectStandardInput`
   complained about the path; piping through `Get-Content` is the
   reliable PowerShell way.
5. **Each SSD phase takes 1-2 min wall time even with the
   hard-bans.** A 5-minute shell timeout covers phases 1-3 with one
   revision comfortably; phase 4 (27 tasks × ~1.5 min) needs ~40 min.
   For unattended runs use `Start-Process` + PID file + `cron self`.


---

# R236 addendum — SSD becomes a built-in CLI command

**Date**: 2026-09-08 (same day as the original demo)
**Driver**: `aethercode ssd ...` (R236, in-process)
**Daemon**: NONE. The in-process `AetherCodeEngine.query()` is the
  LLM transport; stdin is the REPL.
**Feature**: `r236-smoke2` ("Tiny test: add a one-line
  `ssd.config.print()` directive to the bundled ssd-defaults.yaml")

This addendum is the follow-up to the R235 demo above. R236
**supersedes the external `python ssd.py` driver** with a built-in
`aethercode ssd` CLI command. The LLM is now reached in-process
(via `AetherCodeEngine.query()`); no daemon, no WebSocket
notifications buffer, no `_notify_baseline` snapshot. The prompts
ship inside the release jar at `ssd/ssd-defaults.yaml`.

## A.1 What changed

| | R235 (ssd.py) | R236 (aethercode ssd) |
|---|---|---|
| Daemon required? | Yes (HTTP+WS on `<port>`) | No |
| Driver location | `.aethercode/daemon-test/ssd.py` (620 lines) | Built into `aethercode-workflows` jar |
| Prompts location | Python string constants in ssd.py | `ssd/ssd-defaults.yaml` (bundled resource) |
| Prompts overridable? | No (rebuild the script) | Yes (`<cwd>/.aethercode/ssd/ssd.yaml`) |
| `runId` pin needed? | Yes (notifications buffer is shared) | No (each query returns a fresh stream) |
| Post-tool filter needed? | Yes (R235 fix) | No (engine loop detector + `cleanOutput` handle it) |
| WebSocket 64KB frame fix? | Yes (R235 fix) | No (in-process stream, no WS frames) |
| Tests | E2E only (daemon required) | 27 unit tests (1.3s, no daemon) |
| REPL transport | `python input()` | Java `BufferedReader(System.in)` |

## A.2 End-to-end run against the published 0.2.56 jar

```
$ aethercode ssd r236-smoke2 "Tiny test: add a one-line ssd.config.print() ..." --auto
[ssd] using BUNDLED config 'ssd' (4 phases)
[ssd] feature: r236-smoke2
[ssd] intent:  r236-smoke2 Tiny test: ...
[ssd] cwd:     D:\work\workspace\idea\engine\AetherCode\aethercode
[ssd] feature dir: ...\.aethercode\ssd\r236-smoke2
[ssd] phase 1 (initial)
[ssd] wrote ...\r236-smoke2\spec.md (4455 chars)
[ssd] auto-accept on spec.md
[ssd] phase 2 (initial)
[ssd] wrote ...\r236-smoke2\design.md (8989 chars)
[ssd] auto-accept on design.md
[ssd] phase 3 (initial)
[ssd] wrote ...\r236-smoke2\tasks.md (11655 chars)
[ssd] auto-accept on tasks.md
[ssd] parsed 26 tasks from ...\r236-smoke2\tasks.md
[ssd] --- Task T-3.1.1 — Locate the canonical `ssd-defaults.yaml`...
[ssd]     files: src/main/resources/ssd-defaults.yaml ...
```

Phases 1-3 produced clean artefacts (no preamble, no `<think>`
blocks, no tool calls). The `hardRules` block in
`ssd-defaults.yaml` keeps the model in line. Phase 4 started and
parsed 26 tasks; the bash 5-min timeout cut the demo short, but
each completed task was appended to `dev.log` incrementally.

## A.3 The two-layer split

The R236 code is intentionally split:

- **`SsdRunner` (aethercode-workflows)**: testable core. Knows
  about phases, prompts, the REPL loop, `cleanOutput`. Does NOT
  know about the LLM transport or how to read stdin. Consumes an
  `LlmFn` + `ReplFn` + `Logger` triple.
- **`SsdCommand` (aethercode-cli)**: thin I/O adapter. Builds the
  `AetherCodeEngine`, wires `engine.query(...)` into `LlmFn`,
  wires `BufferedReader(System.in)` into `ReplFn`, wires
  `System.out::println` into `Logger`.

The split means 27 unit tests run in 1.3s with no engine, no LLM,
no daemon. A future second front-end (Tauri button, desktop menu,
HTTP+WS RPC) can reuse `SsdRunner` with its own LLM/REPL
transports.

## A.4 Cross-references

- **R236 report**: `aethercode/aethercode-workflows/docs/R236-SSD-INTERNALIZATION.md`
  (full architectural story, 27 test descriptions, lessons)
- **R235 demo**: this document (sections 1-7, the original `ssd.py` run)
- **R235 bug fixes** that R236 no longer needs:
  1. `runId` pin + `_notify_baseline` snapshot (no notifications buffer in-process)
  2. Post-tool filter `last_tool_idx` (engine loop detector + `cleanOutput` cover it)
  3. `setMaxTextMessageSize(4MB)` (no WebSocket frames in-process)

## A.5 Lessons from R236

1. **Two-layer split is the right move for interactive workflows**.
   `SsdRunner` (testable core) + `SsdCommand` (I/O adapter) means
   unit tests run in 1.3s without an engine, LLM, or daemon. The
   same `SsdRunner` can drive a future Tauri button, desktop menu,
   or RPC.
2. **Prompts belong in YAML, not Java**. The four phase templates
   are long enough that inlining them as Java string constants
   would have made the runner 2x the size and impossible to
   override without a rebuild.
3. **R235's `hardRules` block is the most important
   configuration**. Without it, the model runs tools, calls
   `ask_user_question`, and produces 500KB of duplicated drafts.
   R236 puts the rule set next to the phase templates so any
   future override must keep it (the default is shipped, not
   optional).
4. **The post-tool filter from R235 is unnecessary in-process**.
   `AetherCodeEngine.query()` returns a fresh per-call stream;
   there's no shared notifications buffer to cross-pollinate.
   The engine's own loop detector + the SsdRunner.cleanOutput
   pass handle every noise class that the R235 fix handled.
5. **Bundling beats configuring for SSD specifically**. A
   `gate` workflow step would be a much larger change (engine is
   a single-pass spawner; gate semantics want loops, not steps).
   A dedicated CLI command is the right shape for "spec-first,
   human-confirm per phase".
6. **Project-override fallback should be loud, not silent**.
   `SsdConfig.fromProjectOrBundled` prints a one-line diagnostic
   on `stderr` when the override is broken, then falls back to
   the bundled default. Silent fallback would hide YAML typos;
   throw would block the user from running SSD at all.
