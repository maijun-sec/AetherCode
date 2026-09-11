# AetherCode Usage Guide

This is a deeper walkthrough than the top-level README. If you just want to run
something, see `README.md` first.

## 1. Running the shaded jar

```bash
# The build produces a single 35.7 MB jar with all dependencies.
# Location: aethercode-cli/target/aethercode-cli-0.1.0-SNAPSHOT.jar
# (also copied to dist/aethercode-0.1.0.jar for distribution)

java -jar aethercode-0.1.0.jar --version
# -> aethercode 0.1.0

java -jar aethercode-0.1.0.jar --help
# -> full option list
```

### 1.1 API keys

The CLI looks for keys in this order:

1. `--api-key <value>` on the command line
2. `ANTHROPIC_API_KEY` (Anthropic)
3. `MINIMAX_API_KEY` (default in this build — MiniMax-M3 model)
4. `OPENAI_API_KEY` (if `--base-url` is set to an OpenAI-compatible endpoint)

```bash
# Option 1: inline
java -jar aethercode-0.1.0.jar --api-key sk-... --print "hi"

# Option 2: env var
export MINIMAX_API_KEY=sk-cp-...
java -jar aethercode-0.1.0.jar --print "summarise src/main/java"
```

### 1.2 Working directory

By default the agent operates in the current directory. Use `--cwd` to override:

```bash
java -jar aethercode-0.1.0.jar --cwd /path/to/repo --print "find the N+1 query"
```

All tools (`Read`, `Glob`, `Grep`, `Bash`, `Edit`, `Write`, ...) respect this.

## 2. Interactive REPL

```bash
java -jar aethercode-0.1.0.jar
```

The TUI starts in full-screen mode (header bar + status bar + scrollback + input
at the bottom). Type a question, hit Enter. Type `/help` for the slash command
list.

### 2.1 Full-screen vs line mode

```bash
# Default: full-screen (Lanterna alt-buffer)
java -jar aethercode-0.1.0.jar

# Line mode (simpler, works in any terminal):
java -jar aethercode-0.1.0.jar --no-fullscreen

# Disable colours:
java -jar aethercode-0.1.0.jar --no-color
```

Full-screen is auto-disabled when there's no TTY (CI, embedded host).

### 2.2 Vim mode

Hit `Esc` to enter normal mode, `i` to return to insert mode. The mode is shown
in the status bar. Standard motions (`h j k l w b e 0 $ ^` etc.) work.

### 2.3 Slash commands

Type `/` and hit Tab — the popup uses fuzzy matching (subsequence) so
`/hst` finds `/history`, `/sst` finds `/stats` and `/lastplan`.

| Command         | Description                                                |
|-----------------|------------------------------------------------------------|
| `/help`         | List all slash commands                                    |
| `/tools`        | List registered tools                                      |
| `/todos`        | Current plan (in-flight model tasks)                       |
| `/tasks`        | Task tree (with statuses)                                  |
| `/agents`       | List loaded custom agents                                  |
| `/agent <name>` | Switch to a custom agent (re-reads its prompt)             |
| `/state`        | Engine state snapshot (model, mode, transcript size, ...)  |
| `/history`      | Recent prompts                                             |
| `/search <q>`   | Full-text search across the scrollback                     |
| `/select`       | Selection mode prefix (see below)                          |
| `/vim`          | Toggle vim normal mode                                     |
| `/plan`         | Enter plan mode (the agent plans, you approve)             |
| `/approve`      | Approve the current plan                                   |
| `/reject`       | Reject the current plan                                    |
| `/sessions`     | List, fork, or delete saved sessions                       |
| `/resume <id>`  | Resume a saved session                                     |
| `/fork`         | Fork the current session                                   |
| `/jobs`         | List background bash jobs                                  |
| `/job-output <id>` | Print stdout/stderr of a background job                 |
| `/job-kill <id>` | Force-kill a background job                              |
| `/stats`        | Tasks + plan + ETA + trend arrow (↑/↓/·)                  |
| `/lastplan`     | Last completed plan stats                                  |
| `/clear`        | Clear the screen                                           |
| `/exit`, `/quit`| Exit the REPL                                              |

### 2.4 Selection mode

While in the REPL, mouse-drag or Shift+Arrow selects text. Then:

