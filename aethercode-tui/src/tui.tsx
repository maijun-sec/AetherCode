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
import { JsonRpcClient, type RpcValue } from "./jsonrpc.js";
import { findJar } from "./ac-tui.js";
import { reducer, INITIAL, type State, type Action, type PermissionAsk } from "./state.js";
import { handleSlash, completeSlash } from "./commands.js";
import { formatSystemPrompt, formatSystemPromptSection } from "./prompt-formatter.js";
import { Header } from "./components/Header.js";
import { StatusBar } from "./components/StatusBar.js";
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

const App: React.FC<{ client: JsonRpcClient; cwd: string; stateRef: React.MutableRefObject<State> }> = ({
  client,
  cwd,
  stateRef,
}) => {
  const { exit } = useApp();
  const [state, dispatch] = useReducer(reducer, INITIAL);
  stateRef.current = state;
  const [h, setH] = useState(20);

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
        case "stream_event": {
          const p = (params ?? {}) as { runId?: string; event?: Record<string, unknown> };
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
          // in ACCEPT_TASK mode, auto-allow without showing
          // the card. The user explicitly said "don't stop in the
          // middle of a task", so the daemon boundary decisions
          // (which are the only times ACCEPT_TASK should ask) are
          // treated as no-ops. The daemon's permission policy
          // will already have skipped these for in-task calls;
          // what reaches us here is a sub-task boundary decision
          // the user is happy to skip.
          if (stateRef.current.permissionMode === "ACCEPT_TASK") {
            void client.request("permissionResponse", {
              requestId: ask.requestId,
              decision: "allow",
              reason: "auto-allowed in ACCEPT_TASK mode (no mid-task stops)",
            }).catch((e: Error) => {
              dispatch({ type: "log", message: `permission auto-allow failed: ${e.message}` });
            });
            return;  // don't dispatch permissionAsk — skip the card
          }
          // Read-only tools: also auto-allow. The daemon should
          // never send these (the policy's resolveAsk returns
          // Allow without asking), but a buggy daemon or a
          // future feature could. Be defensive.
          if (ask.riskLevel === "low") {
            void client.request("permissionResponse", {
              requestId: ask.requestId,
              decision: "allow",
              reason: "auto-allowed read-only tool",
            }).catch((e: Error) => {
              dispatch({ type: "log", message: `permission auto-allow failed: ${e.message}` });
            });
            return;
          }
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
        });
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
        // switch the daemon to ACCEPT_TASK on first connect
        // unless the user explicitly pinned a mode. The user
        // asked for "no mid-task stops" — this is the
        // single-task-doesn't-pause semantics. The user can
        // still flip back to DEFAULT with `/mode DEFAULT` if they
        // want per-write confirmation. The setPermissionMode call
        // is best-effort: a failure here just means the daemon
        // stayed in its previous mode (no-op for the user).
        try {
          await client.request("setPermissionMode", { mode: "ACCEPT_TASK" });
          dispatch({ type: "setPermissionMode", mode: "ACCEPT_TASK" });
          dispatch({
            type: "pushToast",
            kind: "info",
            text: "permission mode → ACCEPT_TASK (no mid-task stops)",
          });
        } catch (modeErr) {
          // surface the failure. The previous "silent log only"
          // meant the user would see `mode DEFAULT` in the status bar
          // with no explanation — and a DEFAULT-mode daemon will pause
          // the model on every file_write, which they will misread as
          // "the TUI is broken". A warn toast tells them what's wrong
          // and what to do (`/mode ACCEPT_TASK`).
          const reason = (modeErr as Error).message ?? "unknown";
          dispatch({
            type: "pushToast",
            kind: "warn",
            text: `permission mode stayed in DEFAULT (setPermissionMode failed: ${reason}). Run /mode ACCEPT_TASK to retry, or /mode BYPASS_PERMISSIONS to skip prompts.`,
          });
          dispatch({
            type: "log",
            message: `setPermissionMode(ACCEPT_TASK) failed: ${reason}`,
          });
        }
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
    // we no longer swallow Enter while a query is in flight
    // — that produced the "TUI looks stuck" complaint. Instead
    // we show a toast telling the user the prompt is queued. The
    // daemon handles concurrent queries; the user gets clear
    // feedback either way.
    //
    // Slash commands stay local to the TUI and don't need a
    // roundtrip, so we still run them even if the model is busy.
    if (state.submitting) {
      dispatch({
        type: "pushToast",
        kind: "info",
        text: "model is still working — prompt queued",
      });
      return;
    }
    const slash = handleSlash(text);
    if (slash?.local === "__EXIT__") {
      dispatch({ type: "exit" });
      exit();
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
      // /export <path>  or  /export json <path>
      // We dynamically import the export helpers to avoid pulling
      // node:fs into the bundle for the Ink TUI hot path.
      const isJson = slash.local.startsWith("__EXPORT_JSON__:");
      const path = slash.local.slice(isJson ? "__EXPORT_JSON__:".length : "__EXPORT_MD__:".length);
      void (async () => {
        try {
          const { exportToMarkdown, exportToJson, writeExport } = await import("./exportScrollback.js");
          const body = isJson ? exportToJson(state.turns) : exportToMarkdown(state.turns, state.model, state.sessionId);
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
    if (slash?.local) {
      dispatch({ type: "sideNote", kind: "info", message: slash.local });
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
    dispatch({ type: "submit" });
    try {
      await client.request<unknown>("query", { prompt: text });
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

  useInput((input, key) => {
    if (key.ctrl && input === "c") {
      dispatch({ type: "exit" });
      exit();
      return;
    }
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
    // when a permission ask is on screen, route the decision
    // keys to replyPermission. The card is now INLINE in the
    // scrollback (not a full-screen modal), so the user can keep
    // typing in the input box while the ask is pending. The
    // decision keys A / T / P / U / D / N work regardless of
    // focus.
    if (state.permissionAsk) {
      const ask = state.permissionAsk;
      if (input === "a" || input === "A") { replyPermission(client, ask, "allow", dispatch); return; }
      // T — allow for this task. Session-scope rule, but only
      // future calls of this tool in the current run.
      if (input === "t" || input === "T") { replyPermission(client, ask, "always_allow", dispatch, "session"); return; }
      // P — allow for this project. Persists to .aethercode/permissions.json.
      if (input === "p" || input === "P") { replyPermission(client, ask, "always_allow", dispatch, "project"); return; }
      // U — allow for this user (cross-project, but only for this
      // user's account on this machine).
      if (input === "u" || input === "U") { replyPermission(client, ask, "always_allow", dispatch, "user"); return; }
      if (input === "d" || input === "D") { replyPermission(client, ask, "deny", dispatch); return; }
      if (input === "n" || input === "N") { replyPermission(client, ask, "always_deny", dispatch, "session"); return; }
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
      if (state.showSearch) dispatch({ type: "showSearch", show: false });
      if (state.helpVisible) dispatch({ type: "showHelp", show: false });
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
            cwd={cwd}
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
          {state.sidebarVisible ? (
            <Sidebar
              width={26}
              cwd={cwd}
              sessionId={state.sessionId}
              recentQueries={state.history}
              tasks={state.recentTasks}
            />
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
              <Header state={state} cwd={cwd} />
            ) : null}
            <Box flexDirection="row" flexGrow={1}>
              <Box flexDirection="column" flexGrow={1}>
                <Scrollback
                  turns={state.turns}
                  height={1000}
                  showWelcome={showWelcome}
                  welcomeCtx={{ model: state.model, cwd, sessionId: state.sessionId, mode: state.permissionMode }}
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
                  todo board + tokens. Toggleable with Ctrl+D. */}
              {state.rightPanelVisible ? (
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
                      cwd,
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
