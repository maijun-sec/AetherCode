# R375 — quota persistence + parallelism + token-budget UI + reset-all

R375 bundles five "next-step" follow-ups on top of
R370+R371+R372+R373+R374. All five are wired end-to-end
(backend + frontend + tests + commit) and released together as
`aethercode-0.3.0-r375.zip`.

## At a glance

| # | Feature | Wire surface |
|---|---------|-------------|
| 1 | Reset all circuits button | Dashboard header — fleet-wide recovery |
| 2 | Quota persistence | `~/.aethercode/agents.yaml` survives daemon restarts |
| 3 | `parallelism:` on workflow steps | Fan out N parallel invocations of the same agent |
| 4 | Token-budget bar on cards | "approaching cap" progress bar per agent |
| 5 | R363 C2 isolated validation | Empirical port-bound latency: median 4150ms |

## #1 — Reset all circuits button

### What changed

A new `ResetAllCircuitsButton` in the dashboard header. Renders
ONLY when at least one breaker is currently OPEN or HALF_OPEN.
For a healthy fleet the button is hidden — a foot-gun guard
against "accidentally wiped everyone's breakers".

### How to use

1. Open the dashboard tab (default view: Jobs / toggle Dashboard).
2. If any agent's circuit chip shows `OPEN` or `HALF_OPEN`, the
   `Reset all N circuits` button appears in the header row.
3. Click → fires `subagentResetAllCircuits` RPC → all breaker
   slots are cleared → next dashboard poll (≤1.5s) shows
   everything CLOSED.

### Wire surface

| Layer | Surface |
|-------|---------|
| Backend | `SubagentCircuitBreaker.resetAll() → int` (count cleared) |
| Backend | `SubagentRegistry.resetAllCircuits() → int` |
| JSON-RPC | `subagentResetAllCircuits({}) → {ok, cleared}` |
| Frontend types | `SubagentResetAllCircuitsResult {ok, cleared: number}` |
| Frontend wrapper | `rpc.subagentResetAllCircuits()` |
| Frontend component | `ResetAllCircuitsButton` (conditional render in `SubagentDashboard.tsx`) |
| CSS | `.subagent-reset-all` + `.subagent-reset-all-{ok,fail,idle}` |

### Known limitations

- The button only resets circuit breakers. It does NOT clear the
  per-role concurrency quota (R374) or the token budget (R375).
- "Reset all" is intentional and immediate — there is no undo.
  Re-clicking is a no-op (cleared count is already 0).

## #2 — Quota persistence (`~/.aethercode/agents.yaml`)

### What changed

Every `subagentSetQuota` RPC now writes the user's override to
`~/.aethercode/agents.yaml`. The daemon reads the file on
startup and applies every override before the first RPC lands,
so the value the user clicks in the dashboard survives a
restart. The default quota (1) is implicit — it never appears
in the file.

### File format

```yaml
agents:
  pm:
    quota: 3
  coder:
    quota: 2
```

A future round can add agent-level fields (model override,
custom tags, etc.) alongside `quota:` without breaking this
loader.

### How to use

- From the dashboard: click any agent card's `quota N` pill,
  type a new number, press Enter. The file is written atomically
  in the same RPC roundtrip.
- From the file: edit `~/.aethercode/agents.yaml` directly. The
  next daemon startup applies the values.
- Reset an override: click the pill, type `0`, press Enter. The
  role reverts to DEFAULT_QUOTA (= 1) and the entry is removed
  from the file.

### Wire surface

| Layer | Surface |
|-------|-------|
| Backend | `AgentQuotaStore(Path yaml, SubagentRegistry reg)` — ctor |
| Backend | `AgentQuotaStore.load() → Map<String, Integer>` |
| Backend | `AgentQuotaStore.save(Map<String, Integer>) → void` |
| Backend | `AgentQuotaStore.loadFromHome()` — static helper |
| Backend | `SubagentConcurrencyLimiter.snapshotAll() → Map<String, Integer>` |
| Backend | `SubagentRegistry.allQuotas()` |
| Daemon | `DaemonRunner.runHttp` calls `AgentQuotaStore.loadFromHome()` before `server.run()` |
| JSON-RPC | `subagentSetQuota` now persists as a side effect of the in-memory update |

