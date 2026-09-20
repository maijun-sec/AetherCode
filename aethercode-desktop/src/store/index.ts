// Zustand store …single source of truth for the renderer.
// All RPC goes through the AetherCodeRpc singleton (Tauri invoke + listen).

import { create } from 'zustand';
import { invoke } from '@tauri-apps/api/core';
import {
  rpc,
  EngineState,
  ToolInfo,
  SessionInfo,
  TaskInfo,
  ProjectInfo,
  MetricsSnapshot,
  TraceSummary,
  DaemonInfo,
  PreWarmInfo,
} from '../lib/methods';
// re-export TaskInfo so components can
// import it from the store (matches the pattern
// for ChatMessage / ChatStep / ChatSubTask).
export type { TaskInfo } from '../lib/methods';
import { open as openDialog } from '@tauri-apps/plugin-dialog';
// the RpcEvent type. Imported from methods.ts
// so the type flows from the rpc emission through
// the store to the diagnostic panel without a
// duplicated definition.
import type { RpcEvent, RpcMethodInfo } from '../lib/methods';
// child step event type + cap. Imported
// separately so the renderer can re-import the
// type without pulling in the whole store.
import { ChildStepEvent, MAX_STEP_EVENTS } from './types';
export type { ChildStepEvent } from './types';
// pure subagent reducer (see subagentReducer.ts). The
// store wires `subagent_event` JSON-RPC notifications into
// `reduceSubagent`; the StatusBar / toast read the result.
// Re-export the view types so other files don't need to
// import directly from the reducer module.
import {
  INITIAL_SUBAGENT,
  reduceSubagent,
  dismissTerminal as dismissSubagentTerminalPure,
  isOurSession,
  type SubagentState,
  type SubagentJobView,
  type SubagentTerminalEvent,
} from './subagentReducer';
export type { SubagentJobView, SubagentTerminalEvent, SubagentState };

//
// The user picks ONE of three Chinese-labeled tiers in the MessageInput
// dropdown (Ask first / Smart allow / Always allow). The daemon speaks the
// PermissionMode enum (DEFAULT / ASK_BEFORE_TOOL / ACCEPT_EDITS /
// BYPASS_PERMISSIONS / PLAN / AUTO_READ_ONLY / ACCEPT_TASK). We expose
// the same 3-tier mental model to the user but translate to the
// canonical daemon enum on every setPermissionMode call. The mapping is
// also reused by the StatusBar perm pill and the PermissionPromptBanner
// so the user sees the same label everywhere they look.
//
// The "smart" tier maps to ACCEPT_EDITS — that's the engine's existing
// mode name. We did NOT introduce a new PermissionMode enum value; the
// new behaviour is that ACCEPT_EDITS now routes through
// ProjectPermissionPolicy.resolveSmart (the prior round) which auto-allows
// read-only tools + bash read-only commands and asks for mutations. The
// daemon's job is the same as before; the policy's resolution is
// smarter. This keeps every persisted prefs.permissionMode / engine
// state backward-compatible — a build running legacy ACCEPT_EDITS
// means "auto-allow file edits", R203+ means "smart auto-allow for
// read-only + ask for mutations".
//
//  Ask first (ask)    → ASK_BEFORE_TOOL
//  Smart allow (smart)  → ACCEPT_EDITS
//  Always allow (bypass) → BYPASS_PERMISSIONS
//
// DEFAULT, PLAN, AUTO_READ_ONLY, ACCEPT_TASK are not surfaced in the
// 3-tier dropdown (they're advanced modes). Users can still flip to
// them via the TUI slash command / daemon config — the renderer just
// shows the raw enum name in the StatusBar when they're active so the
// power user can see what mode is in effect.
export type UiPermissionMode = 'ask' | 'smart' | 'bypass';
export const UI_PERMISSION_MODES: { value: UiPermissionMode; label: string; title: string; daemon: string[] }[] = [
  { value: 'ask',    label: '主动询问', title: '每个非读操作都让你确认 (默认)',         daemon: ['ASK_BEFORE_TOOL', 'DEFAULT'] },
  { value: 'smart',  label: '智能授权', title: '读/查询/新增自动; 修改/删除/bash 询问', daemon: ['ACCEPT_EDITS'] },
  { value: 'bypass', label: '始终授权', title: '一路绿灯, 不再询问任何调用',           daemon: ['BYPASS_PERMISSIONS'] },
];
/**
 * Map a UI tier (one of {@link UiPermissionMode}) to the
 * canonical daemon enum. Falls through to its input when
 * the value is already a daemon enum (e.g. the engine
 * reports an advanced mode we don't surface, like
 * {@code ACCEPT_TASK}); the daemon-side state stays
 * canonical and we don't want to round-trip the raw enum
 * through this mapping. */
export function mapUiPermissionToDaemon(mode: string): string {
  for (const m of UI_PERMISSION_MODES) {
    if (m.value === mode) return m.daemon[0];
  }
  return mode;
}
/** Inverse of {@link mapUiPermissionToDaemon}. Returns
 *  'ask' / 'smart' / 'bypass' for the 3 surfaced tiers
 *  (matching by ANY of the tier's canonical daemons, so
 *  DEFAULT / ASK_BEFORE_TOOL both map to 'ask'). For
 *  advanced modes (ACCEPT_TASK / PLAN / AUTO_READ_ONLY)
 *  the raw enum name is returned so the StatusBar can
 *  show it verbatim and the user knows it's not one of
 *  the 3 tiers. */
export function mapDaemonToUiPermission(mode: string): UiPermissionMode | string {
  for (const m of UI_PERMISSION_MODES) {
    if (m.daemon.includes(mode)) return m.value;
  }
  return mode;
}
/** Friendly Chinese label for any permission-mode value
 *  (3-tier or advanced). Falls back to the raw enum name
 *  for advanced modes so the StatusBar / banner can show
 *  something meaningful. */
export function permissionModeLabel(mode: string | null | undefined): string {
  if (!mode) return '—';
  for (const m of UI_PERMISSION_MODES) {
    if (m.daemon.includes(mode)) return m.label;
  }
  return mode;
}

// Mirrors AetherCodeMethods.compactToolInput on the Java side. We can't
// reuse the engine's pre-compacted string for stream_event because the
// tool_use_start event carries the raw input, not a trace attr.
function humanizeToolInput(toolName: string, input: unknown): string {
  if (!input || typeof input !== 'object') return '';
  const obj = input as Record<string, unknown>;
  const preferred = (() => {
    switch (toolName) {
      case 'read_file':
      case 'write_file':
      case 'edit_file':
      case 'glob':
      case 'list_directory':
        return ['file_path', 'path', 'pattern', 'directory'];
      case 'bash':
      case 'shell':
        return ['command', 'cmd', 'script'];
      case 'grep':
      case 'search':
      case 'code_search':
        return ['pattern', 'query', 'regex', 'path'];
      case 'web_fetch':
      case 'fetch':
        return ['url', 'uri'];
      case 'web_search':
        return ['query', 'q'];
      default:
        return ['file_path', 'path', 'command', 'query', 'pattern', 'url', 'input'];
    }
  })();
  for (const k of preferred) {
    const v = obj[k];
    if (typeof v === 'string' && v.trim()) {
      const one = v.replace(/\s+/g, ' ').trim();
      return one.length > 120 ? one.slice(0, 117) + '…' : one;
    }
  }
  for (const v of Object.values(obj)) {
    if (typeof v === 'string' && v.trim()) {
      const one = v.replace(/\s+/g, ' ').trim();
      return one.length > 120 ? one.slice(0, 117) + '…' : one;
    }
  }
  return '';
}

// R83 Issue #6: classify a tool name into a counter bucket for
// the step header. Returns the bucket key or null if the tool
// should not be counted (e.g. internal TodoWrite calls).
function toolCategory(name: string): keyof ChatStep['counters'] | null {
  switch (name) {
    case 'read_file':
    case 'file_read':
      return 'fileReads';
    case 'write_file':
    case 'file_write':
    case 'edit_file':
    case 'notebook_edit':
      return 'fileWrites';
    case 'bash':
    case 'shell':
    case 'exec':
      return 'commands';
    case 'grep':
    case 'glob':
    case 'search':
      return 'searches';
    case 'web_fetch':
    case 'web_search':
      return 'web';
    case 'todo_write':
    case 'lsp':
    case 'agent':
      return null;       // internal / not user-facing
    default:
      return 'other';
  }
}

/** convert a wire-format Message (the shape the
 *  daemon's {@code Message.toMap()} returns and that
 *  {@code transcript_event} / {@code getTranscript} push)
 *  into the renderer's {@link ChatMessage}. The two
 *  formats are similar but not identical:
 *  <ul>
 *    <li>Wire role is lowercase
 *        ({@code user} / {@code assistant} / {@code system}
 *        / {@code tool_result}); the renderer's
 *        {@link ChatMessage.role} uses the same set plus
 *        {@code tool} (a synthetic entry the renderer
 *        mints per tool call).</li>
 *    <li>Wire content is a {@code ContentBlock[]} list
 *        with a {@code type} discriminator
 *        ({@code text} / {@code tool_use} / {@code tool_result}).
 *        The renderer flattens this to a plain string for
 *        the {@link ChatMessage.content} field; tool_use
 *        blocks are dropped (the renderer never displays
 *        them inline — they surface as StepCards via
 *        stream_event's tool_use_start).</li>
 *    <li>Wire {@code tool_result} blocks become a
 *        synthetic {@code role: 'tool'} message; the
 *        body is a short preview of the tool's output.</li>
 *  </ul>
 *  Returns {@code null} for messages the renderer
 *  shouldn't surface (e.g. empty / malformed), so the
 *  caller can filter with
 *  {@code .filter(Boolean)}. */
function messageToChatMessage(raw: any): ChatMessage | null {
  if (!raw || typeof raw !== 'object') return null;
  const id = typeof raw.id === 'string' && raw.id
    ? raw.id
    : newId('m');
  const role = String(raw.role ?? 'system').toLowerCase();
  const timestamp = typeof raw.timestamp === 'number' ? raw.timestamp : Date.now();
  const blocks: any[] = Array.isArray(raw.content) ? raw.content : [];
  // Flatten text blocks into a single string. The
  // renderer's MessageList only consumes the `content`
  // string; structured blocks are not displayed
  // inline. tool_use blocks are skipped — the renderer's
  // live UI renders them via the steps[] array, and the
  // historical view doesn't have enough context to
  // reconstruct the StepCards faithfully anyway.
  const text = blocks
    .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text)
    .join('');
  // Wire tool_result blocks become a 'tool' message.
  // (The renderer's tool_use_start handler also adds
  // 'tool' messages live — the historical tool result
  // is a separate piece of information the user might
  // want to see after a reload.)
  const toolResults = blocks.filter((b) => b && b.type === 'tool_result');
  if (toolResults.length > 0) {
    const preview = toolResults.map((tr) => {
      const c = tr.content;
      if (typeof c === 'string') return c;
      try { return JSON.stringify(c); } catch { return String(c); }
    }).join('\n').slice(0, 500);
    return {
      id, role: 'tool', toolName: trToolName(toolResults[0]),
      content: preview, timestamp,
      isError: toolResults.some((tr) => tr.is_error),
    };
  }
  // Map the role. The renderer's ChatMessage.role is a
  // string union; any unknown role falls through to
  // 'system' so the message at least shows up (a
  // missing role would silently disappear).
  let renderRole: ChatMessage['role'];
  switch (role) {
    case 'user':         renderRole = 'user'; break;
    case 'assistant':    renderRole = 'assistant'; break;
    case 'system':       renderRole = 'system'; break;
    case 'tool_result':  renderRole = 'tool'; break;
    default:             renderRole = 'system'; break;
  }
  // R284: pass the daemon's metadata map through so the
  // MessageList can recognise compaction-summary messages
  // and offer "View original". Other message kinds (user
  // / assistant / tool) ignore metadata, so the cost is
  // a shallow object copy on every message — negligible.
  const metadata = (raw && typeof raw === 'object' && raw.metadata
      && typeof raw.metadata === 'object' && !Array.isArray(raw.metadata))
      ? { ...(raw.metadata as Record<string, unknown>) }
      : undefined;
  return { id, role: renderRole, content: text, timestamp, metadata };
}

/** the prior round helper for {@link messageToChatMessage}. The
 *  tool_result block carries a {@code tool_use_id} but
 *  not a tool name; we don't have a lookup map, so
 *  fall back to a generic label. Live tool messages
 *  (the ones the renderer adds via tool_use_start) do
 *  have a name; this is only for the historical view
 *  after a reload. */
function trToolName(tr: any): string | undefined {
  return typeof tr?.tool_use_id === 'string' ? `tool(${tr.tool_use_id.slice(0, 8)})` : undefined;
}

// --- Auto-reconnect + streaming watchdog --------------------------------
// When the daemon dies mid-session (OOM, kill, network blip), the Rust
// WS client emits a `daemon.disconnected` Tauri event. We catch it,
// show a system message, and re-establish with exponential backoff
// (1s/2s/4s/8s/16s/30s cap, 10 attempts max). The watchdog also
// catches the rarer case of a silent half-stream: if `isStreaming` is
// true but no `message.chunk` arrives for 30s, we force-end the
// stream so the input box doesn't get stuck disabled forever.

let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let initializeInFlight: Promise<void> | null = null;
// debounce handle for the input-draft localStorage write.
// Lives at module scope so a setCurrentInput call (which fires
// on every keystroke) doesn't race itself.
let draftTimer: number | null = null;
// periodic poll of getSkipStats. 5 s interval is enough
// resolution for the StatusBar's "skip: 4/7" badge without
// putting pressure on the daemon. Tied to the connectionState
// so the timer doesn't fire while the daemon is down.
// Typed loosely (number | null) because @types/node and the
// DOM lib disagree on whether window.setInterval returns
// number or Timeout — the runtime value is a number either
// way, so we just store the number.
let skipStatsTimer: number | null = null;
// periodic refresh of listTools + listToolActions. The
// legacy-A flow only fetched the tool pool on initialize();
// if that round-trip failed (daemon not ready, WS blip, RPC
// rejection), the renderer's `tools` and `toolActions` arrays
// stayed empty forever and the user had no way to recover —
// the Tools button was disabled, the ToolsPanel showed
// "No tools registered", and the StatusBar hid the "X tools"
// badge. 30 s is enough resolution for "did the daemon come
// back up after a restart" without flooding the WS. The
// tick checks connectionState the same way the skipStats
// timer does.
let toolsTimer: number | null = null;
// periodic refresh of getState (engine state
// snapshot — model, mode, context window, tool count,
// sessionId, loop detector thresholds, ...). The
// the prior round pattern — initial fetch in initialize() +
// 30 s periodic timer + the same connectionState
// guard as the tools / skipStats timers above —
// is applied here to the engine state field. 15 s
// instead of 30 s because engine state changes
// faster than the tool pool (e.g. model switches
// on switchProvider, loop detector thresholds
// after a Settings tweak, maxTurnsPerQuery after
// a /set-max-turns command).
let engineStateTimer: number | null = null;
// RPC event subscription handle. Stored at
// module scope so a re-init (reconnect) can
// unsubscribe the old listener before installing
// a new one. Without this guard, a reconnect
// would double-count events (old listener +
// new listener both push to the same buffer).
let rpcEventUnsubscribe: (() => void) | null = null;

// R289 (2026-09-19): module-local handle for the active
// SsdDriver. Kept out of the Zustand store so the driver
// instance (non-serialisable, holds event handlers + a queue)
// doesn't leak into Redux devtools / getState snapshots.
// startSsdFlow creates + owns; stopSsdFlow tears down; the
// store's event handler closes over `set` to push phase
// updates back into React.
let ssdDriverRef: {
  driver: import('../components/ssd/driver').SsdDriver | null;
  unsubscribe: (() => void) | null;
} = { driver: null, unsubscribe: null };

// R274 step-boundary (2026-09-16): tracks whether the previous
// stream_event was a text_delta. The text_delta handler uses this
// to decide between "accumulate this chunk into the current step"
// (when true, i.e. same think phase) and "close the previous step
// and open a fresh one" (when false, i.e. the previous event was
// a tool_use_start / tool_result / run_start and this is a new
// think phase).
//
// Default `true` because run_start creates the first step with
// empty text; the first text_delta after run_start should
// accumulate into that step, not split off a new one. (The
// run_start handler explicitly resets it back to `true` after
// processing tool events too, so a brand-new run always opens
// with the flag at `true`.)
//
// tool_use_start / tool_result handlers set this to `false` to
// signal "the next text_delta is a new think phase".
//
// (R273 had `let pendingStepBoundary: boolean = false;` instead,
// set on tool_result and consumed on text_delta — but that was
// too aggressive in the OTHER direction: it only split on
// tool_result→text_delta boundaries, so a long think followed by
// many parallel tool_use_starts collapsed into one big think +
// many tools. R274 fixes both directions.)
let prevEventWasText: boolean = true;

// R267 desktop polish (2026-09-14): the user's
// "natural flow" complaint — all thinking on top,
// all tools at the bottom. Root cause: a single
// ChatStep accumulates ALL text_delta + ALL
// tool_use_start events for the entire run, so
// buildBlocks emits exactly one [think][tool][tool]...
// group per sub-task regardless of how many
// model-thinks-between-tools cycles actually
// happened.
//
// Fix: when text_delta arrives immediately after
// a tool_result, close the current step and open a
// new one. The renderer then naturally emits one
// [think][tool] block per model-think-took-tool
// cycle, matching the user's mental model:
//
//   [think1 → tool1 → result1]
//   [think2 → tool2 → result2]
//   [final summary think]
//
// The flag was module-private because it's pure implementation
// state, not part of the React tree. It was flipped on tool_result and cleared on
// text_delta (after we used it to decide whether to open a new step).
// tool_use_start + tool_result pairs without text between them did
// NOT clear the flag — the next text_delta was the signal that the
// model finished its "thinking phase" and started a new one.
// R273 (2026-09-16) strictly subsumes that: every text_delta
// ALWAYS opens a fresh step carrying the new text. Tools
// arriving between this text_delta and the next one land in
// this new step's toolEvents, and buildBlocks emits them in
// the order the LLM produced them ([think,tool,think,tool,…]).
// The flag's dependencies (tool_result assignment + run_start
// reset + text_delta consume) are now dead code and have been
// removed.
//
// R274 (2026-09-16): R273 was wrong. The "always split on every
// text_delta" rule collapsed the LLM's chunked-streamed think
// block into one-step-per-chunk, which renders as a stack of
// single-line "思考 · xxx" details cards the user called "现在的
// 展示方式是在搞笑吗". R274 introduces `prevEventWasText` instead
// of `pendingStepBoundary` and applies the inverse rule:
// consecutive text_deltas accumulate into the same step's text
// (because they are the same think phase), and only the first
// text_delta AFTER a tool_use_start / tool_result triggers a step
// split (because the model just finished a tool and is starting a
// new think phase). R274 strictly subsumes R267 too — splitting
// also on tool_use_start (not just tool_result) catches the
// "text between parallel tool_use_starts" case R267 missed. Example:
//
//   [think1 chunk 1] → step A (text="think1 chunk 1", prevEventWasText=true)
//   [think1 chunk 2] → step A (text="think1 chunk 1 chunk 2", prevEventWasText=true)
//   [tool1]          → prevEventWasText=false, step A.tools=[tool1]
//   [think2]         → split: step B (text="think2"), step A done
//   [tool2]          → prevEventWasText=false, step B.tools=[tool2]
//   [think3 final]   → split: step C (text="think3 final")

// per-session draft helpers. The localStorage key is
// derived from the current sessionId so switching sessions
// restores the right scratchpad. When the user has no current
// session (just hit "+ new session" and hasn't typed yet), we
// fall back to a `__none__` key — the "draft for the next new
// session" slot. A short alphanumeric prefix on the key
// keeps the localStorage inspector easy to scan.
function draftKey(sessionId: string | null): string {
  return `aethercode-input-draft:${sessionId ?? '__none__'}`;
}
function readDraft(sessionId: string | null): string {
  if (typeof window === 'undefined' || !window.localStorage) return '';
  try { return window.localStorage.getItem(draftKey(sessionId)) ?? ''; }
  catch { return ''; }
}
function writeDraft(sessionId: string | null, text: string) {
  if (typeof window === 'undefined' || !window.localStorage) return;
  try {
    const k = draftKey(sessionId);
    if (text.trim().length === 0) window.localStorage.removeItem(k);
    else window.localStorage.setItem(k, text);
  } catch {}
}
function clearDraft(sessionId: string | null) {
  if (typeof window === 'undefined' || !window.localStorage) return;
  try { window.localStorage.removeItem(draftKey(sessionId)); } catch {}
}

//
// Persists the user's engine-related toggles (model,
// permissionMode, loopDetector window/threshold, the
// R120 auto-approve-low-risk flag) across reloads.
// Stored as a single JSON blob at
// `aethercode.enginePrefs` so a future addition is
// just one more field — no schema migration across
// multiple keys.
//
// Failure modes (all silent — localStorage is
// best-effort, the in-memory state is the source of
// truth):
//   - typeof window === 'undefined' (SSR / test)
//   - localStorage disabled (private mode, security policy)
//   - quota exceeded (try/catch around setItem)
//   - malformed JSON from a prior version (we
//     re-derive defaults on parse failure rather
//     than throw)

/** The shape persisted to localStorage. Each
 *  field is optional so a partial entry from an
 *  older build doesn't shadow the daemon's
 *  canonical value (we just leave it alone). */
interface EnginePrefs {
  model?: string;
  /** R285: last-known variant name ("low" /
   *  "medium" / "high" / "xhigh"). Restored
   *  on next boot so the Quality pill row
   *  shows the right active state. */
  variant?: string;
  permissionMode?: string;
  loopWindow?: number;
  loopThreshold?: number;
  autoApproveLowRisk?: boolean;
  // medium+high-risk auto-approve toggle. Off
  // by default — headless opt-in only. Critical
  // risk is NEVER auto-approved regardless of this
  // flag, so the persistence path stores the user's
  // explicit choice without crossing the "did the
  // model run rm -rf without anyone watching" line.
  autoApproveMediumHigh?: boolean;
  // app-start behaviour. 'new' = always mint a
  // fresh session on launch (no cwd leak from the
  // previous run). 'restore' = load the most-recent
  // session and pick up where the user left off.
  defaultOpenBehavior?: 'new' | 'restore';
  // visual theme. 'dark' is the legacy default
  // (the deep amber-on-black palette the TUI / desktop
  // shipped with since R107); 'light' is a fresh white
  // + light-grey surface the user can opt into. The
  // aethercode-themes package ships a richer system
  // (built-in + user YAML), but wiring the full
  // store / hot-reload / RPC plumbing is a T-454
  // follow-up; for now we ship a single binary toggle
  // backed by a localStorage flag. CSS overrides in
  // global.css / App.css / chat.css re-skin every
  // token when `data-theme="light"` is set on the
  // <html> element.
  theme?: 'dark' | 'light';
}
const ENGINE_PREFS_KEY = 'aethercode.enginePrefs';
function readEnginePrefs(): EnginePrefs {
  if (typeof window === 'undefined' || !window.localStorage) return {};
  try {
    const raw = window.localStorage.getItem(ENGINE_PREFS_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || typeof parsed !== 'object') return {};
    return parsed as EnginePrefs;
  } catch {
    return {};
  }
}

// pure helper that decides whether the
// renderer should surface a "your last model
// selection differs from the daemon's default"
// prompt. Exported so the test (enginePrefsR122.test.ts)
// can mock-store + assert behaviour without
// importing the full Zustand store (which pulls
// in WebSocket / Tauri bindings).
//
// Inputs:
//   prefsModel — localStorage enginePrefs.model
//     (the user's last selection, or null/undefined
//     if they never picked one).
//   daemonModel — state.model from the daemon
//     (the canonical, current default).
//   dismissed — the modelMismatchDismissed array
//     (the list of "${prefs}|${daemon}" pairs the
//     user has already dismissed).
//
// Output:
//   - null when no prompt is needed (prefs equals
//     daemon, prefs is empty, daemon is empty, or
//     this exact pair was already dismissed).
//   - { prefsModel, daemonModel } when a prompt
//     is needed.
export function computeModelMismatchPrompt(
  prefsModel: string | null | undefined,
  daemonModel: string | null | undefined,
  dismissed: readonly string[] | null | undefined,
): { prefsModel: string; daemonModel: string } | null {
  if (!prefsModel || !daemonModel) return null;
  if (prefsModel === daemonModel) return null;
  const pair = `${prefsModel}|${daemonModel}`;
  if (dismissed && dismissed.includes(pair)) return null;
  return { prefsModel, daemonModel };
}
function writeEnginePrefs(prefs: EnginePrefs) {
  if (typeof window === 'undefined' || !window.localStorage) return;
  try {
    window.localStorage.setItem(ENGINE_PREFS_KEY, JSON.stringify(prefs));
  } catch {
    // R117 lesson: quota errors should be
    // silent. The in-memory state is the source
    // of truth; the localStorage copy is a
    // nice-to-have for reloads.
  }
}

//
// The multi-session stack (the prior round) lets the user flip between
// sessions in the LeftPanel, but the daemon's `loadSession`
// is still a stub. Until the daemon grows real per-session
// state, the frontend keeps each session's chat history in
// localStorage so a reload or a session switch restores the
// messages the user saw before. The daemon's stream events
// (run_start / text_delta / run_end) still arrive on the
// engine's current session; the frontend's `currentSessionId`
// is a UI-only concept until the daemon's model catches up.
//
// Schema: each persisted session is one localStorage entry
//
//   aethercode-session:<id>     -> { messages, lastUsedAt, createdAt }
//
// A `__none__` id slot is reserved for the "no session yet"
// state (right after the user hits "+ new session" and before the
// first message is sent). We cap each session at
// MAX_PERSISTED_MESSAGES so a chatty long session doesn't
// blow past the 5MB localStorage quota.
//
// the per-session message cache is retired. The
// daemon's appState.transcript is the source of truth;
// the renderer hydrates via transcript_event (live sync)
// and getTranscript (catch-up on reconnect). We keep
// `SESSION_STORAGE_PREFIX` for the one-time migration
// in `migrateLegacyLocalStorage()` — it scans for
// `aethercode-session:*` keys and removes them so the
// localStorage quota isn't carrying dead bytes forward.
const SESSION_STORAGE_PREFIX = 'aethercode-session:';
const RECONNECT_DELAYS_MS = [1000, 2000, 4000, 8000, 16000, 30000];
const MAX_RECONNECT_ATTEMPTS = 10;
// R172 daemon-stability: the previous 30s threshold was
// tripping on legitimate long tool runs (e.g. "generate
// a Maven project" → the engine writes 10+ files back to
// back, the chat client buffers the model stream, and
// the gap between `tool_use_start` and `tool_result` for
// the last file write can easily exceed 30s on a slow
// disk or a multi-megabyte pom.xml). The user saw
// "[Stream stale] Daemon stopped responding for 30s" as
// a red banner even though the engine was happily
// working — a false positive that confused the user into
// thinking the daemon was wedged. Bumping the threshold
// to 90s gives the same complex workflow room to
// breathe, and the user still gets a real signal if the
// daemon actually does go silent for >90s. (For a
// shorter watchdog, set AETHERCODE_STREAM_STALE_MS in
// the env — the watchdog below reads it once at module
// load so a power user can re-tune without rebuilding.)
function readStreamStaleMs(): number {
  try {
    const raw = (typeof process !== 'undefined' && process.env && process.env.AETHERCODE_STREAM_STALE_MS) || '';
    const n = parseInt(raw, 10);
    if (!isNaN(n) && n >= 5000 && n <= 10 * 60_000) return n;
  } catch {}
  return 90_000;
}
const STREAM_STALE_MS = readStreamStaleMs();
const STREAM_CHECK_INTERVAL_MS = 5_000;

type SetStateFn = (partial: Partial<AppState> | ((s: AppState) => Partial<AppState>)) => void;
type GetStateFn = () => AppState;

function scheduleReconnect(set: SetStateFn, get: GetStateFn) {
  if (reconnectTimer) return; // already scheduled
  const attempt = get().reconnectAttempts;
  if (attempt >= MAX_RECONNECT_ATTEMPTS) {
    set((s) => ({
      connectionState: 'error' as ConnectionState,
      messages: [...s.messages, {
        id: newId('system'), role: 'system' as const,
        content: `[Reconnect failed] ${MAX_RECONNECT_ATTEMPTS} attempts exhausted. Reload the window to retry.`,
        timestamp: Date.now(), isError: true,
      }],
    }));
    return;
  }
  const delay = RECONNECT_DELAYS_MS[Math.min(attempt, RECONNECT_DELAYS_MS.length - 1)];
  console.log(`[store] reconnect attempt ${attempt + 1}/${MAX_RECONNECT_ATTEMPTS} in ${delay}ms`);
  reconnectTimer = setTimeout(async () => {
    reconnectTimer = null;
    set({ reconnectAttempts: attempt + 1 });
    try {
      await get().initialize();
    } catch {
      // initialize() already pushed an error into initError; try again
      scheduleReconnect(set, get);
    }
  }, delay);
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
  timestamp: number;
  toolName?: string;
  isError?: boolean;
  /** R82+ Issue 1: set when the assistant message has finished
   *  streaming (on run_end). The renderer uses this to switch
   *  from plain text to react-markdown — markdown re-parse on
   *  every text_delta is janky for long messages. */
  isComplete?: boolean;
  /** R284: the daemon's metadata map for this message.
   *  Surfaced so the renderer's MessageList can recognise
   *  compaction-summary messages (kind === 'compaction-summary')
   *  and offer a "View original (N msgs)" affordance that
   *  loads the pre-compaction transcript via
   *  {@code compact/getSnapshot}. Optional because the
   *  vast majority of messages don't carry metadata; the
   *  renderer's branch keys off the {@code kind} string
   *  rather than field presence. */
  metadata?: Record<string, unknown>;
}

/** R83 Issue #6: a step is one engine round-trip. The
 *  MessageList groups events into step cards and shows a
 *  header with counters (think / file-read / command / edit).
 *  Tool events carry the tool name + an input summary +
 *  result preview. */
export interface ChatStep {
  id: string;
  /** id of the sub-task this step belongs to. The
   *  MessageList uses this to nest step cards inside the
   *  matching sub-task card. Null = legacy steps or steps
   *  emitted before any sub-task was declared. */
  subTaskId: string | null;
  /** R278 (2026-09-17): the daemon-emitted run id from
   *  the {@code run_start} stream event that opened this
   *  step. Two consecutive user prompts (no sub-task
   *  declared) produce two sets of steps — both end up
   *  in the renderer's {@code pre[]} bucket because
   *  neither step has a subTaskId. Without {@code queryId},
   *  the timeline emitted ONE preamble event covering
   *  both queries' steps, with a ts of the FIRST step
   *  (i.e. the older one), so the sort put the user
   *  bubble of the SECOND query AFTER the merged
   *  preamble. Visually: "new prompt's output is on top
   *  of the old prompt". Grouping by queryId gives each
   *  user query its own preamble, sorted by the new
   *  query's step start, so the user bubble lands above
   *  the new preamble and below the previous preamble.
   *  Empty string for legacy steps written before R278. */
  queryId?: string;
  startedAt: number;
  endedAt?: number;
  /** Accumulated text from text_delta events. */
  text: string;
  /** Tool calls in this step, in arrival order. */
  toolEvents: {
    id: string;
    name: string;
    inputSummary: string;
    output?: string;
    isError?: boolean;
    ts: number;
    /** R267 desktop polish: raw input from the
     *  tool_use_start event. Stored so the renderer
     *  can build a real diff for file_edit
     *  (old_string + new_string → unified diff)
     *  without round-tripping back to the daemon —
     *  the protocol does not currently forward
     *  attachments through tool_result, so this is
     *  the only place the desktop has the raw
     *  before/after text. Undefined for tools whose
     *  input doesn't need to be replayed (file_read,
     *  bash, web_search, ...). */
    input?: Record<string, unknown>;
  }[];
  /** Per-tool-kind counters for the header. */
  counters: {
    thinks: number;
    fileReads: number;
    fileWrites: number;
    commands: number;
    searches: number;
    web: number;
    other: number;
  };
  /** True after the run_end event fires. */
  done: boolean;
  /** Stop reason from the engine, e.g. "end_turn" / "max_iterations". */
  stopReason?: string;
}

/** a top-level todo item, captured verbatim from the model's
 *  most recent `todo_write` call. Stored as plain data so the
 *  RightPanel "Plan" tab can render a multi-level list without
 *  re-deriving it from the step / sub-task event stream. */
export interface TodoSubTask {
  /** Stable id the model assigned (UUID or human-readable). */
  id: string;
  content: string;
  /** Default status as declared by the model. The runtime
   *  status from `sub_task_start` / `sub_task_end` events
   *  takes precedence when available. */
  status: 'pending' | 'in_progress' | 'completed' | 'failed' | 'skipped';
  /** Optional one-line summary written when the sub-task closes. */
  summary?: string;
  /** 0-based index inside the parent task's `subtasks[]`. */
  index: number;
}
export interface TodoItem {
  content: string;
  /** Lifecycle status from the model. */
  status: 'pending' | 'in_progress' | 'completed' | 'cancelled' | 'skipped';
  /** Optional present-tense form ("Implementing parser" — shown
   *  while the todo is in_progress). */
  activeForm?: string;
  /** Optional nested sub-tasks. Empty when the model declared a
   *  flat plan. */
  subtasks: TodoSubTask[];
  /** 0-based index in the current `currentTodos` array. */
  index: number;
  /** Wall-clock ms when the model last set this todo. Used by
   *  the panel to age out stale rows when the session rotates. */
  updatedAt: number;
}

/** a sub-task is a business-concept unit within a top-level
 *  todo. Example: "complete module XX's test. The model declares it via
 *  `todo_write` with a `subtasks[]` field, and closes it via
 *  `sub_todo_write` with a status change. The UI groups all
 *  model calls + tool calls that happen between the start and
 *  end events under a `SubTaskCard`. */
export interface ChatSubTask {
  /** Stable id; usually the composite "taskIdx:subTaskId"
   *  the engine emits. */
  id: string;
  /** 0-based index of the parent top-level todo. */
  taskId: number;
  /** Id of the sub-task as assigned by the model. */
  subTaskId: string;
  /** Short description (the business concept the model
   *  committed to, e.g. "complete module XX's test). */
  content: string;
  /** Current status: "pending" / "in_progress" /
   *  "completed" / "failed" / "skipped". The UI uses the
   *  status to colour the card and pick the right icon. */
  status: 'pending' | 'in_progress' | 'completed' | 'failed' | 'skipped';
  /** Optional one-line summary the model wrote when closing
   *  the sub-task. The UI shows it as a footer on the
   *  SubTaskCard. */
  summary?: string;
  /** id of the {@link ChatStep} that opened this sub-task
   *  (the first think in this business unit). Null if no
   *  step ran yet. */
  firstStepId: string | null;
  startedAt: number;
  endedAt?: number;
}

export type ConnectionState = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'closed' | 'error' | 'awaiting-cwd';

/** live engine health snapshot. Mirrors
 *  {@code org.aethercode.core.concurrency.EngineStats.toMap()}.
 *  Only the fields the desktop actually reads are typed;
 *  the rest are pass-through {@code number} / {@code boolean}
 *  so the store doesn't have to track every daemon field. */
