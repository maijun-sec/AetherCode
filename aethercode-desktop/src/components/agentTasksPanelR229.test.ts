import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * source-only assertions for the daemon-driven `task_state
 * kind=todo_update` notification path. R228 wired the "Plan"
 * tab to read from a `currentTodos` field that captured the raw
 * `tool_use_start` input — a one-layer-removed fast path. R229
 * adds the canonical wire: the engine's AppState publishes
 * the list via `setTodoList`, and AetherCodeMethods now emits
 * a JSON-RPC notification (`task_state` + `kind: "todo_update"`)
 * that the desktop picks up off the existing `ws-notify` IPC
 * channel. The Plan tab uses the daemon list when present and
 * falls back to the wire list for the sub-second window before
 * the notification round-trips.
 *
 * Wire path the tests guard:
 *
 *   daemon AppState.setTodoList(list)
 *     -> AetherCodeMethods.onTodoUpdate listener
 *     -> notifyCustom(NOTIFY_TASK_STATE, {kind:"todo_update",
 *         sessionId, params: {todos: list}})
 *     -> tauri lib.rs ws-notify emit
 *     -> desktop store/index.ts rpc.on('task_state', ...) handler
 *     -> set({ daemonTodos: normalized TodoItem[] })
 *     -> AgentTasksPanel: todos = daemonTodos ?? wireTodos
 *         (daemon > wire source-priority)
 *
 * Note: the new field is `daemonTodos` (not `todos`) to avoid
 * a name clash with the R200+ `todos` field on the same
 * AppState — the R200+ one has a flat shape and was
 * referenced only by the dead TodoBoard component
 * (deleted in R230).
 */
const root = (() => {
  if (typeof __dirname !== 'undefined') return join(__dirname, '..', '..');
  return join(dirname(fileURLToPath(import.meta.url)), '..', '..');
})();
const repoRoot = join(root, '..');

function read(rel: string): string {
  return readFileSync(join(root, rel), 'utf-8');
}
function readRepo(rel: string): string {
  return readFileSync(join(repoRoot, rel), 'utf-8');
}

