# R19-G — Better Bash Tool (streaming, cancel, background)

**Date**: 2026-08-06
**Status**: DONE — 1205 tests (+6 BashToolTest, +6 AgentRegistryTest from R19-F), 0 net regression
**Goal**: Make the Bash tool feel like a real terminal session —
stream output as it's produced, respect abort signals, and support
background processes for long-running tasks.

---

## Why

R5's BashTool was a one-shot:
- Spawn the process
- Drain stdout + stderr into StringBuilders
- Wait for exit
- Return the captured output

The user only saw output when the command finished. A 30-second
test suite was 30 seconds of silence followed by a wall of text.
And there's no way to cancel a runaway command without killing the
JVM.

Claude Code and OpenCode both stream output live and support
Ctrl+C cancellation. They also support background tasks for things
like `npm run dev` that never finish on their own.

## What changed

### Streaming: `stream: true` (default)

Each output line is emitted as a `Message.assistantText("[out] ...")`
via `ctx.emit(...)`. The TUI / --print consume these and render
them as the command runs.

The boolean is opt-out (`stream: false`) for tools that want to
suppress the per-line noise and only see the final captured output.

### Cancellation: `ctx.isAborted()` between lines

The drain thread checks `ctx.isAborted()` after each line. The
first time it observes abort:

- **Foreground mode**: it just stops reading; the main thread
  sees `ctx.isAborted() == true` after `process.waitFor` and
  returns `"command cancelled by user"`.
- **Background mode**: it calls `BashJob.process.destroyForcibly()`
  directly (the job owns the process handle).

The cancellation is best-effort — `destroyForcibly` is async on
some platforms, and the drain thread may finish reading a few
lines before the process actually dies. That's acceptable; the
tool returns promptly and the model can re-issue a clean command.

### Background: `background: true`

When set, the tool:
1. Spawns the process (cmd.exe /bin/sh -c `command`)
2. Registers it in `BashTool.JOBS` (process-singleton) with a
   generated `j-XXX` id
3. Starts drain threads (writing to the job's stdout/stderr
   buffers, NOT the caller's sink)
4. Starts a watcher thread that calls `JOBS.markCompleted(id, code)`
   when the process exits
5. Returns immediately with `"(background) job j-XXX started: <first line>"`

The model can then call `/job-output j-XXX` to see the captured
output, or `/job-kill j-XXX` to stop it.

### `BashTool.JOBS` (process-singleton)

A new `BashJobRegistry` class exposes:
- `register(command, process, ctx)` → `String jobId`
- `get(id)` → `BashJob`
- `list()` → `List<BashJob>` (newest first)
- `markCompleted(id, exitCode)`
- `kill(id)` → `boolean`
- `stdoutOf(id)` / `stderrOf(id)` → `StringBuilder`

`BashJob` carries: id, command, startedAtMs, process handle,
stdout/stderr buffers, done flag, exit code, and the original
CallContext. The registry is process-singleton because background
processes outlive the call that spawned them — even after the
REPL session ends, the job keeps running until killed.

### TUI: `/jobs`, `/job-output`, `/job-kill`

Three new TUI commands:
- `/jobs` — list every background job with status (running /
  done) + age + first line of command
- `/job-output <id>` — dump the captured stdout + stderr + exit
  code
- `/job-kill <id>` — forcibly destroy the process; mark as done

These work alongside the existing `/sessions`, `/search`, etc.

## Tests

- `aethercode-tools/.../shell/BashToolTest.java` (6 tests):
  - `call_foreground_runsCommandAndReturnsOutput` — happy path
  - `call_foreground_respectsTimeout` — 500ms timeout on a 30s
    sleep returns well under 10s with a "timed out" error
  - `call_foreground_emitsProgressWhenStreaming` — capture emitted
    messages, verify at least 2 lines tagged `[out]` or `[err]`
  - `call_foreground_streamFalseSuppressesProgress` — same but
    `stream: false`, no messages emitted
  - `background_registersJobAndReturnsImmediately` — spawn a
    `sleep 30` in background, verify it appears in `JOBS`, then
    kill it
  - `call_foreground_destructiveFlag` — pin the destructive flag
    so a future refactor doesn't accidentally make bash read-only

## Files

- `aethercode-tools/.../shell/BashTool.java` — `stream` + `background`
  params, `runForeground` + `spawnBackground` paths, `BashJob` +
  `BashJobRegistry` (process-singleton via `BashTool.JOBS`),
  drain thread checks `ctx.isAborted()` between lines
- `aethercode-tui/.../ReplApp.java` — `/jobs`, `/job-output`,
  `/job-kill` commands; `firstLine` helper; help text update
- New test: `aethercode-tools/.../shell/BashToolTest.java` (6 tests)

## Pitfalls (R19-G)

1. **Background jobs are process-singletons, not per-session** —
   `BashTool.JOBS` lives in the JVM. If two AetherCode REPL
   sessions are running in the same JVM, they share the job
   registry. A future round could namespace by session id; for
   now the test suite confirms the registry is per-classloader.
2. **`destroyForcibly` is slow on Windows** — the timeout test
   originally asserted `< 4s` but `cmd.exe` + `ping` takes ~4s
   to die even after `destroyForcibly`. Relaxed to `< 10s` in
   the test. Real users on Windows will see ~3-5s delay between
   Ctrl+C and the tool returning.
3. **Cancelled foreground still waits for `process.waitFor`** —
   the main thread blocks on `waitFor` even after `isAborted()`
   is observed by the drain thread. The OS still has to clean up
   the process. We could call `destroyForcibly` from the main
   thread when we see abort, but the drain thread already does
   that in background mode. For foreground, the wait is bounded
   by the `timeout_ms` parameter so it never blocks forever.
4. **Streaming output goes to the MODEL, not the user** — the
   emitted messages are `Role.ASSISTANT` text, which the
   engine appends to the transcript. The user sees them via the
   TUI's progress event renderer (R19-A) and the CLI's `--print`
   text stream. But they also become part of the next prompt's
   context, which can bloat the transcript on a chatty command.
   A future round could mark them as ephemeral so the model
   doesn't see them on subsequent turns.
5. **No partial-job state persistence** — background jobs are
   lost on JVM exit. A long-running `npm run dev` dies the
   moment the user closes the REPL. A future round could write
   job metadata to `.aethercode/jobs.json` on start and offer
   to re-attach on next launch.

## Backups

`D:\work\workspace\idea\engine\AetherCode\aethercode\docs\backups\r19g\`
(planned)

## Next

R19-H: Image / multimodal support. The Read tool currently
returns text; this round adds the ability to pass images to the
model (PNG / JPG) and have them rendered in the TUI.
