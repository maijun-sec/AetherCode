package org.aethercode.evals.orchestration;

import org.aethercode.evals.selfcorrect.SelfCorrectionLoop;
import org.aethercode.evals.verifier.Verifier.VerificationResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Append-only trace of one {@link AgentRuntime} invocation.
 *
 * <p>Carries every (action, verifier result) pair the runtime
 * observed, so an audit log can answer "what did the agent try,
 * which verifiers failed, how did self-correction react?" without
 * re-running anything.</p>
 *
 * <p>The trace is intentionally lightweight — just a list of
 * {@link Entry} records. A real audit pipeline would enrich each
 * entry with timestamps / token counts / model ids, but those are
 * outside the runtime's scope.</p>
 */
public class RuntimeTrace {

    /** A single action + verifier-result pair (or ensemble marker). */
    public record Entry(String phase, Object action, VerificationResult result) {
        public Entry {
            if (phase == null || phase.isBlank()) {
                throw new IllegalArgumentException("phase must be non-blank");
            }
            if (action == null) {
                throw new IllegalArgumentException("action must be non-null");
            }
            if (result == null) {
                throw new IllegalArgumentException("result must be non-null");
            }
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public void add(Entry e) { entries.add(e); }

    /**
     * Fold a {@link SelfCorrectionLoop.LoopResult} into the trace so
     * the audit log shows every self-correction attempt. The loop
     * result is preserved as a whole; the trace just lists the
     * per-attempt entries for inspection.
     */
    public void absorb(SelfCorrectionLoop.LoopResult<?> lr) {
        if (lr == null) return;
        for (SelfCorrectionLoop.Attempt a : lr.attempts()) {
            entries.add(new Entry("self-correct", a.action(), a.result()));
        }
    }

    public List<Entry> entries() { return List.copyOf(entries); }

    public int size() { return entries.size(); }

    /** Last entry, or null if the trace is empty. */
    public Entry last() { return entries.isEmpty() ? null : entries.get(entries.size() - 1); }
}
