/**
 * R287 — Tauri shell driver for the SSD subprocess.
 *
 * <p>Replaces the R281 {@link MockSsdDriver} for
 * production: spawns the daemon's `ssd --interactive`
 * command via `@tauri-apps/plugin-shell`, parses
 * each stdout line as a {@link SsdDriverEvent},
 * forwards user commands (accept / revise / skip /
 * quit) as JSONL lines to stdin, and fetches the
 * full draft body via a read-from-disk helper (the
 * daemon writes the artefacts to the project root
 * under `.aethercode/ssd/<feature>/<phase>.md`).
 *
 * <h2>Wire shape</h2>
 *
 * <p>The shell plugin's `Command` class gives us a
 * `Child` handle whose `stdout`/`stdin` are line / /
 * line pipes (Rust auto-decodes UTF-8). The subprocess
 * emits newline-delimited JSON on stdout:
 *
 * <pre>
 *   {"kind":"phase-list","feature":"foo","phases":[…]}
 *   {"kind":"phase-start","phase":"spec","order":1,…}
 *   {"kind":"phase-draft","phase":"spec","path":"…","preview":"…"}
 *   {"kind":"phase-accepted","phase":"spec","revisionCount":1}
 *   …
 *   {"kind":"complete","feature":"foo","results":[…]}
 * </pre>
 *
 * <p>And reads the same JSONL format on stdin
 * (one command per line):
 *
 * <pre>
 *   {"action":"accept"}
 *   {"action":"revise","text":"add an NFR about audit logging"}
 *   {"action":"skip"}
 *   {"action":"quit"}
 * </pre>
 *
 * <h2>Renderer's contract</h2>
 *
 * <p>The renderer's {@link SsdPanel} reads from the
 * driver interface (events + fetchDraft +
 * sendCommand + start/stop) and never knows whether
 * the backing implementation is the in-memory mock
 * or the real subprocess. Tests continue to inject
 * the {@link MockSsdDriver}; production code
 * constructs a {@link TauriSsdDriver} in
 * {@code SettingsPage}.
 */

import { Command } from '@tauri-apps/plugin-shell';
import type {
  SsdDriver,
  SsdDriverEvent,
  SsdInboundCommand,
} from './driver';

/** the absolute path of the jar the desktop
 *  uses to spawn the daemon. The Settings panel
 *  passes this in so the driver doesn't have to
 *  re-derive it from the Tauri runtime. */
export interface TauriSsdDriverOptions {
  /** the aethercode.jar absolute path. Resolved
   *  by the Settings panel via
   *  {@code resources/aethercode.jar} on
   *  Windows. */
  jarPath: string;
  /** the user-supplied feature slug (kebab-case,
   *  used as both the SSD feature name AND the
   *  on-disk artefact directory). */
  feature: string;
  /** the user's intent text. Spawned as
   *  `ssd <feature> "<intent>" --interactive`. */
  intent: string;
  /** the cwd the SSD run should target. The
   *  subprocess writes artefacts under
   *  `<cwd>/.aethercode/ssd/<feature>/…`. */
  cwd: string;
  /** the absolute path of a `java` executable on
   *  PATH. Defaults to "java" if omitted. */
  javaPath?: string;
  /** the read-from-disk helper for the draft body.
   *  Defaults to {@link readDraftFromDisk} which
   *  uses Tauri's fs plugin — tests can inject a
   *  stub to avoid the Tauri runtime. */
  readDraft?: (path: string) => Promise<string>;
}

/**
 * Production driver. Spawns the daemon's
 * `ssd --interactive` subprocess and bridges
 * its newline-delimited JSON I/O to the
 * renderer's event-driven view.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link start} builds the `java -jar …`
 *       command and spawns it via
 *       {@link Command.create}. The subprocess's
 *       stdout is line-streamed into the event
 *       queue;</li>
 *   <li>user clicks Accept / Modify / Quit in
 *       {@link SsdPanel} → {@link sendCommand} →
 *       JSONL write to stdin;</li>
 *   <li>the subprocess emits a `complete` event
 *       on stdout and exits. {@link stop} kills
 *       the child handle if the user closes the
 *       panel before then.</li>
 * </ol>
 *
 * <p>Read the {@link TauriSsdDriverOptions}
 * constructor arg for the contract.
 */
export class TauriSsdDriver implements SsdDriver {
  private handlers: ((ev: SsdDriverEvent) => void)[] = [];
  // We type these as `any` because the
  // @tauri-apps/plugin-shell 2.x `Child` /
  // `Command` types are tightly coupled to the
  // spawn return shape and have changed across
  // minor versions. The runtime contract is
  // stable: `command.spawn()` returns
  // `{ child: { write, kill }, stdout: AsyncIterable<string> }`
  // — we destructure via any and assert against
  // shape at runtime.
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

