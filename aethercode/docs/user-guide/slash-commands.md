# Slash Commands

Type `/` to start a command. **Tab** completes the current partial
(R38). **Ctrl-P** opens the command palette (R47) for fuzzy search.

| Command | Round | What it does |
|---|---|---|
| `/help` | R31 | Show the categorised help overlay |
| `/clear` | R31 | Clear the scrollback (note: currently a no-op in Ink TUI; restart for a fresh scrollback) |
| `/exit`, `/quit`, `/q` | R31 | Exit the TUI |
| `/tools` | R31 | List registered tools (RPC: `listTools`) |
| `/state` | R31 | Engine state snapshot (RPC: `getState`) |
| `/ping` | R31 | Liveness check (RPC: `ping`) |
| `/model <name>` | R31 | Change the model at runtime (RPC: `setModel`) |
| `/mode <name>` | R31 | Change the permission mode (RPC: `setPermissionMode`) |
| `/sessions` | R32 | List saved sessions (RPC: `listSessions`) |
| `/tasks` | R32-E | List recent tool invocations (RPC: `listTasks`) |
| `/projects` | R32-F | List known projects (RPC: `listProjects` — currently returns just the current cwd) |
| `/cwd <path>` | R32-F | Switch to another project (RPC: `switchProject` — currently a stub; restart with `--cwd` instead) |
| `/stats` | R32 | Show session stats as a side note |
| `/history` | R32 | Show input history as a side note |
| `/theme <name>` | R42 | Switch theme: `default` / `solarized` / `monokai` |
| `/layout <name>` | R43 | Switch layout: `full` / `minimal` / `focus` |
| `/budget <usd>` | R44 | Set a per-session cost budget (USD); `off` to clear. Shows a progress bar in the status bar. |
| `/rewind <n>` | R50 | Rewind to the Nth user message (1-based; RPC: `rewind`) |
| `/snippet save <name>` | R51 | Save the current input as a named snippet |
| `/snippet load <name>` | R51 | Replace the current input with the named snippet |
| `/snippet list` | R51 | List all saved snippets (in scrollback) |
| `/snippet delete <name>` | R51 | Delete a snippet |
| `/export <path>` | R61 | Export the scrollback as Markdown to `<path>` |
| `/export json <path>` | R61 | Export the scrollback as JSON to `<path>` |
| `/tutorial` | R59 | Re-show the welcome overlay |
| `/bookmark` | R60 | Bookmark the most recent turn (also bound to `b`) |
| `/bookmark <n>` | R60 | Bookmark the Nth turn (1-based) |
| `/metrics` | R77 | Pretty-print engine metrics (turns, tools, errors, cost) |
| `/trace` | R78 | Show the recent trace spans (root `query` + per-tool `tool.NAME`) with status + duration |
| `/trace <n>` | R78 | Same, but request up to N spans (capped at 256 server-side, 32 in the TUI) |
| `/trace tr-<id>` | R79 | Show the full tree for a single trace (root + all descendants) with `├─` / `└─` / `│` connectors |

## Slash-command groups (in help)

The help overlay (`Ctrl-?`, R49) groups the commands into 4 buckets:

- **session**: `/help`, `/state`, `/ping`, `/exit`
- **tools**: `/tools`, `/tasks`, `/projects`
- **config**: `/model`, `/mode`, `/theme`, `/layout`, `/budget`
- **data**: `/history`, `/sessions`, `/stats`, `/cwd`, `/metrics`, `/trace`

## Slash-command routing

A command can be:

- **Local** — handled in the TUI process (e.g. `/clear`, `/tutorial`, `/snippet save`).
  The TUI dispatches the action and never talks to the daemon.
- **RPC** — the TUI sends the command name + args to the daemon via JSON-RPC.
  The daemon's `AetherCodeMethods` class dispatches it to the engine.

The dispatcher is in `aethercode-tui/src/commands.ts` (`handleSlash`).

## Tab completion (R38)

`/mo<Tab>` → `/mode ` (single match, auto-completes)
`/sn<Tab>` → `/sn` with a side note listing `/snippet save|load|list|delete` (multiple matches, shows alternatives)

The completion is **strict**: it uses `startsWith` (not substring) to
avoid false positives. The longest-common-prefix is computed and the
alternatives are surfaced as a side note.