export interface EngineStatsView {
  memUsedBytes: number;
  memMaxBytes: number;
  memUsedMb: number;
  memMaxMb: number;
  memPct: number;
  throttled: boolean;
  backpressured: boolean;
  queriesInFlight: number;
  maxConcurrentQueries: number;
  toolsInFlight: number;
  maxConcurrentTools: number;
  branchesInFlight: number;
  maxConcurrentBranches: number;
  concurrencyProfile: string;
  sampledAtMs: number;
}

export interface PermissionRequest {
  requestId: string;
  tool: string;
  input: unknown;
  reason?: string;
  /** backend-computed risk level (e.g. "low" / "medium" /
   *  "high" / "critical"). Drives the colour cue in
   *  PermissionList. Optional for backwards compatibility. */
  riskLevel?: 'low' | 'medium' | 'high' | 'critical' | string;
  receivedAt: number;
}

interface AppState {
  daemonInfo: DaemonInfo | null;
  isConnected: boolean;
  connectionState: ConnectionState;
  initError: string | null;
  engineState: EngineState | null;
  // wall-clock ms of the last successful
  // refreshEngineState() call. Used by the R116
  // diagnostic panel to show how stale the displayed
  // engine state is ("engine state: 5s ago"). 0 = never
  // refreshed. The initialization-time fetch in
  // initialize() seeds this with Date.now() so the
  // first paint after launch shows "just now" rather than
  // "never fetched".
  engineStateRefreshedAt: number;
  tools: ToolInfo[];
  // per-tool permission action assessment from
  // listToolActions. Parallel to `tools` but with the
  // safe/ask/deny classification for each one. Empty until
  // the boot RPC completes.
  toolActions: import('../lib/methods').ToolActionInfo[];
  // wall-clock ms of the last successful refreshTools().
  // Used by the ToolsPanel's empty state to show the user how
  // stale the displayed list is ("last fetched: 30s ago"). 0 = never
  // refreshed (e.g. initialize() just succeeded but the panel
  // is reading from a store still warming up).
  toolsRefreshedAt: number;
  // JSON-RPC method names served by the
  // daemon. Pulled from GET /api/methods (HTTP
  // endpoint, not WS) when the user first opens the
  // RPC command palette, then cached. The list
  // rarely changes (only on daemon restart), so a
  // single fetch is enough. Empty until the first
  // loadRpcMethods() succeeds.
  rpcMethods: string[];
  // the tagged counterpart of rpcMethods.
  // Each entry is {name, tags[]} so the R121
  // palette's chip-bar can filter by tag. Older
  // daemons (legacy) return a flat-name list;
  // in that case this field stays empty and the
  // palette hides the chip bar.
  rpcMethodInfos: RpcMethodInfo[];
  // rolling buffer of the most recent RPC
  // round-trips. The diagnostic panel reads this
  // array to render a per-call list (timestamp,
  // method, duration, status, expandable payload).
  // The cap (50) is enforced by recordRpcEvent;
  // a higher cap would make the panel render
  // slower and a lower cap would make 5-minute-old
  // failures scroll out of view. Each entry is a
  // shallow JSON clone of the params (deep
  // clones are too expensive for the inner-loop
  // recording). Newest-first.
  recentRpcEvents: RpcEvent[];
  // daemon-side auto-approve-low-risk flag
  // (mirrored from the engineState on initialize
  // and kept in sync via the setter). Default true
  // (R87 behaviour); a user who wants manual
  // control flips the StatusBar toggle. The store
  // is the source of truth for the UI's current
  // value; the daemon's value can drift if a
  // different client flips it (e.g. a CLI
  // `--no-auto-approve` flag in a future round).
  autoApproveLowRisk: boolean;
  // medium+high-risk auto-approve toggle.
  // Distinct from autoApproveLowRisk so the user
  // can keep the everyday read-only shortcut on
  // while opting into high-risk auto-allow for a
  // headless / scripted run. Critical risk is
  // NEVER auto-approved regardless of this flag.
  // Default false — headless opt-in only.
  autoApproveMediumHigh: boolean;
  // cumulative count of low-risk tool calls
  // auto-approved since the daemon started. The
  // daemon increments and emits in the same
  // notification, so the store just mirrors the
  // value. The StatusBar badge shows this number
  // so the user always knows how many prompts the
  // daemon short-circuited on their behalf.
  autoApprovedCount: number;
  // cumulative count of medium / high-risk
  // tool calls auto-approved via
  // autoApproveMediumHigh. Tracked separately so
  // the UI can warn ("you've auto-approved N
  // elevated-risk calls — was that intentional?")
  // without conflating it with the everyday
  // read-only shortcuts.
  autoApprovedElevatedCount: number;
  // rolling buffer of the 10 most-recent
  // auto-approved tool calls. The StatusBar badge's
  // tooltip shows the recent list ("last 5 min:
  // file_read × 12, grep × 8, glob × 3"). The cap
  // matches the badge's "recent activity" affordance
  // — a longer history would be useful for
  // debugging but lives in the RpcDiagnosticsPanel
  // (the prior round) instead, where the user can scroll.
  recentAutoApproved: { tool: string; atMs: number }[];
  // skip-confirmation adoption stats from getState /
  // getSkipStats. Default zero state so the StatusBar doesn't
  // flash "skip: 0/0" before the first getState resolves.
  skipStats: { consumed: number; armed: number; prompts: number; adoption: number };
  sessions: SessionInfo[];
  currentSessionId: string | null;
  tasks: TaskInfo[];
  currentTaskId: string | null;
  projects: ProjectInfo[];
  currentProjectId: string | null;
  messages: ChatMessage[];
  isStreaming: boolean;
  currentInput: string;
  /** R267 desktop polish: a follow-up prompt the user
   *  typed while the previous run was still streaming.
   *  The store queues it instead of dropping it (the
   *  previous behaviour silently ignored the input —
   *  the user clicked Enter and "nothing happened",
   *  then got frustrated and clicked again).
   *
   *  Lifecycle:
   *    1. user types + sends while isStreaming=true →
   *       currentInput → pendingFollowUp, currentInput
   *       cleared so the user can keep typing the NEXT
   *       prompt.
   *    2. current run_end fires →
   *       pendingFollowUp auto-starts as the next run.
   *    3. user clicks "cancel" → current run cancelled
   *       AND pendingFollowUp dropped (the user wanted
   *       to stop everything, not just this run).
   *
   *  Only one slot — second+ queued prompts overwrite
   *  the previous. The user explicitly said "我会自己
   *  cancel 前一个, 再提交后一个" (I'll cancel the previous
   *  one before submitting the next), so a single-slot
   *  queue matches the user's mental model. */
  pendingFollowUp: string | null;
  model: string;
  permissionMode: string;
  enabledTools: Set<string> | null;
  metrics: MetricsSnapshot | null;
  traces: TraceSummary[];
  pendingPermissions: PermissionRequest[];
  /** tools the user has marked as "always allow for this
   *  session". Currently a UI hint only — the engine doesn't yet
   *  take this into account when auto-approving, so a future
   *  R86+ cycle will need to plumb this through an RPC. */
  alwaysAllowedTools: Set<string>;
  cwd: string | null;
  reconnectAttempts: number;
  lastChunkTs: number;
  /** wall-clock ms of the last hydrateTranscript
   *  call (success OR failure). The disconnect handler
   *  uses this to decide whether to force a re-hydrate
   *  after a WS reconnect, and the renderer can use it
   *  to surface a "transcript may be stale" badge if
   *  the last sync was a long time ago. */
  lastTranscriptSyncMs: number;
  availableModels: { id: string; inputPer1k: number; outputPer1k: number; default: boolean }[];
  /** set while a user-initiated cwd switch is in
   *  flight (kill old daemon + spawn new). The ReconnectBanner
   *  surfaces a specific message during this so the user knows
   *  the 1-2s delay is intentional, not a hang. */
  cwdSwitchInProgress: boolean;
  cwdSwitchTarget: string | null;
  /** one-time mismatch prompt. Set when
   *  the localStorage `enginePrefs.model` differs
   *  from the daemon's canonical `state.model`.
   *  The MessageInput bar watches this and
   *  surfaces a toast / inline banner. The
   *  user clicks "switch" to restore the old
   *  model (which writes both daemon + prefs
   *  via the setModel action) or "keep
   *  ${daemonModel}" to dismiss. The dismissal
   *  records the (prefsModel, daemonModel) pair
   *  in {@code modelMismatchDismissed} so the
   *  same mismatch doesn't fire again on the
   *  next launch. The pair key is
   *  `${prefsModel}|${daemonModel}` — a user
   *  who switches daemon model later (e.g.
   *  upgrades to M4) gets a fresh prompt. */
  modelMismatchPrompt: { prefsModel: string; daemonModel: string } | null;
  /** list of "${prefsModel}|${daemonModel}" pairs the
   *  user has dismissed. Persisted in localStorage
   *  so the same (M1, M3) mismatch doesn't re-prompt
   *  on every launch. A different pair (e.g. M1, M4
   *  after an upgrade) IS re-prompted because the
   *  user hasn't seen that combination before. */
  modelMismatchDismissed: string[];
  /** Phase of the current connection-state transition, for the
   *  ReconnectBanner. the prior round: stages the user sees during a
   *  cwd switch so they understand the latency. */
  // 3-layer memory facade mirrored in the store. The
  // USER / PROJECT / SESSION scopes are populated on demand
  // by `refreshMemory(scope)` (called when the user opens
  // the right-rail Memory tab) and kept fresh by the
  // memory_event notification (which the daemon fires on
  // any change). The `memoryStats` map carries the daemon's
  // reported entry counts + compression thresholds so the
  // UI can show "18 / 50 entries (auto-compress on)" without
  // an extra round-trip. `lastCwdChangedAt` is updated on
  // every NOTIFY_CWD_CHANGED so the right panel can show
  // "cwd changed 5s ago" for a moment after a switch.
  memory: {
    user: { entries: Array<{ key: string; content: string; tags: string[]; createdAt: string; updatedAt: string }>; count: number };
    project: { entries: Array<{ key: string; content: string; tags: string[]; createdAt: string; updatedAt: string }>; count: number; cwd: string | null };
    session: { entries: Array<{ key: string; value: string; createdAtMs: number; updatedAtMs: number }>; count: number; sessionId: string | null };
  };
  memoryStats: {
    autoCompress: boolean;
    projectCompressThreshold: number;
    keepRecent: number;
  } | null;
  lastMemoryRefreshMs: number;
  lastCwdChangedAt: number;
  transitionPhase: 'idle' | 'killing-old' | 'spawning-jvm' | 'health-check' | 'loading-state';
  /** how the renderer should behave on app start.
   *   - 'new'   : always create a fresh session (no cwd); the
   *                previous session's cwd is forgotten.
   *   - 'restore': load the most-recently-used session; the
   *                user picks up where they left off.
   *   The default is 'new' (we err on the side of clean
   *   state so a stale session doesn't leak context into
   *   a fresh launch). Users can flip this in Settings. */
  defaultOpenBehavior: 'new' | 'restore';
  setDefaultOpenBehavior: (b: 'new' | 'restore') => void;
  /** visual theme. 'dark' is the legacy default
   *  (the deep amber-on-black palette the TUI / desktop
   *  shipped with since R107); 'light' is a fresh white
   *  + light-grey surface. The renderer mirrors the
   *  value to `document.documentElement.dataset.theme`
   *  in a useEffect so CSS [data-theme="light"] rules
   *  re-skin every component. The setting is persisted
   *  via {@code prefs.theme} (R209 source-pin: do not
   *  introduce a separate localStorage key). */
  theme: 'dark' | 'light';
  setTheme: (t: 'dark' | 'light') => void;
  /** R82+ Issue 1: derived from stream_event types to give the
   *  center panel a "where execution is at" indicator above the
   *  message list. Set on run_start / tool_use_start / run_end /
   *  errors. Cleared automatically after a short lived-done window. */
  currentActivity: { kind: 'thinking' | 'tool' | 'done' | 'error'; label: string; ts: number } | null;
  /** true while the engine is in the middle of compacting
   *  the transcript (between the `compaction` SideNote "started:"
   *  and the matching "completed:" event). Drives a "compacting…"
   *  badge in the activity indicator so the user sees the work
   *  in flight, not just the post-hoc summary. */
  compactionInProgress: boolean;
  /** R82+ Issue 3: sibling daemon kept warm so a cwd switch into
   *  its directory is sub-second. `cwd` is the directory it serves;
   *  `info` is the DaemonInfo (port etc.) so we can swap it into
   *  the primary slot without re-spawning. `lastSwappedAt` records
   *  the last successful swap (for debugging / UI). */
  preWarm: { cwd: string; info: PreWarmInfo; since: number } | null;
  /** per-todo adaptive control. When the engine hits the
   *  max-bumps limit, the run pauses with stop reason
   *  "awaiting_user_decision" and we surface a special input
   *  prompt. The next `query()` is treated as the user's
   *  continuation decision. */
  awaitingUserDecision: { summary: string; todoSteps: number; softThreshold: number } | null;
  /** R83 Issue #1+2+4: the current user query is the de-facto
   *  "active task" in the UI sense, even though the engine's
   *  {@code tasks} list only carries Subagent tasks. The Header
   *  shows this, the Progress bar tracks its step count, and
   *  the Tasks list injects it as a synthetic running row. */
  currentQuery: { id: string; prompt: string; startedAt: number; stepCount: number; toolCount: number } | null;
  /** R83 Issue #6: a "step" is one round-trip through the engine
   *  (run_start →…→run_end). The MessageList groups events
   *  into steps and shows a counter header per step
   *  (e.g. "think 3 times, read 2 files, run 1 command). */
  steps: ChatStep[];
  /** R83 Issue #6: id of the step currently being appended to.
   *  The MessageList uses this to keep the active card
   *  auto-scrolled. Null when no query is in flight. */
  currentStepId: string | null;
  /** R83 Issue #6: which steps are expanded (UI-only). Steps
   *  with no entry default to expanded (so the active card is
   *  always open). */
  expandedStepIds: Record<string, boolean>;
  /** which workflow step's detail modal is open, if
   *  any. Set by clicking a done/errored pill in the
   *  WorkflowProgressBar; cleared by closing the modal. The
   *  runId guard ensures stale clicks from a previous run
   *  don't reopen a finished run's modal. */
  selectedStepDetail: { runId: string; stepId: string } | null;
  /** sub-task cards (business-concept units). Each
   *  SubTaskCard contains its own step cards. Steps belong
   *  to a sub-task via the ChatStep.subTaskId field. */
  subTasks: ChatSubTask[];
  /** the most recent todo list the model emitted via
   *  `todo_write`. Captured verbatim from the tool_use_start
   *  event's raw `input.todos` (the engine sends the full
   *  list on every call so it's always self-consistent).
   *  Empty until the first `todo_write` of a session, and
   *  reset on a fresh user query. The RightPanel "Plan" tab
   *  reads this only as a fallback — see {@link todos} for
   *  the canonical daemon-driven field. */
  currentTodos: TodoItem[];
  /** daemon-driven todo list. Populated by the
   *  `task_state kind=todo_update` JSON-RPC notification
   *  (AetherCodeMethods.NOTIFY_TASK_STATE, fired from
   *  AppState.onTodoUpdate after every setTodoList call).
   *  This is the canonical source — the engine just
   *  published the list, so the renderer is looking at the
   *  same data the CLI / TUI sees. R228's `currentTodos`
   *  captures the same data one layer removed (from the
   *  tool_use_start event's input) and is kept as a
   *  fast-path fallback for the sub-second window before
   *  the notification round-trips through the WS bridge.
   *
   *  Note: the field is named {@code daemonTodos} (not
   *  {@code todos}) to avoid a name clash with the R200+
   *  {@code todos} field above, which has a different flat
   *  shape ({@code {id, title, status, ...}}) and was only
   *  referenced by the prior round+ TodoBoard component. R229
   *  treats the dead field as legacy and uses the nested
   *  {@link TodoItem} shape end-to-end. R230 deletes the
   *  dead TodoBoard (and its CSS / tests), so the only
   *  live consumer of `todos` is this comment. */
  daemonTodos: TodoItem[];
  /** live engine stats, polled every 5s by the
   *  StatusBar. The shape mirrors
   *  {@code org.aethercode.core.concurrency.EngineStats.toMap()}.
   *  When the field is null the StatusBar skips the
   *  memory / throttle pills (the daemon is not reachable
   *  or hasn't replied yet). */
  engineStats: EngineStatsView | null;
  /** id of the sub-task currently being worked on.
   *  The MessageList highlights this card and auto-expands
   *  its step list. Null when no sub-task is in_progress. */
  currentSubTaskId: string | null;
  /** workflow picker. The desktop input bar lets the
   *  user attach a workflow to the next message; the engine
   *  reads the YAML, walks the steps, and emits
   *  `workflow_step` side notes. `availableWorkflows[]` is
   *  populated by `refreshWorkflows()` (called on app start
   *  + when the picker is opened). `activeWorkflow` is the
   *  workflow the user has selected for the NEXT message;
   *  it's cleared on send so the next message starts clean.
   *  `runningWorkflow` is the workflow currently being
   *  executed (set when runWorkflow() is called, cleared
   *  when the matching run ends). The progress bar reads
   *  runningWorkflow + workflowSteps to render the step
   *  pill row. */
  availableWorkflows: import('../lib/methods').WorkflowDoc[];
  activeWorkflow: import('../lib/methods').WorkflowDoc | null;
  // LRU of recently-selected workflow names.
  // The picker renders the most-recent 5 above the
  // full list so a user who runs the same workflow
  // repeatedly doesn't have to re-find it in the
  // popup. The store handles the LRU semantics
  // (push-to-front, dedupe, cap at 5) so the
  // component is dumb. Persistence is localStorage
  // keyed by cwd (a workflow that's recent in
  // project A shouldn't appear at the top of the
  // picker in project B).
  recentWorkflows: string[];
  runningWorkflow: {
    name: string;
    runId: string;
    steps: import('../lib/methods').WorkflowStep[];
    /** Map of step id -> status. The daemon emits 'pending'
     *  for each step on run start; R103 will move them to
     *  'running' / 'ok' / 'error' as the executor advances. */
    stepStatus: Record<string, 'pending' | 'running' | 'ok' | 'error'>;
    startedAt: number;
    /** per-step child session events, used by
     *  WorkflowProgressBar to render the nested activity
     *  list under the running step pill. Capped to the
     *  last {@link MAX_STEP_EVENTS} entries per step
     *  (the renderer shows the most recent N; older
     *  events are discarded so the in-memory shape
     *  stays bounded across a long workflow). Cleared
     *  when the workflow finishes (runningWorkflow
     *  becomes null). */
    stepEvents: Record<string, import('./types').ChildStepEvent[]>;
  } | null;
  /** when the engine's loop detector hits tier 1 or
   *  tier 2, it emits a side_note of kind "loop-warn-1" /
   *  "loop-warn-2" with the raw cause kind (e.g. "long_output",
   *  "same_fingerprint"). The LoopGuardBanner reads this state
   *  and shows the soft warning + "continue" / "stop" buttons.
   *  Null when no warning is active. Cleared on:
   *    - user clicking "continue" → acknowledgeLoop() RPC resets
   *      the detector tier and clears this state
   *    - user clicking "stop" → cancelQuery() + clears state
   *    - 8s auto-dismiss (handled in the banner itself, not
   *      the store, so a re-render doesn't reset the timer)
   *    - run_end event (so a fresh query starts clean). */
  loopWarn: { kind: string; tier: 1 | 2; runId: string; message: string; ts: number } | null;
  /** live snapshot of the engine's background
   *  subagent jobs. Mirrors the engine's
   *  {@code SubagentRegistry} via the
   *  {@code subagent_event} JSON-RPC notification. The
   *  StatusBar shows {@link SubagentState.status} as a
   *  one-line label; the toast component reads
   *  {@link SubagentState.lastTerminal} and renders a
   *  4-second auto-dismiss notification. */
  subagent: SubagentState;

  initialize: () => Promise<void>;
  refreshModels: () => Promise<void>;
  setCurrentInput: (s: string) => void;
  sendMessage: () => Promise<void>;
  cancelQuery: () => Promise<void>;
  /** R267 desktop polish: drop the queued follow-up
   *  prompt (if any). Called by the UI's "cancel queued"
   *  button or when the user manually dismisses the
   *  queue indicator. Idempotent — safe to call when
   *  the queue is empty. */
  cancelPendingFollowUp: () => void;
  switchSession: (sessionId: string) => Promise<void>;
  /** clear currentSessionId locally without calling the
   *  daemon. Used by the SessionList "+" button to start a
   *  fresh session — the next query() lands without a
   *  sessionId, so the daemon creates a new one. */
  setCurrentSessionId: (id: string | null) => void;
  /** drive a `loadSession` RPC + hydrate the
   *  transcript for a new session id. Used by the
   *  R214 subagent view switch (Ctrl+2..9 in the TUI,
   *  "View subagent →" in the desktop) and any other
   *  in-place session switch that doesn't go through
   *  the regular SessionList flow. */
  loadSession: (id: string) => void | Promise<void>;

  /** live TODO list for the active session. The host
   *  subscribes to `todo_update` notifications (handled in
   *  AgentTasksPanel's own useEffect / event handler — store
   *  doesn't duplicate the subscription) and dispatches
   *  `setTodos` with the latest snapshot. R230: this comment
   *  used to point at the now-deleted R200+ TodoBoard. */
  todos: Array<{
    id: string;
    title: string;
    status: 'pending' | 'in_progress' | 'completed' | 'cancelled';
    owner?: string | null;
    startedAt?: number | null;
    completedAt?: number | null;
    detail?: string | null;
  }>;
  /** which TODO the engine is currently working on
   *  (status=in_progress, first by startedAt). Surfaced by
   *  AgentTasksPanel (R228+) so the user can see "which one is executing".
   * this comment used to point at the now-deleted
   *  R200+ TodoBoard. */
  currentTodoId: string | null;
  /** id of the subagent whose transcript is currently
   *  being viewed. {@code null} = primary agent. Switching
   *  drives a `loadSession` RPC + the same hydration path
   *  used by SessionList's switch. Mirrors the TUI's
   *  Ctrl+1..9 quick-switch. */
  viewingSubagentId: string | null;
  /** breadcrumb stack for the view. Pop one to
   *  return to the previous view (primary or another
   *  subagent). */
  viewHistory: Array<{ kind: 'primary' } | { kind: 'subagent'; jobId: string }>;
  /** setters wired below. `setViewingSubagentId` is
   *  optional on the public type so legacy callers
   *  that destructure the store with a strict shape
   *  don't break. */
  setViewingSubagentId?: (id: string | null) => void;
  /** back-fill `messages` from the daemon's
   *  in-memory transcript for the active session. Called
   *  on app start, after every session switch, and on
   *  WS reconnect. Returns the messages it set so the
   *  caller can chain (e.g. switchSession awaits this
   *  before flipping `isStreaming` / scroll state). */
  hydrateTranscript: (sessionId: string | null) => Promise<ChatMessage[]>;
  /** start a brand-new local session. Generates a
   *  UUID, switches currentSessionId to it, and clears the
   *  messages. The session shows up in the LeftPanel
   *  immediately and the input draft is keyed by the new
   *  id (the prior round). Persisted as a fresh entry on the first
   *  message send. */
  createNewSession: () => void;
  /** delete a session from the daemon's SessionStore
   *  and from the localStorage cache. Refuses to delete the
   *  active session (the daemon rejects with
   *  IllegalStateException; the surface message explains
   *  the user must switch first). */
  deleteSession: (sessionId: string) => Promise<void>;
  refreshSessions: () => Promise<void>;
  refreshTasks: () => Promise<void>;
  /** pull the latest 3-layer memory state from the
   *  daemon. Cached per scope so the right-rail Memory
   *  tab can flip between USER / PROJECT / SESSION
   *  without re-fetching. Best-effort: a network blip
   *  keeps the previous list (matches the pattern
   *  of refreshSessions / refreshTasks). */
  refreshMemory: (scope: 'USER' | 'PROJECT' | 'SESSION') => Promise<void>;
  /** append / set an entry in the chosen scope.
   *  For PROJECT scope, the daemon adds a timestamped
   *  change-log line; for USER it's a regular
   *  FileBackedMemory add; for SESSION it's a
   *  SessionMemoryStore key/value put. The renderer's
   *  MemoryPanel "+" button calls this. */
  memorySet: (opts: {
    scope: 'USER' | 'PROJECT' | 'SESSION';
    key: string;
    content: string;
    value?: string;
    tags?: string[];
    sessionId?: string | null;
    cwd?: string | null;
  }) => Promise<void>;
  /** delete a single entry. The MemoryPanel
   *  row's "..." menu calls this. */
  memoryDelete: (opts: {
    scope: 'USER' | 'PROJECT' | 'SESSION';
    key: string;
    sessionId?: string | null;
    cwd?: string | null;
  }) => Promise<void>;
  /** force the project-memory compressor to run
   *  for the active cwd. The MemoryPanel's
   *  "Compress now" button calls this. The daemon
   *  returns a CompressResult (compressed + before/after
   *  counts + reason). The store refreshes the
   *  PROJECT scope list afterwards so the user sees
   *  the effect immediately. */
  compressProjectMemory: (opts: { cwd: string; force?: boolean }) => Promise<void>;
  /** bind a session to a cwd via the daemon's
   *  switchProject RPC. The daemon persists the
   *  (session, cwd) row into sessions.db, invalidates
   *  the project-memory cache for the old cwd, and
   *  emits NOTIFY_CWD_CHANGED so every connected
   *  client (TUI, Desktop, multica) can react. */
  bindSessionCwd: (opts: { cwd: string; sessionId?: string | null }) => Promise<void>;
  /** create a new task. The renderer's
   *  Kanban "+" button calls this; the daemon
   *  inserts a row in the TaskRegistry and fans
   *  out a `task_event` (action=create) so the
   *  optimistic local insert isn't strictly
   *  needed — but we still return the new task
   *  so the caller's "+" form can clear without
   *  waiting for the WS round-trip. */
  createTask: (opts: { description: string; type?: 'user' | 'agent' | 'subagent' | 'system'; parentTaskId?: string }) => Promise<TaskInfo | null>;
  /** drag-drop a task to a new column. The
   *  daemon's TaskRegistry transitions the status
   *  (idempotent for terminal states) and fans out
   *  a `task_event` (action=update). */
  updateTaskStatus: (id: string, status: 'pending' | 'running' | 'completed' | 'failed' | 'killed') => Promise<void>;
  /** list providers from the daemon's
   *  {@code listProviders} RPC. The Settings
   *  panel calls this on open. Returns the
   *  full registry (providers + models) plus
   *  the current "active" provider/model so
   *  the picker can highlight the active row. */
  refreshProviders: () => Promise<{ providers: any[]; currentProvider: string | null; currentModel: string | null }>;
  /** toggle the supervisor's
   *  auto-restart behaviour. Off by default. */
  setAutoRestart: (enabled: boolean) => Promise<{ ok: boolean; enabled: boolean; autoRestart: boolean }>;
  /** forward a JSON-RPC
   *  notification to a child daemon. Used
   *  when the TUI is connected to the
   *  supervisor and needs to relay
   *  permission_response / loop_ack. */
  proxyNotification: (childId: string, method: string, params: unknown) => Promise<{ ok: boolean; forwarded: boolean }>;
  /** pull the latest session
   *  summary. The user asked for "a
   *  summary regardless of pass/fail" —
   *  the TUI caches it so the End-of-task
   *  panel can read the last value
   *  without re-fetching. */
  refreshSummary: (sessionId?: string | null) => Promise<import('../lib/methods').SessionSummary | null>;
  /** cached supervisor auto-restart
   *  flag. Mirrors the daemon's
   *  SupervisorMode.isAutoRestart(). */
  autoRestart: boolean;
  /** the active child id when the
   *  TUI is connected to the supervisor
   *  (not the child). Null in the normal
   *  case (TUI → daemon direct). When set,
   *  {@link respondPermission} and
   *  {@link acknowledgeLoop} route their
   *  responses via the supervisor's
   *  {@code proxyNotification} path. */
  activeChildId: string | null;
  /** set the active child id. Pass
   *  null to clear (TUI is connected
   *  directly to the daemon). */
  setActiveChildId: (id: string | null) => void;
  /** cached session summary, populated
   *  by {@link refreshSummary}. Null until the
   *  first poll completes. */
  lastSessionSummary: import('../lib/methods').SessionSummary | null;
  /** switch the engine's active
   *  provider + model. The daemon rebuilds
   *  its ChatClient on the fly; the next
   *  query uses the new client. The
   *  currentModel in the store updates so
   *  the Header / StatusBar reflect the
   *  change without a refresh. */
  switchProvider: (provider: string, model?: string, variant?: string | null) => Promise<void>;
  /** R285: switch just the variant without
   *  touching the provider / model. Mirrors the
   *  {@code switchVariant} RPC. Returns the
   *  active variant row so the caller can
   *  echo it in a toast ("Quality set to
   *  high (1.0 / 48K)"). */
  switchVariant: (variant: string) => Promise<import('../lib/methods').VariantInfo | null>;
  /** the daemon's current provider
   *  + model, set by refreshProviders. The
   *  Settings panel reads these to highlight
   *  the active row. */
  currentProvider: string | null;
  availableProviders: import('../lib/methods').ProviderInfo[];
  /** R285: the currently-installed variant name
   *  ("low" / "medium" / "high" / "xhigh"). Set by
   *  {@link switchVariant} and by the model
   *  picker's {@code /model y:y} syntax. The
   *  Settings panel's Quality dropdown reads this
   *  to highlight the active row. */
  currentVariant: string | null;
  /** R285: the active variant's full knobs
   *  (temperature / maxTokens / reasoningBudget /
   *  extendedThinking). Mirrors the daemon's
   *  activeVariant field. The Settings panel
   *  uses this for the read-only preview
   *  ("0.7 / 32K / no thinking") next to the
   *  Quality dropdown. */
  activeVariant: import('../lib/methods').VariantInfo | null;
  /** R288: SDD (Spec-Driven Development) mode.
   *  When true, the next user turn is fed through
   *  the 4-phase SSD flow (需求分析 → 详细设计 →
   *  任务分析 → 开发实现) instead of being sent to
   *  the daemon as a regular query. Toggle lives on
   *  MessageInput (a 📐 pill above the input box);
   *  SddPhaseBar reads this to know whether to render.
   *  The SsdDriver (Mock / Tauri) drives the actual
   *  phase stream; this flag only gates whether the
   *  MessageList / SddPhaseBar react to the toggle. */
  sddEnabled: boolean;
  /** R288: SDD mode setter. See sddEnabled above. */
  setSddEnabled: (on: boolean) => void;
  /**
   * R292 (Spec Kit 6 phases): phases of the current SDD
   * run. Read by {@link SddPhaseBar} to render the chip
   * strip. Replaces the R289 4-phase SSD field. Now covers
   * constitution / specify / clarify / plan / analyze /
   * tasks / implement / converge; optional quality gates
   * (clarify / analyze / converge) carry `optional: true`
   * so the chip strip can render them half-opacity.
   *
   * The `state` field follows the same vocabulary as the
   * canned events (idle / running / pending-accept /
   * clarify-pending / converge-pending / done / skipped /
   * failed). Empty when `sddEnabled` is false or no run
   * has started yet.
   */
  ssdPhases: Array<{
    id: string;
    title: string;
    /** R292: render half-opacity when true (clarify / analyze / converge). */
    optional?: boolean;
    state: 'idle' | 'running' | 'pending-accept' | 'clarify-pending' | 'converge-pending' | 'done' | 'skipped' | 'failed';
    preview?: string;
    path?: string;
  }>;
  /** R289: true while a driver is wired up and emitting
   *  events (i.e. between `startSsdFlow` and the final
   *  `complete`/`abort` event). The SddPhaseBar can use
   *  this to swap its close button for a cancel-style
   *  ✕ that sends `{action: 'quit'}` to the driver. */
  ssdActive: boolean;
  /** R289: feature slug derived from the run's
   *  intent (≤10 ASCII chars, hyphen-joined). The
   *  `<slug>` segment of the file paths the driver
   *  emits (`<cwd>/.aethercode/ssd/<slug>/...`).
   *  Empty string when no run is active. */
  ssdSlug: string;
  /** R289: the raw user intent that started the
   *  current run. Echoed back into phase-draft
   *  previews so the spec carries the user's
   *  original wording. */
  ssdIntent: string;
  /** R289: kick off the SSD run for the given intent.
   *  Spawns a `MockSsdDriver` (default) with a canned
   *  event sequence — the canned sequence mirrors the
   *  real daemon's wire format so swapping in
   *  `TauriSsdDriver` later is a one-line change.
   *  Side-effects:
   *  - resets `ssdPhases` to a 4-phase template
   *  - subscribes to events; updates `ssdPhases[i].state`
   *    on each event
   *  - pushes phase-draft content to the chat list as
   *    an assistant message (so the user reviews in-flow)
   *  - on `complete`, sets `ssdActive=false` and clears
   *    `sddEnabled` so the bar collapses
   *  Also generates a `<slug>` from the intent (≤10
   *  ASCII chars, hyphen-joined) for the file path prefix
   *  — full file writing is the daemon's job in the next
   *  round; for now we just stamp it in the `path` field
   *  of each phase-draft so the path is visible. */
  startSsdFlow: (intent: string) => Promise<void>;
  /** R289: stop the current SSD run (driver.stop +
   *  set sddActive=false). No-op when no run is active. */
  stopSsdFlow: () => void;
  /** R289 / R292: send an inbound command (accept /
   *  revise / skip / quit / clarify-answer /
   *  converge-iterate) to the running driver. No-op
   *  when no driver is wired up. The accept / skip /
   *  revise handlers advance the corresponding phase
   *  to `done` / `skipped` / `pending-accept`.
   *  `clarify-answer` answers a pending
   *  `clarify-question`; `converge-iterate` either
   *  feeds feedback to the next converge iteration
   *  (text non-empty) or accepts the not-converged
   *  verdict (text empty). */
  sendSsdCommand: (
    cmd:
      | { action: 'accept' }
      | { action: 'revise'; text: string }
      | { action: 'skip' }
      | { action: 'quit' }
      | { action: 'clarify-answer'; id: string; answer: string }
      | { action: 'converge-iterate'; text: string }
  ) => void;
  /** refresh the agent list from the
   *  daemon's listAgents. The Agents tab
   *  calls this on open. The agent body
   *  (markdown) is fetched on demand via
   *  getAgentBody when the user opens an
   *  agent for editing. */
  refreshAgents: () => Promise<void>;
  /** CRUD pass-throughs. The Agents
   *  tab uses these; the editor (AgentEditor.tsx)
   *  also calls them directly. */
  createAgent: (opts: any) => Promise<{ ok: true; name: string }>;
  updateAgent: (opts: any) => Promise<{ ok: true; name: string }>;
  deleteAgent: (name: string) => Promise<{ ok: true; name: string }>;
  /** getAgentBody returns the body plus
   *  the frontmatter (description / displayName /
   *  model) so the editor can prefill all four
   *  fields on edit. */
  getAgentBody: (name: string) => Promise<{
    ok: boolean;
    name: string;
    body?: string;
    description?: string;
    displayName?: string;
    model?: string;
    /** R286: per-agent quality preset
     *  (low / medium / high / xhigh). Empty
     *  string means the agent has no
     *  binding. */
    variant?: string;
    path?: string;
    lastModifiedMs?: number;
    error?: string;
  }>;
  /** cached list of agents from
   *  refreshAgents. The Agents tab reads
   *  this directly; refreshAgents fills it. */
  agents: any[];
  refreshProjects: () => Promise<void>;
  refreshMetrics: () => Promise<void>;
  refreshTraces: () => Promise<void>;
  /** poll the daemon for engine stats. Cheap; the
   *  StatusBar calls this every 5s. The result is merged
   *  into {@code engineStats} which the StatusBar / Header
   *  / SettingsPanel read. */
  refreshEngineStats: () => Promise<void>;
  /** one-click profile switch (the Header's
   *  backpressure pill calls this). Writes the new
   *  profile via the daemon's {@code setConcurrencyProfile}
   *  RPC, then re-polls stats so the UI updates. */
  requestConcurrencyProfile: (name: 'low' | 'normal' | 'high') => Promise<void>;
  setModel: (model: string) => Promise<void>;
  setPermissionMode: (mode: string) => Promise<void>;
  // clear the model-mismatch prompt and
  // record the pair in modelMismatchDismissed. The
  // user picked "keep ${daemonModel}".
  dismissModelMismatch: () => void;
  // accept the localStorage preference
  // (the user's "old" model) and switch the daemon
  // to it. Equivalent to picking that model from
  // the dropdown. Clears the prompt + adds the
  // pair to modelMismatchDismissed. Async because
  // it calls setModel (which itself awaits an RPC).
  acceptModelMismatch: () => Promise<void>;
  toggleTool: (toolName: string) => void;
  selectTask: (taskId: string | null) => void;
  /** retry a previously-failed sub-task. Wraps
   *  rpc.retrySubTask and updates the local failed-sub-task card
   *  to `in_progress` so the user sees the retry kick off. */
  retrySubTask: (subTaskId: string, goal: string, hint?: string) => Promise<void>;
  /** mark a sub-task as skipped. The model may still
   *  attempt the work (sub-tasks are conceptual; the engine
   *  doesn't have a strict "skip" boundary), but the card's
   *  status flips to 'skipped' so the user sees they don't
   *  care about the outcome. The model receives a system
   *  note in the message stream so it can also choose to
   *  move on. We don't go through the daemon for this —
   *  a sub-task's "skipped" status is a UI concept, not an
   *  engine state. The retry counterpart (the prior round) DOES hit
   *  the daemon because it issues a new query. */
  skipSubTask: (subTaskId: string) => void;
  /** install a per-(tool, target) permission policy override
   *  via the backend, then mirror the change in the local
   *  alwaysAllowedTools set so the UI shows the new rule. */
  installPermissionOverride: (tool: string, decision: 'allow' | 'deny', target?: string, scope?: 'session' | 'project' | 'user') => Promise<void>;
  cancelTask: (taskId: string) => Promise<void>;
  switchProject: (projectId: string) => Promise<void>;
  respondPermission: (requestId: string, allow: boolean) => Promise<void>;
  /** mark a tool as session-allowed. Local UI state only;
   *  the engine doesn't take this into account yet. */
  allowToolAlways: (toolName: string) => void;
  pickCwd: () => Promise<void>;
  setCwd: (path: string) => Promise<void>;
  /** explicitly force a fresh daemon. Previously the
   *  setCwd did this implicitly (kill + respawn on every
   *  cwd change). later cwd is a per-session binding
   *  and the in-place bindSessionCwd handles it; this is
   *  reserved for "the daemon is wedged and I want a
   *  clean restart". */
  resetDaemon: () => Promise<void>;
  setActivity: (a: AppState['currentActivity']) => void;
  clearActivity: () => void;
  /** R82+ Issue 3: pre-warm a sibling cwd. Resolves to true if
   *  a fresh daemon was spawned, false if there was already one
   *  warm for that cwd (no-op). */
  preWarmCwd: (path: string) => Promise<boolean>;
  /** R82+ Issue 3: drop the pre-warmed daemon. Called on app exit
   *  or when the user switches to a different cwd (the pre-warm
   *  cwd is no longer "next likely target"). */
  discardPreWarm: () => Promise<void>;
  /** dismiss the awaiting-decision prompt without sending
   *  a continuation. Used when the user wants to abandon the
   *  current task. */
  dismissAwaitingUserDecision: () => void;
  /** bump the per-query step counter (called by the
   *  MessageList step-grouper when a new step is appended). */
  bumpCurrentQueryStep: (toolCount: number) => void;
  clearCurrentQuery: () => void;
  /** R83 Issue #6: append text to the current step's body. */
  appendStepText: (text: string) => void;
  /** R83 Issue #6: push a new tool event into the current step.
   *  R267 polish: also accepts the raw {@code input} so the
   *  renderer can reconstruct a file_edit diff from old_string +
   *  new_string without round-tripping back to the daemon. */
  pushStepTool: (tool: { id: string; name: string; inputSummary: string; isError?: boolean; ts: number; input?: Record<string, unknown> }) => void;
  /** R83 Issue #6: fill in the result of a previously pushed tool
   *  event (matched by id). */
  completeStepTool: (id: string, output: string, isError: boolean) => void;
  /** append a streamed chunk to a previously pushed tool
   *  event's `output` field. Used while the tool is still
   *  running so the user sees the command's stdout/stderr live
   *  (e.g. an `mvn --version` banner) instead of waiting for
   *  the whole tool to complete. The `tool_output_delta`
   *  stream_event from the engine maps to this action. */
  appendToolOutput: (id: string, text: string) => void;
  /** R83 Issue #6: mark the current step as done (run_end). */
  finishCurrentStep: (stopReason: string) => void;
  /** R83 Issue #6: a new run_start within the same query is just
   *  another "think" …we don't open a new step for it, but we
   *  bump the think counter. */
  bumpStepThink: () => void;
  /** R83 Issue #6: toggle a step's expand/collapse state (UI only). */
  toggleStepExpanded: (id: string) => void;
  /** open the step detail modal for a workflow
   *  step. The pill click in the WorkflowProgressBar
   *  calls this; the modal reads `selectedStepDetail` to
   *  know which step to render. The `runId` guard keeps
   *  stale clicks from re-opening a finished run. */
  openStepDetail: (runId: string, stepId: string) => void;
  /** clear the step detail modal. Called by the
   *  modal's close button / Esc handler. */
  closeStepDetail: () => void;
  /** append a freshly-closed sub-task card (called from
   *  the sub_task_end handler). The UI uses this to drive the
   *  SubTaskCard footer (status + summary). */
  closeSubTask: (id: string, status: string, summary?: string) => void;
  /** clear all sub-task state. Called on a fresh user
   *  query. The clearCurrentQuery action also calls this. */
  clearSubTasks: () => void;
  /** reorder sub-tasks by moving the entry at `fromIdx`
   *  to `toIdx`. The user can drag a sub-task handle to
   *  re-order their visual layout. Purely client-side — the
   *  engine doesn't care about the order, but a long task
   *  with 8 sub-tasks is easier to read when "in progress"
   *  is at the top, failed at the bottom, etc. */
  reorderSubTasks: (fromIdx: number, toIdx: number) => void;
  /** reorder steps within a sub-task. The engine emits
   *  steps in the order they happened; the user may want to
   *  re-arrange them for readability (e.g. put the failed
   *  step at the top of an expanded trail). Purely
   *  client-side. The global `steps` array is preserved
   *  outside the affected sub-task; we just splice the
   *  matching slice. */
  reorderSteps: (subTaskId: string, fromIdx: number, toIdx: number) => void;
  /** user clicked "continue" in the LoopGuardBanner. Call
   *  the loopAck RPC to reset the detector's tier to 0, then
   *  clear the local loopWarn state. The RPC handler is a
   *  no-op when no query is in flight, so this is safe to
   *  call at any time. */
  acknowledgeLoop: (kind?: string) => Promise<void>;
  /** user clicked "stop" (cancel) in the LoopGuardBanner
   *  or the 8s auto-dismiss fired. Calls cancelQuery() and
   *  clears the local loopWarn state. */
  dismissLoopWarn: (reason?: 'user-cancel' | 'auto') => Promise<void>;
  /** workflow picker. `refreshWorkflows` re-reads the
   *  cwd's workflow directory (called on app start + when
   *  the picker is opened). `setActiveWorkflow` selects a
   *  workflow for the next message; the message is sent
   *  with the workflow attached and the active workflow
   *  is cleared. `clearActiveWorkflow` deselects. */
  refreshWorkflows: () => Promise<void>;
  setActiveWorkflow: (wf: import('../lib/methods').WorkflowDoc | null) => void;
  /** record a workflow as "recent" (used when
   *  the user picks a workflow from the picker or
   *  sends a message with a workflow attached).
   *  The store's setActiveWorkflow implementation
   *  calls this internally so the picker doesn't
   *  have to — but the method is exposed for the
   *  sendMessage path, which sets activeWorkflow
   *  to the same workflow the user just ran. The
   *  LRU semantics (push to front, dedupe, cap at
   *  5) live in the helper. */
  recordRecentWorkflow: (name: string) => void;
  /** toggle the daemon-side auto-approve-low-risk
   *  flag. The store mirrors the daemon's value
   *  (returned by setAutoApproveLowRisk) and seeds
   *  it from the engineState on initialize(). The
   *  default is true (R87's behaviour); a user
   *  who wants manual control flips the StatusBar
   *  toggle. The change is pushed live — no
   *  reconnect. */
  setAutoApproveLowRisk: (enabled: boolean) => Promise<void>;
  /** toggle the daemon-side
   *  auto-approve-medium-high-risk flag. Critical
   *  risk (rm -rf, sudo, mkfs) is NEVER
   *  auto-approved regardless of this flag. Off
   *  by default — flipping it on is the headless
   *  / scripted-run opt-in. The change is pushed
   *  live (no reconnect) and persisted to
   *  localStorage so a reload restores the user's
   *  choice. */
  setAutoApproveMediumHigh: (enabled: boolean) => Promise<void>;
  /** re-fetch the tool pool from the daemon. Wired
   *  to a ↻ button in the input bar's Tools popup and the
   *  ToolsPanel header, plus a 30 s periodic tick in
   *  initialize(). prior-A the tool pool was a
   *  initialize()-time snapshot only — if the round-trip
   *  failed or the daemon reloaded with a different pool,
   *  the renderer had no way to recover. The function is
   *  best-effort: failure logs to the console and the
   *  store keeps the prior value, so a transient WS blip
   *  doesn't blank the panel. */
  refreshTools: () => Promise<void>;
  /** append a single RPC event to the rolling
   *  buffer. Called by the rpc.onRpcEvent subscription
   *  wired in initialize(). The cap (50) is enforced
   *  here so a high-frequency RPC burst (e.g. a query
   *  with 10 tool calls) doesn't grow the buffer
   *  unbounded. The panel reads `recentRpcEvents`
   *  directly; this is the only mutation entry point. */
  recordRpcEvent: (e: RpcEvent) => void;
  /** clear the rolling buffer. Wired to a
   *  "Clear" button in the diagnostic panel. The
   *  store also clears it on reconnect (so a new
   *  daemon session doesn't show stale events from
   *  the previous one). */
  clearRpcEvents: () => void;
  /** re-fetch the engine state snapshot from the
   *  daemon. Mirrors the prior round refreshTools() pattern:
   *  initial fetch in initialize() + 15 s periodic timer
   *  + same connectionState guard. The engine state
   *  drives the model dropdown (the prior round), the permission
   *  mode select, the skip-confirmation counter, the
   *  memory badge, and the StatusBar "X tools" line —
   *  any stale value surfaces visibly. Failure is
   *  best-effort: the store keeps the prior snapshot
   *  so the UI doesn't flash a "model: ?" placeholder
   *  on a transient blip. */
  refreshEngineState: () => Promise<void>;
  /** list of JSON-RPC method names served by the
   *  daemon. Pulled once from {@code GET /api/methods}
   *  (the HTTP endpoint, not the WS RPC layer) when
   *  the user opens the RPC command palette, then
   *  cached. The list powers the palette's filter /
   *  pick UI — the user types a substring, sees a
   *  fuzzy-filtered list, picks one, fills in params,
   *  and presses Enter. The actual call goes through
   *  the same {@code AetherCodeRpc.call} that
   *  everything else uses, so the diagnostic panel's
   *  {@code recentRpcEvents} sees it too.
   *
   *  <p>Why cache rather than refetch on every
   *  palette open: the method list changes only on
   *  daemon restart (which already triggers
   *  initialize() — see the prior round), so a single fetch
   *  per session is enough. The fetch is best-effort:
   *  a 404 / network blip leaves the previous list
   *  intact (same defensive pattern as refreshTools). */
  loadRpcMethods: () => Promise<void>;
  /** runtime loop-detector threshold tweak. The
   *  Settings panel's "Loop Detection" section calls
   *  this when the user drags a slider. The store
   *  optimistically updates the loop fields on
   *  success and kicks refreshEngineState() so the
   *  canonical state slice lands too. The error
   *  path returns the daemon's reason (e.g. "window
   *  must be >= threshold") so the UI can surface
   *  it without re-running the call. */
  setLoopDetectorThresholds: (opts: { window: number; threshold: number }) => Promise<{ ok: boolean; error?: string; window?: number; threshold?: number; disabled?: boolean }>;
  /** clear the pending terminal-event toast. Called
   *  by <SubagentToast> on auto-dismiss (4s) or on click.
   *  No-op when there's nothing pending (defensive — the
   *  dismiss path can race a fresh terminal event arriving
   *  in the same tick). */
  dismissSubagentTerminal: () => void;
}

