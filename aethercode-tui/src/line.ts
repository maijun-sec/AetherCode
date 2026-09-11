/**
 * line-mode renderer (no Ink / no full-screen UI).
 *
 * This is the fallback when the user runs the TUI in a non-TTY
 * environment (e.g. cmd.exe on Windows, or any piped context where
 * Ink can't get raw mode + alt-buffer). It streams each event as a
 * line of text and shows a single-line input prompt at the bottom.
 *
 * The output format mirrors the Ink TUI's structure but in plain
 * line-by-line text:
 *
 *   [● connected · MiniMax-M3 · session abcdef12]
 *
 *   ❯ what is 2+2
 *   2 + 2 = **4**.
 *   ❯   ← prompt
 *
 *   ❯ list the files
 *   ◐ tool: list_files (running)
 *   ✓ tool: list_files
 *   README.md
 *   package.json
 *   ❯
 *
 * Compared to the Ink TUI:
 * - no colours (we use simple symbols)
 * - no scrollback (terminal scroll handles history)
 * - no collapsible cards
 * - no help overlay (Ctrl-? / F1 prints a tip line)
 * - no input history navigation
 *
 * It's deliberately minimal. The Ink TUI is the real product; this
 * is the "works in cmd.exe" fallback.
 */

import * as readline from "node:readline";
import { JsonRpcClient, type RpcValue } from "./jsonrpc.js";
import { handleSlash, SLASH_HELP } from "./commands.js";
import { t as theme } from "./theme.js";

export interface LineOptions {
  jar: string;
  cwd: string;
  javaBin: string;
  jvmArgs: string[];
  model?: string;
  noColor?: boolean;
}

const C = {
  reset: "\x1b[0m",
  bold:  "\x1b[1m",
  dim:   "\x1b[2m",
  red:   "\x1b[31m",
  green: "\x1b[32m",
  yellow:"\x1b[33m",
  blue:  "\x1b[34m",
  magenta:"\x1b[35m",
  cyan:  "\x1b[36m",
  gray:  "\x1b[90m",
  yellowBright: "\x1b[93m",
  bg:    "\x1b[100m",
};

function colorize(s: string, color: string, noColor: boolean): string {
  if (noColor) return s;
  return `${color}${s}${C.reset}`;
}

/** classify a stop reason for line-mode color. Same
 *  logic as the Ink TUI's classifier (state.ts). MUST stay
 *  in sync — see R32 retrospective: strict prefix match avoids
 *  false positives like "main_loop" or "max_tokens". */
function classifyStopReason(reason: string | null | undefined): "ok" | "loop" | "max_turns" | "error" | "empty" | null {
  if (reason == null) return null;
  const r = reason.toLowerCase();
  // Loop detector — only the engine's own "loop_detected" or
  // progress-loop-detector variants. Avoid matching arbitrary
  // words containing "loop" (e.g. "main_loop").
  if (r === "loop_detected" || r.startsWith("loop_")) return "loop";
  // Per-query turn cap. NOT "max_tokens" (that's the model-side
  // output cap, which is a normal completion).
  if (r === "max_iterations" || r === "max_turns" || r === "max_tool_iterations") return "max_turns";
  if (r === "empty_input" || r === "empty") return "empty";
  if (r === "error" || r.startsWith("error_") || r.endsWith("_error")) return "error";
  return "ok";
}

/** a tiny line-mode port of the Ink TUI's tool categoriser.
 *  Same logic so the user sees the same colour + icon for the
 *  same tool in both renderers. */
type LineCategory = "read" | "write" | "search" | "run" | "agent" | "other";
function categorizeLine(name: string | undefined | null): LineCategory {
  if (!name) return "other";
  const n = name.toLowerCase();
  if (n === "file_read" || n === "read" || n.startsWith("read_") || n === "cat" || n === "show" || n === "view") return "read";
  if (n === "glob" || n === "grep" || n === "search" || n.startsWith("search_") || n === "find") return "search";
  if (n === "file_write" || n === "file_edit" || n === "file_create" || n.startsWith("write_") || n.startsWith("edit_")) return "write";
  if (n === "bash" || n === "shell" || n === "exec" || n === "run_command" || n.startsWith("bash_") || n === "test" || n === "build") return "run";
  if (n === "agent" || n === "task" || n === "delegate" || n.startsWith("agent_") || n.startsWith("task_")) return "agent";
  return "other";
}

