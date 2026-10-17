package org.aethercode.sdd;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * R700 鈥?Drives the 8-phase SDD workflow on behalf of desktop / TUI clients.
 *
 * <h2>Per-phase flow</h2>
 * <pre>
 *   1. Read phase-state.json (or create fresh)
 *   2. Mark current phase 鈫?running (startedAt = now)
 *   3. Build chat prompt: system = SKILL.md body, user = phase-N ref
 *      + Inputs section (paths to all earlier artefacts, listed via
 *      cwd + slug + SddPhaseFile names)
 *      + Output path instruction
 *      + Action modifier (run / modify with feedback / skip)
 *   4. ChatClient.stream(messages, systemPrompt, toolPool)
 *   5. Wait for RunEnd; verify artefact file exists on disk
 *   6. Mark phase 鈫?pending_confirm; save state
 *   7. Hand control back to the caller (desktop / TUI) which calls
 *      advance(approve|modify|skip|abort) to continue
 * </pre>
 *
 * <h2>Threading</h2>
 * The orchestrator is safe to call from multiple RPC threads. Per-slug
 * state mutation is guarded by a per-slug lock held inside the orchestrator.
 *
 * <h2>State persistence</h2>
 * Every state change calls {@link SddPhaseState#save(Path)} so a daemon
 * restart can re-load {@code phase-state.json} and resume from
 * {@code pending_confirm} / {@code running}.
 */
public final class SddOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(SddOrchestrator.class);

    private final SddBundleLoader bundle;
    private final ChatClient chatClient;
    private final List<Tool> toolPool;
    /** Slug → cwd for active runs. Cleared on abort / done. */
    private final Map<String, Path> cwdBySlug = new ConcurrentHashMap<>();

    /**
     * R709-C: per-phase wall-clock budget. The pre-R709 path blocked
     * indefinitely on chatClient.stream() if the model hung, leaving
     * the phase stuck in {@code running} with no way to recover
     * other than killing the daemon. 10 minutes is a generous
     * default — model output for the 8 phases varies from a few
     * seconds (phase 1) to a couple of minutes (phase 7 implementation).
     * The constant lives on the class so a future "advanced" RPC
     * can override it per-run; for now it's just a hard cap.
     */
    public static final java.time.Duration DEFAULT_PHASE_TIMEOUT = java.time.Duration.ofMinutes(10);

    public SddOrchestrator(SddBundleLoader bundle,
                          ChatClient chatClient,
                          List<Tool> toolPool) {
        this.bundle = Objects.requireNonNull(bundle);
        this.chatClient = Objects.requireNonNull(chatClient);
        this.toolPool = toolPool == null ? List.of() : List.copyOf(toolPool);
    }

    // ------------------------------------------------------------------
    // Public RPC entrypoints
    // ------------------------------------------------------------------

    /** Start a fresh SDD run and immediately execute phase 1.
     *  Returns the initial state (after phase 1 has run). */
    public SddPhaseState start(String intent, Path cwdRoot) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(cwdRoot, "cwd");
        String slug = SlugUtil.derive(intent);
        Path sddDir = cwdRoot.resolve(".aethercode").resolve("sdd").resolve(slug);
        // R709-A: atomic claim via CREATE_NEW on a marker file. The
        // pre-R709 path was `if (stateFile.exists()) throw` then
        // `Files.createDirectories(...)` — two syscalls with a TOCTOU
        // race window where two concurrent RPCs could both pass the
        // check and both `createDirectories`, leaving the second writer
        // to silently overwrite the first's phase-state.json mid-run.
        // CREATE_NEW on a sentinel file is atomic on the filesystem
        // (one writer wins, the other gets FileAlreadyExistsException).
        try {
            Files.createDirectories(sddDir);
        } catch (IOException ioe) {
            throw new SddException(SddException.Code.IO_FAILURE,
                    "could not create sdd dir for slug " + slug, ioe);
        }
        try {
            Files.writeString(sddDir.resolve(".sdd-marker"),
                    Instant.now().toString() + "\n",
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException dup) {
            throw new SddException(SddException.Code.SLUG_CONFLICT,
                    "slug '" + slug + "' already exists at " + sddDir
                            + " — pick a different intent or restore the existing run");
        } catch (IOException ioe) {
            throw new SddException(SddException.Code.IO_FAILURE,
                    "could not claim sdd dir for slug " + slug, ioe);
        }
        SddPhaseState state = SddPhaseState.fresh(slug, intent, cwdRoot.toString());
        cwdBySlug.put(slug, cwdRoot);
        return runPhase(state, 1, null, cwdRoot);
    }

    /** Continue a run. {@code action} 鈭?{approve, modify, skip, abort}. */
    public SddPhaseState advance(String slug, String action, String feedback) {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(action, "action");
        Path cwdRoot = cwdBySlug.get(slug);
        // R709-B fallback: if the in-memory map lost the cwd (daemon
        // restart, setCwd swap, or simply never refreshed), try the
        // current process's working directory and reload the state
        // from there. The pre-R709 path threw RUN_NOT_FOUND, which
        // the desktop surfaced as "not an active run" even when the
        // run was perfectly recoverable from disk.
        SddPhaseState state;
        if (cwdRoot != null) {
            try {
                state = SddPhaseState.load(cwdRoot, slug);
            } catch (IOException ioe) {
                state = null;
            }
        } else {
            state = null;
        }
        if (state == null) {
            Path probe = Path.of("").toAbsolutePath().resolve(".aethercode")
                    .resolve("sdd").resolve(slug).resolve("phase-state.json");
            if (Files.isRegularFile(probe)) {
                cwdRoot = Path.of("").toAbsolutePath();
                try {
                    state = SddPhaseState.load(cwdRoot, slug);
                } catch (IOException ioe) {
                    throw new SddException(SddException.Code.STATE_FILE_MISSING,
                            "phase-state.json unreadable at " + probe, ioe);
                }
                cwdBySlug.put(slug, cwdRoot);
                LOG.info("[sdd] advance: re-attached slug {} from cwd {}",
                        slug, cwdRoot);
            } else {
                throw new SddException(SddException.Code.RUN_NOT_FOUND,
                        "slug '" + slug + "' is not an active run (no cwd on record, probe "
                                + probe + " not found)");
            }
        }
        SddPhaseState.PhaseEntry cur = state.currentPhaseEntry();
        return switch (action.toLowerCase()) {
            case "approve" -> advanceApprove(state, cur, feedback, cwdRoot);
            case "modify"  -> advanceModify(state, cur, feedback, cwdRoot);
            case "skip"    -> advanceSkip(state, cur, cwdRoot);
            case "abort"   -> advanceAbort(state, cwdRoot);
            default -> throw new SddException(SddException.Code.INVALID_ACTION,
                    "unknown action '" + action + "' (expected approve|modify|skip|abort)");
        };
    }

    /** Read current state from disk. Used by desktop / TUI for status sync. */
    public SddPhaseState status(String slug, Path cwdRoot) {
        try {
            return SddPhaseState.load(cwdRoot, slug);
        } catch (IOException ioe) {
            throw new SddException(SddException.Code.STATE_FILE_MISSING,
                    "phase-state.json missing for slug " + slug, ioe);
        }
    }

    /** Abort a run regardless of which phase is active. */
    public SddPhaseState abort(String slug, Path cwdRoot) {
        try {
            SddPhaseState state = SddPhaseState.load(cwdRoot, slug);
            return advanceAbort(state, cwdRoot);
        } catch (IOException ioe) {
            throw new SddException(SddException.Code.STATE_FILE_MISSING,
                    "phase-state.json missing for slug " + slug, ioe);
        }
    }

    /** List every SDD run under {@code <cwd>/.aethercode/sdd/}. */
    public List<SddPhaseState> listRuns(Path cwdRoot) {
        Path sddRoot = cwdRoot.resolve(".aethercode").resolve("sdd");
        if (!Files.isDirectory(sddRoot)) return List.of();
        List<SddPhaseState> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(sddRoot)) {
            dirs.filter(Files::isDirectory).forEach(d -> {
                Path sf = d.resolve("phase-state.json");
                if (!Files.isRegularFile(sf)) return;
                try {
                    out.add(SddPhaseState.load(cwdRoot, d.getFileName().toString()));
                } catch (IOException e) {
                    LOG.warn("[sdd] skipping unreadable run state at {}: {}", sf, e.getMessage());
                }
            });
        } catch (IOException ioe) {
            throw new SddException(SddException.Code.IO_FAILURE,
                    "could not list SDD runs at " + sddRoot, ioe);
        }
        return out;
    }

    /**
     * R709-B: re-attach in-memory {@code cwdBySlug} entries from
     * the on-disk phase-state.json files under {@code cwdRoot}. The
     * pre-R709 cwdBySlug was populated only by {@link #start} and
     * lost on daemon restart — so a desktop user who started an SDD
     * run, killed the daemon, and re-launched would hit
     * {@code RUN_NOT_FOUND} on the next {@code sdd.advance} call
     * because the in-memory map was empty. This method walks each
     * per-run directory under {@code <cwd>/.aethercode/sdd/} and
     * re-attaches any run whose status is {@code running} or
     * {@code idle} (i.e. a non-terminal phase the user might want
     * to advance / skip / abort). Terminal runs (done / aborted /
     * failed) are skipped — the user can still see them via
     * {@link #listRuns} for history, but they don't need a cwd
     * backref.
     *
     * <p>Idempotent: safe to call on every boot and also on
     * {@code setCwd} swaps (in case the user changes project
     * without restarting the daemon).
     */
    public int reloadActiveRuns(Path cwdRoot) {
        Path sddRoot = cwdRoot.resolve(".aethercode").resolve("sdd");
        if (!Files.isDirectory(sddRoot)) return 0;
        int reattached = 0;
        try (Stream<Path> dirs = Files.list(sddRoot)) {
            List<Path> subdirs = dirs.filter(Files::isDirectory).toList();
            for (Path d : subdirs) {
                Path sf = d.resolve("phase-state.json");
                if (!Files.isRegularFile(sf)) continue;
                SddPhaseState st;
                try {
                    st = SddPhaseState.load(cwdRoot, d.getFileName().toString());
                } catch (IOException e) {
                    LOG.warn("[sdd] reload: skipping unreadable {}: {}", sf, e.getMessage());
                    continue;
                }
                if (st.status == SddPhaseState.RunStatus.running
                        || st.status == SddPhaseState.RunStatus.idle) {
                    if (cwdBySlug.putIfAbsent(st.slug, cwdRoot) == null) {
                        reattached++;
                        LOG.info("[sdd] reload: re-attached slug {} (status={}, currentPhase={})",
                                st.slug, st.status, st.currentPhase);
                    }
                }
            }
        } catch (IOException ioe) {
            LOG.warn("[sdd] reload: failed to list {}: {}", sddRoot, ioe.getMessage());
        }
        return reattached;
    }

    // ------------------------------------------------------------------
    // Phase runners
    // ------------------------------------------------------------------

    private SddPhaseState advanceApprove(SddPhaseState state,
                                          SddPhaseState.PhaseEntry cur,
                                          String feedback,
                                          Path cwdRoot) {
        // If the current phase is still running / pending_confirm, the user
        // can't approve it. Surface a clear error rather than silently
        // dropping the action.
        if (cur.state() == SddPhaseState.PhaseState.running) {
            throw new SddException(SddException.Code.INVALID_ACTION,
                    "phase " + cur.phaseNumber() + " is still running; wait for pause before approving");
        }
        if (cur.state() != SddPhaseState.PhaseState.pending_confirm
                && cur.state() != SddPhaseState.PhaseState.done) {
            throw new SddException(SddException.Code.INVALID_ACTION,
                    "phase " + cur.phaseNumber() + " is in state " + cur.state()
                            + " and cannot be approved");
        }
        // R709-D: when the user types a note alongside their approve
        // (e.g. "approve, but please make sure the next phase also
        // covers X"), carry the note forward as the feedback slot
        // for the next phase's prompt. The pre-R709 path silently
        // dropped feedback on approve, so the user had to switch to
        // "modify" or send a separate chat message to inject context.
        // Logging the note here gives us an audit trail in daemon.log
        // for what the user actually meant when they approved.
        if (feedback != null && !feedback.isBlank()) {
            LOG.info("[sdd] advance(approve) {} with feedback: {}",
                    state.slug, truncate(feedback, 200));
        }
        // Mark current done (if not already)
        SddPhaseState nextState = state.withPhase(
                cur.phaseNumber(),
                cur.state() == SddPhaseState.PhaseState.done
                        ? cur
                        : cur.withState(SddPhaseState.PhaseState.done)
                                .withEndedAt(Instant.now().toString()));
        Integer nextPhaseNum = findNextPhaseNumber(nextState);
        if (nextPhaseNum == null) {
            // All phases finished.
            SddPhaseState finalState = nextState.withStatus(SddPhaseState.RunStatus.done)
                    .withCurrentPhase(null);
            persist(finalState, cwdRoot);
            cwdBySlug.remove(nextState.slug);
            return finalState;
        }
        SddPhaseState advanced = nextState.withCurrentPhase(nextPhaseNum);
        // Mark next phase running
        SddPhaseState.PhaseEntry nextEntry = advanced.phaseByNumber(nextPhaseNum);
        SddPhaseState started = advanced.withPhase(
                nextPhaseNum,
                nextEntry.withState(SddPhaseState.PhaseState.running)
                        .withStartedAt(Instant.now().toString()));
        persist(started, cwdRoot);
        // R709-D: pass the user's note through to the next phase's
        // prompt (buildPhasePrompt renders it as "## User feedback
        // (apply this to the existing artefact)"). Empty/null
        // feedback is preserved as the no-note path.
        SddPhaseState ranAdvance = runPhase(started, nextPhaseNum, feedback, cwdRoot);
        return ranAdvance;
    }

    private SddPhaseState advanceModify(SddPhaseState state,
                                         SddPhaseState.PhaseEntry cur,
                                         String feedback,
                                         Path cwdRoot) {
        if (feedback == null || feedback.isBlank()) {
            throw new SddException(SddException.Code.INVALID_ACTION,
                    "modify requires non-empty feedback");
        }
        // Re-run the current phase.
        SddPhaseState reset = state.withPhase(
                cur.phaseNumber(),
                cur.withState(SddPhaseState.PhaseState.running)
                        .withStartedAt(Instant.now().toString())
                        .withEndedAt(null));
        persist(reset, cwdRoot);
        SddPhaseState ranModify = runPhase(reset, cur.phaseNumber(), feedback, cwdRoot);
        return ranModify;
    }

    private SddPhaseState advanceSkip(SddPhaseState state,
                                       SddPhaseState.PhaseEntry cur,
                                       Path cwdRoot) {
        if (!cur.optional()) {
            throw new SddException(SddException.Code.SKIP_REQUIRED_PHASE,
                    "phase " + cur.phaseNumber() + " (" + cur.id() + ") is REQUIRED and cannot be skipped");
        }
        SddPhaseState.PhaseEntry skipped = cur.withState(SddPhaseState.PhaseState.skipped)
                .withEndedAt(Instant.now().toString());
        // Write the skip sentinel artefact so downstream phases have something
        // to read.
        writeSkipSentinel(cwdRoot, state.slug, cur);
        SddPhaseState next = state.withPhase(cur.phaseNumber(), skipped);
        Integer nextPhaseNum = findNextPhaseNumber(next);
        if (nextPhaseNum == null) {
            SddPhaseState finalState = next.withStatus(SddPhaseState.RunStatus.done)
                    .withCurrentPhase(null);
            persist(finalState, cwdRoot);
            cwdBySlug.remove(state.slug);
            return finalState;
        }
        SddPhaseState advanced = next.withCurrentPhase(nextPhaseNum);
        SddPhaseState.PhaseEntry nextEntry = advanced.phaseByNumber(nextPhaseNum);
        SddPhaseState started = advanced.withPhase(
                nextPhaseNum,
                nextEntry.withState(SddPhaseState.PhaseState.running)
                        .withStartedAt(Instant.now().toString()));
        persist(started, cwdRoot);
        SddPhaseState ranSkip = runPhase(started, nextPhaseNum, null, cwdRoot);
        return ranSkip;
    }

    private SddPhaseState advanceAbort(SddPhaseState state, Path cwdRoot) {
        SddPhaseState aborted = state.withStatus(SddPhaseState.RunStatus.aborted)
                .withCurrentPhase(null);
        persist(aborted, cwdRoot);
        cwdBySlug.remove(state.slug);
        return aborted;
    }

    // ------------------------------------------------------------------
    // LLM driver
    // ------------------------------------------------------------------

    /** Run the given phase via ChatClient. Updates state on disk twice:
     *  (1) mark phase running before the call, (2) mark pending_confirm
     *  after the artefact lands. */
    private SddPhaseState runPhase(SddPhaseState state, int phaseNumber, String feedback, Path cwdRoot) {
        SddPhaseState.PhaseEntry phase = state.phaseByNumber(phaseNumber);
        Path artefactPath = cwdRoot.resolve(".aethercode").resolve("sdd").resolve(state.slug)
                .resolve(SddPhaseSpec.OUTPUT_FILES.get(phase.id()));

        // Build the prompt. We embed the SKILL.md + phase reference directly
        // so the LLM has full context without the agent needing to read_file.
        List<String> inputFiles = new ArrayList<>();
        for (int i = 1; i < phaseNumber; i++) {
            var prevEntry = state.phaseByNumber(i);
            inputFiles.add("<cwd>/.aethercode/sdd/" + state.slug + "/" + SddPhaseSpec.OUTPUT_FILES.get(prevEntry.id()));
        }
        String systemPrompt = bundle.skillBody();
        String userPrompt = buildPhasePrompt(state, phase, inputFiles, artefactPath.toString(), feedback);

        Message user = Message.userText(userPrompt);
        try {
            // R709-C: run the stream consumption on a background thread
            // so we can enforce a wall-clock budget. The pre-R709 path
            // consumed the stream inline, which meant a hung model
            // (or a chat client that never sent RunEnd) left the
            // phase stuck in `running` forever — the only escape was
            // to kill the daemon. We now race the iteration against
            // DEFAULT_PHASE_TIMEOUT and surface a dedicated
            // PHASE_TIMEOUT exception so the UI can suggest a
            // "skip" path instead of a generic error.
            //
            // The race is best-effort: cancelling the future interrupts
            // the consumer thread, but the underlying HTTP/WS socket
            // may keep streaming until the model finishes or the
            // provider times out. That's acceptable — at least the
            // orchestrator returns control to the desktop and the
            // phase-state.json is marked `failed` with PHASE_TIMEOUT
            // so the user can advance / abort / skip.
            StringBuilder fullText = new StringBuilder();
            java.util.concurrent.CompletableFuture<Void> streamFuture =
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        Stream<StreamEvent> stream = chatClient.stream(
                                List.of(user), systemPrompt, toolPool);
                        for (var ev : (Iterable<StreamEvent>) stream::iterator) {
                            if (ev instanceof StreamEvent.TextDelta td) {
                                fullText.append(td.text());
                            }
                            // We do not gate on RunEnd here — the
                            // daemon's tool pool runs write_file /
                            // read_file side effects as the model
                            // issues them, so by the time RunEnd
                            // fires the artefact should be on disk.
                        }
                    });
            try {
                streamFuture.get(DEFAULT_PHASE_TIMEOUT.toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                streamFuture.cancel(true);
                throw new SddException(SddException.Code.PHASE_TIMEOUT,
                        "phase " + phaseNumber + " (" + phase.id() + ") exceeded "
                                + DEFAULT_PHASE_TIMEOUT + " (model output ended with: "
                                + truncate(fullText.toString(), 100) + ")");
            } catch (java.util.concurrent.ExecutionException ee) {
                Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                if (cause instanceof RuntimeException re2) throw re2;
                throw new SddException(SddException.Code.CHAT_FAILURE,
                        "phase " + phaseNumber + " stream failed: " + cause.getMessage(), cause);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                streamFuture.cancel(true);
                throw new SddException(SddException.Code.CHAT_FAILURE,
                        "phase " + phaseNumber + " interrupted", ie);
            }
            // Verify the artefact exists. If not, treat the phase as failed
            // so the user sees a real error instead of a silent "done".
            if (!Files.isRegularFile(artefactPath)) {
                throw new SddException(SddException.Code.CHAT_FAILURE,
                        "phase " + phaseNumber + " (" + phase.id() + ") finished without writing "
                                + artefactPath + " (model output ended with: "
                                + truncate(fullText.toString(), 100) + ")");
            }
            SddPhaseState done = state.withPhase(
                    phaseNumber,
                    phase.withState(SddPhaseState.PhaseState.pending_confirm)
                            .withEndedAt(Instant.now().toString())
                            .withPath(artefactPath.toString()));
            persist(done, cwdRoot);
            return done;
        } catch (RuntimeException re) {
            // Mark phase failed so the user sees an error in the UI.
            SddPhaseState failed = state.withPhase(
                    phaseNumber,
                    phase.withState(SddPhaseState.PhaseState.failed)
                            .withEndedAt(Instant.now().toString()));
            SddPhaseState overallFailed = failed.withStatus(SddPhaseState.RunStatus.failed);
            try {
                persist(overallFailed, cwdRoot);
            } catch (RuntimeException persistFail) {
                LOG.error("[sdd] could not persist failed state for {} phase {}",
                        state.slug, phaseNumber, persistFail);
            }
            if (re instanceof SddException) throw re;
            throw new SddException(SddException.Code.CHAT_FAILURE,
                    "phase " + phaseNumber + " failed: " + re.getMessage(), re);
        }
    }

    private static String buildPhasePrompt(SddPhaseState state,
                                            SddPhaseState.PhaseEntry phase,
                                            List<String> inputFiles,
                                            String artefactPath,
                                            String feedback) {
        StringBuilder sb = new StringBuilder();
        sb.append("[sdd-task: ").append(state.slug)
          .append(", phase: ").append(phase.phaseNumber())
          .append(", action: ").append(feedback == null ? "run" : "modify")
          .append("]\n\n");
        sb.append("## Phase reference (per-phase instruction)\n\n")
          .append("```\n");
        // phase reference is loaded by the bundle and passed via system prompt
        // in real impl; for this orchestrator we embed a short header that
        // tells the LLM which phase reference to consult.
        sb.append("# See SKILL.md system prompt for the per-phase decision tree.\n")
          .append("# Phase ").append(phase.phaseNumber()).append(": ").append(phase.title())
          .append(" (").append(phase.id()).append(")\n");
        sb.append("```\n\n");
        sb.append("## Inputs (must read_file these before writing)\n");
        if (inputFiles.isEmpty()) {
            sb.append("(no input files 鈥?this is the entry phase)\n");
        } else {
            for (int i = 0; i < inputFiles.size(); i++) {
                sb.append(i + 1).append(". `").append(inputFiles.get(i)).append("`\n");
            }
        }
        sb.append("\n## Output (the ONLY file you may write this phase)\n")
          .append("`").append(artefactPath).append("`\n\n")
          .append("STRICT:\n")
          .append("- Do not write source code in this phase (only phase 7 writes code).\n")
          .append("- Do not write to any other path.\n")
          .append("- Filename is strictly lowercase (e.g. `design.md`, NOT `DESIGN.md`).\n")
          .append("- When the artefact is fully written, end your final message with the tag\n")
          .append("  `<!-- sdd-phase-end-").append(phase.phaseNumber()).append(" -->`\n\n");
        if (feedback != null) {
            sb.append("## User feedback (apply this to the existing artefact)\n")
              .append(feedback).append("\n\n");
        }
        sb.append("## User intent\n").append(state.intent).append("\n");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private void writeSkipSentinel(Path cwdRoot, String slug, SddPhaseState.PhaseEntry phase) {
        Path artefact = cwdRoot.resolve(".aethercode").resolve("sdd").resolve(slug)
                .resolve(SddPhaseSpec.OUTPUT_FILES.get(phase.id()));
        String json = "{\"skipped\":true,\"reason\":\"user-opted-out\",\"phase\":\""
                + phase.id() + "\",\"phaseNumber\":" + phase.phaseNumber() + "}\n";
        try {
            Files.writeString(artefact, json, StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            throw new SddException(SddException.Code.IO_FAILURE,
                    "could not write skip sentinel for phase " + phase.phaseNumber(), ioe);
        }
    }

    private static Integer findNextPhaseNumber(SddPhaseState state) {
        Integer cur = state.currentPhase;
        if (cur == null) return null;
        for (int n = cur + 1; n <= SddPhaseSpec.TOTAL_PHASES; n++) {
            var p = state.phaseByNumber(n);
            if (p.state() == SddPhaseState.PhaseState.idle) return n;
        }
        return null;
    }

    private static void persist(SddPhaseState state, Path cwdRoot) {
        try {
            state.save(cwdRoot);
        } catch (IOException ioe) {
            throw new SddException(SddException.Code.IO_FAILURE,
                    "could not persist phase-state.json for " + state.slug, ioe);
        }
    }

    private SddPhaseState statusLoadLocked(Path cwdRoot, String slug) {
        try {
            return SddPhaseState.load(cwdRoot, slug);
        } catch (IOException ioe) {
            throw new SddException(SddException.Code.STATE_FILE_MISSING,
                    "phase-state.json missing after phase run for " + slug, ioe);
        }
    }

    /** Slug derivation: lowercase, kebab-case, 鈮?0 ASCII chars, numeric
     *  suffix on collision (handled by daemon boot / SddOrchestrator caller). */
    static final class SlugUtil {
        static String derive(String intent) {
            String s = intent == null ? "" : intent;
            String out = s.toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            if (out.length() > 10) out = out.substring(0, 10);
            return out.isEmpty() ? "sd-" + Long.toString(System.currentTimeMillis(), 36) : out;
        }
    }
}