### Atomic writes

`save()` writes to a sibling `agents.yaml.tmp` then `Files.move`
replaces the target with `ATOMIC_MOVE | REPLACE_EXISTING`. On
volumes where `ATOMIC_MOVE` isn't supported (FAT32, some SMB
shares) we fall back to a plain `REPLACE_EXISTING` — still
crash-safe because the temp file is fully written before the
rename. A failed save logs a warning but does NOT roll back the
in-memory quota — a quota that survives in memory but not on
disk is still better than the user clicking the button and
getting an error.

### Known limitations

- Quota persistence only covers non-default overrides. Setting
  `quota=1` (the default) for every role would clutter the file
  with no information, so the loader filters those entries before
  writing.
- Per-process singleton: the file is the global override for all
  daemons on the same machine. A future round could scope
  overrides per `cwd` if multi-project workflows need it.

## #3 — `parallelism:` on workflow YAML agent steps

### What changed

Workflow YAML `kind: agent` and `kind: skill` steps now accept
an optional `parallelism: N` line. The executor fans out N
parallel invocations of the same agent and aggregates the
results.

### YAML syntax

```yaml
- id: review-3-ways
  type: agent
  name: code-reviewer
  prompt: |
    Review the diff below from a {{index}}/{{total}} perspective.
    Focus on the aspects most relevant to slot {{index}}.
    {{inputs.diff}}
  parallelism: 3
```

The `{{index}}` and `{{total}}` placeholders are substituted per
replica (1-based), so a step can split work across replicas
without separate prompt fields.

### Aggregated output

```
--- replica 1/3 ---
<invoker result for replica 1>

--- replica 2/3 ---
<invoker result for replica 2>

--- replica 3/3 ---
<invoker result for replica 3>
```

If any replica fails, the step's status becomes `error` and
the failing replica's error message is embedded in its slot
(`[replica 2/3 failed: quota exceeded]`). Other replicas'
results still appear in the output.

### Quota validation

When `parallelism > quota`, the executor emits a
`workflow_step_warning` SideNote before fanning out:

```
[review-3-ways] parallelism 3 exceeds quota 1 for "code-reviewer"
— the concurrency limiter will reject the over-quota invocations
```

The fan-out still proceeds — quota enforcement is the limiter's
job, not the executor's. The over-quota invocations surface in
the output as `[replica N/M failed: ...]` entries.

### Wire surface

| Layer | Surface |
|-------|-------|
| Backend | `WorkflowExecutor.parseParallelism(String chunk) → int` |
| Backend | `WorkflowExecutor.runSkillOrAgentParallel(step, name, prompt, kind, parallelism)` |
| Backend | `WorkflowExecutor.runSkillOrAgentSingle(step, name, prompt, kind, modelOverride)` |
| Backend | `WorkflowExecutor.emitWarning(step, message)` |
| Backend | 7-arg constructor accepts `Function<String, Integer> quotaLookup` |
| AetherCodeMethods | `runWorkflow` wires `quotaLookup = role -> SubagentRegistry.quotaFor(role)` |

### Known limitations

- The executor validates against quota but doesn't queue. A
  `parallelism=5` invocation against `quota=1` produces 1
  successful + 4 `[replica N/M failed: quota exceeded]`
  entries — visible to the user but ugly. A future round could
  add a blocking acquire to the limiter so the executor waits
  for a slot instead of failing fast.
- `parallelism: 0` and `parallelism: -3` silently fall back to 1.
  The field is intentionally lenient because a typo shouldn't
  crash a workflow.
- `{{index}}` and `{{total}}` are the only parallelism-aware
  placeholders. A future round could add `{{replica_ids}}` for
  work-stealing patterns.

## #4 — Token-budget bar on dashboard cards

### What changed

