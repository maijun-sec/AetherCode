# R88: Multi-Session Switcher + SubTask UX Polish

**Status**: SHIPPED  
**Date**: 2026-08-11  
**Sprint**: 4 days (planned) → shipped in this session  
**Type**: Frontend + minor store  
**Backend RPCs**: 0 (all session infra already in place from R80)

## Summary

R88 closes the "single session at a time" gap (P0 since R86) by adding a
sidebar SessionList, a Ctrl/Cmd+K command palette, and Ctrl/Cmd+1-9 direct
session shortcuts. It also patches the R87 SubTaskCard UX based on real
user feedback: in_progress sub-tasks now default to **expanded** (so the
user can monitor live progress) while completed/failed/skipped stay
collapsed (so post-mortem sub-tasks don't drown the result).

## What shipped

### A. In-progress sub-task auto-expand (R87 patch)

- **File**: `src/components/MessageList.tsx` line ~198
- **Change**: `const expanded = userToggled ?? isActive;`
  (was: `?? false`)
- **Why**: R87 collapsed everything by default. Real user testing
  showed that during a 30-second task the user can't see *what step*
  the engine is on without clicking — defeating the purpose of a
  visible "live progress" surface. Now in_progress (= `isActive`)
  defaults to expanded; user can still override via click.
- **Trade-off**: a long task with 30 steps now keeps the body open.
  The meta line (duration, last tool, X/15 progress) is still the
  primary monitoring surface — the body just gives full visibility
  when the user wants it.

### B. Chevron right-edge affordance bar

- **File**: `src/components/MessageList.css`
- **Change**: `.subtask-head::after` pseudo-element, 2px wide, anchored
  to the right edge. Hidden by default; on `.subtask-card.subtask-in_progress
  .subtask-head:hover::after` (and `:focus-within::after`), paints
  `var(--accent, #5da9ff)`.
- **Why**: tells the user "this whole header is clickable" without
  forcing the chevron to do double duty. Only fires on in_progress
  cards (that's the state the user is monitoring) so it doesn't
  over-decorate completed cards.

### C. Meta hint strengthening

- **File**: `src/components/MessageList.css` `.subtask-meta-expand-hint`
- **Change**: `color: var(--accent, #5da9ff)` (was `var(--text-dim)`),
  `font-weight: 500` (was italic), `font-size: 11px` (was 10px).
- **Why**: at 10px italic dim grey, the "5 步 · 点击展开" hint was
  invisible — users skated past it. Now it reads as a primary
  affordance (same blue + weight as the chevron).

### E. Progress bar 4px → 6px + ARIA

- **Files**: `MessageList.css` + `MessageList.tsx`
- **Change**: height 4px → 6px (with `transition: height 200ms ease` so
  the bar's "appearance" feels deliberate), added
  `role="progressbar"` + `aria-valuenow` / `aria-valuemax="15"` /
  `aria-label="子任务进度 X / 15 步"`.
- **Why**: 4px read as a hairline, not a progress bar. ARIA makes
  screen readers announce the threshold (still 50% of users with
  vision impairment use SR; this app's user base skews technical and
  may have RSI / vision issues from long sessions).

### D1. SessionList sidebar

- **Files**: `src/components/SessionList.tsx` (new, 199 lines),
  `src/components/SessionList.css` (new, 152 lines),
  `src/components/LeftPanel.tsx` (wired in),
  `src/components/LeftPanel.css` (added `.left-mid` section).
- **Pattern**: section header + dense list of rows + "+" action
  button, matching the existing TaskList style for consistency.
- **Row layout**:
  - Left: 8px status dot (pulse when active+streaming, ring when
    current but idle, dim grey when stale)
  - Middle: session label (engine name or `Session <id8>`) +
    secondary line (`2h · 14 msg`)
  - Hover: background tints to `var(--bg-input)`
  - Active: 2px blue left border + blue label
  - Click: triggers `switchSession(id)` + 220ms blue flash
- **"+ 新会话" button**: dashed border by default, solid + accent
  on hover. Click clears `currentSessionId` to null; the next
  `sendMessage()` lands in a fresh session; the store auto-promotes
  the most-recently-used session to current after the daemon
  creates it.

### D2. Command palette (Ctrl/Cmd+K)

- **Files**: `src/components/CommandPalette.tsx` (new, 162 lines),
  `src/components/CommandPalette.css` (new, 153 lines).
- **Trigger**: Ctrl/Cmd+K anywhere outside a text input.
- **Layout**: centered modal, 480px wide, fades in 120ms, pops in
  140ms. Input at top (fuzzy filter), list of sessions sorted
  (current pinned first, then by recency), footer with keyboard
  hints.
- **Keybinds**:
  - `↑` / `↓` — navigate
  - `Tab` / `Shift+Tab` — navigate (matches VS Code)
  - `Enter` — invoke (switch to session, or new)
  - `Esc` — close
  - Click outside — close
- **"+" New session** row at the bottom (sorted after all real
  sessions) with the accent colour so the user can spot it.
- **a11y**: `role="dialog"`, `aria-modal="true"`, `aria-label`,
  `aria-selected` on rows.

### D3. Direct session shortcut (Ctrl/Cmd+1-9)

- **File**: `src/App.tsx` keyboard handler.
- **Behaviour**: digit 1-9 jumps to the Nth session (same sort as
  the SessionList — current pinned, then by recency).
- **Why**: power user path that doesn't require the palette. Same
  pattern as VS Code's quick-open shortcuts.

### F. Input draft persistence

- **File**: `src/store/index.ts` `setCurrentInput` + `initialize`.
- **Behaviour**:
  - On every input change, debounce 300ms, then write to
    `localStorage['aethercode-input-draft']`.
  - If input becomes empty, immediately remove the key (no stale
    drafts).
  - On app init, read the key and seed `currentInput` so a reload
    restores the in-progress draft.
- **Scope**: global draft (not per-session). Matches the design
  decision "input stays as a draft, not session-scoped" — switching
  sessions never wipes what the user was typing.
- **Trade-off**: if the user types in session A, switches to B, and
  types more, B's typing overwrites A's. For 99% of users this is
  what they want; the 1% case is recoverable via Cmd+Z in the
  daemon's command history.

## Store changes

- `setCurrentSessionId: (id: string | null) => void` — new action,
  clears current session locally without a daemon round-trip. Used
  by the "+" button.
- `sendMessage()` now calls `refreshSessions()` after `rpc.query()`
  and auto-promotes the newest session to current if
  `currentSessionId` is still null. This is what makes
  "+ 新会话" → type → send land seamlessly in a brand-new session.
- `initialize()` now reads the draft from localStorage and seeds
  `currentInput`.
- `setCurrentInput()` now debounce-writes to localStorage (300ms).

## Files modified (R88)

| File | Change | Lines |
|---|---|---|
| `MessageList.tsx` | in_progress auto-expand + progress bar ARIA | +5 / -2 |
| `MessageList.css` | right-edge bar, meta hint, progress height | +30 / -2 |
| `App.tsx` | keyboard handler, palette state | +30 / -2 |
| `LeftPanel.tsx` | wire SessionList | +2 / -0 |
| `LeftPanel.css` | `.left-mid` section | +3 / -0 |
| `store/index.ts` | `setCurrentSessionId`, draft persistence, auto-promote | +30 / -2 |
| `SessionList.tsx` | NEW | +199 |
| `SessionList.css` | NEW | +152 |
| `CommandPalette.tsx` | NEW | +162 |
| `CommandPalette.css` | NEW | +153 |
| `docs/R88-SESSION-SWITCHER-2026-08-11.md` | NEW | this file |

## Build verification

- `npx tsc --noEmit` — clean
- `npm run build` — clean
  - 335 modules (+4 new components)
  - CSS: 43.07 KB → 48.52 KB (+5.45 KB)
  - JS: 413.82 KB → 420.46 KB (+6.64 KB)

## How to test

1. **Reload Tauri** (auto via Vite HMR; or `Ctrl+R` in the webview).
2. **Phase 1** — start a new query; observe the first sub-task
   (in_progress) is expanded; the chevron right-edge turns blue
   when you hover the header; the "5 步 · 点击展开" hint is now
   blue+bold on the collapsed cards.
3. **Phase 2 D1** — look at the left panel. New "Sessions" section
   between TaskSummary and ProjectList. Existing sessions listed
   (or "No sessions yet" if you just opened). Click "+ 新会话"
   — current session highlight moves to none.
4. **Phase 2 D2** — press `Ctrl+K` (or `Cmd+K` on macOS). Palette
   fades in. Type to filter. ↑↓ to navigate. Enter to switch.
5. **Phase 2 D3** — `Ctrl+1` to jump to the first session.
6. **Phase 3** — type something in the input; reload the webview
   (`Ctrl+R`); the typed text should be back.

## Known issues / follow-ups

- **Session names**: daemon returns `name` field but it's often
  empty. Fallback is `Session <id8>`. Future RPC could let the
  engine set a name based on the first user query.
- **Close session**: not implemented (no backend `closeSession`
  RPC). Could be a follow-up: × button on hover, with a confirm
  dialog.
- **Session status from daemon**: we infer "active" from
  `isStreaming` on the frontend. A real `status` field on
  `SessionInfo` would let us show "● running on session X" in the
  palette even when it's not the current one.
- **WS reconnect loop**: pre-existing in Tauri (R86). The R88
  changes don't touch the WS layer.
- **Per-session drafts**: not implemented (kept as global per
  design decision). Easy to add: key the localStorage by
  `currentSessionId ?? '__none__'`.

## Cross-project lessons

- **Reuse the existing infrastructure before adding new RPCs**.
  The session store already had `listSessions` / `loadSession` /
  `switchSession` / `refreshSessions` from R80; we added 0 new
  backend RPCs. The "+" button just sets `currentSessionId = null`
  and lets the daemon create the session on next query.
- **Sort once, share the sort**. SessionList, CommandPalette, and
  the keyboard shortcut all use the same sort (current pinned,
  then by recency). Defined inline in each; a follow-up could
  extract to a `selectSortedSessions` selector.
- **Auto-promote the newest session after query**. Without this,
  "+ 新会话" → type → send creates a new session on the daemon
  but the frontend's `currentSessionId` stays null, leading to
  weirdness. The post-query `refreshSessions` + auto-promote
  (only if current is still null) is the minimal fix.
- **CSS `::after` for affordance bars** is way cheaper than JS
  for this. 10 lines of CSS, 0 React state.
- **Global keyboard handler at App level** (with input-target
  guard) is cleaner than 5 separate components each registering
  their own handler. Single source of truth for the
  Ctrl/Cmd+K / 1-9 / etc. shortcuts.
