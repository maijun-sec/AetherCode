/**
 * R292 — driver abstraction for the SDD subprocess. The
 * {@link SsdPanel} component reads events from this driver and
 * pushes inbound commands back. Three implementations:
 *
 * <ol>
 *   <li>{@link MockSsdDriver} — emits canned events from an
 *       in-memory queue. Used by tests + for local UI work when
 *       no jar is on disk yet. Trivially deterministic.</li>
 *   <li>{@link TauriSsdDriver} (see {@link ./tauriSsdDriver}) —
 *       spawns `java -jar aethercode.jar sdd <feature> "..."
 *       --interactive --cwd <cwd>` via the Tauri shell plugin,
 *       parses each stdout line as JSON, and forwards
 *       {@link SsdInboundCommand} via stdin.</li>
 * </ol>
 *
 * <p>The driver interface is intentionally tiny: feed it lines
 * (newline-delimited JSON events from the daemon's stdout) and
 * it parses each into a typed event; send lines back (the
 * user's accept / revise / skip / quit / clarify-answer /
 * converge-iterate command) and the driver forwards them to the
 * subprocess's stdin.
 *
 * <h2>R292 — Spec Kit 6 phases</h2>
 *
 * <p>Before R292 the driver spoke the R236 4-phase SSD protocol
 * (spec / design / tasks / dev). R292 upgrades to the Spec Kit
 * 6-phase SDD protocol (constitution / specify / plan / tasks /
 * implement / converge) plus 2 optional quality gates (clarify,
 * analyze) and a converge loop. The wire shapes follow the
 * daemon's `InteractiveRepl`:
 *
 * <h3>Outbound events</h3>
 * <ul>
 *   <li>{@link SsdDriverEvent#kind} = {@code "phase-list"} —
 *       emitted once on start; carries the resolved phase list
 *       (with {@code optional} per phase).</li>
 *   <li>{@code "phase-start"} — runner entered a phase.</li>
 *   <li>{@code "phase-draft"} — artefact written; carries path +
 *       preview (first 4 KB). Driver fetches full body via
 *       {@link SsdDriver#fetchDraft}.</li>
 *   <li>{@code "phase-accepted"} / {@code "phase-skipped"} /
 *       {@code "phase-error"} — per-phase terminal events.</li>
 *   <li>{@code "clarify-question"} — R292 new; carries
 *       {@code id}, {@code header}, {@code question}. The driver
 *       surfaces a Q&amp;A UI and replies with
 *       {@code clarify-answer}.</li>
 *   <li>{@code "analysis"} — R292 new; cross-artifact review
 *       report from the analyze quality gate.</li>
 *   <li>{@code "converge-check"} — R292 new; carries
 *       {@code iteration}, {@code converged}, {@code report}.</li>
 *   <li>{@code "complete"} / {@code "abort"} / {@code "error"}
 *       / {@code "log"} — terminal / diagnostic.</li>
 * </ul>
 *
 * <h3>Inbound commands</h3>
 * <ul>
 *   <li>{@code accept} / {@code revise} / {@code skip} /
 *       {@code quit} — same as R236.</li>
 *   <li>{@code clarify-answer} — R292 new; carries {@code id}
 *       matching the pending {@code clarify-question}.</li>
 *   <li>{@code converge-iterate} — R292 new; carries
 *       {@code text} (feedback) or empty string to accept the
 *       non-converged verdict.</li>
 * </ul>
 *
 * <p>The class names retain the {@code Ssd*} prefix for
 * historical reasons (the {@code ssd/} directory and
 * {@code ./driver} import path are referenced by the renderer
 * store); only the wire shapes are upgraded. The daemon side
 * has its own {@code InteractiveRepl} that emits the matching
 * JSON shapes.
 */

// ---------- Public types ----------

export type SsdPhaseState =
  | 'idle'
  | 'pending'
  | 'running'
  | 'pending-accept'
  | 'clarify-pending'
  | 'converge-pending'
  | 'done'
  | 'skipped'
  | 'failed';

/** shape of the daemon's outbound JSON events, narrowed to a
 *  discriminated union for type-safe pattern matching in
 *  {@link SsdPanel}. */
export type SsdDriverEvent =
  | { kind: 'phase-list'; feature: string; phases: SsdPhaseInfo[] }
  | { kind: 'phase-start'; phase: string; order: number; title: string; optional?: boolean }
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
      /** R309: daemon asks for the rendered markdown body
       *  of a phase. Driver forwards the prompts to an
       *  attached LLM (Mavis agent / manual paste) and
       *  replies with a {@code phase-content} command. */
      kind: 'phase-need-content';
      phase: string;
      systemPrompt: string;
      userPrompt: string;
      maxTokens: number;
    }
  | {
      kind: 'clarify-question';
      id: string;
      header: string;
      question: string;
    }
  | { kind: 'analysis'; phase: string; report: string }
  | {
      kind: 'converge-check';
      phase: string;
      iteration: number;
      converged: boolean;
      report: string;
    }
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
  /** R292: optional quality gates (clarify / analyze) or the
   *  converge loop render half-opacity in the chip strip. */
  optional?: boolean;
}

/** shape of the inbound commands the panel sends to the
 *  driver. Each maps to one JSON object the daemon reads from
 *  its stdin. */