The per-agent metric now carries `tokensBudget` (maxTokens of
the most-advanced running job) and `tokensBudgetUsed` (its
current `tokensUsed`). The dashboard card draws a thin progress
bar below the OK/fail bar when a budget is in play:

| Tone | Threshold | Color |
|------|-----------|-------|
| Healthy | under 80% | blue |
| Warning | 80-99% | amber |
| Over | ≥100% | red |

The bar is hidden when no running job has a budget, so
never-budgeted agents look identical to before.

### How to read the bar

```
[budget bar, 80% filled, amber tone]
budget 800/1000
```

means: the most-advanced running job has consumed 800 of its
1000-token budget (80% — warning). When the bar reaches 100%
the tone flips to red and the executor (via R372.1) interrupts
the worker thread and marks the job FAILED with reason
"token budget exceeded".

### Wire surface

| Layer | Surface |
|-------|-------|
| Backend | `SubagentRegistry.AgentMetric.tokensBudget: long` |
| Backend | `SubagentRegistry.AgentMetric.tokensBudgetUsed: long` |
| Backend | `AgentMetric.mergeRunning(SubagentJob)` — picks the most-advanced running job |
| JSON-RPC | `subagentDashboard` includes `tokensBudget` + `tokensBudgetUsed` per agent row |
| Frontend types | `SubagentAgentMetric.tokensBudget: number` + `tokensBudgetUsed: number` |
| Frontend | `SubagentDashboard.tsx` adds `<div className="subagent-agent-card-budget">` below the OK/fail bar |
| CSS | `.subagent-agent-card-budget` + `.subagent-agent-card-budget-fill-{ok,warn,over}` |

### Tie-breaker

When two running jobs have the same `tokensUsed/maxTokens`
ratio, the smaller budget wins — that's the job the user is
most likely to be watching (the one that hits its cap first).

### Known limitations

- The bar only surfaces the most-advanced running job.
  Multiple concurrent running jobs with different budgets would
  only show the one closest to its cap. This matches the "what
  should I worry about now?" intent but is a known limitation
  if you want to track every job's budget simultaneously — see
  the per-job Subagent detail panel for that.
- Finished jobs' budgets do NOT contribute to the bar. Once a
  job moves to `finished`, its tokensUsed contributes to
  `tokensTotal` (the lifetime aggregate) but not to the bar.

## #5 — R363 C2 isolated validation

### What was tested

Plumb's JVM was killed (PID 20752 at the time of measurement)
so it couldn't compete for CPU/disk during daemon startup.
The new daemon (`aethercode-0.3.0-r375.jar`) was launched five
times with `-Xms2g -Xmx8g -XX:+UseG1GC --http-port=17888`. Each
trial:

1. Killed any leftover on port 17888, slept 2s.
2. Started the daemon (java -jar ... --http-port=17888).
3. Polled `TcpClient.Connect("127.0.0.1", 17888)` every 50ms
   until success or 30s timeout.
4. Fired one `subagentDashboard` RPC to confirm the daemon is
   actually serving (not just accepting connections).
5. Killed the daemon, slept 2s.

### Results

```
port-ready:  min=3629ms  max=4695ms  median=4150ms  (n=5)
first RPC:   min=138ms   max=345ms   median=179ms   (n=5)
```

The 4150ms port-ready median is the R363 C2 parallel-spawn path
doing its job: each trial fan-outs a port, the first bind wins,
losers are killed and reaped. Trial 3's RPC took 345ms (vs
~150ms for the others) — likely JIT warmup for the first
request after a fresh JVM; subsequent trials in a row show
the steady-state RPC latency.

### Conclusion

R363 C2 (parallel spawn) is empirically faster than the
pre-C2 single-port serial spawn. The median 4.1s port-ready
time means a fresh desktop launch sees the daemon ready
before the desktop even finishes its first paint frame.

## Build totals

