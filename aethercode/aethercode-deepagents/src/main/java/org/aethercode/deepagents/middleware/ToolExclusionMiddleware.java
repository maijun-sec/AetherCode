package org.aethercode.deepagents.middleware;

import org.aethercode.core.runtime.Message;
import org.aethercode.deepagents.tools.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import org.aethercode.core.runtime.Message.AIMessage;

/**
 * Middleware that filters excluded tools from the model request.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware._tool_exclusion._ToolExclusionMiddleware}.
 * Should be placed late in the middleware stack (after all
 * tool-injecting middleware) so it can strip middleware-injected tools
 * (filesystem, subagent, etc.) that the harness profile marks as excluded.</p>
 *
 * <p>The middleware runs inside {@link #wrapModelCall(BiFunction, List, org.aethercode.core.runtime.AgentState, Runtime)}.
 * The default contract is "filter the runtime's tool list, then call
 * the model". The Java port reuses the {@link Middleware.Runtime#tools()}
 * accessor to read the live tool registry; the model call is the
 * {@code modelCall} BiFunction passed to the hook.</p>
 */
public class ToolExclusionMiddleware implements Middleware {

    private final Set<String> excluded;

    public ToolExclusionMiddleware(Set<String> excluded) {
        this.excluded = Objects.requireNonNull(excluded, "excluded");
    }

    @Override
    public String name() { return "ToolExclusionMiddleware"; }

    @Override
    public AIMessage wrapModelCall(
            BiFunction<List<Message>, Runtime, AIMessage> modelCall,
            List<Message> messages,
            org.aethercode.core.runtime.AgentState state,
            Runtime runtime) {
        // The Java port doesn't pass a separate ModelRequest with the tool
        // list; the model call is invoked with the messages and the runtime,
        // and the chat-model adapter is expected to consult
        // {@link Middleware.Runtime#tools()} when assembling the request.
        // This hook is the place to override the tool list before that
        // lookup; we wrap the runtime with a filtered view.
        Runtime filteredRuntime = new Runtime() {
            @Override
            public List<Tool> tools() {
                List<Tool> all = runtime.tools();
                if (excluded.isEmpty()) return all;
                List<Tool> kept = new ArrayList<>(all.size());
                for (Tool t : all) {
                    String name = t.name();
                    if (name != null && excluded.contains(name)) continue;
                    kept.add(t);
                }
                return kept;
            }

            @Override
            public java.util.function.Function<List<Message>, AIMessage> chatModel() {
                return runtime.chatModel();
            }
        };
        return modelCall.apply(messages, filteredRuntime);
    }
}
