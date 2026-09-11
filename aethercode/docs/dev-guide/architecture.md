# Architecture

A 30,000-ft view of AetherCode. Suitable for someone joining the
project who wants to understand the runtime flow.

## Process topology

```
┌─────────────────────────────────────────────────────────────────────┐
│  TUI process (Node + Ink)                                           │
│  ┌─────────────────────┐                                            │
│  │  src/tui.tsx         │  ← React/Ink app, useReducer state         │
│  │  src/line.ts         │  ← non-TTY fallback renderer               │
│  │  src/state.ts        │  ← reducer + State                        │
│  │  src/commands.ts     │  ← slash-command dispatcher               │
│  │  src/components/*    │  ← Header, StatusBar, InputBox, …         │
│  │  src/JsonRpcClient   │  ← spawn + pipe to daemon                 │
│  └─────────────────────┘                                            │
│              │ JSON-RPC 2.0 over stdio                              │
└──────────────┼──────────────────────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Daemon process (Java)                                              │
│  java -jar dist/aethercode-0.2.1.jar --daemon                        │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  aethercode-cli/DaemonRunner                               │    │
│  │  → StdioTransport (line-delimited JSON-RPC)                │    │
│  │  → JsonRpcDispatcher                                      │    │
│  │  → AetherCodeMethods (registers 15+ methods)               │    │
│  │  → AetherCodeEngine (the public SDK)                       │    │
│  │     → QueryEngine (LLM turn loop)                          │    │
│  │     → ToolOrchestrator (batched tool execution)           │    │
│  │     → ProgressLoopDetector (5 patterns)                    │    │
│  │     → ProjectPermissionPolicy (allow/deny/always)         │    │
│  │     → MetricsCollector (per-engine counters)              │    │
│  └────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

## Module layout

```
aethercode/                          ← monorepo root
├── pom.xml                          ← Maven multi-module config
├── build.ps1                        ← canonical build script
│
├── aethercode-core/                 ← engine primitives (no I/O)
│   ├── engine/                       QueryEngine, ProgressLoopDetector
│   ├── tool/                         Tool interface, ToolResultCache
│   ├── message/                      ContentBlock, Message
│   ├── cost/                         CostBudget, CostTracker
│   ├── metrics/                      MetricsCollector  (R77)
│   ├── log/                          JsonlLogger
│   ├── ...                           20+ packages
│
├── aethercode-sdk/                  ← public API
│   └── AetherCodeEngine              ← the entry point
│
├── aethercode-protocol/             ← JSON-RPC layer
│   ├── jsonrpc/                      codec, transport, dispatcher
│   ├── methods/AetherCodeMethods     RPC method handlers
│   └── permissions/                   JsonRpcPermissionPrompter
│
├── aethercode-cli/                  ← main + DaemonRunner
├── aethercode-tui/                  ← TypeScript Ink TUI (separate)
├── aethercode-tasks/                 Task/Subagent types
├── (R95-C retired aethercode-tui-jline — the Java REPL is gone; the Ink TUI is the only interactive surface)
└── ... (12 more)
```

## Data flow: a single query

1. **User types a prompt** in the Ink TUI input box, presses Enter.
2. `tui.tsx` dispatches `submit` (clears `lastError`, adds the
   user turn to `state.turns`).
3. `tui.tsx` calls `client.request("query", { prompt })`.
4. `JsonRpcClient` (TUI side) writes a JSON-RPC line to the
   daemon's stdin.
5. **Daemon** receives the line, dispatches to
   `AetherCodeMethods.query(prompt)`.
6. `query` spawns a thread that runs `engine.query(prompt)` and
   forwards each `StreamEvent` as a `stream_event` notification.
7. **Engine** drives the LLM turn loop:
   - `run_start` event
   - `text_delta` events as the model streams
   - `tool_use_start` event when the model emits a tool call
   - execute the tool (via `ToolOrchestrator`)
   - `tool_result` event
   - `run_end` event with the stop reason
8. **Daemon** wraps each event in a JSON-RPC notification and
   writes to stdout.
9. **TUI** receives the line, dispatches the corresponding action
   (e.g. `streamText` appends to the assistant turn; `streamToolEnd`
   marks the tool as completed; `streamEnd` sets `lastStopKind`).
10. The user sees the new content, the status bar updates, and the
    spinner stops.

The TUI never blocks waiting for events; it's event-driven via the
JSON-RPC notification stream.

## Process isolation

The TUI and daemon are separate processes. This gives us:
- **Crash isolation**: a daemon crash doesn't kill the TUI's UI
  state. The TUI shows an error card and lets the user restart.
- **No Node ↔ Java bridge**: we don't need JNI or GraalVM. The
  TUI is plain Node; the daemon is plain Java.
- **Testability**: the daemon can be tested headlessly via
  JSON-RPC. The TUI can be tested with a mock JSON-RPC client.

## Concurrency model

- **TUI side**: React's `useReducer` is single-threaded by design.
  All state mutations go through the reducer. Side effects
  (timers, RPC calls, file I/O) live in `useEffect` or event
  handlers.
- **Daemon side**: a single thread per `query` call. Tools run
  sequentially within a batch but the `query` method itself is
  one query at a time. (R64 will introduce parallel tool calls
  within a batch.)
- **Engine internals**: `MetricsCollector` uses `AtomicLong` for
  thread safety (8 threads × 1000 inc test in R77).

## See also

- `api/jsonrpc.md` — the wire protocol
- `api/state-model.md` — the TUI's state shape
- `dev-guide/adding-rounds.md` — how to add a new R-round
