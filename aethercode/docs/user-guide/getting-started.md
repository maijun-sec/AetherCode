# Getting Started

## Quick start (Windows / PowerShell)

```powershell
# From a fresh checkout:
cd D:\work\workspace\idea\engine\AetherCode\aethercode

# Build everything (Java + TypeScript TUI):
.\build.ps1

# Run the Ink TUI (needs a real terminal — Windows Terminal recommended):
java -jar dist\aethercode-0.2.1.jar tui

# Or the line-mode fallback (works in any context, including pipes):
java -jar dist\aethercode-0.2.1.jar tui --line
```

## Single-turn headless

```powershell
# Run one prompt and print the model response to stdout (no TUI):
java -jar dist\aethercode-0.2.1.jar tui --print "summarise this directory"
```

## What you should see

When the TUI starts, you see a welcome screen (R46) with:
- Wordmark + version
- Model / session / cwd chips
- 6-row shortcut table
- Tip: "type a prompt to begin"

Type a question. Press **Enter**. The status bar shows a yellow spinner.
The model streams text. When the run ends, the status bar settles on a
green dot with "ready" (R32-G), and the cost/tokens update in the right.

## Try the next things

- `Ctrl-?` — categorised help
- `Ctrl-B` — open the side panel
- `Ctrl-F` — search
- `Ctrl-P` — command palette
- `Ctrl-L` — log viewer
- `Tab` after `/` — slash-command completion
- `/theme monokai` then `/layout focus` — see what a focus-mode
  monokai session looks like
- `/metrics` — see what the engine has been doing

See `features.md` for the full list and `keybindings.md` / `slash-commands.md`
for the discovery surface.
