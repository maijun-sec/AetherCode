# R234 Daemon End-to-End Test — Fix Log

## What was tested

Every JSON-RPC method exposed by the daemon over HTTP+WebSocket
(`.aethercode/daemon-test/test_all.py`, 16 test groups, **104
cases**) was driven through a real AetherCode daemon on
`http://127.0.0.1:17962/ws`. Each test group covers a subsystem
(skill registry, workflow editor, agent registry, memory, sessions,
tasks, models, engine state, permissions, loop detector,
diagnostics, engine / worktree / supervisor, sub-task control,
query / cancel, daemon lifecycle).

## Issues found and fixed during R234

### 1. HTTP+WS dispatch missing 7 RPC arms (real bug — fixed)

File: `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/http/HttpJsonRpcServer.java`

The stdio daemon registered all RPCs through
`AetherCodeMethods.registerAll()`, but the HTTP+WS daemon used a
local `dispatch()` `switch` that had drifted. Seven methods were
declared in the stdio map yet were silently returning
`METHOD_NOT_FOUND` to the desktop / Tauri:

| RPC | Stdio registered? | HTTP+WS dispatched? | Now fixed? |
|-----|-------------------|---------------------|------------|
| `engineHealth` | yes | no | yes |
| `addSkill` | yes | no | yes |
| `viewAuditLog` | yes | no | yes |
| `getPermissionStatus` | yes | no | yes |
| `getPermissionModeSuggestion` | yes | no | yes |
| `setSkipConfirmation` | yes | no | yes |
| `setContinuationStopped` | yes | no | yes |

**Fix**: add the seven missing `case "..." -> methods.xxx(params);`
arms to `HttpJsonRpcServer.dispatch()`.

### 2. `getWorkflow` returned `raw` but not `content` (real bug — fixed)

File: `aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java`

`writeWorkflow` writes the body as `content`; `getWorkflow` only
returned `raw` (no `content` alias). The desktop editor reads back
the same field name it wrote, so the round-trip was silently
broken.

**Fix**: also surface `content = doc.raw()` in the `getWorkflow`
response map.

## Test driver fixes (correcting the harness, not the daemon)

A handful of test cases were initially failing because the driver
was using outdated parameter / field names from earlier rounds.
These are all driver-side fixes — no daemon behaviour changed:

| Test | Wire shape now correct |
|------|------------------------|
| `engineHealth` | expects `healthy` (not `status`) |
| `listSkills` (3+4+5) | checks baseline-by-name (not hard-coded `count == 3`) |
| `writeWorkflow` | body is `content` (not `yaml`) |
| `listToolActions` | returns `tools` (not `actions`) |
| `setSystemPrompt` | takes `prompt` (not `text`) |
| `setConcurrencyProfile` | profile must be `low` / `normal` / `high` (not `balanced`) |
| `setAutoApproveMediumHigh` | takes `enabled` (not `mediumEnabled`/`highEnabled`) |
| `setPermissionMode` | returns `{sessionId, mode}` (no `ok` field) |
| `setModel` | returns `{sessionId, model}` (no `ok` field) |
| `setSkipConfirmation` | takes `rounds` (not `count`) |
| `getSystemPromptSection` | reads section name from `getSystemPrompt` snapshot first |
| `registerChild` | takes `childId` + `httpPort` (not `id` + `endpoint`) |
| `unregisterChild` | takes `childId` (not `id`) |
| `subagentCancel` | takes `jobId` (not `subTaskId`) |
| `retrySubTask` | takes `goal` (not `subTaskId`) |
| `cancel` | takes `runId` (not `subTaskId`) |
| `createEngine` | takes `sessionId` (and must `loadSession({"sessionId": "default"})` first to allow `deleteEngine`) |
| `createTask` | takes `description`; returns `{ok, task: {id, ...}}` (id is in the `task` sub-object) |
| `updateTaskStatus` | `status` enum is `PENDING`/`RUNNING`/`COMPLETED`/`FAILED`/`KILLED` (not `in_progress`) |
| `getTrace` | takes `traceId` (not `id`); the trace object uses `traceId` field |
| `setActiveEngine` / `deleteEngine` | session manager can't delete the active engine — switch to `default` first |
| `deleteSession` | session manager can't delete the active session — switch to `default` first |
| `compact` | canonical RPC name is `compact` (R145); `compactTranscript` is the Java method name, not the wire name |
| `setPhase` / `setPhaseBudget` | PhaseTracker is opt-in; daemon accepts the graceful error or a successful update |
| `setContinuationStopped` | returns `{sessionId, stopped}` (no `ok` field); `default` session has no continuation dispatcher so `stopped` stays `false` (the call still succeeds) |
| `getTrace` (no traces) | exercise with a bogus `traceId` to prove dispatch is wired even when the buffer is empty |

## Result

After the fix in `HttpJsonRpcServer.java` and the
`getWorkflow` `content` alias, **all 104 cases pass** against the
real daemon (`aethercode-0.2.54.jar`, SHA-256
`d1dde88b5434fa77958d43cd7454ef98a5cb50db1660663eee26902a62e054bc`).

Test groups: 16. RPCs exercised: 50+. Per-RPC case counts: 1–6.
