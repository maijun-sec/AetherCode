package org.aethercode.talon.interfaces;

import java.util.concurrent.CompletableFuture;

/**
 * Agent runtime invoked by the Talon host.
 *
 * <p>Java-native port of {@code deepagents_talon.interfaces.AgentRuntime}.
 * Each host connects to one runtime and a single runtime may serve many
 * channels and scheduled jobs concurrently.</p>
 */
public interface AgentRuntime {

    /** Initialize the runtime before the host accepts work. */
    CompletableFuture<Void> start();

    /** Release runtime resources. */
    CompletableFuture<Void> stop();

    /**
     * Invoke the agent for one serialized conversation turn.
     *
     * @param request agent request supplied by a channel or scheduler.
     * @return agent output for the host to route back to the trigger.
     */
    CompletableFuture<AgentResult> invoke(AgentRequest request);
}