/** per-category icon + colour for line mode. Mirrors the
 *  Ink TUI's ToolCard so the user sees consistent visual
 *  semantics between renderers. */
const CAT_ICON: Record<LineCategory, string> = {
  read:   "○",
  write:  "✎",
  search: "◎",
  run:    "▷",
  agent:  "❖",
  other:  "⚒",
};
const CAT_COLOR: Record<LineCategory, string> = {
  read:   C.blue,
  write:  C.yellowBright,
  search: C.cyan,
  run:    C.red,
  agent:  C.magenta,
  other:  C.gray,
};

/** Read one keystroke from stdin. In a TTY, uses raw mode and
 *  returns a single character. In a non-TTY, reads one chunk of
 *  input (the whole pipe-in chunk is treated as the first
 *  character, which is fine because we only need the leading byte).
 *  Defaults to "deny" if no input arrives within 1 second. */
async function readOneChar(): Promise<string> {
  if (!process.stdin.isTTY) {
    return "deny";
  }
  return new Promise<string>((resolve) => {
    const onData = (chunk: Buffer | string) => {
      process.stdin.removeListener("data", onData);
      process.stdin.removeListener("keypress", onKeypress as never);
      if (typeof (process.stdin as NodeJS.ReadStream & { setRawMode?: (m: boolean) => void }).setRawMode === "function") {
        try { (process.stdin as NodeJS.ReadStream & { setRawMode: (m: boolean) => void }).setRawMode(false); } catch { /* ignore */ }
      }
      const s = String(chunk);
      const c = s.charAt(0).toLowerCase();
      resolve(
        c === "a" ? "allow" :
        c === "y" ? "always_allow" :
        c === "d" ? "deny" :
        c === "n" ? "always_deny" :
        "deny"
      );
    };
    const onKeypress = (_s: string, k: { name?: string }) => {
      process.stdin.removeListener("data", onData);
      process.stdin.removeListener("keypress", onKeypress as never);
      try { (process.stdin as NodeJS.ReadStream & { setRawMode: (m: boolean) => void }).setRawMode(false); } catch { /* ignore */ }
      const n = k.name ?? "";
      resolve(n === "a" || n === "y" || n === "d" || n === "n" ? (
        n === "a" ? "allow" :
        n === "y" ? "always_allow" :
        n === "d" ? "deny" :
        "always_deny"
      ) : "deny");
    };
    process.stdin.on("data", onData);
    process.stdin.on("keypress", onKeypress as never);
    if (typeof (process.stdin as NodeJS.ReadStream & { setRawMode?: (m: boolean) => void }).setRawMode === "function") {
      try { (process.stdin as NodeJS.ReadStream & { setRawMode: (m: boolean) => void }).setRawMode(true); } catch { /* ignore */ }
    }
    setTimeout(() => {
      process.stdin.removeListener("data", onData);
      process.stdin.removeListener("keypress", onKeypress as never);
      try { (process.stdin as NodeJS.ReadStream & { setRawMode: (m: boolean) => void }).setRawMode(false); } catch { /* ignore */ }
      resolve("deny");
    }, 1000);
  });
}

function shortSession(s: string): string {
  return s ? s.slice(0, 8) : "—";
}