export type SsdInboundCommand =
  | { action: 'accept' }
  | { action: 'revise'; text: string }
  | { action: 'skip' }
  | { action: 'quit' }
  | { action: 'clarify-answer'; id: string; answer: string }
  | { action: 'converge-iterate'; text: string }
  | { action: 'phase-content'; content: string };

/** abstract driver. Implementations:
 *  - {@link MockSsdDriver} for tests / dev mode
 *  - production: spawns the JVM subprocess (see {@link ./tauriSsdDriver}) */
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
  start(): void | Promise<void>;
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
 *   <li>tests in {@code SsdPanelR292.test.tsx} — emit a fixed
 *       event sequence, assert the panel renders the right
 *       chips + confirmation pane + clarify dialog + converge
 *       result;</li>
 *   <li>store's `startSsdFlow` for local dev mode — a canned
 *       6-phase run without spinning up the JVM.</li>
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
    for (let i = this.events.length - 1; i >= 0; i--) {
      const ev = this.events[i];
      if (ev.kind === 'phase-draft' && ev.path === path) {
        return ev.preview;
      }
    }
    return '';
  }
}

/** R292: build a canned 6-phase SDD event sequence the
 *  store can hand to {@link MockSsdDriver} when a real daemon
 *  isn't wired up. Covers constitution → specify → plan →
 *  tasks → implement → converge with a single revision on
 *  specify, a single clarify question after specify, and a
 *  converge-check that loops once before reporting converged.
 *
 *  <p>Used by the desktop store's `startSsdFlow` for dev mode
 *  (no JVM on PATH) so the chip strip + SddPanel can be
 *  exercised without standing up the daemon. */
export function defaultSddEventSequence(slug: string): SsdDriverEvent[] {
  return [
    {
      kind: 'phase-list',
      feature: slug,
      phases: [
        { id: 'constitution', order: 0, title: 'Constitution', optional: false },
        { id: 'specify', order: 1, title: 'Specify', optional: false },
        { id: 'clarify', order: 2, title: 'Clarify', optional: true },
        { id: 'plan', order: 3, title: 'Plan', optional: false },
        { id: 'analyze', order: 4, title: 'Analyze', optional: true },
        { id: 'tasks', order: 5, title: 'Tasks', optional: false },
        { id: 'implement', order: 6, title: 'Implement', optional: false },
        { id: 'converge', order: 7, title: 'Converge', optional: true },
      ],
    },
    {
      kind: 'phase-start',
      phase: 'constitution',
      order: 0,
      title: 'Constitution',
      optional: false,
    },
    {
      kind: 'phase-draft',
      phase: 'constitution',
      path: '.specify/memory/constitution.md',
      bytes: 1024,
      preview: '# Project Constitution\n\nThis is the bundled default constitution…',
    },
    { kind: 'phase-accepted', phase: 'constitution', revisionCount: 0 },
    { kind: 'phase-start', phase: 'specify', order: 1, title: 'Specify', optional: false },
    {
      kind: 'phase-draft',
      phase: 'specify',
      path: `.specify/specs/${slug}/spec.md`,
      bytes: 4096,
      preview: '# Feature Specification: ' + slug,
    },
    {
      kind: 'clarify-question',
      id: 'q1',
      header: 'Auth method',
      question: 'Which auth method should we use (email/password vs SSO)?',
    },
    { kind: 'phase-accepted', phase: 'specify', revisionCount: 1 },
    {
      kind: 'phase-start',
      phase: 'plan',
      order: 3,
      title: 'Plan',
      optional: false,
    },
    {
      kind: 'phase-draft',
      phase: 'plan',
      path: `.specify/specs/${slug}/plan.md`,
      bytes: 2048,
      preview: '# Implementation Plan: ' + slug,
    },
    { kind: 'phase-accepted', phase: 'plan', revisionCount: 0 },
    {
      kind: 'phase-start',
      phase: 'tasks',
      order: 5,
      title: 'Tasks',
      optional: false,
    },
    {
      kind: 'phase-draft',
      phase: 'tasks',
      path: `.specify/specs/${slug}/tasks.md`,
      bytes: 3072,
      preview: '# Tasks: ' + slug,
    },
    { kind: 'phase-accepted', phase: 'tasks', revisionCount: 0 },
    {
      kind: 'phase-start',
      phase: 'implement',
      order: 6,
      title: 'Implement',
      optional: false,
    },
    { kind: 'phase-accepted', phase: 'implement', revisionCount: 0 },
    {
      kind: 'phase-start',
      phase: 'converge',
      order: 7,
      title: 'Converge',
      optional: true,
    },
    {
      kind: 'converge-check',
      phase: 'converge',
      iteration: 1,
      converged: true,
      report: 'All FRs covered; no contradictions.',
    },
    {
      kind: 'complete',
      feature: slug,
      results: [
        { phaseId: 'constitution', path: '.specify/memory/constitution.md', revisions: 0 },
        { phaseId: 'specify', path: `.specify/specs/${slug}/spec.md`, revisions: 1 },
        { phaseId: 'plan', path: `.specify/specs/${slug}/plan.md`, revisions: 0 },
        { phaseId: 'tasks', path: `.specify/specs/${slug}/tasks.md`, revisions: 0 },
        { phaseId: 'implement', path: `.specify/specs/${slug}/logs/implement.log`, revisions: 0 },
        { phaseId: 'converge', path: `.specify/specs/${slug}/convergence.json`, revisions: 0 },
      ],
    },
  ];
}