- `/select copy` — copy selection to clipboard
- `/select all` — select all scrollback
- `/select clear` — clear the selection

### 2.5 Plan mode

The agent can enter plan mode automatically when you ask for a complex change.
In plan mode, the model produces a structured plan (steps + dependencies) but
does not execute anything. You see the plan and decide:

- `/approve` — execute the plan
- `/reject` — discard and return to chat
- `/plan` — manually enter plan mode

## 3. Non-interactive (CI / scripts)

```bash
# Run a single turn and print the reply
java -jar aethercode-0.1.0.jar --print "explain what this script does"

# Pipe-friendly: read prompt from stdin is NOT yet supported.
# Workaround: write the prompt to a file and use shell expansion.
PROMPT=$(cat task.txt)
java -jar aethercode-0.1.0.jar --print "$PROMPT"
```

`--print` automatically bumps `--permission-mode` to `BYPASS_PERMISSIONS` so
file edits / bash commands don't block. Override with `--permission-mode PLAN`
if you want headless plan-only mode.

## 4. MCP servers

Create `.aethercode/mcp.json` in the cwd (or pass `--mcp-config`):

```json
{
  "servers": {
    "filesystem": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
    },
    "github": {
      "type": "sse",
      "url": "https://api.example.com/mcp/sse",
      "auth": "oauth"
    }
  }
}
```

Both stdio and SSE transports are wired in `aethercode-mcp`. Tools registered by
MCP servers show up in `/tools` and are called by the model like any other tool.

OAuth dance:

```bash
java -jar aethercode-0.1.0.jar mcp auth github
# -> opens a browser, completes the dance, stores the token
```

## 5. Permissions

`aethercode/settings.json` in the cwd is created on first run:

```json
{
  "permissions": {
    "allow": [
      {"tool": "Bash", "pattern": "go test ./..."},
      {"tool": "Read", "pattern": "**/*.java"}
    ],
    "ask": [],
    "deny": [
      {"tool": "Bash", "pattern": "rm -rf /"}
    ]
  }
}
```

Permission modes:

| Mode                  | When to use                                  |
|-----------------------|----------------------------------------------|
| `DEFAULT`             | Most cases — asks once per (tool, target).   |
| `ACCEPT_EDITS`        | Auto-allow file writes, ask for bash.        |
| `BYPASS_PERMISSIONS`  | CI / `--print` / trusted scripts.            |
| `PLAN`                | Force plan mode.                             |
| `AUTO_READ_ONLY`      | Reads auto-allowed; writes still ask.        |

Read-only tools (`Read`, `Glob`, `Grep`, `ListFiles`, `ListDir`, `Stat`) and a
curated set of safe bash commands (`ls`, `cat`, `head`, `tail`, `find`, `grep`,
`pwd`, `echo`, `wc`, `diff`, `file`, `which`, `type`) NEVER trigger the prompt —
they're on a built-in safe-list (R25-D).

## 6. Memory

The agent uses layered memory at three scopes:

| Scope     | Path                                | Lifetime     |
|-----------|-------------------------------------|--------------|
| `PROJECT` | `<cwd>/.aethercode/memory/`         | Per repo.    |
| `USER`    | `~/.aethercode/memory/`             | Per user.    |
| `AUTO`    | `PROJECT` if `.git/` in any parent, else `USER`. | Default. |

Sub-folders:

- `MEMORY.md` — long-term (always loaded into the system prompt).
- `agent-memory/<agent>/` — per-agent facts (loaded when that agent is active).
- `session/<sessionId>/` — per-session notes.
- `tasks/<taskId>/` — per-task scratch space (R23-E: 256 KB per task, 64 KB per value).
- `share/` — cross-session tagged memory (R26-E: tag search via sidecar `<key>.tags`).

Inspect memory at any time:

```bash
cat .aethercode/memory/MEMORY.md
ls .aethercode/memory/agent-memory/
```

## 7. Sessions

`--sessions-dir .aethercode/sessions` is the default. Each session is a
transcript file. In the REPL:

- `/sessions` — list
- `/sessions fork <id>` — fork a session (creates a new id, copies the transcript)
- `/sessions delete <id>` — delete a session
- `/resume <id>` — switch into a session

## 8. Performance

- 1M-token context windows work (`--context-window 1000000`).
- Sliding-window compaction keeps the prompt under the configured limit
  automatically.
