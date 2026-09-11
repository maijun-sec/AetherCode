# R17-B: TUI Task Panel + /tasks command

## TL;DR

R17-B extends the TUI to surface the per-task state from R17-A. A new `tasks` panel tab, a `/tasks` command, a `[N running]` task badge in the prompt, and scrollback auto-entries on every task lifecycle change. The full 6-section MiniMax Code layout is still JLine-bound (R18+); this round ships the data-driven pieces that work in JLine.

Real test (MiniMax-M3, 101s): task ID `u-9jhm1mbq` + 4-step TODO list visible in `--print` output, agent writes `logger.py` + `test_logger.py` and runs pytest → **25 passed in 0.32s**. 1168 unit tests, 0 failures.

## What was added

### TUI: 4 new surface points

1. **`tasks` panel tab** — joins the existing `chat / todos / tools / memory / log` tabs. `t` in NORMAL mode cycles to it; `T` cycles backward. Renders all live tasks from `TaskRegistry`:
   ```
   Tasks (3)
     ▶ u-9jhm1mbq  user    "create a file logger.py with a simple logger class..."
     ✓ u-abc123    user    "create hi.py"  (parent=null)
     ✗ u-deadbeef  user    "find missing imports"  (parent=null)
   ```
   - `▶` running (cyan), `✓` completed (green), `✗` failed/killed (red/dim), `○` pending (dim)
   - `id` + `type` (lowercase) + description (truncated to 48 chars) + `(parent X)` if nested

2. **`/tasks` command** — same as the panel but dumped from INSERT mode without going through the tabs. Listed in `/help` next to `/todos`.

3. **`[N running]` prompt badge** — appears next to the existing TODO badge in the prompt. Counts only RUNNING tasks; hides itself when zero:
   ```
   ❯ [2/4] ▶ Create test_logger.py  [1 running]
   ```
   Updated on the next prompt repaint (mid-line ANSI redraw during INSERT is unreliable; deferred).

4. **Scrollback auto-entries** — every `TaskRegistry.onChange` event appends a one-line entry to the scrollback:
   ```
   📦 task ✓ u-9jhm1mbq completed — create a file logger.py with a simple logger class...
   ```
   `📦 task ▶ u-xxx running — ...` for starts, `✗` for failures. The user can `/history` to see the task tree evolve.

### `/help` updated

```
/todos      show the current TODO list (or "t" panel)
/tasks      show the live task tree (USER / AGENT / WORKFLOW)
```

## Architecture

The TUI is reactive on the existing `TaskRegistry.onChange` listener:

```java
// ReplApp constructor:
org.aethercode.tasks.TaskRegistry.instance().onChange(this::onTaskChange);

// onTaskChange:
private void onTaskChange(org.aethercode.tasks.Task t) {
    if (t == null) return;
    String icon = switch (t.status()) { ... };
    scrollback.append("📦 task " + icon + " " + t.id() + " " + t.status().name().toLowerCase()
            + " — " + t.description().lines().findFirst().orElse(""));
}
```

The panel rendering is a pure function over `TaskRegistry.list()` — no caching, no event-driven invalidation. The `PanelTabs` system already handles "render on demand" when the user switches tabs; in INSERT mode the prompt badge reads the registry each repaint.

The full 6-section MiniMax Code layout (top-left new task, bottom-left project list, right-side TODO + deliverables, center-top execution info, center-bottom input, footer with auth mode + model selector) requires a TUI library (lanterna / asciimatics) that supports fixed-position redraw. JLine is line-oriented and cannot reliably redraw mid-prompt without corrupting the input. R18+ is when we add this.

## Real-test verification

```
$ java -jar aethercode-cli-...shaded.jar --cwd examples --print \
  "create a file logger.py with a simple logger class that has
   info/warn/error methods, then create test_logger.py with tests and run pytest"

  [task u-9jhm1mbq started]                ← R17 task ID

📋 TODO plan — 0/4 done (1 in progress)
  ▶ 1. Explore current directory structure
  ○ 2. Create logger.py with SimpleLogger class (info/warn/error)
  ○ 3. Create test_logger.py with pytest tests
  ○ 4. Run pytest and report results

📋 TODO plan — 1/4 done (1 in progress)
  ✓ 1. Explore current directory structure
  ▶ 2. Create logger.py with SimpleLogger class (info/warn/error)
  ...

📋 TODO plan — 4/4 done
  ✓ 1. ...
  ✓ 2. ...
  ...

## Summary
**logger.py** — SimpleLogger class
**test_logger.py** — 25 tests
**Pytest result:** 25 passed in 0.30s
```

R16-b (TODO visibility) + R17-A (task ID) + R17-B (TUI panel/cmd/badge) all compose cleanly.

## Files changed

| File | Lines | What |
|---|---|---|
| `aethercode-tui/pom.xml` | +4 | Add `aethercode-tasks` dependency. |
| `aethercode-tui/src/main/java/.../ReplApp.java` | +60 / -3 | `tasks` panel tab; `renderTasksPanel`, `onTaskChange`, `taskBadge`; `/tasks` command; `/help` updated. |

## Test counts

- 1168 unit tests, 0 failures, 112 test files
- 0 net regression
- 0 known-flaky in this run (R5 timing-flaky tests pass on full run today)

## What's still missing (R17-C, R17-D, R18+)

- **R17-C**: `AgentTool` (the tool that spawns subagents — each gets a fresh `Task` ID and isolated `TaskContext`). The data layer is ready; the tool class itself needs to be written.
- **R17-D**: `--context-window` CLI flag → wire to `AetherCodeEngine.Builder.contextWindow(...)` → `QueryEngine` → `AutoCompact(chatClient, ctx, 1_000_000, ...)` for million-token support.
- **R18**: full MiniMax Code 6-section TUI via `lanterna` (or equivalent), with fixed-position redraw for live updates during agent execution.

## Backups

- `D:\work\workspace\idea\engine\AetherCode\aethercode\docs\backups\r17b\` (planned)
