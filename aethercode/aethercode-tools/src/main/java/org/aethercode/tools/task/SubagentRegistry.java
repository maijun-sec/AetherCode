package org.aethercode.tools.task;

import org.aethercode.core.tool.Tool;
import org.aethercode.tasks.Task;
import org.aethercode.tasks.TaskRegistry;
import org.aethercode.tasks.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * in-process registry of running + recently-finished
 * subagent jobs. Modelled on {@code BashJobRegistry}
 * (which tracks background bash jobs) — same pattern, same
 * lifecycle hooks, same job-id scheme.
 *
 * <p>Each entry is a {@link SubagentJob} carrying the job id,
 * the original sub-task id, the prompt the parent sent, the
 * role, the status, the captured final text, and the
 * start/finish timestamps. The registry keeps finished
 * jobs for a short window (bounded by
 * {@link #MAX_FINISHED_JOBS}) so the parent can poll for
 * results a few turns later without losing them.
 *
 * <p>The registry is process-singleton — there is one
 * engine per daemon, and the registry lives alongside
 * {@code TaskRegistry}. A future R-round can move it to
 * a session-scoped service if the engine becomes
 * multi-tenant.
 */
public final class SubagentRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(SubagentRegistry.class);

    /** Bound on the number of recently-finished jobs we
     *  keep. When exceeded, the oldest entry is evicted.
     *  Matches {@code BashJobRegistry} convention. */
    public static final int MAX_FINISHED_JOBS = 64;
    /** per-job audit log cap. Each lifecycle
     *  transition appends one entry; the oldest is
     *  evicted when the cap is hit. 32 entries cover
     *  the worst-case long-running job (many re-attach
     *  attempts + cancel + final) without unbounded
     *  growth. */
    public static final int MAX_AUDIT_ENTRIES = 32;

    private static final SubagentRegistry INSTANCE = new SubagentRegistry();

    public static SubagentRegistry instance() { return INSTANCE; }

    private final AtomicInteger idCounter = new AtomicInteger();
    private final Map<String, SubagentJob> running = new ConcurrentHashMap<>();
    /** background worker thread for each running job,
     *  so {@link #cancel(String)} can actually interrupt the
     *  chat-client stream. Cleared by the daemon thread on
     *  natural exit (markCompleted/markFailed) and on
     *  cancellation. Without this map, cancel() would only
     *  update the status — the worker would keep running
     *  until the LLM reply came back. */
    private final Map<String, Thread> runningThreads = new ConcurrentHashMap<>();
    /** Insertion-ordered so the eviction loop drops the
     *  oldest finished job first. */
    private final Map<String, SubagentJob> finished = new LinkedHashMap<>();
    /** process-scoped listener list. The registry
     *  fires a {@link SubagentEvent} on every state
     *  transition (new running, completed, failed,
     *  cancelled). Listeners are invoked on the caller's
     *  thread — the daemon thread for background jobs, or
     *  the engine thread for foreground dispatches. They
     *  must be quick (no blocking I/O); a slow listener
     *  would delay the next job the engine wants to start.
     *  Mirrors the {@code BashJobRegistry} convention of
     *  "register then notify". */
    private final CopyOnWriteArrayList<Consumer<SubagentEvent>> listeners =
            new CopyOnWriteArrayList<>();

    private SubagentRegistry() {}

    /** One running or recently-finished subagent. */
    public static final class SubagentJob {
        public final String jobId;
        public final String taskId;
        public final String prompt;
        public final String role;
        /** the engine session that owns this
         *  subagent. The TUI/desktop filter events by
         *  this so a multi-session daemon doesn't bleed
         *  subagent noise from one session into
         *  another's UI. Empty / null for jobs registered
         *  previously-D (the renderer treats those as
         *  "show to every session" for backward
         *  compatibility — a single-session daemon sees
         *  no difference). */
        public final String sessionId;
        public final long startedAtMs;
        public volatile long finishedAtMs;     // 0 while running
        public volatile Status status;
        public volatile String resultText;     // captured on completion
        public volatile String error;          // captured on failure
        /** human-readable reason for a CANCELLED
         *  transition. Set by {@link
         *  SubagentRegistry#cancel(String, String)}; the
         *  reason is also appended to {@link #auditEntries}
         *  and published on the {@link SubagentEvent} so
         *  the UI can show "cancelled by user (timeout)"
         *  instead of a bare "cancelled". {@code null} /
         *  empty for naturally-completed or failed
         *  transitions. */
        public volatile String cancelReason;
        /** most recent streaming partial result.
         *  Updated by {@link
         *  SubagentRegistry#updatePartial(String, String)}
         *  as the worker streams tokens from the LLM.
         *  Cleared by markCompleted (the final result
         *  takes over) and by markFailed. The TUI /
         *  desktop SubagentPanel uses this to show a
         *  live preview of the in-flight work without
         *  waiting for the worker to finish. Empty
         *  for jobs that have not started streaming
         *  yet. */
        public volatile String partialResult;
        /** per-job lifecycle log. Each transition
         *  (REGISTER, ATTACH_THREAD, CANCEL, COMPLETE,
         *  FAIL) appends one entry. Append-only and
         *  thread-safe under the registry's lock (the
         *  same lock that already serialises status
         *  transitions). Exposed via
         *  {@link SubagentRegistry#auditLog(String)} so
         *  debug / audit tools can reconstruct the
         *  sequence of events leading up to a job's
         *  terminal state. Bounded by the same
         *  {@link #MAX_AUDIT_ENTRIES} cap to keep
         *  long-running jobs from accumulating
         *  unbounded log entries. */
        private final java.util.List<AuditEntry> auditEntries =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        public enum Status { RUNNING, COMPLETED, FAILED, CANCELLED }

        SubagentJob(String jobId, String taskId, String prompt, String role, String sessionId) {
            this.jobId = jobId;
            this.taskId = taskId;
            this.prompt = prompt;
            this.role = role == null ? "general-purpose" : role;
            this.sessionId = sessionId == null ? "" : sessionId;
            this.startedAtMs = System.currentTimeMillis();
            this.status = Status.RUNNING;
            appendAudit("REGISTER", "role=" + this.role);
        }

        /** Append an entry to the job's audit log. Called
         *  from inside the registry's lock (status
         *  transitions are already serialised there). The
         *  bounded list evicts the oldest entry when the
         *  cap is hit; this matches the {@code
         *  MAX_FINISHED_JOBS} convention. */
        void appendAudit(String action, String details) {
            long now = System.currentTimeMillis();
            auditEntries.add(new AuditEntry(now, action,
                    details == null ? "" : details));
            while (auditEntries.size() > MAX_AUDIT_ENTRIES) {
                auditEntries.remove(0);
            }
        }

        /** Snapshot of the audit log (newest last). */
        public List<AuditEntry> auditSnapshot() {
            synchronized (auditEntries) {
                return List.copyOf(auditEntries);
            }
        }

        public long elapsedMs() {
            long end = finishedAtMs > 0 ? finishedAtMs : System.currentTimeMillis();
            return end - startedAtMs;
        }
    }

    /** per-job audit entry. {@code action} is one
     *  of the canonical verbs (REGISTER / ATTACH_THREAD /
     *  CANCEL / COMPLETE / FAIL) so log scrapers can
     *  filter without parsing free-form {@code details}.
     *  {@code details} carries the action-specific
     *  payload (role, reason, error message, etc.). */
    public record AuditEntry(long atMs, String action, String details) {}

    /** state-transition payload fired to every
     *  registered listener. Carries enough to render a
     *  one-line status without re-querying the registry —
     *  the TUI uses this to update the status bar in
     *  real time as background subagents start and
     *  finish. prior round adds {@code sessionId} so the
     *  renderer can drop events from other sessions.
     * prior round adds {@code result} — the captured final
     *  text on COMPLETED — so the TUI/desktop
     *  SubagentPanel can offer an "insert into input"
     *  action without an extra round-trip. Empty
     *  string for non-COMPLETED transitions and for
     *  COMPLETED jobs whose result was never captured. */
    public record SubagentEvent(
            String jobId,
            String role,
            SubagentJob.Status status,
            long elapsedMs,
            String summary,
            long atMs,
            String sessionId,
            String result,
            /** cancellation reason (only meaningful
             *  when {@code status == CANCELLED}). Empty
             *  for all other transitions and for
             *  cancellations issued without a reason. The
             *  wire payload always includes the field
             *  (so consumers can rely on its presence)
             *  but TUI/desktop renderers should only
             *  surface it for the CANCELLED case. */
            String reason,
            /** most recent streaming partial
             *  result. Only meaningful for RUNNING
             *  transitions (the partial text the
             *  worker has produced so far). Empty for
             *  every other transition. The TUI /
             *  desktop SubagentPanel uses this to show
             *  a live preview of in-flight work
             *  without waiting for the terminal
             *  event. The field is always present on
             *  the wire so consumers can rely on its
             *  presence. */
            String partialResult) {

        /** Convenience: was the transition a terminal one
         *  (the job no longer accepts new work)? */
        public boolean isTerminal() {
            return status == SubagentJob.Status.COMPLETED
                || status == SubagentJob.Status.FAILED
                || status == SubagentJob.Status.CANCELLED;
        }
    }

    /** Register a new job and return its id. */
    public synchronized String register(String taskId, String prompt, String role) {
        return register(taskId, prompt, role, "");
    }

    /** register a new job, attributing it to a
     *  specific engine session. The sessionId is carried
     *  on the {@link SubagentEvent} so the renderer can
     *  filter events for its own session. {@code null} or
     *  empty sessionId is preserved as empty (matches the
     *  legacy-D wire shape; a single-session daemon
     *  treats an empty sessionId as "the only session"). */
    public synchronized String register(String taskId, String prompt, String role, String sessionId) {
        String id = "sag-" + idCounter.incrementAndGet();
        SubagentJob j = new SubagentJob(id, taskId, prompt, role, sessionId);
        running.put(id, j);
        // SubagentEvent now carries a reason field
        // (empty for non-cancellation transitions). Wire
        // shape is additive — existing consumers that
        // ignore the field keep working.
        // the new partialResult field is also
        // present on every event. Empty on the initial
        // RUNNING transition; the worker will update
        // it as it streams.
        fireChange(new SubagentEvent(id, j.role, SubagentJob.Status.RUNNING,
                0L, summary(j), System.currentTimeMillis(), j.sessionId, "", "", ""));
        return id;
    }

    /** attach the daemon thread that runs this
     *  background subagent so {@link #cancel(String)} can
     *  interrupt it. Called immediately after {@link Thread#start()}.
     *  Stored under {@code jobId}; cleared by the natural
     *  terminal transitions (markCompleted / markFailed /
     *  markCancelled) and by cancel() itself.
     *
     *  <p>The thread reference is intentionally a soft
     *  pointer — if the JVM shuts down before cancel() is
     *  called, the entry is harmless. A future R-round can
     *  swap to a {@code WeakReference} if memory pressure
     *  becomes a concern; for the small number of
     *  in-flight subagents typical of a single session
     *  this is fine. prior round: also appends an
     *  ATTACH_THREAD entry to the job's audit log so the
     *  full sequence is reconstructible. */
    public synchronized void attachThread(String jobId, Thread thread) {
        if (jobId == null || thread == null) return;
        SubagentJob j = running.get(jobId);
        if (j == null) return;  // already finished; ignore
        runningThreads.put(jobId, thread);
        j.appendAudit("ATTACH_THREAD", thread.getName());
    }

    /** Mark a job completed. The captured {@code result}
     *  is the assistant's final text (already trimmed).
     * the result is also published on the
     *  {@link SubagentEvent} so the TUI/desktop panel
     *  can offer an "insert into input" action without
     *  an extra round-trip. Truncated to {@link
     *  #MAX_RESULT_CHARS} on the wire to keep the
     *  notification payload small; the full result
     *  remains in {@link SubagentJob#resultText} for
     *  any consumer that wants the unabridged text
     *  (e.g. a future "open result" panel). prior round: a
     *  COMPLETE entry is appended to the audit log;
     *  the event carries an empty reason. */
    public synchronized void markCompleted(String jobId, String result) {
        SubagentJob j = running.remove(jobId);
        if (j == null) return;
        runningThreads.remove(jobId);
        j.resultText = result;
        j.finishedAtMs = System.currentTimeMillis();
        j.status = SubagentJob.Status.COMPLETED;
        // clear the partial slot — the final
        // result supersedes the streaming preview.
        j.partialResult = "";
        j.appendAudit("COMPLETE", "result=" + truncate(result, 80));
        moveToFinished(j);
        fireChange(new SubagentEvent(jobId, j.role, SubagentJob.Status.COMPLETED,
                j.elapsedMs(), summary(j), j.finishedAtMs, j.sessionId,
                truncateResult(result), "", ""));
    }

    /** Mark a job failed. prior round: a FAIL entry is appended
     *  to the audit log with the error message. */
    public synchronized void markFailed(String jobId, String error) {
        SubagentJob j = running.remove(jobId);
        if (j == null) return;
        runningThreads.remove(jobId);
        j.error = error;
        j.finishedAtMs = System.currentTimeMillis();
        j.status = SubagentJob.Status.FAILED;
        // clear the partial slot on failure too.
        j.partialResult = "";
        j.appendAudit("FAIL", error == null ? "" : error);
        moveToFinished(j);
        // include the error in the result slot
        // so the panel can render "FAILED: <message>"
        // and the user can decide whether to retry.
        fireChange(new SubagentEvent(jobId, j.role, SubagentJob.Status.FAILED,
                j.elapsedMs(), summary(j), j.finishedAtMs, j.sessionId,
                error == null ? "" : error, "", ""));
    }

    /** Mark a job cancelled. The sub-task stays in the
     *  task registry with the status the caller set; we
     *  just note the cancellation here for status polls.
     * deprecated in favour of the
     *  {@link #cancel(String, String)} overload that
     *  records a reason. Kept as a no-reason convenience
     *  for internal callers (the engine shutting down,
     *  a test fixture) that don't have a reason to
     *  record. */
    public synchronized void markCancelled(String jobId) {
        markCancelled(jobId, "");
    }

    /** mark a job cancelled with a human-readable
     *  reason. The reason is stored on the job
     *  ({@link SubagentJob#cancelReason}), appended to
     *  the audit log, and published on the
     *  {@link SubagentEvent} so the UI can show
     *  "cancelled by user (timeout)" instead of a bare
     *  "cancelled" string. {@code null} / empty reason
     *  is stored as the empty string for stable
     *  serialization. */
    public synchronized void markCancelled(String jobId, String reason) {
        SubagentJob j = running.remove(jobId);
        if (j == null) return;
        runningThreads.remove(jobId);
        j.finishedAtMs = System.currentTimeMillis();
        j.status = SubagentJob.Status.CANCELLED;
        j.cancelReason = reason == null ? "" : reason;
        // clear the partial slot on cancel.
        j.partialResult = "";
        j.appendAudit("CANCEL", "reason=" + j.cancelReason);
        moveToFinished(j);
        fireChange(new SubagentEvent(jobId, j.role, SubagentJob.Status.CANCELLED,
                j.elapsedMs(), summary(j), j.finishedAtMs, j.sessionId, "", j.cancelReason, ""));
    }

    /** stream a partial result for a still-running
     *  job. The text is stored on the {@link SubagentJob}
     *  and published on a RUNNING event so the TUI
     *  SubagentPanel can show a live preview of the
     *  worker's in-flight output without waiting for
     *  the terminal event. The text is truncated to
     *  {@link #MAX_PARTIAL_CHARS} on the wire to keep
     *  the notification payload bounded; the full
     *  text remains on the job for any consumer that
     *  wants the unabridged preview (a future
     *  "expand" button on the panel, say).
     *
     *  <p>This is a no-op when the job is no longer in
     *  the {@code running} map (a stream chunk that
     *  arrives after a natural terminal transition is
     *  dropped — the worker is already winding down
     *  and the user has already seen the final
     *  result). The no-op keeps the registry from
     *  resurrecting a finished job just because one
     *  late event slipped through.
     *
     *  <p>Best-effort: the listener is called on the
     *  caller's thread (the engine's chat-client
     *  stream consumer). Slow listeners would back up
     *  the LLM stream; the worker should fire this
     *  at a reasonable cadence (every N tokens or
     *  every M ms) rather than per token.
     */
    public synchronized void updatePartial(String jobId, String text) {
        if (jobId == null) return;
        SubagentJob j = running.get(jobId);
        if (j == null) return;  // already finished; drop
        j.partialResult = text == null ? "" : text;
        // also append a PARTIAL entry to the
        // audit log so a debug dump shows the streaming
        // history. The log is bounded so a long-running
        // streaming job cannot accumulate unbounded
        // entries.
        String truncated = truncatePartial(j.partialResult);
        j.appendAudit("PARTIAL", "chars=" + j.partialResult.length());
        fireChange(new SubagentEvent(jobId, j.role, SubagentJob.Status.RUNNING,
                j.elapsedMs(), summary(j), System.currentTimeMillis(),
                j.sessionId, "", "", truncated));
    }

    /** cap on the partial text we publish on
     *  the wire. Same as the result cap (4 KB) — the
     *  TUI panel shows a preview, the user does not
     *  scroll through a 200 KB streaming dump. */
    public static final int MAX_PARTIAL_CHARS = 4096;

    private static String truncatePartial(String s) {
        if (s == null) return "";
        if (s.length() <= MAX_PARTIAL_CHARS) return s;
        return s.substring(s.length() - MAX_PARTIAL_CHARS) + "…";
    }

    /** cap on the result text we publish on the
     *  wire notification. 4 KB is enough for the
     *  TUI/desktop "insert into input" action (the
     *  user edits before sending anyway) and keeps the
     *  notification payload bounded. The full result
     *  stays on the {@link SubagentJob} record for any
     *  consumer that wants the unabridged text. */
    public static final int MAX_RESULT_CHARS = 4096;

    private static String truncateResult(String s) {
        if (s == null) return "";
        if (s.length() <= MAX_RESULT_CHARS) return s;
        return s.substring(0, MAX_RESULT_CHARS) + "…";
    }

    /** cancel a running background subagent.
     *  {@link Thread#interrupt()}s the worker, then updates
     *  the status (which fires the {@code subagent_event}
     *  notification the TUI/desktop consume). The worker
     *  may finish naturally a moment later — when it
     *  calls {@code markCompleted}/{@code markFailed}, the
     *  job is no longer in the {@code running} map so those
     *  methods no-op and we don't get a duplicate
     *  notification.
     *
     *  <p>Idempotent: calling cancel on a finished job is a
     *  no-op (returns {@code alreadyFinished=true}). The
     *  return value mirrors the JSON-RPC response so the
     *  caller (TUI / desktop) can show a different toast
     *  for "cancelled a live job" vs "tried to cancel a
     *  job that just finished".
     *
     *  @return a small record with the cancel outcome
     */
    public synchronized CancelResult cancel(String jobId) {
        return cancel(jobId, "");
    }

    /** cancel with a reason. The reason is stored on
     *  the job, appended to the audit log, and published
     *  on the {@link SubagentEvent} so the UI / logs can
     *  show why the user (or a hook) asked the worker to
     *  stop. {@code null} / empty reason is treated as
     *  "no reason given" and produces the same wire
     *  shape as the no-arg overload. */
    public synchronized CancelResult cancel(String jobId, String reason) {
        if (jobId == null) {
            // Defensive: a malformed RPC payload (missing
            // jobId) — we treat it the same as a finished
            // job. The caller will see alreadyFinished=true
            // and skip the "cancelled!" toast in favour of
            // a "nothing to cancel" hint.
            return new CancelResult(false, true);
        }
        SubagentJob j = running.get(jobId);
        if (j == null) {
            // Either the id was bogus or the job already
            // finished naturally between the user's click
            // and the RPC landing. Either way, no work to do.
            return new CancelResult(false, true);
        }
        Thread t = runningThreads.get(jobId);
        if (t != null) {
            try {
                t.interrupt();
            } catch (SecurityException se) {
                // Some sandboxes forbid interrupting threads
                // we don't own. Log and continue with the
                // status flip — the worker may eventually
                // notice the canel via a different code path.
                LOG.warn("interrupt denied for subagent thread {}: {}", jobId, se.getMessage());
            }
        } else {
            // No thread attached (e.g. a job registered
            // previously-C shipped, or a foreground
            // dispatch that registered without a worker).
            // Just flip the status; the work continues in
            // the foreground thread and will eventually
            // fire a natural markCompleted/markFailed that
            // we let override.
            LOG.debug("subagent {} has no attached thread; status flip only", jobId);
        }
        markCancelled(jobId, reason);
        return new CancelResult(true, false);
    }

    /** outcome of {@link #cancel(String)}. Returned
     *  to the JSON-RPC caller so the UI can show
     *  "cancelled" / "was already finished" appropriately. */
    public record CancelResult(boolean cancelled, boolean alreadyFinished) {}

    private void moveToFinished(SubagentJob j) {
        finished.put(j.jobId, j);
        // Evict oldest if we exceeded the cap.
        while (finished.size() > MAX_FINISHED_JOBS) {
            String oldest = finished.keySet().iterator().next();
            finished.remove(oldest);
        }
    }

    /** Look up a job by id (running OR recently-finished). */
    public SubagentJob get(String jobId) {
        SubagentJob j = running.get(jobId);
        if (j != null) return j;
        return finished.get(jobId);
    }

    /** snapshot of the job's lifecycle log
     *  (newest last). Returns an empty list for an
     *  unknown job id. The list is a defensive copy so
     *  callers can iterate without worrying about the
     *  registry's internal lock. Each entry carries
     *  timestamp + canonical action verb + free-form
     *  details. */
    public List<AuditEntry> auditLog(String jobId) {
        SubagentJob j = get(jobId);
        if (j == null) return List.of();
        return j.auditSnapshot();
    }

    /** subscribe to lifecycle events. Returns the
     *  consumer so callers can later detach it (not yet
     *  supported — there is no remove API; the listener
     *  list is process-scoped and lives as long as the
     *  JVM). The listener fires on the caller's thread
     *  (the daemon thread for background jobs, the
     *  engine thread for foreground ones). */
    public Consumer<SubagentEvent> onChange(Consumer<SubagentEvent> listener) {
        if (listener == null) throw new IllegalArgumentException("listener");
        listeners.add(listener);
        return listener;
    }

    /** fan out an event to every registered
     *  listener. Best-effort: a misbehaving listener
     *  (throws) is logged and skipped so it can't break
     *  the engine. */
    private void fireChange(SubagentEvent ev) {
        for (var l : listeners) {
            try { l.accept(ev); }
            catch (Exception ex) { LOG.warn("subagent event listener threw: {}", ex.getMessage()); }
        }
    }

    /** Snapshot of currently-running jobs. */
    public List<SubagentJob> listRunning() {
        return List.copyOf(running.values());
    }

    /** Snapshot of recently-finished jobs (newest first,
     *  capped at {@link #MAX_FINISHED_JOBS}). */
    public List<SubagentJob> listFinished() {
        // Reverse the LinkedHashMap so newest is first.
        List<SubagentJob> out = new java.util.ArrayList<>(finished.values());
        java.util.Collections.reverse(out);
        return out;
    }

    /** Translate a job's status to a {@code TaskStatus}
     *  for the task-registry update. */
    public static TaskStatus taskStatusFor(SubagentJob j) {
        if (j == null) return TaskStatus.FAILED;
        return switch (j.status) {
            case RUNNING   -> TaskStatus.RUNNING;
            case COMPLETED -> TaskStatus.COMPLETED;
            case FAILED    -> TaskStatus.FAILED;
            case CANCELLED -> TaskStatus.FAILED;  // closest existing status
        };
    }

    /** Render a one-line summary suitable for printing. */
    public static String summary(SubagentJob j) {
        if (j == null) return "(unknown subagent job)";
        return switch (j.status) {
            case RUNNING -> "[running " + j.elapsedMs() + "ms] role=" + j.role
                    + " task=" + j.taskId + " prompt=\"" + truncate(j.prompt, 60) + "\"";
            case COMPLETED -> "[done " + j.elapsedMs() + "ms] role=" + j.role
                    + " task=" + j.taskId
                    + " result=" + truncate(j.resultText, 80);
            case FAILED -> "[failed " + j.elapsedMs() + "ms] role=" + j.role
                    + " task=" + j.taskId + " error=" + j.error;
            case CANCELLED -> {
                // include the cancellation reason
                // in the summary when one was given, so
                // a debug / log dump shows "cancelled
                // (timeout)" instead of a bare
                // "cancelled" string. The reason field is
                // nullable so the empty case still
                // produces the legacy-D shape.
                String base = "[cancelled " + j.elapsedMs() + "ms] role=" + j.role
                        + " task=" + j.taskId;
                if (j.cancelReason != null && !j.cancelReason.isEmpty()) {
                    yield base + " reason=" + j.cancelReason;
                }
                yield base;
            }
        };
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
