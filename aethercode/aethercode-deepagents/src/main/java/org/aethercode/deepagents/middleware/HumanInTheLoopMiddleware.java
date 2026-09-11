package org.aethercode.deepagents.middleware;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.Message;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import org.aethercode.core.runtime.Message.AIMessage;

/**
 * Stub of the upstream langchain
 * {@code HumanInTheLoopMiddleware}.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.HumanInTheLoopMiddleware}. The
 * Python port's middleware intercepts matching tool calls and
 * surfaces a human approval flow. The Java port keeps the
 * same shape: an {@code interrupt_on} config that the
 * filesystem-middleware glue builds, and a passthrough
 * default. A consumer can subclass to wire a real approval
 * flow.</p>
 */
public class HumanInTheLoopMiddleware implements Middleware {

    private final Map<String, ?> interruptOn;

    public HumanInTheLoopMiddleware(Map<String, ?> interruptOn) {
        this.interruptOn = Objects.requireNonNull(interruptOn, "interruptOn");
    }

    public Map<String, ?> interruptOn() { return interruptOn; }

    @Override
    public String name() { return "HumanInTheLoopMiddleware"; }

    @Override
    public AIMessage wrapModelCall(
            BiFunction<List<Message>, Middleware.Runtime, AIMessage> modelCall,
            List<Message> messages,
            AgentState state,
            Middleware.Runtime runtime) {
        // Stub: delegate to the model without actually intercepting.
        return modelCall.apply(messages, runtime);
    }
}