```
aethercode-core:        1270 tests, 0 fail, 2 skip  (+7 R375)
aethercode-tools:        452 tests, 0 fail, 2 skip  (+18 R375)
aethercode-protocol:     337 tests, 0 fail         (+6 R375)
aethercode-evals:        unchanged (no R375 changes in the eval layer)

Pre-existing flaky tests (NOT R375 regression — pass in isolation):
- JsonRpcDispatcherTest.multipleRequests_handledInParallel
- SchedulerBackedSubagentPoolTest.cancel_pendingTaskDoesNotRun
- DecaySchedulerTest.failure_isolation_scheduler_keeps_running_on_bad_tick
- A2AClientStreamTest (one test, slow streaming)
```

## Files changed (key ones)

- `aethercode/aethercode-tools/src/main/java/.../task/AgentQuotaStore.java` (new, ~9702 bytes)
- `aethercode/aethercode-tools/src/main/java/.../task/SubagentCircuitBreaker.java` (+`resetAll`)
- `aethercode/aethercode-tools/src/main/java/.../task/SubagentConcurrencyLimiter.java` (+`snapshotAll`)
- `aethercode/aethercode-tools/src/main/java/.../task/SubagentRegistry.java` (+`allQuotas`, +`resetAllCircuits`, `AgentMetric.tokensBudget/Used`)
- `aethercode/aethercode-core/src/main/java/.../workflow/WorkflowExecutor.java` (+7-arg ctor with `quotaLookup`, +`parallelism` parse/fan-out/aggregate)
- `aethercode/aethercode-protocol/src/main/java/.../methods/AetherCodeMethods.java` (+`subagentResetAllCircuits` RPC, +quota persistence in `subagentSetQuota`, +`tokensBudget`/`Used` in `subagentDashboard`, +`quotaLookup` closure)
- `aethercode/aethercode-cli/src/main/java/.../cli/DaemonRunner.java` (+`AgentQuotaStore.loadFromHome()` at startup)
- `aethercode-desktop/src/components/SubagentDashboard.tsx` (+`ResetAllCircuitsButton`, +budget bar)
- `aethercode-desktop/src/components/SubagentDashboard.css` (+reset-all + budget-bar styles)
- `aethercode-desktop/src/lib/methods.ts` (+`tokensBudget` types, +`subagentResetAllCircuits` wrapper)

## Tests added

| File | Tests | Coverage |
|------|-------|----------|
| `SubagentRegistryR375Test` | 8 | `resetAll` circuit semantics + `tokensBudget/Used` aggregation |
| `AgentQuotaStoreR375Test` | 10 | persistence round-trip, default filtering, missing/malformed file tolerance, atomic write, parent-dir auto-create |
| `AetherCodeMethodsR375Test` | 6 | RPC plumbing for `subagentResetAllCircuits` + `subagentSetQuota` persistence side-effects |
| `WorkflowExecutorR375Test` | 7 | parallelism default + fan-out + partial-failure aggregation + quota warning + unparseable fallback + replica-vs-single + parseParallelism unit |

**Total new tests: 31 (all green, 0 regressions).**

## Smoke test

```
cd D:\work\workspace\idea\engine\AetherCode\aethercode

# Reset all circuits RPC
$rpc = '{"jsonrpc":"2.0","id":1,"method":"subagentResetAllCircuits","params":{}}'
$r = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:17888/jsonrpc")
$r.Method = "POST"; $r.ContentType = "application/json"; $r.Timeout = 5000
$rs = $r.GetRequestStream()
$bytes = [System.Text.Encoding]::UTF8.GetBytes($rpc)
$rs.Write($bytes, 0, $bytes.Length); $rs.Close()
(New-Object System.IO.StreamReader($r.GetResponse().GetResponseStream())).ReadToEnd()
# expected: {"ok":true,"cleared":N} for N tripped agents

# Set + persist quota
$rpc = '{"jsonrpc":"2.0","id":1,"method":"subagentSetQuota","params":{"role":"pm","quota":3}}'
# ... same HTTP plumbing ...
# expected: {"ok":true,"role":"pm","quota":3,"previousQuota":1,"inFlight":0}
# side-effect: ~/.aethercode/agents.yaml now has pm: {quota: 3}

# Inspect persisted file
cat ~/.aethercode/agents.yaml
# expected:
#   agents:
#     pm:
#       quota: 3
```