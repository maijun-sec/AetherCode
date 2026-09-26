# R-TUI-ClaudeCode Gap Analysis (2026-09-25)

> Goal: align the AetherCode TUI's visual + interactive surface with
> Claude Code's polished terminal experience, while reusing the
> desktop's R341-R343 polish as the reference implementation.
>
> Constraints: minimize Java backend changes; do not touch the
> desktop; iterate until each gap is closed.

## Methodology

For each Claude Code surface area, we score:

| Score | Meaning |
|-------|---------|
| ✅    | already matches Claude Code; no work needed |
| 🟡    | partially there; needs polish |
| ❌    | missing or significantly behind |

We pick the gap subset that moves the most user-visible quality per
line of code changed.

---

## 1. Header (top status bar)

### Claude Code shape
A **single narrow row**, typically:
```
[model-name]  ·  [git-branch + dirty]  ·  [ctx-fill ████░░ 37%]  ·  [session-id-short]
```
or with a sidebar toggle:
```
⌘  ›  /  …                                              [model] [▆▆▆▆ 37%]
```
The header is **information-dense but unobtrusive** — it doesn't
take its own border; it sits flush against the scrollback with a
thin separator.

### Current AetherCode TUI (Header.tsx, 111 lines)
- `borderStyle="round"` pill style with brand color
- 6 items in a row: brand · model · mode · cwd · session · connection
- Session + connection are on the **right side** (justifyContent="space-between")
- Connection pill is `live` (green) or `offline` (red)

### Gap
| Sub-aspect | Status | Notes |
|---|---|---|
| Compact density | ✅ | fits in one row |
| Visual hierarchy | 🟡 | all items equal weight — Claude Code demotes cwd/session |
| Context window fill indicator | ❌ | Claude Code shows a tiny `▆▆▆▆░░ 37%`; we don't |
| Git branch + dirty | ❌ | not surfaced (we have cwd, no branch) |
| Spinner when working | ✅ | status bar already |
| Markdown divider instead of border | 🟡 | we use border; Claude Code uses a thin `─` rule |

### Action
- Replace border with `─` rule (R167 status bar already does this)
- Add `ctx` fill glyph (`▆▆▆▆░░ 37%`) computed from input+output vs contextWindow
- Demote cwd to a smaller dim line *below* the header (or in a thin sub-row)
- Pull model + connection into the brand color; rest in dim

---

## 2. StatusBar (bottom status row)

### Claude Code shape
A single compact line:
```
✓ N tools · 1.2k in · 0.3k out · $0.04 · claude-sonnet-4-5 · 2m14s · main
```
Sometimes:
```
[Stop]  ·  working…  ·  tokens · cost · cwd
```

### Current AetherCode TUI (StatusBar.tsx, **470 lines**)
- **Way too long** — 470 lines for a status bar is a smell
- Has subcomponents: SessionControl (98 lines), stale warning banner,
  ProgressBar, ProseFooter, footer items
- Shows: kind icon · label · conn state badge · tick · pending · pendingCount · bank status · subagent status · session active · context window · token chart · cost

### Gap
| Sub-aspect | Status | Notes |
|---|---|---|
| One-line density | ❌ | we render 3–4 lines of meta |
| Active-run indicator (spinner + Stop button) | ✅ | SessionControl |
| Token + cost in one place | ✅ | both shown |
| Elapsed time | ✅ | we have formatRelative |
| Pending tool count | ✅ | pendingCount |
| Subagent running indicator | ✅ | subagentStatus exists |
| Context fill | ❌ | not in status bar |

### Action
- **Compress StatusBar.tsx from 470 → ~120 lines**
- Move verbose bits (subagent tree, bank stats, ProgressBar) to **on-demand** surfaces (toasts / hover overlay / right panel)
- Show ONE row: kind icon · label · spinner · ctx% · tokens · cost · elapsed
- Keep SessionControl + StaleWarning as opt-in via `state.layout === "full"`

---

## 3. Tool call cards (ToolCard.tsx, 277 lines)

### Claude Code shape
```
⚒  Bash  ·  `git status`
   └ running… 2.3s
─────────────────────
⚒  Bash  ·  `git status`  ✓ 0.4s
   └ stdout: 1 line
─────────────────────
```
- One row of **header** (tool name + icon + args digest)
- One row of **status** (running spinner / ✓ / ✗ / duration)
- One row of **output digest** (stdout/stderr truncation, expand chevron)
- A thin separator between cards

