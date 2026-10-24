/**
 * R362 panel-intent registry - the single source of truth for
 * "what does this slash command actually open, and on which
 * renderer".
 *
 * ## Why this file exists
 *
 * Before R362, `handleSlash` returned a magic string
 * (`"__MEMORY_PANEL__:project"`) and each renderer hand-wrote an
 * `if (slash.local === "__X__")` chain to claim it. Nothing
 * checked that the claim existed. A static reachability sweep on
 * 2026-10-21 found:
 *
 *   - 35 sentinels emitted by `commands.ts`
 *   - 19 claimed by `tui.tsx`  (Ink full-screen)
 *   -  5 claimed by `line.ts`  (cmd.exe / non-TTY path)
 *   - 15 claimed by NEITHER -> the raw `__MEMORY_PANEL__:project`
 *     string was printed straight to the user
 *
 * 15 orphan components (5,859 lines) sat unreachable behind
 * those sentinels. `/help` advertised all of them.
 *
 * ## The failure mode of the first version of this registry
 *
 * R362 shipped this table with TEN rows marked `unavailable`, on the
 * authority of a static reading rather than a reachability check.
 * **Eight of those ten were false.** The panels had in fact been
 * wired - by the same round - and the table had never been updated:
 *
 *   - `memory-panel`, `thread-selector`, `todo-board`,
 *     `agent-selector`, `effort-picker`, `grants-manager`,
 *     `notification-center`, `mcp-viewer` are all mounted on Ink and
 *     rendered in line mode. They are `PANEL_BY_INTENT` entries in
 *     `tui.tsx` with a matching `case`, and `case "__X__":` arms in
 *     `line.ts`.
 *
 * `/cwd-pick` turned out to be the same story for a sharper reason
 * (R364): the Ink panel existed and rendered, but it sent a JSON-RPC
 * method named `setCwd`, which is the DESKTOP's Tauri IPC command and
 * does not exist on the daemon. So `ink: 'unavailable'` was
 * accidentally right about the feature and completely wrong about the
 * cause.
 *
 * A status field that can be false without failing anything is worse
 * than no status field, because the next reader trusts it. So:
 *
 *   1. `blocker` says WHICH kind of gap it is (see PanelBlocker), and
 *   2. `scripts/test/r365-panel-truth.test.mjs` DERIVES the real
 *      reachability from the two renderer sources and fails if the
 *      declared status disagrees. The table cannot lie.
 *
 * ## Rendering status vocabulary
 *
 * `mounted`     - a real UI surface opens (modal / overlay / inline card)
 * `inline-note` - deliberately renders as a sideNote / log line with a
 *                 readable message (fine for one-shot text output)
 * `rendered`    - line mode draws a real framed block
 * `unavailable` - a KNOWN GAP on THIS renderer. Always paired with a
 *                 `blocker` and a `gapNote`.
 *
 * ## Blocker vocabulary (R365)
 *
 * The three reasons a surface can be unavailable are NOT
 * interchangeable, and collapsing them into one boolean is what let
 * the R362 table drift for a round:
 *
 *   `not-wired`       - the backend works, nobody wrote the UI.
 *                       A task, with a known destination.
 *   `backend-missing` - no such RPC exists on the daemon. Do NOT
 *                       build the UI; it would be a dead button.
 *   `no-command`      - the slash command does not exist, so the
 *                       sentinel has no producer. The entry is a
 *                       placeholder, and it MUST be kept out of
 *                       `/help` (see `advertised`).
 *
 * The last one was a live user-facing bug: `/help` listed `/context`,
 * but typing it hit `handleSlash`'s `default:` and answered
 * "unknown slash command: /context. Try /help."
 */

/** How a renderer surfaces a given intent. */
export type InkStatus = "mounted" | "inline-note" | "unavailable";
export type LineStatus = "rendered" | "inline-note" | "unavailable";

