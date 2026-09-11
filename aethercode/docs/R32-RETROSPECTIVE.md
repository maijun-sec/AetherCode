# R32 Retrospective — 2026-08-08

**Theme**: "TUI 用得起来、loop 不写死、授权看得见、项目/任务可管"

## The user complaints that drove R32

After R31, the user actually used the TUI and found 6 concrete problems:

1. **DOS 黑框 + 文字刷新覆盖** — Ink 5 in cmd.exe has no ConPTY,
   the alt-buffer doesn't work, full-screen UI breaks.
2. **内容没有主次** — every tool card expanded, long assistant
   text fully shown, no folding.
3. **没有 task / project 面板** — `TaskSupervisor` exists in
   Java but the TUI doesn't surface it.
4. **授权信息不明** — current permission mode invisible; high-risk
   commands (`rm -rf`, `sudo`) get no special treatment.
5. **不能切 cwd** — no way to switch projects from inside the TUI.
6. **loop 写死** — `ToolLoopDetector` is a fixed sliding-window
   fingerprint match; user wants "动态识别" (dynamic detection).

## What we shipped

### R32-A: Windows TUI rendering fix

| Component              | Before                                 | After                                              |
|------------------------|----------------------------------------|----------------------------------------------------|
| Renderer selection     | always Ink                             | Ink on TTY, **line mode** otherwise                |
| `--tui` / `--line`     | (no flag)                              | explicit overrides                                 |
| `cmd.exe` / `ssh` / CI | broken black box                       | clean line-by-line UI with ANSI                    |

**Key design**: `src/line.ts` is a complete parallel renderer.
It uses Node's `readline` for input, raw-mode `setRawMode` only
when TTY. The `ac-tui.ts` entry point auto-detects
`process.stdin.isTTY && process.stdout.isTTY` and dispatches.

**Critical pitfall (cross-project)**: EOF handling. The
`close` event fires the moment stdin ends. The in-flight
model response (1-3s away) is then lost. Fix: a
`tryExit()` coordination that waits for `inFlight === 0`
before resolving the promise, with a 5s safety timeout.

### R32-B: Collapsible content

- `Turn` gets `collapsed: boolean` and `previewChars: number`
- Default: tool cards start **collapsed** (status + name + 1-line
  args preview); assistant replies stay expanded
- `Ctrl-O` toggles "collapse all"; `Ctrl-E` toggles the most
  recent turn
- `<Tab>` is the "expand/collapse this card" hint in the UI

**Key design**: collapse state lives in the **reducer**, not in
a class hierarchy. The renderer asks `effectiveCollapsed =
state.collapseAll || turn.collapsed`. State is one source of
truth.

### R32-C: Dynamic loop detection

`ProgressLoopDetector` replaces `ToolLoopDetector` with **5
distinct "I'm stuck" patterns**:

| Pattern               | Threshold                | Trigger                                       |
|-----------------------|--------------------------|-----------------------------------------------|
| `same_fingerprint`    | 3 in window 8            | same tool call repeated                       |
| `same_error`          | 3 in a row               | same error message returned N times           |
| `long_output`         | 1500 chars, no tool call | model is "thinking out loud"                 |
| `high_risk_repeat`    | 2 in window              | `bash` / `file_delete` / `file_write` / ...   |
| `user_interrupt`      | immediate                | `notifyUserInterrupt()` from any caller       |

**The detector is dynamic in two ways:**
1. **Configurable**: all thresholds via a `Builder` (no hardcoded magic numbers)
2. **Tool-aware**: high-risk tools use a smaller threshold (2 vs 3) — runaway `rm` is caught before runaway `read`

**Test coverage**: 14 unit tests cover all 5 patterns + builder
validation + fingerprint utils. **No regressions** — 3400
tests pass across all 17 modules.

**Wire-up in QueryEngine**:
- `recordBatch(batch, results, textChars)` — pre-check (no
  results) and post-check (after the batch ran)
