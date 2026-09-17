/**
 * R281 — desktop SSD panel. Spawns `aethercode ssd --interactive`
 * via the {@link SsdDriver} abstraction and renders a TODO
 * list of phases plus a per-stage confirmation pane with
 * accept / modify buttons.
 *
 * <p>The driver interface is intentionally tiny: feed it lines
 * (newline-delimited JSON events from the daemon's stdout) and
 * it parses each into a typed event; send lines back (the
 * user's accept / revise command) and the driver forwards them
 * to the subprocess's stdin. This lets us swap a real subprocess
 * for a mock in tests without touching the panel logic.
 *
 * <p>UX (top to bottom):
 *
 * <pre>
 *   ┌─ TODO ────────────────────────────────┐
 *   │  ✓ spec      (1 revision)              │
 *   │  ● design    ← running...             │
 *   │  ○ tasks                                │
 *   │  ○ dev                                  │
 *   │  ──────────────────────────────       │
 *   │  [artifact preview pane — markdown]    │
 *   │                                         │
 *   │  ──────────────────────────────       │
 *   │  [✓ Accept]  [✎ Modify]                │
 *   │  ┌─ revision text ────────────────┐   │
 *   │  │ add NFR-3 about audit logging   │   │
 *   │  └────────────────────────────────┘   │
 *   │  [Apply Revision]                       │
 *   └─────────────────────────────────────────┘
 * </pre>
 *
 * The panel is mounted by {@code SettingsPage}'s SDD tab. It
 * also takes a {@link SsdDriver} prop so tests inject a canned
 * event source.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import type {
  SsdDriver,
  SsdDriverEvent,
  SsdInboundCommand,
  SsdPhaseState,
} from './driver';
import './SsdPanel.css';

/** wire shape of the panel. Mirrors the daemon's JSON events. */
interface PhaseChip {
  id: string;
  order: number;
  title: string;
  state: SsdPhaseState;
  revisionCount: number;
}

export interface SsdPanelProps {
  driver: SsdDriver;
  /** when the user closes the panel (× button). Wired to the
   *  parent route's onClose so we can show "are you sure?"
   *  confirmation later if a run is in flight. */
  onClose?: () => void;
  /** title to show in the header; defaults to "Spec-Driven Development". */
  title?: string;
}

