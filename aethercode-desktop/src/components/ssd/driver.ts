/**
 * R281 — driver abstraction for the SSD subprocess. The
 * {@link SsdPanel} component reads events from this driver and
 * pushes inbound commands back. Two implementations:
 *
 * <ol>
 *   <li>{@link MockSsdDriver} — emits canned events from an
 *       in-memory queue. Used by tests + for local UI work when
 *       no jar is on disk yet. Trivially deterministic.</li>
 *   <li>(future) TauriSsdDriver — spawns `java -jar aethercode.jar
 *       ssd <feature> "..." --interactive --cwd <cwd>` via
 *       {@code @tauri-apps/plugin-shell}, parses each stdout
 *       line as JSON, and forwards {@link SsdInboundCommand} via
 *       stdin. Wired in a follow-up round; the driver interface
 *       here is its contract.</li>
 * </ol>
 *
 * <h2>Wire contract</h2>
 * <p>The driver wraps the daemon's newline-delimited JSON
 * protocol (see {@code InteractiveRepl.java}). Events are parsed
 * lazily — malformed lines are silently dropped, matching the
 * daemon's behaviour on bad input.
 */

// ---------- Public types ----------

export type SsdPhaseState =
  | 'pending'
  | 'running'
  | 'confirm-pending'
  | 'done'
  | 'skipped'
  | 'failed';

/** shape of the daemon's outbound JSON events, narrowed to a
 *  discriminated union for type-safe pattern matching in
 *  {@link SsdPanel}. */
export type SsdDriverEvent =
  | { kind: 'phase-list'; feature: string; phases: SsdPhaseInfo[] }
  | { kind: 'phase-start'; phase: string; order: number; title: string }
  | {
      kind: 'phase-draft';
      phase: string;
      path: string;
      bytes: number;
      preview: string;
    }
  | {
      kind: 'phase-revising';
      phase: string;
      revision: string;
    }
  | { kind: 'phase-accepted'; phase: string; revisionCount: number }
  | { kind: 'phase-skipped'; phase: string; reason: string }
  | { kind: 'phase-error'; phase: string; message: string }
  | {
      kind: 'complete';
      feature: string;
      results: { phaseId: string; path: string | null; revisions: number }[];
    }
  | { kind: 'abort'; reason: string }
  | { kind: 'error'; message: string }
  | { kind: 'log'; level: string; message: string };

export interface SsdPhaseInfo {
  id: string;
  order: number;
  title: string;
}

/** shape of the inbound commands the panel sends to the
 *  driver. Each maps to one JSON object the daemon reads from
 *  its stdin. */
export type SsdInboundCommand =
  | { action: 'accept' }
  | { action: 'revise'; text: string }
  | { action: 'skip' }
  | { action: 'quit' };

/** abstract driver. Implementations:
 *  - {@link MockSsdDriver} for tests / dev mode
 *  - production: spawns the JVM subprocess (next round) */
export interface SsdDriver {
  /** Subscribe to inbound events. Returns a teardown fn. */
  onEvent(handler: (ev: SsdDriverEvent) => void): () => void;
  /** Fetch the full body of a draft artefact by its on-disk
   *  path. For the real driver this is a fetch() call; for the
   *  mock it's an in-memory read. */
  fetchDraft(path: string): Promise<string>;
  /** Send an inbound command. For the real driver this
   *  writes a JSONL line to stdin; for the mock it pushes onto
   *  a queue the test asserts against. */
  sendCommand(cmd: SsdInboundCommand): void;
  /** Begin the run. Called once by the panel on mount.
   *  Implementations spawn the subprocess / start the canned
   *  event loop. */
  start(): void;
  /** Stop the run. Called once on unmount. Implementations
   *  kill the subprocess / drain the mock queue. */
  stop(): void;
}

// ---------- Mock driver ----------

