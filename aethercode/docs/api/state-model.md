# TUI State Model

The TUI's `state` is a flat record held in a React `useReducer`. The
reducer is **pure** — given `(state, action)`, it returns a new
state. Side effects (RPC calls, file I/O, timers) live in the App
component via `useEffect` and event handlers.

The state file is `aethercode-tui/src/state.ts`.

## Shape

```typescript
interface State {
  // R31: connection
  sessionId: string;        // from getState RPC
  model: string;            // active model name
  status: AppStatus;         // "connecting" | "ready" | "thinking" | "streaming" | "running-tool" | "exiting"
  pending: number;           // in-flight tool call count
  permissionMode: string;    // "DEFAULT" | ...
  connected: boolean;
  jarPath: string;           // path to the aethercode-*.jar (for the status bar)

  // R31: input
  input: string;
  history: string[];
  historyCursor: number;    // -1 = current draft
  submitting: boolean;

  // R31: turns (the scrollback)
  turns: Turn[];

  // R31: cost / token tracking
  inputTokens: number | null;
  outputTokens: number | null;
  totalCostUsd: number | null;

  // R32-B: collapse state
  collapseAll: boolean;

  // R32-D: permission flow
  permissionAsk: PermissionAsk | null;

  // R32-G: run-end visibility
  lastStopReason: string | null;
  lastStopKind: "ok" | "loop" | "max_turns" | "error" | "empty" | null;

  // R37: side panel
  sidebarVisible: boolean;
  recentTasks: Array<{id, name, status, ts}>;

  // R40: search
  showSearch: boolean;
  searchQuery: string;
  searchMatches: number[];

  // R41: toasts
  toasts: Array<{id, kind, text, createdAt}>;

  // R42: theme
  themeName: "default" | "solarized" | "monokai";

  // R43: layout
  layout: "full" | "minimal" | "focus";

  // R44: cost budget
  costBudget: number;

  // R47: command palette
  paletteOpen: boolean;

  // R48: cost sparkline
  recentCosts: number[];

  // R50: rewind
  rewindTarget: number | null;

  // R51: snippets
  snippets: Record<string, string>;

  // R52: error display
  lastError: { message, ts, stack? } | null;

  // R56: log viewer
  logBuffer: Array<{ts, level, message}>;
  logViewerOpen: boolean;

  // R59: tutorial
  tutorialOpen: boolean;

  // R60: bookmarks
  bookmarks: number[];

  // R78: trace recorder (mirrors getTraces snapshot)
  recentTraces: TraceSummary[];
  tracesInFlight: number;
  tracesCompleted: number;

  // R79: single-trace tree (mirrors getTrace snapshot)
  spanTree: TraceSummary[];
  selectedTraceId: string | null;

  // R31: help
  helpVisible: boolean;
  showHelp: boolean;          // alias used by Ctrl-? handler
}

interface TraceSummary {       // R78 + R79: added parentSpanId
  traceId: string;
  name: string;
  parentSpanId: string | null; // R79
  startMs: number;
  endMs: number;
  status: "running" | "ok" | "error";
  durationMs: number;
  attrs: Record<string, unknown>;
}
```

## Turn shape

```typescript
interface Turn {
  id: number;                // unique, monotonically increasing
  role: Role;                 // "user" | "assistant" | "tool" | "plan" | "system" | "error"
  text: string;
  ts: number;                 // epoch ms
  toolName?: string;
  toolArgs?: string;           // JSON-serialised
  toolStatus?: "running" | "ok" | "error";
  toolResult?: string;
  planItems?: string[];
  collapsed: boolean;          // R32-B
  previewChars: number;        // R32-B
  // R34
  toolStartedAt?: number;      // epoch ms
  toolEndedAt?: number;
  toolCategory?: "read" | "write" | "search" | "run" | "agent" | "other";
  // R34
  toolExpanded?: boolean;     // toggled by 'd'
}
```

## Action vocabulary

Every state mutation goes through a `dispatch(action)`. The action
types are:

```
init               — RPC result of getState arrived
setInput           — user typed something
setHistory         — history fetched (future)
historyUp / Down   — ↑/↓ navigation
submit             — Enter pressed; clears lastStopReason + lastError
streamStart        — run_start event
streamText         — text_delta event
streamToolStart    — tool_use_start event (sets toolStartedAt + toolCategory)
streamToolEnd      — tool_result event (sets toolEndedAt)
streamEnd          — run_end event (sets lastStopReason + lastStopKind)
sideNote           — side_note event
plan               — plan event
log                — log event
showHelp           — Ctrl-? pressed
exit               — Ctrl-C pressed
disconnect         — daemon died
toggleTurn         — Tab/Ctrl-E on a turn
setCollapseAll     — Ctrl-O
permissionAsk      — permission_request notification
permissionClear    — A/Y/D/N pressed
toggleToolExpand   — 'd' on a tool turn
toggleSidebar      — Ctrl-B
setRecentTasks     — listTasks RPC result
showSearch         — Ctrl-F
setSearchQuery     — search input changed
pushToast          — emit a toast
trimToasts         — drop expired toasts (200ms tick)
setTheme           — /theme NAME
setLayout          — /layout NAME
setCostBudget      — /budget USD
setPaletteOpen     — Ctrl-P
pushCost           — run_end captured a cost (for the sparkline)
setRewindTarget    — /rewind N
saveSnippet / deleteSnippet  — /snippet
setLastError       — exception captured
pushLog            — log notification
setLogViewerOpen   — Ctrl-L
setTutorialOpen    — Ctrl-T
toggleBookmark     — 'b' or /bookmark
setRecentTraces    — /trace RPC result
setSpanTree        — /trace <id> RPC result (R79)
```

## Per-round reducer additions

Each new round adds a small set of fields + actions. The pattern
is always:

1. **Field** added to `State` (with a default in `INITIAL`).
2. **Action type** added to the `Action` union.
3. **Reducer case** added to handle the action.
4. **TUI wiring** in `tui.tsx` (input handler, render, etc.).
5. **Test** in `scripts/test/rXX-*.test.mjs`.

See `dev-guide/adding-rounds.md` for the full workflow.

## Common pitfalls when adding state

- **Tick effect**: live re-renders (R34 duration, R48 sparkline,
  R41 toasts) use a `useEffect` with `setInterval` and a state
  counter; without the counter, the component doesn't re-render.
- **Strict prefix matching**: the `classifyStopReason` function uses
  `r.startsWith("loop_")` to avoid false positives. Tests enforce
  consistency between `state.ts` and `line.ts`.
- **NaN guards**: ratios in `MetricsCollector` use `d <= 0 ? 0.0 : n/d`.
- **Modal ordering** in `useInput`: always check `state.permissionAsk`
  first, then `state.helpVisible`, then any other modal, then the
  input box. R32-D gotcha.
- **Reducer immutability**: never mutate `state` in place; always
  spread and return a new object.