function newId(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

// parse the executor's structured
// "child_session_event" message into a typed
// ChildStepEvent. The format is
//   "[step-id] kind \"name\" EventClass | step=...|kind=...|...|ev=...|..."
// Values may be backslash-escaped (| and \).
// Returns null when the message can't be parsed
// (the renderer falls through to a generic row).
function parseChildEventMessage(raw: string): ChildStepEvent | null {
  if (!raw) return null;
  // The structured payload starts at the first
  // "|" after the closing "]" of the bracket
  // prefix. We split on the bracket prefix so
  // the rest is unambiguous.
  const bracket = raw.match(/^\[(\S+)\]\s+(.*)$/);
  if (!bracket) return null;
  const stepId = bracket[1];
  const afterPrefix = bracket[2];
  const pipeAt = afterPrefix.indexOf('|');
  if (pipeAt < 0) {
    // Legacy the prior round format: no structured
    // payload. We still attribute to the step
    // and return a "unknown" event with the
    // class name as the summary.
    const evClassMatch = afterPrefix.match(/\b(\w+)$/);
    const evClass = evClassMatch ? evClassMatch[1] : '?';
    return {
      ts: Date.now(),
      stepId,
      kind: 'unknown',
      name: '',
      summary: evClass,
      raw,
    };
  }
  const payload = afterPrefix.slice(pipeAt + 1);
  const kv = parseKeyValuePairs(payload);
  if (!kv) return null;
  const ev = kv.ev ?? '';
  let kind: ChildStepEvent['kind'] = 'unknown';
  let name = kv.name ?? '';
  let summary = '';
  let truncated = false;
  if (ev === 'ToolUseStart') {
    kind = 'tool_use';
    name = kv.tool ?? '';
    summary = kv.arg ?? '';
    truncated = kv.argtruncated === '1';
  } else if (ev === 'ToolResult') {
    kind = kv.status === 'err' ? 'tool_err' : 'tool_ok';
    name = kv.tool ?? '';
    const outlen = kv.outlen ? parseInt(kv.outlen, 10) : 0;
    summary = outlen > 0 ? `${outlen} chars` : 'ok';
  } else if (ev === 'RunStart') {
    kind = 'run_start';
    name = kv.model ?? '';
    summary = kv.model ?? '';
  } else if (ev === 'RunEnd') {
    kind = 'run_end';
    name = kv.stop ?? '';
    summary = kv.stop ?? '';
  } else if (ev === 'SideNote') {
    kind = 'note';
    name = kv.notekind ?? '';
    summary = (kv.notemsg ?? '').slice(0, 60);
  } else if (ev === 'TextDelta') {
    kind = 'text';
    name = '';
    summary = (kv.text ?? '').slice(0, 60);
  } else {
    kind = 'unknown';
    summary = ev || '?';
  }
  return { ts: Date.now(), stepId, kind, name, summary, truncated, raw };
}

// Parse "key=value|key=value|..." into a flat
// record. Values are unescaped (unescape \|
// and \\ in that order). Returns null when the
// payload is malformed (no '=' on a segment).
function parseKeyValuePairs(payload: string): Record<string, string> | null {
  const out: Record<string, string> = {};
  let i = 0;
  let curKey = '';
  let inValue = false;
  let curVal = '';
  while (i < payload.length) {
    const c = payload[i];
    if (!inValue) {
      if (c === '=') {
        inValue = true;
        curVal = '';
      } else if (c === '|') {
        // Empty key segment; skip.
        if (curKey) { out[curKey] = ''; curKey = ''; }
      } else {
        curKey += c;
      }
    } else {
      if (c === '\\' && i + 1 < payload.length) {
        const next = payload[i + 1];
        if (next === '|' || next === '\\') {
          curVal += next;
          i += 2;
          continue;
        }
      }
      if (c === '|') {
        out[curKey] = curVal;
        curKey = '';
        inValue = false;
      } else {
        curVal += c;
      }
    }
    i++;
  }
  if (inValue) {
    // Trailing key=value without a closing pipe.
    if (curKey) out[curKey] = curVal;
  }
  // Sanity: we expect at least the canonical
  // fields. If they're missing, the format is
  // unrecognised.
  if (out.step === undefined && out.kind === undefined) return null;
  return out;
}

// render a ChildStepEvent as a compact
// one-line description for the chat scrollback.
// The WorkflowProgressBar reads the typed
// fields directly and renders its own rows;
// this is for the system-line history that
// appends to the messages list.
function formatSystemLine(ev: ChildStepEvent): string {
  switch (ev.kind) {
    case 'tool_use':
      return `${ev.name || 'tool'}: ${ev.summary}${ev.truncated ? '…' : ''}`;
    case 'tool_ok':
      return `${ev.name || 'tool'} ok (${ev.summary})`;
    case 'tool_err':
      return `${ev.name || 'tool'} err (${ev.summary})`;
    case 'run_start':
      return `turn start: ${ev.name}`;
    case 'run_end':
      return `turn end: ${ev.name}`;
    case 'note':
      return `note: ${ev.name} ${ev.summary}`;
    case 'text':
      // Text deltas are usually suppressed in
      // the scrollback to avoid spamming
      // ("turn text" is enough).
      return `…`;
    default:
      return ev.summary || 'event';
  }
}

/**
 * the shared "log" notification reducer.
 *
 * The renderer's `rpc.on('log', ...)` handler was
 * inlined inside the `useStore` create() closure,
 * which made it impossible to unit-test from a
 * vitest run (the AetherCodeRpc singleton's
 * internal handler list isn't reachable from a
 * test). We extracted the body into this pure
 * helper so the store wires it into the rpc.on
 * callback AND so {@link logHandlerTestSeam}
 * below can drive the same logic with a fake
 * payload.
 *
 * <p>Contract: a NOTIFY_LOG entry is rendered as
 * a system message UNLESS it is a pure noise
 * entry — {@code level: "info"} with no
 * {@code error} field. Anything with an error
 * field, or with an explicit non-info level,
 * surfaces in the chat. The {@code isError}
 * marker on the system message is set when
 * {@code level} is {@code err} or {@code error};
 * this drives the red badge in the message list.
 *
 * <p>Side effect: a non-info entry flips
 * {@code isStreaming} back to {@code false} so
 * the input box un-sticks. The synthetic
 * {@code run_end} emitted by the daemon's query()
 * catch block already covers the most common
 * path, but this is the belt-and-suspenders for
 * log-only failure paths (e.g.
 * {@code EngineContinuationDispatcher}).
 */
export function applyLogNotification(
  set: (partial: Partial<AppState> | ((s: AppState) => Partial<AppState>)) => void,
  params: any,
): void {
  const hasError = params?.error != null && String(params.error).length > 0;
  const rawLevel = String(params?.level ?? '').toLowerCase();
  const level = rawLevel === '' ? (hasError ? 'error' : 'info') : rawLevel;
  if (level === 'info' && !hasError) return; // skip pure-noise info entries
  const msg = params?.message ?? params?.error ?? '';
  if (!msg) return;
  const isError = level === 'err' || level === 'error';
  set((s) => ({
    messages: [...s.messages, {
      id: newId('system'), role: 'system' as const,
      content: `[${level}] ${msg}`, timestamp: Date.now(), isError,
    }],
    isStreaming: isError ? false : s.isStreaming,
  }));
}

export const useStore = create<AppState>((set, get) => {
  // --- Stream events (the engine's primary notification channel) ---
  // The engine fires one `stream_event` per StreamEvent. The inner
  // `event.type` is one of: run_start / text_delta / tool_use_start
  // / tool_result / run_end / side_note. We dispatch on type to
  // drive the UI. Wire shape (AetherCodeMethods.eventToMap):
  //   params = { runId, event: { type, ... } }
  rpc.on('stream_event', (params: any) => {
    const ev = params?.event;
    if (!ev?.type) return;
    switch (ev.type) {
      case 'run_start': {
        // Begin of a new run. Reset watchdog timer so it doesn't
        // fire on long-running tool calls. The assistant message
        // will be created on the first text_delta. R82+ Issue 1:
        // also surface "💭 Thinking…" in the center panel status.
        // R83 Issue #6: open a new step if we don't have one for
        // the current query. Within a single user query the LLM
        // may invoke itself multiple times (think → tool → think);
        // each run_start bumps the step's think counter rather
        // than opening a fresh step.
        // when a step is opened while a sub-task is in
        // progress, associate the step with the sub-task so the
        // MessageList can nest it inside the matching SubTaskCard.
        //
        // R274: reset the prevEventWasText flag so the first
        // text_delta after run_start accumulates into the empty
        // step run_start just created (rather than splitting off
        // a new one). Without this, a run_start followed by N
        // stream chunks would create N+1 micro-steps with one
        // chunk each — see R273 regression.
        prevEventWasText = true;
        // R278 (2026-09-17): the daemon's run_start event carries
        // a `runId` (see AetherCodeMethods.eventToMap — "run_start"
        // payload includes runId from StreamEvent.RunStart). Tag
        // every step opened by this run_start with that runId so
        // the MessageList timeline can split per-query preamble
        // events. Without this, two consecutive user prompts
        // (both without a sub-task) merge into one preamble
        // block and the user bubble of the second prompt lands
        // AFTER the merged content — "new prompt's output runs
        // on top of the old prompt".
        const newQueryId: string = (typeof (ev as any).runId === 'string' && (ev as any).runId)
          ? (ev as any).runId
          : newId('query');
        set((s) => {
          let currentStepId = s.currentStepId;
          let steps = s.steps;
          const parentSubTaskId = s.currentSubTaskId;

          // R274 (2026-09-16): if currentStepId points at a step
          // that's already done (came from a previous query's
          // run_end), force-clear it so the new query opens a
          // fresh step. Without this guard, the daemon's
          // transcript-replay path (desktop rehydrates state on
          // session switch → daemon then runs a new query →
          // run_start fires before our run_end did, OR vice
          // versa) leaves currentStepId pointing at the OLD
          // step, and the new prompt's text_delta / tool_use
          // accumulates into the OLD step. The user calls this
          // "新 prompt 的输出跑到老 prompt 上面执行了". We close
          // any live step defensively before opening the new one.
          if (currentStepId) {
            const oldStep = steps.find((st) => st.id === currentStepId);
            if (!oldStep || oldStep.done) {
              currentStepId = null;
            } else {
              // Old step is still live but we're starting a new
              // query — close it so its buildBlocks rendering
              // is properly terminated, then null out so the new
              // step gets created below.
              const closingId = currentStepId;
              steps = steps.map((st) => st.id === closingId
                ? { ...st, done: true, endedAt: Date.now() }
                : st);
              currentStepId = null;
            }
          }

          if (!currentStepId && s.currentQuery) {
            currentStepId = newId('step');
            steps = [...steps, {
              id: currentStepId,
              subTaskId: parentSubTaskId,
              queryId: newQueryId,
              startedAt: Date.now(),
              text: '',
              toolEvents: [],
              counters: { thinks: 1, fileReads: 0, fileWrites: 0, commands: 0, searches: 0, web: 0, other: 0 },
              done: false,
            }];
            if (parentSubTaskId) {
              const subTasks = s.subTasks.map((st) =>
                st.id === parentSubTaskId && st.firstStepId == null
                  ? { ...st, firstStepId: currentStepId as string }
                  : st,
              );
              return {
                isStreaming: true, lastChunkTs: Date.now(),
                currentActivity: { kind: 'thinking', label: '💭 Thinking…', ts: Date.now() },
                steps, currentStepId, subTasks,
                // a new query starts fresh — clear any
                // stale loop warn from a previous run.
                loopWarn: null,
              };
            }
          }
          return {
            isStreaming: true, lastChunkTs: Date.now(),
            currentActivity: { kind: 'thinking', label: '💭 Thinking…', ts: Date.now() },
            steps, currentStepId,
            // same as the parentSubTaskId branch — a new
            // run wipes any stale loop warn.
            loopWarn: null,
          };
        });
        break;
      }
      case 'sub_task_start': {
        // the model just declared a sub-task (either up-front
        // via todo_write with subtasks[] or by calling sub_todo_write
        // to promote an existing one to in_progress). Open a card
        // and tag it as the "current" sub-task so the next steps /
        // tool calls belong to it.
        const tId = (ev as any).taskId ?? 0;
        const subId = (ev as any).subTaskId ?? newId('sub');
        const content = (ev as any).content ?? '';
        const status = (ev as any).status ?? 'pending';
        const compositeId = `${tId}:${subId}`;
        set((s) => {
          const existing = s.subTasks.find((st) => st.id === compositeId);
          const subTasks = existing
            ? s.subTasks.map((st) => st.id === compositeId
                ? { ...st, status: status as ChatSubTask['status'] }
                : st)
            : [...s.subTasks, {
                id: compositeId,
                taskId: tId,
                subTaskId: subId,
                content,
                status: status as ChatSubTask['status'],
                firstStepId: null,
                startedAt: Date.now(),
              }];
          return {
            subTasks,
            currentSubTaskId: status === 'in_progress' ? compositeId : s.currentSubTaskId,
            currentActivity: status === 'in_progress'
              ? { kind: 'thinking', label: `▶ ${content || 'sub-task'}`, ts: Date.now() }
              : s.currentActivity,
          };
        });
        break;
      }
      case 'sub_task_end': {
        // the model closed a sub-task (status -> completed /
        // failed / skipped, with optional summary). The UI uses
        // the closed status + summary to render the SubTaskCard
        // footer.
        const tId = (ev as any).taskId ?? 0;
        const subId = (ev as any).subTaskId ?? '';
        const status = (ev as any).status ?? 'completed';
        const summary = (ev as any).summary;
        const compositeId = `${tId}:${subId}`;
        set((s) => {
          const subTasks = s.subTasks.map((st) =>
            st.id === compositeId
              ? { ...st, status: status as ChatSubTask['status'], summary, endedAt: Date.now() }
              : st,
          );
          return {
            subTasks,
            currentSubTaskId: s.currentSubTaskId === compositeId ? null : s.currentSubTaskId,
            currentActivity: s.currentSubTaskId === compositeId
              ? { kind: 'done', label: `✓ ${status}`, ts: Date.now() }
              : s.currentActivity,
          };
        });
        break;
      }
      case 'text_delta': {
        const text = ev.text ?? '';
        if (!text) return;
        set((s) => {
          const msgs = [...s.messages];
          let last = msgs[msgs.length - 1];
          // Create a fresh assistant message if the last one is a
          // user/tool/system message, or if there's no last.
          if (!last || last.role !== 'assistant' || last.id.startsWith('user-')) {
            last = { id: newId('assistant'), role: 'assistant', content: '', timestamp: Date.now() };
            msgs.push(last);
          }
          msgs[msgs.length - 1] = { ...last, content: last.content + text };
          // R82+ Issue 1: once we have assistant text streaming,
          // upgrade the activity from "thinking" to "composing".
          const activity = s.currentActivity?.kind === 'thinking'
            ? { kind: 'thinking' as const, label: '✓ Composing…', ts: Date.now() }
            : s.currentActivity;
          // R274 step-boundary (2026-09-16): the rule is now "split on
          // tool→text, accumulate on text→text". A `prevEventWasText`
          // flag tracks whether the previous stream_event was a
          // text_delta. If yes, this chunk is part of the same
          // think and we accumulate it into the current step's text.
          // If no (the previous event was a tool_use_start /
          // tool_result / run_start-after-tool), this chunk is a
          // new think phase and we close the previous step + open a
          // fresh one carrying this text.
          //
          // R273 had "every text_delta always splits". That was
          // wrong: the LLM streams a single think block as many
          // small text_delta chunks (every few characters), and the
          // "always split" rule collapsed each chunk into its own
          // step, rendering as a stack of one-line "思考 · xxx"
          // details cards the user called "现在的展示方式是在搞笑吗".
          // R274 fixes this by accumulating consecutive text_deltas
          // while still splitting at every tool boundary.
          //
          // R267 had `pendingStepBoundary` (true on tool_result,
          // consumed by next text_delta). That worked for the simple
          // think→tool→think pattern but failed when the model
          // emitted text between parallel tool_use_starts without a
          // tool_result in between (e.g. text "think1" →
          // tool_use_start A → text "OK now let me" → tool_use_start B
          // → tool_result A → tool_result B → text "test"). R274
          // strictly subsumes R267 by splitting on tool_use_start
          // too (not just tool_result).
          //
          // We do NOT split when currentStepId is null (the
          // run_start handler creates the first step) so the very
          // first text_delta of a run keeps the existing
          // behaviour — it inherits the empty step that run_start
          // opened.
          let currentStepId = s.currentStepId;
          let steps = s.steps;
          if (currentStepId && !prevEventWasText) {
            // close previous step + open a fresh one for this text
            const prev = steps.find((st) => st.id === currentStepId);
            const parentSubTaskId = prev?.subTaskId ?? null;
            const newStepId = newId('step');
            steps = [
              ...steps.map((st) => st.id === currentStepId
                ? { ...st, done: true, endedAt: Date.now() }
                : st),
              {
                id: newStepId,
                subTaskId: parentSubTaskId,
                startedAt: Date.now(),
                text,
                toolEvents: [],
                counters: { thinks: 1, fileReads: 0, fileWrites: 0, commands: 0, searches: 0, web: 0, other: 0 },
                done: false,
              },
            ];
            currentStepId = newStepId;
          } else if (currentStepId) {
            // accumulate into the current step's text (same think phase)
            steps = steps.map((st) => st.id === currentStepId
              ? { ...st, text: st.text + text, counters: { ...st.counters, thinks: st.counters.thinks + 1 } }
              : st);
          }
          prevEventWasText = true;
          return { messages: msgs, isStreaming: true, lastChunkTs: Date.now(), currentActivity: activity, steps, currentStepId };
        });
        break;
      }
      case 'tool_use_start': {
        // R274: a tool_use_start breaks the current think phase.
        // The next text_delta (if any) opens a new step carrying
        // that text. We don't need to set prevEventWasText here
        // because tool_use_start happens AFTER text_delta in the
        // stream_event order — the upcoming text_delta would still
        // see prevEventWasText=true from the last text chunk and
        // accumulate, which is wrong. So we explicitly flip the
        // flag here.
        prevEventWasText = false;
        const toolName = ev.name ?? '(unknown)';
        const detail = humanizeToolInput(toolName, ev.input);
        const label = detail ? `🛠 ${toolName} · ${detail}` : `🛠 ${toolName}…`;
        const toolId = (ev as any).id ?? newId('tool');
        // R83 Issue #6: push a tool event into the current step,
        // and bump the matching counter for the step header.
        set((s) => {
          const cat = toolCategory(toolName);
          const counters = s.currentStepId
            ? s.steps.find((st) => st.id === s.currentStepId)?.counters
            : null;
          const steps = (s.currentStepId && cat && counters)
            ? s.steps.map((st) => st.id === s.currentStepId
                ? {
                    ...st,
                    toolEvents: [...st.toolEvents, {
                      id: toolId, name: toolName,
                      inputSummary: detail, ts: Date.now(),
                      // R267 polish: stash the raw input so the
                      // renderer can build a real file_edit diff
                      // (old_string + new_string → unified diff).
                      // The protocol does not forward attachments
                      // through tool_result, so this is the only
                      // place the desktop can see the before/after
                      // text. We stash for ALL tools (cheap — the
                      // input is already a parsed object) and let
                      // the renderer pick the ones it cares about.
                      input: ev.input && typeof ev.input === 'object'
                        ? (ev.input as Record<string, unknown>)
                        : undefined,
                    }],
                    counters: { ...counters, [cat]: (counters[cat] ?? 0) + 1 },
                  }
                : st)
            : s.steps;
          // the prior-round Issue 3: bump the per-query tool + step counters
          // so the LiveIndicator (MessageList step-live-detail)
          // updates from "0 steps · 0 tools" as soon as the first
          // tool_use_start arrives. Without this, the live footer
          // stays at 0 steps · 0 tools for the entire query even when
          // the model has called many tools.
          const curQ = s.currentQuery;
          // special-case `todo_write`. The wire event carries
          // the full `input.todos` array (the engine doesn't
          // humanize it) so we capture the entire plan verbatim for
          // the RightPanel "Plan" tab. Defensive against missing /
          // malformed fields — the panel degrades to an empty
          // state when the model emits garbage, never throws.
          let nextTodos: TodoItem[] = s.currentTodos;
          if (toolName === 'todo_write') {
            const raw = (ev.input as any)?.todos;
            if (Array.isArray(raw)) {
              const now = Date.now();
              nextTodos = raw.map((t: any, idx: number) => ({
                content: typeof t?.content === 'string' ? t.content : '',
                status: (typeof t?.status === 'string' ? t.status : 'pending') as TodoItem['status'],
                activeForm: typeof t?.active_form === 'string' ? t.active_form : undefined,
                index: idx,
                updatedAt: now,
                subtasks: Array.isArray(t?.subtasks)
                  ? t.subtasks.map((st: any, sidx: number) => ({
                      id: typeof st?.id === 'string' ? st.id : `sub-${idx}-${sidx}`,
                      content: typeof st?.content === 'string' ? st.content : '',
                      status: (typeof st?.status === 'string' ? st.status : 'pending') as TodoSubTask['status'],
                      summary: typeof st?.summary === 'string' ? st.summary : undefined,
                      index: sidx,
                    }))
                  : [],
              }));
            }
          }
          return {
            messages: [...s.messages, {
              id: newId('tool'), role: 'tool' as const,
              content: `→${toolName}${detail ? ` · ${detail}` : ''}`, timestamp: Date.now(), toolName,
            }],
            lastChunkTs: Date.now(),
            currentActivity: { kind: 'tool', label, ts: Date.now() },
            steps,
            currentTodos: nextTodos,
            currentQuery: curQ
              ? { ...curQ, stepCount: curQ.stepCount + 1, toolCount: curQ.toolCount + 1 }
              : curQ,
          };
        });
        break;
      }
      case 'tool_result': {
        const isError = !!ev.isError;
        const content = ev.content == null ? '' :
          typeof ev.content === 'string' ? ev.content :
          (() => { try { return JSON.stringify(ev.content); } catch { return String(ev.content); } })();
        const preview = content.length > 200 ? content.slice(0, 200) + '…' : content;
        const toolId = (ev as any).id ?? null;
        // R274: a tool_result completes a tool_use. Any subsequent
        // text_delta opens a new step (the model is starting a new
        // think phase after seeing the tool output). The result
        // itself goes into the matching tool event of the *current*
        // step here.
        //
        // (R267 had `pendingStepBoundary = true` here; R273 dropped
        // it because R273's text_delta always split anyway. R274
        // reintroduces the split signal — but on tool_use_start too,
        // not just tool_result.)
        prevEventWasText = false;
        // R83 Issue #6: fill in the result of the matching tool
        // event in the current step (matched by tool_use_start id).
        set((s) => {
          const steps = (s.currentStepId && toolId)
            ? s.steps.map((st) => st.id === s.currentStepId
                ? {
                    ...st,
                    toolEvents: st.toolEvents.map((te) => te.id === toolId
                      ? { ...te, output: content, isError }
                      : te),
                  }
                : st)
            : s.steps;
          return {
            messages: [...s.messages, {
              id: newId('tool'), role: 'tool' as const,
              content: `${isError ? '✗' : '✓'} ${preview}`, timestamp: Date.now(), isError,
            }],
            lastChunkTs: Date.now(),
            currentActivity: s.currentActivity?.kind === 'tool'
              ? { kind: 'tool' as const, label: `${isError ? '✗' : '✓'} ${s.currentActivity.label.replace(/^🛠 /, '')} done`, ts: Date.now() }
              : s.currentActivity,
            steps,
          };
        });
        break;
      }
      case 'tool_output_delta': {
        // a tool is still running and just emitted a chunk
        // of output. Append it to the matching tool event's
        // `output` field so the user sees the streamed result
        // live. We do NOT mark the tool as done here — that
        // happens on the matching `tool_result` event. We also
        // do NOT push a system `→ ...` message to the chat
        // bubble list: streamed lines are part of the tool
        // event's own output, not a separate user-visible
        // message. The MessageList auto-scroll watcher (the prior round)
        // follows the growing output to the bottom of the
        // viewport.
        const toolId = (ev as any).id;
        const text = (ev as any).text ?? '';
        if (!toolId || !text) break;
        get().appendToolOutput(toolId, text);
        break;
      }
      case 'run_end': {
        // End of run. The cursor will disappear on next render.
        // Watchdog stays armed; the next query will reset it.
        // R82+ Issue 1: show "✓Done" for 2s then auto-clear so
        // the center panel doesn't keep a stale "done" pinned.
        // Also mark the last assistant message complete so the
        // MessageList switches from plain text to markdown.
        // stopReason="awaiting_user_decision" means the
        // per-todo controller hit max bumps and is pausing for
        // the user. We surface a special prompt.
        const stopReason = (ev as any).stopReason as string | undefined;
        set((s) => {
          const msgs = [...s.messages];
          for (let i = msgs.length - 1; i >= 0; i--) {
            if (msgs[i].role === 'assistant' && !msgs[i].isComplete) {
              msgs[i] = { ...msgs[i], isComplete: true };
              break;
            }
          }
          // R274 (2026-09-16): close the current step + clear
          // currentStepId. Without this, the live pointer stays
          // attached to the step from the previous query, so the
          // next run_start handler takes the "else if
          // (currentStepId)" branch and bumps the counter on the
          // OLD step instead of opening a fresh one. The new
          // prompt's text_delta / tool_use then accumulate into
          // the old step — visually "the new prompt's output runs
          // on top of the old prompt's tools" (the user reported
          // this in R272/R274). Closing here + clearing
          // currentStepId makes the next run_start take the
          // `if (!currentStepId && s.currentQuery)` branch and
          // open a brand-new step.
          const steps = s.currentStepId
            ? s.steps.map((st) => st.id === s.currentStepId && !st.done
                ? { ...st, done: true, endedAt: Date.now() }
                : st)
            : s.steps;
          const updates: Partial<AppState> = {
            messages: msgs,
            isStreaming: false,
            currentActivity: { kind: 'done', label: '✓Done', ts: Date.now() },
            steps,
            currentStepId: null,
            // a fresh run ended; clear any active workflow
            // selection and the running-workflow tracker. The
            // progress bar unmounts, and the next message starts
            // with no workflow attached (the user re-attaches
            // explicitly via the picker).
            activeWorkflow: null,
            runningWorkflow: null,
          };
          if (stopReason === 'awaiting_user_decision') {
            // The AwaitUserDecision event was emitted just before
            // the RunEnd with the same info; the engine state is
            // already in awaitingUserDecision. We just need to
            // suppress the "✓Done" activity because we're not
            // really done.
            updates.currentActivity = null;
            updates.messages = [
              ...msgs,
              {
                id: newId('system'),
                role: 'system',
                content: '🔔 Per-todo controller hit max LLM bumps. Awaiting your decision to continue, abort, or change direction.',
                timestamp: Date.now(),
                isError: false,
              },
            ];
          }
          return updates;
        });
        if (stopReason !== 'awaiting_user_decision') {
          setTimeout(() => {
            const cur = useStore.getState().currentActivity;
            if (cur?.kind === 'done') useStore.setState({ currentActivity: null });
          }, 2000);
        }
        // refresh metrics so cumulative input/output tokens
        // surface in the TokenUsage panel after each run.
        void get().refreshMetrics().catch(() => {});
        // refresh the session summary
        // so the End-of-task panel and the
        // LoopGuardBanner have fresh data. The
        // user explicitly asked for "a summary
        // regardless of whether the task ended
        // correctly" — this is the canonical
        // "task ended" hook. Best-effort: a
        // failed refreshSummary leaves the
        // previous cached value in place.
        void get().refreshSummary().catch(() => {});
        // drop the synthetic "current query" task. The
        // user's message is now done; the Header reverts to
        // "Idle" / "No active task" until the next sendMessage.
        if (stopReason !== 'awaiting_user_decision') {
          // R83 Issue #6: close out the current step. The user
          // message is done; the step card flips to its
          // "done" state (collapsible, with a stop-reason pill).
          const cur = get().currentStepId;
          if (cur) {
            set((s) => ({
              steps: s.steps.map((st) => st.id === cur
                ? { ...st, done: true, endedAt: Date.now(), stopReason }
                : st),
            }));
          }
          set({ currentQuery: null, currentStepId: null });
        }
        // R267 polish: if the user queued a follow-up
        // prompt while this run was in flight, auto-start
        // it now. We defer the kickoff to a microtask so
        // the run_end set() above lands first — otherwise
        // sendMessage sees isStreaming=false but reads
        // a stale currentQuery/currentStepId from the
        // closure.
        //
        // We DO NOT auto-start if the user paused for a
        // decision (awaiting_user_decision) — that path
        // expects the user to read the prompt and
        // continue manually, and firing an unrelated
        // follow-up while the engine is paused is
        // surprising.
        if (stopReason !== 'awaiting_user_decision') {
          const queued = get().pendingFollowUp;
          if (queued) {
            // clear first so the next run doesn't see
            // a phantom queued prompt in the optimistic
            // state. sendMessage will set isStreaming=true
            // again when its set() fires.
            set({ pendingFollowUp: null });
            // populate the input box from the queued
            // text and fire sendMessage. Going through
            // the action keeps the lazy-create-session,
            // activeWorkflow routing, and rpc.query path
            // in one place instead of duplicating it here.
            set({ currentInput: queued });
            void get().sendMessage();
          }
        }
        break;
      }
      case 'await_user_decision': {
        // per-todo controller hit max bumps. Pause the input
        // box and surface a decision prompt. The summary tells the
        // user what happened.
        set({
          awaitingUserDecision: {
            summary: (ev as any).summary ?? 'Task needs a decision',
            todoSteps: (ev as any).currentTodoSteps ?? 0,
            softThreshold: (ev as any).currentSoftThreshold ?? 0,
          },
        });
        break;
      }
      case 'side_note': {
        // route "compaction" side notes to the in-progress
        // flag instead of a one-shot system message. The desktop
        // renders a "compacting…" pill in the activity indicator
        // while the flag is true, then a single post-hoc
        // "[compaction] completed: N → M messages" line on the
        // matching completion event. Other side_note kinds
        // (memory, task, etc.) still go through the generic
        // system-message path.
        //
        // the engine emits side_note of kind
        // "loop-warn-1" / "loop-warn-2" when the loop detector
        // fires at tier 1 or 2 (i.e. before it would have
        // hard-stopped legacy). The desktop stores the most
        // recent one in `loopWarn` and the LoopGuardBanner
        // shows a soft warning + "continue" / "stop" buttons. The
        // side_note message is also surfaced as a system
        // message for the transcript, so the user has a
        // record of when the detector fired (the banner can
        // auto-dismiss after 8s; the system line is permanent).
        const kind = ev.kind ?? 'note';
        const msg = ev.message ?? '';
        if (kind === 'compaction') {
          set((s) => ({
            compactionInProgress: msg.startsWith('started:'),
            messages: msg.startsWith('completed:')
              ? [...s.messages, {
                  id: newId('system'), role: 'system' as const,
                  content: `[compaction] ${msg}`,
                  timestamp: Date.now(),
                }]
              : s.messages,
            lastChunkTs: Date.now(),
          }));
        } else if (kind === 'loop-warn-1' || kind === 'loop-warn-2') {
          // capture the warn in the loopWarn state so the
          // banner can mount. We also push a system message so
          // the warn shows in the transcript. The banner
          // auto-dismisses after 8s; the system line stays.
          // The runId is taken from the wrapper params so the
          // banner's "continue" can pass it back to the RPC.
          const runId = (params as any)?.runId ?? '';
          const tier: 1 | 2 = kind === 'loop-warn-1' ? 1 : 2;
          // truncate the verbose `msg` (the engine's
          // full loop-detection report can be ~9 KB) to one
          // short line. The user can still see the full
          // detail in the running banner (loopWarn.message
          // is unchanged) — this only affects the chat
          // transcript, which should stay a high-signal
          // narrative, not a debug log.
          const shortMsg = msg.length > 100 ? msg.slice(0, 97) + '...' : msg;
          set((s) => ({
            loopWarn: {
              kind: kind,
              tier,
              runId,
              message: msg,
              ts: Date.now(),
            },
            messages: [...s.messages, {
              id: newId('system'), role: 'system' as const,
              content: `[${kind}] ${shortMsg}`,
              timestamp: Date.now(),
            }],
            lastChunkTs: Date.now(),
          }));
        } else if (kind === 'workflow_step') {
          // workflow run progress. The engine emits one
          // workflow_step side note per declared step on
          // run start (status "pending"). R103 will also
          // emit "running" / "ok" / "error" status updates
          // as the executor advances. We seed `runningWorkflow`
          // on the first workflow_step seen for a run id
          // that isn't already tracked, then mark each step
          // as pending in the stepStatus map. The progress
          // bar reads this state to render the step pill row.
          const runId = (params as any)?.runId ?? '';
          set((s) => {
            const msg1 = msg ?? '';
            // The R102 stub message looks like
            //   "step 1/5 pending: pull (shell)"
            // Parse the step id (and optionally the new
            // status) out of it.
            const stepMatch = msg1.match(/^step\s+(\d+)\/(\d+)\s+(\w+):\s+(\S+)\s+\((\S+)\)/);
            if (!stepMatch) {
              // Unparseable: still surface a system line so
              // the user can see the wire was live.
              return {
                messages: [...s.messages, {
                  id: newId('system'), role: 'system' as const,
                  content: `[${kind}] ${msg1}`,
                  timestamp: Date.now(),
                }],
                lastChunkTs: Date.now(),
              };
            }
            const idx = parseInt(stepMatch[1], 10) - 1;
            const total = parseInt(stepMatch[2], 10);
            const status = stepMatch[3] as 'pending' | 'running' | 'ok' | 'error';
            const stepId = stepMatch[4];
            const stepType = stepMatch[5];
            const cur = s.runningWorkflow;
            let nextRunning = cur;
            if (!cur || cur.runId !== runId) {
              // First step seen for this run — build the
              // full step list from the active workflow's
              // declared steps, then mark the matching one.
              const src = s.activeWorkflow ?? s.availableWorkflows.find((w) => w.name === cur?.name) ?? null;
              const declared = src?.steps ?? [{ id: stepId, type: stepType }];
              // Pad with placeholders if the message count
              // doesn't match declared (defensive).
              while (declared.length < total) {
                declared.push({ id: `step-${declared.length + 1}`, type: 'unknown' });
              }
              nextRunning = {
                name: src?.name ?? stepId,
                runId,
                steps: declared,
                stepStatus: Object.fromEntries(
                  declared.map((st) => [st.id, 'pending' as const]),
                ),
                startedAt: Date.now(),
                // per-step child session event
                // log. Cleared on run end (when
                // runningWorkflow is nulled). The
                // WorkflowProgressBar reads this to
                // render the nested activity list.
                stepEvents: {},
              };
            }
            if (nextRunning) {
              const stepStatus = { ...nextRunning.stepStatus };
              if (stepStatus[stepId] !== undefined) {
                stepStatus[stepId] = status;
              } else {
                // Defensive: step id wasn't in declared list
                // (e.g. dynamic step from gate's `then` branch).
                stepStatus[stepId] = status;
              }
              nextRunning = { ...nextRunning, stepStatus };
            }
            // when the workflow is reused
            // (e.g. a child workflow nested in a
            // tool call), make sure stepEvents is
            // always present on nextRunning so the
            // child_session_event handler can append
            // to it without an undefined check. We
            // initialise to {} on the first
            // workflow_step; subsequent calls
            // preserve the previous map.
            if (nextRunning && !nextRunning.stepEvents) {
              nextRunning = { ...nextRunning, stepEvents: {} };
            }
            return {
              runningWorkflow: nextRunning,
              messages: [...s.messages, {
                id: newId('system'), role: 'system' as const,
                content: `[${kind}] step ${idx + 1}/${total} ${status}: ${stepId} (${stepType})`,
                timestamp: Date.now(),
              }],
              lastChunkTs: Date.now(),
            };
          });
        } else if (kind === 'child_session_event') {
          // the prior round: child session
          // (skill/agent step body) emitted an
          // event. The executor's wrapper puts the
          // parent step id at the start of the
          // message in square brackets, e.g.
          // "[my-step] skill \"my-skill\"
          // TextDelta". the prior round extended the format
          // with a structured key=value payload
          // (see {@link formatChildEventMessage}).
          //
          // We do two things:
          //   1. Parse the message into a typed
          //      ChildStepEvent and append to
          //      runningWorkflow.stepEvents[stepId]
          //      (capped to MAX_STEP_EVENTS).
          //      WorkflowProgressBar reads this
          //      to render the nested activity
          //      list under the running step
          //      pill.
          //   2. Still push a compact system line
          //      so the chat scrollback keeps
          //      its history. the prior round uses a
          //      richer format ("[step-id]
          //      ToolUseStart: Bash command=ls")
          //      so the user can read the
          //      scrollback without expanding the
          //      nested view.
          const m = msg ?? '';
          const parsed = parseChildEventMessage(m);
          if (parsed) {
            set((s) => {
              const cur = s.runningWorkflow;
              let nextRunning = cur;
              // Only attach to the running workflow
              // when its runId matches the event's
              // step. Child events for a stale
              // workflow (e.g. after run_end) are
              // dropped from the nested view but
              // still appear in the chat scrollback
              // as a system line — preserves
              // forward-compat with a future
              // "history" view.
              if (cur) {
                const prev = cur.stepEvents[parsed.stepId] ?? [];
                let next = prev.concat(parsed);
                if (next.length > MAX_STEP_EVENTS) {
                  next = next.slice(next.length - MAX_STEP_EVENTS);
                }
                const stepEvents = { ...cur.stepEvents, [parsed.stepId]: next };
                nextRunning = { ...cur, stepEvents };
              }
              return {
                runningWorkflow: nextRunning,
                messages: [...s.messages, {
                  id: newId('system'), role: 'system' as const,
                  content: `[child ↳ ${parsed.stepId}] ${formatSystemLine(parsed)}`,
                  timestamp: Date.now(),
                }],
                lastChunkTs: Date.now(),
              };
            });
          } else {
            // Unparseable (e.g. an older daemon
            // that still emits the legacy
            // the prior round message). Fall through to a
            // generic system line; the nested
            // progress view gets nothing.
            const stepMatch = m.match(/^\[(\S+)\]\s+(.*)$/);
            set((s) => ({
              messages: [...s.messages, {
                id: newId('system'), role: 'system' as const,
                content: stepMatch
                  ? `[child ↳ ${stepMatch[1]}] ${stepMatch[2]}`
                  : `[${kind}] ${m}`,
                timestamp: Date.now(),
              }],
              lastChunkTs: Date.now(),
            }));
          }
        } else if (kind === 'task') {
          // the engine emits a `task u-xxx started`
          // side_note at the start of every query. The
          // sub-task card already shows the task progress
          // (header + meta line), and the active task
          // ID is in `currentQuery` for the header bar.
          // Rendering this as a chat system message is
          // pure noise — it sits between the user's
          // prompt and the first sub-task card, looking
          // like an alert. Drop it (the sub-task card
          // already conveys the same info).
          //
          // The legacy `[task] task u-abc started` style
          // was a CLI/tty convention from R17 (the
          // --print mode showed it in dim ANSI). The
          // desktop chat view has its own richer
          // surfaces for the same information.
          set({ lastChunkTs: Date.now() });
        } else if (kind === 'todo-step-bump' || kind === 'todo-ask-llm') {
          // TodoRunController internal signals
          // (the prior round). They belong on the engine control
          // plane, not the chat transcript. The engine
          // already does the right thing for the LLM:
          //   - `todo-step-bump` is a state nudge
          //     ("soft threshold 15 → 30"), no chat
          //     equivalent. Surfacing it as a system
          //     line is pure noise.
          //   - `todo-ask-llm` injects the same prompt
          //     via {@code Message.userText(prompt)} in
          //     QueryEngine.java (line 1403) — the LLM
          //     sees it on the next turn as a real user
          //     message. Re-pushing the same content
          //     here as a system line would duplicate
          //     it in the chat scrollback.
          // The full detail is already on the engine's
          // SLF4J log (R83 path) and the daemon's
          // stdout/stderr is captured to
          // %TEMP%\aethercode-daemon-port*.log by the
          // Tauri host. The user can `Get-Content
          // $env:TEMP\aethercode-daemon-port*.log -Wait`
          // to watch the live stream; the chat should
          // stay a high-signal narrative, not a debug
          // log. The {@code lastChunkTs} ping is enough
          // to refresh any watchdog / "still alive"
          // indicator — no messages touched.
          set({ lastChunkTs: Date.now() });
        } else {
          // unknown side_note kind. This is an
          // engine protocol bug — a kind we don't
          // recognise landed on the wire. The legacy
          // fallback pushed `[${kind}] ${msg}` to the
          // chat transcript, but that turned every
          // internal engine signal (loop-warn, todo
          // control, compactor, etc.) into a
          // transcript line, drowning the actual
          // conversation in engine control chatter.
          // R208 contract: the chat transcript is for
          // user / assistant / tool exchanges, not for
          // engine control messages. Unknown kinds
          // are logged to the devtools console so the
          // developer can see them; the source-pin
          // test {@code side_note_doesntPolluteChat}
          // locks this behaviour so a future refactor
          // that re-introduces the silent push fails
          // the test loudly.
          if (typeof console !== 'undefined' && console.warn) {
            console.warn(`[R208 unknown side_note kind] ${kind}: ${msg.slice(0, 200)}`);
          }
          set({ lastChunkTs: Date.now() });
        }
        break;
      }
      // 'unknown' is silently ignored (defensive)
    }
  });

  // The daemon broadcasts two shapes via `transcript_event`:
  //
  //   { action: 'append', sessionId, message }    — every
  //     appendMessage call (user msg, assistant msg,
  //     tool-result msg, system note). Fires WHILE a
  //     query is in flight.
  //
  //   { action: 'sync', sessionId, messages[] }    — after
  //     loadSession / createSession, with the entire
  //     in-memory transcript as a fresh list. Fires on
  //     session switch.
  //
  // The renderer is the live source of truth for the
  // currently-streaming turn (text_delta / tool_use_start
  // build up `messages` locally), so we IGNORE 'append'
  // events during streaming — accepting them would
  // duplicate the local entries (the daemon's id is a
  // UUID, the renderer's local id is `assistant-1234` or
  // `tool-…`).
  //
  // 'sync' is the only path that REPLACES `messages`,
  // and we use it on every session switch so a reload /
  // session-flip restores the historical chat log
  // without re-reading the retired localStorage cache.
  // The sessionId guard means a slow sync from a
  // previous session (e.g. one we just left) cannot
  // wipe the new session's messages.
  rpc.on('transcript_event', (params: any) => {
    if (!params || typeof params !== 'object') return;
    const action = params.action as string | undefined;
    const sid = params.sessionId as string | undefined;
    if (!action || !sid) return;
    // Only honour events for the active session. A late
    // sync for a session the user just left would
    // otherwise wipe the new session's `messages`.
    if (sid !== get().currentSessionId) return;
    if (action === 'sync') {
      const rawMessages = Array.isArray(params.messages) ? params.messages : [];
      const incoming = rawMessages
        .map(messageToChatMessage)
        .filter(Boolean) as ChatMessage[];
      // merge by id instead of REPLACE. The
      // pre-fix `set({ messages, isStreaming: false })`
      // wiped optimistic local additions — most
      // importantly the user message that
      // {@link sendMessage} just pushed into
      // `state.messages` *before* awaiting
      // {@code rpc.query}. If the daemon emitted a
      // `transcript_event(sync)` between the local
      // push and the user message being appended on
      // the server (e.g. via `createSession` for a
      // first-ever query on a fresh session, or a
      // WS reconnect that re-sends the empty/short
      // transcript), the user's prompt disappeared
      // and the chat looked "blank" on submit. The
      // fix: keep every local message whose id is
      // not present in the daemon's sync (those are
      // optimistic local additions the daemon hasn't
      // acknowledged yet), and replace any matching
      // id with the daemon's authoritative copy.
      set((s) => {
        if (incoming.length === 0 && s.messages.length > 0) {
          // Edge case: an empty sync (e.g. just after
          // a brand-new createSession with no messages
          // yet) should NOT clobber the renderer's
          // local view. Bail with only isStreaming
          // update; the next sync (after the user msg
          // is appended on the server) will be a full
          // merge.
          return { isStreaming: false };
        }
        const byId = new Map<string, ChatMessage>();
        for (const m of incoming) byId.set(m.id, m);
        // Walk the daemon's transcript in order; for
        // each id, prefer the daemon's copy but fall
        // back to the local copy if the id is only
        // present locally. Local-only entries (the
        // just-submitted user message) get appended
        // at the tail so their order matches insertion.
        const merged: ChatMessage[] = [];
        const seen = new Set<string>();
        for (const m of incoming) {
          const local = s.messages.find((x) => x.id === m.id);
          merged.push(local ? { ...local, ...m } : m);
          seen.add(m.id);
        }
        for (const m of s.messages) {
          if (!seen.has(m.id) && !byId.has(m.id)) {
            merged.push(m);
            seen.add(m.id);
          }
        }
        return { messages: merged, isStreaming: false };
      });
    } else if (action === 'append') {
      // Live streaming is driven by stream_event. The
      // daemon's append fires on the same Message that
      // produced the stream events, so accepting it
      // here would duplicate the local entry. We
      // deliberately drop it. The only path that
      // mutates `messages` during a live query is
      // stream_event.
      //
      // If the renderer ever falls behind (e.g. WS
      // reconnect with a missed sync), the
      // hydrateTranscript() action can be called
      // explicitly to fetch via getTranscript.
    }
  });

  // --- Engine log (errors and tool warnings) ---
  rpc.on('log', (params: any) => {
    applyLogNotification(set, params);
  });

  // the daemon short-circuits low-risk
  // permission asks and emits this notification
  // instead. The renderer uses it for two
  // purposes: (1) increment the cumulative
  // autoApprovedCount so the StatusBar badge can
  // render "auto-allowed: N this session", and
  // (2) keep a recent-N history (the last 10
  // tool names) for the RpcDiagnosticsPanel /
  // StatusBar tooltip. The history is bounded —
  // a 10-query session with 5 reads each would be
  // 50 entries if uncapped, which is the wrong
  // shape for a UI badge.
  rpc.on('permission_auto_approved', (params: any) => {
    const p = params as {
      tool?: string;
      input?: unknown;
      reason?: string;
      atMs?: number;
      // the daemon's emit payload
      // expanded — riskLevel +
      // autoApprovedElevatedCount are
      // both present in R126+ daemons.
      // Older payloads (R120 era) lack
      // these fields; the handler falls
      // back to the legacy 4-field
      // behaviour so the store stays
      // backward-compatible.
      riskLevel?: string;
      autoApprovedCount?: number;
      autoApprovedElevatedCount?: number;
    };
    if (!p?.tool) return;
    // the daemon-side emit now carries
    // BOTH counters + the risk level. The
    // elevated counter is incremented by
    // medium / high / critical; the low counter
    // is incremented by low. We honour the
    // daemon's value (when present) and only
    // fall back to a +1 when the daemon's
    // payload is the legacy 4-field shape
    // (the prior round).
    const riskLevel = typeof p.riskLevel === 'string' ? p.riskLevel : 'low';
    const elevated = riskLevel !== 'low';
    set((s) => ({
      autoApprovedCount: typeof p.autoApprovedCount === 'number'
        ? p.autoApprovedCount
        : (elevated ? s.autoApprovedCount : s.autoApprovedCount + 1),
      autoApprovedElevatedCount: typeof p.autoApprovedElevatedCount === 'number'
        ? p.autoApprovedElevatedCount
        : (elevated ? s.autoApprovedElevatedCount + 1 : s.autoApprovedElevatedCount),
      recentAutoApproved: [
        { tool: p.tool!, atMs: p.atMs ?? Date.now() },
        ...s.recentAutoApproved,
      ].slice(0, 10),
    }));
  });

  // The daemon fires this on every successful
  // bindSessionCwd (and on the legacy switchProject's
  // session-bound path). The store mirrors the new
  // cwd + bumps lastCwdChangedAt so the right-rail
  // Memory tab can show "cwd changed 5s ago" and
  // re-render. We also kick a PROJECT-scope refresh
  // because the new cwd's project memory is now
  // visible (and the old one is no longer reachable
  // via this session).
  rpc.on('cwd_changed', (params: any) => {
    const p = params as { sessionId?: string; oldCwd?: string | null; newCwd?: string; atMs?: number };
    if (!p || typeof p.newCwd !== 'string') return;
    set({
      cwd: p.newCwd,
      lastCwdChangedAt: p.atMs ?? Date.now(),
    });
    void get().refreshMemory('PROJECT');
  });

  // daemon-driven todo list. AetherCodeMethods emits a
  // `task_state` JSON-RPC notification whenever the engine's
  // AppState publishes a new todo list (the model just called
  // todo_write / sub_todo_write). The wire shape is:
  //
  //   params = { kind: "todo_update", sessionId, params: { todos: [...] } }
  //
  // The inner `todos` is the engine's canonical list (top-level
  // Map<String,Object> objects — `content`, `status`, `active_form`,
  // and a `subtasks[]` of {id, index, content, status, summary?}).
  // We normalise the wire shape into TodoItem[] (the same shape
  // R228 used for `currentTodos`) so the AgentTasksPanel can
  // read either field with one type. R228's `currentTodos`
  // (parsed from the tool_use_start event) is kept as a
  // fast-path fallback for the sub-second window before this
  // notification arrives.
  rpc.on('task_state', (params: any) => {
    if (!params || typeof params !== 'object') return;
    if (params.kind !== 'todo_update') return;
    // sessionId guard: drop notifications for sessions we're
    // not rendering. The desktop follows the active session;
    // a notification for a background session would otherwise
    // stomp the current list. (The desktop is single-session
    // today, so this is defence-in-depth.)
    const p = params as { sessionId?: string; params?: { todos?: unknown } };
    const raw = p.params?.todos;
    if (!Array.isArray(raw)) return;
    const now = Date.now();
    const next: TodoItem[] = raw.map((t: any, idx: number) => {
      const subs = Array.isArray(t?.subtasks) ? t.subtasks : [];
      return {
        content: typeof t?.content === 'string' ? t.content : '',
        status: (typeof t?.status === 'string' ? t.status : 'pending') as TodoItem['status'],
        activeForm: typeof t?.active_form === 'string' ? t.active_form : undefined,
        index: idx,
        updatedAt: now,
        subtasks: subs.map((st: any, sidx: number) => ({
          id: typeof st?.id === 'string' ? st.id : `sub-${idx}-${sidx}`,
          content: typeof st?.content === 'string' ? st.content : '',
          status: (typeof st?.status === 'string' ? st.status : 'pending') as TodoSubTask['status'],
          summary: typeof st?.summary === 'string' ? st.summary : undefined,
          index: sidx,
        })),
      };
    });
    set({ daemonTodos: next });
  });

  // --- Permission request (engine asks before running a tool) ---
  rpc.on('permission_request', (params: any) => {
    const p = params as { requestId?: string; tool?: string; input?: unknown; reason?: string; riskLevel?: string };
    if (!p?.requestId) return;
    // read-only tools never need a confirm — auto-approve
    // immediately. The user can still see what the model read
    // in the chat list; we just don't pause the run.
    // the daemon now short-circuits this on its
    // side (the JsonRpcPermissionPrompter returns Allow
    // without emitting permission_request for low-risk
    // calls when autoApproveLowRisk is on). This
    // branch is a defence-in-depth fallback for old
    // daemons that don't have the R120 prompter
    // change.
    if (p.riskLevel === 'low') {
      void rpc.permissionResponse(p.requestId, true).catch(() => undefined);
      return;
    }
    // in ACCEPT_TASK mode the user said "no mid-task
    // stops" — sub-task boundary decisions get auto-approved.
    // The Java side already auto-allows in-task calls; what
    // reaches us here is a boundary decision, which we treat
    // as a no-op. The user can flip back to DEFAULT via
    // Settings → Permissions if they want to pause on
    // boundaries.
    if (useStore.getState().permissionMode === 'ACCEPT_TASK') {
      void rpc.permissionResponse(p.requestId, true).catch(() => undefined);
      return;
    }
    set((s) => ({
      pendingPermissions: [...s.pendingPermissions, {
        requestId: p.requestId!, tool: p.tool ?? '(unknown)',
        input: p.input, reason: p.reason, riskLevel: p.riskLevel,
        receivedAt: Date.now(),
      }],
    }));
  });

  // --- Auto-reconnect on daemon death --------------------------------
  // The Rust WS client emits `daemon.disconnected` when the underlying
  // WebSocket closes (remote close frame, error, or stream end). We
  // clear Rust-side state via the `disconnect` Tauri command (so the
  // next ensure_daemon can re-establish) and re-initialize with
  // exponential backoff. If we were already in 'reconnecting' state
  // (e.g. from setCwd + swap_to_pre_warm), we just clear streaming;
  // the existing initialize() call is in charge of re-establishing,
  // and Rust state is managed by the swap path …calling disconnect
  // here would wipe the freshly-promoted primary's ws_tx.

  // The daemon fans out every TaskRegistry change
  // (create + status transition) as a `task_event`
  // notification. The Kanban board (the prior round) subscribes
  // to this so its columns stay in sync without
  // polling. We do the merge here at the store level
  // (insert-or-replace by id) so any UI that reads
  // `state.tasks` sees the latest state.
  //
  // Two actions:
  //   action: 'create'  — a new task appeared (the
  //                       Kanban "+" button, or the
  //                       engine spawning a child
  //                       subagent)
  //   action: 'update'  — a status transition
  //                       (drag-drop on the Kanban, or
  //                       the engine's own RUNNING →
  //                       COMPLETED transition on
  //                       subagent finish)
  rpc.on('task_event', (params: any) => {
    if (!params || typeof params !== 'object') return;
    const action = params.action as string | undefined;
    const task = params.task as any;
    if (!action || !task || typeof task !== 'object') return;
    const t: TaskInfo = {
      id: String(task.id),
      type: (task.type ?? 'user') as TaskInfo['type'],
      status: (task.status ?? 'pending') as TaskInfo['status'],
      description: String(task.description ?? ''),
      parentTaskId: task.parentTaskId ?? undefined,
      createdAtMs: typeof task.createdAtMs === 'number' ? task.createdAtMs : Date.now(),
      endedAtMs: typeof task.endedAtMs === 'number' ? task.endedAtMs : 0,
    };
    set((s) => {
      const idx = s.tasks.findIndex((x) => x.id === t.id);
      if (idx < 0) {
        return { tasks: [...s.tasks, t] };
      }
      const next = s.tasks.slice();
      next[idx] = t;
      return { tasks: next };
    });
  });

  rpc.on('daemon.disconnected', (params: any) => {
    const reason = (params as { reason?: string })?.reason ?? 'unknown';
    const wasConnected = get().connectionState === 'connected';
    console.warn(`[store] daemon.disconnected (wasConnected=${wasConnected}):`, reason);
    set((s) => ({
      connectionState: 'reconnecting' as ConnectionState,
      isConnected: false,
      isStreaming: false,
      ...(wasConnected ? {
        messages: [...s.messages, {
          id: newId('system'), role: 'system' as const,
          content: `[Disconnected] ${reason}. Reconnecting…`,
          timestamp: Date.now(), isError: true,
        }],
      } : {}),
    }));
    // Only clear Rust-side state on a genuine disconnect. For
    // user-initiated cwd changes (setCwd →swap_to_pre_warm or
    // setCwd →set_cwd), Rust state is already being managed by
    // the swap/set_cwd path; clearing it here would defeat the
    // swap (we'd wipe the freshly-promoted primary's ws_tx).
    if (wasConnected) {
      invoke('disconnect', { killChild: false }).catch((e) => {
        console.warn('[store] disconnect command failed:', e);
      });
      scheduleReconnect(set, get);
    }
  });

  // The engine's SubagentRegistry fans out a
  // `subagent_event` JSON-RPC notification on every
  // transition (RUNNING / COMPLETED / FAILED / CANCELLED).
  // The Rust side already forwards the notification to the
  // renderer (see aethercode-desktop/src-tauri/src/lib.rs
  // ws-notify channel), so this handler is the single
  // point where the desktop picks up the data. Pure reducer
  // logic lives in `./subagentReducer.ts` (testable in
  // isolation). The StatusBar reads `subagent.status`;
  // <SubagentToast> reads `subagent.lastTerminal`.
  //
  // Wire shape (matches the engine's
  // org.aethercode.protocol.task.SubagentEvent record,
  // emitted by AetherCodeMethods as a JSON-RPC
  // notification):
  //   params = { jobId, role, status, elapsedMs, summary, atMs, sessionId }
  //
  // the payload carries a sessionId. We drop events
  // from other sessions here so the reducer never sees
  // them — the StatusBar and toast only react to events
  // from the user's own session. The `isOurSession` helper
  // (in subagentReducer.ts) handles the empty-string
  // sentinel (legacy-D daemons + the pre-init desktop).
  rpc.on('subagent_event', (params: any) => {
    if (!params || typeof params !== 'object') return;
    const jobId = typeof params.jobId === 'string' ? params.jobId : '';
    if (!jobId) return;
    const rawStatus = String(params.status ?? '').toUpperCase();
    if (rawStatus !== 'RUNNING'
        && rawStatus !== 'COMPLETED'
        && rawStatus !== 'FAILED'
        && rawStatus !== 'CANCELLED') {
      // Defensive: skip unknown statuses. The store should
      // not crash on a future R93+ status the desktop
      // doesn't know about yet.
      return;
    }
    // per-session filter. Run BEFORE the reducer
    // so a multi-session daemon doesn't bleed other
    // sessions' subagent noise into the user's UI.
    const evSession = typeof params.sessionId === 'string' ? params.sessionId : '';
    if (!isOurSession(evSession, get().currentSessionId)) return;
    const role = typeof params.role === 'string' ? params.role : '';
    const elapsedMs = Number.isFinite(params.elapsedMs) ? Number(params.elapsedMs) : 0;
    const atMs = Number.isFinite(params.atMs) ? Number(params.atMs) : Date.now();
    const summary = typeof params.summary === 'string' ? params.summary : '';
    // the engine's captured result text
    // (truncated to 4 KB on the wire). The desktop
    // SubagentPanel uses it for the "click to insert
    // result" action. Empty for non-COMPLETED
    // transitions and for legacy daemons that don't
    // publish a result.
    const resultText = typeof params.result === 'string' ? params.result : '';
    // streaming partial result. Forwarded to
    // the reducer as a separate field so the
    // SubagentPanel can show a live preview of
    // in-flight work. Empty for terminal events and
    // for legacy daemons that don't publish a
    // partial.
    const partialResult = typeof params.partialResult === 'string' ? params.partialResult : '';
    set((s) => ({
      subagent: reduceSubagent(s.subagent, {
        jobId, role,
        status: rawStatus as 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED',
        elapsedMs, atMs, summary,
        sessionId: evSession,
        resultText,
        partialResult,
      }),
    }));
  });

  // skip-confirmation counter. The daemon emits this
  // notification on every counter change (RPC arm, auto-detect,
  // consume). We patch engineState in place so the StatusBar
  // re-renders without a getState round-trip.
  rpc.on('skip_confirmation', (params: any) => {
    if (!params || typeof params !== 'object') return;
    const remaining = Number(params.remaining);
    if (!Number.isFinite(remaining)) return;
    // Per-session filter: ignore events from other sessions.
    const evSession = typeof params.sessionId === 'string' ? params.sessionId : '';
    if (!isOurSession(evSession, get().currentSessionId)) return;
    const cur = get().engineState;
    if (cur) {
      set({
        engineState: { ...cur, skipConfirmationRemaining: Math.max(0, Math.floor(remaining)) },
      });
    } else {
      // No engineState yet (e.g. very early boot). Stash a
      // partial record so the next getState merge picks it up.
      set({
        engineState: {
          sessionId: evSession,
          model: '',
          permissionMode: 'default',
          toolCount: 0,
          contextWindow: 0,
          skipConfirmationRemaining: Math.max(0, Math.floor(remaining)),
        },
      });
    }
  });

  return {
    daemonInfo: null, isConnected: false, connectionState: 'idle', initError: null,
    engineState: null, tools: [], toolActions: [], toolsRefreshedAt: 0, engineStateRefreshedAt: 0, recentRpcEvents: [], autoApproveLowRisk: true, autoApproveMediumHigh: false, autoApprovedCount: 0, autoApprovedElevatedCount: 0, recentAutoApproved: [], skipStats: { consumed: 0, armed: 0, prompts: 0, adoption: 0 }, sessions: [], currentSessionId: null, rpcMethods: [], rpcMethodInfos: [],
    // supervisor auto-restart cached
    // flag. Default false (matches the
    // daemon-side default; opt-in via Settings
    // panel or env var).
    autoRestart: false,
    // active child id. Null in the
    // common case (TUI connects to a single
    // daemon). Set when the TUI is connected
    // to the supervisor and the user has
    // picked a child to drive — the
    // respondPermission / acknowledgeLoop
    // actions then route via proxyNotification.
    activeChildId: null,
    // cached session summary. Populated
    // by refreshSummary; null until the first
    // poll. The TUI's End-of-task panel reads
    // this so the user sees "Wrote 12 files,
    // ran 3 shell commands" without a new
    // round-trip.
    lastSessionSummary: null,
    tasks: [], currentTaskId: null,
    // 3-layer memory defaults — empty for all
    // three scopes until refreshMemory() pulls the
    // daemon's view. lastMemoryRefreshMs=0 means
    // "never refreshed"; the right-rail tab can show
    // a "click to load" hint in that case.
    memory: {
      user: { entries: [], count: 0 },
      project: { entries: [], count: 0, cwd: null },
      session: { entries: [], count: 0, sessionId: null },
    },
    memoryStats: null,
    lastMemoryRefreshMs: 0,
    lastCwdChangedAt: 0,
    projects: [], currentProjectId: null,
    messages: [], isStreaming: false, currentInput: '', pendingFollowUp: null,
    model: '', permissionMode: 'ask', enabledTools: null,
    metrics: null, traces: [], pendingPermissions: [],
    // no engine stats until the daemon replies.
    engineStats: null,
    alwaysAllowedTools: new Set<string>(),
    cwd: null,
    reconnectAttempts: 0, lastChunkTs: 0,
    // wall-clock ms of the last successful (or
    // attempted) hydrateTranscript call. Lets the
    // disconnect handler tell whether a re-hydrate is
    // needed after reconnect, and gives the renderer a
    // signal for the "stale" badge if the last sync was
    // a long time ago.
    lastTranscriptSyncMs: 0,
    availableModels: [],
    cwdSwitchInProgress: false, cwdSwitchTarget: null,
    // no mismatch prompt at boot. The
    // initialize() post-restore check (see
    // modelMismatchPrompt section above) flips this
    // to {prefsModel, daemonModel} when localStorage
    // disagrees with the daemon. The MessageInput
    // bar reads it and surfaces a banner.
    modelMismatchPrompt: null,
    // empty dismissal list at boot. The
    // `dismissModelMismatch` action appends the
    // "${prefsModel}|${daemonModel}" pair. The
    // initialize() check skips pairs already in this
    // list so the same mismatch doesn't re-prompt
    // on every launch. A different pair (e.g. M1→M4
    // upgrade) is a fresh mismatch and re-prompts.
    modelMismatchDismissed: [],
    transitionPhase: 'idle',
    currentActivity: null,
    // default to 'new' so each launch starts with a
    // clean session. Users can switch to 'restore' in
    // Settings if they prefer the previous session to be
    // re-opened automatically.
    defaultOpenBehavior: 'new',
    // 'dark' matches the legacy default
    // (the existing :root token in global.css is
    // the deep amber-on-black palette). Users can
    // flip to 'light' via the header's theme toggle;
    // the choice persists in prefs.theme.
    theme: 'dark',
    // false by default. Flipped to true on the engine's
    // "compaction started" SideNote; back to false on "compaction
    // completed". Renders a "compacting…" pill in the activity
    // indicator so the user sees the work in flight.
    compactionInProgress: false,
    preWarm: null,
    awaitingUserDecision: null,
    // live subagent snapshot. Populated by the
    // `subagent_event` subscription above. See
    // ./subagentReducer.ts for the pure reducer; the toast
    // / StatusBar read the result without going through any
    // middleware.
    subagent: INITIAL_SUBAGENT,
    currentQuery: null,
    steps: [],
    currentStepId: null,
    expandedStepIds: {},
    // no step detail modal open by default.
    selectedStepDetail: null,
    // sub-task cards (business-concept units). Each
    // SubTaskCard contains its own step cards; the MessageList
    // groups them by subTaskId when rendering.
    subTasks: [],
    // no todos yet. The first `todo_write` tool call fills
    // this; a fresh user query clears it back to []. The
    // RightPanel "Plan" tab reads `todos` (the prior round) first and falls
    // back to this when the daemon notification hasn't arrived
    // yet (e.g. during the tool_use_start → setTodoList gap).
    currentTodos: [],
    // daemon-driven todo list. Populated by the
    // `task_state kind=todo_update` notification listener
    // (AetherCodeMethods.java NOTIFY_TASK_STATE, R229) which
    // fires from AppState.onTodoUpdate after setTodoList. This
    // is the canonical source — the engine just published it,
    // so the renderer doesn't have to wait for the model to
    // stop calling tools to see the latest list. R228's
    // `currentTodos` is kept as a fast-path fallback for the
    // sub-second window before the notification arrives.
    daemonTodos: [],
    // id of the sub-task currently being worked on. The
    // MessageList highlights this card and auto-expands its
    // step list. Null when no sub-task is in_progress.
    currentSubTaskId: null,
    // no active loop warn. Set by the side_note handler
    // when a "loop-warn-1" or "loop-warn-2" event arrives, and
    // cleared by acknowledgeLoop() (user clicked "continue") /
    // dismissLoopWarn() (user clicked "stop" or 8s auto-dismiss
    // fired) / on a fresh run_start.
    loopWarn: null,
    // no workflows cached yet. refreshWorkflows() populates
    // this on app start and when the picker is opened. The
    // active workflow is the one the user attached to the next
    // message; it is cleared on send.
    availableWorkflows: [],
    activeWorkflow: null,
    runningWorkflow: null,
    // LRU of recent workflow names. Populated
    // from localStorage on initialize() (keyed by
    // the cwd at the time of the last save) and
    // pushed-to-front every time the user picks a
    // workflow. Default empty — the user has to pick
    // at least one workflow for the LRU to show
    // anything.
    recentWorkflows: [],
    // provider picker state. Cached on
    // the first refreshProviders() call from the
    // Settings panel; null until then. The
    // currentProvider field tells the picker
    // which row to highlight as the active one.
    availableProviders: [],
    currentProvider: null,
    // R285: variant state. Both default to null
    // (the engine falls back to the bundled
    // variant.DEFAULT when the daemon hasn't
    // installed an explicit override). The
    // Settings panel's Quality dropdown watches
    // currentVariant to highlight the active
    // row.
    currentVariant: null,
    activeVariant: null,
    // R288: SDD mode off by default. The toggle in
    // MessageInput flips this; SddPhaseBar / message
    // routing react to it.
    sddEnabled: false,
    // R289: SSD run state. Empty array when no run; an
    // array of 4 phase objects when ssdEnabled was just
    // flipped on and a startSsdFlow() is in flight.
    ssdPhases: [],
    ssdActive: false,
    // R289: feature slug + raw intent text for the
    // active run. Used by the SddPhaseBar footer
    // hint and by the assistant messages we push
    // during the run. Empty strings when no run.
    ssdSlug: '',
    ssdIntent: '',
    // agent list cache. Filled by
    // refreshAgents() (called by the Agents
    // tab on open). Each entry has {name,
    // description, displayName, path,
    // lastModifiedMs}; the body is fetched
    // on demand via getAgentBody when the
    // user opens an agent in the editor.
    agents: [],

    initialize: async () => {
      // Guard against concurrent re-initialization (e.g. setCwd
      // and the disconnect handler both racing to reconnect).
      if (initializeInFlight) return initializeInFlight;
      initializeInFlight = (async () => {
        set({ initError: null, connectionState: 'connecting' });
        try {
          await rpc.start();
          let info: DaemonInfo;
          try {
            info = await invoke<DaemonInfo>('ensure_daemon');
          } catch (e: any) {
            // the Rust side returns the literal string
            // "NEEDS_CWD" when neither `state.cwd` (in-memory
            // override) nor the persisted last-project file
            // is set. We translate that to a dedicated
            // `awaiting-cwd` connection state — the renderer
            // shows the first-launch folder picker instead
            // of the chat list, and the reconnect / health
            // check machinery stays out of the way. Any
            // other error falls through to the catch below.
            const msg = String(e?.message ?? e ?? '');
            if (msg.includes('NEEDS_CWD')) {
              set({
                connectionState: 'awaiting-cwd' as ConnectionState,
                isConnected: false,
                daemonInfo: null,
                // Clear any pre-warm; the user is about to
                // pick a folder, and the existing pre-warm
                // (for a now-irrelevant cwd) is dead weight.
                preWarm: null,
              });
              return;
            }
            throw e;
          }
          set({ daemonInfo: info, connectionState: 'connected', isConnected: true, reconnectAttempts: 0 });
          const [state, tools, actions, sessions, projects, tasks, metrics, traces, workflows] = await Promise.all([
            rpc.getState().catch(() => null),
            rpc.listTools().catch(() => ({ tools: [] })),
            // per-tool permission action assessment.
            // Best-effort: a daemon without this RPC returns
            // an empty list, the panel renders an empty state.
            rpc.listToolActions().catch(() => ({ tools: [] })),
            rpc.listSessions().catch(() => ({ sessions: [] })),
            rpc.listProjects().catch(() => ({ projects: [] })),
            rpc.listTasks().catch(() => ({ tasks: [] })),
            rpc.getMetrics().catch(() => null),
            rpc.getTraces(20).catch(() => ({ traces: [], inFlight: 0, completed: 0 })),
            // list workflows on app start so the input
            // bar's picker can show them without a refresh.
            // Cheap (single directory scan + small parse) and
            // safe to call before the WS RPCs above.
            rpc.listWorkflows().catch(() => ({ workflows: [], count: 0, dir: '' })),
          ]);
          set({
            engineState: state,
            // stamp the initial engine-state fetch.
            // Same reasoning as the prior round's toolsRefreshedAt:
            // without this, the R116 diagnostic panel would
            // say "engine state: never fetched" for the first 15 s
            // even though the data is fresh.
            engineStateRefreshedAt: Date.now(),
            // pull skipStats from getState (initial seed).
            // We also schedule a periodic refresh (handled
            // below) so the counter stays current without a
            // per-turn RPC.
            skipStats: (() => {
              const s = (state as { skipStats?: { consumed: number; armed: number; prompts: number; adoption: number } } | null);
              return s?.skipStats ?? { consumed: 0, armed: 0, prompts: 0, adoption: 0 };
            })(),
            tools: tools.tools ?? [],
            // stamp the initial fetch so the
            // ToolsPanel can show "last fetched: 0s ago" right away.
            // Without this, the panel would say "never"
            // for the first 30s after launch even though
            // the data is fresh.
            toolsRefreshedAt: Date.now(),
            // per-tool action assessment (parallel
            // array to `tools`).
            toolActions: (actions as { tools?: import('../lib/methods').ToolActionInfo[] }).tools ?? [],
            // daemon's listSessions is the
            // authoritative source (the prior-round localStorage
            // merge is retired).
            sessions: sessions.sessions ?? [],
            currentSessionId: state?.sessionId ?? null,
            projects: projects.projects ?? [],
            currentProjectId: projects.projects?.find((p) => p.active)?.id ?? null,
            tasks: tasks.tasks ?? [],
            metrics,
            traces: traces.traces ?? [],
            availableWorkflows: workflows.workflows ?? [],
            model: state?.model ?? '',
            // the renderer's standalone
            // `permissionMode` field is now a UI tier
            // ('ask' / 'smart' / 'bypass'), so the
            // dropdown's value prop matches an option
            // from the get-go. The daemon returns the
            // canonical enum; we map it to a UI tier
            // via mapDaemonToUiPermission. Advanced
            // modes (ACCEPT_TASK / DEFAULT / PLAN /
            // AUTO_READ_ONLY) fall through as their raw
            // enum name so the dropdown will show
            // nothing matching (the browser falls back
            // to the first option visually) and the
            // StatusBar shows the raw enum so the
            // power user can see what's in effect.
            permissionMode: state?.permissionMode
              ? mapDaemonToUiPermission(state.permissionMode)
              : 'ask',
            // restore input draft for the current session.
            // The session list was populated above; we use the
            // server's authoritative currentSessionId (which may
            // be null for a fresh daemon) as the key.
            currentInput: readDraft(state?.sessionId ?? null),
            // empty `messages` initially. The
            // daemon's in-memory transcript is the source of
            // truth now; the hydrateTranscript call below
            // fetches it via getTranscript. We start with []
            // (not the localStorage cache) so the renderer's
            // first paint doesn't briefly show stale data
            // from a different session.
            messages: [],
            // hydrate the per-cwd workflow LRU
            // from localStorage. The key includes the
            // cwd so different projects don't bleed
            // their LRU across. The read is best-effort
            // — a corrupted JSON or quota error leaves
            // the LRU empty, which is the right default
            // (the user can build a new LRU by picking
            // a workflow).
            recentWorkflows: (() => {
              try {
                if (typeof window === 'undefined' || !window.localStorage) return [];
                const cwd = info.cwd || '__none__';
                const raw = window.localStorage.getItem(`aethercode-recent-workflows:${cwd}`);
                if (!raw) return [];
                const parsed = JSON.parse(raw);
                if (!Array.isArray(parsed)) return [];
                return parsed.filter((n) => typeof n === 'string').slice(0, 5);
              } catch {
                return [];
              }
            })(),
          });
          // back-fill the chat log from the
          // daemon. We do this after the synchronous set()
          // so the LeftPanel / Header / Welcome tiles can
          // render with empty state first (no flicker),
          // then the messages flow in.
          if (state?.sessionId) {
            void get().hydrateTranscript(state.sessionId);
          }
          // R268e (2026-09-15): auto-restore the most-recent
          // session when the daemon comes up with NO
          // currentSessionId. Previously the renderer's
          // default was `'new'` (mint a fresh session on
          // launch), which meant a returning user who
          // didn't manually click a session in the LeftPanel
          // saw their prompt land in a fresh empty
          // session — the LLM had no task context, the
          // chat panel showed no history, and `cwd` /
          // `currentSessionId` were blank. With the auto-
          // restore below, the renderer's first paint picks
          // up the user's most-recent session so the next
          // prompt lands where the user expects.
          //
          // The check is conservative:
          //   - `currentSessionId` is null (daemon's default
          //     session is empty — no live work to lose)
          //   - the session list is non-empty (user has
          //     done at least one previous session)
          //   - the most-recent session was used in the
          //     last 24h (fresh enough that auto-restoring is
          //     what the user expects; older sessions stay in
          //     the LeftPanel for manual selection)
          // If any of these fail, the renderer's existing
          // behaviour holds (default = 'new').
          if (!state?.sessionId && (sessions.sessions ?? []).length > 0) {
            const recent = [...(sessions.sessions ?? [])]
              .filter((s) => typeof s.lastUsedAt === 'number')
              .sort((a, b) => (b.lastUsedAt ?? 0) - (a.lastUsedAt ?? 0))[0];
            if (recent && recent.id) {
              const ageMs = Date.now() - (recent.lastUsedAt ?? 0);
              if (ageMs < 24 * 60 * 60 * 1000) {
                try {
                  await get().switchSession(recent.id);
                } catch (e) {
                  console.warn('[store] R268e auto-restore failed:', e);
                }
              }
            }
          }
          // pull available models from the engine (was
          // hardcoded in SettingsPanel, drifted from reality).
          try {
            const ml = await rpc.listModels();
            set({ availableModels: ml.models ?? [] });
          } catch {}
          // restore the user's persisted engine
          // preferences from localStorage. Each field
          // is applied independently — a partial entry
          // (e.g. only `model` from an older build)
          // doesn't shadow the daemon's canonical
          // value for the missing fields. The writes
          // to the daemon are best-effort: a stale
          // value (e.g. a model the daemon no longer
          // supports) returns ok=false and the store
          // keeps the daemon's value. The local
          // mirror (model / permissionMode /
          // autoApproveLowRisk / loopWindow /
          // loopThreshold) updates AFTER the daemon
          // RPC succeeds so the user sees the
          // canonical value, not the stale one.
          const prefs = readEnginePrefs();
          // restore the user's default-open-behaviour
          // preference. The actual decision (mint a fresh
          // session vs restore the most-recent one) is
          // applied below, after the daemon's session list
          // is loaded.
          set({ defaultOpenBehavior: prefs.defaultOpenBehavior ?? 'new' });
          // restore the user's theme preference.
          // The [data-theme="light"] CSS overrides are
          // bound to document.documentElement, not the
          // store, so we apply the attribute here at
          // init time. The header's theme button calls
          // setTheme() which keeps the attribute in
          // sync with state.theme.
          const restoredTheme = prefs.theme === 'light' ? 'light' : 'dark';
          set({ theme: restoredTheme });
          try {
            document.documentElement.setAttribute('data-theme', restoredTheme);
          } catch {
            /* jsdom / SSR — the attribute apply is a
             * no-op in test environments. */
          }
          // the model preference is NOT
          // pushed to the daemon on initialize. The
          // daemon's defaultModel (from its
          // providers.yaml) is the source of truth.
          //
          // previously this branch pushed the stale
          // localStorage value to the daemon on every
          // restart, so a user who once selected
          // MiniMax-M1 saw M1 in the input box and
          // ran M1 in the daemon even after upgrading
          // to a build that defaulted to M3.
          //
          // R176 takes the next step: when the
          // localStorage preference differs from the
          // daemon's canonical model, surface a
          // one-time prompt so the user can choose
          // to switch back. The renderer
          // (MessageInput bar) watches
          // `modelMismatchPrompt` and shows a toast
          // / inline banner when it's set. The user
          // clicks "switch" to call setModel(prefs.model)
          // (which writes to BOTH the daemon and
          // localStorage) or "keep ${daemonModel}"
          // to dismiss. The dismissal records the
          // (prefsModel, daemonModel) pair in
          // modelMismatchDismissed so the same
          // mismatch doesn't fire on the next
          // launch.
          //
          // The R122 source-pin test
          // (enginePrefsR122.test.ts) was rewritten
          // in R176 from "applies persisted model to
          // the daemon" to "trusts daemon's
          // canonical model and surfaces a mismatch
          // prompt" — a behaviour assertion instead
          // of a source-pin. The detection logic
          // lives in the pure helper
          // computeModelMismatchPrompt() above; the
          // test imports + asserts against that
          // helper directly.
          const mismatch = computeModelMismatchPrompt(
            prefs.model,
            state?.model,
            get().modelMismatchDismissed,
          );
          if (mismatch) {
            set({ modelMismatchPrompt: mismatch });
          }
          if (prefs.permissionMode && prefs.permissionMode !== state?.permissionMode) {
            try {
              // the persisted value is now a UI tier
              // ('ask' / 'smart' / 'bypass'), not the raw
              // daemon enum. Map it to the canonical enum
              // before the RPC. previously persisted values
              // (the raw enum, e.g. 'ACCEPT_EDITS') are
              // passed through unchanged via the fallback
              // in mapUiPermissionToDaemon so a v0.2.45
              // build that just upgraded to v0.2.46 doesn't
              // accidentally re-translate the legacy enum.
              const daemonMode = mapUiPermissionToDaemon(prefs.permissionMode);
              const r = await rpc.setPermissionMode(daemonMode);
              if (r?.ok) {
                set((s) => ({
                  engineState: s.engineState
                    ? { ...s.engineState, permissionMode: daemonMode }
                    : null,
                  permissionMode: daemonMode,
                }));
              } else {
                const next = readEnginePrefs();
                delete next.permissionMode;
                writeEnginePrefs(next);
              }
            } catch { /* best-effort */ }
          }
          if (prefs.autoApproveLowRisk !== undefined
              && prefs.autoApproveLowRisk !== (state as { autoApproveLowRisk?: boolean } | null)?.autoApproveLowRisk) {
            try {
              const r = await rpc.setAutoApproveLowRisk({ enabled: prefs.autoApproveLowRisk });
              if (r?.ok) {
                set({ autoApproveLowRisk: !!r.enabled });
              } else {
                const next = readEnginePrefs();
                delete next.autoApproveLowRisk;
                writeEnginePrefs(next);
              }
            } catch { /* best-effort */ }
          }
          // medium+high-risk auto-approve. Same
          // apply-or-clear pattern as autoApproveLowRisk.
          // Critical risk is NEVER auto-approved
          // regardless of this flag, so this only ever
          // covers medium + high.
          if (prefs.autoApproveMediumHigh !== undefined
              && prefs.autoApproveMediumHigh !== (state as { autoApproveMediumHigh?: boolean } | null)?.autoApproveMediumHigh) {
            try {
              const r = await rpc.setAutoApproveMediumHigh({ enabled: prefs.autoApproveMediumHigh });
              if (r?.ok) {
                set({ autoApproveMediumHigh: !!r.enabled });
              } else {
                const next = readEnginePrefs();
                delete next.autoApproveMediumHigh;
                writeEnginePrefs(next);
              }
            } catch { /* best-effort */ }
          }
          if (prefs.loopWindow !== undefined || prefs.loopThreshold !== undefined) {
            try {
              const r = await rpc.setLoopDetectorThresholds({
                window: prefs.loopWindow ?? -1,
                threshold: prefs.loopThreshold ?? -1,
              });
              if (r?.ok) {
                set((s) => ({
                  engineState: s.engineState
                    ? {
                        ...s.engineState,
                        loopWindow: r.window,
                        loopThreshold: r.threshold,
                      }
                    : null,
                }));
              } else {
                const next = readEnginePrefs();
                delete next.loopWindow;
                delete next.loopThreshold;
                writeEnginePrefs(next);
              }
            } catch { /* best-effort */ }
          }
          // schedule a periodic getSkipStats poll so the
          // StatusBar's "skip: 4/7" badge updates without the
          // user opening the Tools panel. The interval is 5s —
          // long enough to not hammer the daemon, short enough
          // that the user sees adoption shift within a turn.
          // The poll stops when the daemon disconnects (we
          // check connectionState inside the tick).
          if (skipStatsTimer) window.clearInterval(skipStatsTimer);
          skipStatsTimer = window.setInterval(async () => {
            const st = get();
            if (st.connectionState !== 'connected') return;
            try {
              const s = await rpc.getSkipStats();
              set({ skipStats: s });
            } catch {
              // best-effort; the next tick will retry
            }
          }, 5000);
          // periodic tool-pool refresh. 30 s interval —
          // long enough that we don't hammer the WS, short
          // enough that "the daemon reloaded with a new tool
          // set" surfaces within a UI turn. The same
          // connectionState guard as skipStatsTimer above.
          if (toolsTimer) window.clearInterval(toolsTimer);
          toolsTimer = window.setInterval(() => {
            const st = get();
            if (st.connectionState !== 'connected') return;
            void get().refreshTools();
          }, 30_000);
          // periodic engine-state refresh. 15 s
          // interval — engine state changes more often
          // than the tool pool (model switches, permission
          // mode changes, loop detector threshold tweaks,
          // transcript size growth), so a faster cadence
          // keeps the UI in sync without making the WS
          // unhappy. The same connectionState guard as
          // skipStatsTimer / toolsTimer.
          if (engineStateTimer) window.clearInterval(engineStateTimer);
          engineStateTimer = window.setInterval(() => {
            const st = get();
            if (st.connectionState !== 'connected') return;
            void get().refreshEngineState();
          }, 15_000);
          // subscribe to the rpc's per-call
          // events. The subscription lives at module
          // scope (rpcEventUnsubscribe) so a re-init
          // tears down the old listener before
          // installing a new one — otherwise a
          // reconnect would leave the previous
          // daemon's events still flowing into the
          // new session's buffer. The handler
          // forwards to recordRpcEvent which caps
          // the buffer at 50.
          if (rpcEventUnsubscribe) rpcEventUnsubscribe();
          rpcEventUnsubscribe = rpc.onRpcEvent((e) => {
            get().recordRpcEvent(e);
          });
          // R82+ Issue 3: pre-warm a sibling cwd for sub-second
          // switch. Best-effort; failure just means next setCwd
          // pays the full 1-2s JVM startup cost.
          try {
            const sibling = suggestSibling(info.cwd || get().cwd);
            if (sibling) await get().preWarmCwd(sibling);
          } catch (e) {
            console.warn('[store] pre-warm on initialize failed:', e);
          }
          // respect the user's default-open-behaviour
          // preference. 'new' mints a fresh session so the
          // previous session's cwd / context doesn't leak
          // into the new launch. 'restore' (the previous
          // default behaviour) keeps the daemon's
          // most-recently-active session. We only fire
          // 'new' when the daemon actually has at least one
          // session to draw from — a totally fresh install
          // shouldn't allocate a session until the user
          // starts typing.
          // the old behaviour here auto-created a
          // "new session" session at app-start, which then sat
          // in the LeftPanel as a placeholder until the
          // user actually submitted a prompt. The user
          // pushed back ("when there is no prompt yet, don't create
          // this session"). The lazy-create flow now lives
          // in `sendMessage`: when the user hits Enter /
          // Submit and there is no current session, we
          // mint a fresh one with the prompt as the
          // session's first-prompt / title, AT submit
          // time, and the LeftPanel picks it up via
          // refreshSessions() right after. The session
          // never appears as a "new session" placeholder.
          //
          // The `defaultOpenBehavior` preference is now
          // ignored at init time; it will be honoured by
          // the lazy-create path below (we read
          // `defaultOpenBehavior === 'new'` and use the
          // new session instead of the daemon's most-recent
          // active one).
          void get().defaultOpenBehavior; // intentionally unused; see R224.
        } catch (e: any) {
          set({ initError: e?.message ?? String(e), connectionState: 'error', isConnected: false });
          throw e; // let scheduleReconnect retry
        } finally {
          initializeInFlight = null;
        }
      })();
      return initializeInFlight;
    },

    setCurrentInput: (s) => {
      // persist the draft to localStorage keyed by the
      // current session id. Switching sessions restores the
      // right scratchpad. 300ms debounce so we're not writing
      // localStorage on every keystroke. The store action
      // captures the *current* sessionId at write time, so a
      // pending debounce flush that lands after a session
      // switch is harmless: it just writes the old session's
      // draft (which is the value the user was actually typing
      // for that session).
      set({ currentInput: s });
      if (typeof window === 'undefined' || !window.localStorage) return;
      const sid = get().currentSessionId;
      if (draftTimer) window.clearTimeout(draftTimer);
      if (s.trim().length === 0) {
        // Empty → drop the key so we don't leak stale drafts.
        clearDraft(sid);
      } else {
        draftTimer = window.setTimeout(() => {
          writeDraft(sid, s);
        }, 300);
      }
    },

    sendMessage: async () => {
      const input = get().currentInput.trim();
      if (!input) return;
      // R289: when SDD mode is on, route the input
      // through the 4-phase SSD flow instead of the
      // regular query() path. startSsdFlow owns the
      // user-message push + driver lifecycle, so we
      // return early — none of the regular send
      // plumbing (lazy createSession, query(),
      // hydrateTranscript, refreshSessions) should
      // fire for an SSD run. The user's intent is
      // captured by startSsdFlow's intent argument.
      if (get().sddEnabled) {
        await get().startSsdFlow(input);
        return;
      }
      // R272 (2026-09-15): reset sub-task / step tracking before
      // firing off the new query. Without this, the OLD
      // currentSubTaskId from the previous query leaks into the
      // new run_start handler — the new step inherits the old
      // sub-task id, and every text_delta / tool_use for the
      // new prompt is bucketed into the OLD sub-task card. The
      // chat panel then renders "all content above the new
      // prompt" because the old sub-task (with the new step
      // appended) sits in the timeline at the original prompt's
      // timestamp.
      //
      // We DON'T clear subTasks — the old sub-task cards
      // stay visible as history. We only break the live
      // pointer so a fresh step starts with subTaskId=null.
      // If the model declares a new sub-task for this query
      // (sub_todo_write), sub_task_start fires and links the
      // *next* step from there.
      //
      // Done BEFORE the isStreaming queue branch below so the
      // follow-up path gets the same clean slate.
      set({
        currentSubTaskId: null,
        currentStepId: null,
      });
      // R267 polish: queue the prompt instead of dropping
      // it when a run is in flight. The user wants the
      // "natural flow" of "model says it's done → my
      // next prompt fires immediately", not "I have to
      // wait for the run to end before the input box
      // unlocks".
      //
      // The current run_end handler auto-promotes
      // pendingFollowUp to a new run (see case 'run_end'
      // below). One slot only — overwriting the previous
      // queued prompt matches the user's explicit
      // instruction "我会自己 cancel 前一个, 再提交后一个".
      if (get().isStreaming) {
        set({ pendingFollowUp: input, currentInput: '' });
        // also clear the localStorage draft so a refresh
        // doesn't restore the just-queued text.
        const sid = get().currentSessionId;
        if (draftTimer) { window.clearTimeout(draftTimer); draftTimer = null; }
        clearDraft(sid);
        return;
      }
      // if we're in the awaiting-decision state, the user's
      // typed message IS the continuation. Clear the flag so the
      // UI stops showing the decision prompt while the engine
      // processes the answer.
      const wasAwaiting = !!get().awaitingUserDecision;
      // capture the sessionId BEFORE we clear the input,
      // because the just-submitted message belongs to that
      // session and we want to clear its draft slot only.
      let sid = get().currentSessionId;

      // lazy-create the session at submit time. legacy
      // the app auto-created a "new session" placeholder at init
      // time (defaultOpenBehavior === 'new'), which sat in the
      // LeftPanel as a row with no name and no transcript.
      // The user pushed back ("when there is no prompt yet, don't
      // create this session"). The new flow:
      //   1. If there's no active session AND the user has
      //      not picked a session, mint a fresh one bound
      //      to the current cwd and pass the first 200
      //      chars of the prompt as `firstPrompt`. The
      //      daemon stores it in session_info.first_prompt
      //      and surfaces it via listSessions.preview, so
      //      the LeftPanel row appears with a real title
      //      (truncated to 10 chars by the frontend's
      //      shortSummary helper) instead of "new session".
      //   2. If the user has an active session, use it as
      //      before — the lazy path is a no-op.
      //   3. If the user has set defaultOpenBehavior='new'
      //      AND there is no active session, prefer a
      //      fresh session over restoring the most-recent
      //      daemon session.
      if (!sid) {
        try {
          const behavior = get().defaultOpenBehavior;
          // behavior === 'restore' + daemon has a current
          // session → let the daemon's session stand.
          // Otherwise (behavior === 'new' or daemon has no
          // current session) → lazy-create a fresh one.
          let daemonCurrent: string | null = null;
          try {
            const sess = await rpc.listSessions();
            daemonCurrent = (sess as { current?: string | null }).current ?? null;
          } catch { /* listSessions is best-effort */ }
          const wantFresh = behavior === 'new' || !daemonCurrent;
          if (wantFresh) {
            const r = await rpc.createSession({
              cwd: get().cwd ?? undefined,
              // The daemon caps at 200 chars. The frontend
              // shortSummary caps to 10 chars in the
              // LeftPanel row, so the user sees a 10-char
              // preview of the first prompt immediately.
              firstPrompt: input.slice(0, 200),
            });
            sid = r.sessionId;
            try { await rpc.loadSession(sid); } catch { /* daemon may be stub */ }
            set({
              currentSessionId: sid,
              messages: [],
              currentInput: '',
              // Push the new session into the local list
              // right away so the LeftPanel can show the
              // row. The title (`name`) is not known yet;
              // it lands in `preview` from listSessions on
              // the refreshSessions() call below. We set
              // `messageCount: 0` to match what the daemon
              // reports for a brand-new session.
              sessions: [
                ...get().sessions,
                {
                  id: sid,
                  name: undefined,
                  preview: input.slice(0, 200),
                  lastUsedAt: Date.now(),
                  messageCount: 0,
                  cwd: r.cwd ?? get().cwd ?? undefined,
                },
              ],
            });
          } else {
            // Restore the daemon's current session.
            sid = daemonCurrent;
            set({ currentSessionId: sid });
            void get().hydrateTranscript(sid);
          }
        } catch (e) {
          console.warn('[store] R224: lazy createSession failed:', e);
          // Best-effort fall-through: the query() call below
          // will surface its own error if there's no valid
          // session.
        }
      }

      // R83 Issue #1+2+4: install a synthetic "current query"
      // task that the Header / ProgressBar / TaskList can all
      // read. We trim the prompt to the first line so the
      // header doesn't show a 500-char wall of text.
      const summary = input.length > 60 ? input.slice(0, 57) + '…' : input;
      const qid = newId('query');
      set((s) => ({
        messages: [...s.messages, { id: newId('user'), role: 'user', content: input, timestamp: Date.now() }],
        currentInput: '', isStreaming: true,
        awaitingUserDecision: null,
        currentQuery: { id: qid, prompt: summary, startedAt: Date.now(), stepCount: 0, toolCount: 0 },
      }));
      // clear the draft for the session we just sent to.
      // A debounced write might be in flight from the prior
      // setCurrentInput; cancel it first so we don't race.
      if (draftTimer) { window.clearTimeout(draftTimer); draftTimer = null; }
      clearDraft(sid);
      // if the user attached a workflow to the next
      // message, route through runWorkflow instead of query().
      // The daemon emits a `workflow_step` side note per
      // declared step; the side_note handler builds
      // runningWorkflow so the progress bar can render the
      // step pill row. The active workflow is cleared here
      // (so the next message starts fresh) and on run_end
      // (defensive — covers errors and the engine's own
      // clear).
      const activeWorkflow = get().activeWorkflow;
      try {
        if (activeWorkflow) {
          set({ activeWorkflow: null });
          // The user's text becomes a free-form "note" the
          // executor can surface in step 0; for R102 we
          // attach it as the workflow's first input slot.
          const r = await rpc.runWorkflow(activeWorkflow.name, {
            note: input,
            sessionId: get().currentSessionId ?? null,
          });
          if (!r?.ok) {
            set((s) => ({
              messages: [...s.messages, {
                id: newId('system'), role: 'system' as const,
                content: `[workflow] 启动失败: ${r?.note ?? 'unknown'}`,
                timestamp: Date.now(), isError: true,
              }],
              isStreaming: false,
            }));
            return;
          }
        } else {
          // a busy response (the prior-round session-level run
          // lock is held by a previous run) lands here as
          // { ok: false, error: "session is busy with run
          // X" }. The query didn't start; the user's
          // message is NOT in the chat. Surface the
          // failure as a system message and bail — do not
          // pretend the query succeeded.
          const r = await rpc.query(input, { sessionId: get().currentSessionId ?? undefined });
          if (r && (r as { ok?: boolean }).ok === false) {
            const errMsg = (r as { error?: string }).error ?? 'unknown';
            set((s) => ({
              messages: [...s.messages, {
                id: newId('system'), role: 'system' as const,
                content: `[busy] ${errMsg}`,
                timestamp: Date.now(), isError: true,
              }],
              isStreaming: false,
            }));
            return;
          }
        }
        // If the user dismissed the decision (or sent an abort-ish
        // response), the engine may or may not resume. Either way
        // we just let the next run_end or stream_event decide.
        void wasAwaiting;
        // daemon may have just created a new session (when
        // currentSessionId was null). Refresh the list and, if
        // we're still on no current session, promote the
        // most-recently-used one. This makes "+ new session" → type →
        // send → land in a brand-new session seamless.
        //
        // merge with localStorage so the LeftPanel
        // shows all known sessions, not just the ones the
        // daemon reports. The local cache also supplies
        // `lastUsedAt` when the daemon omits it.
        // localStorage merge is retired; the
        // daemon's listSessions is authoritative.
        try {
          const { sessions } = await rpc.listSessions();
          const list = sessions ?? [];
          set({ sessions: list });
          if (!get().currentSessionId && list.length > 0) {
            const newest = [...list].sort(
              (a, b) => (b.lastUsedAt ?? 0) - (a.lastUsedAt ?? 0),
            )[0];
            if (newest?.id) {
              set({ currentSessionId: newest.id });
              // when promoting a freshly-discovered
              // session to current, hydrate its transcript
              // from the daemon so the chat log isn't empty
              // for a session we just created.
              void get().hydrateTranscript(newest.id);
            }
          }
        } catch { /* non-fatal: session list refresh failed */ }
      } catch (e: any) {
        set((s) => ({
          messages: [...s.messages, {
            id: newId('system'), role: 'system', content: `[Error] ${e?.message ?? String(e)}`,
            timestamp: Date.now(), isError: true,
          }],
          isStreaming: false,
        }));
      }
    },

    cancelQuery: async () => {
      // R267 polish: a cancel means "stop everything",
      // which includes the queued follow-up. The user
      // explicitly said "我会自己 cancel 前一个, 再提交后一个"
      // — when they hit cancel they want a clean slate,
      // not a surprise prompt firing the moment the
      // daemon tears down.
      try { await rpc.cancel(); } catch {}
      set({ isStreaming: false, pendingFollowUp: null });
    },

    cancelPendingFollowUp: () => set({ pendingFollowUp: null }),

    switchSession: async (sessionId: string) => {
      // cancel any pending debounced draft write for the
      // OLD session (the one we're leaving) before we change
      // currentSessionId, then load the NEW session's draft
      // into currentInput. A pending write to the old session
      // would otherwise fire 300ms later with the new
      // currentSessionId and corrupt the new session's draft.
      if (draftTimer) { window.clearTimeout(draftTimer); draftTimer = null; }
      const oldSid = get().currentSessionId;
      if (oldSid && oldSid !== sessionId) {
        // Persist whatever the user had in the input box for
        // the old session, in case they typed but never sent.
        // (setCurrentInput's debounce may not have flushed yet.)
        const inFlight = get().currentInput;
        if (inFlight.trim().length > 0) writeDraft(oldSid, inFlight);
        else clearDraft(oldSid);
      }
      // R267 (2026-09-14): flip `currentSessionId` BEFORE
      // the daemon round-trip. Both the synchronous
      // `hydrateTranscript` guard and the WS
      // `transcript_event(sync)` handler check
      // `sid !== get().currentSessionId` and bail out
      // when they don't match — pre-fix the switch order
      // was `loadSession → hydrate → set(currentSessionId)`,
      // which meant the daemon's sync push arrived with
      // `sid === NEW` while `get().currentSessionId` was
      // still `OLD`, and the guard dropped the message
      // list on the floor. The result was a session switch
      // that "succeeded" (the left rail updated) but
      // showed an empty chat because no transcript ever
      // landed. Fix: commit currentSessionId first, then
      // let the daemon round-trip + WS push follow.
      //
      // We also clear `messages` synchronously to avoid
      // a "stale frames" flash where the OLD session's
      // messages render under the NEW sessionId (a
      // confusing mismatch the user can see in
      // TaskSummary → messageCount). The async hydrate
      // refills the array when the daemon's transcript
      // arrives.
      set({
        currentSessionId: sessionId,
        isStreaming: false,
        messages: [],
        currentInput: readDraft(sessionId),
      });
      // ask the daemon to swap its in-memory
      // transcript to this session, then back-fill our
      // own `messages` array from the daemon. The
      // daemon's loadSession fires a `transcript_event`
      // with action "sync" to every connected client,
      // but we don't rely on the WS round-trip for the
      // first paint — we explicitly call getTranscript
      // so the chat log is correct even if the WS push
      // is delayed.
      try { await rpc.loadSession(sessionId); } catch { /* daemon may not have a SessionStore */ }
      const cached = await get().hydrateTranscript(sessionId);
      void cached; // hydrateTranscript already called set()
    },

    // back-fill `messages` from the daemon's
    // current in-memory transcript. The first call
    // happens during initialize (right after the
    // getState RPC returns the active session id);
    // subsequent calls happen on session switch and
    // on WS reconnect. Empty when the daemon's
    // transcript is empty (e.g. a brand-new session
    // that hasn't received any messages yet). Returns
    // the messages it set so callers can chain.
    //
    // The `sessionId` arg is what we WANT to hydrate
    // for; the daemon's getTranscript returns the
    // engine's current session, so we verify the
    // sessionId matches before applying — otherwise
    // a slow call from a previous session could
    // pollute the new session's state.
    hydrateTranscript: async (sessionId) => {
      if (!sessionId) {
        set({ messages: [], lastTranscriptSyncMs: Date.now() });
        return [];
      }
      try {
        const t = await rpc.getTranscript();
        if (t.sessionId !== sessionId || t.sessionId !== get().currentSessionId) {
          // The daemon is on a different session than we
          // asked for (or the user has moved on). Don't
          // apply; the next sync / next call will catch up.
          // (the prior round: we still update lastTranscriptSyncMs
          // so the next reconnect's staleness check has a
          // recent anchor; otherwise the threshold check
          // keeps firing forever on a slow session switch.)
          set({ lastTranscriptSyncMs: Date.now() });
          return get().messages;
        }
        const msgs = (t.messages ?? []).map(messageToChatMessage).filter(Boolean) as ChatMessage[];
        // if the daemon returned an empty transcript
        // but we previously had one, the daemon was almost
        // certainly restarted (its in-memory transcript is
        // gone). Surface a system message so the user isn't
        // silently confused by a sudden empty chat. The
        // sessionId still matches, so this isn't a wrong-
        // session case; it's a "the daemon forgot" case.
        const previous = get().messages;
        const daemonLikelyRestarted = msgs.length === 0 && previous.length > 0;
        // merge by id rather than REPLACE. The
        // pre-fix `set({ messages: msgs })` clobbered
        // optimistic local additions — most importantly
        // the just-submitted user message that
        // {@link sendMessage} pushed before the
        // `getTranscript` round-trip landed. If the
        // daemon's transcript is a few messages behind
        // the renderer's local view (e.g. the user msg
        // is in flight to be appended server-side), the
        // user's prompt vanished and the chat looked
        // blank on submit. Same merge contract as the
        // `transcript_event(sync)` handler above:
        // daemon-authoritative for matching ids, local
        // append for ids the daemon hasn't seen yet.
        const byIncomingId = new Set(msgs.map((m) => m.id));
        const merged: ChatMessage[] = [...msgs];
        for (const m of previous) {
          if (!byIncomingId.has(m.id) && !merged.some((x) => x.id === m.id)) {
            merged.push(m);
          }
        }
        set({
          messages: daemonLikelyRestarted
            ? [...merged, {
                id: newId('system'),
                role: 'system' as const,
                content: `[Reconnect] daemon was restarted; in-memory transcript is gone. The session id is preserved, but the chat log starts from here.`,
                timestamp: Date.now(),
                isError: true,
              }]
            : merged,
          lastTranscriptSyncMs: Date.now(),
        });
        return merged;
      } catch (e: any) {
        // don't wipe `messages` on failure. The
        // previous behaviour was to set messages: [] when
        // getTranscript threw, which is wrong on a
        // reconnect — the user had a real transcript a
        // second ago, and a transient RPC error shouldn't
        // erase it. The transcript is the source of truth,
        // but a failed fetch is NOT the same as "the
        // transcript is empty"; we keep what we have and
        // surface the error as a system message so the
        // user can see why the bar might be stale.
        console.warn('[store] hydrateTranscript failed:', e);
        set((s) => ({
          lastTranscriptSyncMs: Date.now(),
          messages: [...s.messages, {
            id: newId('system'),
            role: 'system' as const,
            content: `[Reconnect] failed to fetch transcript: ${e?.message ?? String(e)}. Keeping the previous transcript; it may be stale.`,
            timestamp: Date.now(),
            isError: true,
          }],
        }));
        return get().messages;
      }
    },

    // clear currentSessionId without daemon round-trip.
    // Used by the SessionList "+" button. The next sendMessage
    // (which sees currentSessionId === null) lets the daemon
    // create a new session implicitly; refreshSessions after
    // the call surfaces the new id, and we auto-promote it
    // to current (see sendMessage tail).
    // on switching, also restore the draft for the
    // "no-session-yet" slot. The user types in this state when
    // they've hit "+ new session" and haven't sent yet; we keep
    // their in-progress text in the `__none__` key so a
    // Tauri reload (or a switch back to the no-session
    // state) restores it.
    setCurrentSessionId: (id) => {
      if (draftTimer) { window.clearTimeout(draftTimer); draftTimer = null; }
      const oldSid = get().currentSessionId;
      if (oldSid !== id) {
        const inFlight = get().currentInput;
        if (inFlight.trim().length > 0) writeDraft(oldSid, inFlight);
        else clearDraft(oldSid);
      }
      // hydrate from the daemon (not localStorage).
      // The local copy was the source of truth under
      // the prior round; the daemon's appState.transcript is the
      // source of truth under the prior round. We clear messages
      // synchronously to avoid a stale-frames flash, then
      // call hydrateTranscript asynchronously to fill in
      // the new session's history. The fire-and-forget
      // void here is fine — the caller doesn't await
      // setCurrentSessionId, and the async hydrate
      // updates `messages` when it lands.
      set({
        currentSessionId: id,
        messages: [],
        currentInput: readDraft(id),
      });
      if (id) {
        void get().hydrateTranscript(id);
      }
    },

    // loadSession + hydrate shortcut. Calls the
    // RPC and hydrates the local transcript cache. The
    // TUI uses the same RPC directly; this is the
    // desktop wrapper so SubagentSpawnCard / SubagentPanel
    // can call a single store action.
    loadSession: (id) => {
      void rpc.loadSession(id).then(() => get().hydrateTranscript(id));
    },

    // subagent view switch. The user presses the
    // "View subagent →" link in SubagentPanel, the
    // SubagentSpawnCard's "View subagent →" link, or the
    // TUI's Ctrl+1..9 / 'v'. We push the previous view
    // onto viewHistory (so the user can pop back) and
    // flip viewingSubagentId. loadSession follows the
    // same hydrate path as the regular switch.
    setViewingSubagentId: (id) => {
      const cur = get();
      if (cur.viewingSubagentId === id) return;
      const history = cur.viewHistory.slice();
      if (cur.viewingSubagentId == null) history.push({ kind: 'primary' });
      else history.push({ kind: 'subagent', jobId: cur.viewingSubagentId });
      set({ viewingSubagentId: id, viewHistory: history });
      if (id) {
        // Drive the same RPC + hydrate path as a regular
        // session switch. The session list refreshes
        // happen on the next getState poll.
        void cur.loadSession(id);
        void cur.hydrateTranscript(id);
      }
    },

    // start a fresh local session. The user hits the
    // "+ new session" button (or Ctrl+Shift+N), and we mint a UUID
    // on the spot. No daemon round-trip; the session shows
    // up in the LeftPanel immediately. The first sendMessage
    // after this hits the daemon with the new id; the
    // daemon's sessionId param is metadata only — the engine
    // still answers on its single transcript.
    createNewSession: () => {
      if (draftTimer) { window.clearTimeout(draftTimer); draftTimer = null; }
      const oldSid = get().currentSessionId;
      if (oldSid) {
        const inFlight = get().currentInput;
        if (inFlight.trim().length > 0) writeDraft(oldSid, inFlight);
        else clearDraft(oldSid);
      }
      // ask the daemon to mint a real session id and
      // create an empty transcript on disk. The local
      // `crypto.randomUUID()` path (the prior round) is still the
      // fallback when the daemon rejects the call (e.g. the
      // engine was started without `--sessions-dir`, or the
      // stdio daemon path doesn't have a SessionStore).
      // after createSession, the daemon fires a
      // transcript_event (action "sync", empty messages
      // array) which our subscriber handles. We don't
      // need a local mergeWithPersisted round-trip — the
      // sessions list will catch up via refreshSessions
      // in the next message.
      void (async () => {
        let newId: string;
        try {
          const r = await rpc.createSession();
          newId = r.sessionId;
        } catch {
          newId = (typeof crypto !== 'undefined' && 'randomUUID' in crypto)
            ? crypto.randomUUID()
            : 's-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
        }
        // Now switch to the new id. loadSession is best-effort:
        // a daemon-side error leaves us on the local id.
        try { await rpc.loadSession(newId); } catch { /* daemon stub or local id */ }
        set({
          currentSessionId: newId,
          messages: [],
          currentInput: '',
          sessions: [
            ...get().sessions,
            { id: newId, name: undefined, lastUsedAt: Date.now(), messageCount: 0 },
          ],
        });
      })();
    },

    // delete a session. Refuses to delete the active
    // session (the user must switch first); the daemon
    // returns ENGINE_ERROR which we surface as a system
    // message. the prior round: the localStorage message cache is
    // retired; the only persisted per-session state left
    // is the input draft (the prior round), which we still drop here.
    deleteSession: async (sessionId: string) => {
      if (!sessionId) return;
      try {
        await rpc.deleteSession(sessionId);
        // If the deleted session is currently active, the
        // user needs to switch. The daemon's deleteSession
        // would have already thrown IllegalStateException
        // in that case, so the code below only runs for
        // non-active sessions.
        const next = get().sessions.filter((s) => s.id !== sessionId);
        useStore.setState({ sessions: next });
        // Drop the input draft for this id (the prior round). The
        // retired localStorage message cache no longer
        // needs explicit cleanup — the one-time migration
        // in `migrateLegacyLocalStorage()` already wiped
        // every `aethercode-session:*` key on the first
        // launch of the prior round+.
        try { window.localStorage.removeItem(draftKey(sessionId)); } catch {}
      } catch (e: any) {
        set((s) => ({
          messages: [...s.messages, {
            id: newId('system'), role: 'system',
            content: `[Delete session failed] ${e?.message ?? String(e)}`,
            timestamp: Date.now(), isError: true,
          }],
        }));
      }
    },

    // the daemon's listSessions is now the
    // authoritative source of session metadata. The
    // the prior-round localStorage merge is gone — the retired
    // cache is wiped by `migrateLegacyLocalStorage()`
    // on first launch of the prior round+. The daemon reads
    // from the SessionStore (a directory of JSONL
    // files under --sessions-dir), which is shared
    // across daemons, so a fresh daemon picks up
    // sessions created by previous daemon instances.
    refreshSessions: async () => {
      let daemonSessions: SessionInfo[] = [];
      try {
        // opt in to withPreview so the TUI's
        // session list shows real titles (first user
        // message, 200-char cap) instead of "Session
        // abc12345" placeholders. The daemon's
        // extraction is bounded (4 KB file read per
        // session, regex-only) so the cost is fine
        // for the typical 10-50 sessions per user.
        const { sessions } = await rpc.listSessions({
          withPreview: true,
          limit: 100,
        });
        daemonSessions = sessions ?? [];
      } catch {}
      set({ sessions: daemonSessions });
    },
    refreshTasks: async () => { try { const { tasks } = await rpc.listTasks(); set({ tasks: tasks ?? [] }); } catch {} },
    // 3-layer memory refresh. Each scope pulls its
    // own list + records the daemon's compression stats
    // so the right-rail Memory tab can show "12 / 50
    // entries (auto-compress on)" without an extra
    // round-trip. The sessionId defaults to the
    // currentSessionId; callers that want a different
    // session can pass it explicitly.
    refreshMemory: async (scope) => {
      try {
        const sessionId = scope === 'SESSION' ? (get().currentSessionId ?? null) : null;
        const cwd = scope === 'PROJECT' ? (get().cwd ?? null) : null;
        const result = await rpc.listMemory({ scope, sessionId, cwd });
        if (!result?.ok) return;
        set((s) => {
          const next = { ...s.memory };
          if (scope === 'USER') {
            next.user = { entries: result.entries as any, count: result.count };
          } else if (scope === 'PROJECT') {
            next.project = { entries: result.entries as any, count: result.count, cwd };
          } else {
            next.session = { entries: result.entries as any, count: result.count, sessionId };
          }
          return {
            memory: next,
            memoryStats: {
              autoCompress: result.autoCompress ?? s.memoryStats?.autoCompress ?? false,
              projectCompressThreshold: result.projectCompressThreshold ?? s.memoryStats?.projectCompressThreshold ?? 50,
              keepRecent: result.keepRecent ?? s.memoryStats?.keepRecent ?? 10,
            },
            lastMemoryRefreshMs: Date.now(),
          };
        });
      } catch (e) {
        console.warn('refreshMemory failed:', e);
      }
    },
    memorySet: async (opts) => {
      try {
        const sessionId = opts.scope === 'SESSION' ? (opts.sessionId ?? get().currentSessionId ?? null) : null;
        const cwd = opts.scope === 'PROJECT' ? (opts.cwd ?? get().cwd ?? null) : null;
        const r = await rpc.setMemory({
          scope: opts.scope,
          key: opts.key,
          content: opts.content,
          value: opts.value,
          tags: opts.tags,
          sessionId,
          cwd,
        });
        if (r?.ok) {
          // Refresh just the touched scope so the UI
          // updates without re-pulling the other two.
          await get().refreshMemory(opts.scope);
        }
      } catch (e) {
        console.warn('memorySet failed:', e);
      }
    },
    memoryDelete: async (opts) => {
      try {
        const sessionId = opts.scope === 'SESSION' ? (opts.sessionId ?? get().currentSessionId ?? null) : null;
        const cwd = opts.scope === 'PROJECT' ? (opts.cwd ?? get().cwd ?? null) : null;
        const r = await rpc.deleteMemory({ scope: opts.scope, key: opts.key, sessionId, cwd });
        if (r?.ok) {
          await get().refreshMemory(opts.scope);
        }
      } catch (e) {
        console.warn('memoryDelete failed:', e);
      }
    },
    compressProjectMemory: async (opts) => {
      try {
        const r = await rpc.compressProjectMemory({ cwd: opts.cwd, force: opts.force ?? true });
        if (r?.ok) {
          await get().refreshMemory('PROJECT');
        }
      } catch (e) {
        console.warn('compressProjectMemory failed:', e);
      }
    },
    bindSessionCwd: async (opts) => {
      try {
        const r = await rpc.bindSessionCwd({
          cwd: opts.cwd,
          sessionId: opts.sessionId ?? get().currentSessionId ?? null,
        });
        if (r?.ok) {
          set({
            cwd: r.newCwd,
            lastCwdChangedAt: Date.now(),
          });
        }
      } catch (e) {
        console.warn('bindSessionCwd failed:', e);
      }
    },
    // Kanban "+" button hook. The daemon's
    // TaskRegistry.create() fires a `task_event` (create)
    // which our subscriber will pick up and insert; we
    // also insert optimistically so the form clears
    // without a WS round-trip wait. The two paths
    // converge on the same id (the daemon's UUID), so
    // the optimistic insert is replaced (not duplicated)
    // when the event lands.
    createTask: async (opts) => {
      try {
        const r = await rpc.createTask({
          description: opts.description,
          type: opts.type,
          parentTaskId: opts.parentTaskId,
        });
        // Optimistic insert: also subscribe will
        // dedupe by id when the event lands.
        const t = (r as any).task as TaskInfo;
        if (t && t.id) {
          set((s) => {
            if (s.tasks.find((x) => x.id === t.id)) return {};
            return { tasks: [...s.tasks, t] };
          });
          return t;
        }
        return null;
      } catch (e) {
        console.warn('[store] createTask failed:', e);
        return null;
      }
    },
    // drag-drop a task to a new column. The
    // daemon transitions the status (idempotent) and
    // fans out `task_event` (update). Optimistic local
    // update so the column drop feels instant.
    updateTaskStatus: async (id, status) => {
      const cur = get().tasks.find((t) => t.id === id);
      if (!cur) return;
      if (cur.status === status) return;
      // Optimistic local update.
      set((s) => ({
        tasks: s.tasks.map((t) => t.id === id ? { ...t, status, endedAtMs: status === 'completed' || status === 'failed' || status === 'killed' ? Date.now() : t.endedAtMs } : t),
      }));
      try {
        await rpc.updateTaskStatus({ id, status });
      } catch (e) {
        // Rollback on failure.
        console.warn('[store] updateTaskStatus failed, rolling back:', e);
        set((s) => ({
          tasks: s.tasks.map((t) => t.id === id ? { ...t, status: cur.status, endedAtMs: cur.endedAtMs } : t),
        }));
      }
    },
    // list providers. The Settings panel
    // calls this on open. Cached in the store so
    // the next mount of the panel doesn't repeat
    // the round-trip.
    refreshProviders: async () => {
      try {
        // R285: prefer the rich listAvailableModels
        // RPC (it carries per-model variants +
        // currentVariant + activeVariant). The
        // legacy listProviders RPC is a fallback
        // for older daemon builds that haven't
        // shipped the new endpoint yet.
        let providers: any[] = [];
        let currentProvider: string | null = null;
        let currentModel: string | null = null;
        let currentVariant: string | null = null;
        let activeVariant: any = null;
        try {
          const r = await rpc.listAvailableModels();
          providers = r.providers ?? [];
          currentProvider = (r as any).currentProvider ?? null;
          currentModel = (r as any).currentModel ?? null;
          currentVariant = (r as any).currentVariant ?? null;
          activeVariant = (r as any).activeVariant ?? null;
        } catch {
          // legacy daemon — fall back to the
          // listProviders RPC. We don't try
          // listAvailableModels again on the next
          // tick; the boot path retries via
          // initialize() so the user gets the
          // rich form on a daemon restart.
          const r = await rpc.listProviders();
          providers = r.providers ?? [];
          currentProvider = r.currentProvider ?? null;
        }
        set({
          availableProviders: providers,
          currentProvider,
          currentVariant,
          activeVariant,
        });
        return { providers, currentProvider, currentModel };
      } catch (e) {
        console.warn('[store] refreshProviders failed:', e);
        return { providers: [], currentProvider: null, currentModel: null };
      }
    },
    // toggle the supervisor's
    // auto-restart behaviour. Off by default
    // (the user may want to inspect a crash).
    // The Settings panel surfaces this as a
    // checkbox under the concurrency / loop
    // detector cluster. The boolean is cached
    // in the store so the toggle re-renders
    // optimistically without waiting for a
    // getState round-trip.
    setAutoRestart: async (enabled: boolean) => {
      try {
        const r = await rpc.setAutoRestart({ enabled });
        set({ autoRestart: r.autoRestart });
        return r;
      } catch (e) {
        console.warn('[store] setAutoRestart failed:', e);
        return { ok: false, enabled, autoRestart: enabled };
      }
    },
    // forward a JSON-RPC notification
    // to a child daemon via the supervisor.
    // Used by the permission banner when the
    // TUI is connected to the supervisor (not
    // the child) — without this relay the user
    // would have to manually connect to the
    // child to respond. Returns the supervisor's
    // ack so the UI can show "forwarded" briefly.
    proxyNotification: async (childId: string, method: string, params: unknown) => {
      try {
        return await rpc.proxyNotification({ childId, method, params });
      } catch (e) {
        console.warn('[store] proxyNotification failed:', e);
        return { ok: false, forwarded: false };
      }
    },
    // pull the latest session
    // summary. The user explicitly asked
    // for "a summary regardless of whether
    // the task ended correctly" — so this is
    // called both (a) at the end of every
    // successful query (the engine's
    // RunEnd → setState, then the TUI
    // polls) and (b) when the loop-detected
    // banner shows. Cached in the store so
    // the End-of-task panel can read the
    // last value without re-fetching.
    refreshSummary: async (sessionId?: string | null) => {
      try {
        const r = await rpc.summary({ sessionId: sessionId ?? null });
        set({ lastSessionSummary: r });
        return r;
      } catch (e) {
        console.warn('[store] refreshSummary failed:', e);
        return null;
      }
    },
    // list agents. The Agents tab calls
    // this on open. The cached list is in the
    // store; the editor fetches the body on
    // demand.
    refreshAgents: async () => {
      try {
        const r = await rpc.listAgents();
        set({ agents: r.agents ?? [] });
      } catch (e) {
        console.warn('[store] refreshAgents failed:', e);
        set({ agents: [] });
      }
    },
    // agent CRUD pass-throughs. The
    // editor calls these directly; the
    // refreshAgents() in onSaved picks up
    // the new state.
    createAgent: async (opts) => rpc.createAgent(opts),
    updateAgent: async (opts) => rpc.updateAgent(opts),
    deleteAgent: async (name) => rpc.deleteAgent(name),
    // getAgentBody pass-through. The
    // AgentsPanel's AgentEditorLazy calls
    // this when opening an agent for edit;
    // the daemon returns the body plus
    // frontmatter fields (description /
    // displayName / model) so the editor
    // can prefill all four.
    getAgentBody: async (name) => rpc.getAgentBody(name),
    // switch provider. Updates the
    // engine's ChatClient (via the daemon) and
    // mirrors the new state in the store so
    // the Header's current-model badge and the
    // Settings picker's active row update
    // without a refresh.
    switchProvider: async (provider, model, variant) => {
      try {
        const r = await rpc.switchProvider({ provider, model: model ?? null, variant: variant ?? null });
        set({
          currentProvider: (r as any).provider ?? provider,
          // R285: the response tail from
          // switchProvider carries the active
          // variant row so the Quality
          // dropdown can update without an
          // extra round-trip. Fall back to the
          // daemon's null-payload when the
          // server hasn't filled the field
          // (older daemon builds).
          currentVariant: (r as any).variant ?? null,
          activeVariant: (r as any).activeVariant ?? null,
          // The engineState field is the
          // single source of truth for the
          // model. Refresh it after a switch so
          // the Header picks up the new value.
        });
        // route through refreshEngineState
        // instead of an inline getState() call so
        // the engineStateRefreshedAt stamp is also
        // updated and the failure path matches the
        // the prior round pattern (log + keep prior state).
        await get().refreshEngineState();
        return;
      } catch (e) {
        console.warn('[store] switchProvider failed:', e);
        throw e;
      }
    },
    refreshProjects: async () => {
      try {
        const { projects } = await rpc.listProjects();
        set({ projects: projects ?? [], currentProjectId: projects?.find((p) => p.active)?.id ?? get().currentProjectId });
      } catch {}
    },
    refreshMetrics: async () => { try { const m = await rpc.getMetrics(); set({ metrics: m }); } catch {} },
    refreshTraces: async () => { try { const { traces } = await rpc.getTraces(20); set({ traces: traces ?? [] }); } catch {} },
    refreshEngineStats: async () => {
      // pull the latest stats. The daemon's
      // getEngineStats is cheap (one Runtime call + an
      // AtomicReference read). We don't validate the shape
        // — the daemon is the source of truth and the
        // EngineStatsView is structural.
      try {
        const s = await rpc.getEngineStats() as any;
        if (s && typeof s === 'object' && 'memPct' in s) {
          set({ engineStats: s as EngineStatsView });
        }
      } catch { /* daemon offline; leave stale value */ }
    },
    requestConcurrencyProfile: async (name) => {
      // switch profile + re-poll. The Settings panel
      // also calls this when the user picks a profile from
      // the dropdown.
      try {
        await rpc.setConcurrencyProfile(name);
      } catch (e) {
        // Surface as a system message so the user knows the
        // switch failed (e.g. daemon offline or invalid name).
        const errMsg = (e as Error)?.message ?? String(e);
        set((s) => ({
          messages: [...s.messages, {
            id: newId('system'),
            role: 'system' as const,
            content: `设置并发配置失败: ${errMsg}`,
            timestamp: Date.now(),
            isError: true,
          }],
        }));
        return;
      }
      // Refresh stats so the new profile is reflected in
      // the Header pill immediately.
      await get().refreshEngineStats();
    },
    refreshModels: async () => {
      try {
        const ml = await rpc.listModels();
        set({ availableModels: ml.models ?? [] });
      } catch {}
    },

    setModel: async (model: string) => {
      await rpc.setModel(model);
      set((s) => ({ engineState: s.engineState ? { ...s.engineState, model } : null, model }));
      // persist the user's choice to
      // localStorage so a reload (or a fresh
      // daemon) restores the same model. We
      // write AFTER the daemon RPC succeeds so
      // a failure leaves the prior value
      // intact. The in-memory state is the
      // source of truth; localStorage is a
      // nice-to-have (the R117 lesson).
      const prefs = readEnginePrefs();
      prefs.model = model;
      writeEnginePrefs(prefs);
      // kick a refreshEngineState() so the canonical
      // engineState (with all fields, not just the
      // optimistic model update) lands in the store.
      // Fire-and-forget — the optimistic set above is
      // good enough for the immediate UI; the refresh
      // reconciles any fields the daemon adjusted
      // server-side (e.g. sessionId rotation on a model
      // change that affected provider).
      void get().refreshEngineState();
    },
    /** R285: switch just the variant without
     *  touching the provider / model. Mirrors the
     *  {@code switchVariant} RPC. Returns the
     *  active variant row so the caller can
     *  echo it in a toast ("Quality set to
     *  high (1.0 / 48K)"). */
    switchVariant: async (variant: string) => {
      try {
        const r = await rpc.switchVariant({ variant });
        const rAny = r as any;
        // persist the user's choice to
        // localStorage so a reload (or a fresh
        // daemon) restores the same variant. We
        // write AFTER the daemon RPC succeeds so
        // a failure leaves the prior value
        // intact. The in-memory state is the
        // source of truth; localStorage is a
        // nice-to-have (the R117 lesson).
        const prefs = readEnginePrefs();
        if (rAny.variant) prefs.variant = rAny.variant;
        writeEnginePrefs(prefs);
        set({
          currentVariant: rAny.variant ?? null,
          activeVariant: rAny.activeVariant ?? null,
        });
        return rAny.activeVariant ?? null;
      } catch (e) {
        console.warn('[store] switchVariant failed:', e);
        throw e;
      }
    },
    setPermissionMode: async (mode: string) => {
      // the dropdown sends a UI tier
      // ('ask' / 'smart' / 'bypass') but the
      // daemon speaks the canonical enum
      // (ASK_BEFORE_TOOL / ACCEPT_EDITS /
      // BYPASS_PERMISSIONS). Map before the
      // RPC. The map is a no-op when the caller
      // passes a raw enum (e.g. an advanced mode
      // sent from the TUI / Settings), so legacy
      // call sites keep working unchanged.
      const daemonMode = mapUiPermissionToDaemon(mode);
      // capture the RPC's ok flag. pre-R198
      // the action awaited the RPC but discarded
      // the result, so a daemon-side rejection
      // (e.g. unknown mode) silently left the
      // renderer showing the user's pick while
      // the daemon kept its old mode. The next
      // refreshEngineState() then overwrote the
      // dropdown with the daemon's actual value,
      // and the user saw the mode "revert" out
      // of nowhere. Pin the local state to the
      // daemon's authoritative value on rejection.
      const r: any = await rpc.setPermissionMode(daemonMode);
      const ok = !r || r.ok !== false;
      // the canonical enum goes into
      // engineState (so the StatusBar /
      // PermissionPromptBanner can show the
      // right label via permissionModeLabel);
      // the renderer's standalone
      // `permissionMode` (which the dropdown
      // binds to via `value=`) keeps the UI
      // tier the user just picked, so the
      // dropdown visually stays in sync with
      // the user's choice. The dropdown's
      // options are the 3 tiers — if the
      // caller passed a raw enum (e.g. via
      // the TUI), the standalone field
      // stores that raw enum too, and the
      // browser will fall back to the first
      // option visually until the user
      // re-picks.
      //
      // on rejection, mirror the daemon's
      // value (returned in r.mode) so the
      // dropdown reverts immediately instead of
      // waiting for the next 15s refresh.
      const effectiveMode = ok
        ? mode
        : ((r && (r as any).mode) ? mapDaemonToUiPermission((r as any).mode) : get().permissionMode);
      const effectiveDaemonMode = ok
        ? daemonMode
        : ((r && (r as any).mode) ? (r as any).mode : get().engineState?.permissionMode ?? daemonMode);
      set((s) => ({
        engineState: s.engineState ? { ...s.engineState, permissionMode: effectiveDaemonMode } : null,
        permissionMode: effectiveMode,
      }));
      // persist the UI tier (not the
      // canonical enum) so the next launch's
      // dropdown restores the same option the
      // user picked. mapUiPermissionToDaemon
      // above handles legacy raw-enum values
      // on the way back in.
      //
      // on rejection, do NOT persist the
      // user's pick — the daemon rejected it,
      // so writing the value would mean the
      // next launch also lands on a mode the
      // daemon can't honour. We keep the prior
      // persisted value and let the canonical
      // refresh below reconcile the dropdown.
      if (ok) {
        const prefs = readEnginePrefs();
        // The value we save is the raw `mode`
        // the user sent. If the user picked
        // 'ask' from the dropdown, we save
        // 'ask' and the next launch shows
        // 'Ask first'. If a power user picked
        // 'ACCEPT_TASK' from the TUI and we
        // never saw a UI tier for it, we save
        // the raw enum so we round-trip
        // faithfully (it won't match any
        // dropdown option, but the StatusBar
        // shows the raw name so the user knows
        // they're in an advanced mode).
        prefs.permissionMode = mode;
        writeEnginePrefs(prefs);
      } else {
        // surface the rejection as a
        // chat-line so the user understands
        // why their pick didn't stick. The
        // status bar / dropdown auto-revert is
        // helpful but easy to miss; a one-line
        // banner is unambiguous.
        const reason = (r && (r as any).error) || `daemon rejected permission mode "${daemonMode}"`;
        set((s) => ({
          messages: [...s.messages, {
            id: newId('system'),
            role: 'system' as const,
            content: `[perm 模式变更失败] ${reason}`,
            timestamp: Date.now(),
            isError: true,
          }],
        }));
      }
      // same pattern as setModel — optimistic
      // update above, canonical refresh after. The
      // mode change can affect the daemon's permission
      // matrix wiring and the engine's `permissionMode`
      // field on the state snapshot.
      void get().refreshEngineState();
    },
    // R198: keep the mapping source-of-truth (UI tier -> daemon enum)

    // the user can pick how the renderer behaves on
    // app start. We persist to localStorage so the choice
    // survives reloads. The initialize() flow reads
    // `defaultOpenBehavior` to decide whether to mint a
    // fresh session or restore the most-recent one.
    setDefaultOpenBehavior: (b) => {
      set({ defaultOpenBehavior: b });
      try {
        const prefs = readEnginePrefs();
        (prefs as Record<string, unknown>).defaultOpenBehavior = b;
        writeEnginePrefs(prefs);
      } catch {
        /* localStorage may be unavailable (e.g. private
         * mode); the in-memory value is still set. */
      }
    },

    // flip the visual theme. The choice is
    // mirrored to document.documentElement so the
    // [data-theme="..."] CSS rules re-skin every
    // component (global.css / App.css / chat.css /
    // etc). Persisted to prefs.theme so a reload
    // picks it back up via initialize() above.
    setTheme: (t) => {
      set({ theme: t });
      try {
        document.documentElement.setAttribute('data-theme', t);
      } catch {
        /* jsdom / SSR — the attribute apply is a
         * no-op in test environments. */
      }
      try {
        const prefs = readEnginePrefs();
        (prefs as Record<string, unknown>).theme = t;
        writeEnginePrefs(prefs);
      } catch {
        /* localStorage may be unavailable; the
         * in-memory value is still set. */
      }
    },

    // dismiss the model-mismatch prompt
    // without changing the model. The user
    // picked "keep ${daemonModel}" — they accept
    // the daemon's canonical model and don't want
    // to be re-prompted for THIS particular
    // (prefsModel, daemonModel) pair. The pair key
    // (e.g. "MiniMax-M1|MiniMax-M3") is appended to
    // modelMismatchDismissed; the initialize() check
    // skips pairs already in the list. A different
    // pair (e.g. M1|M4 after an upgrade) re-prompts.
    //
    // The dismissal is in-memory only — it's
    // transient state about a one-time prompt and
    // gets recomputed on every reload. We don't
    // persist it to localStorage because:
    // (a) it's a UI hint, not a user preference,
    // (b) re-prompting once per launch is harmless,
    // (c) avoiding a second localStorage key keeps
    // the enginePrefs contract narrow.
    //
    // The action is no-op when there's no current
    // prompt so a stray call from a hot-reload
    // doesn't error.
    dismissModelMismatch: () => {
      const prompt = get().modelMismatchPrompt;
      if (!prompt) return;
      const pair = `${prompt.prefsModel}|${prompt.daemonModel}`;
      set((s) => ({
        modelMismatchPrompt: null,
        modelMismatchDismissed: s.modelMismatchDismissed.includes(pair)
          ? s.modelMismatchDismissed
          : [...s.modelMismatchDismissed, pair],
      }));
    },

    // accept the localStorage preference
    // (the user's "old" model selection) and
    // switch the daemon to it. Equivalent to
    // picking that model from the model dropdown
    // — the setModel action does the actual
    // daemon RPC + localStorage write. We just
    // clear the prompt state so the banner
    // disappears and the pair is added to the
    // dismissed list (so the next launch with
    // the same daemon model doesn't re-prompt
    // for a mismatch the user just resolved).
    //
    // If the user then switches daemon model
    // again, a fresh prompt fires because the
    // new (prefsModel, daemonModel) pair isn't
    // in modelMismatchDismissed.
    acceptModelMismatch: async () => {
      const prompt = get().modelMismatchPrompt;
      if (!prompt) return;
      // Clear the prompt FIRST so the banner
      // disappears immediately, then call
      // setModel (which is async — RPC + write).
      // If setModel fails, the prompt stays
      // cleared and the user sees the daemon
      // error via the standard channel. We
      // don't restore the prompt on failure
      // because the user has already seen the
      // banner; a stuck prompt is worse than
      // a silent reversion.
      set({ modelMismatchPrompt: null });
      const pair = `${prompt.prefsModel}|${prompt.daemonModel}`;
      set((s) => ({
        modelMismatchDismissed: s.modelMismatchDismissed.includes(pair)
          ? s.modelMismatchDismissed
          : [...s.modelMismatchDismissed, pair],
      }));
      try {
        await get().setModel(prompt.prefsModel);
      } catch (e) {
        // R118 pattern: surface as a system
        // message so the user knows the
        // switch didn't go through.
        set((s) => ({
          messages: [...s.messages, {
            id: newId('system'),
            role: 'system' as const,
            content: `[Model switch failed] ${(e as { message?: string } | null)?.message ?? String(e)}`,
            timestamp: Date.now(),
            isError: true,
          }],
        }));
      }
    },

    // retry a failed sub-task. The backend's retrySubTask RPC
    // delegates to query() with a synthesised "[R89 retry sub-task]
    // <goal>" prompt. We optimistically flip the local sub-task
    // card to `in_progress` so the user sees the retry kick off
    // before the first stream_event arrives. Errors fall back to
    // a system message in the conversation.
    retrySubTask: async (subTaskId: string, goal: string, hint?: string) => {
      try {
        // Optimistic UI: the card's previous status was 'failed';
        // we flip to 'in_progress' so the meta line shows the live
        // ticker. The engine will emit SubTaskStart/SubTaskEnd
        // events as the new run unfolds, which will reconcile.
        set((s) => ({
          subTasks: s.subTasks.map((st) => st.id === subTaskId
            ? { ...st, status: 'in_progress' as const, errorMessage: undefined }
            : st),
        }));
        await rpc.retrySubTask(goal, hint);
      } catch (e: any) {
        // Surface the failure as a system message and revert the
        // optimistic status flip.
        set((s) => ({
          messages: [...s.messages, {
            id: newId('system'),
            role: 'system' as const,
            content: `[Retry failed] ${e?.message ?? String(e)}`,
            timestamp: Date.now(),
            isError: true,
          }],
          subTasks: s.subTasks.map((st) => st.id === subTaskId
            ? { ...st, status: 'failed' as const, errorMessage: e?.message ?? String(e) }
            : st),
        }));
      }
    },

    // mark a sub-task as skipped. Pure local state — the
    // engine doesn't have a "skip" boundary, so we don't issue
    // a new query or hit the daemon. The card flips to
    // 'skipped' and a system note is appended to the message
    // stream so the model also has a record of the user's
    // intent. If the model later emits a SubTaskEnd for this
    // id (e.g. the model already finished the work), the
    // stream-event handler in MessageList reconciles.
    skipSubTask: (subTaskId: string) => {
      set((s) => {
        const target = s.subTasks.find((st) => st.id === subTaskId);
        if (!target) return s;
        return {
          subTasks: s.subTasks.map((st) => st.id === subTaskId
            ? { ...st, status: 'skipped' as const, endedAt: Date.now() }
            : st),
          messages: [...s.messages, {
            id: newId('system'),
            role: 'system' as const,
            content: `[Skip] 用户跳过子任务: ${target.content || '(未命名)'}`,
            timestamp: Date.now(),
          }],
        };
      });
    },

    // install a per-(tool, target) override on the backend's
    // permission policy. The backend's ProjectPermissionPolicy is
    // rebuilt with the new rule appended to the appropriate bucket.
    // On the frontend we mirror the change in the
    // alwaysAllowedTools Set so the UI (the "always allow" checkbox in
    // PermissionList) immediately reflects the new rule.
    installPermissionOverride: async (tool: string, decision: 'allow' | 'deny', target?: string, scope?: 'session' | 'project' | 'user') => {
      try {
        await rpc.permissionPolicyOverride(tool, decision, { target, scope });
        if (decision === 'allow') {
          set((s) => {
            const next = new Set(s.alwaysAllowedTools);
            next.add(tool);
            // Also auto-approve any pending requests for this tool.
            const stillPending = s.pendingPermissions.filter((p) => p.tool !== tool);
            const toAutoApprove = s.pendingPermissions.filter((p) => p.tool === tool);
            for (const p of toAutoApprove) {
              void get().respondPermission(p.requestId, true);
            }
            return {
              alwaysAllowedTools: next,
              pendingPermissions: stillPending,
            };
          });
        } else {
          // For deny we don't need a local mirror — the next
          // permission ask just won't appear (or will be denied
          // immediately by the policy). We still emit a system
          // message so the user can see "denied for this session".
          set((s) => ({
            messages: [...s.messages, {
              id: newId('system'),
              role: 'system' as const,
              content: `[Permission] 始终拒绝 ${tool}${target ? ` (${target})` : ''} (本会话内)`,
              timestamp: Date.now(),
            }],
          }));
        }
      } catch (e: any) {
        set((s) => ({
          messages: [...s.messages, {
            id: newId('system'),
            role: 'system' as const,
            content: `[Permission override failed] ${e?.message ?? String(e)}`,
            timestamp: Date.now(),
            isError: true,
          }],
        }));
      }
    },

    toggleTool: (toolName: string) => {
      set((s) => {
        const cur = s.enabledTools ? new Set(s.enabledTools) : new Set(s.tools.map((t) => t.name));
        if (cur.has(toolName)) cur.delete(toolName); else cur.add(toolName);
        return { enabledTools: cur };
      });
    },

    // R288: SDD mode toggle. Flipped by the 📐 pill
    // above MessageInput. When on, the next user turn
    // is routed through the 4-phase SSD flow instead of
    // a regular query, and SddPhaseBar renders below
    // the MessageList with phase progress.
    //
    // R289 follow-up: flipping off mid-run tears down
    // the driver so we don't leak a half-finished
    // MockSsdDriver (its `started` flag would keep
    // firing events into a dead listener array).
    setSddEnabled: (on: boolean) => {
      set({ sddEnabled: on });
      if (!on) get().stopSsdFlow();
    },

    // R289: kick off an SSD run for the given intent.
    // Spawns a MockSsdDriver with a canned 14-event
    // sequence that mirrors the daemon's wire format
    // (phase-list → 4×(phase-start, phase-draft,
    // phase-accepted) → complete). The driver is
    // dynamically imported so its code is NOT in the
    // initial bundle — users who never flip the
    // 📐 toggle never pay for it.
    //
    // Wire contract:
    //   - resets ssdPhases to a 4-phase template
    //     (idle / 需求分析 etc.)
    //   - pushes the intent as a user message in the
    //     chat list so the dialog has context
    //   - subscribes to driver events; updates
    //     ssdPhases[i].state on phase-start /
    //     phase-draft / phase-accepted / phase-skipped
    //     / phase-error
    //   - on phase-draft, pushes the draft preview
    //     as an assistant message so the user reviews
    //     in-flow (with the file path inline)
    //   - on complete: sets ssdActive=false, marks
    //     remaining phases 'skipped', and toggles
    //     sddEnabled off so the bar collapses
    //   - on abort: stops the driver, clears state
    startSsdFlow: async (intent: string) => {
      // If a previous run is still active, tear it down
      // first — the driver emits events into a closed-
      // over handler and a second start() would leave
      // the old handlers subscribed.
      if (ssdDriverRef.driver) {
        try { ssdDriverRef.unsubscribe?.(); ssdDriverRef.driver.stop(); } catch {}
        ssdDriverRef = { driver: null, unsubscribe: null };
      }
      // Generate a slug from the intent. ≤10 ASCII
      // chars, hyphen-joined, lowercase. Falls back to
      // `sd-<timestamp36>` when the intent has no
      // alphanumeric characters.
      const slug = intent
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .slice(0, 10)
        || `sd-${Date.now().toString(36).slice(-6)}`;

      // R292: Spec Kit 8-phase template (constitution /
      // specify / clarify / plan / analyze / tasks /
      // implement / converge). Optional quality gates
      // (clarify / analyze / converge) carry `optional:
      // true` so the chip strip renders them half-opacity.
      // The driver's `phase-list` event replaces this with
      // its own (matching) list — we seed it here so the
      // UI has something to render before the first event
      // lands.
      const phaseTemplate: Array<{ id: string; title: string; optional?: boolean; state: 'idle' | 'running' | 'pending-accept' | 'clarify-pending' | 'converge-pending' | 'done' | 'skipped' | 'failed'; preview?: string; path?: string }> = [
        { id: 'constitution', title: '项目原则',  state: 'idle' },
        { id: 'specify',      title: '需求分析',  state: 'idle' },
        { id: 'clarify',      title: '需求澄清',  state: 'idle', optional: true },
        { id: 'plan',         title: '详细设计',  state: 'idle' },
        { id: 'analyze',      title: '一致性分析', state: 'idle', optional: true },
        { id: 'tasks',        title: '任务分析',  state: 'idle' },
        { id: 'implement',    title: '执行实现',  state: 'idle' },
        { id: 'converge',     title: '收敛验证',  state: 'idle', optional: true },
      ];

      // R292: delegate the canned event sequence to
      // `defaultSddEventSequence` (lives in driver.ts) so
      // there's exactly one source of truth for the dev-mode
      // 8-phase mock. The mock driver emits one event per
      // ~280 ms so a full run completes in ~6 s — fast
      // enough to feel snappy, slow enough that the user
      // watches each phase chip flip in order.
      //
      // Mirror the daemon's `nextSlug` policy: prepend
      // `001-` so the on-screen path matches what a real
      // Spec Kit run would write (`<cwd>/.specify/specs/
      // 001-<slug>/`). `001-` is the default SEQUENTIAL
      // numbering; the daemon-side runner bumps the
      // counter when multiple features live in the same
      // `.specify/specs/` dir.
      const featureSlug = `001-${slug}`;
      // We pull TauriSsdDriver + MockSsdDriver +
      // defaultSddEventSequence together via dynamic import
      // (chunk split): users who never flip 📐 pay zero. The
      // mock sequence is built below and only consumed when
      // no daemonInfo.jarPath is present.
      const [{ TauriSsdDriver }, driverMod] = await Promise.all([
        import('../components/ssd/tauriSsdDriver'),
        import('../components/ssd/driver'),
      ]);
      const { MockSsdDriver, defaultSddEventSequence } = driverMod;
      const events = defaultSddEventSequence(featureSlug);

      // Push the user intent as a chat message so the
      // chat list has context for the assistant
      // messages we're about to push.
      const userMsgId = newId('user');
      set((s) => ({
        ssdPhases: phaseTemplate,
        ssdActive: true,
        ssdSlug: slug,
        ssdIntent: intent,
        currentInput: '',
        isStreaming: false,   // we don't go through the regular query() path
        messages: [...s.messages, {
          id: userMsgId, role: 'user', content: intent, timestamp: Date.now(),
        }],
      }));

      // Dynamic import so SsdDriver stays out of the
      // initial bundle (users who never flip 📐
      // never pay for it).
      // Pass eventDelay=280 so the 14 events spread
      // out over ~3.9 s — fast enough to feel snappy,
      // slow enough that the user sees each phase
      // chip flip idle → running → pending-accept →
      // done in order. Without this delay the mock
      // driver fires all 14 events in one synchronous
      // tick and the bar collapses before the user
      // perceives anything.
      // (MockSsdDriver + defaultSddEventSequence were
      // pulled in via the dynamic import earlier so the
      // canned events array can be built before the
      // driver wires its event handler.)
      // R292 + R293 follow-up: choose the driver by what's
      // available. The store was historically hard-coded to
      // `MockSsdDriver` (R288/R289 left a "TauriSsdDriver
      // later is a one-line change" comment), but R292 made
      // the UI rely on per-phase accept/revise buttons that
      // only make sense when the daemon is actually wired
      // up. Without that wire the chips flip idle → done in
      // ~4 s with no chance for the user to interact — i.e.
      // exactly the "一闪而过 什么等待确认 全都是不存在的"
      // regression the user hit.
      //
      // Decision rule:
      //   - daemonInfo.jarPath present → spawn the JVM via
      //     TauriSsdDriver (`--interactive` so each phase
      //     pauses for an accept/revise command);
      //   - otherwise (dev mode, no daemon) → fall back to
      //     MockSsdDriver so the UI still works.
      //
      // The dynamic import is done at the top of this
      // function (see above); here we just pick.
      const jarPath = get().daemonInfo?.jarPath;
      const cwd = get().cwd;
      // R293 follow-up: surface the driver choice to the JS
      // console + the desktop log so a user who reports
      // "phase X flashed past" can tell whether they're
      // hitting the canned mock (dev fallback) or a real
      // subprocess. `--auto` is now the default until the
      // Tauri shell 2.x stdin pipe is confirmed on Windows.
      try {
        // eslint-disable-next-line no-console
        console.log('[R293-sdd] driver choice:',
          jarPath && cwd ? 'TauriSsdDriver' : 'MockSsdDriver (dev fallback)',
          'jarPath=', jarPath, 'cwd=', cwd);
      } catch {}
      let driver: import('../components/ssd/driver').SsdDriver;
      if (jarPath && cwd) {
        // Real daemon subprocess. `--interactive` is mandatory
        // here — without it the daemon runs in text-TTY mode
        // and System.in.readLine() blocks forever waiting for
        // keyboard input that never arrives, which would hang
        // the whole pipeline.
        driver = new TauriSsdDriver({
          jarPath,
          feature: featureSlug,
          intent,
          cwd,
        });
      } else {
        // Dev fallback. Without it, removing the in-process mock
        // would break UI development (you can't test the
        // phase chips without standing up the daemon).
        driver = new MockSsdDriver(events, undefined, 280);
      }
      ssdDriverRef.driver = driver;

      // Wire the event handler. Closes over `set` so
      // each event flips the right phase chip.
      ssdDriverRef.unsubscribe = driver.onEvent((ev) => {
        switch (ev.kind) {
          case 'phase-list':
            set((s) => ({
              ssdPhases: ev.phases.map((p) => {
                const existing = s.ssdPhases.find((x) => x.id === p.id);
                return existing ?? { id: p.id, title: p.title, state: 'idle' };
              }),
            }));
            break;
          case 'phase-start':
            set((s) => ({
              ssdPhases: s.ssdPhases.map((p) => p.id === ev.phase
                ? { ...p, state: 'running' }
                : p),
            }));
            break;
          case 'phase-draft':
            // Push the draft body to the chat list as
            // a `system` message so MessageList's
            // existing render path picks it up.
            // Assistant messages aren't rendered
            // standalone (they're inlined into the
            // engine's preamble/subtask steps), so we
            // use `system` with a `kind: 'ssd-draft'`
            // metadata marker. The chip moves to
            // 'pending-accept' to signal "ready for
            // you to accept/revise".
            set((s) => {
              const draftMsgId = newId('system');
              return {
                ssdPhases: s.ssdPhases.map((p) => p.id === ev.phase
                  ? { ...p, state: 'pending-accept', preview: ev.preview, path: ev.path }
                  : p),
                messages: [...s.messages, {
                  id: draftMsgId,
                  role: 'system' as const,
                  content: `📐 ${ev.phase} 阶段草案\n\n文件: \`${ev.path}\`\n\n\`\`\`\n${ev.preview}\n\`\`\`\n\n(等待确认 → 进入下一阶段)`,
                  timestamp: Date.now(),
                  metadata: { kind: 'ssd-draft', phase: ev.phase, path: ev.path },
                }],
              };
            });
            break;
          case 'phase-accepted':
            set((s) => ({
              ssdPhases: s.ssdPhases.map((p) => p.id === ev.phase
                ? { ...p, state: 'done', preview: undefined }
                : p),
            }));
            break;
          case 'phase-skipped':
            set((s) => ({
              ssdPhases: s.ssdPhases.map((p) => p.id === ev.phase
                ? { ...p, state: 'skipped' }
                : p),
            }));
            break;
          case 'phase-error':
            set((s) => ({
              ssdPhases: s.ssdPhases.map((p) => p.id === ev.phase
                ? { ...p, state: 'failed' }
                : p),
            }));
            break;
          case 'clarify-question':
            // R292: the runner surfaced a question about
            // an underspecified area in spec.md (or another
            // artefact). Push it to the chat list as a
            // system message so the user sees the question
            // inline, and move the chip to clarify-pending
            // so the bar reflects "waiting on user".
            set((s) => {
              const msgId = newId('system');
              return {
                ssdPhases: s.ssdPhases.map((p) => p.id === 'clarify'
                  ? { ...p, state: 'clarify-pending', preview: `${ev.header}\n\n${ev.question}` }
                  : p),
                messages: [...s.messages, {
                  id: msgId,
                  role: 'system' as const,
                  content: `📐 **${ev.header}** (${ev.id})\n\n${ev.question}\n\n(在对话框里输入回答后点"发送回答"即可。回复 {\`action\`:"clarify-answer", \`id\`:"${ev.id}", \`answer\`:"..."})`,
                  timestamp: Date.now(),
                  metadata: { kind: 'sdd-clarify-question', id: ev.id, phase: 'clarify' },
                }],
              };
            });
            break;
          case 'analysis':
            // R292: cross-artifact review report. Render
            // it as a system banner; do not gate the run
            // on its findings (Spec Kit's analyze is
            // advisory, not a hard gate).
            set((s) => {
              const msgId = newId('system');
              return {
                messages: [...s.messages, {
                  id: msgId,
                  role: 'system' as const,
                  content: `🔍 **一致性分析报告**\n\n\`\`\`\n${ev.report}\n\`\`\``,
                  timestamp: Date.now(),
                  metadata: { kind: 'sdd-analysis', phase: ev.phase },
                }],
              };
            });
            break;
          case 'converge-check':
            // R292: the model reviewed the implementation
            // and emitted a JSON-ish convergence report.
            // Surface as a system message + flip the
            // converge chip to converge-pending so the
            // bar shows "reviewing…" while the user
            // decides iterate / skip / quit.
            set((s) => ({
              ssdPhases: s.ssdPhases.map((p) => p.id === 'converge'
                ? { ...p, state: 'converge-pending', preview: ev.report }
                : p),
              messages: [...s.messages, {
                id: newId('system'),
                role: 'system' as const,
                content: `🔁 **收敛验证** (迭代 #${ev.iteration}, converged=${ev.converged})\n\n\`\`\`\n${ev.report}\n\`\`\`\n\n(${ev.converged ? '已收敛 — 进入 complete' : '未收敛 — 可发反馈或跳过'})`,
                timestamp: Date.now(),
                metadata: { kind: 'sdd-converge-check', iteration: ev.iteration, converged: ev.converged },
              }],
            }));
            break;
          case 'complete':
            // Run finished cleanly. Tear down driver,
            // mark any still-idle phases as skipped,
            // and turn the toggle off so the bar
            // collapses.
            set((s) => ({
              ssdActive: false,
              ssdPhases: s.ssdPhases.map((p) => p.state === 'idle' || p.state === 'running' || p.state === 'pending-accept'
                ? { ...p, state: 'skipped' }
                : p),
              sddEnabled: false,
            }));
            try { ssdDriverRef.unsubscribe?.(); ssdDriverRef.driver?.stop(); } catch {}
            ssdDriverRef = { driver: null, unsubscribe: null };
            break;
          case 'abort':
            set({ ssdActive: false });
            try { ssdDriverRef.unsubscribe?.(); ssdDriverRef.driver?.stop(); } catch {}
            ssdDriverRef = { driver: null, unsubscribe: null };
            break;
          // 'log' and any unknown events are ignored —
          // the renderer doesn't have a place to show
          // them yet and surfacing them in chat would
          // clutter the message list.
        }
      });

      driver.start();
    },

    // R289: tear down the active SSD run. No-op when
    // no run is in flight. Called by setSddEnabled
    // when the toggle flips off, and by the close
    // button on SddPhaseBar.
    stopSsdFlow: () => {
      if (!ssdDriverRef.driver) return;
      try { ssdDriverRef.unsubscribe?.(); ssdDriverRef.driver.stop(); } catch {}
      ssdDriverRef = { driver: null, unsubscribe: null };
      set({ ssdActive: false });
    },

    // R289: send an inbound command to the running
    // driver. Accept / skip / revise are routed to
    // the mock driver (which records them in its
    // commands[] queue for test inspection); the
    // MVP canned sequence auto-advances on its own
    // without waiting for user input, so these are
    // mostly test hooks. Quit immediately aborts
    // and tears down.
    sendSsdCommand: (
      cmd:
        | { action: 'accept' }
        | { action: 'revise'; text: string }
        | { action: 'skip' }
        | { action: 'quit' }
        | { action: 'clarify-answer'; id: string; answer: string }
        | { action: 'converge-iterate'; text: string },
    ) => {
      const driver = ssdDriverRef.driver;
      if (!driver) return;
      driver.sendCommand(cmd);
      if (cmd.action === 'quit') {
        try { ssdDriverRef.unsubscribe?.(); driver.stop(); } catch {}
        ssdDriverRef = { driver: null, unsubscribe: null };
        set({ ssdActive: false, sddEnabled: false });
      }
    },

    selectTask: (taskId: string | null) => set({ currentTaskId: taskId }),

    cancelTask: async (taskId: string) => {
      try { await rpc.cancel(); } catch {}
      // map the legacy 'cancelled' status
      // to the new TaskRegistry status set
      // ('killed' for terminal cancellation). The
      // TaskList CSS still has a .task-status-cancelled
      // selector (kept for the legacy TaskList
      // rendering); we use 'killed' as the canonical
      // value going forward.
      set((s) => ({
        tasks: s.tasks.map((t) => t.id === taskId ? { ...t, status: 'killed' as const, endedAtMs: Date.now() } : t),
        isStreaming: false,
      }));
    },

    switchProject: async (projectId: string) => {
      try {
        await rpc.switchProject(projectId);
        set({ currentProjectId: projectId });
        await Promise.all([get().refreshSessions(), get().refreshTasks()]);
      } catch (e: any) { console.error('switchProject failed', e); }
    },

    respondPermission: async (requestId: string, allow: boolean) => {
      // if the TUI is connected to
      // the supervisor (activeChildId is set),
      // forward the response via the
      // supervisor's proxyNotification path
      // rather than calling the child directly.
      // The supervisor POSTs a JSON-RPC
      // notification (no id field) to the
      // child's /jsonrpc; the child then
      // processes it as a fire-and-forget
      // permission_response.
      const childId = get().activeChildId;
      if (childId) {
        await get().proxyNotification(childId, 'permissionResponse', {
          requestId,
          allow,
        });
      } else {
        try { await rpc.permissionResponse(requestId, allow); } catch {}
      }
      set((s) => ({ pendingPermissions: s.pendingPermissions.filter((p) => p.requestId !== requestId) }));
    },
    // set the active child id. Pass
    // null when the TUI is connected
    // directly to the daemon (the default).
    // The child id is shown in the header
    // so the user knows which daemon is
    // being driven; the respondPermission /
    // acknowledgeLoop actions route via
    // the supervisor when this is non-null.
    setActiveChildId: (id: string | null) => {
      set({ activeChildId: id });
    },

    // mark a tool as session-allowed. The actual backend
    // plumbing (sending the always-allow list to the engine so it
    // auto-approves future requests) is a follow-up; for now this
    // just stores the choice locally and auto-approves any pending
    // requests for the same tool.
    allowToolAlways: (toolName: string) => {
      set((s) => {
        const next = new Set(s.alwaysAllowedTools);
        next.add(toolName);
        // Auto-approve any pending requests for this tool.
        const stillPending = s.pendingPermissions.filter((p) => p.tool !== toolName);
        const toAutoApprove = s.pendingPermissions.filter((p) => p.tool === toolName);
        for (const p of toAutoApprove) {
          rpc.permissionResponse(p.requestId, true).catch(() => {});
        }
        return { alwaysAllowedTools: next, pendingPermissions: stillPending };
      });
    },

    pickCwd: async () => {
      try {
        const selected = await openDialog({ directory: true, multiple: false, title: 'Select working directory' });
        if (typeof selected === 'string') {
          await get().setCwd(selected);
        }
      } catch (e) {
        console.error('pickCwd failed', e);
      }
    },

    setCwd: async (path: string) => {
      // switching cwd now goes through the Rust
      // `set_cwd` Tauri command, which does the full
      // pre_warm_daemon → swap_to_pre_warm → createSession
      // dance. Previously, this action called
      // `rpc.createSession({ cwd: path })` directly on the
      // WS — but the WS was bound to the current primary
      // daemon, which had a fixed --sessions-dir of
      // <oldCwd>/.aethercode/sessions. So the new
      // transcript got written to the OLD cwd's sessions
      // directory and the engine was still the OLD
      // daemon's JVM (with its own cwd, model context,
      // and file history). The user picked abc_2 but
      // tool calls still ran against abc_1.
      //
      // R199 makes cwd switch = daemon switch: a fresh
      // JVM is spawned rooted at the new cwd, promoted
      // to primary, and a clean session is minted on it.
      // The OLD session is preserved in the OLD daemon's
      // session store, accessible via SessionPicker
      // (Ctrl/Cmd+Shift+P) as long as the user re-opens
      // the OLD cwd.
      //
      // If the new path equals the current cwd, this is a
      // no-op (we don't churn the session id for a click
      // that ends up selecting the same folder).
      const curCwd = (get().cwd ?? '').replace(/[\\/]+$/, '');
      const newCwd = (path ?? '').replace(/[\\/]+$/, '');
      if (curCwd && curCwd.toLowerCase() === newCwd.toLowerCase()) {
        // Same cwd — nothing to do. The pickCwd dialog
        // dismissed; the user is already on the right
        // folder.
        return;
      }
      set({ cwdSwitchInProgress: true, cwdSwitchTarget: path, transitionPhase: 'killing-old' });
      try {
        // 1. Hand off to Rust. The Rust side spawns a
        //    fresh daemon for the new cwd, swaps it to
        //    primary, and mints a session on it. The
        //    returned sessionId is the new active session.
        //    The WS is re-opened by the swap, so any
        //    post-swap rpc_call (refreshSessions, etc.)
        //    goes to the new daemon.
        const r = await rpc.setCwd(path);
        const newId = r.sessionId;
        // 2. Update local state. When `swapped` is true
        //    the daemon is fresh and we re-init the WS
        //    listeners. When false, the slot was just
        //    updated and ensure_daemon will spawn one
        //    rooted at the new cwd on the next call.
        if (r.swapped) {
          set({
            currentSessionId: newId,
            cwd: r.cwd || path,
            messages: [],
            currentInput: '',
            transitionPhase: 'health-check',
            // clear OLD sessions when swapping
            // daemons. Previously the code did
            //   sessions: [...get().sessions, newId]
            // — appending the new session to the OLD
            // daemon's list. The OLD daemon was killed
            // by swap_to_pre_warm, so those sessions
            // are unreachable (they live in the OLD
            // cwd's sessions.db). The user reported
            // "selecting abc_4 became the abc_3 operation again" — they were
            // seeing the OLD daemon's stale sessions
            // mixed in with the new one. Clear them
            // here; refreshSessions() below will
            // repopulate from the new daemon.
            sessions: [
              { id: newId!, name: undefined, lastUsedAt: Date.now(), messageCount: 0, cwd: r.cwd || path },
            ],
            // clear cached metadata tied to the
            // OLD daemon. The user said cwd-switch was
            // sometimes operating on the OLD cwd's
            // state — these caches were the smoking gun.
            memory: { user: { entries: [], count: 0 }, project: { entries: [], count: 0, cwd: null }, session: { entries: [], count: 0, sessionId: null } },
            memoryStats: null,
            lastMemoryRefreshMs: 0,
            // also drop stale project/session/task
            // lists so the panels don't show OLD cwd's
            // tasks or projects for a moment.
            tasks: [],
            projects: [],
            lastSessionSummary: null,
            daemonInfo: null,
            // after swap, the WS is a fresh one.
            // We need the renderer to re-establish its
            // listeners and refetch metadata. Setting
            // daemonInfo=null triggers the WS re-init
            // path in initialize().
          });
        } else {
          // No swap happened (no daemon was up yet).
          // Just update the cwd slot; ensure_daemon
          // picks it up on the next initialize().
          set({
            cwd: r.cwd || path,
            // also drop cached daemon state in the
            // no-swap path. previously the cached sessions
            // / tasks / memory lingered across "switch
            // cwd before daemon is up" — same root cause
            // as the swap case.
            sessions: [],
            memory: { user: { entries: [], count: 0 }, project: { entries: [], count: 0, cwd: null }, session: { entries: [], count: 0, sessionId: null } },
            memoryStats: null,
            lastMemoryRefreshMs: 0,
            tasks: [],
            projects: [],
            transitionPhase: 'spawning-jvm',
          });
          // Trigger ensure_daemon so the next call
          // spawns a fresh daemon rooted at the new cwd.
          void get().initialize().catch(() => {});
          return;
        }
        // 3. Light refresh: re-fetch sessions / tools /
        //    providers / memory so the picker reflects
        //    the new per-cwd metadata. The WS was re-opened
        //    during the swap.
        void get().refreshSessions().catch(() => {});
        void get().refreshTools().catch(() => {});
        void get().refreshProviders().catch(() => {});
        // refresh all 3 memory scopes. legacy
        // only PROJECT was refreshed (via NOTIFY_CWD_CHANGED
        // event in line 2617). USER and SESSION caches
        // were left untouched, so switching cwd from
        // abc_3 to abc_4 left the abc_3 user memory
        // visible until the next event fired. The user
        // explicitly said "user-level memory records the user's preferences,
        // project-level memory records project info, memory and session
        // must not be mixed up" — we now refresh all three on
        // every cwd switch.
        void get().refreshMemory('USER').catch(() => {});
        void get().refreshMemory('PROJECT').catch(() => {});
        void get().refreshMemory('SESSION').catch(() => {});
        // 4. re-apply the user's persisted
        //    permission-mode preference. A new daemon
        //    boots with the engine's DEFAULT mode (the
        //    daemon doesn't remember the OLD daemon's
        //    user-set mode), so without this re-apply
        //    the renderer would briefly show
        //    Ask first (the new daemon's default), then
        //    on the next refreshEngineState the dropdown
        //    would land on whatever the daemon reported.
        // The user explicitly picked always run and the
        // new daemon shouldn't silently flip them back
        // to the default.
        const prefsForPerm = readEnginePrefs();
        if (prefsForPerm.permissionMode && prefsForPerm.permissionMode !== get().permissionMode) {
          void get().setPermissionMode(prefsForPerm.permissionMode).catch(() => {});
        }
        // R204: re-apply the user's persisted permission mode after the daemon swap
        // 5. Pre-warm a sibling for the next switch.
        void get().preWarmCwd(suggestSibling(path)).catch(() => {});
      } catch (e: any) {
        console.error('setCwd failed', e);
        set((s) => ({
          messages: [...s.messages, {
            id: newId('system'), role: 'system' as const,
            content: `[setCwd failed] ${e?.message ?? String(e)}`,
            timestamp: Date.now(), isError: true,
          }],
        }));
      } finally {
        set({ cwdSwitchInProgress: false, cwdSwitchTarget: null, transitionPhase: 'idle' });
      }
    },


    setActivity: (a) => set({ currentActivity: a }),
    clearActivity: () => set({ currentActivity: null }),

    // explicitly force a fresh daemon. The legacy
    // setCwd did this implicitly (kill + respawn on every
    // cwd change). later the daemon's bindSessionCwd
    // handles cwd changes in-place, so this action is
    // reserved for the rare "the daemon is wedged and I
    // want a clean restart" case — exposed in the UI as
    // Settings → Advanced → "Restart daemon".
    resetDaemon: async () => {
      set({
        connectionState: 'reconnecting', isConnected: false, reconnectAttempts: 0,
        cwdSwitchInProgress: true, transitionPhase: 'killing-old',
      });
      try {
        try { await invoke('discard_pre_warm'); } catch {}
        set({ preWarm: null });
        const cur = get().cwd;
        const real = await rpc.setCwd(cur ?? '.');
        set({ cwd: real.cwd, daemonInfo: null, transitionPhase: 'spawning-jvm' });
        await get().initialize();
      } catch (e: any) {
        console.error('resetDaemon failed', e);
      } finally {
        set({ cwdSwitchInProgress: false, transitionPhase: 'idle' });
      }
    },

    preWarmCwd: async (path: string) => {
      if (!path) return false;
      const cur = get().preWarm;
      if (cur && await samePath(cur.cwd, path)) return false; // already warm
      // Drop the previous pre-warm if any.
      if (cur) { try { await invoke('discard_pre_warm'); } catch {} }
      try {
        const info = await invoke<PreWarmInfo>('pre_warm_daemon', { path });
        set({ preWarm: { cwd: path, info, since: Date.now() } });
        console.log(`[store] pre-warmed daemon for ${path} (port=${info.port})`);
        return true;
      } catch (e) {
        console.warn(`[store] pre_warm_daemon failed for ${path}:`, e);
        set({ preWarm: null });
        return false;
      }
    },

    discardPreWarm: async () => {
      try { await invoke('discard_pre_warm'); } catch {}
      set({ preWarm: null });
    },

    dismissAwaitingUserDecision: () => set({ awaitingUserDecision: null }),

    bumpCurrentQueryStep: (toolCount) => set((s) => (
      s.currentQuery
        ? { currentQuery: { ...s.currentQuery, stepCount: s.currentQuery.stepCount + 1, toolCount: s.currentQuery.toolCount + (toolCount ?? 0) } }
        : {}
    )),
    clearCurrentQuery: () => set({ currentQuery: null, subTasks: [], currentSubTaskId: null, currentStepId: null, currentTodos: [], daemonTodos: [] }),

    // R83 Issue #6: step manipulation actions. Most of the
    // step state is updated inline in the stream_event handler
    // above (so the renderer sees a single set() per event);
    // these actions are exposed for tests and for any future
    // back-fill paths.
    appendStepText: (text) => set((s) => (
      s.currentStepId
        ? { steps: s.steps.map((st) => st.id === s.currentStepId ? { ...st, text: st.text + text } : st) }
        : {}
    )),
    pushStepTool: (tool) => set((s) => {
      const cur = s.steps.find((st) => st.id === s.currentStepId);
      if (!cur) return {};
      const cat = toolCategory(tool.name);
      const counters = cat ? { ...cur.counters, [cat]: (cur.counters[cat] ?? 0) + 1 } : cur.counters;
      return {
        steps: s.steps.map((st) => st.id === s.currentStepId
          ? { ...st, toolEvents: [...st.toolEvents, tool], counters }
          : st),
      };
    }),
    completeStepTool: (id, output, isError) => set((s) => (
      s.currentStepId
        ? { steps: s.steps.map((st) => st.id === s.currentStepId
            ? { ...st, toolEvents: st.toolEvents.map((te) => te.id === id ? { ...te, output, isError } : te) }
            : st) }
        : {}
    )),
    // stream-append output to a tool event. We search
    // every step (not just the current one) because a fast
    // tool can finish and a new tool start before the last
    // streamed line lands. The match is by tool id, which is
    // stable across the whole run. We also bump lastChunkTs
    // so the staleness watcher (PreparingCard) doesn't tint
    // the card orange mid-stream.
    appendToolOutput: (id, text) => set((s) => {
      if (!text) return { lastChunkTs: Date.now() };
      let touched = false;
      const steps = s.steps.map((st) => {
        let mut = false;
        const toolEvents = st.toolEvents.map((te) => {
          if (te.id !== id) return te;
          mut = true;
          // First chunk: initialise output. Later chunks: append
          // with a newline if the previous chunk didn't end
          // with one (BashTool emits one line at a time without
          // a trailing \n, so this preserves the line-per-line
          // shape).
          if (te.output == null) return { ...te, output: text };
          const sep = te.output.endsWith('\n') ? '' : '\n';
          return { ...te, output: te.output + sep + text };
        });
        if (mut) { touched = true; return { ...st, toolEvents }; }
        return st;
      });
      // Even if no tool matched, we still bump lastChunkTs so
      // a stale-looking "ready" indicator resets.
      return touched
        ? { steps, lastChunkTs: Date.now() }
        : { lastChunkTs: Date.now() };
    }),
    finishCurrentStep: (stopReason) => set((s) => {
      const cur = s.currentStepId;
      if (!cur) return {};
      return {
        steps: s.steps.map((st) => st.id === cur
          ? { ...st, done: true, endedAt: Date.now(), stopReason }
          : st),
        currentStepId: null,
      };
    }),
    bumpStepThink: () => set((s) => (
      s.currentStepId
        ? { steps: s.steps.map((st) => st.id === s.currentStepId
            ? { ...st, counters: { ...st.counters, thinks: st.counters.thinks + 1 } }
            : st) }
        : {}
    )),
    toggleStepExpanded: (id) => set((s) => {
      const cur = s.expandedStepIds[id];
      return { expandedStepIds: { ...s.expandedStepIds, [id]: cur === undefined ? false : !cur } };
    }),

    // open the step detail modal for a finished
    // (or currently running) step. The pill click in the
    // WorkflowProgressBar calls this; the StepDetailModal
    // reads selectedStepDetail + runningWorkflow.stepEvents
    // to render the history.
    openStepDetail: (runId, stepId) => set({ selectedStepDetail: { runId, stepId } }),
    closeStepDetail: () => set({ selectedStepDetail: null }),

    /**
     * Close a sub-task. The UI uses the closed status + summary
     * to render the SubTaskCard footer (e.g. "✓ completed" or "✗ failed"
     * with the model-written one-liner). Pass-through; the
     * sub_task_end handler in the stream_event switch calls
     * this directly.
     */
    closeSubTask: (id, status, summary) => set((s) => {
      const subTasks = s.subTasks.map((st) =>
        st.id === id
          ? { ...st, status: status as ChatSubTask['status'], summary, endedAt: Date.now() }
          : st,
      );
      const updates: Partial<AppState> = { subTasks };
      if (s.currentSubTaskId === id) updates.currentSubTaskId = null;
      return updates;
    }),
    /**
     * Drop all sub-task state. Called on a fresh user query
     * (via clearCurrentQuery) and on cancel.
     */
    clearSubTasks: () => set({ subTasks: [], currentSubTaskId: null }),

    // move the sub-task at `fromIdx` to position `toIdx`.
    // Clamps out-of-range indices so a drag past the end is a
    // no-op rather than a crash. `currentSubTaskId` is
    // preserved (the id stays the same; only the array order
    // changes). We don't touch `expandedStepIds` — the user's
    // collapse/expand choices are keyed by id, so reordering
    // doesn't disturb them.
    reorderSubTasks: (fromIdx, toIdx) => {
      const { subTasks } = get();
      if (fromIdx < 0 || fromIdx >= subTasks.length) return;
      const clamped = Math.max(0, Math.min(toIdx, subTasks.length - 1));
      if (fromIdx === clamped) return;
      const next = [...subTasks];
      const [moved] = next.splice(fromIdx, 1);
      next.splice(clamped, 0, moved);
      set({ subTasks: next });
    },

    // reorder the steps belonging to a single sub-task.
    // The global `steps` array is chronological across all
    // sub-tasks; we don't change the global ordering, only
    // the relative order of the sub-task's slice. The slice
    // is replaced in place; everything outside stays put.
    // Clamps out-of-range. `expandedStepIds` is keyed by id
    // so the user's collapse/expand choices survive the
    // reorder.
    reorderSteps: (subTaskId, fromIdx, toIdx) => {
      const { steps } = get();
      const subSteps = steps.filter((s) => s.subTaskId === subTaskId);
      if (fromIdx < 0 || fromIdx >= subSteps.length) return;
      const clamped = Math.max(0, Math.min(toIdx, subSteps.length - 1));
      if (fromIdx === clamped) return;
      const reordered = [...subSteps];
      const [moved] = reordered.splice(fromIdx, 1);
      reordered.splice(clamped, 0, moved);
      // Splice the reordered slice back into the global
      // `steps` array. We walk in order so the first match
      // gets the first reordered entry, the second match
      // gets the second, etc.
      const next = [...steps];
      let r = 0;
      for (let i = 0; i < next.length; i++) {
        if (next[i].subTaskId === subTaskId) {
          next[i] = reordered[r++];
        }
      }
      set({ steps: next });
    },

    // user clicked "continue" in the LoopGuardBanner. We
    // call the loopAck RPC (which resets the engine's loop
    // detector tier to 0) and clear the local loopWarn
    // state. Errors are swallowed (the RPC is idempotent
    // and the banner can self-dismiss even if the call
    // fails). The local `messages[]` is intentionally not
    // touched here — the engine will continue to emit
    // events for the current run, and a new warn will
    // show in the transcript.
    acknowledgeLoop: async (kind) => {
      const cur = get().loopWarn;
      // when the TUI is connected
      // to the supervisor, forward the
      // loopAck via proxyNotification. The
      // child daemon's engine resets its
      // detector tier; the supervisor only
      // relays the notification.
      const childId = get().activeChildId;
      try {
        if (childId) {
          await get().proxyNotification(childId, 'loopAck', {
            runId: cur?.runId,
            kind: (kind as any) ?? 'all',
          });
        } else {
          await rpc.loopAck({
            runId: cur?.runId,
            kind: (kind as any) ?? 'all',
          });
        }
      } catch (e) {
        console.warn('loopAck RPC failed:', e);
      }
      set({ loopWarn: null });
    },

    // user clicked "stop" in the LoopGuardBanner (or
    // the 8s auto-dismiss fired). We cancel the in-flight
    // query (if any) and clear the local loopWarn state.
    // The cancelQuery() call is a no-op when no query is
    // running, so this is safe to fire from the auto-dismiss
    // path even though by definition a warn is only shown
    // while a query is in flight.
    dismissLoopWarn: async (reason) => {
      set({ loopWarn: null });
      if (reason === 'user-cancel') {
        try { await get().cancelQuery(); }
        catch (e) { console.warn('cancelQuery from loop guard failed:', e); }
      }
    },

    // workflow picker. Fetches the cwd's workflow
    // directory and caches the parsed metadata. Cheap
    // (a directory scan + small parse) so we don't
    // worry about re-renders. Called on app start and
    // when the user opens the picker; the response is
    // stored in `availableWorkflows` and the picker
    // reads from there.
    refreshWorkflows: async () => {
      try {
        const r = await rpc.listWorkflows();
        set({ availableWorkflows: r.workflows ?? [] });
      } catch (e) {
        // Workflows are an optional feature; failing the
        // call should not break the rest of the app. The
        // picker just shows an empty list.
        console.warn('refreshWorkflows failed:', e);
        set({ availableWorkflows: [] });
      }
    },

    // re-fetch the tool pool. Two RPCs in parallel —
    // listTools gives name + description, listToolActions
    // gives the per-tool defaultAction (ALLOW/ASK/DENY).
    // Both are best-effort: a failure on one doesn't block
    // the other, and the store keeps its prior value rather
    // than blanking on a transient blip. The two `try`s
    // mirror the legacy-A initialize() behaviour where
    // each call had its own catch.
    refreshTools: async () => {
      try {
        const [toolsResp, actionsResp] = await Promise.all([
          rpc.listTools().catch(() => null),
          rpc.listToolActions().catch(() => null),
        ]);
        // Only update the fields we got a non-null response
        // for. This way a half-success (one RPC working, the
        // other failing) still improves the store, and a
        // double-failure leaves the existing values intact
        // so the user doesn't see a flash of "0 tools".
        const patch: { tools?: ToolInfo[]; toolActions?: import('../lib/methods').ToolActionInfo[] } = {};
        if (toolsResp && Array.isArray((toolsResp as { tools?: unknown[] }).tools)) {
          patch.tools = (toolsResp as { tools: ToolInfo[] }).tools;
        }
        if (actionsResp && Array.isArray((actionsResp as { tools?: unknown[] }).tools)) {
          patch.toolActions = (actionsResp as { tools: import('../lib/methods').ToolActionInfo[] }).tools;
        }
        if (Object.keys(patch).length > 0) {
          // stamp the last-refresh time so the
          // ToolsPanel's empty state can show "last fetched: 5s ago"
          // and the user can tell whether a refresh is stale.
          set({ ...patch, toolsRefreshedAt: Date.now() } as Partial<AppState>);
        }
      } catch (e) {
        // Catastrophic failure (e.g. Promise.all threw before
        // either .catch could swallow). Log and keep prior
        // state.
        console.warn('refreshTools failed:', e);
      }
    },

    // re-fetch the engine state snapshot. The prior round
    // pattern (best-effort fetch + preserve-on-failure) is
    // applied to a single RPC — getState returns the
    // canonical model / mode / contextWindow / toolCount /
    // loop detector thresholds that drive half the
    // renderer's UI. A 15 s periodic timer (see initialize)
    // self-heals daemon restarts; the manual entry points
    // are switchProvider (after a model switch) and
    // setPermissionMode (after a mode change), which both
    // call refreshEngineState() right after the RPC.
    refreshEngineState: async () => {
      try {
        const r = await rpc.getState().catch(() => null);
        if (!r) return;
        // the daemon's getState is a small payload
        // (~20 fields); patch the whole engineState object
        // rather than merging field-by-field. The
        // engineStateRefreshedAt stamp lets any future
        // "stale state" indicator (R116 diagnostic panel)
        // tell the user how old the snapshot is.
        set({ engineState: r as EngineState, engineStateRefreshedAt: Date.now(),
              // keep the top-level `model` mirror in
              // sync with the daemon's canonical model.
              // Previously the engineState was updated but
              // `state.model` (which MessageInput's <select>
              // uses) was not, so the dropdown showed a
              // stale value even when the daemon had
              // switched models underneath us.
              model: r.model ?? get().model,
              // the top-level `permissionMode` is a
              // UI tier ('ask' / 'smart' / 'bypass'), not
              // the canonical enum. Map the daemon's
              // canonical value to a UI tier for the
              // dropdown. mapDaemonToUiPermission falls
              // through to the raw enum for advanced
              // modes (ACCEPT_TASK, PLAN,
              // AUTO_READ_ONLY) so the dropdown shows no
              // matching option visually but the
              // StatusBar / banner show the raw enum so
              // the user knows the daemon is in an
              // advanced mode.
              permissionMode: r.permissionMode
                ? mapDaemonToUiPermission(r.permissionMode)
                : get().permissionMode,
        } as Partial<AppState>);
      } catch (e) {
        // Previously, getState failures during initialize()
        // silently left engineState as null forever. The
        // the prior round lesson applied: log and keep the prior
        // snapshot. The next tick (or the next manual
        // trigger from setModel / setPermissionMode) will
        // retry.
        console.warn('refreshEngineState failed:', e);
      }
    },

    // fetch the daemon's JSON-RPC method list
    // (the same list /api/methods returns to the
    // diagnostic panel) and cache it in the store.
    // Lazy: the user pays the fetch cost only when
    // they open the RPC command palette. The HTTP
    // endpoint is exposed by the daemon at
    // {daemonInfo.httpUrl}/api/methods — CORS is
    // already wide-open on the daemon side
    // (localhost-to-localhost is the only caller in
    // practice). The list is small (~50 strings);
    // no pagination / streaming needed.
    loadRpcMethods: async () => {
      // Skip if we already have a list (the user
      // re-opened the palette). Re-fetches would
      // be wasted bandwidth; the list only changes
      // on daemon restart, which already triggers
      // initialize() — and a future R-N can clear
      // rpcMethods on reconnect if we ever need
      // to.
      if (get().rpcMethods.length > 0) return;
      const info = get().daemonInfo;
      if (!info?.httpUrl) return; // not connected yet
      try {
        const r = await fetch(`${info.httpUrl}/api/methods`, {
          method: 'GET',
          headers: { Accept: 'application/json' },
        });
        if (!r.ok) {
          console.warn('loadRpcMethods: HTTP', r.status);
          return;
        }
        // the daemon's /api/methods now
        // returns a tagged list
        //   { methods: [ {name, tags}, ... ],
        //     tags: ["read", "write", ...] }
        // instead of the legacy flat-name list.
        // We extract BOTH the bare names (for the
        // R121 palette's "all methods" filter) and
        // the tagged descriptors (for the R124
        // tag-chip filter). Older daemons that
        // still return the flat list (string[])
        // are tolerated: the names flow into
        // rpcMethods, rpcMethodInfos stays empty,
        // the palette shows no tag chips (the
        // R121 baseline).
        const body = await r.json() as { methods?: unknown; tags?: unknown };
        if (!Array.isArray(body.methods)) return;
        const tagged: RpcMethodInfo[] = [];
        const names: string[] = [];
        for (const m of body.methods) {
          if (typeof m === 'string') {
            // legacy flat-name shape. Accept it
            // for backward compat — the palette's
            // R124 tag filter is just disabled.
            names.push(m);
          } else if (m && typeof m === 'object'
              && typeof (m as { name?: unknown }).name === 'string') {
            const name = (m as { name: string }).name;
            const rawTags = (m as { tags?: unknown }).tags;
            const tags = Array.isArray(rawTags)
              ? rawTags.filter((t): t is string => typeof t === 'string')
              : [];
            names.push(name);
            tagged.push({ name, tags });
          }
        }
        // Defensive: dedupe + sort (the daemon
        // already returns sorted, but a future
        // R-N might not).
        const uniqueNames = [...new Set(names)].sort();
        // Dedupe tagged too — same RPC name from
        // two sources (shouldn't happen, but
        // defensive). Keep the first occurrence.
        const seen = new Set<string>();
        const uniqueTagged: RpcMethodInfo[] = [];
        for (const t of tagged) {
          if (seen.has(t.name)) continue;
          seen.add(t.name);
          uniqueTagged.push(t);
        }
        uniqueTagged.sort((a, b) => a.name.localeCompare(b.name));
        set({ rpcMethods: uniqueNames, rpcMethodInfos: uniqueTagged });
      } catch (e) {
        // the prior round lesson: a transient HTTP blip
        // must not blank the list (or surface
        // an error to the user). Log and keep
        // whatever was there. The next open of
        // the palette retries.
        console.warn('loadRpcMethods failed:', e);
      }
    },

    // push the user's slider values to the
    // daemon. The Settings panel calls this on every
    // debounced change (500 ms) so the user can see
    // the detector become more/less aggressive in
    // real time without a "save" button. The
    // optimistic update mirrors setModel's pattern;
    // the post-RPC refreshEngineState() reconciles
    // any other state the daemon adjusted (the
    // detector rebuild is local so this is mostly
    // a no-op, but the call is cheap and keeps the
    // R118 invariant).
    setLoopDetectorThresholds: async (opts) => {
      try {
        const r = await rpc.setLoopDetectorThresholds(opts);
        if (!r.ok) {
          // Return the daemon's reason so the UI can
          // surface it inline. The store keeps the
          // prior loop fields (no optimistic update
          // on the failure path).
          return { ok: false, error: r.error };
        }
        // Optimistic engineState update so the
        // StatusBar / StatusBar's loop badge can
        // react before the next getState tick lands.
        set((s) => ({
          engineState: s.engineState
            ? {
                ...s.engineState,
                loopWindow: r.window ?? opts.window,
                loopThreshold: r.threshold ?? opts.threshold,
              }
            : null,
        }));
        // persist the user's loop detector
        // settings. Use the daemon's normalised
        // values (r.window / r.threshold) rather
        // than opts — the daemon may have
        // clamped 0/-1 to "disabled" (-1), and
        // we want the persisted value to match
        // the canonical state.
        const prefs = readEnginePrefs();
        prefs.loopWindow = r.window ?? opts.window;
        prefs.loopThreshold = r.threshold ?? opts.threshold;
        writeEnginePrefs(prefs);
        // kick a refresh so the canonical
        // values (which might differ from the
        // optimistic ones if the daemon normalised
        // 0/negative to -1) land in the store.
        void get().refreshEngineState();
        return { ok: true, window: r.window, threshold: r.threshold, disabled: r.disabled };
      } catch (e: any) {
        return { ok: false, error: e?.message ?? String(e) };
      }
    },

    // append an RPC event to the rolling
    // buffer. Capped at 50 entries (a fresh burst
    // drops the tail). The buffer is newest-first
    // so the panel can render without sorting.
    // Each entry is a shallow JSON clone of the
    // params (the rpc layer already clones
    // before emitting) so the panel can stringify
    // the payload inline without hitting a
    // circular reference.
    recordRpcEvent: (e) => {
      set((s) => ({ recentRpcEvents: [e, ...s.recentRpcEvents].slice(0, 50) }));
    },
    // clear the rolling buffer. The
    // diagnostic panel's "Clear" button calls
    // this; the store also calls it on reconnect
    // so a new daemon session doesn't show stale
    // events from the previous one.
    clearRpcEvents: () => set({ recentRpcEvents: [] }),

    // toggle the daemon-side auto-approve-
    // low-risk flag. The daemon's setter is
    // authoritative (returns the new value +
    // the cumulative count); the store mirrors
    // it. Best-effort: a failure logs and keeps
    // the prior value (the UI will re-show the
    // flip on the next refreshEngineState tick).
    setAutoApproveLowRisk: async (enabled) => {
      try {
        const r = await rpc.setAutoApproveLowRisk({ enabled });
        if (!r.ok) return;
        set({
          autoApproveLowRisk: !!r.enabled,
          autoApprovedCount: typeof r.autoApprovedCount === 'number'
            ? r.autoApprovedCount
            : get().autoApprovedCount,
        });
        // persist the toggle so a reload
        // restores the user's choice. Written
        // AFTER the optimistic set so the
        // in-memory state is the source of
        // truth; the localStorage write is
        // best-effort (try/catch in
        // writeEnginePrefs).
        const prefs = readEnginePrefs();
        prefs.autoApproveLowRisk = !!r.enabled;
        writeEnginePrefs(prefs);
      } catch (e) {
        console.warn('setAutoApproveLowRisk failed:', e);
      }
    },

    // mirror the daemon-side
    // autoApproveMediumHigh flag. Same pattern
    // as setAutoApproveLowRisk: fire-and-forget
    // RPC, mirror on success, persist to
    // localStorage. Critical risk is NEVER
    // auto-approved regardless of this flag, so
    // toggling on does not enable rm -rf in a
    // model prompt — that path is still on the
    // explicit-prompt branch.
    setAutoApproveMediumHigh: async (enabled) => {
      try {
        const r = await rpc.setAutoApproveMediumHigh({ enabled });
        if (!r.ok) return;
        set({
          autoApproveMediumHigh: !!r.enabled,
          autoApprovedCount: typeof r.autoApprovedCount === 'number'
            ? r.autoApprovedCount
            : get().autoApprovedCount,
          autoApprovedElevatedCount: typeof r.autoApprovedElevatedCount === 'number'
            ? r.autoApprovedElevatedCount
            : get().autoApprovedElevatedCount,
        });
        const prefs = readEnginePrefs();
        prefs.autoApproveMediumHigh = !!r.enabled;
        writeEnginePrefs(prefs);
      } catch (e) {
        console.warn('setAutoApproveMediumHigh failed:', e);
      }
    },

    // select a workflow for the next message. Pass
    // `null` to clear the selection. The selection is
    // cleared on send (see sendMessage) and on run_end
    // (so the next message starts fresh).
    setActiveWorkflow: (wf) => {
      // side-effect — pushing the new selection
      // to the front of the recentWorkflows LRU. The
      // LRU is updated whether wf is null or not (a
      // null clear doesn't change the LRU; a non-null
      // selection does). We then save the new LRU
      // to localStorage so a reload / cwd switch
      // restores the order.
      if (wf) {
        get().recordRecentWorkflow(wf.name);
      }
      set({ activeWorkflow: wf });
    },

    // push a workflow name to the front of the
    // LRU. Dedupe (a re-pick moves to front without
    // duplicating), cap at 5 (the 6th push drops the
    // tail). Persisted to localStorage keyed by cwd
    // so a cwd switch shows the right per-project
    // LRU. The helper is a top-level action so
    // sendMessage can call it without going through
    // setActiveWorkflow (a re-attached workflow
    // shouldn't also clear the active selection).
    recordRecentWorkflow: (name) => {
      if (!name) return;
      const cur = get().recentWorkflows;
      // Dedupe + push-to-front: remove the existing
      // entry (if any) and prepend the new one.
      const next = [name, ...cur.filter((n) => n !== name)].slice(0, 5);
      set({ recentWorkflows: next });
      // Persist. The key includes the cwd so a
      // different project's recent list doesn't
      // bleed across. localStorage quota is per-origin
      // and ~5MB; the LRU is at most 5 × 64 chars
      // (UTF-16) = 640 bytes, well within budget.
      try {
        if (typeof window !== 'undefined' && window.localStorage) {
          const cwd = get().cwd ?? '__none__';
          window.localStorage.setItem(
            `aethercode-recent-workflows:${cwd}`,
            JSON.stringify(next),
          );
        }
      } catch {
        // localStorage can throw in private mode or
        // when the quota is exhausted. The in-memory
        // LRU is still correct; the persistence is a
        // nice-to-have.
      }
    },

    // clear the pending terminal-event toast. Called
    // by <SubagentToast> on auto-dismiss (4s) or on click.
    // No-op when there's nothing pending (defensive — the
    // dismiss path can race a fresh terminal event arriving
    // in the same tick).
    dismissSubagentTerminal: () =>
      set((s) => ({ subagent: dismissSubagentTerminalPure(s.subagent) })),

    // live TODO snapshot. The AgentTasksPanel component
    // subscribes to `todo_update` events and pushes the
    // latest list here. currentTodoId is derived (the first
    // in_progress row by startedAt) so AgentTasksPanel can
    // highlight "which one is executing" without re-scanning on every
    // render. Empty array when the agent hasn't planned yet.
    // this comment used to point at the now-deleted
    // R200+ TodoBoard.
    setTodos: (todos: Array<{
      id: string;
      title: string;
      status: 'pending' | 'in_progress' | 'completed' | 'cancelled';
      owner?: string | null;
      startedAt?: number | null;
      completedAt?: number | null;
      detail?: string | null;
    }>) => {
      const arr = [...todos];
      let currentTodoId: string | null = null;
      const inProgress = arr
        .filter((t) => t.status === 'in_progress')
        .sort((a, b) => (a.startedAt ?? 0) - (b.startedAt ?? 0));
      if (inProgress.length > 0) currentTodoId = inProgress[0].id;
      set({ todos: arr, currentTodoId });
    },

    // data fields. The actions above mutate these
    // via set(); they're listed here so the AppState
    // shape is consistent and `useStore.getState().todos`
    // returns the right type.
    todos: [] as Array<{
      id: string;
      title: string;
      status: 'pending' | 'in_progress' | 'completed' | 'cancelled';
      owner?: string | null;
      startedAt?: number | null;
      completedAt?: number | null;
      detail?: string | null;
    }>,
    currentTodoId: null as string | null,
    viewingSubagentId: null as string | null,
    viewHistory: [] as Array<{ kind: 'primary' } | { kind: 'subagent'; jobId: string }>,
  } as AppState;
});

// module-level test seam. The log handler
// is registered against the AetherCodeRpc
// singleton inside useStore's create() callback
// and isn't reachable from a vitest unit test.
// Expose the same reducer (applyLogNotification)
// as a function the test can call with a
// synthetic payload. The implementation just
// forwards through useStore.setState so the
// test sees the same effect the production
// wiring produces.
export function __test_handleLogNotification(params: any): void {
  applyLogNotification(
    (partial) => useStore.setState(partial as any),
    params,
  );
}
// type guard for the destructured useStore() shape.
// (No runtime effect — the action was already wired
// in the return object; this comment just records
// that the interface must declare it.)

// Path comparison that tolerates Windows case-insensitivity and
// trailing separators. Both inputs are normalized through
// `path.normalize`-equivalent (Windows is case-insensitive but
// forward/back slashes are interchangeable; we lower-case + use
// forward slashes).
async function samePath(a: string, b: string): Promise<boolean> {
  const na = a.replace(/\\/g, '/').replace(/\/+$/, '').toLowerCase();
  const nb = b.replace(/\\/g, '/').replace(/\/+$/, '').toLowerCase();
  return na === nb;
}

// Pick a sensible "sibling" cwd to pre-warm: the parent of the
// current cwd, so a one-level-up switch is sub-second. If the
// current cwd is a drive root (e.g. C:/) or equal to its parent,
// skip …the user can re-trigger preWarmCwd manually if they want
// a different target.
function suggestSibling(cwd: string | null): string {
  if (!cwd) return '';
  const norm = cwd.replace(/\\/g, '/').replace(/\/+$/, '');
  const parent = norm.includes('/') ? norm.slice(0, norm.lastIndexOf('/')) : '';
  if (!parent || parent === norm) return '';
  // On Windows, ensure leading slash is preserved (e.g. C:/Users/foo
  // →parent C:/Users, not C:). The path 'C:/' becomes '' which we
  // reject above.
  return parent.replace(/\//g, '\\');
}

// --- Streaming-stale watchdog -------------------------------------------
// If `isStreaming` is true but no chunk has arrived in STREAM_STALE_MS,
// the daemon is wedged (e.g. network blip without a clean WS close).
// Force-end the stream so the input box isn't stuck disabled forever.
//
// R172 tuning:
//   1. Bumped default STREAM_STALE_MS from 30s → 90s (see top of file).
//      The 30s window was firing false positives on legitimate
//      long tool runs — see the comment on the constant above.
//   2. Skip the watchdog while we're waiting on the user. If a
//      permission prompt is up, the engine deliberately stops
//      emitting chunks until the user clicks "Allow" / "Always
//      allow" / "Deny" — that's not a daemon stall, that's a
//      user decision. Without this skip, the watchdog would
//      end the stream after 90s of the user just reading the
//      prompt, which is the worst of both worlds (the user
//      gets a red error AND loses the tool result they were
//      about to approve).
if (typeof window !== 'undefined') {
  setInterval(() => {
    const s = useStore.getState();
    if (!s.isStreaming || s.lastChunkTs <= 0) return;
    // don't fire while a permission prompt is up. The
    // user might just be reading a 200-line diff and a 30s
    // window is way too aggressive for that — but a 90s
    // window is also too aggressive if the user walked away.
    // Suspend the timer entirely until the prompt resolves.
    if (s.pendingPermissions && s.pendingPermissions.length > 0) return;
    if (Date.now() - s.lastChunkTs > STREAM_STALE_MS) {
      console.warn(`[store] stream stale: no chunk for ${STREAM_STALE_MS}ms, force-ending`);
      useStore.setState((cur) => ({
        isStreaming: false,
        messages: [...cur.messages, {
          id: newId('system'), role: 'system' as const,
          content: `[Stream stale] Daemon stopped responding for ${STREAM_STALE_MS / 1000}s. Try sending again.`,
          timestamp: Date.now(), isError: true,
        }],
      }));
    }
  }, STREAM_CHECK_INTERVAL_MS);
}

// On every state change, if the `messages` reference changed,
// debounce-write the messages for the current session to
// localStorage. 300ms matches the input-draft debounce so the
// two writes don't fight each other. The subscriber also handles
// the "rename __none__ → real sessionId" transition: when the
// user sends a message from the no-session-yet state, the
// daemon issues a sessionId, sendMessage updates
// `currentSessionId`, and the very next state change triggers
// a write under the new key (the old `__none__` slot is
// dropped — see clearStaleNoneSession below).
//
// this whole block is retired. The daemon's
// appState.transcript is now the source of truth; the
// transcript_event subscriber replaces `messages` on
// session switch, and getTranscript back-fills on
// reconnect. No more localStorage round-trip per
// mutation.
if (typeof window !== 'undefined') {
  // one-time migration. On the first launch of
  // the prior round+, drop every `aethercode-session:*` key
  // (the per-session message cache) so the quota
  // doesn't carry dead bytes forward. We also drop
  // any `aethercode-session:__none__` slot, which the
  // retired write path used for the "no session yet"
  // state. The input-draft keys (the prior round) are kept — those
  // are still in use.
  //
  // Guarded by a `R108_MIGRATION_DONE` flag in
  // localStorage so we don't pay the scan cost on
  // every launch.
  try {
    const FLAG = 'aethercode-r108-migration-v1';
    if (!window.localStorage.getItem(FLAG)) {
      const toRemove: string[] = [];
      for (let i = 0; i < window.localStorage.length; i++) {
        const k = window.localStorage.key(i);
        if (k && k.startsWith(SESSION_STORAGE_PREFIX)) toRemove.push(k);
      }
      for (const k of toRemove) {
        try { window.localStorage.removeItem(k); } catch {}
      }
      window.localStorage.setItem(FLAG, String(Date.now()));
      if (toRemove.length > 0) {
        console.log(`[store] 对应历史 round migration: removed ${toRemove.length} legacy localStorage session keys`);
      }
    }
  } catch { /* localStorage access failed — non-fatal */ }
}