/** WHY a surface is unavailable. See the vocabulary block above. */
export type PanelBlocker =
  /** The backend works; the UI was simply never written. */
  | "not-wired"
  /** No RPC exists on the daemon; a UI would be a dead button. */
  | "backend-missing"
  /** The slash command itself does not exist yet. */
  | "no-command";

export interface PanelIntent {
  /** Canonical kebab id. Used by tests and by /help grouping. */
  id: string;
  /**
   * The exact sentinel string(s) `handleSlash` returns. An array
   * because one panel is reachable from several commands
   * (`/mcp`, `/mcp-login`, `/mcp-reconnect` all land on McpViewer).
   */
  sentinels: string[];
  /** Primary slash command that opens it (no leading args). */
  command: string;
  /** One-line description for `/help`. User-facing copy, no round refs. */
  title: string;
  /** Grouping for the reorganised /help (P0.3). */
  group: "session" | "context" | "config" | "mcp" | "workflow" | "system";
  /** Component that implements it on Ink, when there is one. */
  component?: string;
  ink: InkStatus;
  line: LineStatus;
  /**
   * Required when EITHER renderer is `unavailable`. Names the kind of
   * gap so "nobody wrote it" is never confused with "the daemon cannot
   * do it".
   */
  blocker?: PanelBlocker;
  /** Required whenever `blocker` is set - why we accept the gap. */
  gapNote?: string;
  /**
   * May this row appear in `/help`? Defaults to true.
   *
   * `false` for entries whose command does not exist: advertising a
   * command that answers "unknown slash command" is worse than not
   * listing it.
   */
  advertised?: boolean;
}

/**
 * Every slash command that opens a UI surface or a framed block.
 *
 * Ordering is by `group`, then alphabetically by `command`, so the
 * reorganised `/help` can render straight from this array without a
 * second hand-maintained list.
 */
