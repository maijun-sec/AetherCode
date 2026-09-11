# Keybindings

The TUI recognises the following key chords. **Bold** = global (always works); others = contextual (require a specific mode).

## Global

| Chord | What it does | Round |
|---|---|---|
| **Enter** | Submit the current prompt | R31 |
| **↑ / ↓** | Navigate input history | R31 |
| **Ctrl-C** | Exit the TUI | R31 |
| **Ctrl-? / F1** | Toggle the help overlay | R31 / R49 |
| **Esc** | Close the current modal (help, search, palette, log viewer, tutorial) | R31 / R49 |
| **Tab** | Complete the current slash command | R38 |
| **b** | Bookmark / un-bookmark the most recent turn | R60 |

## Side panel + sidebar

| Chord | What it does | Round |
|---|---|---|
| **Ctrl-B** | Toggle the side panel (projects + tasks + queries) | R37 |

## Search

| Chord | What it does | Round |
|---|---|---|
| **Ctrl-F** | Open the search bar; type to filter the scrollback | R40 |
| **Esc** (in search) | Close search and clear filter | R40 |

## Command palette

| Chord | What it does | Round |
|---|---|---|
| **Ctrl-P** | Open the command palette (fuzzy search over slash commands) | R47 |
| **↑ / ↓** (in palette) | Move the highlight | R47 |
| **Enter** (in palette) | Insert the highlighted command into the input | R47 |

## Display

| Chord | What it does | Round |
|---|---|---|
| **Ctrl-O** | Collapse / expand all tool cards | R32-B |
| **Ctrl-E** | Toggle the most recent card | R32-B |
| **d** | Expand / collapse the focused tool card's full details | R34 |
| **Ctrl-T** | Re-show the tutorial / welcome overlay | R59 |

## Tools + logs

| Chord | What it does | Round |
|---|---|---|
| **Ctrl-L** | Toggle the log viewer (200-entry buffer) | R56 |

## Editing

| Chord | What it does | Round |
|---|---|---|
| **Ctrl-Z** | Rewind to the most recent user message | R50 |
| **Ctrl-L** (re-purposed) | — (now log viewer; clear was a no-op anyway) | R56 |

## Permission modal (in-screen)

| Chord | What it does | Round |
|---|---|---|
| **A** | Allow this tool call once | R32-D |
| **Y** | Always allow this tool for this target | R32-D |
| **D** | Deny this tool call | R32-D |
| **N** | Always deny this tool for this target | R32-D |

## Layouts

Available via `/layout <name>` (R43). The current layout determines which sub-components are rendered.

| Layout | Header | Scrollback | Input | StatusBar |
|---|---|---|---|---|
| `full` (default) | ✓ | ✓ | ✓ | ✓ |
| `minimal` | — | ✓ | ✓ | — |
| `focus` | — | ✓ | — | — |

## Slash commands

See `slash-commands.md` for the full list. Tab-completion (`R38`) is the
fastest way to discover them.
