/**
 * slash command dispatcher.
 *
 * The TUI recognises a small set of slash commands (see /help).
 * Most just forward to JSON-RPC methods; some are local-only
 * (clear, exit, help, history).
 */

export interface SlashResult {
  /** Local-only: a string to print, or one of the special tokens below. */
  local?: string;
  /** JSON-RPC method to invoke (with optional params). */
  rpcMethod?: string;
  rpcParams?: Record<string, unknown>;
}

export const SLASH_HELP = [
  "/help               show this list",
  "/clear              clear the scrollback",
  "/exit, /quit        exit the TUI",
  "/tools              list available tools",
  "/tool-actions       R100: per-tool permission action (safe / ask / deny) + default op-kind",
  "/state              show engine state",
  "/ping               liveness check",
  "/model <name>       change the model",
  "/mode <name>        change permission mode (DEFAULT / ACCEPT_TASK / ACCEPT_EDITS / BYPASS_PERMISSIONS / PLAN / AUTO_READ_ONLY)",
  "/no-confirm         shortcut: /mode BYPASS_PERMISSIONS (no prompts, every tool call auto-allowed)",
  "/sessions           list saved sessions",
  "/tasks              对应历史 round: list recent tool invocations",
  "/projects           对应历史 round: list known projects (cwds)",
  "/cwd <path>         对应历史 round: switch to another project",
  "/stats              show session stats",
  "/history            show input history",
  "/agents             对应历史 round-sync: list Mavis agents with model column",
  "/agent <name>       对应历史 round-sync: show an agent's body + frontmatter",
  "/prompt              对应历史 round: show current system prompt sections (text, totalChars, sectionCount)",
  "/prompt <name>      对应历史 round: drill into one section's full text (e.g. /prompt rules, /prompt identity)",
  "/phase              对应历史 round: show current phase + per-phase tool-call budget",
  "/phase <name>       对应历史 round: transition to a phase (plan / explore / implement / verify)",
  "/budget <p> <calls> [usd]   对应历史 round: reconfigure a phase's cap (0 = unlimited)",
  "/sessions-list      对应历史 round: list every session in the daemon (with the active one marked)",
  "/session <id>       对应历史 round: switch the active session (must already exist)",
  "/session-new <id>   对应历史 round: create a new session and switch to it",
  "/session-del <id>   对应历史 round: delete a session (default is protected)",
  "/skip <N|off>       R99: arm / clear skip-confirmation (N rounds)",
  "/skip-stats         R106: show skip-confirmation adoption (consumed / armed / prompts)",
  "/memory             T-080: open the MemoryPanel (Global / Project / Session tabs)",
  "/memory [tab]       T-080: open MemoryPanel on a specific tab (global|project|session)",
  "/memory-edit        T-083: edit the focused fact in the MemoryPanel",
  "/memory-compact     T-084: force a project-memory compact (memory/compact force=true)",
  "/agent-pick         T-420: open the agent picker (lists agents, type-to-filter, Ctrl+S to set default)",
  "/effort-pick        T-421: open the reasoning-effort picker (low/medium/high/xhigh)",
  "/cwd-pick           T-423: open the cwd switcher (path input, validates, then cwd/set RPC)",
  "/mcp                T-428: open the MCP server + tool viewer",
  "/mcp-login <name>   T-428: start the OAuth login flow for an MCP server",
  "/mcp-reconnect      T-428: force a reconnect of the MCP registry",
  "/threads            T-429: open the thread selector (filter, choose a thread to resume)",
  "/update             T-430: show the update-available modal (if a new release is pending)",
  "/update-deps        T-430: confirm a dependency refresh",
  "/notifications      T-431: open the notification center (pending notices + warning toggles)",
  "/continue           T-6-17 / Phase 6.3: resume the active long-running task (task/resume)",
  "/pause              T-6-17 / Phase 6.3: pause the active long-running task (task/pause)",
  "/stop               T-6-17 / Phase 6.3: kill the active long-running task (task/kill)",
  "/todos              T-6-17 / Phase 6.3: open the TodoBoard (live TODO list with j/k nav)",
  "/tokens             T-6-17 / Phase 6.3: show current token usage (input / output / total / cost)",
  "/consents           T-6-17 / Phase 6.3: open the GrantsManager (list + revoke + preset)",
  "/grants             T-6-17 / Phase 6.3: alias for /consents",
  "/model <name>       T-6-17 / Phase 6.3: switch the active model (model/set)",
  "/workflow <name>    T-6-17 / Phase 6.3: run a workflow by name (workflow/run) or open the picker",
  // R245.1 (O-10): daemon-side strategy bank read.
  "/bank-stats        R245.1: show daemon's bank snapshot (size, kinds, ok / notOk ratio)",
  "/bank-recall <k> [n]   R245.1: recall top-N (default 3) units for task kind <k>",
  // R245.2 (O-6): cross-process self-eval audit.
  "/memory-audit      R245.2: aggregate self-eval metrics across the daemon's bank (success rate, weakest/top kind, avg confidence)",
].join("\n");

