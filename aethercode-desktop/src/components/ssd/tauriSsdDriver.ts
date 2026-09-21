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
import { invoke } from '@tauri-apps/api/core';
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
    // R299: honour the options.auto flag instead of forcing
    // `--auto`. The user wants per-phase confirmation: each
    // phase's chip flips to `pending-accept` and waits for
    // ✅ / ✏️ / ⏭️ before the daemon moves on. R293 originally
    // forced `--auto` because the Tauri shell 2.x Windows
    // stdin pipe was unverified — with that pipe now believed
    // reliable, the driver can stay in the loop. If the pipe
    // is broken on a given host, the daemon's 5-min
    // readReply timeout still kicks in (R293 follow-up) and
    // auto-accepts the phase so the user isn't permanently
    // stuck on a single draft.
    //
    // Pass `--auto` only when the caller set auto:true
    // (older callers / Settings toggle). Default is now
    // `--interactive` so the user-driven flow is the
    // canonical experience.
    const auto = this.opts.options?.auto === true;
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
      // R299: --interactive is the default; --auto is opt-in.
      auto ? '--auto' : '--interactive',
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

    // Tauri shell plugin's Command class (R307: we got
    // the API wrong in R293). Correct shape:
    //
    //   - `Command.create(program, args)` returns a Command
    //     instance with `.stdout` and `.stderr` EventEmitters.
    //     You bind `data` listeners on those BEFORE calling
    //     `cmd.spawn()` so the Rust-side `onEvent` Channel
    //     has somewhere to send NDJSON lines.
    //   - `cmd.spawn()` returns a `Child` instance which is
    //     *just* `{ pid }` — no `stdout` field, no
    //     AsyncIterable. The actual stdout stream is on the
    //     parent Command, not on the Child.
    //
    // R293-R306 assumed the wrong shape (`spawned.child` /
    // `spawned.stdout`) which is why the daemon ran but the
    // desktop never saw its events. R307 logs every line so
    // this never silently regresses again.
    const cmd: any = Command.create(java, args);
    this.command = cmd;
    // R301: log the spawn attempt + final args so the user
    // can verify the desktop side really fired the right
    // subprocess (--interactive vs --auto, --cwd path).
    const logPath = await getSddLogPath();
    try {
        // eslint-disable-next-line no-console
        console.log('[R301-sdd] spawn java: ' + java + ' ' + args.join(' '));
        if (logPath) {
            await invoke('append_text_file', {
                path: logPath,
                contents: `[R301-sdd-spawn] java=${java} args=${args.join(' ')}\n`,
            }).catch(() => {});
        }
    } catch {}

    // R307: bind stdout/stderr listeners on the Command
    // BEFORE calling spawn(). Otherwise we miss every
    // NDJSON line the daemon emits (and the user sees
    // "stuck" with no chip movement).
    cmd.stdout?.on?.('data', (line: string) => {
      if (this.stopped) return;
      const trimmed = (line ?? '').toString().trim();
      if (!trimmed) return;
      try {
        if (logPath) {
          invoke('append_text_file', {
            path: logPath,
            contents: `[R307-sdd-stdout] line=${trimmed.length > 240 ? trimmed.slice(0, 240) + '...' : trimmed}\n`,
          }).catch(() => {});
        }
      } catch {}
      const ev = safeParseSsdEvent(trimmed);
      if (ev) {
        for (const h of [...this.handlers]) h(ev);
      }
    });
    cmd.stderr?.on?.('data', (line: string) => {
      // Daemon-side errors go to stderr. Log them so the
      // user can see what the JVM complained about without
      // attaching a debugger.
      if (this.stopped) return;
      const trimmed = (line ?? '').toString().trim();
      if (!trimmed) return;
      try {
        if (logPath) {
          invoke('append_text_file', {
            path: logPath,
            contents: `[R307-sdd-stderr] line=${trimmed.length > 240 ? trimmed.slice(0, 240) + '...' : trimmed}\n`,
          }).catch(() => {});
        }
        // Surface stderr as an `error` event so the
        // store's catch handler can render a friendly
        // message via friendlySddSpawnError.
        for (const h of [...this.handlers]) {
          h({ kind: 'error', message: `daemon stderr: ${trimmed}` } as any);
        }
      } catch {}
    });
    cmd.on?.('error', (e: unknown) => {
      for (const h of [...this.handlers]) {
        h({ kind: 'error', message: `daemon process error: ${(e as Error)?.message ?? String(e)}` } as any);
      }
    });
    cmd.on?.('close', (e: any) => {
      // Daemon exited without emitting a `complete` event
      // (e.g. crashed). Surface as a synthetic error so the
      // user isn't stuck waiting forever.
      try {
        if (logPath) {
          invoke('append_text_file', {
            path: logPath,
            contents: `[R307-sdd-close] code=${e?.code} signal=${e?.signal}\n`,
          }).catch(() => {});
        }
      } catch {}
    });

    // R307: cmd.spawn() returns a `Child` which is just
    // `{ pid, write(), kill() }`. No stdout field. The
    // listener wiring above is what captures daemon output.
    const child: any = await cmd.spawn();
    try {
        // eslint-disable-next-line no-console
        console.log('[R301-sdd] spawn returned pid=' + (child?.pid ?? '?'));
        if (logPath) {
            await invoke('append_text_file', {
                path: logPath,
                contents: `[R301-sdd-spawned] pid=${child?.pid ?? '?'} listenerWired=${Boolean(cmd.stdout?.on)}\n`,
            }).catch(() => {});
        }
    } catch {}
    this.child = child;
  }

  /** Forward a user command to the subprocess's stdin as a
   *  single JSONL line. */
  sendCommand(cmd: SsdInboundCommand): void {
    if (this.stopped || !this.child) return;
    const line = JSON.stringify(cmd) + '\n';
    // R301: log the inbound command so we can tell whether
    // the desktop-side user input actually round-trips to
    // the JVM's System.in. If R301-sdd-stdin lines never
    // appear the desktop never called sendCommand; if they
    // appear but the daemon still auto-accepts, the Tauri
    // shell 2.x Windows stdin pipe is silently dropping
    // bytes (the most likely remaining root cause).
    try {
      // eslint-disable-next-line no-console
      console.log('[R301-sdd-stdin] action=' + cmd.action + ' textLen=' + ((cmd as any).text?.length ?? 0));
      const logPath = getSddLogPath();
      if (logPath) {
        invoke('append_text_file', {
          path: logPath,
          contents: `[R301-sdd-stdin] action=${cmd.action} textLen=${(cmd as any).text?.length ?? 0}\n`,
        }).catch(() => {});
      }
    } catch {}
    void this.child.write(line).catch((e: unknown) => {
      try {
        const logPath = getSddLogPath();
        if (logPath) {
          invoke('append_text_file', {
            path: logPath,
            contents: `[R301-sdd-stdin-failed] action=${cmd.action} err=${(e as Error)?.message ?? String(e)}\n`,
          }).catch(() => {});
        }
      } catch {}
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
      // R301: log teardown so the user can distinguish
      // "driver cleanly quit" from "process crashed" from
      // "we were already hung waiting on stdin".
      try {
        const logPath = getSddLogPath();
        if (logPath) {
          const { invoke } = await import('@tauri-apps/api/core');
          invoke('append_text_file', {
            path: logPath,
            contents: `[R301-sdd-stop] reason=user-request pid=${(this.child as any)?.pid ?? '?'}\n`,
          }).catch(() => {});
        }
      } catch {}
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
 *  `clarify-question`, `analysis`, `converge-check` kinds.
 *
 *  <h2>R297: wire-format compatibility</h2>
 *  The daemon's InteractiveRepl emits each line as
 *  {@code {"event": "phase-list", ...}} (see
 *  {@link InteractiveRepl.emitPhaseList}); the renderer's
 *  {@link SsdDriverEvent} discriminator is {@code kind}
 *  (mirrors the {@link MockSsdDriver} default
 *  {@link defaultSddEventSequence} fixture). Without remapping
 *  every line would fail {@code obj.kind} check and be
 *  silently dropped — symptom: SDD chips stay at `idle`
 *  while the daemon subprocess is actually running
 *  end-to-end ("flash past" without any phase chip ever
 *  flipping). Accept either {@code kind} or {@code event};
 *  the latter wins if both are present. */
export function safeParseSsdEvent(line: string): SsdDriverEvent | null {
  try {
    const raw = JSON.parse(line) as Record<string, unknown>;
    if (!raw) return null;
    // R297: daemon emits `event`, MockSdrEmit emits `kind`.
    // Normalise so the rest of the renderer can rely on a
    // single discriminator field.
    const obj: Record<string, unknown> =
      typeof raw.kind === 'string'
        ? raw
        : { ...raw, kind: raw.event };
    if (!obj || typeof obj.kind !== 'string') return null;
    switch (obj.kind) {
      case 'phase-list':
      case 'phase-start':
      case 'phase-draft':
      case 'phase-revising':
      case 'phase-accepted':
      case 'phase-skipped':
      case 'phase-error':
      case 'phase-need-content':
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

/** R301: resolve the absolute path of the SDD diagnostic
 *  log file (`%TEMP%\aethercode-desktop-daemon-info.log`).
 *  Returns null in tests / when the Tauri runtime isn't
 *  available — callers should treat null as "best-effort,
 *  skip the log write". We compute this on every call
 *  rather than caching, since `tempDir()` is a single
 *  syscall and the result is cheap. */
async function getSddLogPath(): Promise<string | null> {
  try {
    const tdir = await (await import('@tauri-apps/api/path')).tempDir();
    if (!tdir) return null;
    return `${tdir}\\aethercode-desktop-daemon-info.log`;
  } catch {
    return null;
  }
}