export async function runLineMode(opts: LineOptions): Promise<number> {
  const noColor = opts.noColor || !process.stdout.isTTY;
  const client = new JsonRpcClient({
    jarPath: opts.jar,
    cwd: opts.cwd,
    javaBinary: opts.javaBin,
    jvmArgs: opts.jvmArgs,
    onNotification: () => undefined,
    onExit: (code) => {
      // We log this on the prompt boundary so the user sees it.
      if (code !== 0 && code !== null) {
        logAbove(colorize(`[daemon exited code=${code}]`, C.red, noColor));
      }
    },
  });

  // Wait for daemon to be ready.
  let sessionId = "—";
  let model = opts.model ?? "—";
  try {
    const st = (await client.request("getState")) as Record<string, unknown> | null | undefined;
    const obj = (st ?? {}) as Record<string, unknown>;
    sessionId = String(obj.sessionId ?? "—");
    model = String(obj.model ?? model);
  } catch (e) {
    console.error(colorize(`failed to reach daemon: ${(e as Error).message}`, C.red, noColor));
    return 2;
  }

  // Set up notification dispatch. We track a "run state" so
  // streaming text is accumulated and flushed at boundaries.
  let runState: "idle" | "streaming" | "running-tool" = "idle";
  let textBuffer = "";
  let textFlushTimer: NodeJS.Timeout | null = null;
  let currentTool: string | null = null;
  let lastToolWas = "—";

  function flushText(): void {
    if (textBuffer.length === 0) return;
    const text = textBuffer;
    textBuffer = "";
    // Print the accumulated text. Use logAbove so the prompt is restored.
    logAbove(text);
  }

  function scheduleFlush(delayMs = 80): void {
    if (textFlushTimer) clearTimeout(textFlushTimer);
    textFlushTimer = setTimeout(flushText, delayMs);
  }

  // Stash on the function for the readline loop to attach later.
  const onRunEnd = () => {
    sawRunEnd = true;
    inFlight = Math.max(0, inFlight - 1);
    if (!rlClosed) {
      rl.prompt(true);
    }
    tryExit();
  };
  (runLineMode as unknown as { _onRunEnd?: () => void })._onRunEnd = onRunEnd;

  client.setNotificationHandler((method: string, params: RpcValue | undefined) => {
    if (method === "stream_event") {
      const p = (params ?? {}) as { event?: Record<string, unknown> };
      const ev = p.event ?? {};
      const t = ev.type as string | undefined;
      if (t === "run_start") {
        runState = "streaming";
        textBuffer = "";
      } else if (t === "text_delta") {
        textBuffer += String(ev.text ?? "");
        scheduleFlush();
      } else if (t === "tool_use_start") {
        if (textFlushTimer) { clearTimeout(textFlushTimer); textFlushTimer = null; }
        flushText();
        currentTool = String(ev.name ?? "?");
        runState = "running-tool";
        // per-category icon + colour. Read=blue, write=yellow,
        // search=cyan, run=red, agent=magenta, other=gray.
        const cat = categorizeLine(currentTool);
        const catIcon = CAT_ICON[cat];
        const catColor = CAT_COLOR[cat];
        // Tool name in the category color, dim the "tool:" prefix
        // so the user's eye is drawn to the name.
        logAbove(
          colorize("  " + catIcon + " tool: ", C.dim, noColor) +
          colorize(currentTool, catColor, noColor)
        );
      } else if (t === "tool_result") {
        const isErr = Boolean(ev.isError);
        const name = String(ev.id ?? currentTool ?? "?");
        lastToolWas = name;
        // keep the category icon for the completion line so
        // the user can match start vs end at a glance.
        const cat = categorizeLine(name);
        const catIcon = CAT_ICON[cat];
        const resultIcon = isErr ? "✗" : "✓";
        const c = isErr ? C.red : C.green;
        logAbove(
          colorize(`  ${catIcon} `, C.dim, noColor) +
          colorize(resultIcon, c, noColor) +
          colorize(` tool: ${name}`, C.dim, noColor)
        );
        runState = "streaming";
      } else if (t === "run_end") {
        if (textFlushTimer) { clearTimeout(textFlushTimer); textFlushTimer = null; }
        flushText();
        runState = "idle";
        const stopReason = String(ev.stopReason ?? "end_turn");
        // colour the run-end line by stop kind so the user
        // can tell at a glance whether the model finished normally
        // or was stopped by the loop detector / turn cap.
        const kind = classifyStopReason(stopReason);
        const c =
          kind === "loop"      ? C.red   :
          kind === "max_turns" ? C.yellow :
          kind === "error"     ? C.red   :
          kind === "ok"        ? C.green :
                                  C.dim;
        const tag =
          kind === "loop"      ? "stopped — loop" :
          kind === "max_turns" ? "stopped — max turns" :
          kind === "error"     ? "stopped — error" :
          kind === "ok"        ? "ready" :
                                  "run_end: " + stopReason;
        const line = `  [${tag}]  (reason: ${stopReason})`;
        logAbove(colorize(line, c, noColor));
        // also print a follow-up hint when the model
        // finished abnormally, so the user knows what to do next.
        if (kind === "loop") {
          logAbove(colorize("  (the model repeated the same call or hit a long-running output; daemon stopped it)", C.dim, noColor));
        } else if (kind === "max_turns") {
          logAbove(colorize("  (the run hit the per-query turn cap; raise it with --max-turns, or narrow the prompt)", C.dim, noColor));
        } else if (kind === "error") {
          logAbove(colorize("  (check the daemon log on stderr for details)", C.dim, noColor));
        }
        onRunEnd();
      } else if (t === "side_note") {
        logAbove(colorize(`  · ${ev.message ?? ""}`, C.dim, noColor));
      } else if (t === "plan") {
        const items = Array.isArray(ev.items) ? ev.items : [];
        const lines = items.map((it, i) => `  ${String(i + 1).padStart(2, " ")}. ${it}`).join("\n");
        logAbove(colorize(`✱ Plan (${items.length} item${items.length === 1 ? "" : "s"}):\n${lines}`, C.cyan, noColor));
      }
    } else if (method === "log") {
      const p = (params ?? {}) as { message?: string };
      logAbove(colorize(`  [log] ${p.message ?? ""}`, C.gray, noColor));
    } else if (method === "task_state") {
      const p = (params ?? {}) as { taskId?: string; status?: string };
      logAbove(colorize(`  [task ${p.taskId ?? "?"} → ${p.status ?? "?"}]`, C.dim, noColor));
    } else if (method === "permission_request") {
      // a tool call needs a permission decision. Print the
      // ask, then read a single keystroke from stdin (in raw mode)
      // to let the user pick A / Y / D / N. In piped input we
      // default to "deny" (refuse to run unverified commands).
      const p = (params ?? {}) as Record<string, unknown>;
      const tool = String(p.tool ?? "(unknown)");
      const input = (p.input as Record<string, unknown>) ?? {};
      const reason = String(p.reason ?? "");
      const risk = String(p.riskLevel ?? "medium").toUpperCase();
      const requestId = String(p.requestId ?? "");
      logAbove(colorize(
        `⚠ PERMISSION REQUIRED [${risk}] — tool: ${tool}` +
        `\n  input: ${JSON.stringify(input)}` +
        `\n  reason: ${reason}` +
        `\n  [A]llow  [Y]always  [D]eny  [N]never`,
        risk === "CRITICAL" ? C.red : (risk === "HIGH" ? C.yellowBright : C.yellow),
        noColor,
      ));
      // Read one keystroke + send the reply. We can't await
      // inside a non-async callback, so we wrap in an async IIFE.
      void (async () => {
        const decision = await readOneChar();
        try {
          await client.request("permissionResponse", {
            requestId,
            decision,
            reason: `line mode picked ${decision}`,
          });
        } catch (e) {
          logAbove(colorize(`permission reply failed: ${(e as Error).message}`, C.red, noColor));
        }
      })();
    }
  });

  // Print the header.
  const header =
    colorize("[● ", C.green, noColor) +
    colorize("connected", C.bold, noColor) +
    colorize(" · " + model, C.cyan, noColor) +
    colorize(" · session " + shortSession(sessionId), C.dim, noColor) +
    colorize(" · cwd " + opts.cwd, C.dim, noColor);
  process.stdout.write(header + "\n\n");

  // Set up readline.
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    prompt: colorize("❯ ", C.yellowBright, noColor),
    terminal: process.stdin.isTTY ?? false,
  });
  // readline.Interface doesn't expose a `closed` property, so we
  // track it ourselves to avoid calling prompt() after close.
  let rlClosed = false;
  rl.on("close", () => { rlClosed = true; });

  // Exit coordination: the close handler and onRunEnd both
  // communicate via exitResolve. The process only exits when:
  //   1. readline is closed (EOF / /exit / Ctrl-C in interactive), AND
  //   2. inFlight queries have all reported run_end (or hit timeout).
  let inFlight = 0;
  let sawRunEnd = false;
  let exitResolve: ((code: number) => void) | null = null;
  const exitPromise = new Promise<number>((resolve) => { exitResolve = resolve; });

  function tryExit(): void {
    if (!rlClosed || !exitResolve) return;
    if (inFlight === 0) {
      const r = exitResolve;
      exitResolve = null;
      r(0);
    }
  }

  // Wrapper for output that restores the prompt line.
  function logAbove(text: string): void {
    if (process.stdout.isTTY) {
      // Move to start of line, clear, print the text, then re-prompt.
      process.stdout.write("\r\x1b[K" + text + "\n");
      rl.prompt(true);
    } else {
      // Not a TTY — just append.
      process.stdout.write(text + "\n");
    }
  }

  rl.prompt();

  rl.on("line", async (line) => {
    const text = line.trim();
    if (!text) {
      if (!rlClosed) rl.prompt(true);
      return;
    }
    // Handle slash commands locally first.
    const slash = handleSlash(text);
    if (slash?.local === "__EXIT__") {
      inFlight = 0;
      rl.close();
      tryExit();
      return;
    }
    if (slash?.local === "__CLEAR__") {
      // Clear terminal.
      process.stdout.write("\x1b[2J\x1b[H");
      if (!rlClosed) rl.prompt(true);
      return;
    }
    if (slash?.local === "__HISTORY__") {
      logAbove(colorize("(history navigation is only available in the Ink TUI; use ↑/↓ in a real terminal)", C.dim, noColor));
      return;
    }
    if (slash?.local && slash.local === SLASH_HELP) {
      logAbove(colorize(SLASH_HELP, C.dim, noColor));
      return;
    }
    if (slash?.local) {
      logAbove(colorize(slash.local, C.dim, noColor));
      return;
    }
    if (slash?.rpcMethod) {
      try {
        const result = await client.request<unknown>(slash.rpcMethod, slash.rpcParams as RpcValue);
        logAbove(colorize(`${slash.rpcMethod} → ${JSON.stringify(result)}`, C.dim, noColor));
      } catch (e) {
        logAbove(colorize(`${slash.rpcMethod} failed: ${(e as Error).message}`, C.red, noColor));
      }
      return;
    }
    // Real query.
    logAbove(""); // blank line before the run
    inFlight++;
    sawRunEnd = false;
    try {
      await client.request("query", { prompt: text });
    } catch (e) {
      logAbove(colorize(`query failed: ${(e as Error).message}`, C.red, noColor));
      inFlight--;
      if (!rlClosed) rl.prompt(true);
      tryExit();
    }
    // The run_end event will decrement inFlight and re-prompt.
  });

  // Close handler: EOF / /exit / Ctrl-C. Wait for in-flight to drain
  // (with a 5s timeout) so any model response that was already in
  // flight gets a chance to print.
  rl.on("close", () => {
    if (inFlight === 0) {
      tryExit();
    } else {
      // Set a hard ceiling. After 5s with no progress, force-exit.
      setTimeout(() => {
        if (exitResolve) {
          const r = exitResolve;
          exitResolve = null;
          process.stderr.write("[ac-tui] in-flight query didn't finish in 5s; exiting\n");
          r(1);
        }
      }, 5000);
    }
  });

  return exitPromise.then(async (code) => {
    await client.stop();
    return code;
  });
}
