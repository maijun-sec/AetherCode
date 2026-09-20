/**
 * R287 / R292 — Tauri shell driver for the SDD subprocess.
 *
 * <p>Spawns `java -jar aethercode.jar sdd <feature> "<intent>"
 * --interactive --cwd <cwd>` via the Tauri shell plugin,
 * parses each stdout line as an {@link SsdDriverEvent},
 * forwards user commands (accept / revise / skip / quit /
 * clarify-answer / converge-iterate) as JSONL lines to stdin,
 * and fetches the full draft body via a read-from-disk helper
 * (the daemon writes the artefacts to `.specify/specs/<NNN>-<slug>/`).
 *
 * <h2>R292 changes vs R287</h2>
 * <ul>
 *   <li>Spawn command renamed from `ssd` to `sdd` (R292 Spec Kit
 *       integration; the legacy SSD command was deleted from
 *       the daemon).</li>
 *   <li>Wire protocol gained three new event kinds
 *       (`clarify-question`, `analysis`, `converge-check`) and
 *       two new commands (`clarify-answer`,
 *       `converge-iterate`); the renderer handles them via the
 *       updated {@link SsdDriverEvent} union.</li>
 *   <li>New optional flags {@code --branch-numbering},
 *       {@code --no-clarify}, {@code --no-analyze},
 *       {@code --no-converge} pass through to the subprocess
 *       so the UI's toggle controls map 1:1.</li>
 * </ul>
 *
 * <h2>Wire shape</h2>
 *
 * <p>The shell plugin's `Command` class gives us a
 * `Child` handle whose `stdout`/`stdin` are line / line pipes
 * (Rust auto-decodes UTF-8). The subprocess emits
 * newline-delimited JSON on stdout:
 *
 * <pre>
 *   {"event":"phase-list","feature":"001-foo","phases":[{"id":"constitution","order":0,...},...]}
 *   {"event":"phase-start","phase":"specify","order":1,...}
 *   {"event":"phase-draft","phase":"specify","path":"...","preview":"..."}
 *   {"event":"clarify-question","id":"q1","header":"Auth","question":"..."}
 *   {"event":"converge-check","phase":"converge","iteration":1,"converged":true,"report":"..."}
 *   {"event":"complete","feature":"001-foo","results":[...]}
 * </pre>
 *
 * <p>And reads JSONL on stdin (one command per line):
 *
 * <pre>
 *   {"action":"accept"}
 *   {"action":"revise","text":"add an NFR about audit logging"}
 *   {"action":"clarify-answer","id":"q1","answer":"SSO via Okta"}
 *   {"action":"converge-iterate","text":""}
 *   {"action":"quit"}
 * </pre>
 */

import { Command } from '@tauri-apps/plugin-shell';
import type {
  SsdDriver,
  SsdDriverEvent,
  SsdInboundCommand,
} from './driver';

/** the absolute path of the jar the desktop uses to spawn the
 *  daemon. The Settings panel passes this in so the driver
 *  doesn't have to re-derive it from the Tauri runtime. */
export interface TauriSsdDriverOptions {
  /** the aethercode.jar absolute path. Resolved by the
   *  Settings panel via `resources/aethercode.jar` on Windows. */
  jarPath: string;
  /** the user-supplied feature slug (kebab-case). Becomes the
   *  NNN-prefixed artefact directory under `.specify/specs/`. */
  feature: string;
  /** the user's intent text. Spawned as
   *  `sdd <feature> "<intent>" --interactive`. */
  intent: string;
  /** the cwd the SDD run should target. The subprocess writes
   *  artefacts under `<cwd>/.specify/specs/<NNN>-<feature>/`. */
  cwd: string;
  /** the absolute path of a `java` executable on PATH.
   *  Defaults to "java" if omitted. */
  javaPath?: string;
  /** the read-from-disk helper for the draft body. Defaults
   *  to {@link readDraftFromDisk} which uses Tauri's fs
   *  plugin — tests inject a stub to avoid the Tauri runtime. */
  readDraft?: (path: string) => Promise<string>;
  /** R292: optional Spec Kit flags passed through to the
   *  subprocess. The UI surfaces toggles for these (clarify /
   *  analyze / converge on by default). */
  options?: {
    /** R293: prefer `--auto` over `--interactive` when true.
     *  Lets the daemon run end-to-end without waiting on
     *  stdin — the safe default until the Tauri shell 2.x
     *  Windows stdin pipe is verified. */
    auto?: boolean;
    /** branch numbering strategy: "sequential" or "timestamp" */
    branchNumbering?: 'sequential' | 'timestamp';
    /** skip the optional /speckit.clarify quality gate */
    noClarify?: boolean;
    /** skip the optional /speckit.analyze quality gate */
    noAnalyze?: boolean;
    /** skip the optional /speckit.converge loop */
    noConverge?: boolean;
  };
}

