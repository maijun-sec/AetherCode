package org.aethercode.talon.runtime;

import org.aethercode.talon.interfaces.AgentRequest;
import org.aethercode.talon.interfaces.AgentResult;
import org.aethercode.talon.interfaces.AgentRuntime;

import java.util.concurrent.CompletableFuture;

/**
 * Small placeholder runtime for host bootstrapping and tests.
 *
 * <p>Java-native port of
 * {@code deepagents_talon.runtime.EchoAgentRuntime}. Returns the request
 * text as a trivial response so the host loop can drive messages end
 * to end without a real chat model attached.</p>
 */
public class EchoAgentRuntime implements AgentRuntime {

    @Override
    public CompletableFuture<Void> start() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<AgentResult> invoke(AgentRequest request) {
        return CompletableFuture.completedFuture(new AgentResult(request.text()));
    }
}
