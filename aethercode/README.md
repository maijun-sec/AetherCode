# AetherCode

> A Java 21 port of Claude Code — local-first AI agent with headless daemon + TypeScript TUI + IntelliJ IDEA plugin.
> **31 rounds, 1679 tests, headless backend + polished Ink-based TUI, JSON-RPC 2.0 protocol, unified single-project layout.**

AetherCode is a from-scratch re-implementation of the Claude Code agent (the TypeScript
[claude-code-analysis-main/src](claude-code-analysis-main/src) reference) in **pure Java 21**.
No Node, no TypeScript, no JNI — just the JVM for the engine. The presentation layer
(an Ink-based TUI) is a small Node.js process that talks to the engine over JSON-RPC 2.0
over stdio. The engine drives the same Model Context Protocol (MCP), the same tool
pipeline, the same memory and permission model, but speaks Java records end-to-end and
ships as a single executable shaded jar that drops into a build, a CI step, an
IntelliJ plugin host, or a developer's terminal.

```
┌────────────────────────┐    ┌──────────────────────┐    ┌──────────────────────┐
│  ac-tui (TypeScript)   │    │  aethercode-tui-      │    │  IntelliJ plugin     │
│  Ink + React           │    │  jline (fallback)     │    │  (platform module)   │
│  aethercode/aethercode-│    │  aethercode/          │    │                      │
│  tui/  (R30, R31 v2)   │    │  (Java REPL retired) │    │                      │
└──────────┬─────────────┘    └──────────┬─────────────┘    └──────────┬───────────┘
           │ JSON-RPC 2.0 / stdio        │                              │
           └─────────────┬───────────────┴──────────────────────────────┘
                         │
                  ┌──────▼─────────┐   aethercode-cli --daemon
                  │  aethercode-  │   java -jar aethercode.jar (36 MB)
                  │    sdk        │   headless, no UI
                  └──────┬────────┘
                         │
        ┌────────────────┼────────────────────────┐
        │                │                        │
   ┌────▼─────┐   ┌──────▼───────┐   ┌─────────────▼────┐
   │  core    │   │  permission  │   │  engine-         │
   │  (Tool,  │   │  (allow/ask/ │   │  springai        │
   │   Query, │   │   deny)      │   │  (Anthropic +    │
   │   State) │   │              │   │   OpenAI comp.)  │
   └──────────┘   └──────────────┘   └──────────────────┘
```

The TUI / daemon split (R29-R31) decouples the agent runtime from the
presentation layer. The TypeScript `ac-tui` is one of several possible
clients — `multica`, custom orchestrators, or your own scripts can drive
the daemon via the same JSON-RPC 2.0 interface over stdio. See
[`aethercode/aethercode-tui/README.md`](aethercode-tui/README.md) for the
protocol spec and the TUI itself.

## Documentation

User-facing documentation lives in [`../doc/`](../doc/) (one level up
from this Maven root). The doc set covers getting started, daily
use, architecture, packaging, providers, agents, workflows,
troubleshooting, and the full JSON-RPC API surface.

R-round retrospectives — the development history with design notes
— live in `../aethercode-desktop/docs/R<NN>-<TOPIC>-<DATE>.md`.

## What's in 0.2.1

- **TUI v2** — the Ink TUI is now genuinely pleasant to use. Warm amber
  theme, boxed tool call cards (status icon + name + args + result),
  plan / todo list panel, Markdown rendering for assistant responses,
  input history (↑/↓), help overlay (Ctrl-?), token / cost counters in
  the status bar, and a welcome banner with model + session + cwd.
- **`aethercode tui` subcommand** — one command launches the Ink TUI:
  ```
  java -jar dist\aethercode-0.2.1.jar tui
  ```
  No more `node ac-tui.js` incantations. The subcommand auto-detects
  the TUI bundle next to the jar and the node binary on PATH.
- **Single-project layout** — the TypeScript TUI now lives at
  `aethercode/aethercode-tui/` (inside the Maven project) instead of
  the sibling `aethercode-tui-ts/`. One repo, one `build.ps1`.
- **`aethercode-tui-ts`** (R31+ shipping TUI) — Ink + React TUI bundle
  that ships next to the jar in `dist/ac-tui/ac-tui.js`. The canonical
  interactive surface; launched by `aethercode tui` or by running the
  cli with no arguments (R95-C: the cli now auto-delegates to the TUI
  instead of starting an in-process REPL).
- **`build.ps1` upgraded** — runs Java + TS TUI in one shot, robust
  against JDK 17+ "restricted method" stderr noise.
