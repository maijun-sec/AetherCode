# TUI Visual Guide

A walkthrough of the TUI's layout, top to bottom. The image below is
a text approximation — the real TUI uses ANSI colors, rounded borders,
and live spinners.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ ⌬ MiniMax-M3 · ⚙ DEFAULT · ↳ .../AetherCode · # ec656c00 · ● live         │ R33 (header pills)
├──────────────────────────────────────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────────────────────────────────────┐ │
│ │ ■ Run stopped — loop detected    (reason: loop_detected)                │ │ R32-G (sticky banner
│ │   The model repeated the same call or hit a long-running output;        │ │  when lastStopKind
│ │   the daemon stopped it. Type a new prompt to continue.                │ │  is loop/max_turns/err)
│ └──────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│   12:34:56 ❯ list 2 files in this directory                                │ user turn (blue marker)
│   12:34:57 ●  Here are 2 files:                                              │ assistant text
│   12:34:57 ◐ tool: glob            (running…)                                │ tool card (R34/35:
│   12:34:58 ✓ tool: glob            1.4s  d: details  Tab: collapse            │  per-category icon +
│       result-preview (truncated to 400 chars)                              │  duration + status)
│                                                                              │
│   12:35:00 ●  2 files: README.md, build.ps1                                │
│                                                                              │
├──────────────────────────────────────────────────────────────────────────────┤
│ ❯ type a prompt to begin. / for commands. Ctrl-? for all shortcuts.        │ R31 input box
├──────────────────────────────────────────────────────────────────────────────┤
│ ● ready  · mode DEFAULT  · in 1.2k · out 0.3k · $0.012  · jar aethercode-0.2.1 │ R32-G status bar
│                                       history ▁▂▃▄▅                       │ R48 sparkline
└──────────────────────────────────────────────────────────────────────────────┘
```

## Modals (any of these floats above everything)

| Modal | How | What it shows |
|---|---|---|
| **Permission** | (automatic on dangerous tool) | Tool + risk level + 4 options (A/Y/D/N) |
| **Help** | `Ctrl-?` | 4-bucket help (R49) |
| **Search** | `Ctrl-F` | Search input + match count (R40) |
| **Command palette** | `Ctrl-P` | Fuzzy-matched slash commands (R47) |
| **Log viewer** | `Ctrl-L` | Last 200 daemon log lines (R56) |
| **Tutorial** | `Ctrl-T` | Same as the welcome overlay (R59) |

## Side panel (Ctrl-B)

```
┌──────────────────┐
│ ◰ panel           │
│ ──────────────    │
│ project (1)       │
│  ↳ .../AetherCode │
│    session ec656c00│
│                   │
│ tasks (3)         │
│  ◐ u-fcx3ivvk     │
│  ✓ u-7asd90fj 1m │
│  ✗ u-32fjsldf     │
│                   │
│ queries (5)       │
│  ▸ list 2 files   │
│  ▸ say OK         │
│  ▸ search the …   │
│ ──────────────    │
│ Ctrl+B hide       │
└──────────────────┘
```

## Layout presets (R43)

- `/layout full` (default): everything
- `/layout minimal`: no header, no statusbar
- `/layout focus`: scrollback only (max room for content)

## Color coding

The TUI uses 5 distinct colors to encode the *type* of a tool call
so the user can identify the operation at a glance:

| Color | Category | Examples |
|---|---|---|
| 🔵 blue | read | `file_read`, `glob`, `grep` |
| 🟡 yellow | write | `file_write`, `file_edit` |
| 🟢 cyan | search | `glob` (when searching), `search`, `find` |
| 🔴 red | run | `bash`, `shell`, `test` |
| 🟣 magenta | agent | `agent`, `task`, `delegate` |

Same convention for spinner types and tool-card borders.
