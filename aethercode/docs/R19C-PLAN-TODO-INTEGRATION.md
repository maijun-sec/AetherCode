# R19-C — Plan Mode + TODO Integration

**Date**: 2026-08-05
**Status**: DONE — 1185 tests (+2 PlanPanelApproveTest, +7 from R19-B), 0 net regression
**Goal**: When the user approves a plan, the approved steps should
flow into the AppState's todo list so the TUI / CLI / --print see
them as a live TODO plan and the model is nudged to materialise them
via `todo_write`.

---

## Why

R4 introduced a structured plan flow (`PlanPanel`, `StructuredPlan`)
with `/plan`, `/approve-step N`, `/reject-step N`, `/skip-step N`,
`/approve`, `/reject`. The approval step pushed the approved step
titles into the user message as a hint, but the AppState's
`todoList` was never updated. The TUI's `todos` panel stayed empty
until the model decided to call `todo_write` itself (which it
sometimes didn't, leaving the user with no visual progress).

The TS original `claude-code` always converts an approved plan into
a TODO list — the two are tightly coupled.

## What changed

### `PlanPanel.approve()` pushes approved steps to AppState

After switching to execute mode and clearing the plan-mode suffix,
the panel now:

1. Collects the `APPROVED` steps from `currentPlan`
2. Maps each to a `Map<String, Object>` with `content` (the step
   title) and `status` (`"pending"`)
3. Calls `engine.appState().setTodoList(todos)` — the TUI's `todos`
   panel and `--print`'s "📋 TODO plan" line both subscribe to this
   and immediately redraw
4. Appends a hint to the user message: "Call the `todo_write`
   tool with these N steps so they appear in the transcript, then
   start with step 1." The model will then issue a `todo_write` on
   its next turn, which lands the plan in the conversation
   transcript for future turns to reference.

PENDING / REJECTED / SKIPPED steps are NOT included — only
`APPROVED` ones become TODOs.

### `PlanPanel.setStructuredPlan(...)`

New public setter for the `currentPlan` field. Used by tests that
want to drive `approve()` without running a real planning turn.
Production code sets the field inside `runPlanningTurn(String)` via
`StructuredPlan.parse(...)`.

## Tests

- `aethercode-tui/.../PlanPanelApproveTest.java` (2 tests):
  - `approve_pushesApprovedStepsIntoTodoList` — 3-step plan (2
    APPROVED, 1 PENDING) → 2 todos in AppState, both `pending`
  - `approveWithNoPlan_doesNotMutateTodoList` — `/approve` with no
    plan in flight → empty todo list

## Files

- `aethercode-tui/.../PlanPanel.java` — `approve()` pushes
  APPROVED steps to `AppState.todoList`; new `setStructuredPlan` for
  tests
- `aethercode-tui/src/test/.../PlanPanelApproveTest.java` (new)

## Pitfalls (R19-C)

1. **Only APPROVED steps become TODOs** — the panel maps
   `StructuredPlan.approved()` to the list. PENDING / REJECTED /
   SKIPPED are dropped. If the user wants to include a step that
   they previously skipped, they have to `/approve-step N` first
   and then `/approve`.
2. **The model is still responsible for `todo_write`** — pre-seeding
   the AppState's todo list is for the UI (TUI / CLI / hooks). The
   model doesn't see the AppState directly; it sees the system
   prompt + transcript. We append a hint to the user message asking
   the model to call `todo_write`, which lands the plan in the
   transcript for future turns. Without this hint, the model would
   not know about the new TODOs and would re-call `todo_write` with
   an empty list (wiping the AppState's pre-seeded list).
3. **Step has no `detail` field** — `StructuredPlan.Step` is a
   record of `(index, title, status)`. We map `title → content` and
   don't set an `active_form` (the in-progress verb form). The TUI
   falls back to the content when active_form is missing, which
   is acceptable for now. A future round could extend
   `StructuredPlan` to capture a longer detail block per step.

## Backups

`D:\work\workspace\idea\engine\AetherCode\aethercode\docs\backups\r19c\`
(planned)

## Next

R19-D: Multi-step Subagent. Currently `AgentTool` (R18-c) runs a
single LLM call with no tools. This round allows the subagent to
call tools (with a depth limit to prevent infinite recursion) and
shares the parent's transcript (so the subagent has the same
context as the parent).
