/**
 * the Ink-based TUI.
 *
 * Layout (top to bottom):
 *   ┌──────────────────────────────────────────────────────┐
 *   │ Header  · brand · model · mode · conn · session      │  1 row
 *   ├──────────────────────────────────────────────────────┤
 *   │                                                      │
 *   │  Scrollback (assistant / user / tool / system msgs)  │  flex
 *   │                                                      │
 *   ├──────────────────────────────────────────────────────┤
 *   │ ▸ input (the user prompt)                             │  1 row
 *   ├──────────────────────────────────────────────────────┤
 *   │ StatusBar · tokens · cost · mode · jar                │  1 row
 *   └──────────────────────────────────────────────────────┘
 *
 * The state is a small reducer (useReducer) over a list of
 * "turns" (one per visible line / card in the scrollback).
 * Side effects (RPC calls, file I/O) live in the App component
 * via useEffect / event handlers.
 */

import React, { useEffect, useReducer, useRef, useState } from "react";
import { render, Box, Text, useApp, useInput, type Instance as InkInstance } from "ink";
import Spinner from "ink-spinner";
import { JsonRpcClient, type RpcValue } from "./jsonrpc.js";
import { findJar } from "./ac-tui.js";
import {
  reducer, INITIAL, type State, type Action, type PermissionAsk, type SddRunState,
  type PanelId, type PanelStatus, type MemoryScopeId, type MemoryRow, type ThreadRow,
  type GrantRow,
} from "./state.js";
import { handleSlash, completeSlash } from "./commands.js";
// R362 — the sentinel → intent contract. The slash dispatcher looks
// intents up here instead of hand-writing an `if (local === "__X__")`
// chain, so a sentinel can never be emitted without a declared owner
// on this renderer.
import { intentForSentinel, describeSentinel } from "./panelIntents.js";
// R370 — `sameCwd` / `partitionByCwd` for `/sessions`. String `===`
// on a path is not a cwd comparison: it is case- and separator-
// sensitive, which on Windows empties the list it is filtering.
import { partitionByCwd, cwdBasename, sameCwd } from "./cwdMatch.js";
import { formatSystemPrompt, formatSystemPromptSection } from "./prompt-formatter.js";
import { t, icon } from "./theme.js";
import { Header } from "./components/Header.js";
import { StatusBar } from "./components/StatusBar.js";
import { SddMode } from "./components/SddMode.js";
import { SddReview } from "./components/SddReview.js";
import { SubagentPanel } from "./components/SubagentPanel.js";
import { InputBox } from "./components/InputBox.js";
import { Scrollback } from "./components/Scrollback.js";
import { SubagentSpawnCards } from "./components/chat/SubagentSpawnCard.js";
import { HelpOverlay } from "./components/HelpOverlay.js";
import { Sidebar } from "./components/Sidebar.js";
import { SearchBar } from "./components/SearchBar.js";
import { ToastStack } from "./components/Toast.js";
// T-6-15..T-6-18: 3-column layout (left = session list,
// center = chat, right = details + todos + tokens).
import { SessionDetailsPanel } from "./components/session/SessionDetailsPanel.js";
import { TodoBoard } from "./components/todo/TodoBoard.js";
// StaleWarning is rendered by the host when a session
// goes stale (> 30 min inactive). The hook lives in
// `useStaleSessionWarning` (Phase 6.3); for now we
// import the component so the host can mount it
// on demand. Re-enable in T-7 once the supervisor
// fires the right events.
import { StaleWarning } from "./components/StaleWarning.js";
import { ThemeProvider } from "./ThemeContext.js";
import { ThemePicker, type PickerTheme } from "./components/ThemePicker.js";
import { toPickerTheme, useAethercodeThemeList, useAethercodeThemeFromStore } from "./aethercodeThemesBridgeCore.js";
import { AethercodeThemeProvider } from "./aethercodeThemesBridgeUI.js";
import { ThemeStore } from "aethercode-themes";
import * as os from "node:os";
import { CommandPalette } from "./components/CommandPalette.js";
import { LogViewer } from "./components/LogViewer.js";
import { Welcome } from "./components/Welcome.js";
/* --- R362: the twelve previously-unmounted panels ------------------ */
import { MemoryPanel, type CompactProgress } from "./components/MemoryPanel.js";
import { ThreadSelector, type ThreadInfo } from "./components/ThreadSelector.js";
import { AgentSelector, type AgentEntry } from "./components/AgentSelector.js";
import { EffortPicker, type EffortEntry } from "./components/EffortPicker.js";
import { CwdSwitcher, makeStatValidator } from "./components/CwdSwitcher.js";
import {
  GrantsManager,
  filterGrants,
  groupGrantsByScope,
  formatGrantLine,
  type Grant,
} from "./components/permissions/GrantsManager.js";
import { McpViewer, type ServerInfo } from "./components/McpViewer.js";
import { NotificationCenter, type PendingNotification } from "./components/NotificationCenter.js";
import * as fs from "node:fs";

export interface TuiOptions {
  jar: string;
  cwd: string;
  javaBin: string;
  jvmArgs: string[];
  model?: string;
  noColor?: boolean;
}

function handleStreamEvent(dispatch: (a: Action) => void, ev: Record<string, unknown>): void {
  const t = ev.type as string | undefined;
  switch (t) {
    case "run_start":
      dispatch({ type: "streamStart" });
      break;
    case "text_delta":
      dispatch({ type: "streamText", text: String(ev.text ?? "") });
      break;
    case "tool_use_start":
      dispatch({
        type: "streamToolStart",
        name: String(ev.name ?? "?"),
        args: ev.args ? String(JSON.stringify(ev.args)) : undefined,
      });
      break;
    case "tool_result":
      dispatch({
        type: "streamToolEnd",
        name: String(ev.id ?? "?"),
        isError: Boolean(ev.isError),
        result: ev.result ? String(ev.result) : undefined,
      });
      break;
    case "run_end":
      // capture the cost for the sparkline.
      const evCost = (ev.usage as { costUsd?: number } | undefined)?.costUsd;
      if (typeof evCost === "number" && Number.isFinite(evCost)) {
        dispatch({ type: "pushCost", cost: evCost });
      }
      dispatch({
        type: "streamEnd",
        stopReason: String(ev.stopReason ?? "end_turn"),
        usage: ev.usage as { input?: number; output?: number; costUsd?: number } | undefined,
      });
      break;
    case "side_note":
      dispatch({
        type: "sideNote",
        kind: String(ev.kind ?? "note"),
        message: String(ev.message ?? ""),
      });
      break;
    case "plan":
      dispatch({
        type: "plan",
        items: Array.isArray(ev.items) ? ev.items.map((x) => String(x)) : [],
      });
      break;
    default:
      break;
  }
}

/**
 * send a permission decision back to the daemon. Wraps the
 * `permissionResponse` RPC call and ALSO issues a
 * `permissionPolicyOverride` when the user picks a remembered
 * scope (T / P / U / N). The override registers a rule in the
 * engine's ProjectPermissionPolicy so the next call to the same
 * tool / target auto-resolves without an ask.
 *
 * Mapping:
 *   A — allow once
 *   T — allow for this task (session-scope rule, future calls in
 *       the current task skip the ask)
 *   P — allow for this project (project-scope rule, persisted to
 *       .aethercode/permissions.json)
 *   U — allow for this user (user-scope rule, persisted to
 *       ~/.aethercode/projects/<hash>/permissions.json)
 *   D — deny once
 *   N — deny and remember for this tool (session-scope deny rule)
 */
function replyPermission(
  client: JsonRpcClient,
  ask: PermissionAsk,
  decision: "allow" | "deny" | "always_allow" | "always_deny",
  dispatch: (a: Action) => void,
  rememberScope?: "session" | "project" | "user",
): void {
  void client.request("permissionResponse", {
    requestId: ask.requestId,
    decision,
    reason: `TUI picked ${decision}`,
  }).catch((e: Error) => {
    dispatch({ type: "log", message: `permission reply failed: ${e.message}` });
  });
  // If the user picked a remembered scope, also install a rule.
  // We do this AFTER the immediate reply so the model isn't held
  // up — the rule only affects future calls in this or later runs.
  if (rememberScope && rememberScope !== "session") {
    const overrideDecision = (decision === "always_allow" || decision === "allow") ? "allow" : "deny";
    // For file_* / bash, the "target" is the file path / command.
    // For other tools we pass no target (matches all inputs of that tool).
    const target =
      typeof ask.input.command === "string" ? String(ask.input.command) :
      typeof ask.input.file_path === "string" ? String(ask.input.file_path) :
      typeof ask.input.path === "string" ? String(ask.input.path) :
      typeof ask.input.url === "string" ? String(ask.input.url) :
      "";
    void client.request("permissionPolicyOverride", {
      tool: ask.tool,
      target,
      decision: overrideDecision,
      scope: rememberScope,
    }).then((res) => {
      const r = res as { ok?: boolean; reason?: string } | null | undefined;
      if (r?.ok) {
        dispatch({
          type: "pushToast",
          kind: "ok",
          text: `saved ${overrideDecision} rule for ${ask.tool} (${rememberScope})`,
        });
      } else {
        dispatch({
          type: "pushToast",
          kind: "warn",
          text: `policy override failed: ${r?.reason ?? "unknown"}`,
        });
      }
    }).catch((e: Error) => {
      dispatch({ type: "log", message: `policy override failed: ${e.message}` });
    });
  }
  // clear the inline permission prompt once
  // the user has chosen an option. The card is
  // unmounted by the reducer's permissionClear case.
  dispatch({ type: "permissionClear" });
}

/** insert a finished subagent's result (or
 *  short note for failed / cancelled) into the
 *  input box. The user can edit before sending.
 *  Mirrors the desktop's "insert result" button on
 *  the SubagentPanel. */
function cancelSubagent(
  jobId: string,
  state: State,
  client: JsonRpcClient,
  dispatch: (a: Action) => void,
): void {
  const job = state.subagentJobs[jobId];
  if (!job || job.status !== "RUNNING") {
    dispatch({ type: "pushToast", kind: "warn", text: `subagent ${jobId} is not running` });
    return;
  }
  dispatch({ type: "pushToast", kind: "info", text: `cancelling subagent ${jobId}…` });
  void (async () => {
    try {
      const r = (await client.request("subagentCancel", { jobId })) as
        { ok?: boolean; cancelled?: boolean; alreadyFinished?: boolean } | null | undefined;
      if (r?.alreadyFinished) {
        dispatch({ type: "pushToast", kind: "warn", text: `subagent ${jobId} already finished` });
      } else if (r?.cancelled) {
        dispatch({ type: "pushToast", kind: "ok", text: `cancelled subagent ${jobId}` });
      } else {
        dispatch({ type: "pushToast", kind: "warn", text: `subagent ${jobId}: nothing to cancel` });
      }
    } catch (e: any) {
      dispatch({ type: "pushToast", kind: "err", text: `cancel failed: ${e?.message ?? String(e)}` });
    }
  })();
}

function insertSubagentResult(
  jobId: string,
  state: State,
  dispatch: (a: Action) => void,
): void {
  const job = state.subagentJobs[jobId];
  if (!job) {
    dispatch({ type: "pushToast", kind: "warn", text: `subagent ${jobId} not found` });
    return;
  }
  let text = "";
  if (job.status === "COMPLETED" && job.resultText) {
    text = job.resultText;
  } else if (job.status === "FAILED") {
    text = `[subagent ${jobId} failed: ${job.summary || "no detail"}]`;
  } else if (job.status === "CANCELLED") {
    text = `[subagent ${jobId} was cancelled; result not available]`;
  } else {
    dispatch({ type: "pushToast", kind: "warn", text: `subagent ${jobId} is still running` });
    return;
  }
  dispatch({ type: "setInput", text });
  dispatch({ type: "closeSubagentPanel" });
  dispatch({ type: "pushToast", kind: "info", text: `inserted subagent ${jobId} result` });
}

/**
 * R362 — one-line preview for a queued prompt in the strip above the input
 * box. Collapses newlines so a multi-line prompt does not break the row, and
 * clips to the terminal width so the strip never wraps into the transcript.
 */
function truncateForQueue(text: string, max = 56): string {
  const flat = text.replace(/\s+/g, " ").trim();
  if (flat.length <= max) return flat;
  return `${flat.slice(0, max - 1)}…`;
}

/* ===================================================================
 * R362 — Ink panel host
 *
 * Twelve components sat behind slash-command sentinels that no
 * renderer claimed, so `/memory` printed `__MEMORY_PANEL__:project`
 * to the screen. This block is everything needed to mount them:
 * a registry-driven claim in `handleSubmit`, one fetch effect, one
 * shared modal shell, and the per-panel prop wiring.
 * =================================================================== */

/** `PanelIntent.id` (panelIntents.ts) → the `PanelId` the reducer
 *  stores. Two tables keyed by the same string would be exactly the
 *  drift the registry exists to prevent, so keep this next to it. */
const PANEL_BY_INTENT: Record<string, PanelId> = {
  "memory-panel": "memory",
  "thread-selector": "threads",
  // R370: same panel as `thread-selector` on purpose. `/threads`
  // lists everything the daemon knows; `/sessions` lists this
  // cwd's sessions. The difference is `State.panelCwdOnly`, not the
  // component, not the fetch, and not the continuation.
  "session-picker": "threads",
  "todo-board": "todos",
  "agent-selector": "agents",
  "effort-picker": "effort",
  "cwd-switcher": "cwd",
  "grants-manager": "grants",
  "notification-center": "notifications",
  "mcp-viewer": "mcp",
};

/** Intents whose panel row list is scoped to the active cwd. Kept as
 *  an explicit set rather than an `id === "session-picker"` test at
 *  the call site, so adding a third cwd-scoped intent is a one-line
 *  change and the `openPanel` call site stays a lookup. */
const CWD_SCOPED_INTENTS = new Set(["session-picker"]);

/** Per-panel chrome copy. The shell renders the `title` and
 *  `hint`; `loading` names the RPC in flight so the pending row
 *  says WHAT is loading, not just "loading". */
const PANEL_META: Record<PanelId, { title: string; hint: string; loading: string }> = {
  memory: {
    title: "Memory",
    hint: "1/2/3 or ←/→ scope · ↑/↓ select · Enter edit · c compact",
    loading: "reading memory",
  },
  threads: {
    title: "Threads",
    hint: "type to filter · ↑/↓ or j/k navigate · Enter switch · Esc cancel",
    loading: "listing sessions",
  },
  todos: {
    title: "TODO board",
    hint: "j/k or ↑/↓ navigate · Enter expand · g/G top/bottom",
    loading: "loading todos",
  },
  agents: {
    title: "Agents",
    hint: "type to filter · ↑/↓ or j/k navigate · Enter make active · Ctrl+S default",
    loading: "listing agents",
  },
  effort: {
    title: "Reasoning effort",
    hint: "↑/↓ or j/k navigate · Enter set · Esc cancel",
    loading: "loading effort levels",
  },
  cwd: {
    title: "Working directory",
    hint: "type an absolute path · Enter switch · Esc cancel",
    loading: "validating path",
  },
  grants: {
    title: "Consents",
    hint: "↑/↓ or j/k navigate · r revoke · 1-4 scope filter",
    loading: "listing grants",
  },
  notifications: {
    title: "Notifications",
    hint: "↑/↓ or j/k navigate · Enter select · Tab settings · Esc close",
    loading: "loading notifications",
  },
  mcp: {
    title: "MCP servers",
    hint: "↑/↓ or j/k navigate · Enter sign in · Ctrl+R reconnect",
    loading: "listing MCP servers",
  },
};

/** Strip the argument suffix from a sentinel: `__MCP_LOGIN__:github`
 *  → `__MCP_LOGIN__`. The registry keys on the bare token; several
 *  commands pass a target after a colon. */
function bareSentinel(s: string): string {
  const i = s.indexOf(":");
  return i < 0 ? s : s.slice(0, i);
}

/**
 * MemoryPanel's scope union is `global | project | session`, but the
 * daemon's `MemoryScope` enum is `USER | PROJECT | SESSION` — the
 * cross-project layer is called "global" in the panel and "USER" on
 * the wire. Anything sent as `"GLOBAL"` comes back as an unknown
 * scope. This map is the only place the two vocabularies meet.
 */
const MEMORY_WIRE_SCOPE: Record<MemoryScopeId, string> = {
  global: "USER",
  project: "PROJECT",
  session: "SESSION",
};

/** Panel width from the live terminal, minus the shell's own frame.
 *  80 columns is the hard floor the layout is designed against; the
 *  cap stops the panel from becoming a letterboxed island on a 4K
 *  terminal. */
function panelWidthFor(cols: number): number {
  return Math.max(52, Math.min(cols - 4, 100));
}

/**
 * Minimum terminal width at which `GrantsManager`'s 3-column layout
 * still fits: left 36 + right 32 + both inner borders/padding +
 * margins + the outer frame ≈ 112. Below it we render
 * `GrantsCompact` instead.
 */
const GRANTS_WIDE_MIN_COLS = 112;

/** Horizontal rule exactly `n` cells wide, for the shell dividers. */
function rule(n: number): string {
  return "─".repeat(Math.max(4, n));
}

/** Stable across renders — CwdSwitcher's validation effect lists
 *  `validate` in its dependency array, so a fresh closure each
 *  render would loop forever. */
const CWD_VALIDATE = makeStatValidator((p: string) => {
  try {
    return fs.statSync(p);
  } catch {
    return null;
  }
});

/** Static reasoning-effort catalog.
 *
 *  NOTE: the daemon exposes no effort RPC (verified against
 *  `AetherCodeMethods`' method registry — there is neither a read
 *  nor a write path for it), so this catalog cannot be model-
 *  specific yet. It is the de-facto OpenAI/Anthropic ladder.
 *  Flagged for a product decision rather than invented. */
const EFFORT_CATALOG: ReadonlyArray<EffortEntry> = [
  { label: "low", id: "low", description: "fastest, least reasoning" },
  { label: "medium", id: "medium", description: "balanced" },
  { label: "high", id: "high", description: "more reasoning, slower" },
  { label: "xhigh", id: "xhigh", description: "maximum reasoning" },
];

/* ------------------------- wire normalisers ------------------------ */

/** The daemon is the source of truth for the wire shape, but the
 *  three "list" RPCs have grown optional fields over time. Each
 *  normaliser therefore reads the canonical key first and falls
 *  back rather than throwing — a renamed field should degrade one
 *  column, not blank the panel. */

