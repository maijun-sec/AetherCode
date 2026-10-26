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

// R369: the capability filter needs the table that says which methods
// this daemon cannot serve. Imported here so `handleSlash` can refuse
// honestly instead of returning -32601; the R366 gate imports the same
// table, so the UI and the gate cannot disagree about it.
import { EFFORT_METHOD, unsupportedReason, unsupportedMessage } from "./rpcCapabilities.js";

// R385: the ONE permission-mode catalog. `/mode <name>` normalises
// its argument through it (the daemon's `PermissionMode.valueOf` is
// case-sensitive and dash-blind), and `/no-confirm` reads the bypass
// mode's name from it — so the mode vocabulary is written once.
import { MODE, normalisePermissionMode } from "./permissionModes.js";

// R380: the command surface is ONE list.
//
// Before this round there were THREE lists of the same command set:
//   1. SLASH_COMMANDS          — a flat array of 77 strings
//   2. SLASH_COMMANDS_DETAILED — the catalog (name + description + args)
//   3. SLASH_COMMAND_GROUPS    — group -> ordered names, for /help
//
// They had already drifted, and the comments above them were wrong:
// "source of truth for autocomplete is SLASH_COMMANDS_DETAILED" (it was
// SLASH_COMMANDS), and "mirrors the entries in SLASH_COMMANDS" (it did
// not: `/demo` was missing from the flat array, so it was neither
// Tab-completable nor in the command palette, and `budget` appeared
// twice).
//
// Now the catalog is the only list. Each entry carries its `group`, and
// an alias carries the command it stands for (`aliasOf`). The flat
// array and the /help grouping are DERIVED from it, so the three
// surfaces (Tab completion, the InputBox dropdown, the command
// palette) cannot disagree, and a command cannot be in the catalog and
// missing from /help.
//
// To add a command: add ONE entry to SLASH_COMMANDS_DETAILED (with a
// group) and handle it in `routeSlash` below. Nothing else to update.

/**
 * The command catalog — the ONE list of slash commands.
 *
 * Each entry is (name, description, args?, group, aliasOf?) so the
 * InputBox can render a fuzzy-autocomplete dropdown with one-line
 * hints, `/help` can group the rows, and the flat name list can be
 * derived.
 */