- `notifyUserInterrupt()` exposed publicly for Ctrl-C
- 11 new tests in `ProgressLoopDetectorTest`

### R32-D: Permission UX (the big one)

**Before**: TUI's `ToolPermissionPrompter` was an in-process
JLine dialog. Worked fine for line mode, but had no way to
reach an external TUI.

**After**: Standard JSON-RPC 2.0 round-trip:

```
daemon                          TUI
  │                               │
  │─── permission_request ──────►│
  │    {requestId, tool, input,  │
  │     reason, riskLevel}        │
  │                               │ (modal shown, user picks)
  │◄──── permission_response ────│
  │     {requestId, decision:    │
  │      "allow"|"deny"|         │
  │      "always_allow"|         │
  │      "always_deny"}           │
  │                               │
  │ (tool runs or gets denied)    │
```

**Daemon side:**
- `JsonRpcPermissionPrompter` implements `ToolPermissionPrompter`
- `askPermission()` emits the notification and returns a
  `CompletableFuture<PermissionDecision>`
- 60s timeout → automatic deny (no hung clients block workers)
- `classifyRisk(tool, input)` returns `low | medium | high |
  critical` — bash with `rm -rf` / `sudo` / `mkfs` is
  **critical** (red, bold); `file_read` is **low** (green)
- `ProjectPermissionPolicy.setPrompter(p)` + `AetherCodeEngine.setPermissionPrompter(p)` — engine exposes a setter so the daemon can swap the in-process JLine prompter for the JSON-RPC one
- `AetherCodeMethods.PermissionDecision` is the internal record
  returned to the policy

**TUI side:**
- New `PermissionModal` component (double border, risk color, 4 options)
- New `permission_request` notification handler
- `state.permissionAsk: PermissionAsk | null` in the reducer
- Key bindings: `A` = allow, `Y` = always allow, `D` = deny, `N` = always deny
- The input prompt is disabled while a permission is pending
- `line.ts` (line mode) also handles `permission_request` via raw-mode single-keystroke read

**Critical pitfall (cross-project)**: **UTF-8 BOM in PowerShell pipes**.
`Get-Content` piped to `java -jar` emits a leading `\ufeff` which
Jackson's `readTree` rejects. Fix: `JsonRpcCodec.decode()`
strips the leading BOM if present. Without this, **every**
PowerShell-driven daemon test fails.

### R32-E: Project + Task panel (RPC done, TUI panel UI deferred to R33)

**Daemon side** (R32-E.1):
- `listTasks` RPC: walks the transcript, returns the last 50
  tool invocations with `{id, name, status, ts}`. Tool name
  correlated by walking back from the tool_result message to
  the matching tool_use in the same assistant turn.
- `listProjects` RPC: returns the current project (cwd). Real
  multi-project history requires persisting to `~/.aethercode/projects.json`
  (R33 work).

**TUI side** (R32-E.2):
- `/tasks` slash command → calls `listTasks`, prints the result
- `/projects` slash command → calls `listProjects`
- A dedicated **panel UI** (sidebar with task list + status) is
  deferred to R33 — the data plumbing is done, the layout isn't.

### R32-F: CWD switcher (RPC + slash command, daemon switch is a stub)

**Daemon side**:
- `switchProject(cwd)` RPC — implemented as a stub that logs a
  warning ("restart the daemon with --cwd"). A real
  implementation would need to:
  - tear down subprocess-backed tools (`bash`, `web_fetch`, ...)
  - rebuild with the new cwd
  - persist the new cwd to `appState.cwd`
  - emit a `project_changed` notification so the TUI can refresh
- This is a much bigger change than the user might expect; the
  stub is honest about that.

**TUI side**:
- `/cwd <path>` slash command → calls `switchProject` (which
  currently returns "restart required")
- `/projects` already wired (R32-E)

## Numbers

