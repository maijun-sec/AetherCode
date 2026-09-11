package org.aethercode.acp.examples;

import org.aethercode.acp.AgentServerACP;
import org.aethercode.acp.AgentSessionContext;
import org.aethercode.acp.Client;
import org.aethercode.acp.Main;
import org.aethercode.acp.StreamingStateGraph;
import org.aethercode.acp.examples.LocalContext.MiddlewareImpl;
import org.aethercode.acp.schema.PermissionOption;
import org.aethercode.acp.schema.SessionConfig;
import org.aethercode.acp.schema.SessionConfig.SessionMode;
import org.aethercode.acp.schema.SessionConfig.SessionModeState;
import org.aethercode.backends.BackendProtocol;
import org.aethercode.backends.CompositeBackend;
import org.aethercode.backends.LocalShellBackend;
import org.aethercode.backends.StateBackend;
import org.aethercode.langchain_compat.langgraph.Checkpointer;
import org.aethercode.langchain_compat.langgraph.CompiledStateGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Demo coding agent using ACP.
 *
 * <p>Java-native port of
 * {@code deepagents_acp.examples.demo_agent}. Builds a
 * Deep Agent that runs in the user's working directory,
 * wires the {@link LocalContext.MiddlewareImpl} to inject
 * the local context into the system prompt, and exposes a
 * session-mode switcher ({@code ask_before_edits},
 * {@code accept_edits}, {@code accept_everything}).</p>
 *
 * <p>Marked {@code @Example}: the file is not on the
 * hot path; it shows how a host application wires the
 * deepagents-acp server with custom middleware, modes,
 * and a model switcher.</p>
 */
public final class DemoAgent {

    private DemoAgent() {}

    /** Interrupt configuration per mode. Mirrors
     *  {@code _get_interrupt_config} in
     *  {@code examples/demo_agent.py}. */
    static Map<String, Object> getInterruptConfig(String modeId) {
        Map<String, Object> askBeforeEdits = new LinkedHashMap<>();
        askBeforeEdits.put("edit_file", Map.of("allowed_decisions", List.of("approve", "reject")));
        askBeforeEdits.put("write_file", Map.of("allowed_decisions", List.of("approve", "reject")));
        askBeforeEdits.put("write_todos", Map.of("allowed_decisions", List.of("approve", "reject")));
        askBeforeEdits.put("execute", Map.of("allowed_decisions", List.of("approve", "reject")));

        Map<String, Object> acceptEdits = new LinkedHashMap<>();
        acceptEdits.put("write_todos", Map.of("allowed_decisions", List.of("approve", "reject")));
        acceptEdits.put("execute", Map.of("allowed_decisions", List.of("approve", "reject")));

        Map<String, Object> acceptEverything = new LinkedHashMap<>();

        return switch (modeId) {
            case "ask_before_edits" -> askBeforeEdits;
            case "accept_edits" -> acceptEdits;
            case "accept_everything" -> acceptEverything;
            default -> Map.of();
        };
    }

    /** Build the demo agent. Mirrors the Python
     *  {@code _serve_example_agent} factory. */
    public static AgentServerACP createDemoAgent() {
        java.util.function.Function<AgentSessionContext, StreamingStateGraph> factory = ctx -> {
            String rootDir = ctx.cwd().isEmpty() ? "." : ctx.cwd();
            // Local-shell backend with the full env, plus an
            // ephemeral state backend for /memories/ and
            // /conversation_history/. The agent factory wires
            // a CompositeBackend that routes those prefixes
            // to in-memory storage.
            LocalShellBackend shellBackend = new LocalShellBackend(
                    rootDir,
                    true, /* virtualMode */
                    LocalShellBackend.DEFAULT_EXECUTE_TIMEOUT,
                    LocalShellBackend.DEFAULT_MAX_OUTPUT_BYTES,
                    System.getenv(),
                    true /* inheritEnv */);
            StateBackend ephemeralBackend = new StateBackend();
            BackendProtocol backend = new CompositeBackend(
                    shellBackend,
                    Map.of(
                            "/memories/", ephemeralBackend,
                            "/conversation_history/", ephemeralBackend));
            // The full demo would call create_deep_agent(...)
            // and then run the assembled middleware stack
            // through the runtime. The Java port of
            // create_deep_agent is in deepagents-core; the
            // runtime that wires it into a StreamingStateGraph
            // is a future round. For now we assemble a
            // placeholder graph and surface the runtime gap
            // through the StreamingStateGraph.Unsupported
            // wrapper.
            CompiledStateGraph graph = CompiledStateGraph.builder()
                    .addNode("start", (state, config) -> state)
                    .withCheckpointer(new Checkpointer.MemoryCheckpointer("demo-agent"))
                    .build();
            return StreamingStateGraph.of(graph);
        };

        SessionModeState modes = new SessionModeState(
                List.of(
                        new SessionMode(
                                "ask_before_edits", "Ask before edits",
                                Optional.of("Ask permission before edits, writes, shell commands, and plans")),
                        new SessionMode(
                                "accept_edits", "Accept edits",
                                Optional.of("Auto-accept edit operations, but ask before shell commands and plans")),
                        new SessionMode(
                                "accept_everything", "Accept everything",
                                Optional.of("Auto-accept all operations without asking permission"))),
                "accept_edits");

        List<Map<String, String>> models = new ArrayList<>();
        models.add(Map.of("value", "anthropic:claude-opus-4-7", "name", "Claude Opus 4.7"));
        models.add(Map.of("value", "anthropic:claude-sonnet-4-6", "name", "Claude Sonnet 4.6"));
        models.add(Map.of("value", "anthropic:claude-haiku-4-5", "name", "Claude Haiku 4.5"));
        models.add(Map.of("value", "openai:gpt-5.5", "name", "GPT-5.5"));

        return new AgentServerACP(factory, modes, models);
    }

    /**
     * Run the demo agent against the no-op in-process
     * transport. Hosts (IDEs, the {@code deepagents-cli}
     * command, ...) replace this with a real client.
     */
    public static void main(String[] args) {
        AgentServerACP agent = createDemoAgent();
        Main.runAcpAgent(agent);
    }
}