function normalizeMemoryRow(raw: unknown, i: number): MemoryRow {
  const r = (raw ?? {}) as Record<string, unknown>;
  const k = String(r.kind ?? "fact");
  const kind = k === "rule" || k === "change" || k === "breadcrumb" ? k : "fact";
  return {
    id: String(r.id ?? r.entryId ?? r.key ?? `mem-${i}`),
    kind,
    key: r.key == null ? undefined : String(r.key),
    value: String(r.value ?? r.text ?? r.description ?? r.body ?? ""),
    ts: Number(r.ts ?? r.updatedAt ?? r.createdAt ?? 0),
    source: String(r.source ?? r.scope ?? r.origin ?? "—"),
    tags: Array.isArray(r.tags) ? r.tags.map(String) : [],
  };
}

/** R370 — `listSessions` sends `lastUsedAt` as EPOCH MILLIS (a JSON
 *  number, from `SessionInfo.lastModified()`), not a formatted
 *  string, and sends NO `updatedAt` / `lastActiveAt` at all. The old
 *  reader (`r.updatedAt ?? r.lastActiveAt`) therefore resolved to
 *  `""` for every row, every time — a field that is structurally
 *  incapable of being non-empty. Render it as an age instead, and
 *  only when the wire actually carried one. */
function formatAge(raw: unknown, now: number): string {
  const ms = typeof raw === "number" ? raw : Number(raw);
  if (!Number.isFinite(ms) || ms <= 0) return "";
  const d = now - ms;
  if (d < 0) return "just now";
  const min = Math.floor(d / 60_000);
  if (min < 1) return "just now";
  if (min < 60) return `${min}m ago`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h ago`;
  const day = Math.floor(hr / 24);
  if (day < 30) return `${day}d ago`;
  const mo = Math.floor(day / 30);
  if (mo < 12) return `${mo}mo ago`;
  return `${Math.floor(mo / 12)}y ago`;
}

function normalizeThreadRow(raw: unknown, i: number, now: number): ThreadRow {
  const r = (raw ?? {}) as Record<string, unknown>;
  return {
    threadId: String(r.sessionId ?? r.id ?? r.threadId ?? `session-${i}`),
    agentName: String(r.agentName ?? r.agent ?? "primary"),
    messages: Number(r.messageCount ?? r.messages ?? r.count ?? 0) || 0,
    createdAt: formatAge(r.createdAt, now),
    updatedAt: formatAge(r.lastUsedAt ?? r.updatedAt ?? r.lastActiveAt, now),
    prompt: String(r.title ?? r.prompt ?? r.summary ?? r.firstPrompt ?? r.preview ?? ""),
    cwd: String(r.cwd ?? r.workingDirectory ?? ""),
    gitBranch: r.gitBranch == null ? undefined : String(r.gitBranch),
  };
}

function normalizeGrantRow(raw: unknown, i: number): GrantRow {
  const r = (raw ?? {}) as Record<string, unknown>;
  const s = String(r.scope ?? "session");
  const scope: GrantRow["scope"] = s === "project" || s === "user" ? s : "session";
  return {
    id: String(r.id ?? r.grantId ?? `grant-${i}`),
    scope,
    scopeId: String(r.scopeId ?? r.target ?? r.tool ?? "—"),
    category: String(r.category ?? r.tool ?? "—"),
    decision: String(r.decision ?? "allow") === "deny" ? "deny" : "allow",
    reason: String(r.reason ?? ""),
    createdAt: Number(r.createdAt ?? r.createdAtMs ?? r.ts ?? 0) || 0,
    expiresAt: r.expiresAt == null ? null : Number(r.expiresAt),
  };
}

/** Turn `{ ok: false, error }` daemon envelopes into a message
 *  instead of rendering them as an empty list. */
function envelopeError(v: unknown): string | null {
  if (v && typeof v === "object") {
    const r = v as Record<string, unknown>;
    if (r.ok === false) return String(r.error ?? r.reason ?? "the daemon rejected the request");
  }
  return null;
}

/* --------------------------- the shell ---------------------------- */

/**
 * The shared modal chrome. Every R362 panel renders inside this so
 * the twelve surfaces read as one family: same rounded frame, same
 * brand title bar, same hint line, same pending / error / notice
 * row, same Esc affordance.
 *
 * Esc is handled HERE as well as by each component. Two components
 * (`GrantsManager`, `TodoBoard`) have no Esc branch of their own,
 * and Ink's `useInput` fans every keystroke out to every mounted
 * hook — so this is the safety net, and a component that also
 * closes on Esc simply dispatches the same idempotent action twice.
 */
const PanelShell: React.FC<{
  title: string;
  /**
   * Optional. Six of the nine components already print their own
   * keymap line under their title, so passing their hint here would
   * render the same sentence twice. The shell only supplies a hint
   * for the component that has none (TodoBoard).
   */
  hint?: string;
  width: number;
  status: PanelStatus;
  loadingLabel: string;
  onClose: () => void;
  children?: React.ReactNode;
}> = ({ title, hint, width, status, loadingLabel, onClose, children }) => {
  useInput((_input, key) => {
    if (key.escape) onClose();
  });
  // frame = 2 border cells + 2 padding cells.
  const inner = width - 4;
  return (
    <Box
      flexDirection="column"
      width={width}
      borderStyle="round"
      borderColor={t.dim}
      paddingX={1}
    >
      <Box justifyContent="space-between">
        <Text color={t.brand} bold>{`◆ ${title}`}</Text>
        <Text dimColor>esc close</Text>
      </Box>
      {hint ? <Text dimColor>{hint}</Text> : null}
      <Text color={t.dim}>{rule(inner)}</Text>
      <Box flexDirection="column">{children}</Box>
      <Text color={t.dim}>{rule(inner)}</Text>
      {status.loading ? (
        <Box>
          <Text color={t.spinner}><Spinner type="dots" /></Text>
          <Text color={t.warn}>{` ${loadingLabel}…`}</Text>
        </Box>
      ) : null}
      {status.error ? <Text color={t.err}>{`✗ ${status.error}`}</Text> : null}
      {status.notice && !status.error ? (
        <Text color={t.warn}>{`! ${status.notice}`}</Text>
      ) : null}
    </Box>
  );
};

/* ------------------ GrantsManager narrow-terminal fallback -------- */

/**
 * `GrantsManager` hard-codes a 3-column layout whose fixed columns
 * (36 + 32 + borders + margins) need ~112 columns before the middle
 * column gets any width. Below that it wraps into an unreadable
 * mess, which breaks the 80-column floor the rest of the TUI is
 * designed against.
 *
 * This is the same data and the same actions in a single column,
 * reusing the component's own pure helpers so the filtering and
 * grouping semantics cannot drift. The component itself needs a
 * `width`-prop fix; this is the host-side mitigation.
 */
const GrantsCompact: React.FC<{
  grants: Grant[];
  width: number;
  onRevoke: (id: string) => void;
}> = ({ grants, width, onRevoke }) => {
  const [focus, setFocus] = useState(0);
  const [scope, setScope] = useState<"all" | "session" | "project" | "user">("all");
  const filtered = React.useMemo(() => filterGrants(grants, scope, ""), [grants, scope]);
  const grouped = React.useMemo(() => groupGrantsByScope(filtered), [filtered]);

  // Flatten the grouped view so ↑/↓ walks what the user sees.
  const flat = React.useMemo(() => grouped.flatMap((g) => g.items), [grouped]);
  useEffect(() => {
    if (focus >= flat.length) setFocus(Math.max(0, flat.length - 1));
  }, [flat.length, focus]);

  useInput((input, key) => {
    if (key.upArrow || input === "k") {
      setFocus((f) => Math.max(0, f - 1));
      return;
    }
    if (key.downArrow || input === "j") {
      setFocus((f) => Math.min(flat.length - 1, f + 1));
      return;
    }
    if (input === "r") {
      const g = flat[focus];
      if (g) onRevoke(g.id);
      return;
    }
    if (input === "1") setScope("all");
    else if (input === "2") setScope("session");
    else if (input === "3") setScope("project");
    else if (input === "4") setScope("user");
  });

  const label = (s: string) => (s === "all" ? `all (${filtered.length})` : s);
  return (
    <Box flexDirection="column" width={width}>
      <Box>
        <Text dimColor>scope: </Text>
        {(["all", "session", "project", "user"] as const).map((s) => (
          <Text key={s} color={scope === s ? t.accent : undefined} bold={scope === s}>
            {` [${s === "all" ? 1 : s === "session" ? 2 : s === "project" ? 3 : 4}]${label(s)} `}
          </Text>
        ))}
      </Box>
      {flat.length === 0 ? (
        <Text dimColor>
          no grants stored yet — answer a permission prompt with P / U to remember one, or run /consents again
        </Text>
      ) : (
        grouped.map(({ scope: gscope, items }) => (
          <Box key={gscope} flexDirection="column" marginTop={1}>
            <Text dimColor>{`▼ ${gscope}`}</Text>
            {items.map((g) => {
              const idx = flat.indexOf(g);
              const on = idx === focus;
              return (
                <Text key={g.id} color={on ? t.accent : undefined} bold={on}>
                  {on ? "▶ " : "  "}
                  {g.decision === "allow" ? icon.ok : icon.err} {formatGrantLine(g)}
                  <Text dimColor>{`  · ${g.scopeId}`}</Text>
                </Text>
              );
            })}
          </Box>
        ))
      )}
      <Text dimColor>  {`${flat.length} shown · ↑/↓ navigate · r revoke · 1-4 scope`}</Text>
    </Box>
  );
};

const App: React.FC<{ client: JsonRpcClient; cwd: string; stateRef: React.MutableRefObject<State> }> = ({
  client,
  cwd,
  stateRef,
}) => {
  const { exit } = useApp();

  // R364 — seed `state.cwd` from the launch directory ONCE, then let
  // the daemon own it. Using a lazy useReducer initialiser rather than
  // a mount effect: an effect would render one frame with an empty
  // cwd, and every `currentCwd={currentCwd}` would flash.
  // The prop is only a bootstrap value — from here on `state.cwd` is
  // whatever the daemon last reported.
  const [state, dispatch] = useReducer(reducer, INITIAL, (init) =>
    cwd ? { ...init, cwd } : init,
  );
  stateRef.current = state;
  const [h, setH] = useState(20);

  // R364 — the ONE place the app reads its working directory from.
  //
  // Before this, `cwd` (the launch-time prop) was read in ~10 places:
  // the Header pill, the Welcome card, the Sidebar, the SDD banner,
  // the `/export` default path, the export frontmatter, and — worst —
  // four `sdd.*` RPC payloads. After a `/cwd` switch all ten showed
  // the OLD directory, and the SDD calls told the daemon to build
  // against a directory the session had already left.
  //
  // `state.cwd || cwd` keeps the launch prop as the bootstrap value
  // (the daemon may not have reported anything yet), and after the
  // first `switchProject` / `cwd_changed` the state wins.
  const currentCwd = state.cwd || cwd;

  // R363 — "a cancel for THIS run has already been dispatched".
  // Distinct from `state.cancelRequested` (which the reducer owns and
  // which a failed cancel deliberately rolls back): this ref is the
  // synchronous latch that makes a double-tap of Esc within one
  // render frame a single RPC. Cleared on every run boundary.
  const cancelSentRef = React.useRef(false);

  // T-422: aethercode-themes bridge. We instantiate a single
  // ThemeStore per TUI session and wrap the subtree in the
  // provider. The store reads <UserHome>/.aethercode/theme.json
  // (or falls back to the surface default). The legacy
  // 5-palette `state.themeName` is left in place — the two
  // systems are independent in this round.
  const themeStoreRef = React.useRef<ThemeStore | null>(null);
  if (themeStoreRef.current === null) {
    themeStoreRef.current = new ThemeStore({ userHome: os.homedir() });
  }
  const themeStore = themeStoreRef.current;
  useEffect(() => {
    const unsub = themeStore.on("change", (e) => {
      dispatch({ type: "setThemesPickedName", name: e.current.name });
    });
    return unsub;
  }, [themeStore]);
  const themeList = useAethercodeThemeList(themeStore);
  const activeTheme = useAethercodeThemeFromStore(themeStore);
  const pickerThemes: ReadonlyArray<PickerTheme> = themeList.map(toPickerTheme);
  const pickerCurrent =
    state.themesPickedName ?? activeTheme?.name ?? pickerThemes[0]?.name ?? "";
  // wire the JsonRpcClient's onConnectionState into
  // the reducer. The runTui entry point sets up a no-op
  // placeholder (the dispatch is owned by App), so we
  // re-wire it here on mount. The callback is the one path
  // for the daemon's lifecycle events (connecting /
  // connected / reconnecting / disconnected) to reach
  // the renderer; the legacy `client.onExit` callback
  // remains for the user's terminal stderr, but the
  // status bar reads the reducer state, not the onExit.
  useEffect(() => {
    // The client has no public setter for the connection
    // callback (it's wired at construction), so we use
    // an out-of-band event on process. runTui emits; App
    // listens here and dispatches into the reducer.
    const onConn = (state: string, attempt: number, lastError: string | null) => {
      dispatch({
        type: "setConnectionState",
        state: state as any,
        attempt,
        lastError,
      });
    };
    (process as any).on("aethercode:connectionState", onConn);
    return () => {
      (process as any).off("aethercode:connectionState", onConn);
    };
  }, []);

  // are running tools. We use this as a `key` on the running
  // ToolCards so Ink re-renders them and the duration timer
  // updates. Without this, a running tool would show "0ms"
  // forever (its render is one-shot on streamToolStart).
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (state.pending === 0) return;
    const id = setInterval(() => setTick((t) => t + 1), 500);
    return () => clearInterval(id);
  }, [state.pending]);

  // R245.5: welcome-banner bank status. We fire-and-forget
  // a single readBankStats() on mount; the resolved summary
  // (always populates `text`, including the "down" case) is
  // dropped into local state and rendered as a dim hint on
  // the welcome screen. The fetch is bounded by the
  // BankClient's own 10s timeout (R244.3); we don't add a
  // second layer of bookkeeping here.
  const [bankStatus, setBankStatus] = useState<string | null>(null);
  // R700d — ephemeral diff-viewer state. /sdd-view and /sdd-diff
  // populate this; /sdd-clear-cache + /sdd-snapshot both leave
  // the slot untouched so the user can browse the rendered
  // output without re-issuing the read.
  const [sddReview, setSddReview] = useState<{
    path: string;
    lines: string[];
    previousLines: string[] | null;
  } | null>(null);
  // R362: which TODO row the user expanded inside the /todos panel.
  // Local, not in the reducer: it is pure view state that resets on
  // every open and nothing else reads it.
  const [todoSelectedId, setTodoSelectedId] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const { readBankStats } = await import("aethercode-memory");
        const summary = await readBankStats();
        if (!cancelled) setBankStatus(summary.text);
      } catch {
        if (!cancelled) setBankStatus(null);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // 200ms tick that trims expired toasts. Cheap; runs only
  // when at least one toast is active.
  useEffect(() => {
    if (state.toasts.length === 0) return;
    const id = setInterval(() => {
      dispatch({ type: "trimToasts", now: Date.now() });
    }, 200);
    return () => clearInterval(id);
  }, [state.toasts.length]);

  // 1s tick that auto-clears the "low!" badge 5
  // seconds after the last skip-low event. Runs only when
  // the badge is active; the dispatch is a no-op when
  // there's nothing to clear.
  useEffect(() => {
    if (!state.lastSkipLow) return;
    const id = setInterval(() => {
      const atMs = stateRef.current.lastSkipLow?.atMs ?? 0;
      if (atMs > 0 && Date.now() - atMs > 5_000) {
        dispatch({ type: "clearLastSkipLow" });
      }
    }, 1_000);
    return () => clearInterval(id);
  }, [state.lastSkipLow?.atMs]);

  useEffect(() => {
    client.setNotificationHandler((method: string, params: RpcValue | undefined) => {
      switch (method) {
        case "cwd_changed": {
          // R364 — the daemon emits this on EVERY cwd change
          // (`AetherCodeMethods.switchProject` → NOTIFY_CWD_CHANGED),
          // not just the ones this window initiated. Pre-R364 the
          // TUI had no case for it and dropped it on the floor, so a
          // switch — even a successful one — left every cwd display
          // showing the launch directory.
          //
          // The RPC response already covers "the user just clicked";
          // this covers everything else: the desktop changing it, a
          // second connected client, or a switch initiated by the
          // `/cwd` slash command rather than the panel.
          const p = (params ?? {}) as { newCwd?: string; oldCwd?: string; sessionId?: string };
          if (p.newCwd) {
            dispatch({ type: "setCurrentCwd", cwd: p.newCwd });
            // Full path, not `shortCwd(...)` — that helper is local to
            // line.ts and a sideNote already wraps/truncates for
            // display. Copying it here would be a second copy to drift.
            dispatch({
              type: "sideNote",
              kind: "cwd",
              message: `cwd → ${p.newCwd}`,
            });
          }
          break;
        }
        case "stream_event": {
          const p = (params ?? {}) as { runId?: string; event?: Record<string, unknown> };
          // R363 — runId fallback. `sendPrompt` captures the id from
          // the `query` response, but that response can be lost (a
          // reconnect between request and reply) while the stream
          // keeps flowing. The notification carries the same id, so
          // adopting it here keeps Esc functional instead of stranding
          // the user in a run they cannot stop.
          if (p.runId && stateRef.current.activeRunId !== p.runId) {
            dispatch({ type: "setActiveRunId", runId: p.runId });
          }
          handleStreamEvent(dispatch, p.event ?? {});
          break;
        }
        case "task_state": {
          // a task update notification. Show as a side note
          // AND schedule a listTasks re-fetch so the sidebar
          // reflects the new state within ~100ms.
          const p = (params ?? {}) as { taskId?: string; status?: string; name?: string };
          dispatch({
            type: "sideNote",
            kind: "task",
            message: `task ${p.taskId ?? "?"} → ${p.status ?? "?"}`,
          });
          // also push a transient toast so the user notices
          // the task update without scrolling.
          const tkind = (p.status === "error" || p.status === "failed") ? "err" :
                        (p.status === "done" || p.status === "ok" || p.status === "completed") ? "ok" :
                        "info";
          dispatch({ type: "pushToast", kind: tkind, text: `task ${p.taskId ?? "?"} → ${p.status ?? "?"}` });
          // optimistically update the recent-tasks list with
          // the new status. We don't know the full name from
          // task_state alone, so we patch the matching id in place
          // if we already have it; otherwise we re-fetch.
          void (async () => {
            try {
              const list = (await client.request("listTasks", { limit: 10 })) as Array<{ id: string; name: string; status: string; ts: number }> | null | undefined;
              if (Array.isArray(list)) {
                dispatch({ type: "setRecentTasks", tasks: list });
              }
            } catch { /* ignore — sidebar will refresh on the next tick */ }
          })();
          break;
        }
        case "log": {
          const p = (params ?? {}) as { message?: string; runId?: string; level?: string };
          if (p.runId) {
            dispatch({ type: "log", message: `[error] ${p.message ?? "unknown"}` });
          } else {
            dispatch({ type: "log", message: p.message ?? "" });
          }
          // also push to the log buffer for the log viewer.
          const level = p.level === "warn" ? "warn" :
                        p.level === "err" || p.level === "error" ? "err" : "info";
          dispatch({ type: "pushLog", level, message: p.message ?? "" });
          break;
        }
        case "subagent_event": {
          // a background subagent lifecycle transition.
          // The payload mirrors the daemon's SubagentEvent
          // (jobId, role, status, elapsedMs, summary, atMs,
          // sessionId). We dispatch into the reducer which
          // updates subagentStatus (rendered in the StatusBar)
          // and runningSubagents (a count). A terminal event
          // also pushes a transient toast so the user notices
          // the completion without staring at the status bar.
          //
          // sessionId is on the payload; the reducer
          // drops events from other sessions. Older daemons
          // don't send a sessionId — the reducer treats an
          // empty string as "ours" when state.sessionId is
          // also empty, so a legacy-D daemon + a single-
          // session TUI still works.
          const p = (params ?? {}) as {
            jobId?: string; role?: string; status?: string;
            elapsedMs?: number; summary?: string; atMs?: number;
            sessionId?: string; result?: string;
          };
          dispatch({
            type: "subagentEvent",
            jobId: String(p.jobId ?? ""),
            role: String(p.role ?? ""),
            status: String(p.status ?? ""),
            elapsedMs: Number(p.elapsedMs ?? 0),
            atMs: Number(p.atMs ?? Date.now()),
            summary: String(p.summary ?? ""),
            sessionId: String(p.sessionId ?? ""),
            // the engine's captured result text
            // (truncated to 4 KB on the wire). The TUI
            // SubagentPanel uses it for the "Enter to
            // insert" action. Empty for non-COMPLETED
            // transitions and for legacy daemons that
            // don't publish a result.
            resultText: String(p.result ?? ""),
          });
          // surface terminal events as toasts. A
          // background subagent finishing is a useful
          // signal — the parent may want to read the
          // result — so we don't want the user to miss
          // it. Running / intermediate transitions stay
          // in the status bar only (toast spam would
          // bury the actual chat).
          const st = String(p.status ?? "").toUpperCase();
          if (st === "COMPLETED") {
            dispatch({
              type: "pushToast",
              kind: "ok",
              text: `subagent ${p.jobId ?? "?"} done (${Math.round(Number(p.elapsedMs ?? 0))}ms)`,
            });
          } else if (st === "FAILED") {
            dispatch({
              type: "pushToast",
              kind: "err",
              text: `subagent ${p.jobId ?? "?"} failed`,
            });
          } else if (st === "CANCELLED") {
            dispatch({
              type: "pushToast",
              kind: "warn",
              text: `subagent ${p.jobId ?? "?"} cancelled`,
            });
          }
          break;
        }
        case "permission_request": {
          // a tool call is waiting for a permission decision.
          // We park the request in state; the user picks A/Y/D/N
          // and we send back permission_response.
          const p = (params ?? {}) as Record<string, unknown>;
          const ask: PermissionAsk = {
            requestId: String(p.requestId ?? ""),
            runId:     String(p.runId ?? ""),
            tool:      String(p.tool ?? "(unknown)"),
            input:     (p.input as Record<string, unknown>) ?? {},
            reason:    String(p.reason ?? ""),
            riskLevel: ((p.riskLevel as string) ?? "medium") as PermissionAsk["riskLevel"],
          };
          // R363 — runId fallback #2. A permission ask is proof a run
          // is live, and the daemon has already stamped it with the
          // owning runId. This is the last opportunity to make Esc
          // work before the first token arrives.
          if (ask.runId && ask.runId !== "n/a" && stateRef.current.activeRunId !== ask.runId) {
            dispatch({ type: "setActiveRunId", runId: ask.runId });
          }
          // R344: REMOVED the ACCEPT_TASK auto-allow branch.
          // The previous behaviour silently allow'd every
          // permission_request when the user was in ACCEPT_TASK
          // mode — defeating the daemon's per-sub-task boundary
          // prompts. The daemon already short-circuits IN-TASK
          // calls in ACCEPT_TASK (so the user only sees ONE
          // decision per sub-task, not one per tool call);
          // what reaches the renderer here is a real boundary
          // decision that the user should see. Render the
          // PermissionCard so the user can pick A/T/P/U/D/N.
          //
          // R344: also REMOVED the riskLevel==="low" auto-allow
          // branch. The daemon's JsonRpcPermissionPrompter
          // short-circuits low-risk calls before the renderer
          // is asked (see JsonRpcPermissionPrompter.ask line
          // 77: `if ("low".equals(riskLevel) && isAutoApproveLowRisk())`)
          // so this branch was dead code; defensive fallback
          // for old daemons only kicked in if the daemon
          // hadn't reported autoApproveLowRisk — which is
          // exactly the case where the user WANTS to be
          // asked. Render the card and let the user decide.
          dispatch({ type: "permissionAsk", ask });
          break;
        }
        case "skip_confirmation": {
          // live counter update from the daemon. The
          // payload is { sessionId, remaining, source }. We
          // update the State so the StatusBar re-renders
          // without waiting for the next getState poll.
          const p = (params ?? {}) as { remaining?: number; source?: string };
          const remaining = Number(p.remaining ?? 0);
          // Defensive: ignore events for other sessions if the
          // daemon is multi-session.
          dispatch({ type: "setSkipConfirmationRemaining", remaining });
          // Toast on a fresh arm so the user knows the
          // counter changed (only when source is "rpc" or
          // "auto-detect" — "consume" is too noisy).
          const src = String(p.source ?? "");
          if ((src === "rpc" || src === "auto-detect") && remaining > 0) {
            dispatch({
              type: "pushToast",
              kind: "info",
              text: `skip confirmation: ${remaining} round${remaining === 1 ? "" : "s"}`,
            });
          }
          // when the source is "consume" the user just
          // burned a skip-round. Bump the consumed counter in
          // the local stats so the footer reflects it without
          // waiting for the next getState poll.
          if (src === "consume") {
            dispatch({
              type: "setSkipStats",
              consumed: stateRef.current.skipStats.consumed + 1,
              armed:    stateRef.current.skipStats.armed,
              prompts:  stateRef.current.skipStats.prompts,
            });
          }
          break;
        }
        case "skip_low": {
          // skip-low notification. The daemon fires
          // this ONCE per session when the per-session skip
          // counter crosses DOWN through the waterline. The
          // TUI shows a 5-second "low!" badge so the user
          // knows to re-arm.
          const p = (params ?? {}) as { sessionId?: string; remaining?: number };
          const sid = String(p.sessionId ?? "");
          const remaining = Number(p.remaining ?? 0);
          if (sid) {
            dispatch({
              type: "setLastSkipLow",
              snap: { sessionId: sid, remaining, atMs: Date.now() },
            });
            // also push a toast so the user is alerted
            // even if the StatusBar is offscreen (small TUI
            // window, or scrollback covers the footer).
            dispatch({
              type: "pushToast",
              kind: "warn",
              text: `⏩ skip running low (${remaining} left) — /skip 50 to re-arm`,
            });
          }
          break;
        }
        default:
          dispatch({
            type: "sideNote",
            kind: "rpc",
            message: `${method}: ${JSON.stringify(params)}`,
          });
      }
    });
  }, [client]);

  useEffect(() => {
    (async () => {
      try {
        const st = (await client.request("getState")) as Record<string, unknown> | null | undefined;
        const stObj = (st ?? {}) as Record<string, unknown>;
        dispatch({
          type: "init",
          sessionId: String(stObj.sessionId ?? "—"),
          model: String(stObj.model ?? "—"),
          permissionMode: String(stObj.permissionMode ?? "ACCEPT_TASK"),
          jarPath: client.jarPath,
          // R344: surface the model's context window so the
          // Header can render a fill glyph + percentage. The
          // daemon's getState returns contextWindow as a
          // number; we coerce defensively.
          contextWindow: Number(stObj.contextWindow ?? 0) || 0,
          // R344: provider is reserved for the daemon's future
          // getProviders RPC. Today the wire doesn't carry it,
          // so we leave it undefined.
          provider: typeof stObj.provider === "string" ? stObj.provider : undefined,
        });

        // R364 — reconcile the launch directory against the DAEMON's.
        //
        // The `cwd` prop is where the TUI was started, which is not
        // necessarily where the engine is rooted (the desktop spawns
        // the daemon with its own `--cwd`, and a daemon restarted by
        // someone else keeps its binding). Until this, every cwd
        // display showed the launch directory for the whole session,
        // and the four `sdd.*` calls sent that wrong path to the
        // daemon.
        //
        // `listProjects` is the read that already carries the truth —
        // it returns `{cwd, name, active}` with `active: true` on the
        // current binding (`AetherCodeMethods.listProjects`), and
        // `panelCwdPick` already depends on that shape.
        void (async () => {
          try {
            const projects = (await client.request("listProjects")) as
              | Array<{ cwd?: string; active?: boolean }>
              | null
              | undefined;
            const current = Array.isArray(projects)
              ? projects.find((p) => p?.active) ?? projects[0]
              : undefined;
            const daemonCwd = String(current?.cwd ?? "").trim();
            if (daemonCwd) dispatch({ type: "setCurrentCwd", cwd: daemonCwd });
          } catch {
            // Best-effort. The launch prop is still the fallback, so a
            // daemon without `listProjects` still shows a sensible cwd.
          }
        })();
        // seed the skip-confirmation counter from
        // getState (R98 default-skip-from-config may have armed
        // a non-zero value at engine boot).
        const skipInitial = Number(stObj.skipConfirmationRemaining ?? 0);
        if (skipInitial > 0) {
          dispatch({ type: "setSkipConfirmationRemaining", remaining: skipInitial });
        }
        // seed skip-confirmation adoption stats. The
        // daemon reports consumed / armed / prompts so we
        // can show "skip: 4/7 prompts used" in the footer.
        const st2 = stObj.skipStats as { consumed?: number; armed?: number; prompts?: number } | undefined;
        if (st2 && typeof st2 === "object") {
          dispatch({
            type: "setSkipStats",
            consumed: Number(st2.consumed ?? 0),
            armed:    Number(st2.armed ?? 0),
            prompts:  Number(st2.prompts ?? 0),
          });
        }
        // seed the waterline + last-skip-low snapshot
        // from getState. A reconnecting client (after a TUI
        // crash or daemon restart) should still see the
        // "low!" badge if the snapshot is recent.
        const w = Number((stObj as Record<string, unknown>).skipLowWaterline ?? 0);
        if (Number.isFinite(w)) {
          dispatch({ type: "setSkipLowWaterline", waterline: w });
        }
        const lsl = (stObj as Record<string, unknown>).skipLow as
          { sessionId?: string; remaining?: number; atMs?: number } | null | undefined;
        if (lsl && typeof lsl === "object" && lsl.sessionId) {
          dispatch({
            type: "setLastSkipLow",
            snap: {
              sessionId: String(lsl.sessionId),
              remaining: Number(lsl.remaining ?? 0),
              atMs:      Number(lsl.atMs ?? Date.now()),
            },
          });
        }
        // seed the permission-mode suggestion. The
        // // `currentMode` is also seeded by the existing
        // dispatch above; we only need the suggestion.
        const pms = (stObj as Record<string, unknown>).permissionModeSuggestion as
          { mode?: string; reasons?: string[] } | null | undefined;
        if (pms && typeof pms === "object" && pms.mode) {
          dispatch({
            type: "setPermissionModeSuggestion",
            suggestion: {
              mode: String(pms.mode),
              reasons: Array.isArray(pms.reasons) ? pms.reasons.map(String) : [],
            },
          });
        }
        // R347: REMOVED the "silently switch to ACCEPT_TASK on first
        // connect" code path. The previous behaviour overwrote
        // whatever mode the daemon was already in (the user's
        // pinned mode from a prior session, or DEFAULT) without
        // asking, then toasted "permission mode → ACCEPT_TASK"
        // — effectively hijacking the user's choice. Senior PM
        // (R347 review) flagged this as the most-loathed current
        // behaviour: "products doing things behind the user's
        // back".
        //
        // The replacement behaviour is purely *read*: we already
        // dispatch `setPermissionMode` above when `engineState`
        // reports the daemon's mode. The user picks the mode
        // explicitly via `/mode <name>` or the slash-menu
        // autocomplete. If the user wants ACCEPT_TASK semantics
        // ("no mid-task stops") they can set it with one
        // keystroke: `/mode ACCEPT_TASK`. The Welcome card tip
        // row already advertises this.
        //
        // We deliberately do NOT call setPermissionMode here.
        // This is the only correct behaviour: the daemon owns
        // the persisted mode, the client only mirrors it.
      } catch (e) {
        dispatch({ type: "log", message: `init failed: ${(e as Error).message}` });
      }
    })();
  }, [client]);

  useEffect(() => {
    return () => {
      client.stop().catch(() => undefined);
    };
  }, [client]);

  // when the sidebar is visible, fetch the task list once on
  // mount and refresh every 30s. The user can hide the panel and
  // the polling stops naturally.
  useEffect(() => {
    if (!state.sidebarVisible) return;
    let cancelled = false;
    const fetchTasks = async () => {
      try {
        const list = (await client.request("listTasks", { limit: 10 })) as Array<{ id: string; name: string; status: string; ts: number }> | null | undefined;
        if (!cancelled && Array.isArray(list)) {
          dispatch({ type: "setRecentTasks", tasks: list });
        }
      } catch { /* ignore — task list is best-effort */ }
    };
    fetchTasks();
    const id = setInterval(fetchTasks, 30_000);
    return () => { cancelled = true; clearInterval(id); };
  }, [state.sidebarVisible, client]);

  // R362 — fetch the open panel's data.
  //
  // One effect for all nine panels, keyed on `activePanel` plus
  // `memoryTab` (the memory panel re-fetches on a tab switch). Only
  // the panels that actually issue an RPC set `panelStatus.loading`;
  // the rest resolve synchronously so the shell never flashes a
  // spinner over a local dataset.
  useEffect(() => {
    const panel = state.activePanel;
    if (!panel) return;
    let cancelled = false;
    const begin = () =>
      dispatch({ type: "setPanelStatus", status: { loading: true, error: null, notice: null } });
    const done = () => {
      if (!cancelled) {
        dispatch({ type: "setPanelStatus", status: { loading: false, error: null, notice: null } });
      }
    };
    const fail = (what: string, e: unknown) => {
      if (cancelled) return;
      const msg = e instanceof Error ? e.message : String(e);
      dispatch({
        type: "setPanelStatus",
        status: { loading: false, error: `${what} failed: ${msg}`, notice: null },
      });
    };
    const notice = (n: string) => {
      if (!cancelled) {
        dispatch({ type: "setPanelStatus", status: { loading: false, error: null, notice: n } });
      }
    };

    if (panel === "memory") {
      // Each scope loads independently: a dead USER scope must not
      // blank the PROJECT tab the user is actually looking at.
      (["global", "project", "session"] as MemoryScopeId[]).forEach((scope) => {
        dispatch({
          type: "setMemorySlice",
          scope,
          // Fresh slice, NOT `{...state.memoryScopes[scope]}`:
          // reading it here would put `memoryScopes` in the dep
          // array, and the dispatch below would re-trigger this
          // effect forever.
          slice: { entries: [], totalTokens: 0, source: "file", loading: true, error: null },
        });
        void client
          .request("memory/list", {
            scope: MEMORY_WIRE_SCOPE[scope],
            sessionId: state.sessionId,
          } as RpcValue)
          .then((raw) => {
            if (cancelled) return;
            const envErr = envelopeError(raw);
            const r = (raw ?? {}) as { entries?: unknown[]; totalTokens?: number };
            dispatch({
              type: "setMemorySlice",
              scope,
              slice: {
                entries: envErr
                  ? []
                  : (Array.isArray(r.entries) ? r.entries : []).map(normalizeMemoryRow),
                totalTokens: Number(r.totalTokens ?? 0),
                source: "file",
                loading: false,
                error: envErr,
              },
            });
          })
          .catch((e: unknown) => {
            if (cancelled) return;
            dispatch({
              type: "setMemorySlice",
              scope,
              slice: {
                entries: [],
                totalTokens: 0,
                source: "file",
                loading: false,
                error: e instanceof Error ? e.message : String(e),
              },
            });
          });
      });
      return () => { cancelled = true; };
    }

    if (panel === "threads") {
      begin();
      void client
        .request("listSessions", {} as RpcValue)
        .then((raw) => {
          if (cancelled) return;
          const envErr = envelopeError(raw);
          if (envErr) {
            fail("listSessions", envErr);
            dispatch({ type: "setThreads", threads: [] });
            return;
          }
          // `listSessions` has returned both a bare array and
          // `{sessions: [...]}` over its lifetime; accept both.
          const arr = Array.isArray(raw)
            ? raw
            : ((raw as { sessions?: unknown[] })?.sessions ?? []);
          const now = stateRef.current.panelOpenedAt || Date.now();
          const all = (arr as unknown[]).map((r, i) => normalizeThreadRow(r, i, now));
          // R370 — `/sessions` is "this project's sessions", so the
          // row list is scoped to the active cwd. `/threads` keeps
          // everything and lets the picker offer `switchProject`.
          //
          // The ATTRIBUTED count goes in `threads` and the rest is
          // reported as a notice rather than dropped: `listSessions`
          // omits `cwd` entirely for sessions it cannot attribute,
          // and a panel that quietly shows 0 rows next to `/threads`'s
          // 20 reads as a bug instead of an honest answer.
          const { mine, other } = partitionByCwd(all, (r) => r.cwd, currentCwd);
          const scoped = stateRef.current.panelCwdOnly;
          dispatch({ type: "setThreads", threads: scoped ? mine : all });
          if (scoped && other.length > 0) {
            setNotice(
              `${mine.length} of ${all.length} sessions are recorded under ` +
              `${cwdBasename(currentCwd) || currentCwd || "(no cwd)"} — ` +
              `the other ${other.length} have no recorded directory. ` +
              `Use /threads to see everything.`,
            );
          } else {
            done();
          }
        })
        .catch((e: unknown) => fail("listSessions", e));
      return () => { cancelled = true; };
    }

    if (panel === "agents") {
      begin();
      void client
        .request("listAgents", {} as RpcValue)
        .then((raw) => {
          if (cancelled) return;
          const envErr = envelopeError(raw);
          const arr = (raw as { agents?: unknown[] })?.agents;
          const agents: AgentEntry[] = envErr || !Array.isArray(arr)
            ? []
            : (arr as Record<string, unknown>[]).map((a) => ({
                name: String(a.name ?? a.displayName ?? "?"),
                description: a.description == null ? undefined : String(a.description),
                isDefault: Boolean(a.isDefault),
              }));
          dispatch({ type: "setAgents", agents });
          dispatch({
            type: "setDefaultAgent",
            name: (raw as { defaultAgent?: string })?.defaultAgent ?? null,
          });
          if (envErr) fail("listAgents", envErr);
          else done();
        })
        .catch((e: unknown) => fail("listAgents", e));
      return () => { cancelled = true; };
    }

    if (panel === "grants") {
      begin();
      void client
        .request("permission/list", {} as RpcValue)
        .then((raw) => {
          if (cancelled) return;
          const envErr = envelopeError(raw);
          if (envErr) {
            dispatch({ type: "setGrants", grants: [] });
            fail("permission/list", envErr);
            return;
          }
          const arr = Array.isArray(raw)
            ? raw
            : ((raw as { grants?: unknown[] })?.grants ?? []);
          dispatch({ type: "setGrants", grants: (arr as unknown[]).map(normalizeGrantRow) });
          done();
        })
        .catch((e: unknown) => fail("permission/list", e));
      return () => { cancelled = true; };
    }

    if (panel === "mcp") {
      // No MCP RPC exists on the daemon. Rather than render an
      // empty viewer that reads like "you have no servers", say so
      // explicitly — a wrong empty state is worse than a gap.
      notice("this daemon exposes no MCP RPC yet — /mcp shows the viewer shell only");
    }
    // "todos", "cwd", "effort" and "notifications" are served from
    // local state; nothing to fetch, so nothing to await.
    return () => { cancelled = true; };
  // R370: `state.panelCwdOnly` is a DEP, not an afterthought.
  // `/threads` and `/sessions` both resolve to `activePanel ===
  // "threads"`, so opening one straight after closing the other can
  // leave `activePanel` unchanged while the filter flips — without
  // this dep the panel would re-render showing the previous
  // command's row list with the new command's title.
  }, [state.activePanel, state.memoryTab, state.sessionId, state.panelCwdOnly, client]);

  useEffect(() => {
    // TUI flicker fix (R-2026-09-13-R-tui-flicker): the
    // `process.stdout.on("resize", ...)` handler was firing on
    // every ANSI re-render in Windows + Ink 5.x. Ink writes
    // cursor-position / clear-line sequences to redraw the
    // frame; the Windows console treats each write as a
    // potential buffer-shape change and re-emits `"resize"`
    // even when the actual `rows` count did not change. Each
    // spurious `resize` triggered `setH(...)` → React
    // re-render → Ink redraws → spurious resize → setH
    // → ...  a tight feedback loop the user reported as
    // "screen flashes, can't read a thing".
    //
    // The fix has two parts:
    //   1. A 80 ms debounce so a burst of resize events (one
    //      per Ink redraw) collapses into a single setH.
    //   2. A `lastH` ref guard so we only setH when the
    //      computed height actually changed — most resize
    //      events are no-ops on `rows` and now drop on the
    //      floor.
    let lastH = 20;
    let debounceId: ReturnType<typeof setTimeout> | null = null;
    const apply = (rows: number) => {
      const next = Math.max(5, rows - 6);
      if (next === lastH) return;
      lastH = next;
      setH(next);
    };
    const onResize = () => {
      if (debounceId !== null) clearTimeout(debounceId);
      debounceId = setTimeout(() => {
        debounceId = null;
        apply(process.stdout.rows ?? 24);
      }, 80);
    };
    // initial sync without going through the debounce —
    // the first frame is needed immediately so the layout
    // doesn't pop on mount.
    apply(process.stdout.rows ?? 24);
    process.stdout.on("resize", onResize);
    return () => {
      if (debounceId !== null) clearTimeout(debounceId);
      process.stdout.off("resize", onResize);
    };
  }, []);

  async function handleSubmit(text: string): Promise<void> {
    // R362 — the ordering here was the actual bug, not just the toast.
    //
    // Before: `if (state.submitting) { toast(...); return; }` sat at the
    // TOP of the function, *above* the slash handling — so despite the
    // comment claiming "Slash commands stay local to the TUI and don't need
    // a roundtrip, so we still run them even if the model is busy", a
    // streaming run silently swallowed every slash command too.
    //
    // Two real defects, one place:
    //   1. slash commands were blocked while streaming (contradicts the comment)
    //   2. prompts were dropped while streaming, after a toast claimed "queued"
    //
    // Now: slash first (local, no roundtrip, always allowed), then queue.
    const slash = handleSlash(text);
    if (slash?.local === "__EXIT__") {
      dispatch({ type: "exit" });
      exit();
      return;
    }
    if (slash?.local === "__DEMO__") {
      // R344: walk through a canned conversation that exercises
      // every visual surface. Dispatches happen on a timer so
      // the user can see each surface paint before the next
      // one lands. No RPC, no model round-trip.
      //
      // R350 (PM P1-4): the demo's permission step is now a
      // REAL wait — `runDemo` polls `stateRef.current.permissionAsk`
      // and only continues once the user has pressed 1/2/3/4 (or
      // A/Y/D/N). Earlier rounds faked the answer via a
      // setTimeout, which defeated the point of demonstrating
      // the prompt UX.
      void runDemo(dispatch, client, stateRef);
      return;
    }
    if (slash?.local === "__CLEAR__") {
      dispatch({ type: "log", message: "(clear is a no-op in this build; restart for a fresh scrollback)" });
      return;
    }
    if (slash?.local === "__HISTORY__") {
      const lines = state.history.length === 0
        ? "(empty)"
        : state.history.map((h, i) => `${String(i + 1).padStart(3, " ")}  ${h}`).join("\n");
      dispatch({ type: "sideNote", kind: "history", message: lines });
      return;
    }
    if (slash?.local?.startsWith("__THEME__:")) {
      // /theme NAME — switch the active color palette.
      const name = slash.local.slice("__THEME__:".length) as "default" | "solarized" | "monokai";
      dispatch({ type: "setTheme", theme: name });
      dispatch({ type: "pushToast", kind: "info", text: `theme → ${name}` });
      return;
    }
    if (slash?.local === "__THEMES_PICK__") {
      // T-422: /theme-pick — open the aethercode-themes picker
      // (separate from the legacy 5-palette /theme command).
      dispatch({ type: "setThemePickerOpen", open: true });
      return;
    }
    if (slash?.local?.startsWith("__LAYOUT__:")) {
      // /layout NAME — switch the layout preset.
      const name = slash.local.slice("__LAYOUT__:".length) as "full" | "minimal" | "focus";
      dispatch({ type: "setLayout", layout: name });
      dispatch({ type: "pushToast", kind: "info", text: `layout → ${name}` });
      return;
    }
    if (slash?.local?.startsWith("__BUDGET__:")) {
      // /budget USD — set the cost budget for the progress bar.
      const usd = Number(slash.local.slice("__BUDGET__:".length));
      dispatch({ type: "setCostBudget", usd });
      dispatch({ type: "pushToast", kind: "info", text: usd === 0 ? "budget off" : `budget = $${usd.toFixed(2)}` });
      return;
    }
    if (slash?.local === "__TUTORIAL__") {
      // /tutorial — re-show the welcome overlay.
      dispatch({ type: "setTutorialOpen", open: true });
      return;
    }
    if (slash?.local === "__BOOKMARK_LAST__") {
      // /bookmark — bookmark the most recent turn.
      const last = state.turns[state.turns.length - 1];
      if (!last) {
        dispatch({ type: "pushToast", kind: "warn", text: "no turns to bookmark" });
        return;
      }
      dispatch({ type: "toggleBookmark", id: last.id });
      dispatch({ type: "pushToast", kind: "ok", text: `bookmarked turn #${last.id}` });
      return;
    }
    if (slash?.local?.startsWith("__BOOKMARK__:")) {
      // /bookmark N — bookmark turn by 1-based index.
      const n = Number(slash.local.slice("__BOOKMARK__:".length));
      const target = state.turns[n - 1];
      if (!target) {
        dispatch({ type: "pushToast", kind: "err", text: `turn #${n} not found` });
        return;
      }
      dispatch({ type: "toggleBookmark", id: target.id });
      dispatch({ type: "pushToast", kind: "ok", text: `bookmarked turn #${n} (id ${target.id})` });
      return;
    }
    if (slash?.local === "__SNIPPET_LIST__") {
      // /snippet list — show the saved snippets.
      const names = Object.keys(state.snippets);
      if (names.length === 0) {
        dispatch({ type: "sideNote", kind: "snippet", message: "(no snippets yet — /snippet save <name> to save the current input)" });
      } else {
        const lines = names.sort().map((n) => `  ${n.padEnd(20, " ")}  ${state.snippets[n].slice(0, 50)}${state.snippets[n].length > 50 ? "…" : ""}`);
        dispatch({ type: "sideNote", kind: "snippet", message: lines.join("\n") });
      }
      return;
    }
    if (slash?.local?.startsWith("__SNIPPET_SAVE__:")) {
      // /snippet save NAME — save the current input.
      const name = slash.local.slice("__SNIPPET_SAVE__:".length);
      if (!state.input.trim()) {
        dispatch({ type: "pushToast", kind: "warn", text: "no input to save" });
        return;
      }
      dispatch({ type: "saveSnippet", name, body: state.input });
      dispatch({ type: "pushToast", kind: "ok", text: `saved snippet "${name}"` });
      return;
    }
    if (slash?.local?.startsWith("__SNIPPET_LOAD__:")) {
      // /snippet load NAME — replace the current input.
      const name = slash.local.slice("__SNIPPET_LOAD__:".length);
      const body = state.snippets[name];
      if (body == null) {
        dispatch({ type: "pushToast", kind: "err", text: `snippet "${name}" not found` });
        return;
      }
      dispatch({ type: "setInput", text: body });
      dispatch({ type: "pushToast", kind: "ok", text: `loaded snippet "${name}"` });
      return;
    }
    if (slash?.local?.startsWith("__SNIPPET_DELETE__:")) {
      // /snippet delete NAME.
      const name = slash.local.slice("__SNIPPET_DELETE__:".length);
      if (state.snippets[name] == null) {
        dispatch({ type: "pushToast", kind: "err", text: `snippet "${name}" not found` });
        return;
      }
      dispatch({ type: "deleteSnippet", name });
      dispatch({ type: "pushToast", kind: "ok", text: `deleted snippet "${name}"` });
      return;
    }
    if (slash?.local?.startsWith("__EXPORT_MD__:") || slash?.local?.startsWith("__EXPORT_JSON__:")) {
      // R347: `/export` and `/export json` (no path) now fall
      // back to `<cwd>/.aethercode/exports/session-<id>-<YYYYMMDD-HHMM>.{md,json}`
      // so the user doesn't have to type a path. The helper
      // mkdir -p's the parent directory before writeFileSync
      // (writeFileSync throws ENOENT on a missing dir).
      const isJson = slash.local.startsWith("__EXPORT_JSON__:");
      const userPath = slash.local.slice(isJson ? "__EXPORT_JSON__:".length : "__EXPORT_MD__:".length);
      void (async () => {
        try {
          const { exportToMarkdown, exportToJson, exportWithFrontmatter, defaultExportPath, writeExport } = await import("./exportScrollback.js");
          const { mkdirSync } = await import("node:fs");
          const path = userPath && userPath.length > 0
            ? userPath
            : defaultExportPath(currentCwd, state.sessionId, isJson ? "json" : "md");
          // Ensure the exports/ directory exists. mkdirSync with
          // { recursive: true } is a no-op when the directory
          // already exists, so this is safe to call every time.
          mkdirSync(path.replace(/[\\/][^\\/]+$/, ""), { recursive: true });
          // R347: markdown exports get a YAML frontmatter
          // (session / model / turns / exported_at / cwd / cost)
          // so the pasted document is self-describing in GitHub
          // PRs / Obsidian. JSON stays raw so tooling can
          // round-trip it.
          const body = isJson
            ? exportToJson(state.turns)
            : exportWithFrontmatter(state.turns, state.model, state.sessionId, { cwd: currentCwd });
          writeExport(path, body);
          dispatch({ type: "pushToast", kind: "ok", text: `exported ${state.turns.length} turns → ${path}` });
        } catch (e) {
          dispatch({ type: "pushToast", kind: "err", text: `export failed: ${(e as Error).message}` });
        }
      })();
      return;
    }
    if (slash?.local === "__BANK_STATS__") {
      // R245.1: read the daemon's strategy bank and dispatch a
      // one-line sideNote. We use the same fire-and-forget
      // pattern as /export: lazy-import aethercode-memory's
      // bank-recall wrapper, call readBankStats() in a void
      // async, and surface the summary text via sideNote so
      // the user can see it in the next render tick. If the
      // daemon is down, the helper already returns a
      // "bank: down" line — no extra try/catch needed in the
      // dispatcher.
      void (async () => {
        try {
          const { readBankStats } = await import("aethercode-memory");
          const summary = await readBankStats();
          dispatch({ type: "sideNote", kind: "bank", message: summary.text });
        } catch (e) {
          dispatch({ type: "sideNote", kind: "bank", message: `bank: error (${(e as Error).message})` });
        }
      })();
      return;
    }
    if (slash?.local?.startsWith("__BANK_RECALL__:")) {
      // R245.1: same fire-and-forget as /bank-stats but with
      // a (kind, n) payload. The local token shape is
      // `__BANK_RECALL__:<kind>:<n>`. We don't URL-decode
      // either field — kinds are short ASCII identifiers and
      // n is always an integer, both opaque strings that the
      // daemon parses server-side.
      const body = slash.local.slice("__BANK_RECALL__:".length);
      const colonAt = body.lastIndexOf(":");
      const kind = colonAt >= 0 ? body.slice(0, colonAt) : body;
      const n = colonAt >= 0 ? Math.max(1, Number(body.slice(colonAt + 1)) || 3) : 3;
      void (async () => {
        try {
          const { readBankRecall } = await import("aethercode-memory");
          const summary = await readBankRecall(kind, n);
          dispatch({ type: "sideNote", kind: "bank", message: summary.text });
        } catch (e) {
          dispatch({ type: "sideNote", kind: "bank", message: `bank: error (${(e as Error).message})` });
        }
      })();
      return;
    }
    if (slash?.local === "__MEMORY_AUDIT__") {
      // R245.2: cross-process self-eval audit. Pulls the
      // bank stats + every unit, aggregates per-kind success
      // rate + Laplace-smoothed avg confidence, and renders
      // a 1-screen summary. Fire-and-forget — same pattern
      // as /bank-stats and /export. On failure the helper
      // already returns a "down" line; we just need a
      // safety net for unexpected throws.
      void (async () => {
        try {
          const { auditSelfEval } = await import("aethercode-memory");
          const summary = await auditSelfEval();
          dispatch({ type: "sideNote", kind: "memoryAudit", message: summary.text });
        } catch (e) {
          dispatch({ type: "sideNote", kind: "memoryAudit", message: `memory-audit: error (${(e as Error).message})` });
        }
      })();
      return;
    }
    // R700 — SDD product integration. The TUI dispatches
    // the sdd.* RPCs and mirrors the returned state into
    // store.sddRun so SddMode.tsx re-renders. The RPC
    // parameters come from the slash handler (commands.ts);
    // we only need the slug here for advance/abort/status,
    // which we read from store.sddRun if the user didn't pass
    // it explicitly. cwd is the daemon-side current
    // working directory from the engine (read from store).
    if (slash?.local === "__SDD_START__") {
      const intent = String((slash.rpcParams as { intent?: unknown } | undefined)?.intent ?? "");
      if (!intent) {
        dispatch({ type: "sideNote", kind: "error", message: "sdd.start: missing intent" });
        return;
      }
      void (async () => {
        try {
          const resp = await client.request<{ ok: boolean; state?: SddRunState; error?: string }>(
            "sdd.start", { intent, cwd: currentCwd } as RpcValue);
          if (!resp.ok || !resp.state) {
            dispatch({ type: "sideNote", kind: "error", message: `sdd.start failed: ${resp.error ?? "unknown"}` });
            return;
          }
          dispatch({ type: "sddRun.set", state: resp.state });
          dispatch({ type: "sideNote", kind: "info",
            message: `📐 SDD started · slug=${resp.state.slug} · phase 1/8 · ${resp.state.phases[0]?.title ?? "?"}` });
        } catch (e) {
          dispatch({ type: "sideNote", kind: "error", message: `sdd.start error: ${(e as Error).message}` });
        }
      })();
      return;
    }
    if (slash?.local === "__SDD_ADVANCE__") {
      const rpcAction = String((slash.rpcParams as { action?: unknown } | undefined)?.action ?? "approve");
      const feedback = String((slash.rpcParams as { feedback?: unknown } | undefined)?.feedback ?? "");
      const sddSlug = state.sddRun?.slug;
      if (!sddSlug) {
        dispatch({ type: "sideNote", kind: "error", message: "sdd.advance: no active run (run /sdd <intent> first)" });
        return;
      }
      void (async () => {
        try {
          const resp = await client.request<{ ok: boolean; state?: SddRunState; error?: string; code?: string }>(
            "sdd.advance",
            { slug: sddSlug, action: rpcAction, feedback: feedback || undefined, cwd: currentCwd } as RpcValue);
          if (!resp.ok || !resp.state) {
            dispatch({ type: "sideNote", kind: "error",
              message: `sdd.advance failed (${resp.code ?? "UNKNOWN"}): ${resp.error ?? "unknown"}` });
            return;
          }
          dispatch({ type: "sddRun.set", state: resp.state });
          const ph = resp.state.currentPhase ?? resp.state.phases.length;
          dispatch({ type: "sideNote", kind: "info",
            message: `📐 SDD ${rpcAction} · now at phase ${ph}/${resp.state.phases.length}` });
        } catch (e) {
          dispatch({ type: "sideNote", kind: "error", message: `sdd.advance error: ${(e as Error).message}` });
        }
      })();
      return;
    }
    if (slash?.local === "__SDD_ABORT__") {
      const sddSlug = state.sddRun?.slug;
      if (!sddSlug) {
        dispatch({ type: "sideNote", kind: "error", message: "sdd.abort: no active run" });
        return;
      }
      void (async () => {
        try {
          await client.request("sdd.abort", { slug: sddSlug, cwd: currentCwd } as RpcValue);
          dispatch({ type: "sddRun.clear" });
          dispatch({ type: "sideNote", kind: "info", message: "📐 SDD run aborted" });
        } catch (e) {
          dispatch({ type: "sideNote", kind: "error", message: `sdd.abort error: ${(e as Error).message}` });
        }
      })();
      return;
    }
    if (slash?.local === "__SDD_STATUS__") {
      const sddSlug = state.sddRun?.slug;
      if (!sddSlug) {
        dispatch({ type: "sideNote", kind: "info", message: "sdd.status: no active run" });
        return;
      }
      void (async () => {
        try {
          const resp = await client.request<{ ok: boolean; state?: SddRunState; error?: string }>(
            "sdd.status", { slug: sddSlug, cwd: currentCwd } as RpcValue);
          if (!resp.ok || !resp.state) {
            dispatch({ type: "sideNote", kind: "error",
              message: `sdd.status failed: ${resp.error ?? "unknown"}` });
            return;
          }
          dispatch({ type: "sddRun.set", state: resp.state });
          const ph = resp.state.currentPhase ?? resp.state.phases.length;
          dispatch({ type: "sideNote", kind: "info",
            message: `📐 SDD · slug=${resp.state.slug} · ${ph}/${resp.state.phases.length} · status=${resp.state.status}` });
        } catch (e) {
          dispatch({ type: "sideNote", kind: "error", message: `sdd.status error: ${(e as Error).message}` });
        }
      })();
      return;
    }
    // R700d — /sdd-view [N] shows the artefact on disk (first
    // 80 lines) + a file:// link. The artefact path comes from
    // sddRun.phases[N-1].path, which the daemon populates when
    // it emits the SddRunState over RPC. When no phase is given
    // we default to the run's currentPhase.
    if (slash?.local === "__SDD_VIEW__" || slash?.local === "__SDD_DIFF__" || slash?.local === "__SDD_SNAPSHOT__") {
      const sddRun = state.sddRun;
      if (!sddRun) {
        dispatch({ type: "sideNote", kind: "error", message: "sdd: no active run" });
        return;
      }
      const phaseParam = (slash.rpcParams as { phase?: number | null } | undefined)?.phase;
      const phaseIdx =
        phaseParam && phaseParam >= 1 && phaseParam <= sddRun.phases.length
          ? phaseParam - 1
          : (sddRun.currentPhase ?? 1) - 1;
      const phase = sddRun.phases[phaseIdx];
      if (!phase || !phase.path) {
        dispatch({ type: "sideNote", kind: "error",
          message: `sdd: phase ${phaseIdx + 1} has no artefact yet` });
        return;
      }
      const path = phase.path;
      void (async () => {
        try {
          const fs = await import("node:fs");
          const lines = fs.readFileSync(path, "utf-8").split(/\r?\n/);
          if (slash?.local === "__SDD_SNAPSHOT__") {
            dispatch({ type: "sddCache.set", phase: phaseIdx + 1, lines });
            dispatch({ type: "sideNote", kind: "info",
              message: `📐 cached snapshot for phase ${phaseIdx + 1} (${lines.length} lines)` });
            return;
          }
          const cached = state.sddCache[phaseIdx + 1] ?? null;
          setSddReview({ path, lines, previousLines: slash.local === "__SDD_DIFF__" ? cached : null });
          dispatch({ type: "sideNote", kind: "info",
            message: `📂 viewing ${path} (${lines.length} lines${cached ? `, diff vs cached ${cached.length} lines` : ""})` });
        } catch (e) {
          dispatch({ type: "sideNote", kind: "error",
            message: `sdd read ${path}: ${(e as Error).message}` });
        }
      })();
      return;
    }
    if (slash?.local === "__SDD_CLEAR_CACHE__") {
      dispatch({ type: "sddCache.clear" });
      setSddReview(null);
      dispatch({ type: "sideNote", kind: "info", message: "📐 cleared sdd cache + viewer" });
      return;
    }
    // R362 — panel claim. Lookup goes through `panelIntents.ts`
    // instead of a hand-written `if (local === "__X__")` chain, so
    // a sentinel emitted by `commands.ts` can never reach the
    // fallback below without first being given a declared owner.
    //
    // `bareSentinel` strips the `:<arg>` suffix (`__MCP_LOGIN__:gh`)
    // so the registry's bare-token keys resolve. Four intents have
    // no component (`__UPDATE_*__`, `__WORKFLOW_PICKER__`,
    // `__CONTEXT_METER__`) and deliberately fall through to the
    // human-readable fallback.
    const panelLocal = slash?.local;
    if (panelLocal) {
      const intent = intentForSentinel(bareSentinel(panelLocal));
      const panelId = intent ? PANEL_BY_INTENT[intent.id] : undefined;
      if (panelId) {
        // R370: cwd-scoped intents (today: `session-picker` =
        // `/sessions`) get their filter flag here, once, at the only
        // place a panel is opened. Every other consumer reads
        // `state.panelCwdOnly` and does not re-derive it, so the
        // filter cannot disagree between the fetch and the header.
        dispatch({
          type: "openPanel",
          id: panelId,
          cwdOnly: intent ? CWD_SCOPED_INTENTS.has(intent.id) : false,
        });
        return;
      }
    }
    if (slash?.local) {
      // R362: the fallback must be a SENTENCE, never the raw
      // token. Pre-R362 this printed `__MCP_VIEWER__` straight to
      // the user; `describeSentinel` resolves the intent and
      // renders its title plus why it is not available.
      //
      // `describeSentinel` returns its argument UNCHANGED when the
      // token has no registry entry (a sentinel the registry does
      // not own, e.g. `__PICK_MODE__`). Passing that through would
      // re-introduce exactly the leak this branch exists to close,
      // so anything still shaped like a token gets a fixed
      // sentence instead.
      const bare = bareSentinel(slash.local);
      const described = describeSentinel(bare, "ink");
      dispatch({
        type: "sideNote",
        kind: "info",
        message: described.includes("__")
          ? "no panel for that command in the TUI yet — /help lists what is supported"
          : described,
      });
      return;
    }
    if (slash?.rpcMethod) {
      try {
        const result = await client.request<unknown>(slash.rpcMethod, slash.rpcParams as RpcValue);
        // when the user lands in a new permission mode (via
        // /mode or /no-confirm) show a confirmation toast so they
        // can verify the switch took effect — the status bar will
        // also re-render with the new mode but only on the next
        // event, and we don't want them to wonder why prompts
        // stopped / started.
        if (slash.rpcMethod === "setPermissionMode") {
          const newMode = String((slash.rpcParams as { mode?: unknown } | undefined)?.mode ?? "");
          if (newMode) {
            dispatch({ type: "setPermissionMode", mode: newMode });
            const blurb = newMode === "BYPASS_PERMISSIONS"
              ? "no prompts will be shown for any tool call"
              : newMode === "ACCEPT_TASK"
              ? "single task, no mid-task prompts (DEFAULT behaviour unless sub-task boundary)"
              : newMode === "ACCEPT_EDITS"
              ? "file edits auto-allowed; bash / network still ask"
              : newMode === "AUTO_READ_ONLY"
              ? "read-only tools auto-allowed; writes still ask"
              : newMode === "PLAN"
              ? "plan mode — tools ask before running"
              : "default — every tool call asks before running";
            dispatch({
              type: "pushToast",
              kind: newMode === "DEFAULT" ? "info" : "ok",
              text: `mode → ${newMode}: ${blurb}. Run /mode <name> to change again.`,
            });
          }
        }
        // pretty-print the metrics snapshot. For other
        // RPCs we fall back to a plain JSON dump.
        if (slash.rpcMethod === "getMetrics" && result && typeof result === "object") {
          const m = result as Record<string, unknown>;
          const fmt = (n: unknown) => typeof n === "number" ? n.toFixed(3) : String(n);
          const lines: string[] = [];
          lines.push("engine metrics");
          for (const k of Object.keys(m)) {
            const v = m[k];
            if (typeof v === "number" && (k === "errorRate" || k === "cacheHitRate")) {
              lines.push(`  ${k.padEnd(18, " ")}  ${fmt((v as number) * 100)}%`);
            } else if (typeof v === "number" && k === "costUsd") {
              lines.push(`  ${k.padEnd(18, " ")}  $${fmt(v)}`);
            } else {
              lines.push(`  ${k.padEnd(18, " ")}  ${v}`);
            }
          }
          dispatch({ type: "sideNote", kind: "metrics", message: lines.join("\n") });
        } else if (slash.rpcMethod === "getTraces" && result && typeof result === "object") {
          // pretty-print the trace snapshot. The shape is
          // {inFlight, completed, traces: [{traceId, name,
          // startMs, endMs, status, durationMs, attrs}, ...]}.
          // We surface the most recent N spans as a small table:
          // status icon + name + duration. We also push the result
          // into state.recentTraces so future /trace calls can
          // diff/refresh (and so other components could read it).
          const r = result as { inFlight?: number; completed?: number; traces?: Array<Record<string, unknown>> };
          const traces = Array.isArray(r.traces) ? r.traces : [];
          const inFlight = typeof r.inFlight === "number" ? r.inFlight : 0;
          const completed = typeof r.completed === "number" ? r.completed : 0;
          dispatch({
            type: "setRecentTraces",
            traces: traces as unknown as Array<import("./state.js").TraceSummary>,
            inFlight,
            completed,
          });
          const lines: string[] = [];
          lines.push(`traces (${traces.length} shown, ${completed} total, ${inFlight} in flight)`);
          for (const t of traces) {
            const name = String(t.name ?? "?");
            const status = String(t.status ?? "?");
            const dur = typeof t.durationMs === "number" ? t.durationMs : 0;
            const durStr = dur < 1000 ? `${dur}ms` : `${(dur / 1000).toFixed(2)}s`;
            const icon = status === "ok" ? "✓" : status === "error" ? "✗" : "·";
            lines.push(`  ${icon} ${name.padEnd(20, " ")}  ${durStr.padStart(8, " ")}  ${status}`);
          }
          if (traces.length === 0) {
            lines.push("  (no traces yet — run a query to populate)");
          }
          dispatch({ type: "sideNote", kind: "trace", message: lines.join("\n") });
        } else if (slash.rpcMethod === "getTrace" && result && typeof result === "object") {
          // render a single trace as a tree. The shape is
          // {traceId, inFlight, completed, spans: [{traceId,
          // name, parentSpanId, ...}, ...]}. We dispatch
          // setSpanTree so the state holds the structured tree,
          // AND we pretty-print the tree here with ├─/└─
          // connectors so the user sees it inline.
          const r = result as { traceId?: string; inFlight?: number; completed?: number; spans?: Array<Record<string, unknown>> };
          const spans = Array.isArray(r.spans) ? r.spans : [];
          const traceId = typeof r.traceId === "string" ? r.traceId : (slash.rpcParams as { traceId?: string } | undefined)?.traceId ?? "?";
          dispatch({
            type: "setSpanTree",
            traceId,
            spans: spans as unknown as Array<import("./state.js").TraceSummary>,
          });
          const lines: string[] = [];
          lines.push(`trace ${traceId} (${spans.length} spans, ${typeof r.completed === "number" ? r.completed : 0} total in recorder)`);
          if (spans.length === 0) {
            lines.push("  (empty — root evicted or unknown id)");
          } else {
            // Build children-by-parent map. The root is the span
            // whose traceId equals the requested id; orphans
            // (parent missing from the spans list) get rendered
            // at the top with no connectors. A node is an orphan
            // iff its parentSpanId is set AND its parent is not
            // in the spans list.
            interface TNode { id: string; name: string; status: string; dur: number; children: TNode[]; }
            const byId = new Map<string, TNode>();
            const childrenOf = new Map<string, TNode[]>();
            let rootNode: TNode | null = null;
            for (const s of spans) {
              const id = String(s.traceId ?? "?");
              const name = String(s.name ?? "?");
              const status = String(s.status ?? "?");
              const dur = typeof s.durationMs === "number" ? s.durationMs : 0;
              const node: TNode = { id, name, status, dur, children: [] };
              byId.set(id, node);
              if (id === traceId) rootNode = node;
            }
            for (const s of spans) {
              const id = String(s.traceId ?? "?");
              const parent = (s.parentSpanId as string | null | undefined) ?? null;
              const node = byId.get(id);
              if (!node) continue;
              // An "orphan" is any span whose parent is missing
              // from the spans list (regardless of whether the
              // parent's parentSpanId is null). We collect them
              // under the sentinel key "__orphans__" so we can
              // render them flat at the top.
              if (parent == null || !byId.has(parent)) {
                let arr = childrenOf.get("__orphans__");
                if (!arr) { arr = []; childrenOf.set("__orphans__", arr); }
                arr.push(node);
              } else {
                let arr = childrenOf.get(parent);
                if (!arr) { arr = []; childrenOf.set(parent, arr); }
                arr.push(node);
              }
            }
            // Wire children into every node (not just the root)
            // so grandchild nesting works.
            for (const [id, node] of byId) {
              node.children = childrenOf.get(id) ?? [];
            }
            // Emit. The order: requested root (if present),
            // then its descendants depth-first. Orphans (if the
            // root is missing) get a "(root not found)" header.
            const emit = (node: TNode, prefix: string, isLast: boolean) => {
              const icon = node.status === "ok" ? "✓" : node.status === "error" ? "✗" : "·";
              const durStr = node.dur < 1000 ? `${node.dur}ms` : `${(node.dur / 1000).toFixed(2)}s`;
              const connector = prefix === "" ? "" : (isLast ? "└─ " : "├─ ");
              lines.push(`  ${prefix}${connector}${icon} ${node.name.padEnd(20, " ")}  ${durStr.padStart(8, " ")}  ${node.status}`);
              const kids = node.children;
              for (let i = 0; i < kids.length; i++) {
                const k = kids[i];
                const childPrefix = prefix === "" ? "  " : (prefix + (isLast ? "   " : "│  "));
                emit(k, childPrefix, i === kids.length - 1);
              }
            };
            if (rootNode) {
              emit(rootNode, "", true);
            } else {
              lines.push(`  (root ${traceId} not in the recorder — showing orphans)`);
              const orphans = childrenOf.get("__orphans__") ?? [];
              for (let i = 0; i < orphans.length; i++) {
                emit(orphans[i], "", i === orphans.length - 1);
              }
            }
          }
          dispatch({ type: "sideNote", kind: "traceTree", message: lines.join("\n") });
        } else if (slash.rpcMethod === "listAgents" && result && typeof result === "object") {
          // prior round-sync: pretty-print the agent list as a
          // "name | model" table. The shape is
          // {ok, count, agents: [{name, description,
          // displayName, model, lastModifiedMs}, ...]}.
          // Empty / missing model column shows "(default)"
          // — same UX as the desktop AgentsPanel model
          // badge so the two surfaces match.
          const r = result as {
            count?: number;
            agents?: Array<{
              name?: string;
              description?: string;
              displayName?: string;
              model?: string;
            }>;
          };
          const agents = Array.isArray(r.agents) ? r.agents : [];
          const lines: string[] = [];
          lines.push(`agents (${r.count ?? agents.length})`);
          if (agents.length === 0) {
            lines.push("  (no agents — run the desktop editor to create one)");
          } else {
            const nameWidth = Math.max(
              "name".length,
              ...agents.map((a) => (a.displayName || a.name || "").length)
            );
            lines.push(`  ${"name".padEnd(nameWidth)}  model`);
            for (const a of agents) {
              const nm = a.displayName || a.name || "?";
              const mdl = a.model && a.model.length > 0 ? a.model : "(default)";
              lines.push(`  ${nm.padEnd(nameWidth)}  ${mdl}`);
              if (a.description) {
                lines.push(`  ${"".padEnd(nameWidth)}    ${a.description}`);
              }
            }
          }
          dispatch({ type: "sideNote", kind: "agents", message: lines.join("\n") });
        } else if (slash.rpcMethod === "getAgentBody" && result && typeof result === "object") {
          // prior round-sync: show an agent's body +
          // frontmatter. The shape is
          // {ok, name, body, path, description,
          // displayName, model, lastModifiedMs}.
          const r = result as {
            ok?: boolean;
            name?: string;
            body?: string;
            path?: string;
            description?: string;
            displayName?: string;
            model?: string;
            error?: string;
          };
          if (r.ok === false) {
            dispatch({ type: "log", message: `agent not found: ${r.error ?? r.name ?? "?"}` });
          } else {
            const lines: string[] = [];
            lines.push(`agent: ${(r.displayName || r.name) ?? "?"}`);
            if (r.path) lines.push(`  path:  ${r.path}`);
            if (r.description) lines.push(`  desc:  ${r.description}`);
            lines.push(`  model: ${r.model && r.model.length > 0 ? r.model : "(engine default)"}`);
            lines.push("");
            lines.push("--- body ---");
            lines.push(r.body ?? "(empty)");
            dispatch({ type: "sideNote", kind: "agent", message: lines.join("\n") });
          }
        } else if (slash.rpcMethod === "getSystemPrompt" && result && typeof result === "object") {
          // pretty-print the system prompt the
          // model is currently seeing. Delegates to
          // formatSystemPrompt (in prompt-formatter.ts)
          // so the contract is unit-testable and the
          // renderer doesn't carry parsing logic.
          //
          // also push a 2-second toast
          // summarising the response. The sideNote
          // stays in the scrollback for users who want
          // to scroll back to it; the toast gives the
          // "you ran a command" feedback in the same
          // way other RPCs do (e.g. /metrics, /traces).
          // The toast text mirrors the sideNote's
          // first line so the two views never disagree.
          const r = result as {
            text?: string;
            totalChars?: number;
            sectionCount?: number;
            sections?: Array<{ name?: string; length?: number; source?: string; firstLine?: string }>;
          };
          const formatted = formatSystemPrompt(r);
          dispatch({ type: "sideNote", kind: "systemPrompt", message: formatted });
          const sectionCount = typeof r.sectionCount === "number"
            ? r.sectionCount
            : (Array.isArray(r.sections) ? r.sections.length : 0);
          const totalChars = typeof r.totalChars === "number"
            ? r.totalChars
            : (typeof r.text === "string" ? r.text.length : 0);
          if (sectionCount > 0) {
            dispatch({
              type: "pushToast",
              kind: "info",
              text: `system prompt: ${sectionCount} sections, ${totalChars} chars (run /prompt <name> to drill down)`,
            });
          } else {
            dispatch({ type: "pushToast", kind: "warn", text: "system prompt: empty (no sections)" });
          }
        } else if (slash.rpcMethod === "getSystemPromptSection" && result && typeof result === "object") {
          // drill-down. The /prompt <name> variant
          // routes here. The response carries {ok, name,
          // source, length, text} on success and {ok: false,
          // error, available: [...]} on failure. The
          // formatter handles both shapes — we just forward
          // the wire payload and trust the formatter.
          const r = result as {
            ok?: boolean;
            name?: string;
            source?: string;
            length?: number;
            text?: string;
            error?: string;
            available?: string[];
          };
          dispatch({ type: "sideNote", kind: "systemPromptSection", message: formatSystemPromptSection(r) });
        } else {
          dispatch({
            type: "sideNote",
            kind: "rpc",
            message: `${slash.rpcMethod} → ${JSON.stringify(result)}`,
          });
        }
      } catch (e) {
        dispatch({ type: "log", message: `${slash.rpcMethod} failed: ${(e as Error).message}` });
      }
      return;
    }
    // R362 — a prompt typed while a run is in flight is QUEUED, not dropped.
    // This is the path the old "model is still working - prompt queued" toast
    // claimed to take. It now actually exists.
    if (state.submitting) {
      dispatch({ type: "enqueuePrompt", text });
      const pos = state.pendingPrompts.length; // 0-based index of the entry just pushed
      dispatch({
        type: "pushToast",
        kind: "info",
        text: pos === 0
          ? "model is still working - prompt queued (sends when this run ends)"
          : `model is still working - prompt queued (#${pos + 1})`,
      });
      return;
    }
    void sendPrompt(text);
  }

  /**
   * The single send path for a non-slash prompt, shared by the direct submit
   * and the queue drain. Extracted in R362 so the queued-prompt path cannot
   * drift away from the live one (they must produce identical turn history,
   * status transitions and error handling — a queued prompt that renders or
   * errors differently is worse than no queue).
   */
  async function sendPrompt(text: string): Promise<void> {
    dispatch({ type: "submit" });
    try {
      // R363 — the `query` response carries `{runId, accepted}`. This
      // is the ONLY reliable place the TUI learns the id it must hand
      // back to `cancel`, so it has to be captured here rather than
      // left to the notification fallback. A daemon that answers
      // `{ok:false, busyRunId}` (session lock held) is also handled:
      // we adopt `busyRunId` so Esc can stop the run that is actually
      // blocking us instead of dead-ending on a cancel we can't send.
      const resp = (await client.request<{
        runId?: string;
        accepted?: boolean;
        ok?: boolean;
        busyRunId?: string;
        error?: string;
      }>("query", { prompt: text })) ?? {};
      const runId = resp.runId ?? resp.busyRunId ?? null;
      if (runId) dispatch({ type: "setActiveRunId", runId });
      if (resp.ok === false || resp.accepted === false) {
        // The daemon refused the turn (session busy). Do NOT go into
        // streaming: there is no run to watch. Surface the reason and
        // leave the queue intact so the prompt retries after the live
        // run ends.
        dispatch({ type: "streamEnd", stopReason: "error" });
        dispatch({
          type: "pushToast",
          kind: "warn",
          text: resp.error ? `run refused: ${resp.error}` : "run refused by daemon",
        });
      }
      // The full lifecycle is observed via stream_event notifications.
    } catch (e) {
      dispatch({ type: "streamEnd", stopReason: "error" });
      dispatch({ type: "log", message: `query failed: ${(e as Error).message}` });
      // capture the error for the dedicated error card.
      const err = e as Error;
      dispatch({
        type: "setLastError",
        error: { message: err.message, ts: Date.now(), stack: err.stack },
      });
    }
  }

  /**
   * R363 — interrupt the run currently in flight.
   *
   * Wired to Esc (and Ctrl-C's non-exit sibling path). The daemon's
   * cancel is R721-hard: thread interrupt + destroyForcibly on the
   * recorded children + a 5s `Thread.stop` fallback, so it settles in
   * milliseconds for a cooperative model and ≤5s for a wedged one.
   *
   * Three rules this function exists to enforce:
   *
   *  1. **Never fire blind.** `cancel` is keyed by runId and the daemon
   *     hard-fails without one. If we don't have an id we say so in
   *     human words instead of sending a request that is guaranteed to
   *     error.
   *  2. **Never fire twice.** `requestCancelRun` is a no-op the second
   *     time, and the guard here makes it a no-op even before the
   *     reducer has re-rendered.
   *  3. **Never swallow the failure.** If the cancel RPC itself fails
   *     (old daemon, transport drop) we must clear `cancelRequested`,
   *     otherwise the strip shows "stopping..." forever on a run that
   *     is still happily running.
   */
  async function cancelActiveRun(): Promise<void> {
    if (cancelSentRef.current) return;
    const runId = stateRef.current.activeRunId;
    if (!runId) {
      dispatch({
        type: "pushToast",
        kind: "warn",
        text: "no cancellable run (daemon did not report a run id)",
      });
      return;
    }
    cancelSentRef.current = true;
    dispatch({ type: "requestCancelRun" });
    dispatch({ type: "pushToast", kind: "info", text: `stopping run ${runId}...` });
    try {
      await client.request("cancel", { runId });
    } catch (e) {
      // The daemon may not know this run (it already finished), or the
      // transport dropped. Either way the run is not ours to stop, so
      // release the latch and let the strip go back to normal.
      cancelSentRef.current = false;
      dispatch({ type: "setCancelSettled" });
      dispatch({
        type: "pushToast",
        kind: "err",
        text: `cancel failed: ${(e as Error).message}`,
      });
    }
  }

  /**
   * R362 — drain the prompt queue.
   *
   * Fires whenever the run stops (`submitting` flips false) and the queue is
   * non-empty: pops the oldest prompt and sends it through the SAME
   * `sendPrompt` path the live submit uses.
   *
   * Deliberately an effect rather than a reducer side effect: the reducer is
   * pure and cannot issue the `query` RPC. The reducer flips `submitting`;
   * this reacts.
   *
   * Guarded on `state.submitting` so we never double-fire: React StrictMode
   * double-invokes effects in development, and a double-dispatch would send
   * the queued prompt twice.
   */
  useEffect(() => {
    if (state.submitting) return;
    const next = state.pendingPrompts[0];
    if (!next) return;
    dispatch({ type: "dequeuePrompt" });
    dispatch({
      type: "pushToast",
      kind: "info",
      text: state.pendingPrompts.length > 1
        ? `sending queued prompt (${state.pendingPrompts.length - 1} still queued)`
        : "sending queued prompt",
    });
    void sendPrompt(next);
    // `state.pendingPrompts` is the trigger. Depending on the full state
    // would re-enter this while a send is still in flight; the submitting
    // guard covers that, so the narrow dep list is deliberate.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.pendingPrompts, state.submitting]);

  // R363 — re-arm the Esc latch at every run boundary.
  //
  // `cancelSentRef` is a synchronous latch (see its declaration). It
  // has to be cleared when a run ENDS, not when a cancel succeeds —
  // the two differ on the failure path, where a failed cancel must
  // leave the latch closed only until the run actually ends. Keyed on
  // `submitting` alone so it fires on both edges (start and end).
  const prevSubmittingRef = React.useRef(state.submitting);
  useEffect(() => {
    if (prevSubmittingRef.current === state.submitting) return;
    prevSubmittingRef.current = state.submitting;
    if (!state.submitting) cancelSentRef.current = false;
  }, [state.submitting]);

  useInput((input, key) => {
    if (key.ctrl && input === "c") {
      dispatch({ type: "exit" });
      exit();
      return;
    }
    // R362 — an open panel owns the keyboard. Ink's `useInput` is
    // NOT focus-scoped: every mounted hook receives every keystroke,
    // so without this gate ↑/↓ would also drive the prompt history,
    // `d` would expand a tool turn, `b` would bookmark, Tab would
    // run slash completion and Esc would toggle the decision card —
    // all while the user is trying to navigate the panel.
    // Ctrl+C above stays live so the user is never trapped.
    if (state.activePanel) return;
    // Ctrl+1..9 quick-switch between primary agent and
    // the most recent subagents. Ctrl+1 always returns to the
    // primary (current session) view. Ctrl+2..9 pick the
    // subagent at that index in the most-recent-first order
    // (which matches the SubagentPanel's display order, so
    // the user can read the panel and press the matching
    // shortcut). The previous view is pushed onto
    // viewHistory so the user can pop back with Ctrl+Shift+B
    // (or the planned 'back' shortcut in a later round).
    if (key.ctrl && /^[1-9]$/.test(input)) {
      const idx = Number(input);
      if (idx === 1) {
        // Back to primary.
        void client.request("loadSession", { sessionId: state.sessionId })
          .then(() => dispatch({ type: "viewPrimary" }))
          .catch((e) => dispatch({
            type: "pushToast", kind: "err",
            text: `view primary failed: ${(e as any)?.message ?? String(e)}`,
          }));
        return;
      }
      // Pick the (idx-2)th subagent in MRU order. We mirror
      // the SubagentPanel sort (RUNNING first, then terminal
      // newest-first).
      const ids = Object.keys(state.subagentJobs)
        .filter((id) => state.subagentJobs[id]?.status === "RUNNING")
        .sort((a, b) => (state.subagentJobs[b].startedAtMs ?? 0) - (state.subagentJobs[a].startedAtMs ?? 0));
      const terminal = Object.keys(state.subagentJobs)
        .filter((id) => state.subagentJobs[id]?.status !== "RUNNING")
        .sort((a, b) => (state.subagentJobs[b].endedAtMs ?? state.subagentJobs[b].startedAtMs ?? 0) - (state.subagentJobs[a].endedAtMs ?? state.subagentJobs[a].startedAtMs ?? 0));
      const ordered = [...ids, ...terminal];
      const subId = ordered[idx - 2];
      if (!subId) {
        dispatch({ type: "pushToast", kind: "warn", text: `Ctrl+${idx}: no subagent at that slot` });
        return;
      }
      void client.request("loadSession", { sessionId: subId })
        .then(() => dispatch({ type: "viewSubagent", jobId: subId }))
        .catch((e) => dispatch({
          type: "pushToast", kind: "err",
          text: `view subagent failed: ${(e as any)?.message ?? String(e)}`,
        }));
      return;
    }
    if (key.ctrl && key.shift && (input === "b" || input === "B")) {
      // Ctrl+Shift+B pops the view history.
      dispatch({ type: "viewHistoryPop" });
      return;
    }

    // R363 — the Esc ladder, resolved in strict priority order and
    // placed BEFORE the permission/decision-card branches on purpose.
    //
    // Those branches sit at their current position because they
    // predate cancel, and two of them `return` on Esc. Left alone,
    // they would make a live run **structurally uncancellable**:
    // pressing Esc with a pending permission ask would just collapse
    // the decision card, and pressing Esc with no ask would
    // re-expand it. The user would hold down Esc on a run they
    // cannot stop — the exact defect R363 exists to close.
    //
    // Priority: overlays (close what you opened) > stop the live run
    // > drop the queue. A run is never something Esc opened, so it
    // never competes with rule 1.
    if (key.escape) {
      if (state.showSearch) { dispatch({ type: "showSearch", show: false }); return; }
      if (state.helpVisible) { dispatch({ type: "showHelp", show: false }); return; }
      if (state.submitting) { void cancelActiveRun(); return; }
    }
    // when a permission ask is on screen, route the decision
    // keys to replyPermission. The card is now INLINE in the
    // scrollback (not a full-screen modal), so the user can keep
    // typing in the input box while the ask is pending. The
    // decision keys A / T / P / U / D / N work regardless of
    // focus.
    if (state.permissionAsk) {
      const ask = state.permissionAsk;
      // R344: numbered selector aliases (1=allow / 2=always_allow /
      // 3=deny / 4=always_deny) match Claude Code's modal style.
      // Letter aliases (A/Y/D/N + T/P/U for scope variants) are
      // still accepted for muscle memory.
      if (input === "a" || input === "A" || input === "1") {
        replyPermission(client, ask, "allow", dispatch); return;
      }
      // T — allow for this task. Session-scope rule, but only
      // future calls of this tool in the current run.
      if (input === "t" || input === "T") { replyPermission(client, ask, "always_allow", dispatch, "session"); return; }
      // P — allow for this project. Persists to .aethercode/permissions.json.
      if (input === "p" || input === "P") { replyPermission(client, ask, "always_allow", dispatch, "project"); return; }
      // U — allow for this user (cross-project, but only for this
      // user's account on this machine).
      if (input === "u" || input === "U") { replyPermission(client, ask, "always_allow", dispatch, "user"); return; }
      // 2 = always_allow (session scope by default; matches "Y").
      if (input === "y" || input === "Y" || input === "2") {
        replyPermission(client, ask, "always_allow", dispatch, "session"); return;
      }
      if (input === "d" || input === "D" || input === "3") {
        replyPermission(client, ask, "deny", dispatch); return;
      }
      if (input === "n" || input === "N" || input === "4") {
        replyPermission(client, ask, "always_deny", dispatch, "session"); return;
      }
      // Esc collapses the inline card but leaves the ask pending.
      if (key.escape && state.decisionCardExpanded) {
        dispatch({ type: "setDecisionCardExpanded", expanded: false });
        return;
      }
    } else {
      // No active ask — Esc re-expands the card slot (it'll show
      // again next time a permission request comes in).
      if (key.escape && !state.decisionCardExpanded) {
        dispatch({ type: "setDecisionCardExpanded", expanded: true });
        return;
      }
    }
    if (key.ctrl && input === "l") {
      dispatch({ type: "log", message: "(clear is a no-op in this build)" });
      return;
    }
    if (key.ctrl && input === "?") {
      dispatch({ type: "showHelp", show: !state.helpVisible });
      return;
    }
    if (key.escape) {
      // R362 — Esc drops the queued prompts. The strip above the input box
      // advertises this, so it has to actually work. Without it a queued
      // prompt can only be sent, never cancelled, which is the same
      // "no user control" defect the PM review flagged on the stale banner.
      //
      // R363: the search/help/`cancelActiveRun` cases moved UP into the
      // unified Esc ladder, so by the time we get here `submitting` is
      // false and no overlay is open. What is left is exactly the
      // "nothing to stop, something queued" case.
      if (state.pendingPrompts.length > 0) {
        dispatch({ type: "clearPendingPrompts" });
        dispatch({
          type: "pushToast",
          kind: "warn",
          text: `dropped ${state.pendingPrompts.length} queued prompt${state.pendingPrompts.length === 1 ? "" : "s"}`,
        });
      }
      return;
    }
    if (state.helpVisible) {
      // Help is modal — any other key dismisses it.
      dispatch({ type: "showHelp", show: false });
      return;
    }
    if (key.ctrl && (input === "o" || input === "O")) {
      // Ctrl-O toggles collapse-all.
      dispatch({ type: "setCollapseAll", collapsed: !state.collapseAll });
      return;
    }
    if (key.ctrl && (input === "b" || input === "B")) {
      // Ctrl-B toggles the side panel.
      dispatch({ type: "toggleSidebar" });
      return;
    }
    if (key.ctrl && (input === "d" || input === "D")) {
      // T-6-15: Ctrl-D toggles the right column (session
      // details + todo board + tokens). The user can
      // collapse the column for more chat space.
      dispatch({ type: "toggleRightPanel" });
      return;
    }
    if (key.ctrl && (input === "f" || input === "F")) {
      // Ctrl-F opens the search bar. Esc closes it.
      dispatch({ type: "showSearch", show: true });
      return;
    }
    if (key.ctrl && (input === "p" || input === "P")) {
      // Ctrl-P opens the command palette.
      dispatch({ type: "setPaletteOpen", open: !state.paletteOpen });
      return;
    }
    if (key.ctrl && key.shift && (input === "t" || input === "T")) {
      // T-422: Ctrl-Shift-T opens the aethercode-themes picker.
      dispatch({ type: "setThemePickerOpen", open: !state.themePickerOpen });
      return;
    }
    if (key.ctrl && (input === "l" || input === "L")) {
      // Ctrl-L toggles the log viewer.
      dispatch({ type: "setLogViewerOpen", open: !state.logViewerOpen });
      return;
    }
    if (key.ctrl && (input === "t" || input === "T")) {
      // Ctrl-T re-shows the tutorial / welcome screen.
      dispatch({ type: "setTutorialOpen", open: !state.tutorialOpen });
      return;
    }
    if (key.ctrl && (input === "i" || input === "I")) {
      // Ctrl-I toggles the dim "thinking" sub-section. When
      // off, model <think>...</think> content is hidden from the
      // scrollback (the turn is still in state, so re-toggling
      // brings it back). Default is on because the model emits
      // long reasoning blocks that would otherwise drown the
      // actual answer.
      dispatch({ type: "setShowThinking", show: !state.showThinking });
      dispatch({
        type: "pushToast",
        kind: "info",
        text: `thinking ${state.showThinking ? "hidden" : "shown"}`,
      });
      return;
    }
    // SubagentPanel. Toggle with Ctrl+S; the
    // per-row actions (c, Enter) are handled below
    // when the panel is open. We process the toggle
    // BEFORE the panel-open branch so the user can
    // dismiss the panel with the same chord.
    if (key.ctrl && (input === "s" || input === "S")) {
      if (state.subagentPanelOpen) {
        dispatch({ type: "closeSubagentPanel" });
      } else {
        dispatch({ type: "openSubagentPanel" });
      }
      return;
    }
    // panel-open keys. While the panel is open,
    // Esc closes it; ↑/↓ move the cursor; Enter inserts
    // the focused job's result; c cancels the focused
    // running job. We treat these as modal-ish — they
    // take precedence over the scrollback input but
    // NOT over a permission ask (the user must answer
    // the ask first).
    if (state.subagentPanelOpen) {
      if (key.escape) {
        dispatch({ type: "closeSubagentPanel" });
        return;
      }
      if (key.upArrow) {
        dispatch({ type: "subagentPanelFocusPrev" });
        return;
      }
      if (key.downArrow) {
        dispatch({ type: "subagentPanelFocusNext" });
        return;
      }
      if (key.return) {
        const focusId = state.subagentPanelFocus;
        if (focusId) insertSubagentResult(focusId, state, dispatch);
        return;
      }
      if (input === "c" || input === "C") {
        const focusId = state.subagentPanelFocus;
        if (!focusId) return;
        const job = state.subagentJobs[focusId];
        if (!job || job.status !== "RUNNING") {
          dispatch({ type: "pushToast", kind: "warn", text: `subagent ${focusId} is not running` });
          return;
        }
        dispatch({ type: "pushToast", kind: "info", text: `cancelling subagent ${focusId}…` });
        void (async () => {
          try {
            const r = (await client.request("subagentCancel", { jobId: focusId })) as
              { ok?: boolean; cancelled?: boolean; alreadyFinished?: boolean } | null | undefined;
            if (r?.alreadyFinished) {
              dispatch({ type: "pushToast", kind: "warn", text: `subagent ${focusId} already finished` });
            } else if (r?.cancelled) {
              dispatch({ type: "pushToast", kind: "ok", text: `cancelled subagent ${focusId}` });
            } else {
              dispatch({ type: "pushToast", kind: "warn", text: `subagent ${focusId}: nothing to cancel` });
            }
          } catch (e: any) {
            dispatch({ type: "pushToast", kind: "err", text: `cancel failed: ${e?.message ?? String(e)}` });
          }
        })();
        return;
      }
      if (input === "v" || input === "V") {
        // 'v' switches the active transcript to the
        // focused subagent. Same as onView (the SubagentPanel
        // also surfaces the onView callback for mouse/click
        // parity, but the keyboard binding lives in the host's
        // useInput so the panel stays purely presentational).
        const focusId = state.subagentPanelFocus;
        if (!focusId) return;
        void client.request("loadSession", { sessionId: focusId })
          .then(() => dispatch({ type: "viewSubagent", jobId: focusId }))
          .catch((e) => dispatch({
            type: "pushToast", kind: "err",
            text: `view subagent failed: ${(e as any)?.message ?? String(e)}`,
          }));
        return;
      }
      // Other keys are swallowed while the panel is open
      // so the user can press j/k/etc. without affecting
      // the input box.
      return;
    }
    if (input === "b" && !state.permissionAsk && !state.helpVisible
        && !state.showSearch && !state.paletteOpen && !state.logViewerOpen
        && !state.tutorialOpen) {
      // 'b' bookmarks the most recent turn (when no modal
      // is open). The user can /bookmark <id> for specific ids.
      const last = state.turns[state.turns.length - 1];
      if (last) {
        dispatch({ type: "toggleBookmark", id: last.id });
        const isBookmarked = state.bookmarks.includes(last.id);
        dispatch({
          type: "pushToast",
          kind: isBookmarked ? "info" : "ok",
          text: isBookmarked
            ? `removed bookmark from turn #${last.id}`
            : `bookmarked turn #${last.id}`,
        });
      }
      return;
    }
    if (key.ctrl && (input === "z" || input === "Z")) {
      // Ctrl-Z rewinds to the most recent user message.
      // The engine RPC `rewind` is called; on success, the
      // engine emits a fresh "run_start" event for the rewound
      // session, which we handle like any other run.
      if (state.history.length === 0) {
        dispatch({ type: "pushToast", kind: "warn", text: "nothing to rewind" });
        return;
      }
      const target = state.history.length - 1;
      void (async () => {
        try {
          const r = (await client.request("rewind", { target })) as { ok?: boolean; reason?: string } | null | undefined;
          if (r?.ok) {
            dispatch({ type: "pushToast", kind: "ok", text: `rewound to #${target + 1}` });
            dispatch({ type: "setRewindTarget", target });
          } else {
            dispatch({ type: "pushToast", kind: "err", text: `rewind failed: ${r?.reason ?? "unknown"}` });
          }
        } catch (e) {
          dispatch({ type: "pushToast", kind: "err", text: `rewind error: ${(e as Error).message}` });
        }
      })();
      return;
    }
    if (key.tab) {
      // Tab completes the current slash command. The
      // completion logic returns a "completed" string (to set
      // the input to) and a list of "alternatives" (when there's
      // more than one match). When alternatives are non-empty,
      // we show them as a side note so the user can pick.
      if (state.input.startsWith("/")) {
        const c = completeSlash(state.input);
        dispatch({ type: "setInput", text: c.completed });
        if (c.alternatives.length > 0) {
          dispatch({
            type: "sideNote",
            kind: "completion",
            message: `→ ${c.alternatives.map((a) => "/" + a).join(", ")}`,
          });
        }
      }
      return;
    }
    if (key.ctrl && (input === "e" || input === "E")) {
      // Ctrl-E toggles the most recent turn.
      const last = state.turns[state.turns.length - 1];
      if (last) dispatch({ type: "toggleTurn", id: last.id });
      return;
    }
    if (input === "d" || input === "D") {
      // 'd' on a tool turn toggles the full-content "details"
      // view. We toggle the most recent tool turn. The user can
      // press it again to collapse.
      for (let i = state.turns.length - 1; i >= 0; i--) {
        const t = state.turns[i];
        if (t.role === "tool") {
          dispatch({ type: "toggleToolExpand", id: t.id });
          break;
        }
      }
      return;
    }
    if (key.upArrow) {
      dispatch({ type: "historyUp" });
      return;
    }
    if (key.downArrow) {
      dispatch({ type: "historyDown" });
      return;
    }
  });

  const showWelcome = state.connected && state.turns.length === 0;

  // R362 — panel geometry + the shared close handler.
  const cols = typeof process.stdout.columns === "number" ? process.stdout.columns : 120;
  const panelWidth = panelWidthFor(cols);
  const meta = state.activePanel ? PANEL_META[state.activePanel] : null;
  const closePanel = () => dispatch({ type: "closePanel" });
  const setNotice = (n: string) =>
    dispatch({ type: "setPanelStatus", status: { loading: false, error: null, notice: n } });
  const setPanelError = (m: string) =>
    dispatch({ type: "setPanelStatus", status: { loading: false, error: m, notice: null } });
  const errMsg = (e: unknown) => (e instanceof Error ? e.message : String(e));

  // The memory panel's compact action. Fires `memory/compact` with
  // `force: true`, mirrors the outcome into `memoryCompact` so the
  // panel's own footer can show the before/after token counts, and
  // re-fetches by switching the tab (which the fetch effect keys on).
  async function compactProjectMemory(): Promise<void> {
    dispatch({ type: "setMemoryCompact", progress: { state: "running" } });
    try {
      const r = (await client.request("memory/compact", { force: true } as RpcValue)) as
        Record<string, unknown> | null | undefined;
      if (r?.skipped) {
        dispatch({
          type: "setMemoryCompact",
          progress: { state: "skipped", reason: String(r.reason ?? "the daemon reported nothing to compress") },
        });
      } else {
        dispatch({
          type: "setMemoryCompact",
          progress: {
            state: "ok",
            beforeTokens: Number(r?.beforeTokens ?? 0),
            afterTokens: Number(r?.afterTokens ?? 0),
            changesCompressed: Number(r?.changesCompressed ?? 0),
            ms: Number(r?.ms ?? 0),
          },
        });
      }
      dispatch({ type: "setMemoryTab", tab: "project" });
    } catch (e) {
      dispatch({ type: "setMemoryCompact", progress: { state: "error", message: errMsg(e) } });
    }
  }

  /**
   * The open panel's body. Each branch owns its own props and its
   * own action wiring; the surrounding chrome (frame, title, hint,
   * pending / error row, Esc) comes from `PanelShell`.
   *
   * Three of these panels are honest about a missing daemon RPC
   * rather than pretending to work:
   *   - agents  — no `agent/setActive` exists
   *   - effort  — no effort RPC at all
   *   - mcp     — no MCP RPC at all
   * Each surfaces the gap inside the panel rather than silently
   * accepting the pick and doing nothing.
   */
  const panelBody = (): React.ReactNode => {
    switch (state.activePanel) {
      case "memory": {
        const progress: CompactProgress =
          state.memoryCompact.state === "running"
            ? { state: "running", startedAt: state.panelOpenedAt || Date.now() }
            : state.memoryCompact;
        return (
          <MemoryPanel
            global={state.memoryScopes.global}
            project={state.memoryScopes.project}
            session={state.memoryScopes.session}
            sessionId={state.sessionId}
            activeTab={state.memoryTab}
            onTabChange={(tab) => dispatch({ type: "setMemoryTab", tab })}
            now={state.panelOpenedAt || Date.now()}
            width={panelWidth - 6}
            onEditFact={() =>
              setNotice("editing a memory entry is not wired up — /memory is read-only for now")
            }
            onCompact={() => void compactProjectMemory()}
            compactProgress={progress}
            onClose={closePanel}
          />
        );
      }

      case "threads": {
        const threads: ThreadInfo[] = state.threads.map((r) => ({
          threadId: r.threadId,
          agentName: r.agentName,
          messages: r.messages,
          createdAt: r.createdAt,
          updatedAt: r.updatedAt,
          prompt: r.prompt,
          cwd: r.cwd,
          gitBranch: r.gitBranch,
        }));
        return (
          <ThreadSelector
            threads={threads}
            currentCwd={currentCwd}
            onSelect={(threadId, choice) => {
              closePanel();
              const target = state.threads.find((r) => r.threadId === threadId);
              // A thread recorded under a different cwd is only
              // usable if the daemon is pointed at it too.
              //
              // R364: this used to send `setCwd`, which is the
              // DESKTOP's Tauri IPC command name (aethercode-desktop
              // src/store/index.ts → rpc.setCwd(path, sid)). It was
              // copied here as if it were a JSON-RPC method, and it
              // is not one — the daemon has no such handler, so every
              // cross-cwd thread switch died on `-32601 method not
              // found` before it could do anything. The JSON-RPC
              // name is `switchProject`
              // (AetherCodeMethods.registerAll + HttpJsonRpcServer).
              // R370: `choice` is decided by ThreadSelector from a
              // strict `t.cwd === currentCwd`, but we gate the actual
              // `switchProject` on `sameCwd`. The two disagreed
              // before: a row whose cwd differed only in case
              // (`d:\work` vs `D:\Work`) was labelled "cwd≠" and
              // would have fired a project switch for a directory
              // the daemon is already in.
              if (choice === "switch" && target?.cwd && !sameCwd(target.cwd, currentCwd)) {
                void client
                  .request("switchProject", { cwd: target.cwd } as RpcValue)
                  .then(() =>
                    dispatch({ type: "pushToast", kind: "ok", text: `cwd → ${target.cwd}` })
                  )
                  .catch((e: unknown) =>
                    dispatch({ type: "pushToast", kind: "warn", text: `switchProject failed: ${errMsg(e)}` })
                  );
              }
              void client
                .request("loadSession", { sessionId: threadId } as RpcValue)
                .then((res: unknown) => {
                  // R370 — the daemon swapped its active session, so
                  // the TUI's own sessionId is now a lie (it still
                  // names the session we just left, and it is what
                  // the Header pill and `/export` show). Take the id
                  // back off the response rather than assuming the
                  // request succeeded into the one we asked for.
                  const sid =
                    (res as { sessionId?: unknown } | null)?.sessionId;
                  const adopted = typeof sid === "string" && sid.length > 0 ? sid : threadId;
                  dispatch({ type: "adoptSession", sessionId: adopted });
                  dispatch({
                    type: "pushToast",
                    kind: "ok",
                    // Say what actually happened. The scrollback is
                    // NOT the picked session's history (there is no
                    // transcript hydration in this renderer), and a
                    // toast that implies otherwise is the R369 class
                    // of lie one layer up.
                    text: `now working in ${adopted} — the next prompt continues it (history is not replayed here)`,
                  });
                })
                .catch((e: unknown) =>
                  dispatch({ type: "pushToast", kind: "err", text: `loadSession failed: ${errMsg(e)}` })
                );
            }}
            onClose={closePanel}
            // R370: this was `title=""`, which rendered a bare `◆ `
            // with nothing after it — a modal with no name, on BOTH
            // `/threads` and (new) `/sessions`. Two commands share
            // this panel now, so the heading has to say which one is
            // open or the user cannot tell whether the row list is
            // filtered.
            title={
              state.panelCwdOnly
                ? `Sessions in ${cwdBasename(currentCwd) || currentCwd || "(no cwd)"}`
                : "All sessions"
            }
            emptyLabel={
              state.panelCwdOnly
                ? `no sessions recorded under ${cwdBasename(currentCwd) || currentCwd || "the current directory"} — /threads shows every session`
                : "no sessions yet — start one with a prompt"
            }
            // The title carries the leaf name; two checkouts can
            // share one. Print the absolute path under it so the
            // filter target is never a guess.
            subtitle={state.panelCwdOnly ? currentCwd : undefined}
            maxVisible={10}
          />
        );
      }

      case "todos":
        return (
          <TodoBoard
            todos={state.todos}
            selectedId={todoSelectedId}
            onSelect={(id) => setTodoSelectedId(id)}
            currentTodoId={state.currentTodoId}
            maxRows={14}
            emptyLabel="(no todos yet — ask the model to plan something and they appear here)"
            showHeader
            progressBarWidth={Math.max(8, Math.min(28, panelWidth - 24))}
          />
        );

      case "agents":
        return (
          <AgentSelector
            agents={state.agents}
            current={state.defaultAgent}
            onSelect={(name) => {
              // The daemon registers no "set active agent" method
              // (checked against AetherCodeMethods' registry), so
              // there is nothing to dispatch. Say so instead of
              // accepting the pick and silently doing nothing.
              setNotice(`this daemon has no "set active agent" RPC — ${name} was not applied`);
              dispatch({
                type: "pushToast",
                kind: "warn",
                text: "agent switching is not supported by this daemon",
              });
            }}
            onClose={closePanel}
            title=""
            maxVisible={10}
          />
        );

      case "effort":
        return (
          <Box flexDirection="column">
            <Text color={t.warn}>
              this daemon exposes no effort RPC — the level below is recorded locally only
            </Text>
            <EffortPicker
              efforts={EFFORT_CATALOG}
              current={state.effortCurrent}
              defaultEffort="medium"
              modelSpec={state.model}
              onSelect={(id) => {
                dispatch({ type: "setEffortCurrent", effort: id });
                dispatch({ type: "pushToast", kind: "info", text: `reasoning effort → ${id}` });
              }}
              onClose={closePanel}
              title=""
              maxVisible={8}
            />
          </Box>
        );

      case "cwd":
        return (
          <CwdSwitcher
            currentCwd={currentCwd}
            validate={CWD_VALIDATE}
            onSelect={(path) => {
              closePanel();
              // R364 — `switchProject`, not `setCwd`. See the
              // ThreadSelector call site above for the full story:
              // `setCwd` is a Tauri IPC command that does not exist
              // on the JSON-RPC surface.
              void client
                .request("switchProject", { cwd: path } as RpcValue)
                .then((r: unknown) => {
                  // The RPC echoes the daemon's NORMALISED absolute
                  // path. Adopt it rather than the user's raw string
                  // so the panel shows what the engine actually
                  // bound — `Paths.get(cwd).toAbsolutePath().normalize()`
                  // resolves `.` and relative segments.
                  const normalised = (r as { newCwd?: string } | null)?.newCwd;
                  dispatch({ type: "setCurrentCwd", cwd: normalised || path });
                  dispatch({ type: "pushToast", kind: "ok", text: `cwd → ${normalised || path}` });
                })
                .catch((e: unknown) =>
                  dispatch({
                    type: "pushToast",
                    kind: "err",
                    text: `switchProject failed: ${errMsg(e)}`,
                  })
                );
            }}
            onClose={closePanel}
            title=""
          />
        );

      case "grants": {
        const grants = state.grants;
        const onRevoke = (id: string) => {
          dispatch({ type: "setPanelStatus", status: { loading: true, error: null, notice: null } });
          void client
            .request("permission/revoke", { id } as RpcValue)
            .then(() => {
              dispatch({ type: "setGrants", grants: state.grants.filter((g) => g.id !== id) });
              setNotice(`revoked grant ${id}`);
              dispatch({ type: "pushToast", kind: "ok", text: `revoked grant ${id}` });
            })
            .catch((e: unknown) => setPanelError(`permission/revoke failed: ${errMsg(e)}`));
        };
        // GrantsManager hard-codes a ~112-column 3-column layout;
        // below that it wraps into unreadable columns. Swap in the
        // single-column fallback rather than ship a broken frame.
        return cols >= GRANTS_WIDE_MIN_COLS ? (
          <GrantsManager grants={grants} onRevoke={onRevoke} />
        ) : (
          <GrantsCompact grants={grants} width={panelWidth - 4} onRevoke={onRevoke} />
        );
      }

      case "notifications": {
        // The daemon has no notification RPC; the pending notices the
        // user actually has are the live toasts. Rendering them here
        // gives the hub real content instead of a permanent "nothing
        // pending" line.
        const entries: PendingNotification[] = state.toasts.map((tst) => ({
          key: String(tst.id),
          title: `${tst.kind === "err" ? "✗" : tst.kind === "warn" ? "!" : "·"} ${tst.text}`,
          body: new Date(tst.createdAt).toLocaleTimeString(),
          actions: [{ actionId: { value: "dismiss" }, label: "Dismiss", primary: true }],
        }));
        return (
          <Box flexDirection="column">
            {/* Empty state done properly: NotificationCenter's own
                blank line says "no pending notifications" without
                telling the user what one IS or how to make one. */}
            {entries.length === 0 ? (
              <Box flexDirection="column" marginBottom={1}>
                <Text dimColor>
                  {"  nothing pending — notices appear here (and as toasts) when the daemon asks for a"}
                </Text>
                <Text dimColor>
                  {"  permission decision, a background subagent finishes, or a tool call errors."}
                </Text>
              </Box>
            ) : null}
            <NotificationCenter
              entries={entries}
              onSelect={() =>
                setNotice("notification actions are local-only — the daemon exposes no notification RPC")
              }
              onToggleWarning={(key, enabled) => {
                dispatch({ type: "setNotificationToggle", key, enabled });
                setNotice(`${key} ${enabled ? "enabled" : "suppressed"} (this session only — not persisted)`);
              }}
              onClose={closePanel}
              title=""
            />
          </Box>
        );
      }

      case "mcp": {
        const servers: ServerInfo[] = state.mcpServers.map((s) => ({
          name: s.name,
          status: s.status,
          tools: s.tools,
        }));
        return (
          <McpViewer
            servers={servers}
            onSelect={(name) =>
              setNotice(`signing in to ${name} needs an MCP RPC, which this daemon does not expose`)
            }
            onReconnect={() =>
              setNotice("reconnect needs an MCP RPC, which this daemon does not expose")
            }
            onClose={closePanel}
            title=""
            maxVisible={10}
          />
        );
      }

      default:
        return null;
    }
  };

  return (
    <Box flexDirection="column" flexGrow={1}>
      <ThemeProvider themeName={state.themeName}>
      <AethercodeThemeProvider store={themeStore}>
      {state.themePickerOpen ? (
        <Box flexDirection="column" flexGrow={1} justifyContent="center" alignItems="center">
          <ThemePicker
            themes={pickerThemes}
            current={pickerCurrent}
            onClose={() => dispatch({ type: "setThemePickerOpen", open: false })}
            onSelect={(name) => {
              void themeStore.setActive(name).then(() => {
                dispatch({ type: "setThemesPickedName", name });
                dispatch({ type: "setThemePickerOpen", open: false });
                dispatch({ type: "pushToast", kind: "info", text: `theme → ${name}` });
              });
            }}
            onPreview={(name) => {
              void themeStore.setActive(name);
            }}
          />
        </Box>
      ) : state.paletteOpen ? (
        <Box flexDirection="column" flexGrow={1} justifyContent="center" alignItems="center">
          <CommandPalette
            onClose={() => dispatch({ type: "setPaletteOpen", open: false })}
            onSelect={(cmd) => dispatch({ type: "setInput", text: cmd + " " })}
          />
        </Box>
      ) : state.tutorialOpen ? (
        <Box flexDirection="column" flexGrow={1} justifyContent="center" alignItems="center">
          <Welcome
            model={state.model}
            cwd={currentCwd}
            sessionId={state.sessionId}
            mode={state.permissionMode}
            bankStatus={bankStatus ?? undefined}
          />
        </Box>
      ) : state.logViewerOpen ? (
        <Box flexDirection="column" flexGrow={1} justifyContent="center" alignItems="center">
          <LogViewer
            entries={state.logBuffer}
            onClose={() => dispatch({ type: "setLogViewerOpen", open: false })}
          />
        </Box>
      ) : state.helpVisible ? (
        <Box flexDirection="column" flexGrow={1} justifyContent="center" alignItems="center">
          <HelpOverlay />
        </Box>
      ) : (
        <Box flexDirection="row" flexGrow={1}>
          {/* R362: the sidebar and the right column are suppressed
              while a panel is open. Two reasons, both hard:
              (1) the right column embeds its own TodoBoard, whose
                  `useInput` would receive ↑/↓/j/k a second time and
                  move a focus the user cannot see; (2) 26 + 30
                  columns of chrome leaves the panel too narrow to
                  honour the 80-column floor.
              The `activePanel ? null :` guards sit INSIDE the
              pinned `sidebarVisible ?` / `rightPanelVisible ?`
              ternaries so both remain the single source of truth
              for whether the column is toggled on. */}
          {state.sidebarVisible ? (
            state.activePanel ? null : (
              <Sidebar
                width={26}
                cwd={currentCwd}
                sessionId={state.sessionId}
                recentQueries={state.history}
                tasks={state.recentTasks}
              />
            )
          ) : null}
          {state.subagentPanelOpen ? (
            // live list of recent subagent jobs.
            // Sits to the right of the sidebar (or the
            // main column when the sidebar is hidden).
            // Width is capped so the main column keeps
            // enough horizontal space; on a 120-col
            // terminal the panel is ~42 cols.
            // NOTE: do NOT pass `now={Date.now()}` here
            // — that produces a fresh value every render,
            // which forces a re-render of the whole
            // SubagentPanel subtree (and Ink re-paints the
            // row), which the user perceives as a visible
            // "jumping" of the layout. The panel reads
            // `Date.now()` internally for its duration
            // counters; that only re-runs when something
            // else (the panel's own setInterval) ticks.
            <SubagentPanel
              state={state}
              width={Math.max(36, Math.min(54, Math.floor((typeof process.stdout.columns === "number" ? process.stdout.columns : 120) * 0.35)))}
              onCancel={(jobId) => cancelSubagent(jobId, state, client, dispatch)}
              onInsert={(jobId) => insertSubagentResult(jobId, state, dispatch)}
              onView={(jobId) => {                  // R214: 'v' → loadSession + viewSubagent
                void client.request("loadSession", { sessionId: jobId })
                  .then(() => dispatch({ type: "viewSubagent", jobId }))
                  .catch((e) => dispatch({
                    type: "pushToast",
                    kind: "err",
                    text: `view subagent failed: ${(e as any)?.message ?? String(e)}`,
                  }));
              }}
            />
          ) : null}
          <Box flexDirection="column" flexGrow={1}>
            {/* toast notifications sit above the header so
                they don't compete with the scrollback for space. */}
            <ToastStack toasts={state.toasts} />
            {/* layout presets control which sub-components are
                rendered. "full" = everything; "minimal" = no header
                / statusbar; "focus" = scrollback + input only. */}
            {state.layout !== "focus" && state.layout !== "minimal" ? (
              <Header state={state} cwd={currentCwd} />
            ) : null}
            {/* R700 — SDD mode banner. Renders only when
                state.sddRun is non-null (i.e. an active SDD run
                exists). Sits above the scrollback so the user
                always sees the current phase + chip strip. */}
            {state.sddRun ? (
              <SddMode run={state.sddRun} cwd={currentCwd} />
            ) : null}
            {sddReview ? (
              <Box flexDirection="column" paddingX={1}>
                <SddReview
                  artefactPath={sddReview.path}
                  currentLines={sddReview.lines}
                  previousLines={sddReview.previousLines}
                  maxLines={60}
                />
              </Box>
            ) : null}
            {/* R362 — the open panel. Deliberately placed HERE,
                above the scrollback and NOT as a full-screen
                replacement: the Header, the scrollback, the input
                box and the status bar all stay mounted and visible
                underneath, which is the same contract the inline
                permission card follows (see the `useInput` block
                above). The user can always see their draft and that
                the session is alive while a panel is open; only the
                keyboard is handed over. */}
            {state.activePanel && meta ? (
              <Box flexDirection="column" paddingX={1}>
                <PanelShell
                  title={meta.title}
                  // Only TodoBoard lacks a keymap line of its own;
                  // for the other eight the component prints it.
                  hint={state.activePanel === "todos" ? meta.hint : undefined}
                  width={panelWidth}
                  status={state.panelStatus}
                  loadingLabel={meta.loading}
                  onClose={closePanel}
                >
                  {panelBody()}
                </PanelShell>
              </Box>
            ) : null}
            <Box flexDirection="row" flexGrow={1}>
              <Box flexDirection="column" flexGrow={1}>
                <Scrollback
                  turns={state.turns}
                  height={1000}
                  showWelcome={showWelcome}
                  welcomeCtx={{ model: state.model, cwd: currentCwd, sessionId: state.sessionId, mode: state.permissionMode }}
                  collapseAll={state.collapseAll}
                  onToggleTurn={(id) => dispatch({ type: "toggleTurn", id })}
                  lastStopReason={state.lastStopReason}
                  lastStopKind={state.lastStopKind}
                  lastError={state.lastError}
                  showThinking={state.showThinking}
                  permissionAsk={state.permissionAsk}
                  decisionCardExpanded={state.decisionCardExpanded}
                />
                {/* inline subagent spawn cards. The host
                    also calls onOpen to switch the active
                    transcript to the subagent via loadSession
                    + the viewSubagent reducer action. */}
                <SubagentSpawnCards
                  cards={Object.entries(state.subagentSpawnCards).map(
                    ([eventId, card]) => ({ eventId, card })
                  )}
                  onOpen={(subagentId) => {
                    void client.request("loadSession", { sessionId: subagentId })
                      .then(() => dispatch({ type: "viewSubagent", jobId: subagentId }))
                      .catch((e) => dispatch({
                        type: "pushToast",
                        kind: "err",
                        text: `loadSession(${subagentId}) failed: ${e?.message ?? e}`,
                      }));
                  }}
                  onDismiss={(subagentId) => {
                    const eid = Object.entries(state.subagentSpawnCards)
                      .find(([, c]) => c.subagentId === subagentId)?.[0];
                    if (eid) dispatch({ type: "removeSubagentSpawnCard", eventId: eid });
                  }}
                  width={Math.max(36, Math.min(72, Math.floor((typeof process.stdout.columns === "number" ? process.stdout.columns : 120) * 0.6)))}
                />
                {state.showSearch ? (
                  <SearchBar
                    turns={state.turns}
                    initial={state.searchQuery}
                    onClose={() => dispatch({ type: "showSearch", show: false })}
                    onCommit={(matches) => dispatch({
                      type: "setSearchQuery",
                      query: state.searchQuery,
                      matches,
                    })}
                  />
                ) : null}
                {/*
                  R362 — the queued-prompt strip.
                  Claude Code's contract is explicit: it "lists the queued
                  entries in the conversation until it sends them". A queue
                  the user cannot see is a queue they will not trust — they
                  will assume their prompt was lost and retype it. So the
                  strip sits directly above the input box, shows position and
                  a truncated preview, and offers a way to drop one.
                */}
                {/*
                  R363 — the interrupt affordance.
                  Claude Code's contract: "Esc explicitly stops the
                  current response or tool call mid-turn so you can
                  redirect." A stop control that exists only in a
                  help screen is not a stop control — the user has to
                  SEE that the turn is interruptible at the moment
                  they might want to interrupt it.

                  Two states, because the R721 cancel is asynchronous:
                  `cancelRequested` means the RPC is in flight and the
                  daemon is interrupting the worker thread. Showing
                  "esc to interrupt" then would be a lie (Esc is
                  latched) and showing nothing would look broken.
                */}
                {state.submitting && !state.pendingPrompts.length ? (
                  <Box paddingX={1}>
                    <Text color={state.cancelRequested ? t.warn : t.dim}>
                      {state.cancelRequested
                        ? "stopping... (interrupt sent)"
                        : "esc to interrupt"}
                    </Text>
                  </Box>
                ) : null}
                {state.submitting && state.pendingPrompts.length > 0 ? (
                  <Box paddingX={1}>
                    <Text color={state.cancelRequested ? t.warn : t.dim}>
                      {state.cancelRequested
                        ? "stopping... (interrupt sent)"
                        : "esc to interrupt (press again to drop the queue)"}
                    </Text>
                  </Box>
                ) : null}
                {state.pendingPrompts.length > 0 ? (
                  <Box flexDirection="column" paddingX={1}>
                    {state.pendingPrompts.map((p, i) => (
                      <Box key={`${i}-${p.slice(0, 12)}`} flexDirection="row">
                        <Text color={t.dim}>
                          {i === 0 ? "queued next" : `queued ${i + 1}`}
                          {"  "}
                        </Text>
                        <Text color={t.accent} bold>
                          {truncateForQueue(p)}
                        </Text>
                        <Box flexGrow={1} />
                        {i === 0 ? (
                          <Text color={t.dim}>{"  (esc drops the queue)"}</Text>
                        ) : null}
                      </Box>
                    ))}
                  </Box>
                ) : null}
                <InputBox
                  state={state}
                  onChange={(t) => dispatch({ type: "setInput", text: t })}
                  onSubmit={handleSubmit}
                />
                {state.layout === "full" ? <StatusBar
                  state={state}
                  onContinue={() => {
                    void client.request("task/resume", { id: state.sessionId });
                  }}
                  onPause={() => {
                    void client.request("task/pause", { id: state.sessionId });
                  }}
                  onStop={() => {
                    void client.request("task/kill", { id: state.sessionId });
                  }}
                /> : null}
              </Box>
              {/* T-6-15: right column with session details +
                  todo board + tokens. Toggleable with Ctrl+D.
                  R362: hidden while a panel is open — it mounts a
                  second TodoBoard whose keys would fight the panel's. */}
              {state.rightPanelVisible ? (
                /* R362: the `activePanel` guard is nested inside the
                   pinned ternary — see the note on the sidebar. */
                state.activePanel ? null : (
                <Box
                  flexDirection="column"
                  width={Math.max(28, Math.min(46, Math.floor((typeof process.stdout.columns === "number" ? process.stdout.columns : 120) * 0.25)))}
                  borderStyle="single"
                  borderColor="gray"
                >
                  <SessionDetailsPanel
                    details={{
                      id: state.sessionId,
                      title: state.sessionId,
                        cwd: currentCwd,
                      model: state.model,
                      // Use 0 as the sentinel for "unknown /
                      // not started yet" so the panel's
                      // formatTimeAgo() returns "—"
                      // instead of chasing Date.now() on
                      // every parent re-render. The
                      // startedAt / lastActiveAt fields
                      // are stamped by the daemon's
                      // session/show RPC; until that
                      // lands, the panel shows "—".
                      startedAt: 0,
                      lastActiveAt: 0,
                      state: state.status,
                      tokensIn: state.inputTokens ?? 0,
                      tokensOut: state.outputTokens ?? 0,
                      messageCount: state.turns.length,
                    }}
                    todoSummary={null}
                  />
                  <Text dimColor>{"─".repeat(20)}</Text>
                  <TodoBoard todos={state.todos} currentTodoId={state.currentTodoId} maxRows={8} />
                </Box>
                )
              ) : null}
            </Box>
          </Box>
        </Box>
      )}
      </AethercodeThemeProvider>
      </ThemeProvider>
    </Box>
  );
};

/**
 * R344: /demo canned conversation.
 *
 * Dispatches a scripted sequence of reducer actions that exercise
 * every visual surface: a user prompt, an assistant stream with
 * markdown, two tool calls (one auto-allowed, one user-decided),
 * a permission modal that the user must dismiss, a status change,
 * and an error path. Each step is gated by a 600 ms timer so the
 * user can watch each surface paint in turn — the goal is to let
 * the user evaluate the visuals without burning tokens or waiting
 * on a real model round-trip.
 *
 * The `client` is accepted for API symmetry with the rest of the
 * TUI helpers but not actually used — the demo dispatches
 * directly into the reducer. Real conversation is at /prompt.
 *
 * R350 (PM P1-4): the demo's permission step is now a REAL
 * wait — we poll `stateRef.current.permissionAsk` and only
 * continue once the user has pressed 1/2/3/4 (or A/Y/D/N) on
 * the inline permission card. Earlier rounds faked the answer
 * via a `setTimeout` 700ms after the ask, which made the demo
 * useless as a UX reference (the user could never see the
 * prompt stay open). The 60s demo ceiling matches the inline
 * card's own "wait 5 min → deny" default compressed for the
 * demo so a user walking away doesn't hang the TUI indefinitely.
 */
async function runDemo(
  dispatch: (a: Action) => void,
  client: JsonRpcClient,
  stateRef: React.MutableRefObject<State>,
): Promise<void> {
  void client;

  const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));
  /**
   * Resolve when `stateRef.current.permissionAsk` becomes null
   * (i.e. the user answered, or the 5-min daemon timeout
   * cleared it). The 60s demo ceiling matches the inline
   * card's 5-min ceiling compressed for the demo so a user
   * walking away doesn't hang the TUI indefinitely.
   */
  const waitForPermissionAnswer = (timeoutMs: number): Promise<"answered" | "timeout"> => {
    return new Promise((resolve) => {
      const start = Date.now();
      const id = setInterval(() => {
        if (stateRef.current.permissionAsk == null) {
          clearInterval(id);
          resolve("answered");
          return;
        }
        if (Date.now() - start > timeoutMs) {
          clearInterval(id);
          resolve("timeout");
        }
      }, 60);
    });
  };

  // Step 1: announce + assistant stream_start.
  dispatch({ type: "log", message: "(demo) starting canned conversation" });
  dispatch({ type: "streamStart" });
  await sleep(400);

  // Step 2: a tool call begins (file_read — low risk, daemon
  // would auto-allow in real use; we just render the running card).
  dispatch({
    type: "streamToolStart",
    name: "file_read",
    args: JSON.stringify({ file_path: "package.json" }),
  });
  await sleep(500);

  // Step 3: tool completes with a result preview.
  dispatch({
    type: "streamToolEnd",
    name: "file_read",
    isError: false,
    result: '{ "name": "aethercode-tui", "version": "0.2.1", ... }',
  });
  await sleep(500);

  // Step 4: streaming text — short reply with markdown.
  dispatch({
    type: "streamText",
    text:
      "Sure — let me read the manifest and check the build pipeline.\n\n" +
      "The TUI is built on **Ink 5** (React for CLI) and bundles into a single " +
      "~2.3 MB ESM file. The desktop side ships a Tauri shell.",
  });
  await sleep(800);

  // Step 5: another tool call — file_write (medium risk).
  dispatch({
    type: "streamToolStart",
    name: "file_write",
    args: JSON.stringify({ file_path: "package.json", content: "{ /* ... */ }" }),
  });

  // Step 6: surface a real permission_request. The inline card
  // renders; the user picks 1/2/3/4 (or A/Y/D/N). We poll
  // stateRef.current.permissionAsk until it goes null, then
  // mirror the real reply logic — allow → ok, deny → tool
  // error.
  dispatch({
    type: "permissionAsk",
    ask: {
      requestId: "demo-perm-1",
      runId: "demo-run",
      tool: "file_write",
      input: { file_path: "package.json", content: "{ /* ... */ }" },
      reason: "file_write requires approval",
      riskLevel: "medium",
    },
  });
  dispatch({
    type: "log",
    message:
      "(demo) permission card is live — press 1/2/3/4 or A/Y/D/N to dismiss it (60s ceiling)",
  });
  const permOutcome = await waitForPermissionAnswer(60_000);
  if (permOutcome === "timeout") {
    dispatch({
      type: "log",
      message: "(demo) permission timed out — treating as deny",
    });
    dispatch({
      type: "streamToolEnd",
      name: "file_write",
      isError: true,
      result: "denied by user (demo timeout)",
    });
  } else {
    // Step 7: tool completes with the user's choice. Without
    // the permission `ask` payload we don't know the exact
    // decision (the inline card sends `permissionResponse`
    // straight to the daemon via `replyPermission`); we
    // default to "ok" because demo asks are mostly Allow.
    // A future round could mirror the real `allow` /
    // `always_allow` / `deny` decision into the
    // `streamToolEnd` result text.
    dispatch({
      type: "streamToolEnd",
      name: "file_write",
      isError: false,
      result: "wrote 47 bytes to package.json (demo: user answered)",
    });
  }

  // Step 8: final assistant prose + run_end with usage stats.
  dispatch({
    type: "streamText",
    text: "\n\nUpdated the manifest and bumped the patch version. Ready for review.",
  });
  dispatch({
    type: "streamEnd",
    stopReason: "end_turn",
    usage: { input: 1200, output: 240, costUsd: 0.012 },
  });
  await sleep(500);

  // Step 9: post-conversation toast.
  dispatch({
    type: "pushToast",
    kind: "info",
    text: "(demo) conversation complete — restart to clear",
  });
}