export function SsdPanel({
  driver,
  onClose,
  title = 'Spec-Driven Development',
}: SsdPanelProps) {
  // TODO list state. Initially empty until we receive the
  // `phase-list` event. Keyed by phase id for O(1) updates.
  const [phases, setPhases] = useState<Record<string, PhaseChip>>({});
  const [phaseOrder, setPhaseOrder] = useState<string[]>([]);

  // The phase whose confirmation we are presenting to the user.
  // When non-null, the artifact preview pane + accept/modify
  // bar are visible.
  const [pendingPhase, setPendingPhase] = useState<string | null>(null);

  // The artifact content for the pending phase (full text).
  // We fetch it via the driver.fetchDraft(path) so the JSON
  // event stream stays compact (only a preview is sent on the
  // wire; the full content lives on disk).
  const [draftPath, setDraftPath] = useState<string | null>(null);
  const [draftText, setDraftText] = useState<string | null>(null);
  const [draftLoading, setDraftLoading] = useState(false);

  // Revision text the user types into the modify box.
  const [revision, setRevision] = useState('');

  // Free-form log messages from the daemon, kept for debugging.
  const [logs, setLogs] = useState<string[]>([]);

  // Final completion summary, when the daemon emits `complete`.
  const [completed, setCompleted] = useState<{
    feature: string;
    results: { phaseId: string; path: string | null; revisions: number }[];
  } | null>(null);

  // Aborted / errored state.
  const [abortedReason, setAbortedReason] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  // Subprocess lifecycle: null when not started, 'running' once
  // events start arriving, 'done' once we see `complete`/`abort`/
  // `error`.
  const [lifecycle, setLifecycle] = useState<
    'idle' | 'running' | 'done' | 'aborted' | 'errored'
  >('idle');

  // Subscribe to driver events. Re-subscribes only if the
  // driver instance changes (test-only path).
  useEffect(() => {
    const off = driver.onEvent((ev: SsdDriverEvent) => {
      switch (ev.kind) {
        case 'phase-list': {
          const next: Record<string, PhaseChip> = {};
          const order: string[] = [];
          for (const p of ev.phases) {
            next[p.id] = {
              id: p.id,
              order: p.order,
              title: p.title,
              state: 'pending',
              revisionCount: 0,
            };
            order.push(p.id);
          }
          setPhases(next);
          setPhaseOrder(order);
          setLifecycle('running');
          break;
        }
        case 'phase-start': {
          setPhases((prev) => ({
            ...prev,
            [ev.phase]: { ...prev[ev.phase], state: 'running' },
          }));
          break;
        }
        case 'phase-draft': {
          setPendingPhase(ev.phase);
          setDraftPath(ev.path);
          setDraftText(ev.preview);
          setDraftLoading(true);
          // Fetch the full draft from disk. The driver knows
          // how to do this — for subprocess it's a fetch()
          // call to the daemon's static-serve endpoint; for
          // tests it's an in-memory read.
          driver.fetchDraft(ev.path).then(
            (full) => {
              setDraftText(full);
              setDraftLoading(false);
            },
            () => setDraftLoading(false),
          );
          setPhases((prev) => ({
            ...prev,
            [ev.phase]: { ...prev[ev.phase], state: 'confirm-pending' },
          }));
          break;
        }
        case 'phase-revising': {
          setPhases((prev) => ({
            ...prev,
            [ev.phase]: { ...prev[ev.phase], state: 'running' },
          }));
          break;
        }
        case 'phase-accepted':
        case 'phase-skipped': {
          const nextState: SsdPhaseState = ev.kind === 'phase-skipped' ? 'skipped' : 'done';
          const nextRevisions = ev.kind === 'phase-accepted' ? ev.revisionCount : 0;
          setPhases((prev) => ({
            ...prev,
            [ev.phase]: {
              ...prev[ev.phase],
              state: nextState,
              revisionCount: nextRevisions,
            },
          }));
          setPendingPhase(null);
          setDraftPath(null);
          setDraftText(null);
          setRevision('');
          break;
        }
        case 'phase-error': {
          setPhases((prev) => ({
            ...prev,
            [ev.phase]: { ...prev[ev.phase], state: 'failed' },
          }));
          break;
        }
        case 'complete': {
          setCompleted({ feature: ev.feature, results: ev.results });
          setLifecycle('done');
          setPendingPhase(null);
          break;
        }
        case 'abort': {
          setAbortedReason(ev.reason);
          setLifecycle('aborted');
          setPendingPhase(null);
          break;
        }
        case 'error': {
          setErrorMessage(ev.message);
          setLifecycle('errored');
          setPendingPhase(null);
          break;
        }
        case 'log': {
          setLogs((prev) => [...prev, `[${ev.level}] ${ev.message}`]);
          break;
        }
      }
    });
    return off;
  }, [driver]);

  // When the panel mounts, ask the driver to start. The driver
  // is responsible for actually spawning the subprocess (or
  // emitting canned events in tests). We close the panel on
  // unmount.
  useEffect(() => {
    driver.start();
    return () => driver.stop();
  }, [driver]);

  const send = useCallback(
    (cmd: SsdInboundCommand) => {
      driver.sendCommand(cmd);
    },
    [driver],
  );

  // Stable order so React doesn't shuffle the chips on each
  // event. The `phaseOrder` array is set once and never mutated.
  const orderedPhases = useMemo(
    () =>
      phaseOrder
        .map((id) => phases[id])
        .filter((p): p is PhaseChip => Boolean(p)),
    [phases, phaseOrder],
  );

  return (
    <div className="ssd-panel" data-testid="ssd-panel">
      <header className="ssd-panel-header">
        <h1 className="ssd-panel-title">{title}</h1>
        <div className="ssd-panel-header-actions">
          {lifecycle === 'running' && (
            <button
              type="button"
              className="ssd-btn ssd-btn-danger"
              onClick={() => send({ action: 'quit' })}
              data-testid="ssd-quit"
            >
              Quit
            </button>
          )}
          {onClose && (
            <button
              type="button"
              className="ssd-btn ssd-btn-ghost"
              onClick={onClose}
              aria-label="Close"
              data-testid="ssd-close"
            >
              ×
            </button>
          )}
        </div>
      </header>

      <section className="ssd-panel-todo" aria-label="Phase progress">
        {orderedPhases.length === 0 && lifecycle === 'idle' && (
          <div className="ssd-empty">Waiting for daemon to announce the phase list…</div>
        )}
        {orderedPhases.map((p) => (
          <PhaseChipView key={p.id} chip={p} />
        ))}
      </section>

      {pendingPhase && draftPath && (
        <section
          className="ssd-panel-confirm"
          data-testid="ssd-confirm-pane"
          aria-label="Per-phase confirmation"
        >
          <h2 className="ssd-confirm-heading">
            {phases[pendingPhase]?.title ?? pendingPhase} — review
          </h2>
          <div className="ssd-confirm-path">
            <code>{draftPath}</code>
            <span className="ssd-confirm-bytes">
              {draftLoading ? 'loading full body…' : 'full body loaded'}
            </span>
          </div>
          <pre
            className="ssd-confirm-preview"
            data-testid="ssd-preview"
            aria-label="Artifact preview"
          >
            {draftText ?? ''}
          </pre>
          <div className="ssd-confirm-actions">
            <button
              type="button"
              className="ssd-btn ssd-btn-primary"
              onClick={() => send({ action: 'accept' })}
              data-testid="ssd-accept"
            >
              ✓ Accept &amp; continue
            </button>
            <button
              type="button"
              className="ssd-btn ssd-btn-secondary"
              onClick={() => send({ action: 'skip' })}
              data-testid="ssd-skip"
            >
              ↷ Skip phase
            </button>
          </div>
          <div className="ssd-confirm-modify">
            <label htmlFor="ssd-revision" className="ssd-confirm-modify-label">
              Modify — paste revisions, then click Apply:
            </label>
            <textarea
              id="ssd-revision"
              className="ssd-confirm-modify-input"
              data-testid="ssd-revision"
              value={revision}
              onChange={(e) => setRevision(e.target.value)}
              placeholder="add an NFR about audit logging"
              rows={3}
            />
            <button
              type="button"
              className="ssd-btn ssd-btn-secondary"
              onClick={() => send({ action: 'revise', text: revision })}
              disabled={!revision.trim()}
              data-testid="ssd-apply-revision"
            >
              Apply Revision
            </button>
          </div>
        </section>
      )}

      {completed && (
        <section
          className="ssd-panel-complete"
          data-testid="ssd-complete-pane"
          aria-label="Run complete"
        >
          <h2>✓ {completed.feature} — SSD complete</h2>
          <ul>
            {completed.results.map((r) => (
              <li key={r.phaseId}>
                <strong>{r.phaseId}</strong>
                {r.path ? <> · <code>{r.path}</code></> : null}
                {r.revisions > 0 ? <> · revised {r.revisions}×</> : null}
              </li>
            ))}
          </ul>
        </section>
      )}

      {abortedReason && (
        <section
          className="ssd-panel-aborted"
          data-testid="ssd-aborted-pane"
          aria-label="Run aborted"
        >
          <h2>Run aborted</h2>
          <p>{abortedReason}</p>
        </section>
      )}

      {errorMessage && (
        <section
          className="ssd-panel-error"
          data-testid="ssd-error-pane"
          aria-label="Run error"
        >
          <h2>Run error</h2>
          <p>{errorMessage}</p>
        </section>
      )}

      {logs.length > 0 && (
        <details className="ssd-panel-logs">
          <summary>Daemon log ({logs.length} lines)</summary>
          <pre>{logs.join('\n')}</pre>
        </details>
      )}
    </div>
  );
}

function PhaseChipView({ chip }: { chip: PhaseChip }) {
  const cls = `ssd-chip ssd-chip-${chip.state}`;
  const label = chipLabel(chip);
  return (
    <div
      className={cls}
      data-testid={`ssd-chip-${chip.id}`}
      data-state={chip.state}
      data-revisions={chip.revisionCount}
    >
      <span className="ssd-chip-icon">{label.icon}</span>
      <span className="ssd-chip-title">{chip.title}</span>
      <span className="ssd-chip-meta">{label.meta}</span>
    </div>
  );
}

function chipLabel(chip: PhaseChip): { icon: string; meta: string } {
  switch (chip.state) {
    case 'pending':
      return { icon: '○', meta: 'queued' };
    case 'running':
      return { icon: '●', meta: 'running…' };
    case 'confirm-pending':
      return { icon: '◐', meta: 'awaiting your input' };
    case 'done':
      return {
        icon: '✓',
        meta:
          chip.revisionCount > 0
            ? `done · revised ${chip.revisionCount}×`
            : 'done',
      };
    case 'skipped':
      return { icon: '↷', meta: 'skipped' };
    case 'failed':
      return { icon: '✗', meta: 'failed' };
  }
}