export const SLASH_COMMANDS_DETAILED: ReadonlyArray<{
  name: string;
  description: string;
  args?: string;
  /** The /help group. R380: the group lives ON THE ENTRY, so a command
   *  cannot be in the catalog and missing from /help. */
  group: string;
  /** Set on an alias: the command it stands for. An alias gets no /help
   *  row of its own — the primary mentions it, and that mention is
   *  DERIVED from this field. */
  aliasOf?: string;
}> = [
  { name: "help", group: "session", description: "show the shortcut + command list" },

  { name: "?", group: "session", aliasOf: "help", description: "alias for /help" },

  { name: "clear", group: "session", description: "clear the scrollback" },

  { name: "exit", group: "session", description: "exit the TUI" },

  { name: "quit", group: "session", aliasOf: "exit", description: "alias for /exit" },

  { name: "q", group: "session", aliasOf: "exit", description: "alias for /exit" },

  { name: "history", group: "session", description: "show input history" },

  { name: "session", group: "session", args: "<id>", description: "switch the active session" },

  {
    name: "session-new",
    group: "session",
    args: "<id>",
    description: "create a new session and switch to it",
  },

  {
    name: "session-del",
    group: "session",
    args: "<id>",
    description: "delete a session (default is protected)",
  },

  { name: "sessions-list", group: "session", description: "list every session in the daemon" },

  {
    name: "sessions",
    group: "session",
    description: "this project's sessions — pick one to continue",
  },

  { name: "threads", group: "session", description: "switch to another session thread" },

  { name: "lastplan", group: "session", description: "show the most recent plan" },

  { name: "tutorial", group: "session", description: "re-show the welcome overlay" },

  { name: "rewind", group: "session", description: "rewind to a previous turn" },

  { name: "bookmark", group: "session", args: "[N]", description: "bookmark the current turn" },

  {
    name: "snippet",
    group: "session",
    args: "<save|load|list|delete> <name>",
    description: "save / load a snippet",
  },

  { name: "export", group: "session", description: "export the session to markdown or json" },

  // R344: /demo walks the user through a canned conversation
  // that exercises every visual surface (welcome banner, tool
  // cards, permission modal, status changes, error path). It's
  // the fastest way to evaluate the TUI without needing a real
  // model round-trip.

  { name: "demo", group: "session", description: "walk a canned conversation (no LLM round-trip)" },

  {
    name: "memory",
    group: "context",
    description: "open the memory panel (global / project / session)",
  },

  {
    name: "memory-edit",
    group: "context",
    description: "edit the focused fact in the memory panel",
  },

  { name: "memory-compact", group: "context", description: "force a project-memory compact" },

  // R245.2 (O-6) — cross-process self-eval audit.

  {
    name: "memory-audit",
    group: "context",
    description: "aggregate self-eval metrics from the daemon bank",
  },

  // R245.1 (O-10) — daemon bank read commands.

  { name: "bank-stats", group: "context", description: "snapshot the daemon strategy bank" },

  {
    name: "bank-recall",
    group: "context",
    args: "<kind> [n]",
    description: "recall top-N units for a task kind (default N=3)",
  },

  { name: "todos", group: "context", description: "open the live TODO board (j/k nav)" },
  { name: "todo", group: "context", aliasOf: "todos", description: "alias for /todos" },

  {
    name: "context",
    group: "context",
    description: "what is filling the context window, itemised",
  },
  { name: "ctx", group: "context", aliasOf: "context", description: "alias for /context" },

  {
    name: "tokens",
    group: "context",
    description: "show current token usage (input / output / total / cost)",
  },
  { name: "token", group: "context", aliasOf: "tokens", description: "alias for /tokens" },

  { name: "metrics", group: "context", description: "show session metrics" },

  { name: "trace", group: "context", description: "list recent traces" },

  {
    name: "model",
    group: "config",
    args: "<name>",
    description: "show or switch the active model",
  },

  { name: "mode", group: "config", description: "show or switch the permission mode" },
  { name: "no-confirm", group: "config", aliasOf: "mode", description: "alias for /mode" },
  { name: "noconfirm", group: "config", aliasOf: "mode", description: "alias for /mode" },
  { name: "yes-do-it", group: "config", aliasOf: "mode", description: "alias for /mode" },

  { name: "tool-actions", group: "config", description: "list recent tool actions" },

  { name: "theme", group: "config", args: "<name>", description: "show or set the active theme" },

  { name: "theme-pick", group: "config", description: "open the interactive theme picker" },

  {
    name: "layout",
    group: "config",
    args: "<full|focus|minimal>",
    description: "switch layout (full / focus / minimal)",
  },

  {
    name: "budget",
    group: "config",
    args: "<usd> [calls]",
    description: "set a phase's tool-call / cost cap",
  },

  { name: "agent-pick", group: "config", description: "open the agent picker (type to filter)" },
  {
    name: "agent-pick-set-default",
    group: "config",
    args: "[name]",
    description: "set the highlighted agent as your default",
  },

  {
    name: "effort-pick",
    group: "config",
    description: "set reasoning effort (low / medium / high / xhigh)",
  },
  {
    name: "effort",
    group: "config",
    args: "<level>",
    description: "set the effort directly (low / medium / high / xhigh)",
  },

  { name: "cwd-pick", group: "config", description: "switch the daemon working directory" },

  { name: "agents", group: "config", description: "list Mavis agents with model column" },

  {
    name: "agent",
    group: "config",
    args: "<name>",
    description: "show an agent's body + frontmatter",
  },

  {
    name: "prompt",
    group: "config",
    args: "[section]",
    description: "show current system prompt sections",
  },

  {
    name: "phase",
    group: "config",
    args: "<name>",
    description: "show / transition the current phase",
  },

  {
    name: "skip",
    group: "config",
    args: "<N|off>",
    description: "arm / clear skip-confirmation (N rounds)",
  },

  { name: "skip-stats", group: "config", description: "show skip-confirmation adoption" },

  { name: "mcp", group: "mcp & integrations", description: "open the MCP server + tool viewer" },

  {
    name: "mcp-login",
    group: "mcp & integrations",
    args: "<name>",
    description: "start the OAuth login flow for an MCP server",
  },

  {
    name: "mcp-reconnect",
    group: "mcp & integrations",
    description: "force a reconnect of the MCP registry",
  },

  { name: "consents", group: "mcp & integrations", description: "open the grants manager" },

  {
    name: "grants",
    group: "mcp & integrations",
    aliasOf: "consents",
    description: "alias for /consents",
  },

  {
    name: "notifications",
    group: "mcp & integrations",
    description: "open the notification center",
  },

  {
    name: "workflow",
    group: "workflows & sdd",
    description: "run a workflow by name, or open the picker",
  },

  // Phase 6.3 (T-6-17) — long-running + must-have commands.
  // R375: these three used to name methods the daemon does not have
  // ("task/resume", "task/pause", "task/kill"), which made the help
  // text a promise the TUI could not keep. The descriptions now say
  // what the command DOES, and the method names live in the code.

  {
    name: "continue",
    group: "workflows & sdd",
    description: "resume this session's automatic continuation",
  },
  {
    name: "resume",
    group: "workflows & sdd",
    aliasOf: "continue",
    description: "alias for /continue",
  },

  {
    name: "pause",
    group: "workflows & sdd",
    description: "pause this session's automatic continuation",
  },

  { name: "stop", group: "workflows & sdd", description: "interrupt the run that is in flight" },
  { name: "kill", group: "workflows & sdd", aliasOf: "stop", description: "alias for /stop" },

  // Spec-driven development — 8-phase run with phase artefacts.

  {
    name: "sdd",
    group: "workflows & sdd",
    args: "<intent>",
    description: "start an 8-phase spec-driven run with an intent",
  },

  { name: "sdd-status", group: "workflows & sdd", description: "show the current SDD run state" },

  {
    name: "sdd-list",
    group: "workflows & sdd",
    description: "list every SDD run under the current cwd",
  },

  {
    name: "sdd-approve",
    group: "workflows & sdd",
    description: "approve the current phase and advance",
  },

  {
    name: "sdd-modify",
    group: "workflows & sdd",
    args: "<feedback>",
    description: "re-run the current phase with your feedback",
  },

  {
    name: "sdd-skip",
    group: "workflows & sdd",
    description: "skip the current phase (required phases refuse)",
  },

  { name: "sdd-abort", group: "workflows & sdd", description: "abort the current SDD run" },

  // SDD modify-flow helpers (snapshot + diff the phase artefact).

  {
    name: "sdd-view",
    group: "workflows & sdd",
    args: "[phase]",
    description: "show the phase artefact and its path",
  },

  {
    name: "sdd-snapshot",
    group: "workflows & sdd",
    args: "[phase]",
    description: "cache the current artefact for a later diff",
  },

  {
    name: "sdd-diff",
    group: "workflows & sdd",
    args: "[phase]",
    description: "diff the cached snapshot against the file on disk",
  },

  {
    name: "sdd-clear-cache",
    group: "workflows & sdd",
    description: "drop every cached per-phase snapshot",
  },

  { name: "tools", group: "diagnostics", description: "list available tools" },

  { name: "state", group: "diagnostics", description: "show the reducer state (debug)" },

  { name: "stats", group: "diagnostics", description: "show session stats" },

  { name: "ping", group: "diagnostics", description: "round-trip the daemon" },

  { name: "tasks", group: "diagnostics", description: "list recent tasks" },

  { name: "projects", group: "diagnostics", description: "list known projects" },

  {
    name: "cwd",
    group: "diagnostics",
    args: "<path>",
    description: "show or switch the working directory",
  },

  // R374: these two descriptions were T-430's originals — "review an
  // available update" and "confirm a dependency refresh" — and both
  // were LIES. There is no available update to review (the daemon has
  // no update RPC) and nothing to confirm (the daemon has no
  // dependency-refresh RPC). The verbs now describe what the panel
  // actually does: it REPORTS the versions and the manual path.
  //
  // The optional `[install]` is the third verb. It existed since T-430
  // and was reachable, but invisible — the catalog advertised one
  // shape for a command that has two.

  {
    name: "update",
    group: "diagnostics",
    args: "[install]",
    description: "check for a newer release (add 'install' to see how to apply one)",
  },

  { name: "update-deps", group: "diagnostics", description: "check how to refresh dependencies" },
];

