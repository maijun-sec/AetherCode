package org.aethercode.tasks.limits;

import org.aethercode.tasks.lifecycle.LimitsHitService;
import org.aethercode.tasks.supervisor.ChildEventRecord;
import org.aethercode.tasks.supervisor.ChildRecord;
import org.aethercode.tasks.supervisor.ChildStatus;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * prior round (T-352/§4.6 design.md): the "pause and ask" glue that
 * sits on top of {@link LimitsHitService}. When a cap is
 * tripped, the policy:
 *
 * <ol>
 *   <li>evaluates the {@link Usage} against the {@link Limits},</li>
 *   <li>pauses the child (via the {@link LimitsHitService} so the
 *       {@code limits_hit} column + event are persisted),</li>
 *   <li>invokes the registered {@link Decision} callback with the
 *       pause summary so the UI (TUI, desktop, CLI) can prompt
 *       the user.</li>
 *   <li>applies the user's answer: {@code CONTINUE} resumes the
 *       child at its current limits, {@code RAISE} replaces the
 *       limits in the child's {@code config} blob and resumes,
 *       {@code CANCEL} kills the child.</li>
 * </ol>
 *
 * <p>The callback model keeps the lifecycle glue free of any
 * concrete UI dependency. The CLI and the TUI can each wire
 * their own prompt; tests use a stub that records the calls.
 *
 * <p>The policy is safe to share across threads. Pending
 * decisions are keyed by {@code childId}; a duplicate
 * {@code check} for a child that is already paused is treated
 * as a no-op (the original decision is not interrupted).
 */
public final class LimitsPausePolicy {

    private static final Logger LOG = LoggerFactory.getLogger(LimitsPausePolicy.class);

    /**
     * The user-visible decision for a paused child. Mirrors
     * design.md §4.6 ("continue / cancel / adjust the limit").
     */
    public enum Decision { CONTINUE, RAISE, CANCEL }

    /** Prompt payload sent to the UI for a paused child. */
    public record PausePrompt(
            String childId,
            List<LimitsEnforcer.LimitHit> tripped,
            Limits currentLimits,
            String cwd,
            String promptExcerpt) {}

    /** Callback the UI must implement to ask the user. */
    @FunctionalInterface
    public interface AskUser {
        Decision ask(PausePrompt prompt);
    }

    /** Callback the policy uses to persist a new limits blob. */
    @FunctionalInterface
    public interface LimitWriter {
        void write(String childId, Limits limits) throws SQLException;
    }

    private final SupervisorStore store;
    private final LimitsHitService hitService;
    private final AskUser askUser;
    private final LimitWriter limitWriter;
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong totalAsks = new AtomicLong(0L);
    private final AtomicLong totalContinues = new AtomicLong(0L);
    private final AtomicLong totalRaises = new AtomicLong(0L);
    private final AtomicLong totalCancels = new AtomicLong(0L);

    public LimitsPausePolicy(SupervisorStore store,
                             LimitsHitService hitService,
                             AskUser askUser,
                             LimitWriter limitWriter) {
        this.store = Objects.requireNonNull(store, "store");
        this.hitService = Objects.requireNonNull(hitService, "hitService");
        this.askUser = Objects.requireNonNull(askUser, "askUser");
        this.limitWriter = Objects.requireNonNull(limitWriter, "limitWriter");
    }

    /** Total number of {@link #check} calls that produced a prompt. */
    public long totalAsks() { return totalAsks.get(); }
    public long totalContinues() { return totalContinues.get(); }
    public long totalRaises()   { return totalRaises.get(); }
    public long totalCancels()  { return totalCancels.get(); }

    /**
     * Evaluate the limits, pause the child if any cap is tripped,
     * and ask the user for a decision. The decision is applied
     * inline (synchronously) — tests should not have to deal
     * with async/await for a single-shot ask.
     *
     * <p>Returns the {@link Decision} the user picked (or
     * {@code null} if no cap was tripped and no ask happened).
     */
    public Decision check(String childId, Limits limits, LimitsEnforcer.Usage usage)
            throws SQLException {
        Objects.requireNonNull(childId, "childId");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(usage, "usage");
        LimitsEnforcer.Verdict verdict = hitService.check(childId, limits, usage);
        if (!verdict.any()) return null;
        return askAndApply(childId, limits, verdict);
    }

    /**
     * Re-prompt for a child that was paused earlier (e.g. the
     * user pressed "decide now" in the TUI). The decision is
     * applied the same way {@link #check} applies it.
     */
    public Decision askPending(String childId) throws SQLException {
        Optional<ChildRecord> opt = store.getChild(childId);
        if (opt.isEmpty()) throw new IllegalArgumentException("unknown childId: " + childId);
        if (opt.get().status() != ChildStatus.PAUSED) {
            throw new IllegalStateException("child " + childId + " is not PAUSED, got "
                    + opt.get().status());
        }
        Limits current = hitService.loadLimits(childId).orElse(Limits.unlimited());
        return askAndApply(childId, current,
                new LimitsEnforcer.Verdict(parsePersistedHits(opt.get().limitsHitJson())));
    }

