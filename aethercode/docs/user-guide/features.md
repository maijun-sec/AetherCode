# Features

Every feature the TUI exposes, with the round it was added in.
Cross-reference with `keybindings.md` (how to invoke) and `slash-commands.md`
(`/foo` text commands).

## Visual differentiation

| Feature | Round | What |
|---|---|---|
| **Header pills** | R33 | 5 colored chips (model / mode / cwd / session / conn) — each is a self-contained visual unit. |
| **Per-category tool icons + colors** | R34, R35 | read=blue, write=yellow, search=cyan, run=red, agent=magenta. Spinner also varies by category (dots / line / arc / triangle / star). |
| **Markdown tables / rules / quote / strike** | R36 | The assistant's markdown is rendered with GFM tables, `---` rules, `> blockquote`, `~~strike~~`. |
| **Color-coded stop banner** | R32-G | When a run ends abnormally, a sticky round-bordered banner appears at the top of the scrollback. Loop=red, max_turns=yellow, error=red. |
| **Per-status toolbar colors** | R32-G | StatusBar's border + label color match the stop kind. The user can never confuse "model is thinking" with "model stopped". |
| **Themes** | R42 | `default` (warm amber + cyan) / `solarized` (muted teal + sand) / `monokai` (vivid magenta + green). Switch live with `/theme NAME`. |
| **Layout presets** | R43 | `/layout full\|minimal\|focus`. Minimal hides header+statusbar; focus hides everything but the scrollback. |

## Live status

| Feature | Round | What |
|---|---|---|
| **Tool card duration timer** | R34 | Live "1.4s" while a tool runs; final duration once it ends. Updates every 500ms via a tick effect. |
| **Cost budget progress bar** | R44 | `/budget <usd>` shows a Unicode-block bar that fills as `totalCostUsd` approaches the cap. Color: green (0-50%) → yellow (50-80%) → red (80%+). |
| **Token / cost sparkline** | R48 | The last 64 run costs visualized as `▁▂▃▄▅▆▇█` in the status bar. |
| **Toast notifications** | R41 | 2s transient banner above the header for: connection state, task updates, permission grants, RPC results. 5 kinds: info / ok / warn / err / rpc. |

## Discovery + recovery

| Feature | Round | What |
|---|---|---|
| **Welcome v2** | R46 | First screen shows model/session/cwd + a 6-row shortcut table. |
| **Categorised help** | R49 | `Ctrl-?` opens a 4-bucket help (session / display / editing / slash). |
| **Command palette** | R47 | `Ctrl-P` fuzzy-searches every slash command (3-tier scoring: prefix 1000 / substring 500 / subsequence 100). |
| **Search** | R40 | `Ctrl-F` finds past turns. Substring match across text / tool name / args / result. |
| **Log viewer** | R56 | `Ctrl-L` shows the last 200 daemon log lines with timestamps + level icons. |
| **Tutorial** | R59 | `Ctrl-T` re-shows the welcome overlay on demand. |
| **Rewind** | R50 | `Ctrl-Z` rolls the engine back to the most recent user message. |
| **Bookmarks** | R60 | `b` bookmarks the most recent turn. |
| **Snippets** | R51 | `/snippet save\|load\|list\|delete <name>` — save and reuse prompt templates. |
| **Export** | R61 | `/export <path>` (Markdown) or `/export json <path>` saves the scrollback. |

## Side panel

| Feature | Round | What |
|---|---|---|
| **Sidebar (Ctrl-B)** | R37 | Left rail with 3 sections: project (cwd + session), tasks (from `listTasks` RPC, refreshed every 30s + on `task_state` notifications), queries (recent prompts from `state.history`). |
| **Metrics** | R77 | `/metrics` shows engine counters: turns started/completed, tool calls, errors, retries, loop stops, permission asks/denies, cache hits/misses, cost (USD), error rate, cache hit rate, uptime. |
| **Traces** | R78 | `/trace` shows the recent span recorder: one root `query` span per turn, plus one `tool.<name>` span per tool invocation. Status icon (✓/✗/·) + name + duration. The recorder is bounded (256 spans) and the TUI caps the list at 32. |
| **Trace tree** | R79 | `/trace <id>` shows the full tree for a single root span: `query` at the top, all `tool.*` children below with `├─` / `└─` / `│` connectors. The parent linkage is recorded in the recorder and assembled by the TUI render. |

## Errors + recovery

| Feature | Round | What |
|---|---|---|
| **Error card** | R52 | When an exception is caught (e.g. RPC failure), a dedicated red card shows the message + first 3 stack lines. Cleared on the next submit. |
| **Collapse / expand** | R32-B | `Ctrl-O` collapses all tool cards; `Ctrl-E` toggles the most recent. Lets you skim many tool calls at once. |
| **Tool details** | R34 | `d` expands a tool card to show the full args (pretty-printed JSON) + full result. |
| **Tool category** | R34 | The icon on a tool card tells you the *type* of operation, not just the name. |

## Concurrency safety

| Feature | Round | What |
|---|---|---|
| **Permission flow** | R32-D | The daemon sends `permission_request` JSON-RPC notifications with a `requestId`. The TUI shows a modal (A/Y/D/N). On timeout (60s) the daemon auto-denies. |
| **JSON-RPC transport** | R32 | The CLI's `--daemon` mode is a JSON-RPC 2.0 server over stdio. PowerShell pipe BOM is stripped at the codec layer (R32+ pitfall fix). |
| **Line-mode fallback** | R32 | When stdin/stdout is not a TTY (cmd.exe, CI, pipes), the TUI uses a line-mode renderer that works in any context. |