/**
 * The command NAMES, DERIVED from the catalog above.
 *
 * R380: this used to be a hand-maintained array of 77 strings — a
 * SECOND copy of the command list, and it had already drifted: `/demo`
 * was missing from it (so `/demo` was neither Tab-completable nor in the
 * command palette), and `budget` appeared twice. The catalog is the only
 * list now; this is its projection, so the two cannot disagree.
 *
 * Declared AFTER the catalog on purpose: the catalog is read at
 * module-eval time, and an earlier declaration would be a TDZ crash
 * that `tsc` does NOT catch.
 */
export const SLASH_COMMANDS: string[] = SLASH_COMMANDS_DETAILED.map((c) => c.name);

/**
 * Which command each alias stands for.
 *
 * R380: the catalog says it, so the tab completion can use it — an
 * alias must not shadow the command it stands for.
 */
const ALIAS_PRIMARY = new Map<string, string>();
for (const c of SLASH_COMMANDS_DETAILED) {
  if (c.aliasOf) ALIAS_PRIMARY.set(c.name, c.aliasOf);
}

/**
 * Render the `/help` blurb.
 *
 * R362: this used to be a hand-maintained 88-line string literal that
 * drifted away from {@link SLASH_COMMANDS} (76) — the two disagreed, and
 * `/help` advertised commands the autocomplete could not reach while
 * carrying development notes like "T-080:" and "对应历史 round:" into the
 * user-facing text.
 *
 * R380: the grouping came from a SECOND list (`SLASH_COMMAND_GROUPS`, a
 * table of group -> ordered names). A command in the catalog but in no
 * group simply did not appear in `/help`, and the comment here claimed
 * a test made that "an explicit decision" — there was no such test, and
 * the four commands it covered happened to be aliases whose primaries
 * mentioned them. The group is now a field ON THE ENTRY, so the
 * grouping cannot drift and the claim is true.
 *
 * The alias mention is derived too: the catalog says which command an
 * alias stands for, and the help text follows.
 */