export const PANEL_INTENTS: PanelIntent[] = [
  // --- context -----------------------------------------------------
  {
    id: "memory-panel",
    sentinels: ["__MEMORY_PANEL__", "__MEMORY_EDIT__"],
    command: "/memory",
    title: "Browse / edit the 3-layer memory (user / project / session)",
    group: "context",
    component: "components/MemoryPanel.tsx",
    // R365: both were 'unavailable' and both were false.
    ink: "mounted",
    line: "rendered",
  },
  {
    id: "context-meter",
    sentinels: ["__CONTEXT_METER__"],
    command: "/context",
    title: "What is filling the context window, itemised",
    group: "context",
    component: "components/ContextMeter.tsx",
    // R371: this row was `unavailable`/`unavailable` with
    // `blocker: 'no-command'` and `advertised: false`, because
    // `handleSlash` had no `case "context"` and answered "unknown
    // slash command" while 251 lines of ContextMeter sat unmounted
    // behind it.
    //
    // Three separate half-finished rounds were involved:
    //
    //   1. T-441 wrote ContextMeter.tsx, added three State fields,
    //      and wrote six source-pin assertions — but never imported
    //      the component into StatusBar and never gave anything a
    //      way to WRITE those three fields. Three of its six
    //      assertions have been red ever since.
    //   2. Nothing called `CompactMethods.registerAll` outside the
    //      test that constructed the object itself, so
    //      `compact/status` was `-32601` on every daemon.
    //   3. Nothing ever emitted `__CONTEXT_METER__`.
    //
    // All three are now closed. The panel is real on both renderers.
    ink: "mounted",
    line: "rendered",
  },

  // --- session -----------------------------------------------------
  {
    id: "thread-selector",
    sentinels: ["__THREAD_SELECTOR__"],
    command: "/threads",
    title: "Switch to another session thread",
    group: "session",
    component: "components/ThreadSelector.tsx",
    ink: "mounted", // R365: was 'unavailable'; PANEL_BY_INTENT has it as "threads".
    line: "rendered", // R365: was 'unavailable'; line.ts has a __THREAD_SELECTOR__ arm.
  },
  {
    id: "session-picker",
    sentinels: ["__SESSION_PICKER__"],
    command: "/sessions",
    title: "This project's sessions — pick one to keep working",
    group: "session",
    // Deliberately the SAME component as `thread-selector`. The two
    // commands differ by exactly one thing — whether the row list
    // is filtered to the active cwd (`State.panelCwdOnly`) — and a
    // second PanelId would have meant a second fetch, a second
    // component instance and a third copy of the loadSession call
    // to keep in sync. R370 pins that they resolve to the same
    // PanelId.
    component: "components/ThreadSelector.tsx",
    ink: "mounted",
    line: "rendered",
    // R370: `/sessions` used to return `{rpcMethod:"listSessions"}`,
    // which printed the RAW WIRE PAYLOAD — id / lastUsedAt /
    // sizeBytes / trashedAt / messageCount / preview, unformatted,
    // with no way to pick anything. `/threads` has been a real
    // picker all along; `/sessions` was the raw dump. Now both
    // open the picker and `/sessions` additionally scopes it to
    // the current cwd.
  },
  {
    id: "todo-board",
    sentinels: ["__TODO_BOARD__"],
    command: "/todos",
    title: "Live TODO list with j/k navigation",
    group: "session",
    component: "components/todo/TodoBoard.tsx",
    ink: "mounted", // R365: was 'unavailable'.
    line: "rendered", // R365: was 'unavailable'.
  },

  // --- config ------------------------------------------------------
  {
    id: "agent-selector",
    sentinels: ["__AGENT_PICK__"],
    command: "/agent-pick",
    title: "Pick the active agent (type to filter)",
    group: "config",
    component: "components/AgentSelector.tsx",
    // The R362 note claimed "NOTHING dispatches it". False: the
    // sentinel path resolves through intentForSentinel ->
    // PANEL_BY_INTENT["agent-selector"] -> openPanel("agents").
    ink: "mounted",
    line: "rendered",
  },
  {
    id: "effort-picker",
    sentinels: ["__EFFORT_PICK__"],
    command: "/effort-pick",
    title: "Set reasoning effort (low / medium / high / xhigh)",
    group: "config",
    component: "components/EffortPicker.tsx",
    ink: "mounted",
    line: "rendered",
  },
  {
    id: "cwd-switcher",
    sentinels: ["__CWD_PICK__"],
    command: "/cwd-pick",
    title: "Switch the daemon working directory",
    group: "config",
    component: "components/CwdSwitcher.tsx",
    // Corrected in R364. This used to read
    //   ink: 'unavailable', line: 'unavailable'
    // with the note "CwdSwitcher.tsx (176 lines) exists, unmounted" -
    // and BOTH halves were false.
    //
    //   * line mode WAS wired: `line.ts` dispatches `__CWD_PICK__` to
    //     `panelCwdPick`, which probes `listProjects` and calls
    //     `switchProject`.
    //   * Ink HAS the render branch (`case "cwd":` renders
    //     `CwdSwitcher`), it was just sending a JSON-RPC method
    //     named `setCwd` that does not exist on the daemon.
    //
    // `setCwd` is the DESKTOP's Tauri IPC command (aethercode-desktop
    // src/store/index.ts -> rpc.setCwd(path, sid)), not a JSON-RPC
    // method. Every `/cwd-pick` died on -32601. The JSON-RPC name is
    // `switchProject` (registered in AetherCodeMethods.registerAll,
    // routed in HttpJsonRpcServer). See docs/R364-cwd-rpc-name.md.
    ink: "mounted",
    line: "rendered",
  },
  {
    id: "grants-manager",
    sentinels: ["__GRANTS_MANAGER__"],
    command: "/consents",
    title: "Review and revoke stored tool grants",
    group: "config",
    component: "components/permissions/GrantsManager.tsx",
    ink: "mounted", // R365: was 'unavailable'; PANEL_BY_INTENT id is "grants".
    line: "rendered", // R365: was 'unavailable'.
    // R367: "mounted" is a REACHABILITY claim, not a WORKING claim, and
    // those were two separate failures here. The panel rendered for
    // months; the RPC it called was -32601 the whole time (the daemon
    // never called PermissionMethods/GrantMethods.registerAll). Now
    // that the daemon answers, line mode has a second failure: it
    // unwraps results with asRows(), and `grants/list` answers
    // `{grants: [...]}` — not a bare array — so the panel rendered
    // "0 stored" with no error. Both are pinned in
    // r366-rpc-names.test.mjs. Reachable is not the same as honest.
  },
  {
    id: "notification-center",
    sentinels: ["__NOTIFICATION_CENTER__"],
    command: "/notifications",
    title: "Pending notices and warning toggles",
    group: "config",
    component: "components/NotificationCenter.tsx",
    ink: "mounted",
    line: "rendered",
  },

  // --- mcp ---------------------------------------------------------
  {
    id: "mcp-viewer",
    sentinels: ["__MCP_VIEWER__", "__MCP_LOGIN__", "__MCP_RECONNECT__"],
    command: "/mcp",
    title: "MCP servers and their tools",
    group: "mcp",
    component: "components/McpViewer.tsx",
    // One component serves all three commands; the login and reconnect
    // variants pass an action hint. All three sentinels resolve to the
    // same PANEL_BY_INTENT id, so all three are reachable on Ink.
    ink: "mounted",
    line: "rendered",
  },

  // --- workflow ----------------------------------------------------
  {
    id: "workflow-picker",
    sentinels: ["__WORKFLOW_PICKER__"],
    command: "/workflow",
    title: "Run a workflow by name, or pick one from the list",
    group: "workflow",
    component: "components/WorkflowPicker.tsx",
    // R373: this row was `ink: 'unavailable'` with
    // `blocker: 'not-wired'` for three rounds, and the blocker was
    // TRUE each time — line mode really did have a frame and Ink
    // really did not.
    //
    // R366 had already fixed the deeper bug underneath: the line
    // frame called `workflow/list` / `workflow/run`, which are
    // registered on SupervisorRpcServer (a separate CLI the TUI never
    // starts), while the daemon the TUI spawns registers the flat
    // `listWorkflows` / `runWorkflow`. So the line frame was calling
    // methods that do not exist on its own daemon.
    //
    // R373 mounted the Ink half and fixed the daemon contract it
    // needs: `listWorkflows` now publishes an ADDRESSABLE `id` (the
    // workflow file's name) alongside the display `name`, because
    // `runWorkflow` resolves a FILE and the YAML's declared `name:`
    // is not necessarily that file's name. It also publishes
    // `skipped`, so "the directory is empty" and "you cannot read
    // what is in it" stop being the same answer.
    ink: "mounted",
    line: "rendered",
  },

  // --- system ------------------------------------------------------
  {
    id: "update-available",
    sentinels: ["__UPDATE_AVAILABLE__", "__UPDATE_DEPS__", "__UPDATE_INSTALL__"],
    command: "/update",
    title: "Check the version, and how to update it",
    group: "system",
    component: "components/UpdatePanel.tsx",
    // R374: this row was `ink: 'unavailable'` with
    // `blocker: 'not-wired'` for three rounds, and the blocker was
    // WRONG. `not-wired` means "the backend works, nobody wrote the
    // UI" — but the daemon has no update RPC at all: no
    // `update/check`, no `update/refreshDeps`, no `update/install`,
    // and no updater module in the reactor. The honest blocker was
    // `backend-missing`, whose rule is "do NOT write the UI; it would
    // be a dead button".
    //
    // So R374 did not mount `UpdateAvailable` / `UpdateConfirm` /
    // `UpdateProgress` — each of them needs a fact this daemon
    // cannot produce ("an update is available", a `latest` version, a
    // download in flight), so each would have rendered a button that
    // does nothing.
    //
    // Instead the panel reports what DOES exist — the TUI and daemon
    // versions, side by side, the way every mature updater shows
    // "current vs available" — says plainly what did NOT happen, and
    // points at the package manager, which is what Electron's own
    // docs recommend for platforms with no built-in updater.
    //
    // The surface is real on both renderers now, so there is no
    // renderer-side blocker left to declare. The gapNote stays,
    // because the gap it describes is STILL REAL: typing `/update`
    // does not update anything. The panel reports the versions and
    // the manual path instead of pretending otherwise.
    //
    // Two different questions, two different answers: "is there a UI
    // surface?" (the ink/line columns — yes) and "does the underlying
    // capability exist?" (the gapNote — no). They do not contradict.
    ink: "mounted",
    line: "rendered",
    gapNote:
      "R374: the daemon has NO update RPC at all — no `update/check`, " +
      "no `update/refreshDeps`, no `update/install`, and no updater " +
      "module in the reactor. R365 had classified this row " +
      "`blocker: 'not-wired'`, which was WRONG: `not-wired` means " +
      '"the backend works, nobody wrote the UI", and its rule is ' +
      '"write the UI". The backend does not work, so the honest ' +
      'blocker was `backend-missing`, whose rule is "do NOT write the ' +
      'UI; it would be a dead button". R374 therefore did not mount ' +
      "`UpdateAvailable` / `UpdateConfirm` / `UpdateProgress` — each " +
      "needs a fact this daemon cannot produce. The panel reports the " +
      "versions, says what did NOT happen, and points at the package " +
      "manager.",
  },
];

