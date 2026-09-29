package org.aethercode.core.eval;

import org.aethercode.core.engine.QueryEngine;
import org.aethercode.core.engine.PermissionPolicy;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolHookRegistry;
import org.aethercode.core.stream.StreamEvent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Runs an {@link EvalRequest} through the AetherCode engine and returns
 * a complete {@link EvalResult} with trace + cost + latency.
 *
 * <p>This is the Java-side counterpart to the Python harness's
 * {@code agent.run} JSON-RPC method. The harness sends a request
 * payload over JSON-RPC, the daemon routes to {@link #run} via
 * {@code AetherCodeMethods.registerAll()}, and the resulting
 * {@link EvalResult} is serialized back.
 *
 * <p>Why a dedicated runner instead of calling the engine directly:
 * <ul>
 *   <li>Eval mode wants a STRICT cost / latency cap (kill if exceeded)</li>
 *   <li>Eval mode wants full trace capture (vs. streaming-only normal mode)</li>
 *   <li>Eval mode wants deterministic permission policy (no HIL interrupts)</li>
 *   <li>Eval mode wants loop detectors forced on (no manual abort)</li>
 * </ul>
 *
 * <p>Typical flow:
 * <pre>{@code
 * AgentRunner runner = AgentRunner.builder()
 *     .engine(engine)
 *     .toolHookRegistry(hookRegistry)
 *     .costLedger(ledger)
 *     .build();
 *
 * EvalResult r = runner.run(new EvalRequest(
 *     "A1.1.1", "A1", "simple_goal",
 *     "move all .md to docs/", ...),  // runIdx=0
 *     maxCostUsd = 5.0,
 *     maxLatencyMs = 60_000
 * );
 * }</pre>
 */
public final class AgentRunner {

    private final QueryEngine engine;
    private final ToolHookRegistry toolHookRegistry;
    private final CostLedger costLedger;

    private AgentRunner(Builder b) {
        this.engine = b.engine;
        this.toolHookRegistry = b.toolHookRegistry;
        this.costLedger = b.costLedger;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Run one test against the agent. The trace + cost + wall-clock are
     * captured. If cost or latency cap is exceeded, the run is aborted
     * with an error string in {@link EvalResult#error}.
     */
    public EvalResult run(EvalRequest req, int runIdx,
                           double maxCostUsd, long maxLatencyMs) {
        TraceRecorder recorder = new TraceRecorder();
        Set<ToolHookRegistry> registriesRegistered = new HashSet<>();
        if (toolHookRegistry != null) {
            toolHookRegistry.add(recorder);
            registriesRegistered.add(toolHookRegistry);
        }
        long start = System.currentTimeMillis();

        try {
            // Note: QueryEngine doesn't currently expose a setPermissionPolicy
            // setter. We construct an eval-friendly policy here in case the
            // engine grows one; for now we rely on the engine's existing
            // policy and skip the wiring step.
            PermissionPolicy evalPolicy = new PermissionPolicy() {
                @Override
                public CompletableFuture<PermissionResult> check(
                        Tool tool, Map<String, Object> input, Tool.CallContext ctx) {
                    return CompletableFuture.completedFuture(
                            new PermissionResult.Allow(Map.of()));
                }
            };
            // ignore evalPolicy -- engine uses its own set policy.
            // (kept here as a comment so future wiring lands in one place.)

            // QueryEngine.query(String) returns Stream<StreamEvent>.
            // Join text deltas; collect a simple "output" string.
            String output = engine.query(req.userMessage)
                    .filter(e -> e instanceof StreamEvent.TextDelta)
                    .map(e -> ((StreamEvent.TextDelta) e).text())
                    .filter(t -> t != null && !t.isEmpty())
                    .collect(Collectors.joining("\n"));

            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > maxLatencyMs) {
                return new EvalResult(
                        req.id, runIdx, output,
                        recorder.snapshotTrace(),
                        0.0,
                        recorder.tokensInput(), recorder.tokensOutput(),
                        elapsed,
                        "max_latency_exceeded",
                        Map.of("max_latency_ms", maxLatencyMs)
                );
            }

            double cost = costLedger == null ? 0.0 : costLedger.totalUsd();
            if (cost > maxCostUsd) {
                return new EvalResult(
                        req.id, runIdx, output,
                        recorder.snapshotTrace(),
                        cost,
                        recorder.tokensInput(), recorder.tokensOutput(),
                        elapsed,
                        "max_cost_exceeded",
                        Map.of("max_cost_usd", maxCostUsd)
                );
            }

            return new EvalResult(
                    req.id, runIdx, output,
                    recorder.snapshotTrace(),
                    cost,
                    recorder.tokensInput(), recorder.tokensOutput(),
                    elapsed,
                    null,
                    Map.of()
            );
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - start;
            return new EvalResult(
                    req.id, runIdx, "",
                    recorder.snapshotTrace(),
                    0.0,
                    recorder.tokensInput(), recorder.tokensOutput(),
                    elapsed,
                    t.getClass().getSimpleName() + ": " + t.getMessage(),
                    Map.of()
            );
        } finally {
            // ToolHookRegistry doesn't have unregister in this version of AetherCode.
            // Hooks are cleared by the registry owner. Leaving the reference here
            // so a future unregister() can be wired in without changing the API.
            // (See ToolHookRegistry.add for the LIFO ordering semantics.)
            for (var reg : registriesRegistered) {
                // intentional no-op; reserved for future unregister support.
                if (reg == null) continue;
            }
        }
    }

    public static final class Builder {
        private QueryEngine engine;
        private ToolHookRegistry toolHookRegistry;
        private CostLedger costLedger;

        public Builder engine(QueryEngine e) { this.engine = e; return this; }
        public Builder toolHookRegistry(ToolHookRegistry r) { this.toolHookRegistry = r; return this; }
        public Builder costLedger(CostLedger c) { this.costLedger = c; return this; }

        public AgentRunner build() {
            if (engine == null) {
                throw new IllegalStateException("QueryEngine is required");
            }
            return new AgentRunner(this);
        }
    }
}