function renderSlashHelp(): string {
  const out: string[] = [];
  out.push("TUI command reference — grouped below. Tab completes; the autocomplete");
  out.push("dropdown shows the same one-line hints. <arg> is required, [arg] optional.");

  // The alias mention is DERIVED. The catalog says which command an
  // alias stands for (the aliasOf field); the help text follows from
  // that. A hand-written "(aliases: /quit, /q)" inside the description
  // would be a second copy of the same fact — and the R377 lesson
  // applies to prose too: a second copy of a truth drifts silently.
  const aliases = new Map<string, string[]>();
  for (const c of SLASH_COMMANDS_DETAILED) {
    if (!c.aliasOf) continue;
    const list = aliases.get(c.aliasOf) ?? [];
    list.push(c.name);
    aliases.set(c.aliasOf, list);
  }
  const describe = (c: (typeof SLASH_COMMANDS_DETAILED)[number]): string => {
    const list = aliases.get(c.name);
    if (!list || list.length === 0) return c.description;
    const label = list.length === 1 ? "alias" : "aliases";
    return `${c.description} (${label}: ${list.map((a) => "/" + a).join(", ")})`;
  };

  // Longest "/name <arg>" label across the whole catalog, so every group
  // aligns its description column on the same gutter.
  let gutter = 0;
  for (const c of SLASH_COMMANDS_DETAILED) {
    const label = `/${c.name}${c.args ? " " + c.args : ""}`;
    if (label.length > gutter) gutter = label.length;
  }
  gutter += 2;

  // The groups are the CATALOG'S groups, in the catalog's order. R380:
  // there is no second group table any more, so a command cannot be in
  // the catalog and missing from /help — the group is a field on the
  // entry itself.
  let current = "";
  for (const c of SLASH_COMMANDS_DETAILED) {
    // An alias has no row of its own: the primary mentions it.
    if (c.aliasOf) continue;
    if (c.group !== current) {
      current = c.group;
      out.push("");
      out.push(`# ${current}`);
    }
    const label = `/${c.name}${c.args ? " " + c.args : ""}`;
    out.push(`${label.padEnd(gutter)}${describe(c)}`.trimEnd());
  }
  return out.join("\n");
}