### Current AetherCode TUI (ToolCard.tsx, 277 lines)
- BorderStyle "round" with **category color** (catRead blue / catWrite yellow / etc.)
- Multi-row layout: tool name, args (pretty-printed), output, status
- Has diffSnippet / FileDiff / tool result preview
- 277 lines but feels cluttered; too much per-card padding

### Gap
| Sub-aspect | Status | Notes |
|---|---|---|
| Tool category color | ✅ | catRead/catWrite/catRun |
| Compact 1-row header | 🟡 | we use multi-row |
| Running spinner | ✅ | spinner exists |
| Duration timer | ✅ | tick + formatRelative |
| Stdout/stderr digest | 🟡 | shown but verbose |
| Diff card for file_write/edit | ✅ | FileDiffCard exists |
| Subagent spawn card | ✅ | SubagentSpawnCard exists |

### Action
- Reduce vertical padding (paddingX=1 → paddingX=0 with `▌` accent)
- Show tool name + icon + args digest on header (one row)
- Status row only when running / done (collapsed when idle)
- Output digest as 1–3 lines (no full preview; expand on click)
- Use `─` rule separator instead of border

---

## 4. Scrollback messages (Scrollback.tsx, 416 lines)

### Claude Code shape
```
› Write a quick sort
  I'll write a quicksort in Python.

  Sure! Here's a quicksort:

  ```python
  def quicksort(arr):
      ...
  ```
- User prompts: `›` accent (warm), flush against left edge, multi-line
- Assistant prose: flush against left edge, full width, no border
- Code blocks: subtle bg + monospace + faint left bar
- No card borders — just whitespace

### Current AetherCode TUI (Scrollback.tsx, 416 lines)
- Turn-based with cards (borderStyle "single" around user turns?)
- Has riskColor border for permission cards
- Markdown rendering for assistant content
- ToolCallCard rendered as separate component

### Gap
| Sub-aspect | Status | Notes |
|---|---|---|
| User `›` accent (not `❯`) | 🟡 | we have icon.user "›" |
| Multi-line input | ✅ | shift+Enter |
| Assistant prose no border | ✅ | Markdown.tsx flush |
| Markdown rendering | ✅ | Markdown.tsx is 682 lines, comprehensive |
| Code-block syntax highlight | ✅ | highlight.ts |
| Heading styles (h1–h6) | ✅ | theme.t.heading1..6 |
| Blockquote accent | ✅ | theme.t.quoteBar |
| Inline code pill | ✅ | theme.t.code/codeBg |
| Tool card interleaved | 🟡 | rendered below prose |
| Diff cards (file edit) | ✅ | FileDiffCard |
| Plan / Todo list panel | ✅ | PlanList.tsx + TodoBoard.tsx |
| Loading shimmer for streaming text | ❌ | no shimmer |

### Action
- Verify user turn uses `›` icon consistently (no `>` plain ASCII)
- Verify assistant turns have zero padding/border — just `Text`
- Verify tool cards interleave properly with prose (currently OK)
- Add optional shimmer on streaming text (low priority)

---

## 5. Input box (InputBox.tsx, 311 lines)

### Claude Code shape
```
─────────────────────────────────────────────
› Type your message… ▍
```
or with content:
```
› def quicksort(arr):
  │     if len(arr) <= 1: return arr
  │     …
  ▌▍