/** the set of slash command names (without the leading "/" and
 *  without arguments). Used by the Tab-completion logic to find
 *  matches as the user types. We list each command's primary name
 *  and common aliases.
 *
 *  Adding a new slash command? Add it here AND in handleSlash. */
export const SLASH_COMMANDS: string[] = [
  "help", "?",
  "clear",
  "exit", "quit", "q",
  "tools",
  "state",
  "ping",
  "model",
  "mode",
  "sessions",
  "tasks",
  "projects",
  "cwd",
  "stats",
  "history",
  "lastplan",
  "theme", "theme-pick",
  "layout",
  "budget",
  "rewind",
  "snippet",
  "export",
  "tutorial",
  "bookmark",
  "metrics",
  "trace",
  "agents",
  "agent",
  "prompt",
  "phase",
  "budget",
  "sessions-list",
  "session",
  "session-new",
  "session-del",
  "skip",            // R99
  "skip-stats",      // R106
  "tool-actions",    // R100
  "memory",          // T-080
  "memory-edit",     // T-083
  "memory-compact",  // T-084
  "agent-pick",      // T-420
  "effort-pick",     // T-421
  "cwd-pick",        // T-423
  "mcp",             // T-428
  "mcp-login",       // T-428
  "mcp-reconnect",   // T-428
  "threads",         // T-429
  "update",          // T-430
  "update-deps",     // T-430
  "notifications",   // T-431
  // Phase 6.3 (T-6-17) — long-running + must-have commands.
  "continue",        // task/resume
  "pause",           // task/pause
  "stop",            // task/kill
  "todos",           // open the TodoBoard
  "tokens",          // show current token usage
  "consents",        // open the GrantsManager
  "grants",          // alias for /consents
  // `model` is already in the list (the pre-existing
  // /model <name> → setModel RPC). We re-route its handler
  // so the Phase 6.3 spec'd behaviour wins: it calls
  // `model/set` instead of the legacy `setModel`. The
  // legacy alias still works because the daemon maps both.
  "workflow",        // run a workflow by name (or open the picker)
  // R245.1 (O-10) — daemon bank read commands.
  "bank-stats",       // snapshot the bank (size + per-kind + ok / notOk)
  "bank-recall",      // recall top-N units for a task kind
  // R245.2 (O-6) — cross-process self-eval audit.
  "memory-audit",     // aggregate self-eval metrics from the daemon bank
];

/** T-442: structured slash-command catalog. Each entry is
 *  (name, description) so the InputBox can render a
 *  fuzzy-autocomplete dropdown with one-line hints.
 *  Mirrors the entries in `SLASH_COMMANDS` above (the
 *  flat string array is kept for the legacy
 *  `completeSlash` consumer). */