export async function runTui(opts: TuiOptions): Promise<number> {
  const jar = opts.jar || findJar();
  const client = new JsonRpcClient({
    jarPath: jar,
    cwd: opts.cwd,
    javaBinary: opts.javaBin,
    jvmArgs: opts.jvmArgs,
    onNotification: () => {},
    onExit: (code) => {
      process.stderr.write(`\n[ac-tui] daemon exited (code=${code})\n`);
    },
    // enable auto-reconnect. The user explicitly asked
    // for the daemon to stop "经常断连" — the client now
    // respawns the JVM up to 10 times with exponential
    // backoff (capped at 30s) before giving up. The status
    // bar surfaces the state via process events the App
    // component listens for.
    autoReconnect: true,
    maxReconnectAttempts: 10,
    onConnectionState: (state, info) => {
      // emit on process so the App component's
      // useEffect can dispatch into the reducer. process
      // is the simplest cross-cutting channel that doesn't
      // require restructuring the constructor.
      (process as any).emit("aethercode:connectionState", state, info?.attempt ?? 0, info?.lastError ?? null);
    },
  });
  const stateRef: React.MutableRefObject<State> = { current: INITIAL } as React.MutableRefObject<State>;
  let app: InkInstance | undefined;
  try {
    await client.request("ping", undefined, { timeoutMs: 30_000 });
    app = render(<App client={client} cwd={opts.cwd} stateRef={stateRef} />, { exitOnCtrlC: false });
    await app.waitUntilExit();
  } catch (e) {
    process.stderr.write(`ac-tui: daemon failed to start: ${(e as Error).message}\n`);
  } finally {
    await client.stop().catch(() => undefined);
    app?.unmount();
  }
  return 0;
}