/** Every sentinel string declared anywhere in the registry. */
export const ALL_DECLARED_SENTINELS: string[] = PANEL_INTENTS.flatMap((p) => p.sentinels);

/** Rows that may appear in `/help`. Defaults to advertised !== false. */
export const ADVERTISED_INTENTS: PanelIntent[] = PANEL_INTENTS.filter(
  (p) => p.advertised !== false,
);

/** Fast lookup: sentinel -> its declaring intent. */
export function intentForSentinel(sentinel: string): PanelIntent | undefined {
  return PANEL_INTENTS.find((p) => p.sentinels.includes(sentinel));
}

/** Intent reachable from a primary command, e.g. `/memory`. */
export function intentForCommand(command: string): PanelIntent | undefined {
  const norm = command.startsWith("/") ? command : `/${command}`;
  return PANEL_INTENTS.find((p) => p.command === norm);
}

/**
 * Human-readable description of a sentinel, for the case where a
 * renderer is about to give up and print something. Used by the
 * `unavailable` fallback branches so the user sees
 * `"memory panel (not available in line mode)"` instead of
 * `"__MEMORY_PANEL__:project"`.
 */
export function describeSentinel(sentinel: string, surface: "ink" | "line"): string {
  const intent = intentForSentinel(sentinel);
  if (!intent) return sentinel;
  const label = intent.title.replace(/\s*\(.*?\)\s*$/, "");
  return `${label} - ${surface === "line" ? "not available in line mode" : "not available"}`;
}

/**
 * Grouped command listing for the reorganised `/help`.
 * Returns `[{ group, entries: [{ command, title }] }]`.
 *
 * R365: built from ADVERTISED_INTENTS, so an entry whose command does
 * not exist yet is not listed. See the `no-command` blocker.
 */
export function helpByGroup(): { group: string; entries: { command: string; title: string }[] }[] {
  const out: { group: string; entries: { command: string; title: string }[] }[] = [];
  for (const intent of ADVERTISED_INTENTS) {
    let bucket = out.find((b) => b.group === intent.group);
    if (!bucket) {
      bucket = { group: intent.group, entries: [] };
      out.push(bucket);
    }
    bucket.entries.push({ command: intent.command, title: intent.title });
  }
  for (const b of out) b.entries.sort((x, y) => x.command.localeCompare(y.command));
  return out;
}
