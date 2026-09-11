package org.aethercode.hooks.builtin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * the "boulder" continuation hook. Modelled on
 * oh-my-opencode's {@code todo-continuation-enforcer}, but
 * simplified for AetherCode's RPC + ToolResult architecture.
 *
 * <p>When a query finishes and the session goes idle, we look
 * at the todo list. If there are still pending / in-progress
 * todos, we schedule a 2-second countdown; if the user does
 * not stop it, we re-prompt the engine with a continuation
 * marker that says "continue working on the next pending task,
 * proceed without asking, mark each task complete when done,
 * do not stop until all tasks are done".
 *
 * <p>The hook keeps a small per-session state struct
 * (cooldown timestamp, consecutive failure count, in-flight
 * flag, scheduled countdown) so it can:
 * <ul>
 *   <li>back off when the model keeps failing to make
 *       progress ({@code MAX_CONSECUTIVE_FAILURES = 5}, then
 *       exponential cooldown),</li>
 *   <li>suppress duplicate countdowns when the previous one
 *       is still running,</li>
 *   <li>and reset failure state after a 5-minute idle window
 *       (matches oh-my-opencode's FAILURE_RESET_WINDOW_MS).</li>
 * </ul>
 *
 * <p>The hook is intentionally engine-agnostic — it talks to
 * the protocol layer via {@link ContinuationDispatcher}, so
 * unit tests can drive it with a stub dispatcher and assert
 * the calls. The production dispatcher (in
 * {@code aethercode-protocol}) re-enters the engine and pushes
 * a countdown notification to the TUI.
 *
 * <p>Constants match oh-my-opencode's
 * {@code todo-continuation-enforcer/constants.ts} where it
 * makes sense, with two intentional deltas:
 * <ul>
 *   <li>{@code COUNTDOWN_SECONDS = 2} (same)</li>
 *   <li>{@code CONTINUATION_COOLDOWN_MS = 30_000} (same)</li>
 *   <li>{@code MAX_CONSECUTIVE_FAILURES = 5} (same)</li>
 *   <li>{@code FAILURE_RESET_WINDOW_MS = 5 * 60_000} (same)</li>
 *   <li>exponential cooldown multiplier when failures stack up
 *       (same: {@code 2^failures} capped at 5)</li>
 * </ul>
 */
public class TodoContinuationHook {

    private static final Logger LOG = LoggerFactory.getLogger(TodoContinuationHook.class);

    /** How long to wait between "session is idle" and the actual
     *  re-prompt. The TUI shows a toast with a "Stop" button during
     *  this window; if the user clicks it, the scheduled continuation
     *  is cancelled. 2s matches oh-my-opencode. */
    public static final long DEFAULT_COUNTDOWN_SECONDS = 2L;
    private volatile long countdownSeconds = DEFAULT_COUNTDOWN_SECONDS;
    /** Cooldown between two consecutive auto-continuations on the
     *  same session. Volatile so tests can shorten it. 30s default
     *  matches oh-my-opencode. */
    public static final long DEFAULT_COOLDOWN_MS = 30_000L;
    private volatile long cooldownMs = DEFAULT_COOLDOWN_MS;

    /** Minimum time between two consecutive auto-continuations on
     *  the same session. 30s matches oh-my-opencode. */
    public static final long CONTINUATION_COOLDOWN_MS = DEFAULT_COOLDOWN_MS;

    /** After this many consecutive failed continuations (model
     *  yields without making progress), the hook stops firing
     *  until {@link #FAILURE_RESET_WINDOW_MS} of silence. 5 matches
     *  oh-my-opencode. */
    public static final int MAX_CONSECUTIVE_FAILURES = 5;
    /** maximum number of research-mode auto-recoveries
     *  per session (or per task — see
     *  {@link #RESEARCH_MODE_BUDGET_RESET_STOPS}). The
     *  R141 "loop of loops" smoke test showed that some
     *  models ignore the recovery prompt and fall into
     *  research-mode again 30-60s into the recovery
     *  continuation. Without a budget, the engine
     *  recovery-fires forever and the user never sees
     *  the [todo-ask-llm] prompt. Default 1: the
     *  FIRST research_mode hard-stop gets an automatic
     *  recovery; the SECOND one (in the same task)
     *  falls back to user ack. A larger budget would
     *  make the engine more autonomous at the cost of
     *  hiding stuck-model patterns from the user. */
    public static final int MAX_RESEARCH_MODE_RECOVERIES = 1;

    /** After this much silence, the failure counter resets. 5
     *  minutes matches oh-my-opencode. */
    public static final long FAILURE_RESET_WINDOW_MS = 5L * 60_000L;
    /** stop reasons that RESET the research-mode
     *  recovery budget. A successful end_turn means the
     *  model completed its task (or made real progress);
     *  the next task in the same session should get its
     *  own research_mode budget. An "awaiting_user" or
     *  explicit user intervention also resets — the user
     *  has acknowledged the situation and the next task
     *  is a fresh start. Loop stops do NOT reset (a
     *  second research_mode in a row means the recovery
     *  didn't help and the budget should be exhausted). */
    private static final java.util.Set<String> RESEARCH_MODE_BUDGET_RESET_STOPS =
            java.util.Set.of("end_turn", "awaiting_user_decision", "completed");

    /** budget for the no_file_write_progress
     *  auto-recovery. The model had been making real
     *  progress (file_writes > 0) and then stopped
     *  writing. A "you've made progress, keep going"
     *  recovery continuation is almost always the
     *  right call. Default 1 — first stall gets a
     *  recovery, second stall in the same task falls
     *  back to user ack. A larger budget hides
     *  actually-stuck models from the user; a smaller
     *  budget (0) would defeat the purpose. */
    public static final int MAX_PROGRESS_STALL_RECOVERIES = 1;

    /** Cap the exponential-cooldown multiplier. 2^5 = 32, so the
     *  maximum effective cooldown is {@code 30_000 * 32 = 16 min}.
     *  5 matches oh-my-opencode. */
    public static final int COOLDOWN_BACKOFF_CAP = 5;

    /** Background scheduler for the 2s countdown. Single-threaded
     *  so the hook never has to worry about cross-task ordering;
     *  the only thing it does is fire a Runnable 2s after
     *  schedule(), and the Runnable itself just calls
     *  dispatcher.dispatchContinuation(...) on the calling thread. */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "todo-continuation-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** Per-session state. Bounded by {@link #evictStale()} so it
     *  does not grow without bound across long-lived daemons. */
    private final Map<String, SessionState> stateBySession = new ConcurrentHashMap<>();

    /** The dispatcher that knows how to actually re-enter the
     *  engine + push notifications. May be null for tests; in
     *  that case the hook is a no-op. */
    private volatile ContinuationDispatcher dispatcher;

    public TodoContinuationHook() {}

    public TodoContinuationHook(ContinuationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void setDispatcher(ContinuationDispatcher d) { this.dispatcher = d; }
    /** For tests: shorten the countdown so the test does not
     *  have to wait the full 2s per scenario. The production
     *  code path does not call this. */
    public void setCountdownSecondsForTest(long seconds) {
        this.countdownSeconds = seconds;
    }
    /** For tests: shorten the cooldown so the multi-idle test
     *  can observe the cooldown without waiting 30s. */
    public void setCooldownMsForTest(long ms) {
        this.cooldownMs = ms;
    }
    protected long countdownSeconds() { return countdownSeconds; }
    protected long cooldownMs() { return cooldownMs; }

    /** For tests: shutdown the scheduler so the JVM can exit. */
    public void shutdown() { scheduler.shutdownNow(); }

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    /**
     * Called by {@code AetherCodeMethods.query} after the run
     * finishes streaming. Decides whether to schedule a
     * continuation, and on success fires
     * {@link ContinuationDispatcher#dispatchContinuation}
     * after a 2s countdown.
     *
     * <p>This method is non-blocking — it returns immediately
     * after scheduling. The actual continuation runs on the
     * {@link #scheduler}'s thread, which calls into the
     * dispatcher (which is the protocol layer's re-entry
     * point).
     */
    public void onSessionIdle(String sessionId, String runId,
                              String stopReason, List<Map<String, Object>> todoList) {
        if (sessionId == null) return;
        if (dispatcher == null) return;
        if (todoList == null || todoList.isEmpty()) return;

        // reset the research-mode budget on a
        // successful non-loop stop. Placed BEFORE the
        // incomplete-todo check so a session that
        // finishes all its todos (and the next
        // onSessionIdle event has an empty
        // incomplete list) still resets the budget.
        // The reset is per-task, not per-session —
        // a successful end_turn means the model
        // finished a task, and the next task in the
        // same session gets a fresh budget.
        // same reset logic for the
        // progress-stall budget. Both budgets share
        // the same reset triggers.
        SessionState earlyState = stateBySession.get(sessionId);
        if (earlyState != null && stopReason != null
                && RESEARCH_MODE_BUDGET_RESET_STOPS.contains(stopReason.toLowerCase())) {
            if (earlyState.researchModeRecoveryCount > 0) {
                LOG.info("R142 research_mode: budget reset ({} -> 0) on successful stop '{}' (run {})",
                        earlyState.researchModeRecoveryCount, stopReason, runId);
                earlyState.researchModeRecoveryCount = 0;
            }
            if (earlyState.progressStallRecoveryCount > 0) {
                LOG.info("R151a progress_stall: budget reset ({} -> 0) on successful stop '{}' (run {})",
                        earlyState.progressStallRecoveryCount, stopReason, runId);
                earlyState.progressStallRecoveryCount = 0;
            }
        }

        // 1. Are there any incomplete todos?
        List<Map<String, Object>> incomplete = filterIncomplete(todoList);
        if (incomplete.isEmpty()) {
            LOG.debug("R89 boulder: all todos complete, skipping (run {})", runId);
            return;
        }

        SessionState state = stateBySession.computeIfAbsent(sessionId, k -> new SessionState());

        // 2. User-stopped flag (set via the TUI's "Stop auto-continue"
        // button). Cleared on the next user-prompt submit, which is
        // handled by the protocol layer resetting the flag — the hook
        // just reads it.
        if (dispatcher.isContinuationStopped(sessionId)) {
            LOG.debug("R89 boulder: continuation stopped by user (run {})", runId);
            return;
        }

        // 3. Already in flight? Skip — the previous continuation
        // hasn't yielded yet. Without this guard a fast-failing
        // model could trigger overlapping runs.
        if (state.inFlight.get() != 0) {
            LOG.debug("R89 boulder: previous continuation in flight, skipping (run {})", runId);
            return;
        }

        long now = System.currentTimeMillis();

        // 4. Reset failure counter if the failure-reset window has
        // passed since the last injection. The user has either
        // stopped the loop or moved on to a new task; either way,
        // we trust the model to try again.
        if (state.consecutiveFailures.get() >= MAX_CONSECUTIVE_FAILURES
                && state.lastInjectedAt > 0
                && (now - state.lastInjectedAt) >= FAILURE_RESET_WINDOW_MS) {
            state.consecutiveFailures.set(0);
            LOG.info("R89 boulder: reset consecutive failures after {}ms idle", now - state.lastInjectedAt);
        }

        // 5. Hard cap: too many consecutive failures, give up until
        // the user does something.
        if (state.consecutiveFailures.get() >= MAX_CONSECUTIVE_FAILURES) {
            LOG.info("R89 boulder: max consecutive failures ({}) reached, skipping (run {})",
                    MAX_CONSECUTIVE_FAILURES, runId);
            return;
        }

        // 6. Cooldown: don't fire continuations too fast. The
        // effective cooldown grows exponentially with the failure
        // count, capped at 2^5 = 32x.
        long multiplier = 1L << Math.min(state.consecutiveFailures.get(), COOLDOWN_BACKOFF_CAP);
        long effectiveCooldown = cooldownMs * multiplier;
        if (state.lastInjectedAt > 0 && (now - state.lastInjectedAt) < effectiveCooldown) {
            LOG.debug("R89 boulder: cooldown active ({}ms < {}ms), skipping (run {})",
                    now - state.lastInjectedAt, effectiveCooldown, runId);
            return;
        }

        // 7. Don't fire if the stop reason is suspicious — a
        // permission denial, an error, or a loop detection
        // means the model didn't cleanly yield, and a
        // continuation would just re-trigger the same failure.
        //
        // SPECIAL CASE for `loop_research_mode`. The
        // research-mode hard-stop means the model spent 3+
        // turns on small-output shell (mvn -version /
        // java -version / echo / ls / pwd) without
        // writing anything. A continuation with a
        // directive "start writing NOW" prompt is
        // actually USEFUL here — without one the user
        // has to manually ack via A/B/C/D on every
        // model that's about to do useful work. Other
        // loop kinds (same_fingerprint, no_file_write,
        // long_output) keep the existing skip behaviour
        // because their failure mode is "model stuck
        // on a specific broken pattern" and a
        // continuation that just repeats the same
        // prompt would re-trigger the same failure.
        if (stopReason != null) {
            String sr = stopReason.toLowerCase();
            if (sr.contains("error") || sr.contains("deny") || sr.contains("abort")
                    || sr.contains("max_iterations")
                    || sr.contains("max_tokens")) {
                LOG.info("R89 boulder: stop reason '{}' not safe for continuation, skipping (run {})",
                        stopReason, runId);
                return;
            }
            if (sr.startsWith("loop_")) {
                // allow continuation for research_mode.
                // also allow for loop_no_file_write_progress
                // when the model had been making progress
                // (file_writes > 0 in this run). The user
                // reported a case where 66 file_writes
                // turned into 6 turns of small bash and
                // the session stalled with [todo-ask-llm].
                // Same_fingerprint and other loop kinds
                // keep the safe-default skip (a continuation
                // that re-runs the same broken pattern is
                // worse than a manual user ack).
                boolean isResearch = sr.equals("loop_research_mode");
                boolean isProgressStall = sr.equals("loop_no_file_write_progress");
                if (!isResearch && !isProgressStall) {
                    LOG.info("R89 boulder: stop reason '{}' is a non-recoverable loop, skipping (run {})",
                            stopReason, runId);
                    return;
                }
                // budget check (per-loop-kind).
                // First occurrence in a task gets a recovery;
                // subsequent ones in the same task fall back
                // to user ack so the user actually sees the
                // [todo-ask-llm] prompt instead of getting a
                // "loop of loops" silent recovery. The budget
                // resets on a successful end_turn (see the
                // reset block at the top of onSessionIdle).
                if (isResearch) {
                    if (state.researchModeRecoveryCount >= MAX_RESEARCH_MODE_RECOVERIES) {
                        LOG.info("R142 research_mode: budget exhausted ({}/{}), falling back to user ack (run {})",
                                state.researchModeRecoveryCount, MAX_RESEARCH_MODE_RECOVERIES, runId);
                        return;
                    }
                    LOG.info("R141 research_mode: stop reason '{}' — proceeding with recovery continuation (run {}, budget {}/{})",
                            stopReason, runId,
                            state.researchModeRecoveryCount, MAX_RESEARCH_MODE_RECOVERIES);
                } else {
                    if (state.progressStallRecoveryCount >= MAX_PROGRESS_STALL_RECOVERIES) {
                        LOG.info("R151a progress_stall: budget exhausted ({}/{}), falling back to user ack (run {})",
                                state.progressStallRecoveryCount, MAX_PROGRESS_STALL_RECOVERIES, runId);
                        return;
                    }
                    LOG.info("R151a progress_stall: stop reason '{}' — proceeding with recovery continuation (run {}, budget {}/{})",
                            stopReason, runId,
                            state.progressStallRecoveryCount, MAX_PROGRESS_STALL_RECOVERIES);
                }
            }
        }

        // 8. Schedule the 2s countdown. We atomically claim
        // the in-flight slot BEFORE scheduling so a parallel
        // idle event (theoretically impossible since the
        // engine is single-threaded per session, but defensive)
        // does not double-schedule.
        if (!state.inFlight.compareAndSet(0, 1)) return;
        try {
            // Tell the TUI a countdown is starting so it can
            // show the toast. Best-effort.
            try {
                dispatcher.notifyCountdown(sessionId, runId, incomplete.size(),
                        todoList.size(), countdownSeconds * 1000L);
            } catch (Exception notifyEx) {
                LOG.debug("R89 boulder: notifyCountdown failed: {}", notifyEx.getMessage());
            }

            ScheduledFuture<?> f = scheduler.schedule(() -> {
                try {
                    // Re-check the user-stopped flag RIGHT BEFORE we
                    // dispatch — the TUI may have flipped it during
                    // the 2s window.
                    if (dispatcher.isContinuationStopped(sessionId)) {
                        LOG.info("R89 boulder: user stopped during countdown, skipping dispatch (run {})", runId);
                        return;
                    }
                    // special-case research_mode to inject
                    // a stronger "you were stuck in research, start
                    // writing NOW" prompt instead of the standard
                    // R137 directive. The R137 prompt is good for
                    // general continuation but doesn't explicitly
                    // call out the recent failure pattern.
                    // also special-case
                    // loop_no_file_write_progress — the model
                    // had been making real progress (file_writes
                    // > 0) and then stalled; a "you've made
                    // progress, keep going" prompt is the right
                    // call here.
                    String prompt;
                    if ("loop_research_mode".equalsIgnoreCase(stopReason)) {
                        prompt = buildResearchModeRecoveryPrompt(incomplete);
                        state.researchModeRecoveryCount++;
                        LOG.info("对应历史 round: dispatching research-mode recovery continuation ({} incomplete, run {}, budget {}/{})",
                                incomplete.size(), runId,
                                state.researchModeRecoveryCount, MAX_RESEARCH_MODE_RECOVERIES);
                    } else if ("loop_no_file_write_progress".equalsIgnoreCase(stopReason)) {
                        prompt = buildProgressStallRecoveryPrompt(incomplete);
                        state.progressStallRecoveryCount++;
                        LOG.info("R151a: dispatching progress-stall recovery continuation ({} incomplete, run {}, budget {}/{})",
                                incomplete.size(), runId,
                                state.progressStallRecoveryCount, MAX_PROGRESS_STALL_RECOVERIES);
                    } else {
                        prompt = buildContinuationPrompt(incomplete);
                        LOG.info("R89 boulder: dispatching continuation ({} incomplete / {} total, run {})",
                                incomplete.size(), todoList.size(), runId);
                    }
                    state.lastInjectedAt = System.currentTimeMillis();
                    dispatcher.dispatchContinuation(sessionId, runId, prompt, incomplete);
                    // Success — reset the failure counter ONLY after
                    // the dispatch has returned without throwing.
                    // (If we reset before, a throwing dispatch would
                    // increment from 0 back to 1 instead of 1 → 2,
                    // and the failure counter would never grow.)
                    state.consecutiveFailures.set(0);
                } catch (Throwable dispatchErr) {
                    // A failing dispatcher bumps the failure
                    // counter and we let the cooldown backoff
                    // take over on the next idle event.
                    int f0 = state.consecutiveFailures.incrementAndGet();
                    state.lastInjectedAt = System.currentTimeMillis();
                    LOG.warn("R89 boulder: dispatch failed (failures={}/{}): {}",
                            f0, MAX_CONSECUTIVE_FAILURES, dispatchErr.getMessage());
                } finally {
                    state.inFlight.set(0);
                }
            }, countdownSeconds, TimeUnit.SECONDS);

            state.countdownTask = f;
        } catch (Throwable scheduleEx) {
            state.inFlight.set(0);
            LOG.warn("R89 boulder: failed to schedule continuation: {}", scheduleEx.getMessage());
        }
    }

    /**
     * Cancel a pending countdown (e.g. the user types a new
     * prompt, which clears the auto-continue flag, but the
     * scheduled task is still alive for up to 2s). Safe to
     * call when no countdown is scheduled.
     */
    public void cancelPendingCountdown(String sessionId) {
        SessionState state = stateBySession.get(sessionId);
        if (state == null) return;
        ScheduledFuture<?> f = state.countdownTask;
        if (f != null) f.cancel(false);
    }

    /**
     * For tests + status introspection. Returns the number
     * of consecutive failures the hook has recorded for
     * the session. 0 means "fresh / healthy".
     */
    public int consecutiveFailures(String sessionId) {
        SessionState state = stateBySession.get(sessionId);
        return state == null ? 0 : state.consecutiveFailures.get();
    }

    /**
     * for tests + status introspection. Returns the
     * number of research-mode auto-recoveries dispatched
     * for the session. Reset to 0 on a successful
     * end_turn. Capped at {@link #MAX_RESEARCH_MODE_RECOVERIES}.
     */
    public int researchModeRecoveryCount(String sessionId) {
        SessionState state = stateBySession.get(sessionId);
        return state == null ? 0 : state.researchModeRecoveryCount;
    }

    /**
     * read-only accessor for the progress-stall
     * auto-recovery counter. The number of no_file_write
     * auto-recoveries dispatched for the session. Reset
     * to 0 on a successful end_turn. Capped at
     * {@link #MAX_PROGRESS_STALL_RECOVERIES}. Tests +
     * introspection use this to verify the budget.
     */
    public int progressStallRecoveryCount(String sessionId) {
        SessionState state = stateBySession.get(sessionId);
        return state == null ? 0 : state.progressStallRecoveryCount;
    }

    /**
     * Build the continuation prompt. The shape mirrors
     * oh-my-opencode's {@code CONTINUATION_PROMPT} + the
     * todo-list summary it appends. We include both the
     * boulder marker ("proceed without asking, mark each
     * task complete, do not stop until all tasks are done")
     * and the list of remaining tasks so the model does
     * not have to re-read its own todo state.
     */
    static String buildContinuationPrompt(List<Map<String, Object>> incomplete) {
        StringBuilder sb = new StringBuilder();
        sb.append("<system-directive type=\"todo-continuation\">\n\n");
        sb.append("Incomplete tasks remain in your todo list. Continue working on the next pending task.\n\n");
        // be DIRECTIVE. The R89 prompt ("continue
        // working on the next pending task") was too
        // passive — the model often re-entered
        // "research mode" (file_read, bash version
        // checks) before doing any tool work, which
        // tripped the NoFileWriteProgressDetector and
        // turned the run into a loop. Force the model
        // to use file_write / file_edit on the next
        // turn; only fall back to read tools when
        // there's actually content missing.
        sb.append("- Proceed without asking for permission\n");
        sb.append("- Mark each task complete when finished\n");
        sb.append("- Do not stop until all tasks are done\n");
        sb.append("- **On this turn, your FIRST tool call\n");
        sb.append("  must be a writer (file_write / file_edit\n");
        sb.append("  / todo_write) — NOT a reader (file_read,\n");
        sb.append("  bash, glob).** Pick the next pending\n");
        sb.append("  task and start writing its file contents\n");
        sb.append("  immediately. You can read afterwards if\n");
        sb.append("  a specific detail is missing.\n");
        sb.append("- Do NOT run bash version-checks, do NOT\n");
        sb.append("  re-list the workspace, do NOT verify the\n");
        sb.append("  project structure. You wrote the project\n");
        sb.append("  moments ago; resume the build directly.\n");
        sb.append("- If you genuinely don't know what to\n");
        sb.append("  write next, call todo_write to break the\n");
        sb.append("  next pending task into smaller items, then\n");
        sb.append("  file_write the first sub-item immediately.\n\n");
        sb.append("Remaining tasks:\n");
        for (Map<String, Object> t : incomplete) {
            Object content = t.get("content");
            Object status = t.get("status");
            if (content == null) continue;
            sb.append("- [").append(status == null ? "pending" : status).append("] ")
                    .append(content).append("\n");
        }
        sb.append("</system-directive>");
        return sb.toString();
    }

    /**
     * research-mode recovery prompt. Used when the
     * previous run was hard-stopped by the prior round.1
     * research_mode detector — the model spent 3+ turns
     * doing {@code mvn -version} / {@code java -version}
     * / {@code echo} / {@code ls} / {@code pwd} before
     * any {@code file_write}. The standard R137 directive
     * is too soft for this case: the model has clearly
         * decided to "verify the environment" and needs to
     * be told that this verification is ALREADY DONE and
     * the next action MUST be a file_write.
     *
     * <p>Differences from {@link #buildContinuationPrompt}:
     * <ul>
     *   <li>Explicitly acknowledges the previous failure
     *       ("you just spent 3 turns on mvn -version")</li>
     *   <li>Hard ban on bash version checks (vs R137's
     *       softer "do not run bash version-checks")</li>
     *   <li>Requires file_write to be the FIRST tool call
     *       (not just "a writer" generically)</li>
     *   <li>Suggests a specific first file (the smallest
     *       one — e.g. {@code pom.xml} for a Java project)</li>
     * </ul>
     */
    static String buildResearchModeRecoveryPrompt(List<Map<String, Object>> incomplete) {
        StringBuilder sb = new StringBuilder();
        sb.append("<system-directive type=\"research-mode-recovery\">\n\n");
        sb.append("**RESEARCH-MODE RECOVERY**: Your previous run was\n");
        sb.append("hard-stopped because you spent 3+ consecutive turns\n");
        sb.append("on small-output shell commands (likely mvn -version,\n");
        sb.append("java -version, echo foo, ls, or pwd) without\n");
        sb.append("writing any file. Those environment checks are\n");
        sb.append("NOT what the user asked for. Resume the build NOW.\n\n");
        sb.append("STRICT RULES for this turn:\n");
        sb.append("- Your FIRST tool call MUST be `file_write`\n");
        sb.append("  (not bash, not file_read, not glob).\n");
        sb.append("- Write the SMALLEST file first — for a Java\n");
        sb.append("  project, that is `pom.xml`. For other\n");
        sb.append("  stacks, write the manifest / config first.\n");
        sb.append("- DO NOT run any bash command that does not\n");
        sb.append("  directly produce a file. `mvn -version`,\n");
        sb.append("  `java -version`, `which X`, `ls`, `pwd`,\n");
        sb.append("  `echo` are all BANNED on this turn.\n");
        sb.append("- DO NOT re-list the workspace. You already\n");
        sb.append("  did that 3 times; the directory is empty\n");
        sb.append("  or contains the project you're creating.\n");
        sb.append("- You may read a file ONLY if you cannot\n");
        sb.append("  remember its exact contents (rare).\n");
        sb.append("- After the first file_write succeeds, the\n");
        sb.append("  detector resets and you may continue\n");
        sb.append("  normally. Proceed without asking permission.\n\n");
        sb.append("Remaining tasks:\n");
        for (Map<String, Object> t : incomplete) {
            Object content = t.get("content");
            Object status = t.get("status");
            if (content == null) continue;
            sb.append("- [").append(status == null ? "pending" : status).append("] ")
                    .append(content).append("\n");
        }
        sb.append("</system-directive>");
        return sb.toString();
    }

    /** progress-stall recovery prompt. The
     *  R141 research-mode recovery is for the
     *  "model spent 3 turns on version checks before
     *  writing anything" pattern (file_writes = 0).
     *  prior round is for the "model had been making real
     *  progress and then stalled" pattern: 60+
     *  file_writes followed by 6 turns of small
     *  bash. A user-reported failure mode.
     *
     *  <p>The recovery prompt is softer than the
     *  research-mode one — the model wasn't lost, it
     *  just got distracted. We tell it the recent
     *  state ("you wrote 60+ files in this run") and
     *  remind it that the next action should be a
     *  writer, not a version check.
     *
     *  <p>Differences from
     *  {@link #buildResearchModeRecoveryPrompt}:
     *  <ul>
     *    <li>Acknowledges prior progress (so the
     *        model doesn't feel like it just failed)</li>
     *    <li>Allows {@code file_read} as the first
     *        call (in case the model needs to
     *        re-orient)</li>
     *    <li>Does NOT hard-ban bash (mvn test, npm
     *        test, etc. are legitimate file_write
     *        progress)</li>
     *    <li>Bans the SMALL-output research pattern
     *        (mvn -version, java -version, which,
     *        ls, pwd, echo) specifically</li>
     *  </ul>
     */
    static String buildProgressStallRecoveryPrompt(List<Map<String, Object>> incomplete) {
        StringBuilder sb = new StringBuilder();
        sb.append("<system-directive type=\"progress-stall-recovery\">\n\n");
        sb.append("**PROGRESS-STALL RECOVERY**: Your previous run was\n");
        sb.append("hard-stopped because the loop detector noticed 6+\n");
        sb.append("consecutive turns with no file_write AFTER you had\n");
        sb.append("already made real progress (60+ file_writes in\n");
        sb.append("this run). You were on a roll; you just got\n");
        sb.append("distracted by shell calls. Resume the build NOW.\n\n");
        sb.append("STRICT RULES for this turn:\n");
        sb.append("- Your next tool call should be EITHER\n");
        sb.append("  `file_write` (continue creating) OR `bash`\n");
        sb.append("  for a real command that produces > 1K of\n");
        sb.append("  output (e.g. `mvn test`, `npm test`,\n");
        sb.append("  `dir /s /b`) — NOT a version check.\n");
        sb.append("- DO NOT run any of: `mvn -version`,\n");
        sb.append("  `java -version`, `which X`, `echo foo`,\n");
        sb.append("  `ls`, `pwd`, `cat .gitignore` without\n");
        sb.append("  intent. Those are NOT progress.\n");
        sb.append("- If the next pending task is a shell\n");
        sb.append("  verification (mvn test, build, etc.),\n");
        sb.append("  run it — that's a valid form of progress.\n");
        sb.append("- `file_read` is OK as the FIRST call if you\n");
        sb.append("  genuinely need to re-check the file you\n");
        sb.append("  were editing (rare; only after a long\n");
        sb.append("  pause).\n");
        sb.append("- After the next file_write OR the next\n");
        sb.append("  big-output bash, the detector resets and\n");
        sb.append("  you may continue normally. Proceed without\n");
        sb.append("  asking permission.\n\n");
        sb.append("Remaining tasks:\n");
        for (Map<String, Object> t : incomplete) {
            Object content = t.get("content");
            Object status = t.get("status");
            if (content == null) continue;
            sb.append("- [").append(status == null ? "pending" : status).append("] ")
                    .append(content).append("\n");
        }
        sb.append("</system-directive>");
        return sb.toString();
    }

    private static List<Map<String, Object>> filterIncomplete(List<Map<String, Object>> todos) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : todos) {
            Object status = t.get("status");
            if (status == null) {
                out.add(t);
                continue;
            }
            String s = String.valueOf(status);
            if (!"completed".equalsIgnoreCase(s) && !"cancelled".equalsIgnoreCase(s)
                    && !"done".equalsIgnoreCase(s)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Per-session state. Kept as a static class so the
     *  fields are mutable and we don't pay for an
     *  AtomicReference dance on every idle event. */
    private static final class SessionState {
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        volatile long lastInjectedAt = 0L;
        /** 0 = idle, 1 = countdown / dispatch in flight. CAS so
         *  overlapping idle events can't double-schedule. */
        final AtomicInteger inFlight = new AtomicInteger(0);
        volatile ScheduledFuture<?> countdownTask;
        /** research-mode auto-recovery counter.
         *  Increments when a research_mode recovery
         *  continuation is dispatched. Resets on a
         *  successful end_turn (so a fresh task gets
         *  its own budget). The hard cap
         *  MAX_RESEARCH_MODE_RECOVERIES (default 1)
         *  prevents the R141 "loop of loops" failure
         *  mode. */
        int researchModeRecoveryCount = 0;
        /** progress-stall auto-recovery counter.
         *  Parallel to {@link #researchModeRecoveryCount}
         *  but for {@code loop_no_file_write_progress}
         *  (the user-reported stall: model had file_writes,
         *  then stopped writing, then small bash). Same
         *  hard-cap pattern; resets on the same stops
         *  (end_turn, awaiting_user_decision, completed). */
        int progressStallRecoveryCount = 0;
    }
}