export const SLASH_COMMANDS_DETAILED: ReadonlyArray<{ name: string; description: string }> = [
  { name: "help",        description: "show the shortcut + command list" },
  { name: "?",           description: "alias for /help" },
  { name: "clear",       description: "clear the scrollback" },
  { name: "exit",        description: "exit the TUI" },
  { name: "quit",        description: "alias for /exit" },
  { name: "q",           description: "alias for /exit" },
  { name: "tools",       description: "list available tools" },
  { name: "state",       description: "show the reducer state (debug)" },
  { name: "ping",        description: "round-trip the daemon" },
  { name: "model",       description: "show or switch the active model" },
  { name: "mode",        description: "show or switch the permission mode" },
  { name: "sessions",    description: "list recent sessions" },
  { name: "tasks",       description: "list recent tasks" },
  { name: "projects",    description: "list known projects" },
  { name: "cwd",         description: "show or switch the working directory" },
  { name: "stats",       description: "show session stats" },
  { name: "history",     description: "show input history" },
  { name: "lastplan",    description: "show the most recent plan" },
  { name: "theme",       description: "show or set the active theme" },
  { name: "theme-pick",  description: "open the interactive theme picker" },
  { name: "layout",      description: "switch layout (full / focus / minimal)" },
  { name: "budget",      description: "set a phase's tool-call / cost cap" },
  { name: "rewind",      description: "rewind to a previous turn" },
  { name: "snippet",     description: "save / load a snippet" },
  { name: "export",      description: "export the session to markdown" },
  { name: "tutorial",    description: "open the tutorial" },
  { name: "bookmark",    description: "bookmark the current turn" },
  { name: "metrics",     description: "show session metrics" },
  { name: "trace",       description: "list recent traces" },
  { name: "agents",      description: "list Mavis agents with model column" },
  { name: "agent",       description: "show an agent's body + frontmatter" },
  { name: "prompt",      description: "show current system prompt sections" },
  { name: "phase",       description: "show / transition the current phase" },
  { name: "sessions-list", description: "list every session in the daemon" },
  { name: "session",     description: "switch the active session" },
  { name: "session-new", description: "create a new session and switch to it" },
  { name: "session-del", description: "delete a session (default is protected)" },
  { name: "skip",        description: "arm / clear skip-confirmation (N rounds)" },
  { name: "skip-stats",  description: "show skip-confirmation adoption" },
  { name: "tool-actions", description: "list recent tool actions" },
  { name: "memory",      description: "open the MemoryPanel (Global / Project / Session)" },
  { name: "memory-edit", description: "edit the focused fact in the MemoryPanel" },
  { name: "memory-compact", description: "force a project-memory compact" },
  { name: "agent-pick",  description: "open the agent picker" },
  { name: "effort-pick", description: "open the reasoning-effort picker" },
  { name: "cwd-pick",    description: "open the cwd switcher" },
  { name: "mcp",         description: "open the MCP server + tool viewer" },
  { name: "mcp-login",   description: "start the OAuth login flow for an MCP server" },
  { name: "mcp-reconnect", description: "force a reconnect of the MCP registry" },
  { name: "threads",     description: "open the thread selector" },
  { name: "update",      description: "show the update-available modal" },
  { name: "update-deps", description: "confirm a dependency refresh" },
  { name: "notifications", description: "open the notification center" },
  // Phase 6.3 (T-6-17) — long-running + must-have commands.
  { name: "continue",    description: "resume the active long-running task (task/resume)" },
  { name: "pause",       description: "pause the active long-running task (task/pause)" },
  { name: "stop",        description: "kill the active long-running task (task/kill)" },
  { name: "todos",       description: "open the TodoBoard (live TODO list with j/k nav)" },
  { name: "tokens",      description: "show current token usage (input / output / total / cost)" },
  { name: "consents",    description: "open the GrantsManager (list + revoke + preset)" },
  { name: "grants",      description: "alias for /consents" },
  { name: "workflow",    description: "run a workflow by name (workflow/run) or open the picker" },
  // R245.1 (O-10) — daemon bank read commands.
  { name: "bank-stats",  description: "show the daemon's strategy-bank snapshot" },
  { name: "bank-recall", description: "recall top-N units for a task kind (default N=3)" },
  // R245.2 (O-6) — cross-process self-eval audit.
  { name: "memory-audit", description: "aggregate self-eval metrics across the daemon bank" },
];

/** tab-completion result.
 *  - completed: the new input value to set (may equal `partial` if no match)
 *  - alternatives: other matches if completed is ambiguous (>=2 candidates)
 *  - oneShot: true if there is exactly one match (auto-completed cleanly)
 */
export interface Completion {
  completed: string;
  alternatives: string[];
  oneShot: boolean;
}

/** complete a slash command from a partial input. Returns
 *  the longest common prefix match and the set of all matching
 *  commands. The caller decides whether to:
 *    - set input to `completed` (when alternatives is empty), or
 *    - show `alternatives` to the user (when there's >1).
 *
 *  Only completes the *command* part — not arguments. So for
 *  "/mod<Tab>" we get { completed: "/mode", alternatives: ["model"] }
 *  (model and mode both start with "mod"). For "/mo<Tab>" we get
 *  { completed: "/mo", alternatives: ["model", "mode"] } (no
 *  unique prefix beyond "mo").
 */
export function completeSlash(partial: string): Completion {
  const trimmed = partial.trim();
  if (!trimmed.startsWith("/")) {
    return { completed: partial, alternatives: [], oneShot: false };
  }
  // Split into command + rest; only complete the command.
  const spaceAt = trimmed.indexOf(" ");
  const cmdPart = spaceAt < 0 ? trimmed.slice(1) : trimmed.slice(1, spaceAt);
  const rest = spaceAt < 0 ? "" : trimmed.slice(spaceAt);

  if (cmdPart.length === 0) {
    // User typed just "/" — show everything.
    return {
      completed: "/" + SLASH_COMMANDS[0],
      alternatives: SLASH_COMMANDS.slice(1),
      oneShot: false,
    };
  }

  const matches = SLASH_COMMANDS.filter((c) => c.startsWith(cmdPart));
  if (matches.length === 0) {
    return { completed: partial, alternatives: [], oneShot: false };
  }
  if (matches.length === 1) {
    return {
      completed: "/" + matches[0] + (rest ? rest : " "),
      alternatives: [],
      oneShot: true,
    };
  }
  // Multiple matches: find the longest common prefix.
  const lcp = longestCommonPrefix(matches);
  return {
    completed: "/" + lcp + rest,
    alternatives: matches,
    oneShot: false,
  };
}

function longestCommonPrefix(strs: string[]): string {
  if (strs.length === 0) return "";
  if (strs.length === 1) return strs[0];
  let prefix = strs[0];
  for (let i = 1; i < strs.length; i++) {
    // Truncate prefix until strs[i] actually starts with it.
    // NOTE: must use startsWith, not indexOf === 0 — indexOf
    // matches a substring anywhere, so "model".indexOf("mode") === 0
    // would falsely accept "mode" as a prefix of "model".
    while (!strs[i].startsWith(prefix)) {
      prefix = prefix.slice(0, -1);
      if (prefix.length === 0) return "";
    }
  }
  return prefix;
}

