package org.aethercode.engine.springai;

import org.aethercode.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.function.FunctionCallback;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * adapts our {@link Tool} to spring-ai's M6
 * {@link org.springframework.ai.model.function.FunctionCallback} so that
 * {@code OpenAiChatOptions.toolCallbacks(...)} can drive our tool pool.
 *
 * <p>Why M6's {@code FunctionCallback} and not the newer
 * {@code org.springframework.ai.tool.ToolCallback}? spring-ai 1.0.0-M6's
 * {@code OpenAiChatOptions.Builder} still uses
 * {@code toolCallbacks(List<FunctionCallback>)} — the GA
 * {@code ToolCallback} API only landed in spring-ai 1.0.0+ and is not
 * on the classpath here. When we upgrade to GA, this adapter can be
 * swapped to the new API in one place.
 *
 * <p>The wire shape is: the model emits a JSON string
 * {@code {"arg1":..., "arg2":...}} as the function call argument;
 * spring-ai invokes {@link #call(String)}; we parse it, hand it to
 * our {@code Tool.call(Map, CallContext)}, join the future, and
 * return the textual result. Errors come back as
 * {@code "ERROR: ..."} strings.
 */
public final class ToolAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(ToolAdapter.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private ToolAdapter() {}

    /** Wrap one of our {@link Tool}s as a spring-ai M6 {@link FunctionCallback}.
     *  Use this overload when the call site has no {@link
     *  org.aethercode.core.app.AppState} handy (e.g. unit tests). */
    public static FunctionCallback adapt(Tool tool) {
        return adapt(tool, null);
    }

    /** Wrap one of our {@link Tool}s as a spring-ai M6 {@link FunctionCallback},
     *  passing the optional {@code appState} through the {@link Tool.CallContext}
     *  extras so tools that need to publish session state (e.g. {@code TodoWriteTool}
     *  updating the in-session todo list) can find it. */
    public static FunctionCallback adapt(Tool tool,
                                          org.aethercode.core.app.AppState appState) {
        return adapt(tool, appState, null);
    }

    /** same as {@link #adapt(Tool, AppState)} but also stashes
     *  the chat client so tools like {@code AgentTool} can spawn subagents
     *  from inside the spring-ai tool loop. */
    public static FunctionCallback adapt(Tool tool,
                                          org.aethercode.core.app.AppState appState,
                                          org.aethercode.core.llm.ChatClient chatClient) {
        return adapt(tool, appState, chatClient, null);
    }

    /** full-fat overload that also stashes the {@code SubagentEngine}
     *  (the {@code AetherCodeEngine} implements this interface) so
     *  multi-step {@code AgentTool} invocations can re-enter the full
     *  engine loop for recursive subagents. The {@code subagentEngine}
     *  extra is optional — when absent, AgentTool falls back to its
     *  prior round single-shot path. */
    public static FunctionCallback adapt(Tool tool,
                                          org.aethercode.core.app.AppState appState,
                                          org.aethercode.core.llm.ChatClient chatClient,
                                          org.aethercode.core.agent.Subagent.SubagentEngine subagentEngine) {
        String name = tool.name();
        String description = tool.description() == null ? "" : tool.description();
        String inputSchema = schemaString(tool.inputSchema());
        return new FunctionCallback() {
            @Override
            public String getName() { return name; }

            @Override
            public String getDescription() { return description; }

            @Override
            public String getInputTypeSchema() { return inputSchema; }

            @Override
            public String call(String toolInput) {
                Map<String, Object> args = parseJsonArgs(toolInput);
                // pass the AppState through the CallContext extras so
                // TodoWriteTool (and friends) can publish to it. prior round: also
                // pass the chat client so AgentTool can spawn subagents.
                // pass the SubagentEngine too so AgentTool can
                // spawn a multi-step subagent that itself can call tools.
                // Use a ConcurrentHashMap to match the engine's CallContext shape.
                java.util.Map<String, Object> extras = new java.util.concurrent.ConcurrentHashMap<>();
                if (appState != null) extras.put("app_state", appState);
                if (chatClient != null) extras.put("chat_client", chatClient);
                if (subagentEngine != null) extras.put("subagent_engine", subagentEngine);
                Tool.CallContext ctx = new Tool.CallContext("spring-ai", null, extras);
                try {
                    Tool.ToolResult result;
                    CompletableFuture<Tool.ToolResult> fut = tool.call(args, ctx);
                    result = fut == null ? null : fut.join();
                    if (result == null) {
                        return "ERROR: tool returned null result";
                    }
                    if (result.isError()) {
                        return "ERROR: " + String.valueOf(result.output());
                    }
                    Object out = result.output();
                    return out == null ? "" : out.toString();
                } catch (Throwable t) {
                    // surface the cause class + message, not just the
                    // message — many tools throw a CompletionException wrapping
                    // a real checked exception; the wrapper's getMessage() is
                    // usually less useful than the cause's getClass().
                    Throwable cause = t.getCause() == null ? t : t.getCause();
                    LOG.warn("tool {} failed: {}: {}", name, cause.getClass().getSimpleName(), cause.getMessage());
                    return "ERROR: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
                }
            }
        };
    }

    private static String schemaString(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) return "{}";
        try {
            return MAPPER.writeValueAsString(schema);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Map<String, Object> parseJsonArgs(String s) {
        if (s == null || s.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(s,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