| Metric                            | R31 (0.2.1)  | R32 (0.2.2)  | Δ           |
|-----------------------------------|--------------|--------------|-------------|
| Java tests passing                | 3378         | 3400         | +22         |
| Java modules                      | 17           | 17           | 0           |
| New TUI components                | 8            | 9            | +1 (PermissionModal) |
| TUI bundle size                   | 1.46 MB      | 1.47 MB      | +10 KB      |
| TUI source files                  | 11           | 12           | +1 (line.ts) |
| Permission flow                   | in-process   | JSON-RPC     | cross-process |
| Loop detection patterns           | 1            | 5            | +4          |
| Slash commands                    | 12           | 14           | +tasks, +projects (+2) |

## Key technical decisions

### 1. Two renderers, one entry point

The Ink TUI is the real product; the line mode is the "works
in cmd.exe" fallback. They share the reducer and the JSON-RPC
client. Only the render layer differs. The entry point
auto-detects and routes.

### 2. Permission flow as JSON-RPC, not in-process

The old `ToolPermissionPrompter` was synchronous and in-process.
A JSON-RPC round-trip is async by nature, so the new
`JsonRpcPermissionPrompter` uses a `CompletableFuture` and the
engine's `setPrompter()` setter to swap implementations. This
makes the daemon usable from any client (TUI, multica, custom
orchestrator) without code changes.

### 3. Per-tool risk classification

`classifyRisk(tool, input)` is a heuristic (bash with `rm -rf` =
critical, `file_read` = low, etc.). It's not a security
boundary — the user can still allow a critical tool. But it
makes the **UI** meaningful: red double border for `rm -rf`,
green for `file_read`.

### 4. ProgressLoopDetector with a Builder

The detector is configurable but with sensible defaults. A
production deployment can tighten the thresholds for
high-risk environments, loosen for casual use, or disable
specific patterns. The defaults match the R28-A
behaviour (window=8, threshold=3) for the same_fingerprint
pattern.

### 5. switchProject is a stub (honest about it)

Real CWD switching requires tearing down subprocess-backed
tools and re-initialising the engine. Doing that mid-query
is risky (in-flight tool calls, partial state). The stub
returns `{ok: false, reason: "restart the daemon with --cwd"}`
and logs a warning. **The user can already pass `--cwd` to
the daemon at startup**; the TUI-side command is forward-looking.

## Pitfalls we hit (and learned from)

### UTF-8 BOM in PowerShell pipes (cross-project)

`Get-Content file.json | java -jar daemon.jar` adds a leading
`\ufeff` to stdin. Jackson's `readTree` rejects it. Fix: strip
the BOM in `JsonRpcCodec.decode()`. Without this fix, **every**
daemon test run from PowerShell fails with a confusing
"Unexpected character (code 65279)" error.

### `useInput` event ordering in Ink (cross-project)

When the permission modal is on screen, we want A/Y/D/N keys
to be captured by the modal — not the input box. Solution:
check `state.permissionAsk` **first** in the `useInput`
handler; if non-null, route the key to the modal callback and
return early. The input box is effectively disabled while a
permission is pending.

### Daemon stdout buffered when redirected (cross-project)

`java -jar daemon.jar 1>output.txt` doesn't flush on exit.
`StdioTransport.send()` already calls `writer.flush()`, so
each line is visible. Verified with `node D:\tmp\test-daemon.js`
— all 5 RPC responses came back.

### Loop detector needs both pre and post checks

Pre-check (before running the batch) catches "same fingerprint
N times" before the Nth call wastes resources. Post-check
(after the batch ran) catches "same error N times" because
the errors only exist after execution. The same detector
handles both via the `recordBatch(batch, results, textChars)`
overload.

### Permission policy is `final` on `AetherCodeEngine` (cross-project)

Adding a setter meant changing the field from `final` to
`volatile`. Tests need a `setPrompter` on
`ProjectPermissionPolicy` to actually update the underlying
prompter. The double-setter (engine → policy → prompter) is a
small wart but unavoidable without breaking the existing
constructor API.

