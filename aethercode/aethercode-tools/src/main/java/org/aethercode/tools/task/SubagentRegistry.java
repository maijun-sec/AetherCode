package org.aethercode.tools.task;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.util.Watchdog;
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

    /** R362 Round 3: Watchdog timeout for background
     *  subagents. Read once at JVM startup from the
     *  env var {@code AETHERCODE_SUBAGENT_TIMEOUT_MS}
     *  (default 60s). The watchdog poll interval is
     *  fixed at {@link Watchdog#DEFAULT_POLL_MS} —
     *  users tune the timeout, not the polling
     *  cadence. An invalid value (≤ 0 or non-numeric)
     *  falls back to the default. Tests can call
     *  {@link #setWatchdogTimeoutMs(long)} to override
     *  before registering jobs. */
    private volatile long watchdogTimeoutMs = readWatchdogTimeoutMs();

    /** R372.2: per-agent circuit breaker. Owns the
     *  failure-streak state for every distinct agent
     *  name the registry has seen. A new agent creates a
     *  new slot on first failure; slots never expire
     *  (the "open until" timestamp is the only TTL). The
     *  breaker is consulted by callers that want to
     *  refuse to spawn a known-bad agent before
     *  burning tokens on it — see
     *  {@link SubagentCircuitBreaker#isOpen(String)}. */
    private final SubagentCircuitBreaker circuitBreaker =
            new SubagentCircuitBreaker();
    // R374.2: per-agent concurrency limiter. Each agent
    // name has a configurable quota (default 1 = serial);
    // register() acquires a token before adding the job
    // to the running set, and the natural markCompleted /
    // markFailed / markCancelled transitions release it.
    // The setQuota() setter is wired to the JSON-RPC layer
    // (subagentSetQuota) so the desktop's per-agent
    // dashboard can adjust the limit at runtime.
    private final SubagentConcurrencyLimiter concurrencyLimiter =
            new SubagentConcurrencyLimiter();

    /** R372.2: optional handle the engine / methods
     *  layer can swap in (e.g. with a custom threshold
     *  loaded from {@code ~/.aethercode/agents.yaml}).
     *  The default constructor is what tests + most
     *  deployments use. */
    public void setCircuitBreaker(SubagentCircuitBreaker breaker) {
        if (breaker == null) {
            throw new IllegalArgumentException("breaker must not be null");
        }
        this.circuitBreaker.reset();
        // we can't reassign a final field; rely on the
        // mutable default breaker instead. This setter
        // exists for the future "wire a custom breaker"
        // path — for now callers configure thresholds
        // via the env-driven constants.
    }

    /** R372.2: peek the circuit breaker. Returns the
     *  same instance the registry uses internally so the
     *  caller can query the state without re-constructing
     *  a separate breaker. */
    public SubagentCircuitBreaker circuitBreaker() { return circuitBreaker; }

    // R374.2: concurrency limiter accessor + quota setter.
    // Returned by reference so the dashboard can call
    // snapshot(name) for the per-card "X / quota" display;
    // the setQuota(name, n) setter is wired to the
    // subagentSetQuota JSON-RPC method. The limiter is
    // shared with the registry's spawn path so quota
    // changes are immediately effective for the next
    // register() call.
    public SubagentConcurrencyLimiter concurrencyLimiter() {
        return concurrencyLimiter;
    }

    /** R374.2: update the per-agent concurrency quota.
     *  Default is 1 (serial — the legacy behaviour).
     *  Setting it to a value smaller than the number of
     *  in-flight jobs does NOT kill the in-flight jobs;
     *  future register() calls simply refuse until
     *  enough have completed to drop below the new quota.
     *
     *  <p>The setter is fire-and-forget from the
     *  registry's perspective; the previous quota is
     *  captured by the RPC layer (which reads the
     *  limiter's snapshot before + after the call) so
     *  it can echo it back to the UI. A non-positive
     *  {@code n} resets to the default (DEFAULT_QUOTA =
     *  1) — useful for "remove the custom override"
     *  semantics. */
    public void setQuota(String role, int n) {
        concurrencyLimiter.setQuota(role, n);
    }

    /** R374.2: reset the concurrency limiter. Test-only
     *  helper — production code should not call this.
     *  Exposed so cross-module tests (e.g.
     *  aethercode-protocol's AetherCodeMethodsR374Test)
     *  can return the singleton to a known state
     *  without pulling in the SubagentConcurrencyLimiter
     *  type. */
    public void resetConcurrencyLimiter() {
        concurrencyLimiter.reset();
    }

    /** R374.2: peek the current quota for a role. Returns
     *  the default (1) if the user has never set a
     *  custom value. */
    public int quotaFor(String role) {
        return concurrencyLimiter.snapshot(role).quota();
    }

    /** R374.3: clear the circuit breaker for one agent.
     *  Wired to the subagentResetCircuit JSON-RPC
     *  method so the dashboard's "Reset circuit"
     *  button can short-circuit the 60s cooldown when
     *  the user has fixed the underlying cause.
     *  Unknown roles are a no-op (returns false). */
    public boolean resetCircuit(String role) {
        return circuitBreaker.reset(role);
    }

    /** env-var name the user can set to extend or
     *  shorten the default watchdog timeout. */
    public static final String WATCHDOG_TIMEOUT_ENV = "AETHERCODE_SUBAGENT_TIMEOUT_MS";

    private static long readWatchdogTimeoutMs() {
        String v = System.getenv(WATCHDOG_TIMEOUT_ENV);
        if (v == null || v.isBlank()) return Watchdog.DEFAULT_TIMEOUT_MS;
        try {
            long parsed = Long.parseLong(v.trim());
            if (parsed <= 0) return Watchdog.DEFAULT_TIMEOUT_MS;
            // clamp to a sane range — 1s to 24h.
            // Below 1s is too tight to ever fire (any
            // LLM reply takes longer). Above 24h is
            // almost certainly a typo. The clamp is
            // silent (no warning) so a deployment
            // override just works.
            if (parsed < 1_000L) return 1_000L;
            if (parsed > 24L * 60 * 60 * 1000) return 24L * 60 * 60 * 1000;
            return parsed;
        } catch (NumberFormatException nfe) {
            LOG.warn("invalid {} value '{}' — falling back to {}ms",
                    WATCHDOG_TIMEOUT_ENV, v, Watchdog.DEFAULT_TIMEOUT_MS);
            return Watchdog.DEFAULT_TIMEOUT_MS;
        }
    }

    /** get the configured watchdog timeout (ms). */
    public long getWatchdogTimeoutMs() { return watchdogTimeoutMs; }

    /** override the watchdog timeout (ms). Tests use
     *  this to drive a 100ms timeout so the watchdog
     *  fires within the test's lifetime. */
    public void setWatchdogTimeoutMs(long ms) {
        if (ms <= 0) throw new IllegalArgumentException("timeout must be > 0");
        this.watchdogTimeoutMs = ms;
    }

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

    // Package-private constructor so R372 tests can build a
    // fresh registry with a long watchdog timeout (the
    // default watchdog trips in seconds and would
    // misfire inside a fast unit test). Production code
    // uses the {@link #INSTANCE} singleton via the
    // static accessor at the bottom of this file.
    SubagentRegistry() {}

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
        // R362 Round 3: changed from `public final long`
        // to `public volatile long` so the retry()
        // method can reset it on a reused SubagentJob.
        // The field is read by elapsedMs() and summary()
        // (both run on the caller's thread, holding the
        // registry's lock — no visibility concern) and by
        // the Watchdog's LongSupplier (which we just
        // touch() before the read so the gap is fresh).
        // The volatility cost is one store-load fence
        // per status flip — negligible.
        public volatile long startedAtMs;
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
        /** R372.1: cumulative tokens consumed by this
         *  subagent. Updated by
         *  {@link SubagentRegistry#addTokens(String, long)}
         *  as the worker streams tokens from the LLM.
         *  Surfaced via the {@code tokenUsage} field on
         *  {@link SubagentEvent} so the desktop dashboard
         *  can plot cumulative cost per agent. */
        public volatile long tokensUsed;
        /** R372.1: per-agent token budget. 0 = no limit
         *  (the legacy default). When non-zero, the
         *  registry checks {@link #addTokens(String, long)}
         *  on every updatePartial; once cumulative tokens
         *  exceed this cap, the registry fires the
         *  watchdog (which interrupts the worker thread)
         *  and marks the job FAILED with reason
         *  "token budget exceeded". The token-budget cap
         *  is independent of the wall-clock watchdog — a
         *  long-running agent that streams cheaply can
         *  blow the token budget long before the
         *  wall-clock timeout fires. */
        public volatile long maxTokens;
        // R374.2: token id returned by
        // SubagentConcurrencyLimiter.tryAcquire() in
        // register(). Stored on the job so the natural
        // terminal transitions (markCompleted /
        // markFailed / markCancelled) can release the
        // exact token without leaking a permit to a
        // different worker. The token-keyed release is
        // important: a wrong-key release would let
        // another thread's job keep its permit and we'd
        // exceed the quota silently. The id is a String
        // (UUID-shaped per the limiter's AcquireResult);
        // empty/null means no token was acquired (the
        // job either predates R374.2 or the limiter
        // refused to acquire — but a refusal is a
        // throw, so this branch is unreachable for new
        // code; we keep the null check for forward
        // safety).
        public volatile String concurrencyTokenId;
        /** R362 Round 3: per-job Watchdog (null when
         *  the registry was created without watchdog
         *  support, e.g. legacy unit tests). The
         *  watchdog polls {@link #lastEventMs} and fires
         *  when no activity has been seen for
         *  {@link #DEFAULT_TIMEOUT_MS}. Activity includes
         *  register (start), updatePartial (kick), and
         *  the natural terminal transitions (stop). The
         *  watchdog is owned by the job (not the registry
         *  map) so a single subagent's lifecycle owns its
         *  timer cleanly without cross-job interference.
         *  Watchdog is created in {@link
         *  SubagentRegistry#register} and {@link
         *  #startWatchdog(long, long)}; it is
         *  {@linkplain Watchdog#stop() stopped} on every
         *  natural terminal transition (markCompleted /
         *  markFailed / markCancelled) so it cannot
         *  misfire after the job is gone. */
        private volatile Watchdog watchdog;
        /** Wall-clock ms of the most recent activity
         *  on this job (register, partial, retry
         *  reset). The watchdog polls this field every
         *  {@link Watchdog#DEFAULT_POLL_MS}; if the gap
         *  to {@code now} exceeds the timeout, the
         *  watchdog fires. {@code volatile long} is
         *  fine — the watchdog reads via the
         *  {@code LongSupplier} passed to
         *  {@link Watchdog#Watchdog(java.util.function.LongSupplier,
         *  Watchdog.TimeoutHandler)} so visibility is
         *  preserved. */
        public volatile long lastEventMs;
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
            this.lastEventMs = this.startedAtMs;
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

        /** touch the lastEventMs to the current wall-clock.
         *  Called from {@link SubagentRegistry#updatePartial}
         *  and {@link SubagentRegistry#retry} so the
         *  watchdog sees fresh activity and does not
         *  fire prematurely. The {@code volatile long}
         *  is read by the watchdog's {@code LongSupplier}
         *  on the watchdog's executor thread; the JVM
         *  memory model guarantees visibility for
         *  {@code volatile long} reads. */
        void touch() {
            lastEventMs = System.currentTimeMillis();
        }

        /** Attach + start the per-job Watchdog. The
         *  watchdog fires after {@code timeoutMs} of
         *  silence (no register / partial / retry). The
         *  {@code pollMs} argument controls how often
         *  the watchdog checks; the registry always
         *  passes {@link Watchdog#DEFAULT_POLL_MS} so
         *  the only knob the user has is timeout (via
         *  {@code AETHERCODE_SUBAGENT_TIMEOUT_MS}).
         *
         *  <p>The poll cadence is clamped to at most
         *  {@code timeoutMs / 5} so the watchdog
         *  actually polls faster than its timeout when
         *  a test (or a future env-var) drives a very
         *  short timeout. Without this clamp the
         *  Watchdog constructor throws "timeoutMs
         *  must be >= pollMs" when a 200ms test
         *  timeout collides with the default 5s poll.
         *
         *  <p>Replaces any existing watchdog (defensive:
         *  if a job is retry'd we restart the timer
         *  cleanly). The previous watchdog is stopped
         *  via {@link Watchdog#close()} so a leaked
         *  executor cannot accumulate across retries. */
        void startWatchdog(long pollMs, long timeoutMs) {
            stopWatchdog();
            // Clamp poll so the Watchdog's invariant
            // (timeoutMs >= pollMs) holds for any
            // timeout setting. The min 100ms is the
            // Watchdog's own floor.
            long effectivePollMs = Math.min(pollMs, Math.max(100L, timeoutMs / 5));
            final SubagentJob self = this;
            Watchdog w = new Watchdog(
                    () -> self.lastEventMs,
                    silenceMs -> {
                        // Watchdog fired — the job has been
                        // silent too long. Mark it failed so
                        // the UI sees the transition, and
                        // interrupt the worker thread so
                        // the chat-client stream unwinds.
                        LOG.warn("subagent {} watchdog fired after {}ms of silence",
                                self.jobId, silenceMs);
                        SubagentRegistry.instance().markFailed(self.jobId,
                                "watchdog timeout after " + silenceMs + "ms of silence");
                        Thread t = SubagentRegistry.instance().runningThreads.get(self.jobId);
                        if (t != null) {
                            try { t.interrupt(); } catch (SecurityException ignored) {}
                        }
                    },
                    effectivePollMs, timeoutMs);
            w.start();
            this.watchdog = w;
            appendAudit("WATCHDOG", "poll=" + effectivePollMs + "ms timeout=" + timeoutMs + "ms");
        }

        /** stop the per-job Watchdog. Safe to call on a
         *  job that never had one (no-op). The
         *  watchdog's single-threaded executor is
         *  shut down via {@link Watchdog#close()} so it
         *  cannot leak across the daemon's lifetime. */
        void stopWatchdog() {
            Watchdog w = this.watchdog;
            if (w != null) {
                try { w.close(); } catch (Exception e) {
                    LOG.debug("watchdog close failed for {}: {}", jobId, e.getMessage());
                }
                this.watchdog = null;
            }
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
        return register(taskId, prompt, role, "", 0L);
    }

    /** R372.1: register with an explicit token budget.
     *  {@code maxTokens == 0} means "no budget" (the
     *  legacy behaviour). The budget is enforced by
     *  {@link #addTokens(String, long)} which the worker
     *  thread calls on every LLM token arrival. */
    public synchronized String register(String taskId, String prompt, String role,
                                        long maxTokens) {
        return register(taskId, prompt, role, "", maxTokens);
    }

    /** register a new job, attributing it to a
     *  specific engine session. The sessionId is carried
     *  on the {@link SubagentEvent} so the renderer can
     *  filter events for its own session. {@code null} or
     *  empty sessionId is preserved as empty (matches the
     *  legacy-D wire shape; a single-session daemon
     *  treats an empty sessionId as "the only session"). */
    public synchronized String register(String taskId, String prompt, String role, String sessionId) {
        return register(taskId, prompt, role, sessionId, 0L);
    }

    /** R372.1: full session-aware + token-budget overload. */
    public synchronized String register(String taskId, String prompt, String role,
                                        String sessionId, long maxTokens) {
        // Mint the id up front so the same number can be
        // used as the jobHint for the concurrency limiter
        // and as the jobId once the acquire succeeds. One
        // increment per register() call, no wasted ids.
        long rawId = idCounter.incrementAndGet();
        String id = "sag-" + rawId;
        // R374.2: normalise the role to "general-purpose"
        // BEFORE consulting the limiter. Without this,
        // a null role becomes a SubagentJob with role
        // "general-purpose" but a limiter slot keyed on
        // the literal string "null" — the release in
        // moveToFinished() would then log a warning
        // ("unknown token") and silently leak the permit.
        // The normalised role is what the limiter sees,
        // what the SubagentJob has, and what the error
        // message reports, so all three stay in sync.
        String normalizedRole = role == null ? "general-purpose" : role;
        SubagentConcurrencyLimiter.AcquireResult ar =
                concurrencyLimiter.tryAcquire(normalizedRole, id);
        if (!ar.acquired()) {
            // The id is just a counter; rolling it back
            // would require a non-trivial dance with the
            // AtomicInteger and is not worth it (a
            // refused spawn still consumed one increment
            // of a long-lived counter, which is harmless).
            // The quota-snapshot carried by the result
            // gives the caller a clear "X/Y concurrent"
            // error message so the model knows what
            // happened.
            throw new IllegalStateException(
                    "concurrent subagent quota exceeded for role '"
                            + normalizedRole + "' ("
                            + ar.currentInFlight() + "/" + ar.quota()
                            + " in flight); wait for one to finish or "
                            + "raise the quota via subagentSetQuota RPC");
        }
        SubagentJob j = new SubagentJob(id, taskId, prompt, normalizedRole, sessionId);
        j.maxTokens = maxTokens;  // R372.1: token budget (0 = unlimited)
        // R374.2: stash the token id on the job so the
        // terminal transitions can release it. Released
        // by moveToFinished() once status flips to a
        // terminal state. The token is a String (the
        // limiter's id format).
        j.concurrencyTokenId = ar.tokenId();
        running.put(id, j);
        // R362 Round 3: start a per-job Watchdog. The
        // watchdog polls j.lastEventMs; if the gap to
        // "now" exceeds watchdogTimeoutMs, it fires
        // and we mark the job FAILED. The timer is
        // kicked by every updatePartial() call (the
        // worker streams tokens) and by retry(). The
        // timer is stopped by every natural terminal
        // transition below.
        j.startWatchdog(Watchdog.DEFAULT_POLL_MS, watchdogTimeoutMs);
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
        // R362 Round 3: stop the watchdog so it can't
        // misfire after a natural completion (the
        // worker is done; we don't need the timer
        // anymore). stopWatchdog is a no-op when the
        // job never had a watchdog (legacy tests
        // that pre-date Round 3).
        j.stopWatchdog();
        j.resultText = result;
        j.finishedAtMs = System.currentTimeMillis();
        j.status = SubagentJob.Status.COMPLETED;
        // clear the partial slot — the final
        // result supersedes the streaming preview.
        j.partialResult = "";
        j.appendAudit("COMPLETE", "result=" + truncate(result, 80));
        moveToFinished(j);
        // R372.2: a successful completion resets the
        // breaker's failure streak. A single success
        // after 2 failures doesn't recover a tripped
        // breaker (we never reached 3 yet) but it does
        // zero the counter so the next failure is the
        // "first of a fresh streak".
        circuitBreaker.recordSuccess(j.role);
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
        // R362 Round 3: stop the watchdog. The
        // watchdog may have been the very thing that
        // fired markFailed (via the timeout handler);
        // stopping here is still safe because
        // Watchdog.close() is idempotent (it just
        // cancels the scheduled task and shuts down
        // the executor — no callback fires on close).
        j.stopWatchdog();
        j.error = error;
        j.finishedAtMs = System.currentTimeMillis();
        j.status = SubagentJob.Status.FAILED;
        // clear the partial slot on failure too.
        j.partialResult = "";
        j.appendAudit("FAIL", error == null ? "" : error);
        moveToFinished(j);
        // R372.2: record the failure with the
        // circuit breaker. The breaker keys on the
        // role (which is the agent name for custom
        // agents, or the SubagentRole enum value for
        // built-in roles). After enough consecutive
        // failures the breaker will refuse to spawn
        // this agent until the cooldown window passes.
        circuitBreaker.recordFailure(j.role);
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
        // R362 Round 3: stop the watchdog on cancel.
        j.stopWatchdog();
        j.finishedAtMs = System.currentTimeMillis();
        j.status = SubagentJob.Status.CANCELLED;
        j.cancelReason = reason == null ? "" : reason;
        // clear the partial slot on cancel.
        j.partialResult = "";
        j.appendAudit("CANCEL", "reason=" + j.cancelReason);
        moveToFinished(j);
        // R372.2: cancel is not a failure (the user
        // asked for it). Record success so a string
        // of cancellations doesn't trip the breaker.
        circuitBreaker.recordSuccess(j.role);
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
        // R362 Round 3: kick the watchdog so a
        // streaming job never trips the timeout while
        // tokens are arriving. touch() also resets
        // lastEventMs (which the watchdog polls). The
        // kick is implicit — the watchdog's tick reads
        // j.lastEventMs each poll cycle, and the new
        // timestamp is fresh enough that the gap is
        // well below the timeout.
        j.touch();
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

    /** R372.1: account for {@code delta} more tokens
     *  consumed by the subagent. The worker thread
     *  calls this on every LLM token arrival
     *  (typically via the
     *  {@code text_delta}/{@code tool_use_start}
     *  stream-event paths).
     *
     *  <p>When {@link SubagentJob#maxTokens} is non-zero
     *  AND the cumulative {@code tokensUsed} now exceeds
     *  the budget, the registry fires the watchdog
     *  (which interrupts the worker thread) and marks
     *  the job FAILED with reason {@code "token budget
     *  exceeded"}. A budget breach is fatal — once the
     *  cap is tripped, the worker is killed regardless
     *  of partial-result progress. {@code delta <= 0}
     *  is a no-op.
     */
    public synchronized void addTokens(String jobId, long delta) {
        if (jobId == null || delta <= 0) return;
        SubagentJob j = running.get(jobId);
        if (j == null) return;  // already finished; drop
        j.tokensUsed += delta;
        // R372.1: enforce the per-job budget. The
        // check is intentionally "exceeded" (>=)
        // rather than "would exceed" so a single
        // delta that pushes us past the cap still
        // counts the overrun — the dashboard needs
        // to know how far over we went for
        // retrospective accounting.
        if (j.maxTokens > 0 && j.tokensUsed >= j.maxTokens) {
            String reason = "token budget exceeded: " + j.tokensUsed
                    + " >= " + j.maxTokens;
            j.appendAudit("BUDGET_BREACH", reason);
            // stop the watchdog so it doesn't
            // double-fire, then mark FAILED which
            // stops the watchdog's underlying timer.
            if (j.watchdog != null) j.watchdog.stop();
            markFailed(jobId, reason);
            // interrupt the worker thread if the
            // worker registered itself via
            // attachThread. The interrupt is
            // best-effort — a thread that ignores it
            // is the worker's responsibility.
            Thread worker = runningThreads.get(jobId);
            if (worker != null) worker.interrupt();
        }
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

    /** R362 Round 3: retry a failed/cancelled job.
     *  Resets the job to RUNNING (clearing the error,
     *  finishedAtMs, partialResult) and fires a fresh
     *  RUNNING event so the TUI / desktop SubagentPanel
     *  re-renders the row as "running" again.
     *
     *  <p>The job is moved from {@code finished} back
     *  to {@code running}; the watchdog is restarted
     *  with the current timeout setting (the previous
     *  watchdog is stopped via {@link
     *  SubagentJob#stopWatchdog()} so we never leak
     *  a scheduled executor across retries). The
     *  worker thread is NOT attached here — that's
     *  the caller's job (the {@code SubagentRetryTool}
     *  or {@code subagentRetry} RPC kicks a new daemon
     *  thread that re-runs the original prompt via
     *  {@link AgentTool#runBackgroundJob}.
     *
     *  <p>State machine:
     *  <pre>
     *    FAILED    → retry() → RUNNING (cleared)
     *    CANCELLED → retry() → RUNNING (cleared)
     *    COMPLETED → retry() → refused (terminal — the
     *                job succeeded; re-running would
     *                change history)
     *    RUNNING   → retry() → refused (already running;
     *                no double-spawn)
     *    unknown   → refused (no such job)
     *  </pre>
     *
     *  <p>Returns a {@link RetryResult} so the caller
     *  can render a precise toast ("retried!" vs "can't
     *  retry, job is RUNNING").
     */
    public synchronized RetryResult retry(String jobId) {
        if (jobId == null) {
            return new RetryResult(false, "jobId is required", null, null);
        }
        // First check running — we refuse to retry a
        // currently-running job (caller should cancel
        // first or wait). Without this check we'd have
        // two jobs sharing the same id.
        SubagentJob live = running.get(jobId);
        if (live != null) {
            return new RetryResult(false,
                    "job " + jobId + " is currently RUNNING; cancel first if you want to restart",
                    live, null);
        }
        SubagentJob j = finished.get(jobId);
        if (j == null) {
            return new RetryResult(false, "no such job: " + jobId, null, null);
        }
        if (j.status == SubagentJob.Status.COMPLETED) {
            return new RetryResult(false,
                    "job " + jobId + " is COMPLETED; re-running would change history. " +
                    "Spawn a fresh subagent instead.",
                    j, null);
        }
        // CANCELLED or FAILED — proceed.
        // Reset the job's mutable state. The job
        // object itself is reused (same id, same
        // prompt, same role) so existing event
        // listeners that hold a reference to it
        // see the fresh fields. Capture the previous
        // status for the audit log BEFORE we flip it.
        String previousStatus = j.status.name();
        // R374.2: re-acquire a concurrency token for the
        // retry. The original token was released when the
        // job first moved to finished (see
        // moveToFinished); without a fresh acquire the
        // retry would slip past the quota. A refusal
        // here mirrors the register() error message so
        // the caller sees the same "X/Y in flight" hint.
        SubagentConcurrencyLimiter.AcquireResult ar =
                concurrencyLimiter.tryAcquire(j.role, jobId);
        if (!ar.acquired()) {
            return new RetryResult(false,
                    "concurrent subagent quota exceeded for role '"
                            + j.role + "' (" + ar.currentInFlight() + "/"
                            + ar.quota() + " in flight); wait or raise the quota",
                    j, j.taskId);
        }
        j.concurrencyTokenId = ar.tokenId();
        j.status = SubagentJob.Status.RUNNING;
        j.startedAtMs = System.currentTimeMillis();
        j.lastEventMs = j.startedAtMs;
        j.finishedAtMs = 0L;
        j.error = "";
        j.resultText = "";
        j.cancelReason = "";
        j.partialResult = "";
        j.appendAudit("RETRY", "previousStatus=" + previousStatus);
        // move the job back to the running map so a
        // subsequent cancel / markCompleted / updatePartial
        // finds it.
        finished.remove(jobId);
        running.put(jobId, j);
        // restart the watchdog with a fresh timer.
        j.startWatchdog(Watchdog.DEFAULT_POLL_MS, watchdogTimeoutMs);
        // Fire a RUNNING event so the TUI / desktop
        // panel re-renders the row as running. The
        // summary at this moment is still the prior
        // "failed" text (it's rendered from the live
        // job fields which we've now reset) — the
        // TUI/desktop sees a clean RUNNING transition
        // with the original prompt.
        fireChange(new SubagentEvent(jobId, j.role, SubagentJob.Status.RUNNING,
                0L, summary(j), System.currentTimeMillis(), j.sessionId, "", "", ""));
        return new RetryResult(true, "", j, j.taskId);
    }

    /** outcome of {@link #retry(String)}. Mirrors
     *  {@link CancelResult}'s shape so the LLM tool
     *  and JSON-RPC handler can render a unified
     *  error / success message.
     *
     *  <p>{@code previousJob} is the (now-reset) job
     *  on success, or the current job on a refusal
     *  (so the caller can show "this job is currently
     *  RUNNING" without a second registry lookup).
     *  {@code previousTaskId} carries the task id
     *  the retry should re-attach to (the AgentTool
     *  re-uses it so the /tasks panel doesn't see a
     *  phantom new task per retry). */
    public record RetryResult(boolean retried, String reason,
                              SubagentJob previousJob, String previousTaskId) {}

    private void moveToFinished(SubagentJob j) {
        finished.put(j.jobId, j);
        // Evict oldest if we exceeded the cap.
        while (finished.size() > MAX_FINISHED_JOBS) {
            String oldest = finished.keySet().iterator().next();
            finished.remove(oldest);
        }
        // R374.2: release the concurrency token now that
        // the job is out of the running map. The release
        // is keyed on the job's stored tokenId (not the
        // thread or role) so a typo'd release can't free
        // a different job's permit. A null/empty tokenId
        // means the job predates R374.2 or the limiter
        // refused to acquire; either way, release is a
        // no-op (no token was ever taken).
        if (j.concurrencyTokenId != null && !j.concurrencyTokenId.isEmpty()) {
            concurrencyLimiter.release(j.role, j.concurrencyTokenId, j.jobId);
            j.concurrencyTokenId = null;
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

    /** R372.4: aggregated per-agent metrics for the
     *  Multi-Agent Dashboard. The shape is intentionally
     *  flat (Map<String, Metric>) so the renderer's
     *  React component can iterate without nesting. One
     *  Metric per distinct agent name the registry has
     *  seen in either running or finished. The breaker
     *  snapshot and concurrency snapshot come from
     *  their respective side-channel singletons, which
     *  means a brand-new agent that's never been
     *  spawned (no breaker / limiter slot) still gets
     *  CLOSED / 0 / 1 from this method. */
    public synchronized Map<String, AgentMetric> agentMetrics() {
        // Aggregate across running + finished so the
        // dashboard sees recent completions, not just
        // in-flight jobs.
        Map<String, AgentMetric> out = new LinkedHashMap<>();
        // running tokens are aggregated first so they
        // take precedence on conflicting keys.
        for (SubagentJob j : running.values()) {
            out.computeIfAbsent(j.role, k -> new AgentMetric(k, 0, 0, 0, 0, "CLOSED", 0,
                            SubagentConcurrencyLimiter.DEFAULT_QUOTA))
                    .mergeRunning(j);
        }
        for (SubagentJob j : finished.values()) {
            out.computeIfAbsent(j.role, k -> new AgentMetric(k, 0, 0, 0, 0, "CLOSED", 0,
                            SubagentConcurrencyLimiter.DEFAULT_QUOTA))
                    .mergeFinished(j);
        }
        // decorate with breaker + concurrency snapshots.
        var brSnap = circuitBreaker.snapshot();
        for (var e : out.entrySet()) {
            SubagentCircuitBreaker.SlotState br = brSnap.slots().get(e.getKey());
            if (br != null) {
                e.getValue().circuitState = br.state();
                e.getValue().consecutiveFailures = br.consecutiveFailures();
                e.getValue().breakerOpenRemainingMs = br.openRemainingMs();
            }
            // R372.4 + R374.2: concurrency is owned by
            // the limiter we wired into register() above.
            // Read the actual per-agent quota so the
            // dashboard shows what the user set via
            // subagentSetQuota (or DEFAULT_QUOTA = 1 if
            // they've never overridden). If the agent has
            // never registered a job, the limiter returns
            // a snapshot with quota=DEFAULT_QUOTA so the
            // dashboard's "quota 1" label still makes
            // sense for brand-new agents.
            SubagentConcurrencyLimiter.Snapshot limSnap =
                    concurrencyLimiter.snapshot(e.getKey());
            e.getValue().concurrencyQuota = limSnap.quota();
        }
        return out;
    }

    /** R372.1 + R372.2 + R372.4: the public hook a
     *  dashboard / metrics consumer queries. Returns the
     *  full set of agent metrics; callers can post-filter
     *  by agent name. The result is a snapshot — values
     *  may shift between calls. */
    public Map<String, AgentMetric> dashboardMetrics() {
        return agentMetrics();
    }

    /** Per-agent rollup for the dashboard. Combines
     *  running / finished counts, total token usage,
     *  circuit-breaker state, and concurrency quota. */
    public static final class AgentMetric {
        public final String agentName;
        public int running;
        public int completed;
        public int failed;
        public long tokensTotal;
        public String circuitState;
        public int consecutiveFailures;
        public long breakerOpenRemainingMs;
        public int concurrencyQuota;
        AgentMetric(String name, int run, int comp, int fail,
                    long tokens, String cState, int fails, int quota) {
            this.agentName = name;
            this.running = run;
            this.completed = comp;
            this.failed = fail;
            this.tokensTotal = tokens;
            this.circuitState = cState;
            this.consecutiveFailures = fails;
            this.concurrencyQuota = quota;
        }
        void mergeRunning(SubagentJob j) {
            running++;
            tokensTotal += j.tokensUsed;
            if (j.status == SubagentJob.Status.FAILED) failed++;
            else if (j.status == SubagentJob.Status.COMPLETED) completed++;
        }
        void mergeFinished(SubagentJob j) {
            // finished jobs are COMPLETED, FAILED, or CANCELLED.
            if (j.status == SubagentJob.Status.COMPLETED) completed++;
            else if (j.status == SubagentJob.Status.FAILED) failed++;
            tokensTotal += j.tokensUsed;
        }
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
