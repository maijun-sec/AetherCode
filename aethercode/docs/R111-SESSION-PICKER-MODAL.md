# R111 — Session Picker Modal (R97-J UI)

**Date**: 2026-08-18
**Status**: SHIPPED
**Round count**: 1
**Test delta**: +15 vitest tests (51 → 66, in `SessionPickerModal.test.tsx`)

## Why R111

R88 already shipped a `SessionList` sidebar component that shows the current session, lets the user click to switch, delete sessions, and create new ones. R95-E/R97-A/R97-B added the wire-level multi-session support. R111 adds the missing piece: a **keyboard-driven picker modal** for power users who have 10+ sessions and want to jump to one without taking their hands off the keyboard.

The shape mirrors the existing `CommandPalette` (R103): Ctrl/Cmd+Shift+P for the picker, Ctrl/Cmd+P for commands, Ctrl/Cmd+T for tools. A consistent shortcut stack reduces the cognitive cost of switching between them.

## What R111 ships

1. **`SessionPickerModal.tsx`** — new component. Modal with:
   - Search input (fuzzy match on label / id / name)
   - Sorted list of sessions (current pinned to top, then by `lastUsedAt` desc)
   - "+ new session" row at the bottom (always visible, even when a query is active)
   - Keyboard navigation: `↑`/`↓` move highlight, `Enter` switches, `Escape` closes
   - Mouse hover updates the highlight (consistent with VSCode quick open)
   - Click on the overlay closes; click inside the modal does NOT close (stops propagation)
   - Visual model: `●` current, `○` idle, `+` new-session row, `current` tag on the active row
2. **`App.tsx`** — wires the picker:
   - `showSessionPicker` state
   - Ctrl/Cmd+Shift+P toggles the picker (skipped while typing, same as Ctrl/Cmd+P)
   - Renders `<SessionPickerModal>` conditionally
3. **`Header.tsx`** — adds an optional `onSessionPickerClick` prop and a `🗂` button. When the App passes the prop, the button is rendered next to the existing `🔧` (Tools) and `⚙` (Settings) buttons.

## Numerical results

| Test runner | Tests | Pass | Fail | Error | Skip |
|---|---:|---:|---:|---:|---:|
| vitest (R111 new file) | 15 | 15 | 0 | 0 | 0 |
| vitest (R104, R103 preserved) | 51 | 51 | 0 | 0 | 0 |
| **vitest total** | **66** | **66** | **0** | **0** | **0** |
| Java (no change, R110 preserved) | 4216 | 4216 | 0 | 0 | 0 |
| TypeScript typecheck | — | — | 0 | 0 | 0 |
| BUILD SUCCESS (Java 17 modules) | — | — | — | — | — |

## New tests (15)

The desktop test harness doesn't have `@testing-library/react` installed, so R111 follows the **source-only assertion** pattern that R104 established for `ToolsPanel.test.ts`. Each test reads the source file and verifies the wiring via regex match.

1. `SessionPickerModal.tsx exists`
2. `SessionPickerModal.css exists`
3. `imports the SessionPickerModal into App.tsx`
4. `wires the picker state (showSessionPicker / setShowSessionPicker)`
5. `renders SessionPickerModal when showSessionPicker is true`
6. `binds Ctrl/Cmd+Shift+P to open the picker`
7. `Header accepts onSessionPickerClick prop`
8. `Header renders the session-picker button with Ctrl/Cmd+Shift+P hint`
9. `App.tsx passes onSessionPickerClick to Header`
10. `SessionPickerModal uses the store for sessions / currentSessionId / switchSession / createNewSession`
11. `SessionPickerModal handles keyboard navigation (ArrowDown / ArrowUp / Enter / Escape)`
12. `SessionPickerModal filters by query (label + id + name)`
13. `SessionPickerModal always shows the + new session row`
14. `SessionPickerModal closes on overlay click and on Escape`
15. `SessionPickerModal renders nothing when closed (returns null)`

## Cross-cutting design lessons (R111)

1. **The shortcut stack is now consistent.** Ctrl/Cmd+P / Ctrl/Cmd+K (palette), Ctrl/Cmd+T (tools), Ctrl/Cmd+Shift+P (picker), Ctrl/Cmd+1-9 (jump to Nth session). All modals follow the same shape: open with shortcut, close with Escape or overlay click. A user who learns one shortcut learns the others.

2. **The picker and the sidebar are complementary, not redundant.** The sidebar shows the current session at a glance. The picker is for "jump to session" — fuzzy search is the killer feature when the list is long. Both ship; the user picks the one that matches the task.

3. **Source-only tests are the desktop norm.** R104 set the precedent: when a component needs a DOM harness that's not installed, verify the wiring via source regex instead. R111 follows the same pattern. The cost is "tests don't catch behaviour regressions" but the benefit is "tests run in 1.6s, no dependency footprint". For a small UI surface, wiring verification is enough.

4. **`isContentEditable` matters for input detection.** R111's keydown handler uses the same `isTypingTarget` helper as Ctrl/Cmd+P / Ctrl/Cmd+T. Without it, pressing Ctrl+Shift+P inside a text input would pop the picker open. The helper checks `INPUT` / `TEXTAREA` / `isContentEditable` — all three are real ways the user can be "typing".

5. **The `+ new session` row is always visible, even with a query.** A naive "filter" implementation would hide the new-session row when the query is non-empty. But the user might be searching for a session that doesn't exist, and the natural follow-up is "create a new one". Always showing the row matches the user's mental model.

## Files (R111)

- NEW: `aethercode-desktop/src/components/SessionPickerModal.tsx` (~8.6 KB)
- NEW: `aethercode-desktop/src/components/SessionPickerModal.css` (~3 KB)
- NEW: `aethercode-desktop/src/components/SessionPickerModal.test.tsx` (15 tests, source-only)
- EDIT: `aethercode-desktop/src/App.tsx` (import + state + keyboard handler + conditional render)
- EDIT: `aethercode-desktop/src/components/Header.tsx` (optional `onSessionPickerClick` prop + `🗂` button)

## Cumulative R1-R111 (AetherCode)

- 4216 Java tests (no change in R111)
- 66 vitest (+15 R111)
- 9 node `--test` (no change)
- 17 reactor modules
- 0 known regressions
- New capabilities: keyboard-driven session picker, Ctrl/Cmd+Shift+P shortcut

## R111+ follow-up candidates

- **R112**: TUI/Desktop skip-low badge + permissionModeSuggestion wiring
- **R97-K**: Session resume (R106 sessionStore + R97-A SessionManager union)
- **R97-L**: Per-session cwd override (multi-worktree)
- **Picker enhancement**: rename session inline, jump to next/previous unread, archive
- **Picker discoverability**: first-time tooltip showing the Ctrl/Cmd+Shift+P hint
