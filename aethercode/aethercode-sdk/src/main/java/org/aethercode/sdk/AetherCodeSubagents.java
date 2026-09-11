package org.aethercode.sdk;

import org.aethercode.core.agent.Subagent;
import org.aethercode.core.agent.SubagentOrchestrator;

/**
 * SDK-side glue for the subagent framework. Wraps the core
 * {@link SubagentOrchestrator} with an {@link Subagent.EngineFactory} that
 * knows how to fork an {@link AetherCodeEngine} via {@code toBuilder()}.
 */
public final class AetherCodeSubagents {

    private AetherCodeSubagents() {}

    /**
     * Build an orchestrator that creates child engines by cloning the parent
     * engine with the subagent's tools + system-prompt prefix. This is the
     * canonical "fork and run" factory.
     */
    public static SubagentOrchestrator orchestrator(Subagent.Registry registry) {
        return new SubagentOrchestrator(registry, AetherCodeSubagents::defaultFactory);
    }

    public static Subagent.SubagentEngine defaultFactory(Subagent spec, Object parentCtx) {
        if (!(parentCtx instanceof AetherCodeEngine parent)) {
            throw new IllegalArgumentException(
                    "AetherCodeSubagents.defaultFactory requires AetherCodeEngine parent, got " +
                    (parentCtx == null ? "null" : parentCtx.getClass().getName()));
        }
        return parent.toBuilder()
                .tools(spec.tools())
                .systemPrompt(spec.systemPromptPrefix())
                .sessionId(parent.appState().sessionId() + "::" + spec.name())
                .build();
    }
}
