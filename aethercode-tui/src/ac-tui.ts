/**
 * entry point for the standalone AetherCode TUI.
 *
 * Discovers the aethercode-*.jar in the same directory as this
 * script (or via AETHERCODE_JAR env var, or via the `--jar`
 * flag), spawns the daemon in --daemon mode, and renders the
 * Ink-based UI.
 *
 * CLI flags:
 *   --jar <path>     explicit path to the aethercode-*.jar
 *   --cwd <path>     working directory for the daemon
 *   --java <bin>     Java binary to use (default: java)
 *   --xmx <size>     JVM heap (default: -Xmx1g)
 *   --model <name>   set the model on startup
 *   --no-color       disable ANSI colour
 *   --print "..."    headless single turn (no TUI; just print result)
 *   --version        print version
 *   --help           this message
 */

import { parseArgs } from "node:util";
import { existsSync, statSync, readdirSync, realpathSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { JsonRpcClient, type RpcValue } from "./jsonrpc.js";
import { runTui } from "./tui.js";

const VERSION = "0.2.1";

function parseCli(argv: string[]): { values: Record<string, unknown>; positionals: string[] } {
  return parseArgs({
    args: argv,
    options: {
      jar:        { type: "string" },
      cwd:        { type: "string" },
      java:       { type: "string", default: "java" },
      xmx:        { type: "string", default: "-Xmx1g" },
      model:      { type: "string" },
      "no-color": { type: "boolean", default: false },
      line:       { type: "boolean", default: false },     // prior round: force line mode
      tui:        { type: "boolean", default: false },     // prior round: force full-screen TUI (Ink)
      print:      { type: "string" },
      version:    { type: "boolean", default: false },
      help:       { type: "boolean", default: false },
    },
    allowPositionals: true,
  });
}

function printHelp(): void {
  process.stdout.write(`ac-tui v${VERSION}

Usage:
  ac-tui                       start the interactive TUI (Ink on a TTY,
                               line mode otherwise)
  ac-tui --tui                 force the full-screen Ink TUI
  ac-tui --line                force line mode (no full-screen UI)
  ac-tui --print "prompt"      run a single turn and exit
  ac-tui --jar <path>          explicit path to aethercode-*.jar
  ac-tui --cwd <path>          working directory for the daemon
  ac-tui --model <name>        override the model on startup
  ac-tui --java <bin>          Java binary (default: java)
  ac-tui --xmx <size>          JVM heap (default: -Xmx1g)
  ac-tui --no-color            disable ANSI colour
  ac-tui --version             print version
  ac-tui --help                this message

Environment:
  AETHERCODE_JAR    path to the aethercode-*.jar (overrides auto-detect)
  MINIMAX_API_KEY   API key for MiniMax (default model)
  ANTHROPIC_API_KEY API key for Anthropic
  OPENAI_API_KEY    API key for any OpenAI-compatible endpoint

Modes:
  The TUI picks the renderer based on whether stdin/stdout are real
  TTYs. On a real TTY (Linux/macOS terminal, Windows Terminal, or any
  ConPTY-capable host) you get the full-screen Ink UI. In cmd.exe or
  any other non-TTY host (CI, piped output, etc.) the TUI falls back
  to a line-by-line renderer that works in any context.

  Pass --tui or --line to override the auto-detect.

Shortcuts (in the Ink TUI):
  Enter              submit the prompt
  Up / Down          navigate input history
  Ctrl-C             exit
  Ctrl-L             clear scrollback
  Ctrl-?  /  F1      help overlay
`);
}

export function findJar(explicit?: string): string {
  if (explicit) {
    if (!existsSync(explicit)) {
      throw new Error(`--jar path does not exist: ${explicit}`);
    }
    return resolve(explicit);
  }
  const env = process.env.AETHERCODE_JAR;
  if (env) {
    if (!existsSync(env)) throw new Error(`AETHERCODE_JAR does not exist: ${env}`);
    return resolve(env);
  }
  // in a bun --compile binary, import.meta.url points to a
  // virtual path inside the executable (e.g. file:///B:/~BUN/root/...),
  // so dirname(import.meta.url) is NOT the directory the user launched
  // the exe from. Use process.execPath (the resolved path to the
  // running exe) for the primary lookup, then fall back to the script
  // path for the unbundled `node dist/ac-tui.js` case. The bundled
  // release ships the jar next to the exe so this hits immediately.
  const exeDir = (() => {
    try { return dirname(realpathSync(process.execPath)); }
    catch { return null; }
  })();
  const here = dirname(fileURLToPath(import.meta.url));
  const candidates: (string | null)[] = [
    exeDir,                                    // prior round: bun-compiled exe's directory
    resolve(exeDir ?? here, ".."),             // parent of exe dir (e.g. release/..)
    here,                                      // node dist/ac-tui.js case
    resolve(here, ".."),                       // parent of script
    resolve(here, "../.."),
    resolve(here, "../dist"),
    resolve(here, "../dist/ac-tui"),
    resolve(here, "../dist/ac-tui-jline"),
  ];
  for (const dir of candidates) {
    if (!dir) continue;
    if (!existsSync(dir)) continue;
    let entries: string[];
    try { entries = readdirSync(dir); } catch { continue; }
    const jars = entries
      .filter((n) => /^aethercode-.*\.jar$/.test(n) && !n.endsWith(".jar.original"))
      .map((n) => join(dir, n))
      .filter((p) => { try { return statSync(p).isFile(); } catch { return false; } })
      .sort();
    if (jars.length > 0) return realpathSync(jars[jars.length - 1]);
  }
  throw new Error(
    "could not find aethercode-*.jar. Set AETHERCODE_JAR or pass --jar <path>.",
  );
}

async function runHeadless(
  jar: string, cwd: string, javaBin: string, jvmArgs: string[],
  prompt: string, model?: string,
): Promise<number> {
  return new Promise<number>((resolveP) => {
    let buffer = "";
    let finished = false;
    const client = new JsonRpcClient({
      jarPath: jar,
      cwd,
      javaBinary: javaBin,
      jvmArgs,
      onNotification: (method, params) => {
        if (method !== "stream_event") return;
        const p = (params ?? {}) as { event?: Record<string, unknown> };
        const ev = p.event ?? {};
        if (ev.type === "text_delta") {
          buffer += String(ev.text ?? "");
          // Mirror text to stdout in real time so the user sees
          // the model thinking, not just the final answer.
          process.stdout.write(String(ev.text ?? ""));
        } else if (ev.type === "side_note" && ev.kind === "memory") {
          process.stderr.write(`[memory] ${ev.message}\n`);
        } else if (ev.type === "run_end") {
          finished = true;
          setTimeout(() => {
            if (buffer.length > 0 && !buffer.endsWith("\n")) process.stdout.write("\n");
            client.stop().finally(() => resolveP(0));
          }, 50);
        }
      },
      onExit: (code) => {
        if (!finished) {
          process.stderr.write(`\n[ac-tui] daemon exited (code=${code}) before stream end\n`);
          resolveP(1);
        }
      },
    });
    (async () => {
      try {
        if (model) {
          try { await client.request("setModel", { model }); }
          catch (e) { process.stderr.write(`warning: setModel failed: ${(e as Error).message}\n`); }
        }
        await client.request("query", { prompt });
        // Don't await — the response is just {runId, accepted}.
      } catch (e) {
        process.stderr.write(`\n[ac-tui] ${(e as Error).message}\n`);
        await client.stop();
        resolveP(1);
      }
    })();
  });
}

async function main(): Promise<number> {
  const { values, positionals } = parseCli(process.argv.slice(2));
  const v = values as Record<string, unknown>;
  if (v.help) { printHelp(); return 0; }
  if (v.version) { process.stdout.write(`ac-tui v${VERSION}\n`); return 0; }

  const javaBin = String(v.java ?? "java");
  const jvmArgs = [String(v.xmx ?? "-Xmx1g")];
  const cwd = String(v.cwd ?? process.cwd());
  let jar: string;
  try { jar = findJar(v.jar as string | undefined); }
  catch (e) { process.stderr.write(`error: ${(e as Error).message}\n`); return 2; }

  if (typeof v.print === "string" && v.print.length > 0) {
    return await runHeadless(jar, cwd, javaBin, jvmArgs, v.print as string, v.model as string | undefined);
  }

  // decide between Ink TUI and line-mode renderer.
  // - --line forces line mode
  // - --tui forces Ink TUI
  // - default: Ink if both stdin and stdout are TTY, line mode otherwise
  const wantLine = Boolean(v.line);
  const wantTui  = Boolean(v.tui);
  const haveTty  = Boolean(process.stdin.isTTY && process.stdout.isTTY);
  const useLine  = wantLine || (!wantTui && !haveTty);

  const model = v.model as string | undefined;
  const noColor = Boolean(v["no-color"]);

  if (useLine) {
    const { runLineMode } = await import("./line.js");
    return await runLineMode({ jar, cwd, javaBin, jvmArgs, model, noColor });
  }
  return await runTui({ jar, cwd, javaBin, jvmArgs, model, noColor });
}

main().then(
  (code) => process.exit(code),
  (err) => {
    process.stderr.write(`fatal: ${(err as Error).stack ?? (err as Error).message}\n`);
    process.exit(1);
  },
);