export function handleSlash(input: string): SlashResult | null {
  const trimmed = input.trim();
  if (!trimmed.startsWith("/")) return null;
  const [cmd, ...rest] = trimmed.slice(1).split(/\s+/);
  switch (cmd) {
    case "help":
    case "?":
      return { local: SLASH_HELP };
    case "clear":
      return { local: "__CLEAR__" };
    case "exit":
    case "quit":
    case "q":
      return { local: "__EXIT__" };
    case "tools":
      return { rpcMethod: "listTools" };
    case "tool-actions":
      // per-tool permission action assessment. Shows
      // each tool + its default action + a "safe" badge.
      return { rpcMethod: "listToolActions" };
    case "state":
      return { rpcMethod: "getState" };
    case "ping":
      return { rpcMethod: "ping" };
    case "model":
      // T-6-17: the new spec routes `/model <name>` to
      // `model/set` (the mid-session switch RPC, see
      // design.md §3.7). We keep the legacy `setModel`
      // method as a secondary alias so older daemons
      // still respond. The Phase 6.3 form wins when the
      // daemon supports it.
      if (rest.length === 0) return { local: "usage: /model <name>" };
      return { rpcMethod: "model/set", rpcParams: { name: String(rest[0]) } };
    case "mode":
      if (rest.length === 0) return {
        local: "usage: /mode <DEFAULT|ACCEPT_TASK|ACCEPT_EDITS|BYPASS_PERMISSIONS|PLAN|AUTO_READ_ONLY>",
      };
      return { rpcMethod: "setPermissionMode", rpcParams: { mode: rest[0] } };
    case "no-confirm":
    case "noconfirm":
    case "yes-do-it":
      // shortcut the user asked for — a single command that
      // skips every permission prompt. Equivalent to `/mode
      // BYPASS_PERMISSIONS` but discoverable in one word. We do the
      // RPC the same way `/mode` does so the daemon's setPermissionMode
      // handler is the single source of truth (and the user can flip
      // back with /mode DEFAULT later).
      return { rpcMethod: "setPermissionMode", rpcParams: { mode: "BYPASS_PERMISSIONS" } };
    case "skip":
      // per-round skip-confirmation. `/skip 5` arms the next
      // 5 tool calls to be auto-allowed. `/skip 0` (or `/skip
      // off`) clears the counter. The state is surfaced in the
      // status bar; live updates arrive via the `skip_confirmation`
      // notification.
      if (rest.length === 0) return { local: "usage: /skip <N|off>" };
      const n = rest[0] === "off" || rest[0] === "0" ? 0 : Number(rest[0]);
      if (!Number.isFinite(n) || n < 0) {
        return { local: "usage: /skip <N|off>   (N must be a non-negative integer)" };
      }
      return { rpcMethod: "setSkipConfirmation", rpcParams: { rounds: Math.floor(n) } };
    case "skip-stats":
      // read the adoption stats. The daemon reports
      // consumed / armed / prompts so the user can see how
      // often they're using the feature.
      return { rpcMethod: "getSkipStats" };
    case "sessions":
      return { rpcMethod: "listSessions" };
    case "stats":
      return { local: "session stats: see the status bar for token / cost counters; /state for the full snapshot" };
    case "history":
      return { local: "__HISTORY__" };
    case "tasks":
      return { rpcMethod: "listTasks" };
    case "projects":
      return { rpcMethod: "listProjects" };
    case "cwd":
      if (rest.length === 0) return { local: "usage: /cwd <path>" };
      return { rpcMethod: "switchProject", rpcParams: { cwd: rest[0] } };
    case "lastplan":
      return { local: "last plan: run a multi-step query to populate the plan view; use the IDEA plugin for a fuller view" };
    case "theme": {
      // switch color palette. We don't change the input
      // here — we surface a side note telling the user to use
      // the dedicated dispatch in tui.tsx. Actually, simpler:
      // we emit a special local token that tui.tsx catches.
      if (rest.length === 0) return { local: "usage: /theme <default|solarized|monokai> | /theme-pick" };
      const name = String(rest[0]).toLowerCase();
      if (name === "pick") {
        // T-422: open the aethercode-themes picker modal.
        return { local: "__THEMES_PICK__" };
      }
      if (name !== "default" && name !== "solarized" && name !== "monokai") {
        return { local: `unknown theme: ${name}. Try: default, solarized, monokai.` };
      }
      return { local: "__THEME__:" + name };
    }
    case "theme-pick":
      // T-422: open the aethercode-themes picker modal directly.
      return { local: "__THEMES_PICK__" };
    case "layout": {
      // switch layout preset.
      if (rest.length === 0) return { local: "usage: /layout <full|minimal|focus>" };
      const name = String(rest[0]).toLowerCase();
      if (name !== "full" && name !== "minimal" && name !== "focus") {
        return { local: `unknown layout: ${name}. Try: full, minimal, focus.` };
      }
      return { local: "__LAYOUT__:" + name };
    }
    case "budget": {
      // set the per-session cost budget (USD). 0 = off.
      if (rest.length === 0) return { local: "usage: /budget <usd|off>" };
      const raw = String(rest[0]).toLowerCase();
      if (raw === "off" || raw === "0" || raw === "none") {
        return { local: "__BUDGET__:0" };
      }
      const usd = Number(raw);
      if (!Number.isFinite(usd) || usd < 0) {
        return { local: "usage: /budget <usd>  (e.g. /budget 1.50, /budget off)" };
      }
      return { local: "__BUDGET__:" + usd };
    }
    case "rewind": {
      // rewind to a specific history index (1-based for UX).
      // Emitted as RPC; the engine handles the actual transcript
      // rollback.
      if (rest.length === 0) return { local: "usage: /rewind <history-index>" };
      const n = Number(rest[0]);
      if (!Number.isInteger(n) || n < 1) return { local: "usage: /rewind <history-index>  (>= 1)" };
      return { rpcMethod: "rewind", rpcParams: { target: n - 1 } };
    }
    case "snippet": {
      // snippet save / load / list / delete.
      // Usage: /snippet save NAME     → save current input
      //        /snippet load NAME     → load into input
      //        /snippet delete NAME   → remove
      //        /snippet list          → list names
      if (rest.length === 0) {
        return { local: "usage: /snippet <save|load|list|delete> [name]" };
      }
      const sub = String(rest[0]).toLowerCase();
      if (sub === "list") {
        return { local: "__SNIPPET_LIST__" };
      }
      if (sub === "save") {
        if (rest.length < 2) return { local: "usage: /snippet save <name>" };
        return { local: "__SNIPPET_SAVE__:" + String(rest[1]) };
      }
      if (sub === "load") {
        if (rest.length < 2) return { local: "usage: /snippet load <name>" };
        return { local: "__SNIPPET_LOAD__:" + String(rest[1]) };
      }
      if (sub === "delete") {
        if (rest.length < 2) return { local: "usage: /snippet delete <name>" };
        return { local: "__SNIPPET_DELETE__:" + String(rest[1]) };
      }
      return { local: `unknown snippet subcommand: ${sub}. Try: save, load, list, delete.` };
    }
    case "export": {
      // export the current scrollback to a file.
      // Usage: /export <path>      → markdown
      //        /export json <path> → JSON
      if (rest.length === 0) return { local: "usage: /export <path>  (or /export json <path>)" };
      const fmt = String(rest[0]).toLowerCase();
      if (fmt === "json") {
        if (rest.length < 2) return { local: "usage: /export json <path>" };
        return { local: "__EXPORT_JSON__:" + String(rest[1]) };
      }
      return { local: "__EXPORT_MD__:" + String(rest[0]) };
    }
    case "tutorial":
      // re-show the tutorial / welcome overlay.
      return { local: "__TUTORIAL__" };
    case "bookmark": {
      // bookmark a turn by id. /bookmark [id] — defaults to
      // the most recent turn.
      if (rest.length === 0) return { local: "__BOOKMARK_LAST__" };
      const n = Number(rest[0]);
      if (!Number.isInteger(n) || n < 1) return { local: "usage: /bookmark [id]" };
      return { local: "__BOOKMARK__:" + n };
    }
    case "metrics":
      // ask the daemon for the current engine metrics snapshot.
      return { rpcMethod: "getMetrics" };
    case "trace": {
      // ask the daemon for the recent trace spans. Optional
      // first arg is the limit (e.g. /trace 50). Default 10.
      // We pass the limit as an RPC param so the daemon can
      // honour the user's choice without a separate field.
      //
      // if the first arg looks like a traceId (starts with
      // "tr-"), call getTrace(traceId) instead. This is the
      // "show me the tree for THIS run" flow; the TUI renders
      // it with `├─` / `└─` connectors.
      if (rest.length > 0) {
        const first = String(rest[0]);
        if (first.startsWith("tr-")) {
          return { rpcMethod: "getTrace", rpcParams: { traceId: first } };
        }
        const n = Number(first);
        if (Number.isFinite(n) && n > 0) {
          return { rpcMethod: "getTraces", rpcParams: { limit: Math.min(256, Math.floor(n)) } };
        }
      }
      return { rpcMethod: "getTraces", rpcParams: { limit: 10 } };
    }
    case "agents": {
      // prior round-sync: list the Mavis agents on the
      // daemon with their model binding. The TUI
      // formatter in tui.tsx renders the response
      // as a "name | model" table so the user can
      // see at a glance which agents override the
      // engine's default model and which inherit.
      // Empty / missing model column shows
      // "(default)" — matches the desktop UI.
      return { rpcMethod: "listAgents" };
    }
    case "agent": {
      // prior round-sync: show an agent's body +
      // frontmatter (description / displayName /
      // model). One positional arg, the agent's
      // name (the kebab-case id from the
      // ~/.aethercode/agents/<name>/agent.md path).
      if (rest.length === 0) {
        return { local: "usage: /agent <name>  (kebab-case agent id, e.g. /agent code-reviewer)" };
      }
      const name = String(rest[0]);
      return { rpcMethod: "getAgentBody", rpcParams: { name } };
    }
    case "prompt": {
      // show the current system prompt as a
      // one-section-per-line table. The TUI formatter
      // in tui.tsx (else-if for getSystemPrompt)
      // pretty-prints the response, including the
      // first line of each section, the section's
      // source label (default / rules:project+global /
      // builder / etc.), and the byte count. Useful
      // for "what's the model actually seeing right
      // now" — especially after editing .aethercode/
      // rules/*.md and watching the live reload fire.
      //
      // drill-down. If the user passes a
      // section name (e.g. "/prompt rules"), call
      // getSystemPromptSection(name) and pretty-print
      // the full text of that one section. Otherwise
      // (no args) we keep the original table view.
      if (rest.length === 0) {
        return { rpcMethod: "getSystemPrompt" };
      }
      const name = rest.map(s => String(s)).join(" ").trim();
      return { rpcMethod: "getSystemPromptSection", rpcParams: { name } };
    }
    case "phase": {
      // per-phase tool budget. With no args we
      // fetch the current snapshot; with one arg we
      // transition to that phase (resets the per-phase
      // counters in the tracker).
      if (rest.length === 0) {
        return { rpcMethod: "getPhaseBudget" };
      }
      const name = String(rest[0]);
      return { rpcMethod: "setPhase", rpcParams: { name } };
    }
    case "budget": {
      // reconfigure a phase's cap. Usage:
      //   /budget <phase> <maxToolCalls> [maxCostUsd]
      //   /budget implement 80           // raise tool-call cap to 80 (USD stays 0.20)
      //   /budget verify 15 0.10         // raise both
      //   /budget explore 0              // unlimited tool calls (USD stays)
      if (rest.length < 2) {
        return { local: "usage: /budget <phase> <maxToolCalls> [maxCostUsd]" };
      }
      const phase = String(rest[0]);
      const maxCalls = Number(rest[1]);
      if (!Number.isInteger(maxCalls) || maxCalls < 0) {
        return { local: "maxToolCalls must be a non-negative integer (0 = unlimited)" };
      }
      const params: Record<string, unknown> = { phase, maxToolCalls: maxCalls };
      if (rest.length >= 3) {
        const maxCost = Number(rest[2]);
        if (!Number.isFinite(maxCost) || maxCost < 0) {
          return { local: "maxCostUsd must be a non-negative number (0 = unlimited)" };
        }
        params.maxCostUsd = maxCost;
      } else {
        // 0 = "keep current cap unchanged" — the
        // server treats an explicit 0 as "unlimited",
        // so we leave the field out when the user
        // didn't supply it.
        params.maxCostUsd = 0;
      }
      return { rpcMethod: "setPhaseBudget", rpcParams: params };
    }
    case "sessions-list": {
      // list every engine the daemon knows
      // about. Pretty-prints the wire payload in
      // tui.tsx.
      return { rpcMethod: "listEngines" };
    }
    case "session": {
      // switch the active engine. The engine
      // must already exist (the user typically types
      // /session-new first).
      if (rest.length === 0) {
        return { local: "usage: /session <id>  (id of an existing engine)" };
      }
      const id = rest.map(s => String(s)).join(" ").trim();
      return { rpcMethod: "setActiveEngine", rpcParams: { sessionId: id } };
    }
    case "session-new": {
      // create + switch. After this command the
      // newly-created engine is also the active one.
      if (rest.length === 0) {
        return { local: "usage: /session-new <id>" };
      }
      const newId = rest.map(s => String(s)).join(" ").trim();
      return { rpcMethod: "createEngine", rpcParams: { sessionId: newId } };
    }
    case "session-del": {
      // delete an engine. The default engine
      // is protected (the server returns removed=false
      // for it).
      if (rest.length === 0) {
        return { local: "usage: /session-del <id>" };
      }
      const delId = rest.map(s => String(s)).join(" ").trim();
      return { rpcMethod: "deleteEngine", rpcParams: { sessionId: delId } };
    }
    case "memory": {
      // T-080 / T-085: open the TUI MemoryPanel.
      // The dispatch is local because the panel
      // itself is a presentational component that
      // talks to the daemon via the standard
      // `memory/list` and `memory/compact` RPCs; the
      // App's useInput handler matches on the
      // `__MEMORY_PANEL__` token to toggle the panel.
      // Optional arg: which tab to open on
      // (global | project | session). Default is the
      // project's tab — that's the layer the user
      // most often wants to inspect mid-session.
      if (rest.length === 0) {
        return { local: "__MEMORY_PANEL__:project" };
      }
      const tab = String(rest[0]).toLowerCase();
      if (tab !== "global" && tab !== "project" && tab !== "session") {
        return { local: `unknown memory tab: ${tab}. Try: global, project, session.` };
      }
      return { local: `__MEMORY_PANEL__:${tab}` };
    }
    case "memory-edit": {
      // T-083: open the edit dialog for the focused
      // entry in the MemoryPanel. The dispatch is
      // local because the panel tracks the focused
      // row; the App translates this into the edit
      // dialog UI (T-083) and fires
      // `memory/appendSessionFact` /
      // `memory/appendProjectChange` on save.
      return { local: "__MEMORY_EDIT__" };
    }
    case "memory-compact": {
      // T-084: force a project-memory compact.
      // Forwards to the `memory/compact` RPC with
      // `force: true` so the trigger threshold is
      // ignored. Mirrors the `aethercode memory
      // compact` CLI (T-092).
      return { rpcMethod: "memory/compact", rpcParams: { force: true } };
    }
    case "agent-pick": {
      // T-420: open the agent picker modal. The
      // dispatch is local because the picker is a
      // presentational component that the App
      // mounts on demand; the App's useInput
      // handler matches on the `__AGENT_PICK__`
      // token to open the modal. The picker's
      // onSelect is wired to `agent/setActive` RPC
      // by the host (see tui.tsx's
      // `__AGENT_PICK__` handler).
      return { local: "__AGENT_PICK__" };
    }
    case "agent-pick-set-default": {
      // T-420: persist the highlighted agent as
      // the user's default. Optional arg: agent
      // name (defaults to the highlighted row in
      // the open picker; if no picker is open, the
      // arg is required).
      if (rest.length === 0) {
        return { local: "usage: /agent-pick-set-default <name>" };
      }
      const name = String(rest[0]);
      return { rpcMethod: "agent/setDefault", rpcParams: { name } };
    }
    case "effort-pick": {
      // T-421: open the reasoning-effort picker
      // modal. The host knows the catalog (it
      // depends on the active provider:model
      // pair) and feeds it to the picker. Enter
      // dispatches `effort/set` RPC.
      return { local: "__EFFORT_PICK__" };
    }
    case "effort": {
      // T-421: shortcut to set the effort
      // directly without opening the picker.
      // Usage: /effort <low|medium|high|xhigh>
      if (rest.length === 0) {
        return { local: "usage: /effort <low|medium|high|xhigh>  (or /effort-pick)" };
      }
      const effort = String(rest[0]).toLowerCase();
      return { rpcMethod: "effort/set", rpcParams: { effort } };
    }
    case "cwd-pick": {
      // T-423: open the cwd switcher modal. The
      // user types a path; the modal validates
      // and emits `cwd/set` on submit.
      return { local: "__CWD_PICK__" };
    }
    case "mcp": {
      // T-428: open the MCP server + tool viewer
      // modal. The host fetches the server list
      // via `mcp/listServers` and feeds it to the
      // McpViewer. Enter → `mcp/login` for the
      // highlighted server; Ctrl+R → reconnect.
      return { local: "__MCP_VIEWER__" };
    }
    case "mcp-login": {
      // T-428: start the OAuth login flow for
      // a single MCP server. The host opens the
      // McpLogin modal and dispatches `mcp/login`
      // on the OAuth callback URL.
      if (rest.length === 0) {
        return { local: "usage: /mcp-login <server-name>" };
      }
      const name = String(rest[0]);
      return { local: `__MCP_LOGIN__:${name}` };
    }
    case "mcp-reconnect": {
      // T-428: force a reconnect of the MCP
      // registry. Optionally takes a server
      // name (`/mcp-reconnect <name>`); when
      // omitted, reconnects all servers.
      const target = rest.length > 0 ? String(rest[0]) : null;
      return { local: target ? `__MCP_RECONNECT__:${target}` : "__MCP_RECONNECT__" };
    }
    case "threads": {
      // T-429: open the thread selector modal.
      // The host fetches the thread list
      // (typically `session/list`) and feeds it
      // to ThreadSelector. Enter selects a
      // thread; Esc cancels.
      return { local: "__THREAD_SELECTOR__" };
    }
    case "update": {
      // T-430: open the update-available modal
      // (or, if no update is pending, run a
      // `update/check` RPC and show a status
      // message). With no args we just open
      // the modal; with `install` we run the
      // install directly.
      const sub = rest.length > 0 ? String(rest[0]).toLowerCase() : null;
      if (sub === "install") {
        return { local: "__UPDATE_INSTALL__" };
      }
      return { local: "__UPDATE_AVAILABLE__" };
    }
    case "update-deps": {
      // T-430: confirm a dependency refresh.
      // The host shows the UpdateConfirm
      // modal; on confirm, dispatches
      // `update/refreshDeps`.
      return { local: "__UPDATE_DEPS__" };
    }
    case "notifications": {
      // T-431: open the notification center.
      // The host mounts the NotificationCenter
      // modal; the user navigates pending
      // notifications + warning toggles. Esc
      // closes.
      return { local: "__NOTIFICATION_CENTER__" };
    }
    // ---------------------------------------------------------------
    //  Phase 6.3 (T-6-17) — long-running + must-have commands.
    //
    //  These 9 commands are the TUI's interface to the long-running
    //  task machinery (Phase 1) and the must-have capability set
    //  (Phases 5-6). The host (tui.tsx) translates the local
    //  tokens to RPC calls; the rpc/* helpers own the actual
    //  wiring so the dispatcher stays presentational.
    // ---------------------------------------------------------------
    case "continue":
    case "resume": {
      // T-6-17: resume the active long-running task.
      // With no args, the host resumes the currently-focused
      // session. With one arg, the host uses it as the task id
      // (so the user can re-attach to a specific running task).
      if (rest.length === 0) {
        return { rpcMethod: "task/resume" };
      }
      return { rpcMethod: "task/resume", rpcParams: { id: String(rest[0]) } };
    }
    case "pause": {
      // T-6-17: pause the active long-running task. The daemon
      // sets state to "paused" at the next safe checkpoint
      // (so the agent doesn't lose state mid-tool-call).
      if (rest.length === 0) {
        return { rpcMethod: "task/pause" };
      }
      return { rpcMethod: "task/pause", rpcParams: { id: String(rest[0]) } };
    }
    case "stop":
    case "kill": {
      // T-6-17: kill the active long-running task. Graceful
      // shutdown — the daemon persists the final state before
      // exiting. The state goes to "cancelled".
      if (rest.length === 0) {
        return { rpcMethod: "task/kill" };
      }
      return { rpcMethod: "task/kill", rpcParams: { id: String(rest[0]) } };
    }
    case "todos":
    case "todo": {
      // T-6-17: open the TodoBoard. The host mounts the
      // TodoBoard modal; the user navigates with j/k (the
      // board itself owns the keyboard handler).
      return { local: "__TODO_BOARD__" };
    }
    case "tokens":
    case "token": {
      // T-6-17: show current token usage. The host fires
      // `session/tokens` against the active session and
      // pretty-prints the response (input / output / total
      // / cost). The StatusBar already shows live counters
      // — this is a one-shot snapshot.
      return { rpcMethod: "session/tokens" };
    }
    case "consents":
    case "grants": {
      // T-6-17: open the GrantsManager. The host mounts
      // the GrantsManager modal which itself calls
      // `permission/list` (via the host-provided prop) and
      // dispatches `permission/revoke` + `permission/setPreset`
      // on user action. `/grants` is an alias.
      return { local: "__GRANTS_MANAGER__" };
    }
    case "workflow": {
      // T-6-17: with no args, open the WorkflowPicker. With
      // one arg, run the named workflow via `workflow/run`.
      // Inputs are intentionally not exposed on the CLI —
      // the picker is the UI surface for input prompts.
      if (rest.length === 0) {
        return { local: "__WORKFLOW_PICKER__" };
      }
      return {
        rpcMethod: "workflow/run",
        rpcParams: { name: String(rest[0]) },
      };
    }
    case "bank-stats": {
      // R245.1: read the daemon's strategy bank and render a
      // one-line snapshot (size + per-kind + ok / notOk). The
      // host (tui.tsx) lazily imports bank-recall.ts, calls
      // readBankStats(), and dispatches a sideNote with the
      // summary text. We encode the request as a local token
      // instead of an RPC method because the bank lives in
      // a separate process (the daemon's BankServer) and we
      // want to read it directly from the TUI, not via the
      // JSON-RPC bridge.
      return { local: "__BANK_STATS__" };
    }
    case "bank-recall": {
      // R245.1: recall top-N (default 3) units for a given
      // task kind. Same lazy-import pattern as /bank-stats.
      // The host dispatches a sideNote with the formatted
      // recall line (e.g. "bank[file_edit]: 3 units (top: ...)")
      if (rest.length === 0) {
        return { local: "usage: /bank-recall <kind> [n]  (n defaults to 3)" };
      }
      const kind = String(rest[0]);
      const n = rest.length > 1 ? Math.max(1, Number(rest[1]) || 3) : 3;
      return { local: `__BANK_RECALL__:${kind}:${n}` };
    }
    case "memory-audit": {
      // R245.2: cross-process self-eval audit. Aggregates
      // okCount/notOkCount across the daemon's bank and
      // reports a 1-screen summary (overall success rate,
      // avg confidence, weakest + top kind). Lazy-import
      // pattern matches /bank-stats so the import cost is
      // only paid when the user asks.
      return { local: "__MEMORY_AUDIT__" };
    }
    default:
      return { local: `unknown slash command: /${cmd}. Try /help.` };
  }
}
