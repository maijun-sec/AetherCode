# Themes (R42)

The TUI supports 3 color themes. Switch live with `/theme NAME`.

| Theme | Brand | Accent | OK | Warn | Err | Mood |
|---|---|---|---|---|---|---|
| `default` | warm amber | cyan | green | yellow | red | matches Claude Code's visual language |
| `solarized` | Solarized yellow | teal | olive green | yellow | red | muted, easy on the eyes for long sessions |
| `monokai` | vivid yellow | cyan | bright green | yellow | hot pink | high-contrast, code-editor feel |

## How themes are wired

- `src/themes.ts` defines 3 `Palette` records + `pickPalette(name)`.
- `src/ThemeContext.tsx` provides a `ThemeProvider` + `useTheme()` hook.
- `tui.tsx` wraps the whole tree in `<ThemeProvider themeName={state.themeName}>`.
- New components that want to be theme-aware call `useTheme()` and
  use the returned palette instead of importing `t` from `theme.ts`
  directly. (Existing components still use the static `t` — R62 will
  migrate them in a focused refactor.)

## Why 3 themes?

Three is the smallest number that gives the user a *meaningful* choice:
default / warm / vivid. Adding more (e.g. nord, dracula) is a simple
matter of adding a new palette to `themes.ts` and updating the
`Palette` type's `brandBold` + 25 color fields. No architectural
changes needed.

## Persistence

Theme is per-session. There's no persistence across restarts (R73
plugin loader is the place where ~/.aethercode/settings.json would
land).