- Streaming tool events — the agent's `→ tool(args)` lines appear as the
  model emits them, not in one batch at the end.
- Token rate is shown in the status bar (R20-B).

### 8.1 Long-running tasks

Two safety nets keep the agent on track during long workflows:

| Safety net | Default | What it does |
|------------|---------|--------------|
| `--max-turns` | 50 | Cap on model turns per query. Set to 0 / -1 to disable (rely on the loop detector alone). |
| `--loop-detect-window` | 8 | Sliding-window size for the loop detector. |
| `--loop-detect-threshold` | 3 | Number of identical tool calls in the window that triggers a stop. |

The loop detector (R28-A) is a sliding-window detector that stops the run
when the same tool call signature (tool name + canonicalised args) appears
`--loop-detect-threshold` times in the last `--loop-detect-window` turns.
This catches the "model is stuck trying the same fix" pathology without
cutting short a legitimate 30-turn refactor.

Disable either guard with `--loop-detect-window 0` or `--max-turns -1`.

## 9. Troubleshooting

### "API key not set"
Set `MINIMAX_API_KEY`, `ANTHROPIC_API_KEY`, or pass `--api-key`.

### The REPL shows "fullscreen init failed, falling back to line mode"
On Windows, Lanterna's full-screen TUI needs the JVM to be launched with
`javaw.exe` (not `java.exe`). R28-B adds an auto-relaunch: if you're on
Windows and the TUI fails to init, the CLI re-execs itself under
`javaw.exe` from the same `JAVA_HOME\bin` directory. You should see a
log line like:

```
INFO  JavawAutoRelaunch - Relaunching under C:\Program Files\Java\jdk-21\bin\javaw.exe
       for fullscreen TUI (Lanterna needs javaw on Windows; see
       https://github.com/mabe02/lanterna/issues/335).
```

If the auto-relaunch fails (e.g. a JRE install without `javaw.exe`), you'll
get a clear warning telling you to either install a full JDK or pass
`--no-fullscreen` to use line mode.

You can also force the line-mode REPL at any time with `--no-fullscreen`.

### The REPL looks broken
Try `--no-fullscreen` — some terminal emulators don't support the alt-buffer
sequence.

### Model says "I cannot access that file"
Check the permission log: `.aethercode/permission-log.jsonl`. The agent's tool
call was probably denied. Either allow it in `settings.json` or pass
`--permission-mode BYPASS_PERMISSIONS`.

### Tests are slow
Default `mvn test` runs the full 1636-test suite (~4 min). Use `-pl <module>`
for a single module:

```bash
mvn -B -pl aethercode-tui test
```

8 pre-existing flaky tests in `aethercode-tui` are skipped by default. To
include them, drop the `-Dsurefire.excludes=...` flag — but be aware they
sometimes fail on slow CI.

### Build fails with "module not found"
Make sure you ran `mvn install -DskipTests` at least once. The `aethercode-cli`
jar pulls every other module via the local Maven repo, not a multi-module
shortcut.

## 10. Advanced

### Custom agents

Drop a `.md` file in `~/.aethercode/agents/<name>.md` (user-wide) or
`<cwd>/.aethercode/agents/<name>.md` (project-scoped, overrides on collision):

```markdown
# Name: refactor-specialist
# Description: aggressive refactor agent, prefers Extract Method + Replace Conditional
You are a refactoring specialist. Prefer Extract Method, Replace Conditional with
Polymorphism, and Introduce Parameter Object. Always run `mvn -B test` after edits.
```

In the REPL: `/agent refactor-specialist` switches to it.

### Embedding the engine in another JVM app

```java
AetherCodeEngine engine = AetherCodeEngine.builder()
    .cwd(Path.of("/some/repo"))
    .model("claude-3-5-sonnet-20241022")
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .build();

engine.query("explain the build system").forEach(event -> {
    if (event instanceof StreamEvent.TextDelta t) System.out.print(t.text());
});
```

See `aethercode-sdk/src/test/java/.../AetherCodeEngineTest.java` for more.

### Hooks

Pre/post tool hooks live in `.aethercode/hooks.json`:

```json
{
  "preToolUse": [
    {"match": {"tool": "Bash"}, "action": "log"}
  ]
}
```

Supported actions: `log`, `deny`, `inject` (extra context into the prompt).