  /** Spawn the subprocess and wire its stdout to
   *  the event queue. Safe to call once; subsequent
   *  calls are no-ops (mirrors MockSsdDriver). */
  async start(): Promise<void> {
    if (this.command) return;
    this.stopped = false;

    const java = this.opts.javaPath ?? 'java';
    const args = [
      // R172 daemon-stability: pass the JVM heap
      // hint so the SSD run has the same 4 GB
      // budget as the main daemon. Without this
      // a multi-phase run with several tool
      // invocations can GC-starve the JVM and
      // stall the JSONL stream.
      '-Xmx4g',
      '-jar',
      this.opts.jarPath,
      'ssd',
      this.opts.feature,
      this.opts.intent,
      '--interactive',
      // R281 + R283 lessons: pass --cwd so the
      // subprocess writes artefacts to the
      // project root the user picked, not the
      // renderer's working directory.
      '--cwd',
      this.opts.cwd,
    ];

    // Tauri shell plugin's Command.spawn() returns
    // a { child, stdout, stderr } triple. The
    // child handle exposes stdin.write() for
    // inbound commands; stdout is an
    // AsyncIterable<string> of decoded lines.
    const cmd: any = Command.create(java, args);
    this.command = cmd;
    // The return type of spawn() in @tauri-apps/plugin-shell 2.x
    // is a `Child` (which exposes `write` / `kill`) plus
    // separate `stdout` / `stderr` AsyncIterables. The exact
    // shape depends on the plugin version; we destructure
    // via the loose {@code any} cast so the build works
    // against both 2.3.x and 2.4.x without forcing a tight
    // pin. The shape is stable enough at runtime (the
    // IPC payload is a fixed JSON shape).
    const spawned: any = await cmd.spawn();
    this.child = spawned?.child ?? spawned;
    const stdout: AsyncIterable<string> | undefined =
        spawned?.stdout ?? (spawned as any)?.output;

    // Stream stdout line-by-line. Tauri splits on
    // \n automatically so each iteration is one
    // daemon event.
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
          // Subprocess pipe error → surface to the
          // panel as an `error` event so the UI
          // shows the diagnostic. The renderer's
          // lifecycle flips to "errored" on the
          // error event.
          const msg = (e as Error)?.message ?? String(e);
          for (const h of [...this.handlers]) {
            h({ kind: 'error', message: `subprocess pipe error: ${msg}` });
          }
        }
      })();
    }
  }

  /** Forward a user command to the subprocess's
   *  stdin as a single JSONL line. The subprocess
   *  reads one command per line and the JSONL
   *  parser is line-oriented, so we explicitly
   *  append \n. */
  sendCommand(cmd: SsdInboundCommand): void {
    if (this.stopped || !this.child) return;
    const line = JSON.stringify(cmd) + '\n';
    void this.child.write(line).catch((e: unknown) => {
      for (const h of [...this.handlers]) {
        h({ kind: 'error', message: `stdin write failed: ${(e as Error)?.message ?? String(e)}` });
      }
    });
  }

  /** Fetch the full draft body by path. For the
   *  Tauri driver we read from disk via the
   *  injected helper (default: the Tauri fs
   *  plugin's readTextFile). The daemon writes
   *  artefacts to `<cwd>/.aethercode/ssd/<feature>/
   *  <phase>.md` so the path the panel receives
   *  is already absolute. */
  async fetchDraft(path: string): Promise<string> {
    const reader = this.opts.readDraft ?? defaultReadDraft;
    return reader(path);
  }

  /** Stop the subprocess. Called by SsdPanel on
   *  unmount. Idempotent — the flag guards against
   *  a double-stop. */
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

/** Best-effort JSONL → SsdDriverEvent parser.
 *  Tolerant of unknown `kind` values — returns
 *  null so the panel's switch drops the line (the
 *  daemon's own parser does the same on bad
 *  input). Exported so tests can exercise the
 *  parser in isolation. */
export function safeParseSsdEvent(line: string): SsdDriverEvent | null {
  try {
    const obj = JSON.parse(line) as Record<string, unknown>;
    if (!obj || typeof obj.kind !== 'string') return null;
    // The driver only knows the discriminated
    // union from driver.ts. Anything else is
    // silently dropped — same behaviour as the
    // daemon's InteractiveRepl on unknown kinds.
    switch (obj.kind) {
      case 'phase-list':
        return obj as unknown as SsdDriverEvent;
      case 'phase-start':
        return obj as unknown as SsdDriverEvent;
      case 'phase-draft':
        return obj as unknown as SsdDriverEvent;
      case 'phase-revising':
        return obj as unknown as SsdDriverEvent;
      case 'phase-accepted':
        return obj as unknown as SsdDriverEvent;
      case 'phase-skipped':
        return obj as unknown as SsdDriverEvent;
      case 'phase-error':
        return obj as unknown as SsdDriverEvent;
      case 'complete':
        return obj as unknown as SsdDriverEvent;
      case 'abort':
        return obj as unknown as SsdDriverEvent;
      case 'error':
        return obj as unknown as SsdDriverEvent;
      case 'log':
        return obj as unknown as SsdDriverEvent;
      default:
        return null;
    }
  } catch {
    return null;
  }
}

/** Default read-from-disk helper. Routes through
 *  the symmetric Rust `read_text_file` Tauri
 *  command (added alongside `write_text_file` in
 *  R287) so we don't depend on `@tauri-apps/plugin-fs`.
 *  Tests inject a stub via the constructor to
 *  bypass the Tauri runtime. */
async function defaultReadDraft(path: string): Promise<string> {
  // Tauri 2 invokes a Rust command by name via
  // `@tauri-apps/api/core`. The Rust side
  // signature is `read_text_file(path: String)`
  // and returns `Result<String, String>`.
  const { invoke } = await import('@tauri-apps/api/core');
  return invoke<string>('read_text_file', { path });
}