```
- Single row with `›` glyph
- Multi-line: indent continuation lines with `│`
- Blinking cursor `▍`
- Above input: a thin rule, optionally a status line ("working… 4s")

### Current AetherCode TUI (InputBox.tsx, 311 lines)
- ▌ accent + multi-line via flexDirection column
- Cursor blink at end (530ms toggle)
- CompletionDropdown (Tab to accept, ↑/↓ to navigate)
- Slash command + @ file fuzzy
- "Decision pending" mode (A/T/P/U/D/N)

### Gap
| Sub-aspect | Status | Notes |
|---|---|---|
| Multi-line input | ✅ | shift+Enter |
| Slash autocomplete | ✅ | MultiCompletionManager |
| @ file autocomplete | ✅ | FuzzyFileController |
| Cursor blink | ✅ | 530ms |
| Inline rule separator | ✅ | ─ |
| Spinner strip above input when working | ✅ | exists |
| Decision-mode indicator | ✅ | "⚠" replaces prompt |
| Compact height | ✅ | no border |

### Action
- Status: already polished. Minor: tighten line height.

---

## 6. Permission modal (PermissionModal.tsx, 86 lines)

### Claude Code shape
```
╔═══════════════════════════════════════════════════════════════╗
║  Permission required                                           ║
║                                                               ║
║  Bash will run `rm -rf build/`                                ║
║                                                               ║
║  ❯ 1. Yes                                                    ║
║    2. Yes, and don't ask again for `rm` commands               ║
║    3. No                                                     ║
║    4. No, and don't ask again for `rm` commands                ║
╚═══════════════════════════════════════════════════════════════╝
```
- Centered modal overlay
- Numbered options (1–4) with `❯` selector
- Shows the **exact command / path** that will run

### Current AetherCode TUI (PermissionModal.tsx, 86 lines)
- `borderStyle="double"` with risk color
- Format input: `command: ...` / `file_path: ...`
- Options: [A] allow / [Y] always / [D] deny / [N] never
- Decision keys handled in tui.tsx:1391

### Gap
| Sub-aspect | Status | Notes |
|---|---|---|
| Risk-level color coding | ✅ | RISK_COLOR map |
| Show tool name + args | ✅ | formatInput |
| 4 options | ✅ | A/Y/D/N |
| Inline PermissionCard (not modal) | ✅ | R342 Scrollback renders inline card |
| Numbered selector (1/2/3/4) | ❌ | we use letter keys |
| Session/project/user scope | ✅ | T/P/U |

### Action
- Add number-key aliases (1=allow, 2=always, 3=deny, 4=never) for both
  letter and number selectors
- Show remaining auto-allow count if `skipConfirmationRemaining > 0`

---

## 7. Slash command palette

### Claude Code shape
Type `/` → dropdown shows commands with descriptions.
`/help` lists every command.

### Current AetherCode TUI (commands.ts, 600+ lines)
- 40+ slash commands in `SLASH_COMMANDS_DETAILED`
- CommandPalette overlay (Ctrl+Shift+P equivalent)
- Autocomplete dropdown with `↑/↓` and `Tab`

### Gap
| Sub-aspect | Status | Notes |
|---|---|---|
| `/` autocomplete | ✅ | SlashCommandController |
| Command palette overlay | ✅ | CommandPalette.tsx |
| 40+ commands | ✅ | commands.ts is comprehensive |
| `/help` shows all | ✅ | HelpOverlay |

### Action
- Status: already excellent. Polish: add `/demo` command (R344 todo)

---

## 8. Sidebar (left panel)

### Claude Code shape
- Toggle with `⌘B` / `Ctrl+B`
- Conversation list (sessions) + project info
- Used to be a wide panel; in v2 they collapsed it

### Current AetherCode TUI (Sidebar.tsx, 196 lines)
- Sessions + recent queries + tasks
- Toggle via Ctrl+B
- Width 26 cols

### Gap
- Status: already there. Polish: maybe widen for better readability.

---

## 9. Right panel

### Claude Code shape
- Toggle with `Ctrl+D` (or similar)
- Shows: session details, todos, token usage chart

### Current AetherCode TUI (RightPanel area)
- SessionDetailsPanel + TodoBoard
- Toggle via state.rightPanelVisible (R-prefix)

### Gap
- Status: already there. Already pretty good.

---

## 10. Themes

### Claude Code shape
- Default + Dark + Light + Dark (high contrast)
- Theme picker Ctrl+T

### Current AetherCode TUI (themes.ts)
- 5 themes: default, solarized, monokai, kanagawa, opencode
- ThemePicker component
- ThemeStore from aethercode-themes

### Gap
- Status: matches. Already polished.

---

## 11. Spinner / loading states

### Claude Code shape
- ⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏ spinning dot
- Always shows "thinking…" or "running tool…" subtitle

### Current AetherCode TUI
- ink-spinner "dots" type
- "thinking" / "running tool" / "streaming reply" labels

### Gap
- Status: matches. Already polished.

---

## 12. Streaming text

### Claude Code shape
- text_delta events render incrementally
- No spinner during streaming (only during thinking)
- Subtle `▍` cursor at end of latest line

### Current AetherCode TUI
- text_delta → buffer
- Spinner during run

### Gap
- Status: matches.

---

## 13. Diff display

### Claude Code shape
- For file_write/edit: shows unified diff (red − / green +)
- Collapsed by default; expand with `▼`

### Current AetherCode TUI
- FileDiffCard.tsx exists (200+ lines)
- Used in SubagentPanel / chat

### Gap
- Status: exists. Verify it's used in main chat (not just subagent).

---

## 14. Auto-compact

### Claude Code shape
- When context window fills, automatically compact (summarize)
- Show toast: "Context low! Auto-compacting…"

### Current AetherCode TUI
- AutoCompact? Let me check…
- Memory-related compression passes exist (aethercode-memory module)

### Gap
- Need to verify. May already be there.

---

## 15. Subagent indicator

### Claude Code shape
- Background subagent runs show as `running 2 tasks` in status bar
- Click to expand list

### Current AetherCode TUI
- SubagentPanel (38 lines)
- Status: already there.

### Gap
- Status: matches.

---

## 16. Toast / notifications

### Claude Code shape
- Bottom-right corner
- Auto-dismiss after 3–5s
- Color-coded by severity

### Current AetherCode TUI
- ToastStack component
- Bottom of screen

### Gap
- Status: matches.

---

## 17. Help overlay

### Claude Code shape
- Full-screen overlay with categorized keyboard shortcuts
- Triggered by `?`

### Current AetherCode TUI
- HelpOverlay.tsx (147 lines)
- Triggered by Ctrl-?

### Gap
- Status: matches.

---

## 18. Welcome banner

### Claude Code shape
- **Minimal**: just shows `Welcome to Claude Code!` + brief tip
- Or skipped entirely (Claude Code doesn't have a rich welcome)

### Current AetherCode TUI (Welcome.tsx, just rewritten in R344)
- Rich banner with provider/model/mode/session/cwd/uptime + shortcuts grid + tip

### Gap
- We have **more** than Claude Code (which is OK; this is a feature)
- Polish: Claude Code actually does have a welcome with cwd display,
  but ours is denser

### Action
- Status: already done in R344. Minor polish only.

---

## 19. CommandPicker / IDE features

### Claude Code has
- `/clear` — clear context
- `/compact` — manual compact
- `/cost` — show token cost
- `/doctor` — diagnostics
- `/exit` — quit
- `/help` — help
- `/init` — initialize CLAUDE.md
- `/login` — re-authenticate
- `/logout`
- `/mcp` — MCP servers
- `/memory` — manage memory
- `/model` — switch model
- `/permissions` — manage permissions
- `/pr-comments` — fetch PR comments
- `/release-notes` — show release notes
- `/resume` — resume a session
- `/review` — review PR
- `/status` — show status
- `/terminal-setup` — terminal setup
- `/vim` — vim mode
- `/bug` — report bug
- `/logout`
- `/upgrade`

### Current AetherCode TUI
- Already has 40+ commands (commands.ts)
- Specific gaps to check:
  - `/doctor` — present?
  - `/status` — present?
  - `/review` — present?
  - `/vim` — present?

### Action
- Audit commands.ts against Claude Code's full set
- Add missing common commands

---

## 20. MCP visibility

### Claude Code shape
- Shows MCP servers in status
- `/mcp reconnect` command

### Current AetherCode TUI
- McpViewer.tsx, McpLogin.tsx, McpReconnect.tsx exist

### Gap
- Status: matches.

---

## 21. Bash output streaming

### Claude Code shape
- Bash tool output streams live to scrollback with `[stdout]` prefix per line
- Long output truncated; expand chevron

### Current AetherCode TUI
- Bash output streams (StreamingToolExecutor)
- ToolCard shows output

### Gap
- Status: matches.

---

## Summary: Priority-ordered work list

| # | Area | Gap | Effort | Impact |
|---|---|---|---|---|
| 1 | **Header** | Replace border with rule, add ctx fill, demote cwd | low | high |
| 2 | **StatusBar** | Compress 470 → ~120 lines; one row only | medium | high |
| 3 | **ToolCard** | Reduce padding, one-row header | low | high |
| 4 | **PermissionModal** | Add number-key aliases (1/2/3/4) | low | medium |
| 5 | **InputBox** | Already excellent; minor tightening | low | low |
| 6 | **`/demo` command** | Pre-canned conversation | medium | high |
| 7 | **Ink TUI raw mode fallback** | PowerShell 5.1 + conhost | medium | high |
| 8 | **Audit slash commands** | Compare to Claude Code's full set | low | medium |
| 9 | **Auto-compact indicator** | Verify + toast | low | medium |
| 10 | **Welcome banner polish** | Already done in R344 | n/a | n/a |

## Files to touch (TUI side only)

- `src/components/Header.tsx` — rewrite
- `src/components/StatusBar.tsx` — compress
- `src/components/ToolCard.tsx` — tighten
- `src/components/PermissionModal.tsx` — add 1/2/3/4
- `src/components/InputBox.tsx` — tighten
- `src/commands.ts` — audit + add `/demo`
- `src/components/Welcome.tsx` — already R344; minor polish
- `src/ac-tui.ts` — Ink fallback for non-ConPTY hosts

## Files NOT to touch

- `aethercode-desktop/**` — explicit user constraint
- `aethercode/**` Java code — minimize; only touch if absolutely necessary
  (e.g. new RPC method for /demo state, or skipped — pure TUI feature)

---

End of gap analysis.