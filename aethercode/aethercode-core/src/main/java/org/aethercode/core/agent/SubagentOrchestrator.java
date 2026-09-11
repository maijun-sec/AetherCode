package org.aethercode.core.agent;

import org.aethercode.core.stream.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * orchestrates delegations to subagents. Modelled on the TS
 * {@code services/agentSummary/subagentOrchestrator.ts}.
 *
 * <p>The orchestrator does three things:
 *
 * <ol>
 *   <li>Look up the named {@link Subagent} in the registry.</li>
 *   <li>Use the injected {@link Subagent.EngineFactory} to create a child
 *       engine with the subagent's restricted tool pool.</li>
 *   <li>Run the task and collect the captured text + tool-call count.</li>
 * </ol>
 *
 * <p>The orchestrator itself is stateless: the parent engine owns the
 * registry and the factory. The default factory re-uses the parent's
 * builder with the subagent's tools + system-prompt prefix.
 */
public final class SubagentOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(SubagentOrchestrator.class);

    private final Subagent.Registry registry;
    private final Subagent.EngineFactory engineFactory;

    public SubagentOrchestrator(Subagent.Registry registry, Subagent.EngineFactory engineFactory) {
        this.registry = registry == null ? new Subagent.Registry() : registry;
        this.engineFactory = engineFactory == null ? SubagentOrchestrator::defaultFactory : engineFactory;
    }

    /** delegate a task to the named subagent. Blocks until the child finishes. */
    public Subagent.Result delegate(Object parent, String agentName, String task) {
        Subagent spec = registry.get(agentName);
        if (spec == null) {
            return Subagent.Result.error(agentName, "unknown subagent: " + agentName, 0);
        }
        long start = System.currentTimeMillis();
        Subagent.SubagentEngine child;
        try {
            child = engineFactory.create(spec, parent);
        } catch (Exception e) {
            return Subagent.Result.error(agentName, "engine factory failed: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
        if (child == null) {
            return Subagent.Result.error(agentName, "engine factory returned null",
                    System.currentTimeMillis() - start);
        }
        AtomicInteger toolCalls = new AtomicInteger();
        StringBuilder out = new StringBuilder();
        try {
            child.query(task).forEach(ev -> {
                if (ev instanceof StreamEvent.TextDelta td) {
                    out.append(td.text());
                } else if (ev instanceof StreamEvent.ToolUseStart) {
                    toolCalls.incrementAndGet();
                } else if (ev instanceof StreamEvent.RunEnd re) {
                    if (!"end_turn".equals(re.stopReason()) && re.stopReason() != null) {
                        LOG.debug("subagent '{}' finished with stopReason={}", agentName, re.stopReason());
                    }
                }
            });
        } catch (Exception e) {
            return Subagent.Result.error(agentName, e.getMessage(), System.currentTimeMillis() - start);
        }
        long elapsed = System.currentTimeMillis() - start;
        return Subagent.Result.ok(agentName, out.toString(), toolCalls.get(), elapsed);
    }

    /** async variant. */
    public CompletableFuture<Subagent.Result> delegateAsync(Object parent, String agentName, String task) {
        return CompletableFuture.supplyAsync(() -> delegate(parent, agentName, task));
    }

    /**
     * default factory hook. Implementations that want the canonical
     * "child engine via toBuilder()" should provide this in the SDK module.
     * The core module is intentionally SDK-agnostic, so the default here
     * throws — callers must supply an explicit factory.
     */
    public static Subagent.SubagentEngine defaultFactory(Subagent spec, Object parentCtx) {
        throw new UnsupportedOperationException(
                "core SubagentOrchestrator has no default factory; provide one in the SDK or app layer");
    }
}