export const SLASH_HELP = renderSlashHelp();

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

  // R380: an alias must not SHADOW the command it stands for.
  //
  // `/token` is an alias of `/tokens`, so the prefix "/tok" used to
  // complete to "/tokens " in one keystroke. With both in the list it
  // became two candidates, and the user had to type one more letter
  // for a distinction nobody cares about. The alias is dropped when
  // the primary it stands for also matches — the user who typed
  // "/tok" wanted the real command.
  //
  // An alias whose primary does NOT match stays: "/q" still offers
  // both "/q" and "/quit", because neither is the other.
  const matches = SLASH_COMMANDS.filter((c) => c.startsWith(cmdPart)).filter((c) => {
    const primary = ALIAS_PRIMARY.get(c);
    if (!primary || primary === c) return true;
    return !primary.startsWith(cmdPart);
  });
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

/**
 * R700d — parse an optional `[phase]` arg (1-8). Returns
 * undefined when the user did not pass a phase so the daemon
 * can fall back to the run's currentPhase. Whitespace tokens
 * and out-of-range values return null.
 */
function parsePhase(rest: string[]): number | null | undefined {
  const tok = rest.find((t) => /^\d+$/.test(t));
  if (!tok) return undefined;
  const n = Number.parseInt(tok, 10);
  if (!Number.isFinite(n) || n < 1 || n > 8) return null;
  return n;
}

/**
 * Route a slash command to its result, WITHOUT the capability filter.
 *
 * <p>Not the UI's entry point — `handleSlash` is. Exported because the
 * refusal in R369 drops `rpcParams`, which was the only thing pinning
 * the `? id` argument shape of `/stop <id>` and friends. Losing that
 * check silently is worse than exporting one more function, and
 * `r369-honest-commands.test.mjs` pins that this stays an inner
 * function with a filter in front of it rather than being merged back.
 */