/**
 * In-process driver that plays back a sequence of events from a
 * queue the caller passes in. Used by:
 *
 * <ul>
 *   <li>tests in {@code SsdPanelR281.test.tsx} — emit a fixed
 *       event sequence, assert the panel renders the right
 *       chips + confirmation pane;</li>
 *   <li>dev-mode "demo" — a future story can add a button in the
 *       settings page that plays a canned spec-design-tasks-dev
 *       run without spinning up the JVM, useful for design
 *       review of the panel without an LLM.</li>
 * </ul>
 */
export class MockSsdDriver implements SsdDriver {
  private handlers: ((ev: SsdDriverEvent) => void)[] = [];
  private commands: SsdInboundCommand[] = [];
  private started = false;
  private stopped = false;
  private draftStore: Record<string, string> = {};

  constructor(
    private readonly events: SsdDriverEvent[],
    /** optional map of {path → full body} used by
     *  {@link fetchDraft}. When omitted, returns the
     *  `preview` field of the matching `phase-draft`. */
    drafts?: Record<string, string>,
    /**
     * R289: optional per-event delay in ms. When set,
     * the mock driver emits one event every
     * {@code eventDelay} ms (instead of all of them in
     * one synchronous tick). The renderer uses a
     * non-zero delay (~250 ms) so the user can watch
     * each phase chip flip — a 0 ms run completes in
     * <1 ms and the phase bar collapses before the
     * user perceives anything. Tests that want a
     * deterministic run pass 0 (the default). */
    private readonly eventDelay: number = 0,
  ) {
    if (drafts) this.draftStore = { ...drafts };
  }

  onEvent(handler: (ev: SsdDriverEvent) => void): () => void {
    this.handlers.push(handler);
    return () => {
      this.handlers = this.handlers.filter((h) => h !== handler);
    };
  }

  /** Test hook: inspect the commands sent so far. */
  getCommands(): readonly SsdInboundCommand[] {
    return this.commands;
  }

  /** Test hook: register a full-body draft for {@link fetchDraft}. */
  registerDraft(path: string, body: string): void {
    this.draftStore[path] = body;
  }

  sendCommand(cmd: SsdInboundCommand): void {
    if (this.stopped) return;
    this.commands.push(cmd);
  }

  start(): void {
    if (this.started) return;
    this.started = true;
    // R289: when eventDelay is 0 (default, used by
    // tests) we fire the full sequence in one tick so
    // a unit test can assert the final state
    // synchronously. When eventDelay > 0 (the
    // interactive path) we schedule one event per
    // delay, matching how the real daemon streams
    // events from its InteractiveRepl. Either way, we
    // defer the very first event to next-tick so
    // consumers can wire onEvent before events fire
    // (mirrors a real subprocess whose first event
    // arrives shortly after spawn, not
    // synchronously).
    const fireAll = () => {
      if (this.stopped) return;
      for (const ev of this.events) {
        if (this.stopped) return;
        for (const h of [...this.handlers]) h(ev);
      }
    };
    if (this.eventDelay <= 0) {
      setTimeout(fireAll, 0);
      return;
    }
    let i = 0;
    const tick = () => {
      if (this.stopped || i >= this.events.length) return;
      for (const h of [...this.handlers]) h(this.events[i]);
      i++;
      if (i < this.events.length) setTimeout(tick, this.eventDelay);
    };
    setTimeout(tick, 0);
  }

  stop(): void {
    this.stopped = true;
  }

  async fetchDraft(path: string): Promise<string> {
    if (Object.prototype.hasOwnProperty.call(this.draftStore, path)) {
      return this.draftStore[path];
    }
    // Find the most recent `phase-draft` for this path and
    // return its preview. Useful when tests don't register a
    // separate body.
    for (let i = this.events.length - 1; i >= 0; i--) {
      const ev = this.events[i];
      if (ev.kind === 'phase-draft' && ev.path === path) {
        return ev.preview;
      }
    }
    return '';
  }
}