## What we didn't do (and why)

- **R32-E TUI panel UI** — the RPC works, the slash command
  works, but the dedicated "task panel" UI (sidebar showing
  recent tool calls with status icons + the projects list) is
  deferred to R33. The data plumbing is done; the layout isn't.
- **R32-F real CWD switch** — the stub is honest. A real
  implementation is bigger than it looks; defer to R33.
- **R32-D permission policy persistence** — `always_allow` /
  `always_deny` decisions should be written to
  `.aethercode/settings.json` (alongside the existing
  allow/ask/deny rules). Currently they're session-local.
- **R32-B search** — `/search` for the scrollback is on the
  list. Not done in R32.
- **R32-C ML-based loop detection** — the user asked for
  "动态" (dynamic) and we delivered heuristics with a
  configurable surface. ML-based detection ("ask the model if
  it's stuck") is expensive and racy; the heuristic approach
  has clear failure modes and is production-ready.

## R32-G follow-up (2026-08-09)

After R32 shipped, the user tested the TUI in a real loop
scenario and reported: *"loop之后，我都看不出来到底结束了
没有"* (after a loop, I can't tell whether it's really done
or still going).

**Root cause**: the `streamEnd` action captured the
`stopReason` parameter but the reducer never used it. The
StatusBar always showed "● ready" regardless of why the run
ended. A loop and a normal end_turn looked identical.

**Fix** (R32-G):
- New `State.lastStopReason: string | null` and
  `State.lastStopKind: "ok" | "loop" | "max_turns" | "error" | "empty" | null`
- `classifyStopReason(reason)` exported function maps daemon
  stop reasons to UI kinds. MiniMax-specific values
  ("stop", "tool_calls", "max_tokens") are correctly classified
  as "ok".
- `submit` action clears the stop state (new run, fresh indicator)
- `StatusBar` colour-codes the ready indicator by kind:
  - `ok` → green border, ●
  - `loop` → red border, ■ "stopped: loop"
  - `max_turns` → yellow border, ▲ "stopped: max turns"
  - `error` → red border, ✗ "stopped: error"
- `Scrollback` shows a sticky round-border banner at the top
  when the last run ended abnormally, with a hint ("the
  daemon stopped the model — type a new prompt to continue")
- `line.ts` mirrors the colour + a follow-up hint

**Tests**: 13 unit tests in `scripts/test/state.test.mjs`
cover classification, reducer transitions, and submit-clears-
state.

**E2E verified**: `echo "say OK" | node ac-tui.js --line`
shows `[ready] (reason: stop)` in green. A loop-detected run
shows `[stopped — loop] (reason: loop_detected)` in red, with
a follow-up hint.



```
D:\work\workspace\idea\engine\AetherCode\aethercode\dist\
├── aethercode-0.2.1.jar      37.5 MB    (R32-B/C/D + R31 features)
└── ac-tui\ac-tui.js          1.47 MB    (R32-A line + B collapse + D modal)
```

End-to-end verified:

```bash
# Ink TUI with R32-B collapse and R32-D modal
java -jar aethercode-0.2.1.jar tui

# Line mode (R32-A fallback for cmd.exe / non-TTY)
java -jar aethercode-0.2.1.jar tui --line

# Daemon over JSON-RPC with R32-D permission + R32-E tasks/projects
java -jar aethercode-0.2.1.jar --daemon
# (then send {method: "ping"} / {method: "getState"} /
#  {method: "listTools"} / {method: "listTasks"} /
#  {method: "listProjects"} / {method: "switchProject"})
```

R32 done. The TUI is now genuinely usable: the user can
fold cards, the loop detector adapts to the situation, the
permission flow is visible, and there's a path to projects
and tasks. R33 work (TUI panel UI, real CWD switch,
permission persistence) is queued.
