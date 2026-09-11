package org.aethercode.code.hooks;

import org.aethercode.code.hooks.HookDomainEvents.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Standalone orchestration for the Hooks v2 execution engine.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.engine.HookEngine} record. The engine
 * matches handlers, runs them concurrently with bounded time, and
 * reduces their results through the {@link Reducer}.</p>
 */
public final class HookEngine {

    private static final Logger LOG = LoggerFactory.getLogger(HookEngine.class);

    private final Snapshot.HooksSnapshot snapshot;
    private final Double defaultTimeout;
    private final int maxOutputBytes;
    private final HookEnvelopeAdapter adapter;

    public HookEngine(Snapshot.HooksSnapshot snapshot) {
        this(snapshot, null, Runner.MAX_HOOK_OUTPUT_BYTES, new HookEnvelopeAdapter());
    }

    public HookEngine(Snapshot.HooksSnapshot snapshot, Double defaultTimeout, int maxOutputBytes,
                      HookEnvelopeAdapter adapter) {
        this.snapshot = snapshot;
        this.defaultTimeout = defaultTimeout;
        this.maxOutputBytes = maxOutputBytes;
        this.adapter = adapter;
    }

    /**
     * Execute matching handlers and return a normalized decision.
     *
     * @return the event-specific decision produced by ordered hook
     *         reduction
     */
    public DecisionEngineResult run(HookInvocation invocation, Path transcriptPath,
                                    Path agentTranscriptPath,
                                    Presenter.HookProgressCallback onProgress) {
        Snapshot.HookMatch match = snapshot.match(invocation);
        byte[] payload;
        try {
            payload = adapter.serialize(invocation, transcriptPath, agentTranscriptPath);
        } catch (RuntimeException ex) {
            HookDiagnostic diagnostic = new HookDiagnostic(
                    "projection_failed", HookDiagnostic.Severity.WARNING,
                    "Could not project hook invocation: " + ex.getMessage(), null, null);
            Decision decision = adapter.reduce(invocation, List.of(),
                    concat(match.diagnostics(), List.of(diagnostic)));
            return new DecisionEngineResult(decision, match);
        }
        double eventDefault = defaultTimeout != null
                ? defaultTimeout
                : Capabilities.getEventSpec(invocation.event().event()).defaultTimeoutSeconds();
        List<CompletableFuture<HookEnvelopeAdapter.HandlerResult>> futures = new ArrayList<>();
        for (Snapshot.HookHandler handler : match.handlers()) {
            String operationId = System.identityHashCode(invocation) + ":" + handler.id();
            futures.add(Runner.runCommandHandler(
                    handler, payload, invocation.context().cwd(), eventDefault,
                    maxOutputBytes, Env.sanitizeHookEnviron(null), operationId, onProgress));
        }
        List<HookEnvelopeAdapter.HandlerResult> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();
        Decision decision = adapter.reduce(invocation, results, match.diagnostics());
        return new DecisionEngineResult(decision, match);
    }

    private static List<HookDiagnostic> concat(List<HookDiagnostic> a, List<HookDiagnostic> b) {
        List<HookDiagnostic> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    /** Engine result bundling the reduced decision with the matched handlers. */
    public record DecisionEngineResult(Decision decision, Snapshot.HookMatch match) {}
}
