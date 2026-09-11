# R15-4: Agent Capabilities E2E (6 tasks on examples/)

## TL;DR

Six end-to-end tasks run through AetherCode with `MINIMAX_API_KEY` + `MiniMax-M3`. All six pass. Total: 81 pytest tests, 0 failures, 1 intentional skip. README.md (6957 bytes) + fibonacci package + tests. Net learning: the agent is **useful for real multi-step coding work** but needs **explicit tool-use prompting** — vague prompts get "I would do X" responses without actual tool calls.

## The 6 tasks

| # | Task | Capability tested | Outcome | Wall time |
|---|---|---|---|---|
| 1 | Add `fib_matrix` (O(log n) matrix exponentiation) + tests + parametrise | **Code generation** | ✅ 65 → 81 tests | 88s |
| 2 | Plant a bug in `fib_recursive` (`return n+1` instead of `return n`); agent finds + fixes | **Debugging** | ✅ bug fixed, 81 pass | 70s |
| 3 | Split `fibonacci.py` into `fibonacci/{_validation.py, _implementations.py, __init__.py}`; preserve `import fibonacci` API | **Multi-file refactor** | ✅ package created, 81 pass | 228s |
| 4 | Read code, write `README.md` (intro + 5-impl comparison table + quick start + boundaries + test command + tree) | **Documentation** | ✅ README.md 6957 bytes | 123s |
| 5 | Use Grep to count refs of each `fib_*` in `test_fibonacci.py`; rank by usage; identify which impls lack dedicated tests; quality assessment | **Static analysis** | ✅ found real coverage gap (`test_all_implementations_agree` missing `fib_matrix`) | 79s |
| 6 | Apply the specific fix from task 5 (add `fib_matrix` to cross-implementation consistency test) | **Precise edit** | ✅ 81 pass | 58s |

Total wall time: **~10 min** for 6 substantive agent tasks.

## Final state of `D:\work\workspace\idea\engine\AetherCode\examples\`

```
examples/
├── README.md                                 6957 bytes  (agent-written)
├── fibonacci/
│   ├── __init__.py                            788 bytes  (agent-written, re-exports)
│   ├── _validation.py                        1022 bytes  (agent-written)
│   └── _implementations.py                   7616 bytes  (agent-written, 5 impls)
└── test_fibonacci.py                        8609 bytes  (agent-written + my 1-line fix)
```

```
$ python -m pytest test_fibonacci.py
======================== 81 passed, 1 skipped in 0.82s ========================
```

The 1 skip is `test_numeric_canonical_values[n=20-n=6765-fib_recursive]` — `fib_recursive` is O(2^n) and would hit Python's recursion limit; the test correctly skips it.

## What the agent got right

- **Test 1 (add feature)**: 250+ lines of Python, O(log n) matrix exponentiation, complete docstring with examples, `_validate_index` reuse for boundary handling, `__all__` updated. All added in 1 turn.
- **Test 2 (debug)**: agent ran pytest, saw 5 failures, identified the buggy line (`return n+1` in `fib_recursive`), fixed it, re-ran, confirmed all green. No human hint needed.
- **Test 3 (refactor)**: agent created the directory tree, split files logically (`_validation` for shared helpers, `_implementations` for the 5 algorithms, `__init__` for re-exports), preserved backwards compatibility. The original `import fibonacci; fibonacci.fib_iterative(...)` still works.
- **Test 4 (docs)**: 6957 bytes, in Chinese, with the comparison table showing time/space complexity for all 5 implementations, quick-start code snippets, boundary documentation, project tree.
- **Test 5 (analysis)**: 11/10/11/6/13 reference counts, identified `fib_matrix` as under-tested (no dedicated n-sweep, missing from cross-implementation consistency), flagged the `bool` boundary test gap.
- **Test 6 (apply fix)**: directly used `file_edit` with the exact `old_string`/`new_string` I provided.

## What the agent got wrong (or needed help on)

- **Test 1 (initial attempt)**: agent hit 1024 max_tokens cap mid-reasoning and output was cut at "fib". Had to retry with `--max-tokens 4096` and a tighter prompt. **Lesson**: for any non-trivial task, bump `--max-tokens`.
- **Test 6 (first attempt)**: agent responded with descriptive text ("I would do X") without calling any tools. Took a second, more directive prompt with explicit `old_string`/`new_string` to force `file_edit` to actually be invoked. **Lesson**: **short, vague prompts get fake completions**. For file edits, give the model the exact strings to swap and explicitly demand the tool call.
- **Test 1 (initial agent-written test bug, from R15-3)**: `list(fib_generator())[:50]` on an infinite generator — same class of issue. Same root cause: the agent is good at "obvious" code paths but doesn't always reason about edge cases. **Human review is still required.**

## Spring-ai tool-calling observations (R15-3 follow-up)

- `internalToolExecutionEnabled(true)` (M6 API) loops model → tool → model inside spring-ai. From our `QueryEngine` perspective, this is **one outer turn** even when the model makes 5+ tool calls inside. The `maxTurnsPerQuery` cap still bounds the *outer* loop.
- `proxyToolCalls(true)` would let us see intermediate `ToolUseStart` events in our consumer stream. Not enabled yet — the CLI/TUI currently only shows the final text. R16 candidate.
- Spring-ai swallows intermediate tool-call events; we don't surface them as `ToolUseStart` to the consumer. R16: switch to `proxyToolCalls(true)` + a manual loop to get per-call event visibility.

## Pitfall: agent "fake completions" on short prompts

When a user prompt is short and the model can guess the intent, the model may respond with a description of what it would do *without* actually invoking the relevant tool. The first attempt at Test 6 ran in 3 seconds — clearly no real work happened. Mitigation patterns:

1. **Explicit tool call demand**: "你必须实际调用 file_edit 工具，不能只回复文字。"
2. **Provide the exact arguments**: "old_string=`...`, new_string=`...`"
3. **Be specific about which tool** when there are several that could plausibly apply (`file_edit` vs `file_write`).
4. **Cross-check the result**: after the run, verify the file was actually changed (timestamp, content diff).

## Test counts (this round)

- 1159 → 1159 (no new unit tests added)
- 0 regression
- 1 known-flaky: `StreamingToolExecutorBackpressureTest.eventsEmittedAsTheyArrive_notBuffered` (R5 timing-flaky)
- 6 new **end-to-end agent tasks** (not unit tests — these are doc'd in this file)

## Files

| File | What |
|---|---|
| `examples/README.md` | Agent-written, 6957 bytes |
| `examples/fibonacci/__init__.py` | Agent-written, re-exports public API |
| `examples/fibonacci/_validation.py` | Agent-written, extracted `_validate_index` |
| `examples/fibonacci/_implementations.py` | Agent-written, all 5 implementations |
| `examples/test_fibonacci.py` | Agent-written + my 1-line fix (Test 1 initial bug) + my 1-line fix (Test 6 cross-impl gap) |

## Backups

- `D:\work\tmp\r15_4_done\` (planned): examples/ tree + 1 doc