describe('R229: daemon-driven Plan tab via task_state kind=todo_update', () => {
  // --- store/index.ts: `daemonTodos` field + initial + reset ---
  const storeSrc = read('src/store/index.ts');

  it('AppState has `daemonTodos` field (R229 canonical daemon list)', () => {
    // The new field sits next to `currentTodos` (R228 wire-event
    // fast path) and `todos` (R200+ dead flat-shape field).
    // Both have the same TodoItem[] shape so the panel can
    // pick one without re-typing.
    expect(storeSrc).toMatch(/daemonTodos:\s*TodoItem\[\]/);
  });

  it('initial state initializes `daemonTodos` to []', () => {
    // The exact line in the initial-state object so a future
    // refactor doesn't forget the empty default.
    expect(storeSrc).toMatch(/daemonTodos:\s*\[\]/);
  });

  it('clearCurrentQuery resets `daemonTodos` to []', () => {
    // A fresh user query must wipe the prior plan so the
    // panel doesn't show a stale list against a new run.
    expect(storeSrc).toMatch(/clearCurrentQuery:[\s\S]{0,300}daemonTodos:\s*\[\]/);
  });

  // --- store/index.ts: rpc.on('task_state', ...) handler ---
  // The handler body has nested arrow functions
  // (raw.map + subs.map) whose closing `});` look identical
  // to the outer callback's `});` to a non-greedy regex.
  // We pin the contract with separate, independent regex
  // assertions against the file as a whole instead of
  // trying to capture the whole callback.

  it('store registers a task_state notification handler', () => {
    // The handler is the bridge between the daemon's
    // NOTIFY_TASK_STATE emission and the store's `daemonTodos`
    // field. Without this subscription the daemon list never
    // reaches the renderer and the panel falls back to the
    // R228 wire-event path permanently.
    expect(storeSrc).toMatch(
      /rpc\.on\(\s*['"]task_state['"]\s*,\s*\(params:\s*any\)\s*=>/,
    );
  });

  it('task_state handler filters on params.kind === "todo_update"', () => {
    // NOTIFY_TASK_STATE is a multiplexed method (R229 design
    // notes called out: kind discriminates the payload). The
    // handler must check kind and drop everything else so
    // future kinds (kind=session_idle, kind=loop_warn, ...)
    // don't accidentally land in `daemonTodos`.
    expect(storeSrc).toMatch(/params\.kind\s*!==\s*['"]todo_update['"]/);
  });

  it('task_state handler reads params.params.todos (nested shape)', () => {
    // The wire shape is { kind, sessionId, params: { todos } } —
    // AetherCodeMethods.java wraps the inner todos under a
    // `params` key. The handler must drill into p.params.todos,
    // not p.todos (top-level would always be undefined).
    expect(storeSrc).toMatch(/p\.params\?\.todos/);
  });

  it('task_state handler validates todos is an array', () => {
    // Defence against a malformed payload (a future daemon
    // version sending a single object instead of an array, or
    // a partial test fixture). Without this guard the
    // subsequent .map call throws.
    expect(storeSrc).toMatch(/Array\.isArray\(raw\)/);
  });

  it('task_state handler normalises wire shape into TodoItem[]', () => {
    // The Java side sends Map<String,Object> with snake_case
    // keys (active_form, subtasks[].id/status/summary). The
    // handler must project these into the camelCase TodoItem
    // shape the panel renders. Guard the field projections.
    expect(storeSrc).toMatch(/t\?\.active_form/);
    expect(storeSrc).toMatch(/typeof\s+t\?\.content\s*===\s*'string'/);
    expect(storeSrc).toMatch(/st\?\.summary/);
  });

  it('task_state handler writes the new list via set({ daemonTodos })', () => {
    // The handler must hand off to the zustand store with the
    // exact field name `daemonTodos` (so the AgentTasksPanel's
    // source-priority selector picks it up).
    expect(storeSrc).toMatch(/set\(\{\s*daemonTodos:\s*next\s*\}\s*\)/);
  });

  it('task_state handler is defensive against missing params', () => {
    // A notification that arrives with a null/undefined
    // payload (a buggy old daemon) must not throw. Guard the
    // type check at the top of the handler.
    expect(storeSrc).toMatch(
      /!params\s*\|\|\s*typeof\s+params\s*!==\s*['"]object['"]/,
    );
  });

  // --- AgentTasksPanel: source-priority ---
  const panelSrc = read('src/components/AgentTasksPanel.tsx');

  it('AgentTasksPanel reads daemon `daemonTodos` from the store', () => {
    // The daemon-pushed field must be in the panel's
    // subscription list so zustand re-renders on updates.
    expect(panelSrc).toMatch(/useStore\(\(s\)\s*=>\s*s\.daemonTodos\b/);
  });

  it('AgentTasksPanel reads `currentTodos` as fallback (R228 quick fix)', () => {
    // The wire-event field is kept as a fallback for the
    // sub-second window before the daemon notification
    // arrives. The panel must still subscribe to it so the
    // fallback is reactive, not stale.
    expect(panelSrc).toMatch(/useStore\(\(s\)\s*=>\s*s\.currentTodos/);
  });

  it('AgentTasksPanel applies daemon > wire source-priority', () => {
    // The two fields carry the same data via two different
    // paths. The panel picks the daemon one when it has
    // content; the wire one is the sub-second-gap fallback.
    // The literal `daemonTodos.length > 0` is the contract.
    expect(panelSrc).toMatch(/daemonTodos\.length\s*>\s*0/);
    expect(panelSrc).toMatch(
      /const\s+todos\s*=\s*daemonTodos\.length\s*>\s*0\s*\?\s*daemonTodos\s*:\s*wireTodos/,
    );
  });

  it('AgentTasksPanel still renders the empty state when both are empty', () => {
    // R228 already covered this; guard the branch survives
    // the R229 refactor (the early-return path must not have
    // been accidentally dropped).
    expect(panelSrc).toMatch(/if\s*\(todos\.length\s*===\s*0\)/);
    expect(panelSrc).toMatch(/Plan not started yet/);
  });

  // --- AetherCodeMethods.java: emit side ---
  const javaMethods = readRepo(
    'aethercode/aethercode-protocol/src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java',
  );

  it('AetherCodeMethods declares TODO_UPDATE_KIND = "todo_update"', () => {
    // The kind constant is the discriminator the desktop
    // uses to filter task_state notifications. A typo here
    // (e.g. "todo-updated") silently drops every update.
    expect(javaMethods).toMatch(
      /public\s+static\s+final\s+String\s+TODO_UPDATE_KIND\s*=\s*"todo_update"/,
    );
  });

  it('AetherCodeMethods wires engine.appState().onTodoUpdate(...) listener', () => {
    // The listener is the bridge from the engine's
    // setTodoList publish to the JSON-RPC notification.
    // Without this the canonical daemon list never reaches
    // the wire.
    expect(javaMethods).toMatch(
      /engine\.appState\(\)\.onTodoUpdate\s*\(/,
    );
  });

  it('AetherCodeMethods emit uses NOTIFY_TASK_STATE method', () => {
    // The notification method name is what the desktop
    // subscribes to via rpc.on('task_state', ...). The
    // constant must be used (not a string literal) so a
    // future rename stays consistent.
    const emitBlock = javaMethods.match(
      /engine\.appState\(\)\.onTodoUpdate[\s\S]*?\}\s*\)\s*;/,
    );
    expect(emitBlock, 'onTodoUpdate emit block not found').toBeTruthy();
    expect(emitBlock![0]).toMatch(/NOTIFY_TASK_STATE/);
  });

  it('AetherCodeMethods emit payload includes kind/sessionId/params', () => {
    // The desktop's handler reads three fields: kind
    // (filter), sessionId (multi-session guard), and
    // params.todos (the actual list). All three must be in
    // the Map.of(...) call.
    const emitBlock = javaMethods.match(
      /engine\.appState\(\)\.onTodoUpdate[\s\S]*?\}\s*\)\s*;/,
    )![0];
    expect(emitBlock).toMatch(/"kind",\s*TODO_UPDATE_KIND/);
    expect(emitBlock).toMatch(/"sessionId",\s*engine\.appState\(\)\.sessionId\(\)/);
    expect(emitBlock).toMatch(/"params",\s*Map\.of\(\s*"todos",\s*todos\s*\)/);
  });

  it('AetherCodeMethods imports Consumer + JsonRpcNotification', () => {
    // The onTodoUpdate listener accepts a
    // Consumer<List<Map<String,Object>>> and emits a
    // JsonRpcNotification — both must be imported for the
    // file to compile.
    expect(javaMethods).toMatch(/import\s+.*\bConsumer\b/);
    expect(javaMethods).toMatch(/import\s+.*\bJsonRpcNotification\b/);
  });

  it('R228 currentTodos path is still wired (regression guard)', () => {
    // R229 adds `daemonTodos` next to `currentTodos`; it does
    // NOT delete the R228 wire-event fast path. The Plan tab
    // falls back to it for the sub-second window before the
    // daemon notification arrives. Guard the tool_use_start
    // special-case survives the R229 refactor.
    const caseBody = storeSrc.match(
      /case 'tool_use_start':\s*\{[\s\S]*?break;\s*\}/,
    );
    expect(caseBody, 'tool_use_start case body not found').toBeTruthy();
    expect(caseBody![0]).toMatch(/toolName === 'todo_write'/);
    expect(caseBody![0]).toMatch(/currentTodos:\s*nextTodos/);
  });

  // --- Wire-shape sanity: daemon's todos field is the engine's
  //     setTodoList list (Map<String,Object>) ---
  it('TodoWriteTool produces the nested subtask shape the daemon emits', () => {
    // Cross-reference: TodoWriteTool's `out` list is the
    // same shape AppState.setTodoList accepts, and the same
    // shape the desktop's task_state handler normalises.
    // Guard the field names line up so a rename on one side
    // doesn't silently desync the other.
    const todoTool = readRepo(
      'aethercode/aethercode-tools/src/main/java/org/aethercode/tools/task/TodoWriteTool.java',
    );
    expect(todoTool).toMatch(/todo\.put\(\s*"content",\s*content\s*\)/);
    expect(todoTool).toMatch(/todo\.put\(\s*"status",\s*status\s*\)/);
    expect(todoTool).toMatch(/todo\.put\(\s*"active_form"/);
    expect(todoTool).toMatch(/sub\.put\(\s*"id",\s*sId\s*\)/);
    expect(todoTool).toMatch(/sub\.put\(\s*"status",\s*sStatus\s*\)/);
  });
});