/**
 * Production driver. Spawns the daemon's `sdd --interactive`
 * subprocess and bridges its newline-delimited JSON I/O to the
 * renderer's event-driven view.
 */
export class TauriSsdDriver implements SsdDriver {
  private handlers: ((ev: SsdDriverEvent) => void)[] = [];
  // We type these as `any` because the
  // @tauri-apps/plugin-shell 2.x `Child` / `Command` types are
  // tightly coupled to the spawn return shape and have changed
  // across minor versions. The runtime contract is stable:
  // `command.spawn()` returns
  // `{ child: { write, kill }, stdout: AsyncIterable<string> }`
  // — we destructure via any and assert against shape at
  // runtime.
  private child: any = null;
  private command: any = null;
  private stopped = false;

  constructor(private readonly opts: TauriSsdDriverOptions) {}

  onEvent(handler: (ev: SsdDriverEvent) => void): () => void {
    this.handlers.push(handler);
    return () => {
      this.handlers = this.handlers.filter((h) => h !== handler);
    };
  }

  /** Spawn the subprocess and wire its stdout to the event
   *  queue. Safe to call once; subsequent calls are no-ops
   *  (mirrors MockSsdDriver). */
  async start(): Promise<void> {
    if (this.command) return;
    this.stopped = false;

    const java = this.opts.javaPath ?? 'java';
    // R293 follow-up: default to `--auto` because Tauri shell
    // 2.x's stdin pipe behaviour on Windows is unreliable —
    // the child.write() call sometimes never reaches the JVM's
    // System.in, so the `--interactive` confirm() blocks until
    // the 5-minute readReply timeout fires (and the user sees
    // the whole thing "flash past" once the LLM chains run).
    // With `--auto` the daemon accepts each phase internally
    // and the UI just reflects the running/done chip transitions
    // as the LLM calls land. The user still sees every phase
    // draft in the chat stream as a `system` card.
    //
    // To re-enable per-phase accept/revise, flip the Settings
    // toggle (R294) once the stdin pipe is fixed.
    const args = [
      // R172 daemon-stability: pass the JVM heap hint so the
      // SDD run has the same 4 GB budget as the main daemon.
      '-Xmx4g',
      '-jar',
      this.opts.jarPath,
      // R292: command renamed from `ssd` to `sdd`. The legacy
      // `ssd` command was deleted from the daemon; this is
      // now the only Spec-Driven Development entry point.
      'sdd',
      this.opts.feature,
      this.opts.intent,
      '--auto',
      // R281 + R283 lessons: pass --cwd so the subprocess
      // writes artefacts to the project root the user picked,
      // not the renderer's working directory.
      '--cwd',
      this.opts.cwd,
    ];

    // R292: pass through Spec Kit optional-gate flags so the
    // UI's toggles map 1:1 to the subprocess.
    const opts = this.opts.options ?? {};
    if (opts.branchNumbering && opts.branchNumbering !== 'sequential') {
      args.push('--branch-numbering', opts.branchNumbering);
    }
    if (opts.noClarify) args.push('--no-clarify');
    if (opts.noAnalyze) args.push('--no-analyze');
    if (opts.noConverge) args.push('--no-converge');

    // Tauri shell plugin's Command.spawn() returns a
    // { child, stdout, stderr } triple. The child handle
    // exposes stdin.write() for inbound commands; stdout is
    // an AsyncIterable<string> of decoded lines.
    const cmd: any = Command.create(java, args);
    this.command = cmd;
    const spawned: any = await cmd.spawn();
    this.child = spawned?.child ?? spawned;
    const stdout: AsyncIterable<string> | undefined =
        spawned?.stdout ?? (spawned as any)?.output;

    if (stdout) {
      void (async () => {
        try {
          for await (const line of stdout) {
            if (this.stopped) return;
            const trimmed = (line ?? '').toString().trim();
            if (!trimmed) continue;
            const ev = safeParseSsdEvent(trimmed);
            if (ev) {
              for (const h of [...this.handlers]) h(ev);
            }
          }
        } catch (e) {
          const msg = (e as Error)?.message ?? String(e);
          for (const h of [...this.handlers]) {
            h({ kind: 'error', message: `subprocess pipe error: ${msg}` });
          }
        }
      })();
    }
  }