export function routeSlash(input: string): SlashResult | null {
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
      // R366: this used to send `model/set`, on the strength of a
      // T-6-17 comment saying "the daemon maps both" (and keeping
      // `setModel` as a "legacy alias"). **Neither half was true.**
      //
      //   * `model/set` is not registered. The DTOs exist
      //     (`protocol/rpc/model/ModelSetParams` + `ModelSetResult`,
      //     Phase 1.2 / T-1-19) but no handler was ever wired, so
      //     every `/model <name>` died on -32601.
      //   * `setModel` is the real handler
      //     (`AetherCodeMethods.registerAll`), and it is not an
      //     alias — it is the only one.
      //
      // The parameter key changes too: `setModel` reads `model`
      // (`stringOrThrow(p, "model")`), the dead `model/*` DTOs used
      // `name`. A name-only fix would still have thrown.
      if (rest.length === 0) return { local: "usage: /model <name>" };
      return { rpcMethod: "setModel", rpcParams: { model: String(rest[0]) } };
    case "mode":
      // R352: bare `/mode` triggers the in-process picker so the
      // user can arrow-key through modes instead of typing the
      // full enum. With an arg we keep the direct-RPC path for
      // scripts / pipelines.
      //
      // R385: the argument is NORMALISED before it goes on the wire.
      // The daemon resolves it with `PermissionMode.valueOf(...)`,
      // which is case-sensitive and knows nothing about dashes — so
      // `/mode default` used to die with "unknown permission mode",
      // and so did `/mode ask-before-tool`, the spelling the daemon's
      // OWN javadoc documents as the TUI's.
      if (rest.length === 0) return { local: "__PICK_MODE__" };
      return {
        rpcMethod: "setPermissionMode",
        rpcParams: { mode: normalisePermissionMode(String(rest[0])) },
      };
    case "no-confirm":
    case "noconfirm":
    case "yes-do-it":
      // shortcut the user asked for — a single command that
      // skips every permission prompt. Equivalent to `/mode
      // BYPASS_PERMISSIONS` but discoverable in one word. We do the
      // RPC the same way `/mode` does so the daemon's setPermissionMode
      // handler is the single source of truth (and the user can flip
      // back with /mode DEFAULT later).
      //
      // R385: the mode name comes from the shared catalog, so the
      // string is written in ONE place.
      return {
        rpcMethod: "setPermissionMode",
        rpcParams: { mode: MODE.BYPASS_PERMISSIONS },
      };
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
      // R370: was `{ rpcMethod: "listSessions" }`, which printed the
      // RAW WIRE PAYLOAD (id / lastUsedAt / sizeBytes / trashedAt /
      // messageCount / preview) with no way to pick a session. The
      // user asked for "list this cwd's sessions and continue one".
      //
      // The sentinel opens the SAME picker as `/threads`; the only
      // difference is `State.panelCwdOnly`, which the host sets
      // because this intent id is `session-picker`. Keep them as two
      // intents rather than one command with an argument: `/sessions`
      // is the one a user reaches for without knowing that "threads"
      // means "sessions", and it must be discoverable in `/help`
      // under its own name.
      return { local: "__SESSION_PICKER__" };
    case "stats":
      return {
        local:
          "session stats: see the status bar for token / cost counters; /state for the full snapshot",
      };
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
      return {
        local:
          "last plan: run a multi-step query to populate the plan view; use the IDEA plugin for a fuller view",
      };
    case "theme": {
      // switch color palette. We don't change the input
      // here — we surface a side note telling the user to use
      // the dedicated dispatch in tui.tsx. Actually, simpler:
      // we emit a special local token that tui.tsx catches.
      if (rest.length === 0)
        return { local: "usage: /theme <default|solarized|monokai> | /theme-pick" };
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
      // R347: export the current scrollback. The user can pass
      // a path or rely on the default
      // `<cwd>/.aethercode/exports/session-<id>-<timestamp>.md`.
      // Usage:
      //   /export                  → markdown to default path
      //   /export <path>           → markdown to <path>
      //   /export json             → JSON to default path
      //   /export json <path>      → JSON to <path>
      // We keep the `__EXPORT_*__:<path>` sentinel so the
      // existing dynamic-import handler in tui.tsx still works
      // — empty path means "let the handler compute a default".
      if (rest.length === 0) return { local: "__EXPORT_MD__:" };
      const fmt = String(rest[0]).toLowerCase();
      if (fmt === "json") {
        if (rest.length < 2) return { local: "__EXPORT_JSON__:" };
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
      const name = rest
        .map((s) => String(s))
        .join(" ")
        .trim();
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
      const id = rest
        .map((s) => String(s))
        .join(" ")
        .trim();
      return { rpcMethod: "setActiveEngine", rpcParams: { sessionId: id } };
    }
    case "session-new": {
      // create + switch. After this command the
      // newly-created engine is also the active one.
      if (rest.length === 0) {
        return { local: "usage: /session-new <id>" };
      }
      const newId = rest
        .map((s) => String(s))
        .join(" ")
        .trim();
      return { rpcMethod: "createEngine", rpcParams: { sessionId: newId } };
    }
    case "session-del": {
      // delete an engine. The default engine
      // is protected (the server returns removed=false
      // for it).
      if (rest.length === 0) {
        return { local: "usage: /session-del <id>" };
      }
      const delId = rest
        .map((s) => String(s))
        .join(" ")
        .trim();
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
      // dispatches the daemon's `switchVariant`.
      return { local: "__EFFORT_PICK__" };
    }
    case "effort": {
      // T-421: shortcut to set the effort
      // directly without opening the picker.
      // Usage: /effort <low|medium|high|xhigh>
      //
      // R377: the daemon calls this a VARIANT. `switchVariant` takes
      // `{ variant: "<name>" }` and answers with the RESOLVED preset
      // (name + temperature / maxTokens / reasoningBudget), so the
      // caller reports what the daemon actually applied rather than
      // what it asked for.
      if (rest.length === 0) {
        return { local: "usage: /effort <low|medium|high|xhigh>  (or /effort-pick)" };
      }
      const effort = String(rest[0]).toLowerCase();
      return { rpcMethod: EFFORT_METHOD, rpcParams: { variant: effort } };
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
    case "demo": {
      // R344: canned conversation that exercises every
      // visual surface. The TUI host renders a
      // scripted sequence of reducer dispatches
      // (welcome → user prompt → assistant reply →
      // tool call → permission modal → deny →
      // tool call → status bar changes). No RPC,
      // no model round-trip — purely client-side
      // so the user can evaluate the visuals
      // without burning tokens.
      return { local: "__DEMO__" };
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
      // R375: this used to ask for `task/resume`, which the daemon
      // this TUI spawns does NOT register. `task/resume` belongs to
      // `TaskMethods`, an adapter that proxies to a SEPARATE
      // supervisor process that production code never constructs — and
      // it carries no `task/pause` at all.
      //
      // The real "let it keep going" is the session's auto-continue
      // flag, which the daemon DOES expose.
      return { rpcMethod: "setContinuationStopped", rpcParams: { stopped: false } };
    }
    case "pause": {
      // R375: same story as `continue`, inverted. The daemon's own
      // wording for this is "Stop auto-continue" — it stops the
      // session's automatic continuation and cancels the pending
      // countdown immediately.
      return { rpcMethod: "setContinuationStopped", rpcParams: { stopped: true } };
    }
    case "stop":
    case "kill": {
      // R375: the real "stop" is `cancel {runId}` — the same call Esc
      // makes (R363). The run id is not known at this layer (the
      // command table has no state), so the HOST fills it in from the
      // active run. `task/kill` was a task id on a supervisor that
      // does not exist, and it was being handed a SESSION id.
      if (rest.length === 0) {
        return { rpcMethod: "cancel" };
      }
      return { rpcMethod: "cancel", rpcParams: { runId: String(rest[0]) } };
    }
    case "todos":
    case "todo": {
      // T-6-17: open the TodoBoard. The host mounts the
      // TodoBoard modal; the user navigates with j/k (the
      // board itself owns the keyboard handler).
      return { local: "__TODO_BOARD__" };
    }
    case "context":
    case "ctx": {
      // R371: this command did not exist. `panelIntents.ts` had a
      // `context-meter` row marked `no-command` + `advertised:false`
      // precisely because `handleSlash` fell through to
      // "unknown slash command: /context" — and 251 lines of
      // ContextMeter sat behind a placeholder. Now the command exists,
      // so the row is a real surface (see panelIntents.ts).
      return { local: "__CONTEXT_METER__" };
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
      // R366: `workflow/run` is registered on **SupervisorRpcServer**
      // (aethercode-tasks), which is only started by the standalone
      // task CLI. The daemon the TUI spawns is DaemonRunner ->
      // AetherCodeMethods, which registers the FLAT name `runWorkflow`.
      // So `/workflow <name>` was a -32601.
      //
      // Param shapes happen to match ({name}), so the rename alone is
      // the whole fix here. (It does NOT for `setModel` — see the
      // `/model` case.)
      //
      // Inputs are intentionally not exposed on the CLI; the picker is
      // the UI surface for input prompts.
      if (rest.length === 0) {
        return { local: "__WORKFLOW_PICKER__" };
      }
      return {
        rpcMethod: "runWorkflow",
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
    // R700 — SDD product integration. The TUI dispatches
    // sdd.* RPCs and the daemon's SddOrchestrator owns the
    // state machine + LLM call + artefact write. The
    // `__SDD_*__` local tokens are intercepted by tui.tsx
    // to dispatch the RPC + update store.sddRun so
    // SddMode.tsx re-renders.
    case "sdd": {
      if (rest.length === 0) {
        return { local: "usage: /sdd <intent>   (e.g. /sdd Build a sorting library)" };
      }
      const intent = rest.join(" ").trim();
      return { local: "__SDD_START__", rpcParams: { intent } };
    }
    case "sdd-status": {
      return { local: "__SDD_STATUS__" };
    }
    case "sdd-list": {
      return { rpcMethod: "sdd.listRuns" };
    }
    case "sdd-approve": {
      return { local: "__SDD_ADVANCE__", rpcParams: { action: "approve" } };
    }
    case "sdd-modify": {
      if (rest.length === 0) {
        return {
          local: "usage: /sdd-modify <feedback>   (e.g. /sdd-modify add max-array-size = 10000)",
        };
      }
      const feedback = rest.join(" ").trim();
      return { local: "__SDD_ADVANCE__", rpcParams: { action: "modify", feedback } };
    }
    case "sdd-skip": {
      return { local: "__SDD_ADVANCE__", rpcParams: { action: "skip" } };
    }
    case "sdd-abort": {
      return { local: "__SDD_ABORT__" };
    }
    case "sdd-view": {
      return { local: "__SDD_VIEW__", rpcParams: { phase: parsePhase(rest) } };
    }
    case "sdd-snapshot": {
      return { local: "__SDD_SNAPSHOT__", rpcParams: { phase: parsePhase(rest) } };
    }
    case "sdd-diff": {
      return { local: "__SDD_DIFF__", rpcParams: { phase: parsePhase(rest) } };
    }
    case "sdd-clear-cache": {
      return { local: "__SDD_CLEAR_CACHE__" };
    }
    default:
      return { local: `unknown slash command: /${cmd}. Try /help.` };
  }
}

/**
 * The single entry point for slash commands.
 *
 * <p>R369. Five commands — `/stop`, `/pause`, `/continue`, `/rewind`,
 * `/tokens` — are advertised in `/help` and would each return -32601,
 * because the daemon the TUI spawns registers none of their methods.
 * The user typed a command `/help` told them existed and got a raw
 * JSON-RPC error code back.
 *
 * <p>The filter lives HERE rather than in the five `case` bodies on
 * purpose. Per-site discipline is exactly what R364 and R366 had to
 * fix one bug at a time; making it a property of the dispatcher means
 * a command added next month that targets a missing method is honest
 * for free. Same shape as R367's lesson: change the model, not the
 * call sites.
 *
 * <p>Deliberately NOT a silent skip. The command stays advertised and
 * still runs — it just answers in a sentence instead of an error code.
 * Hiding it would lose discovery; firing the request spends a round
 * trip to learn something this table already knows.
 */
export function handleSlash(input: string): SlashResult | null {
  const result = routeSlash(input);
  const why = unsupportedReason(result?.rpcMethod);
  if (!result || !why || !result.rpcMethod) return result;
  return { local: unsupportedMessage(result.rpcMethod, why) };
}
