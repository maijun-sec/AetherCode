import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * source-only assertions for the RightPanel "Plan" tab
 * that renders the live todo list the model emits via
 * `todo_write`. Matches the project convention (see
 * preparingCardR182.test.ts, RpcCommandPaletteR127.test.ts) —
 * verify the wire path without rendering. The actual rendering
 * correctness comes from the human eye; the source-pin tests
 * catch regressions in the wiring (file deletion, import loss,
 * tab string drift, state field rename, etc.).
 *
 * Wire path the tests guard:
 *
 *   daemon tool_use_start (toolName='todo_write', input.todos=[...])
 *     -> store/index.ts tool_use_start case (parse ev.input.todos
 *        into TodoItem[]; set currentTodos)
 *     -> RightPanel tab='plan' branch -> <AgentTasksPanel />
 *     -> AgentTasksPanel reads currentTodos + subTasks via
 *        useStore selectors, renders nested todo list
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();

function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}

describe('R228: RightPanel "Plan" tab + AgentTasksPanel', () => {
  // --- Files ---
  it('AgentTasksPanel.tsx exists', () => {
    expect(existsSync(join(root, 'src/components/AgentTasksPanel.tsx'))).toBe(true);
  });
  it('AgentTasksPanel.css exists', () => {
    expect(existsSync(join(root, 'src/components/AgentTasksPanel.css'))).toBe(true);
  });

  // --- AgentTasksPanel.tsx imports & shape ---
  const panelSrc = read('src/components/AgentTasksPanel.tsx');

  it('AgentTasksPanel reads currentTodos + subTasks from the store', () => {
    // The panel must subscribe to both data sources so the
    // runtime sub-task status overrides the model-declared
    // status (see resolveSubStatus in the panel).
    expect(panelSrc).toMatch(/useStore\(\(s\)\s*=>\s*s\.currentTodos/);
    expect(panelSrc).toMatch(/useStore\(\(s\)\s*=>\s*s\.subTasks/);
  });

  it('AgentTasksPanel imports TodoItem and TodoSubTask types', () => {
    // The types are exported from store/index.ts (R228 added
    // them). The panel must consume them so the rendered list
    // is type-checked end-to-end.
    expect(panelSrc).toMatch(/import\s*\{[^}]*\bTodoItem\b[^}]*\}\s*from\s*'\.\.\/store'/);
    expect(panelSrc).toMatch(/import\s*\{[^}]*\bTodoSubTask\b[^}]*\}\s*from\s*'\.\.\/store'/);
  });

  it('AgentTasksPanel renders an empty state when todos is empty', () => {
    // The "Plan not started yet" placeholder is the contract
    // for the first half-second of every query — without it
    // the tab is a blank column. Guard the literal so a
    // future copy-edit doesn't silently delete it.
    expect(panelSrc).toMatch(/Plan not started yet/);
  });

  it('AgentTasksPanel renders a progress bar with a percent', () => {
    // The 1-line summary (`3/8 done · ▶ 1 running`) plus the
    // horizontal progress bar is the main glance value — the
    // whole point of the tab.
    expect(panelSrc).toMatch(/done/);
    expect(panelSrc).toMatch(/agent-tasks-progress/);
    expect(panelSrc).toMatch(/aria-valuenow/);
  });

  it('AgentTasksPanel renders nested sub-tasks with status icons', () => {
    // The user explicitly asked for "task 下面再分 subtask".
    // Guard the literal `agent-subtasks` class + the icon
    // map so a refactor can't silently flatten the rendering.
    expect(panelSrc).toMatch(/agent-subtasks/);
    expect(panelSrc).toMatch(/SUB_STATUS_ICON/);
  });

  it('AgentTasksPanel status icon map covers all 5 sub-task statuses', () => {
    // R85 sub-task status enum has 5 values; a missing entry
    // makes the panel render `undefined` for the icon glyph.
    expect(panelSrc).toMatch(/pending:\s*'○'/);
    expect(panelSrc).toMatch(/in_progress:\s*'▶'/);
    expect(panelSrc).toMatch(/completed:\s*'✓'/);
    expect(panelSrc).toMatch(/failed:\s*'✗'/);
    expect(panelSrc).toMatch(/skipped:\s*'–'/);
  });

  it('AgentTasksPanel status icon map covers all 5 top-level statuses', () => {
    // Top-level has cancelled instead of failed; guard that
    // distinction so a copy-paste of the SUB map doesn't
    // accidentally drop cancelled.
    expect(panelSrc).toMatch(/cancelled:\s*'⊘'/);
  });

  // --- RightPanel.tsx wiring ---
  const rightPanelSrc = read('src/components/RightPanel.tsx');

  it('RightPanel imports AgentTasksPanel', () => {
    expect(rightPanelSrc).toMatch(
      /import\s*\{\s*AgentTasksPanel\s*\}\s*from\s*'\.\/AgentTasksPanel'/,
    );
  });

  it('RightPanel state union has a "plan" tab', () => {
    // The tab discriminator must include 'plan' so the toggle
    // type-checks. Drift here breaks the tab.
    expect(rightPanelSrc).toMatch(/'telemetry'\s*\|\s*'plan'/);
  });

  it('RightPanel renders a "Plan" tab button', () => {
    // The button text is `>Plan</button>` (no whitespace between
    // `Plan` and the closing tag because there's no badge child).
    expect(rightPanelSrc).toMatch(/>Plan<\/button>/);
  });

  it('RightPanel renders AgentTasksPanel when tab is "plan"', () => {
    // The branch must mount AgentTasksPanel inside a right-section
    // wrapper, mirroring the other tabs' shape.
    const branch = rightPanelSrc.match(
      /tab === 'plan' \?\s*\([\s\S]*?\)\s*:\s*tab === 'subagents'/,
    );
    expect(branch, 'plan tab branch not found').toBeTruthy();
    expect(branch![0]).toMatch(/<AgentTasksPanel\s*\/>/);
  });

  // --- store/index.ts: currentTodos + todo_write capture ---
  const storeSrc = read('src/store/index.ts');

  it('store exports TodoItem and TodoSubTask types', () => {
    // The types are part of the public surface — the panel
    // imports them. A rename would break the import in
    // AgentTasksPanel.tsx but the source-pin test catches it
    // first.
    expect(storeSrc).toMatch(/export interface TodoItem\b/);
    expect(storeSrc).toMatch(/export interface TodoSubTask\b/);
  });

  it('AppState has currentTodos field', () => {
    expect(storeSrc).toMatch(/currentTodos:\s*TodoItem\[\]/);
  });

  it('initial state initializes currentTodos to []', () => {
    // The exact line in the initial-state object so a future
    // refactor doesn't forget the empty default.
    expect(storeSrc).toMatch(/currentTodos:\s*\[\]/);
  });

  it('clearCurrentQuery resets currentTodos to []', () => {
    // A fresh user query must wipe the prior plan so the
    // panel doesn't show a stale list against a new run. The
    // call spans multiple lines in the source, so use a
    // non-line-anchored pattern.
    expect(storeSrc).toMatch(/clearCurrentQuery:[\s\S]{0,200}currentTodos:\s*\[\]/);
  });

  it('tool_use_start case captures todo_write input.todos', () => {
    // The whole point of R228: the wire event's raw input is
    // already complete, so we just parse it. Guard the
    // special-case so a future refactor doesn't drop it.
    const caseBody = storeSrc.match(
      /case 'tool_use_start':\s*\{[\s\S]*?break;\s*\}/,
    );
    expect(caseBody, 'tool_use_start case body not found').toBeTruthy();
    const body = caseBody![0];
    expect(body).toMatch(/toolName === 'todo_write'/);
    expect(body).toMatch(/ev\.input/);
    expect(body).toMatch(/Array\.isArray\(raw\)/);
    expect(body).toMatch(/currentTodos:\s*nextTodos/);
  });

  it('todo_write parser is defensive against missing fields', () => {
    // The model occasionally emits garbage; the panel must
    // degrade to an empty render rather than throw. Guard the
    // typeof / Array.isArray guards.
    const caseBody = storeSrc.match(
      /case 'tool_use_start':\s*\{[\s\S]*?break;\s*\}/,
    )![0];
    expect(caseBody).toMatch(/typeof\s+t\?\.content\s*===\s*'string'/);
    expect(caseBody).toMatch(/Array\.isArray\(t\?\.subtasks\)/);
  });

  it('AgentTasksPanel uses subTasks (R228) to override subtask status', () => {
    // R85 wired sub_task_start/end events into store.subTasks.
    // R228 cross-references that data so a sub-task can flip
    // to completed via sub_todo_write even without a fresh
    // todo_write call. The composite key is `${taskId}:${subTaskId}`.
    const resolveFn = panelSrc.match(/function\s+resolveSubStatus[\s\S]*?\n\}/);
    expect(resolveFn, 'resolveSubStatus helper not found').toBeTruthy();
    expect(resolveFn![0]).toMatch(/rs\.taskId === topIndex/);
    expect(resolveFn![0]).toMatch(/rs\.subTaskId === sub\.id/);
  });
});