- **No regressions** — 1675 tests pass, 0 fail, 0 skip.

## Why a Java port

- **Drop into the JVM** — the same agent can be driven from a CLI, embedded in a Gradle
  task, hosted inside IntelliJ, or wired into a Spring service. No `node`, no `tsx`, no
  cross-runtime friction.
- **Headless daemon + JSON-RPC 2.0** (R29) — the engine runs as a headless process
  (`java -jar aethercode.jar --daemon`) and exposes a clean JSON-RPC 2.0 interface
  over stdio. The TypeScript TUI is just one possible client; `multica` or any
  other orchestrator can drive the agent the same way Claude Code does.
- **Modern TUI** (R30) — a separate `@aethercode/tui` package (Ink + React) replaces
  the JLine / Lanterna REPL with a much nicer interactive experience. Cross-platform,
  no terminal-API issues, and you can run it from anywhere as long as the daemon is
  reachable.
- **Strong types everywhere** — the Tool protocol, permission system, and memory layers
  use `record`, sealed types, and pattern matching. A 200-line spec is enforceable at
  compile time.
- **Virtual threads** — `query()` uses a structured-concurrency loop backed by
  `Thread.ofVirtual()` rather than callbacks.
- **Native MCP** — `aethercode-mcp` ships a stdio + SSE client; any external MCP server
  plugs into the same tool pool.
- **No external services required** — runs against the same Anthropic Messages API the
  TS version uses, but with optional `--base-url` for any OpenAI-compatible endpoint
  (e.g. MiniMax, internal proxies, or a local llama.cpp).

## Quick start

### 1. Run the prebuilt jar

```bash
# download or copy dist/aethercode-0.1.0.jar (35.7 MB, includes all deps)
java -jar aethercode-0.1.0.jar --help
```

You will need an API key. Pick one of:

```bash
# Anthropic (default model)
export ANTHROPIC_API_KEY=sk-ant-...

# MiniMax (default in this build)
export MINIMAX_API_KEY=sk-cp-...

# OpenAI-compatible
export OPENAI_API_KEY=sk-...   # and pass --base-url https://api.openai.com
```

The env var is picked up automatically. You can also pass it inline with `--api-key`.

### 2. Interactive TUI (R95-C: Ink-based, default)

```bash
# macOS / Linux / Windows: same one-liner, same TUI
java -jar aethercode-0.2.1.jar

# Or call the TUI subcommand explicitly
java -jar aethercode-0.2.1.jar tui
```

The cli's default interactive surface is the Ink-based TUI
(`aethercode-tui/`), which ships next to the jar in
`dist/ac-tui/ac-tui.js` and is launched as a Node child process. It
talks to a daemon over JSON-RPC stdio, so the jar is the same one
you'd run for `--print` or `--daemon`. R95-C retired the previous
in-process Java REPL (JLine + Lanterna) — the cli now delegates to
the TUI by default. Headless users (no Node.js) can fall back to
`--print "..."` for one-shot queries or `--daemon` for batch use.

### 3. One-shot non-interactive

```bash
java -jar aethercode-0.2.0.jar --print "summarise the contents of README.md"
```

`--print` runs a single turn, prints the model's reply, and exits. Permission mode is
automatically bumped to `BYPASS_PERMISSIONS` so file edits and bash commands don't
block on a prompt.

### 4. Headless daemon (R29)

The Java process can also run as a headless JSON-RPC 2.0 daemon. Spawn it from
any orchestrator (multica, your own scripts, etc.):

```bash
# Spawn the daemon; it reads JSON-RPC from stdin and writes to stdout.
java -jar aethercode-0.2.0.jar --daemon

# One request (line-delimited JSON over stdin):
echo '{"jsonrpc":"2.0","id":1,"method":"ping"}' | java -jar aethercode-0.2.0.jar --daemon
# Response (one line on stdout):
# {"jsonrpc":"2.0","id":1,"result":{"version":"...","uptimeMs":42,"model":"MiniMax-M3",...}}
```

See [`aethercode-tui-ts/README.md`](aethercode-tui-ts/README.md) for the
full protocol (methods + notifications). The bundled TypeScript TUI
(`dist/ac-tui/ac-tui.js`) uses this interface to talk to the daemon.

### 5. Modern TypeScript TUI (R30 / R31)