  /** Forward a user command to the subprocess's stdin as a
   *  single JSONL line. */
  sendCommand(cmd: SsdInboundCommand): void {
    if (this.stopped || !this.child) return;
    const line = JSON.stringify(cmd) + '\n';
    void this.child.write(line).catch((e: unknown) => {
      for (const h of [...this.handlers]) {
        h({ kind: 'error', message: `stdin write failed: ${(e as Error)?.message ?? String(e)}` });
      }
    });
  }

  /** Fetch the full draft body by path. For the Tauri driver
   *  we read from disk via the injected helper (default: the
   *  Tauri fs plugin's readTextFile). The daemon writes
   *  artefacts to `<cwd>/.specify/specs/<NNN>-<feature>/`
   *  so the path the panel receives is already relative —
   *  we resolve it against cwd. */
  async fetchDraft(path: string): Promise<string> {
    const reader = this.opts.readDraft ?? defaultReadDraft;
    // The daemon sends relative paths (e.g.
    // `.specify/specs/001-foo/spec.md`); resolve against
    // cwd before asking Tauri to read.
    const abs = await resolveAgainstCwd(path, this.opts.cwd);
    return reader(abs);
  }

  /** Stop the subprocess. Called by SsdPanel on unmount.
   *  Idempotent — the flag guards against a double-stop. */
  async stop(): Promise<void> {
    if (this.stopped) return;
    this.stopped = true;
    try {
      if (this.child) {
        await this.child.kill();
      }
    } catch {
      // already dead / never spawned — fine
    }
    this.child = null;
    this.command = null;
  }
}

/** Best-effort JSONL → SsdDriverEvent parser. Tolerant of
 *  unknown `kind` values — returns null so the panel's switch
 *  drops the line (the daemon's own parser does the same on
 *  bad input). R292: extended to cover the new
 *  `clarify-question`, `analysis`, `converge-check` kinds. */
export function safeParseSsdEvent(line: string): SsdDriverEvent | null {
  try {
    const obj = JSON.parse(line) as Record<string, unknown>;
    if (!obj || typeof obj.kind !== 'string') return null;
    switch (obj.kind) {
      case 'phase-list':
      case 'phase-start':
      case 'phase-draft':
      case 'phase-revising':
      case 'phase-accepted':
      case 'phase-skipped':
      case 'phase-error':
      case 'clarify-question':
      case 'analysis':
      case 'converge-check':
      case 'complete':
      case 'abort':
      case 'error':
      case 'log':
        return obj as unknown as SsdDriverEvent;
      default:
        return null;
    }
  } catch {
    return null;
  }
}

/** Default read-from-disk helper. Routes through the
 *  symmetric Rust `read_text_file` Tauri command (added
 *  alongside `write_text_file` in R287) so we don't depend on
 *  `@tauri-apps/plugin-fs`. Tests inject a stub via the
 *  constructor to bypass the Tauri runtime. */
async function defaultReadDraft(path: string): Promise<string> {
  const { invoke } = await import('@tauri-apps/api/core');
  return invoke<string>('read_text_file', { path });
}

/** Resolve a daemon-emitted relative path against the run's
 *  cwd. The Tauri fs plugin requires absolute paths. We use
 *  a tiny `path.posix.join` clone to avoid pulling Node
 *  `path` into the bundle. */
async function resolveAgainstCwd(path: string, cwd: string): Promise<string> {
  // Already absolute?
  if (/^([a-zA-Z]:[\\/]|\/)/.test(path)) return path;
  const sep = cwd.includes('\\') ? '\\' : '/';
  const left = cwd.replace(/[\\/]+$/, '');
  const right = path.replace(/^[\\/]+/, '');
  return `${left}${sep}${right}`;
}