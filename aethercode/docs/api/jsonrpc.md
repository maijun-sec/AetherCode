# JSON-RPC API

AetherCode's CLI daemon speaks JSON-RPC 2.0 over stdio. This page
documents every method and notification the daemon exposes as of
**R79** (2026-08-09). Round numbers in parentheses show when the
method/notification was added.

## Connection model

- The daemon is launched via `java -jar ... --daemon`.
- It reads line-delimited JSON-RPC 2.0 messages from stdin.
- It writes responses + notifications to stdout.
- Stderr is reserved for daemon logs (the TUI captures these in the
  log buffer, R56).
- **UTF-8 BOM is stripped** at the codec layer (R32 fix for
  PowerShell pipes that prepend `\ufeff`).

## Methods (request → response)

| Method | Round | Params | Result |
|---|---|---|---|
| `ping` | R29 | `{}` | `{version, uptimeMs, model, sessionId}` |
| `getState` | R29 | `{}` | `{sessionId, model, permissionMode, toolCount, tools, transcriptSize, contextWindow, maxTurns, loopWindow, loopThreshold}` |
| `listTools` | R29 | `{}` | `{tools: [{name, description}, ...]}` |
| `setModel` | R29 | `{model: string}` | `{ok: true}` |
| `setPermissionMode` | R29 | `{mode: "DEFAULT" \| "ACCEPT_EDITS" \| "BYPASS_PERMISSIONS" \| "PLAN" \| "AUTO_READ_ONLY"}` | `{ok: true}` |
| `setSystemPrompt` | R29 | `{prompt: string}` | `{ok: true}` |
| `query` | R29 | `{prompt: string}` | `{runId, accepted: true}` (events stream via `stream_event` notifications) |
| `cancel` | R29 | `{runId: string}` | `{ok: true}` |
| `listSessions` | R29 | `{}` | `{sessions: [...]}` |
| `loadSession` | R29 | `{id: string}` | `{ok: true}` |
| `listTasks` | R32-E | `{limit?: number}` (default 50) | `{tasks: [{id, name, status, ts}], count}` |
| `listProjects` | R32-F | `{}` | `{projects: [{cwd, name, active}]}` (currently single project = current cwd) |
| `switchProject` | R32-F | `{cwd: string}` | `{ok: false, reason: "stub; restart with --cwd"}` (honest stub) |
| `permissionResponse` | R32-D | `{requestId, decision: "allow"\|"deny"\|"always_allow"\|"always_deny", reason?}` | `{ok, requestId, decision}` |
| `getMetrics` | **R77** | `{}` | `{uptimeMs, turnsStarted, turnsCompleted, toolCalls, toolErrors, toolRetries, loopStops, permissionAsks, permissionDenies, cacheHits, cacheMisses, costUsd, errorRate, cacheHitRate}` |
| `getTraces` | **R78** | `{limit?: number}` (default 10, max 256) | `{inFlight, completed, traces: [{traceId, name, parentSpanId, startMs, endMs, status, durationMs, attrs}, ...]}` |
| `getTrace` | **R79** | `{traceId: string}` (required) | `{traceId, inFlight, completed, spans: [...]}` — one root + all descendants in BFS order |

## Notifications (server → client, no response)

| Notification | Round | Params |
|---|---|---|
| `stream_event` | R29 | `{runId, event}` where `event.type` is one of `run_start` / `text_delta` / `tool_use_start` / `tool_result` / `run_end` / `side_note` / `plan` |
| `task_state` | R29 | `{taskId, status, runId?, name?}` |
| `log` | R29 | `{level: "info"\|"warn"\|"err", message}` (R41 also fires from tool calls) |
| `permission_request` | R32-D | `{requestId, runId, tool, input, reason, riskLevel: "low"\|"medium"\|"high"\|"critical"}` |

## Stream event shapes

### run_start
```json
{"type": "run_start", "runId": "run-1", "model": "MiniMax-M3"}
```

### text_delta
```json
{"type": "text_delta", "text": "Hello, "}
```

### tool_use_start
```json
{"type": "tool_use_start", "id": "call_abc", "name": "glob", "input": {"pattern": "*.java"}}
```

### tool_result
```json
{"type": "tool_result", "id": "call_abc", "content": "A.java\nB.java", "isError": false}
```

### run_end
```json
{"type": "run_end", "stopReason": "stop" | "end_turn" | "loop_detected" | "max_iterations" | "error" | "max_tokens"}
```

### side_note
```json
{"type": "side_note", "kind": "task" | "memory" | "info" | "completion" | "snippet" | "rpc" | "metrics" | "history", "message": "..."}
```

## Stop reason classification (R32-G, line.ts/state.ts)

The TUI maps `run_end.stopReason` to a visual `lastStopKind`:

| stopReason | lastStopKind | Visual |
|---|---|---|
| `"end_turn"`, `"stop"`, `"tool_calls"`, `"max_tokens"`, `"length"` | `ok` | green border |
| `"loop_detected"`, `"loop_*"` | `loop` | red border + sticky banner |
| `"max_iterations"`, `"max_turns"`, `"max_tool_iterations"` | `max_turns` | yellow border + sticky banner |
| `"error"`, `"rpc_error"`, `"*_error"`, `"error_*"` | `error` | red border + sticky banner |
| `"empty_input"`, `"empty"` | `empty` | dim |
| anything else | `ok` | green (conservative default) |

**Important**: the classifier uses `r.startsWith("loop_")` (NOT
`r.includes("loop")`) to avoid matching "main_loop". Similarly
`r === "max_iterations"` (NOT `r.includes("max_")`) to avoid matching
"max_tokens". See `state.ts:classifyStopReason` and
`line.ts:classifyStopReason` — they must stay in sync. A test
(`scripts/test/line-runend.test.mjs`) enforces this.