The recommended interface for day-to-day use. Polished, fast, cross-platform,
and built on [Ink](https://github.com/vadimdemedes/ink) (React for the CLI).

**Easiest path — via the `tui` subcommand:**

```bash
# Spawns the bundled TUI bundle (dist/ac-tui/ac-tui.js), which
# in turn spawns the daemon in --daemon mode. You do not need
# to find the jar or remember the node path.
java -jar aethercode-0.2.1.jar tui

# One-shot:
java -jar aethercode-0.2.1.jar tui --print "what is 2+2"
```

**Direct path — run the bundle yourself:**

```bash
node dist/ac-tui/ac-tui.js                # auto-detects the jar
AETHERCODE_JAR=path/to/jar node ac-tui.js # explicit jar
```

**As an npm package** (after `npm publish` — work-in-progress):

```bash
npx @aethercode/tui
```

Features:

- **Welcome banner** with model, session, cwd on first run
- **Boxed tool call cards** (status icon + name + args + result preview)
- **Plan / todo list** panel
- **Markdown rendering** in assistant text (bold / italic / code / lists / code blocks)
- **Input history** (↑ / ↓) — last 200 prompts
- **Help overlay** (Ctrl-? or F1)
- **Status bar** with token counts and cost
- **Slash commands** (see `/help` in the TUI)

### 6. Build from source

```bash
cd AetherCode/aethercode
.\build.ps1                       # full test + Java + TUI bundle
.\build.ps1 -SkipTests            # package only (Java + TUI bundle)
.\build.ps1 -Module aethercode-core   # single Java module
```

The unified `build.ps1` produces two artefacts in `dist/`:

```
dist/
├── aethercode-0.2.1.jar          # the Java engine + shaded CLI (35.76 MB)
└── ac-tui/
    ├── ac-tui.js                 # the Ink TUI bundle (1.42 MB)
    ├── package.json
    └── README.md
```

If you only want the Java side, pass `-SkipTui`. If you only want a
single module, pass `-Module <name>` and the TUI is skipped automatically.

## Module map

```
AetherCode/aethercode/                    Maven multi-module root
├── aethercode-core              Tool protocol, Message, QueryEngine, AppState
├── aethercode-engine-springai   Anthropic Messages + MiniMax + OpenAI compat
├── aethercode-permission        allow/ask/deny rules, sandbox, safe-list
├── aethercode-memory            MEMORY.md, auto memory, session, task, share
├── aethercode-mcp               stdio + SSE MCP client
├── aethercode-skills            Skills / slash command system
├── aethercode-hooks             Pre/Post tool hooks
├── aethercode-compact           Sliding-window context auto-compaction
├── aethercode-prompts           System prompt assembly
├── aethercode-tools             Bash / Read / Edit / Write / Glob / Grep / etc.
├── aethercode-tasks             TaskRegistry, plan executor, watchdog, heartbeat
├── (R95-C retired aethercode-tui-jline; Ink TUI is now the only interactive surface)
├── aethercode-bridge            Cross-process swarm coordination
├── aethercode-protocol          R29: JSON-RPC 2.0 (stdio + Dispatcher + AetherCode methods)
├── aethercode-sdk               Public SDK for embedding the engine
└── aethercode-cli               Picocli entry point + shaded jar
aethercode-tui/                          R31: TypeScript Ink TUI (npm project, lives under the Maven root)
```

The TypeScript TUI is a separate npm project that lives **inside** the
Maven root (sibling of `aethercode-core`, `aethercode-cli`, etc.).
`build.ps1` builds both sides in one shot and the TUI bundle is copied
to `dist/ac-tui/` next to the Java jar.

The IDEA plugin lives one level up at `../idea-plugin/` (Gradle sub-project — the
JetBrains platform toolchain is Gradle-native).

## Slash commands (interactive REPL)

| Command       | What it does                                          |
|---------------|-------------------------------------------------------|
| `/help`       | Show this list                                        |
| `/tools`      | List available tools                                  |
| `/todos`      | Show the current plan / todo list                     |
| `/tasks`      | Show the task tree                                    |
| `/agents`     | List loaded custom agents                             |
| `/agent <n>`  | Switch to a custom agent                              |
| `/state`      | Snapshot of engine state (model, mode, transcript)    |
| `/history`    | Recent prompts                                        |
| `/select`     | Selection-mode prefix (`/select copy`, `/select all`) |
| `/vim`        | Toggle vim normal mode                                |
| `/plan`       | Enter plan mode                                       |
| `/approve`    | Approve the current plan                              |
| `/reject`     | Reject the current plan                               |
| `/sessions`   | List, fork, or delete saved sessions                  |
| `/resume <id>`| Resume a saved session                                |
| `/fork`       | Fork the current session                              |
| `/jobs`       | List background bash jobs                             |
| `/job-output` | Print stdout/stderr of a job                          |
| `/job-kill`   | Force-kill a background job                           |
| `/search <q>` | Search the scrollback                                 |
| `/stats`      | Tasks + plan summary + ETA + trend arrow              |
| `/lastplan`   | Last completed plan stats                             |
| `/clear`      | Clear the screen                                      |
| `/exit`       | Exit the REPL                                         |

Type `/` and hit Tab to fuzzy-complete (R23-A subsequence matcher, e.g. `/hst` → `/history`).

## CLI flags

```text
Usage: aethercode [-hpvV] [--fullscreen] [--no-color] [--no-fullscreen]
                  [--api-key=<apiKey>] [--base-url=<baseUrl>]
                  [--context-window=<contextWindow>] [--cwd=<cwd>]
                  [--max-tokens=<maxTokens>] [--max-turns=<maxTurns>]
                  [--mcp-config=<mcpConfig>] [--model=<model>]
                  [--permission-mode=<permissionMode>]
                  [--sessions-dir=<sessionsDir>] [<prompt>] [COMMAND]

      [<prompt>]             Optional prompt for non-interactive mode (with --print).
      --api-key=<apiKey>     API key (or set MINIMAX_API_KEY / ANTHROPIC_API_KEY).
      --base-url=<baseUrl>   Override the API base URL (advanced).
      --context-window=<n>   Context window in tokens (default 0 = 200K; 1_000_000 for 1M).
      --cwd=<cwd>            Working directory. Default: current dir.
      --fullscreen           Open the full-screen TUI (default on).
      --max-tokens=<n>       Max tokens in the model's response (default 4096).
      --max-turns=<n>        Cap on model turns per query (default 10; 0/-1 = unbounded).
      --mcp-config=<path>    Path to mcp.json. Default: .aethercode/mcp.json.
      --model=<id>           Model id. Default: MiniMax-M3.
      --no-color             Disable ANSI colours in the TUI.
      --no-fullscreen        Use the line-mode REPL.
  -p, --print                Run a single turn non-interactively and exit.
      --permission-mode=<m>  DEFAULT | ACCEPT_EDITS | BYPASS_PERMISSIONS | PLAN | AUTO_READ_ONLY.
      --sessions-dir=<path>  Multi-session transcript directory. Default: .aethercode/sessions.
  -v, --verbose              Verbose logging (DEBUG level for the LLM client).
  -V, --version              Print version and exit.
Commands:
  mcp                        MCP server utilities (auth, list).
  tui                        R31: launch the bundled Node-based Ink TUI (auto-spawns the daemon).
```

## Permissions model

AetherCode uses a three-way allow / ask / deny model with persistent per-project
settings. The file `.aethercode/settings.json` in the cwd is auto-created on first
prompt; you can also point `--permission-mode`:

| Mode                  | Behaviour                                                                 |
|-----------------------|---------------------------------------------------------------------------|
| `DEFAULT`             | Ask the user once per (tool, target) tuple; remember the answer.         |
| `ACCEPT_EDITS`        | Auto-allow `file_write` / `file_edit`; ask for everything else.          |
| `BYPASS_PERMISSIONS`  | No prompts — the agent can do anything (CI / `--print` mode).            |
| `PLAN`                | Lock to plan mode; agent can only plan, not execute.                      |
| `AUTO_READ_ONLY`      | Read-only tools run without prompts; writes still need approval.          |

Read-only tools (`Read`, `Glob`, `Grep`, `ListFiles`, `ListDir`, `Stat`) and a
curated set of safe bash commands (`ls`, `cat`, `head`, `tail`, `find`, `grep`,
`pwd`, `echo`, `wc`, `diff`, `file`, `which`, `type`) are auto-allowed — the
permission dialog never fires for them (R25-D).

## Memory

Three scopes, auto-resolved:

| Scope     | Path                                            | Lifetime            |
|-----------|-------------------------------------------------|---------------------|
| `PROJECT` | `<cwd>/.aethercode/memory/`                     | Tied to the repo.   |
| `USER`    | `~/.aethercode/memory/`                         | Per-developer.      |
| `AUTO`    | `PROJECT` if `.git/` is in any parent, else `USER`. | Default.        |

Sub-scopes inside: `MEMORY.md` (long-term), `agent-memory/<agent>/` (per agent),
`session/<sessionId>/` (per session), `tasks/<taskId>/` (per task), `share/`
(cross-session tagged).

## MCP integration

Drop an `mcp.json` in `.aethercode/` (or pass `--mcp-config <path>`):

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
      "auth": "oauth",
      "clientId": "..."
    }
  }
}
```

Both stdio and SSE transports are supported. OAuth dance is exposed via
`aethercode mcp auth <server-name>`.

## Comparison with the TypeScript original

| Layer                | TS source                             | Java equivalent                              |
|----------------------|---------------------------------------|----------------------------------------------|
| Tool pipeline        | `services/tools/*`                    | `aethercode-core` (records) + `aethercode-tools` |
| Memory               | `memdir/*` + `services/SessionMemory` | `aethercode-memory`                          |
| MCP                  | `services/mcp/*`                      | `aethercode-mcp` (stdio + SSE)               |
| LLM client           | Anthropic SDK                         | `aethercode-engine-springai`                 |
| TUI                  | Ink (React)                           | Ink (TS bundle, R31+) / JLine 3 + Lanterna (R95-C retired) |
| CLI arg dispatch     | `entrypoints/cli.tsx`                 | `aethercode-cli` (picocli)                   |
| Slash commands       | `commands.tsx`                        | `ReplApp.handleCommand` + completer         |
| Permission system    | `services/permissions/*`              | `aethercode-permission`                      |
| Context compaction   | `services/compact/*`                  | `aethercode-compact`                         |
| Task management      | `services/tasks/*`                    | `aethercode-tasks`                           |
| Subagent / dispatch  | `services/agents/*`                   | `aethercode-core.agent` (SubagentPool)       |
| Plan mode            | `commands/planMode*`                  | `aethercode-tui.StructuredPlan`              |
| Hooks                | `hooks/*`                             | `aethercode-hooks`                           |
| Session search       | `services/sessionSearch/*`            | `aethercode-tui.HistorySearch`               |
| Multi-session        | `services/sessions/*`                 | `aethercode-core.transcript.SessionStore`    |

The 1902-file TS source is mapped file-by-file in [`docs/TS-TO-JAVA-MAPPING.md`](docs/TS-TO-JAVA-MAPPING.md).

## What's in 0.2.0

- **1679 unit tests** across 17 modules, 0 failures, ~1.5 min cold
- **Single shaded jar** (`aethercode-cli/target/aethercode-cli-0.1.0-SNAPSHOT.jar`),
  35.8 MB, all deps included
- **Headless daemon mode** (R29) — `java -jar aethercode.jar --daemon` exposes a
  clean JSON-RPC 2.0 interface over stdio. Multica, custom orchestrators, or
  any other tool can drive the agent the same way Claude Code does.
- **TypeScript TUI** (R30) — `@aethercode/tui` (Ink + React) replaces the
  JLine / Lanterna REPL. Self-contained 1.4 MB bundle, no `node_modules`
  required. Cross-platform, no terminal-API quirks.
- **Loop detector + larger turn cap** (R28) — 50-turn default + sliding-window
  detector catches "same call 3+ times in 8 turns" patterns.
- Streaming tool events, plan mode, auto memory, MCP, context compaction
- 30+ R-series rounds tracked in [`docs/`](docs/): R1-R15 MVP/streaming/MiniMax,
  R16-R22 agent UX, R23-R27 plan/tasks/stats/SDK/heartbeat, R28 long-running
  + Windows TUI, R29 headless daemon + JSON-RPC, R30 TypeScript TUI

The TypeScript TUI is a separate package at `aethercode-tui-ts/`. See its
[`README.md`](aethercode-tui-ts/README.md) for the protocol spec and
distribution instructions.

## Known limitations

- Streaming tool events are a best-effort order — the model may emit parallel tool
  calls that interleave in unexpected ways.
- `--print` mode does not display diff hunks (use the REPL for that).
- The IDEA plugin lives in a separate Gradle sub-project and is not included in
  this jar (see `../idea-plugin/`).
- Test exclusions: 8 pre-existing flaky tests in aethercode-tui are skipped in
  the default `mvn test` invocation. They are tracked separately.
- On Windows, the TUI auto-relaunches under `javaw.exe` from the same `JAVA_HOME`
  (R28-B). If the auto-relaunch fails (e.g. a stripped JRE without `javaw.exe`),
  the agent falls back to line mode and prints a clear warning telling you to
  either install a full JDK or pass `--no-fullscreen`.
- The loop detector (R28-A) fires when the same tool call signature appears
  3 times in the last 8 turns. Tune with `--loop-detect-window N` and
  `--loop-detect-threshold N`; disable with `--loop-detect-window 0`.

## License

TBD (currently internal — see your distribution agreement).

## Status

**R27 — first usable release.** 27 rounds of iteration, 1636 tests, 0 failures,
single 36 MB jar, ready to run.