    private Decision askAndApply(String childId, Limits current, LimitsEnforcer.Verdict verdict)
            throws SQLException {
        if (inFlight.putIfAbsent(childId, Boolean.TRUE) != null) {
            // A decision is already pending for this child. Don't
            // re-prompt; the first ask wins.
            return null;
        }
        try {
            totalAsks.incrementAndGet();
            PausePrompt prompt = buildPrompt(childId, current, verdict);
            Decision d = askUser.ask(prompt);
            if (d == null) d = Decision.CANCEL; // defensive default
            applyDecision(childId, d, current, verdict);
            return d;
        } finally {
            inFlight.remove(childId);
        }
    }

    private PausePrompt buildPrompt(String childId, Limits current, LimitsEnforcer.Verdict v) {
        String cwd = "";
        String promptExcerpt = "";
        try {
            Optional<ChildRecord> opt = store.getChild(childId);
            if (opt.isPresent()) {
                cwd = opt.get().cwd();
                String p = opt.get().prompt();
                if (p != null) {
                    promptExcerpt = p.length() > 80 ? p.substring(0, 77) + "..." : p;
                }
            }
        } catch (SQLException ignored) {}
        return new PausePrompt(childId, v.tripped(), current, cwd, promptExcerpt);
    }

    private void applyDecision(String childId, Decision d, Limits current,
                               LimitsEnforcer.Verdict v) throws SQLException {
        switch (d) {
            case CONTINUE -> {
                totalContinues.incrementAndGet();
                resumeIfPaused(childId);
            }
            case RAISE -> {
                totalRaises.incrementAndGet();
                Limits raised = raiseLimits(current, v);
                limitWriter.write(childId, raised);
                resumeIfPaused(childId);
            }
            case CANCEL -> {
                totalCancels.incrementAndGet();
                killIfNotTerminal(childId, "user cancelled after limits hit");
            }
        }
    }

    private void resumeIfPaused(String childId) throws SQLException {
        Optional<ChildRecord> opt = store.getChild(childId);
        if (opt.isEmpty()) return;
        if (opt.get().status() == ChildStatus.PAUSED) {
            store.updateStatus(childId, ChildStatus.RUNNING);
            store.appendEvent(childId, ChildEventRecord.TYPE_STATUS_CHANGE,
                    "{\"to\":\"RUNNING\",\"reason\":\"limits-resume\"}");
        }
    }

    private void killIfNotTerminal(String childId, String reason) throws SQLException {
        Optional<ChildRecord> opt = store.getChild(childId);
        if (opt.isEmpty()) return;
        if (opt.get().status().isTerminal()) return;
        store.updateStatus(childId, ChildStatus.KILLED);
        store.setError(childId, reason);
        store.appendEvent(childId, ChildEventRecord.TYPE_STATUS_CHANGE,
                "{\"to\":\"KILLED\",\"reason\":\"limits-cancel\"}");
    }

    /**
     * Bump every tripped cap to {@code max(2 * actual, 2 *
     * limit)}; for caps that were not tripped, keep the existing
     * value (the user only asked to "raise", not "remove all
     * caps"). If a cap is null (unlimited), leave it null.
     */
    private static Limits raiseLimits(Limits current, LimitsEnforcer.Verdict v) {
        Map<String, Long> byName = new LinkedHashMap<>();
        for (LimitsEnforcer.LimitHit h : v.tripped()) {
            long bumped = Math.max(h.actual() * 2L, h.limit() * 2L);
            byName.put(h.name(), bumped);
        }
        Limits.Builder b = Limits.builder();
        if (current.wallClockMs() != null) {
            b.wallClockMs(byName.getOrDefault("wallClockMs", current.wallClockMs()));
        }
        if (current.tokens() != null) {
            b.tokens(byName.getOrDefault("tokens", current.tokens()));
        }
        if (current.calls() != null) {
            b.calls(byName.getOrDefault("calls", current.calls()));
        }
        if (current.fileWrites() != null) {
            b.fileWrites(byName.getOrDefault("fileWrites", current.fileWrites()));
        }
        if (current.network() != null) {
            b.network(byName.getOrDefault("network", current.network()));
        }
        return b.build();
    }

    /** Re-parse the {@code limits_hit} JSON column for re-asks. */
    private static List<LimitsEnforcer.LimitHit> parsePersistedHits(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) return List.of();
        java.util.List<LimitsEnforcer.LimitHit> out = new java.util.ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode arr =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (arr.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                    String name = n.path("name").asText();
                    long limit = n.path("limit").asLong();
                    long actual = n.path("actual").asLong();
                    if (!name.isEmpty()) {
                        out.add(new LimitsEnforcer.LimitHit(name, limit, actual));
                    }
                }
            }
        } catch (Exception ignored) {}
        return out;
    }
}
