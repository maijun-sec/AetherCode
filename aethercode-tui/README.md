# aethercode-tui

Modern terminal UI for [AetherCode](../README.md), built on
[Ink](https://github.com/vadimdemedes/ink) (React for the CLI).

The TUI is a separate Node.js process that talks to the
AetherCode Java backend over **JSON-RPC 2.0 over stdio**. The
backend runs in `--daemon` mode and streams model output back
as `stream_event` notifications.

The TUI has two render modes:

| Mode              | When                                         | Tech                       |
|-------------------|----------------------------------------------|----------------------------|
| **Ink TUI**       | `process.stdin.isTTY && process.stdout.isTTY` | Ink (React for CLI)        |
| **line mode**     | anywhere else (CI, piped, cmd.exe, etc.)     | plain readline + ANSI      |

`--tui` forces the Ink TUI. `--line` forces line mode. The
default is auto-detect. The Ink TUI is the recommended
interface on any modern terminal (Linux, macOS,
**Windows Terminal**, ConPTY-capable hosts); line mode is the
fallback for hosts that don't expose a real TTY.

> **Windows note**: the Ink TUI uses the alt-buffer, cursor
> positioning, and raw mode. These need a real TTY.
> **cmd.exe** does not provide one reliably; Ink falls back to
> a "dumb" rendering that draws a black box and overwrites
> text. If you're on Windows, **use Windows Terminal** (or any
> ConPTY-capable host) for the full Ink UI, or pass `--line`
> to get the simpler line-by-line UI.

## Install / Build

```bash
# from this directory
npm install
npm run build
```

`npm run build` produces two artefacts:

| File                                                     | Purpose                                          |
|----------------------------------------------------------|--------------------------------------------------|
| `dist/ac-tui.js`                                         | self-contained ESM bundle (1.5 MB)               |
| `../dist/ac-tui/ac-tui.js`                               | copy shipped next to the Java jar for distribution |

No `node_modules` is required at runtime — the bundle
includes Ink, React, and all transitive deps.

## Run

```bash
# After `mvn package` produced aethercode-0.2.1.jar:
java -jar aethercode-0.2.1.jar --daemon          # in one shell, for ad-hoc testing
node dist/ac-tui.js                              # in another, the TUI

# Or one-liner via --print (no TUI; just stream text to stdout):
node dist/ac-tui.js --print "what is 2+2"

# Force line mode (useful in cmd.exe or any non-TTY host):
node dist/ac-tui.js --line
```

The TUI auto-detects the jar in any of:

- `dist/ac-tui.js` next to the jar (preferred; the build ships it there)
- `dist/ac-tui-jline/` (the JLine fallback jar, if present)
- `AETHERCODE_JAR` env var
- `--jar <path>` CLI flag

## Features

### Ink TUI (default on a TTY)

- **Welcome banner** with model, session, and cwd
- **Boxed tool call cards** (status icon + name + args + result)
- **Plan / todo list** panel
- **Markdown rendering** in assistant responses
- **Input history** (↑ / ↓) — last 200 prompts
- **Help overlay** (Ctrl-? or F1)
- **Status bar** with token counts and cost
- **Slash commands** (see `/help` in the TUI)
- **Themes** — warm amber primary, cyan accent, dim greys

### Line mode (`--line` or auto-detect in non-TTY)

- Single prompt at the bottom; output streams above
- ANSI colors (or `--no-color` for plain text)
- Same slash commands as the Ink TUI
- Works in cmd.exe, PowerShell, ssh, CI, piped input
- `Ctrl-C` to exit; `Ctrl-D` for EOF
- Stream text is buffered at 80ms boundaries so the prompt
  doesn't flicker

### Keyboard shortcuts (Ink TUI only)

| Key            | Action                                |
|----------------|---------------------------------------|
| Enter          | submit the prompt                     |
| ↑ / ↓          | navigate input history                |
| Ctrl-C         | exit the TUI                          |
| Ctrl-L         | clear scrollback (no-op; restart)     |
| Ctrl-? / F1    | show / hide the help overlay          |
| Esc            | dismiss the help overlay              |

## Slash commands

| Command           | Action                                          |
|-------------------|-------------------------------------------------|
| `/help`           | show the command list                           |
| `/clear`          | clear the scrollback (Ink: redraw; line: `\x1b[2J`) |
| `/exit`           | exit the TUI                                    |
| `/tools`          | list available tools (RPC)                      |
| `/state`          | show engine state (RPC)                         |
| `/ping`           | liveness check (RPC)                            |
| `/model <name>`   | change the model (RPC)                          |
| `/mode <name>`    | change the permission mode (RPC)                |
| `/sessions`       | list saved sessions (RPC)                       |
| `/stats`          | show session stats (local)                      |
| `/history`        | show input history (local)                      |

## Layout (Ink TUI)

```
┌──────────────────────────────────────────────────────┐
│ Header  · AetherCode · model · mode · conn · session │  1 row
├──────────────────────────────────────────────────────┤
│                                                      │
│  Scrollback (assistant / user / tool / plan)         │  flex
│                                                      │
├──────────────────────────────────────────────────────┤
│ ▸ input                                              │  1 row
├──────────────────────────────────────────────────────┤
│ StatusBar · tokens · cost · mode · jar               │  1 row
└──────────────────────────────────────────────────────┘
```

## Layout (line mode)

```
[● connected · MiniMax-M3 · session 48f7d479 · cwd ...]

❯ what is 2+2

  · task u-r7uxfjr4 started
  · recalled 2 memory file(s)
2 + 2 = 4.

  [run_end: end_turn]

❯ 
```

## Why Ink (and not Lanterna / JLine)?

The Java-based TUI lives at `../aethercode-tui-jline/` as
an R28 fallback for environments without Node. The Ink TUI
is the primary one: declarative React components, mature
ecosystem, no JVM console-attach headaches on Windows.

## Architecture

```
┌──────────────────┐  stdin/stdout  ┌──────────────────┐
│  ac-tui.js       │  JSON-RPC 2.0  │  AetherCode      │
│  (Ink UI / line) │ ──────────────►│  Java daemon     │
│                  │ ◄──────────────│  --daemon        │
└──────────────────┘  notifications └──────────────────┘
        │                                    │
        │  useReducer                       │  QueryEngine
        │  useState                         │  StreamEvent
        │  React components                 │  ToolRuntime
        ▼                                    ▼
   Scrollback                          LLM provider
   Header / Status                     (MiniMax / Anthropic / OpenAI)
   InputBox / ToolCard
```

The TUI is intentionally thin. All model logic, tool
dispatch, and permission handling lives in the Java
backend. The TUI's job is to render events and forward
user input.
