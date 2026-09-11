package org.aethercode.acp;

import org.aethercode.backends.BackendProtocol;
import org.aethercode.backends.FilesystemBackend;
import org.aethercode.langchain_compat.langgraph.Checkpointer;
import org.aethercode.langchain_compat.langgraph.CompiledStateGraph;

import java.util.function.Function;

/**
 * Default test agent factory used by {@link Main#main(String[])}
 * and the {@code deepagents-acp} demo entry point.
 *
 * <p>Mirror of the Python {@code _serve_test_agent} helper in
 * {@code deepagents_acp.server}. The Java port skips the
 * {@code dotenv} read (Java hosts are expected to load env
 * files at the application layer) and wires the
 * {@link StreamingStateGraph} wrapper expected by
 * {@link AgentServerACP}.</p>
 */
public final class TestAgentFactory {
    private TestAgentFactory() {}

    /**
     * Build the default test agent. The agent is a
     * filesystem-backed Deep Agent with a virtual-mode
     * filesystem rooted at the user's working directory.
     */
    public static AgentServerACP createDefault() {
        Function<AgentSessionContext, StreamingStateGraph> factory = ctx -> {
            String root = ctx.cwd().isEmpty() ? "." : ctx.cwd();
            // FilesystemBackend(rootDir, virtualMode, timeout) — 3-arg
            // constructor. The Java port of the test agent does
            // not yet plug the real DeepAgent graph into the
            // streaming surface (that lands when the langgraph4j
            // runtime ships). For now we wrap a placeholder
            // graph in StreamingStateGraph.Unsupported.
            BackendProtocol backend = new FilesystemBackend(
                    root, true /* virtualMode */,
                    (int) FilesystemBackend.DEFAULT_GLOB_TIMEOUT);
            // The Java port does not yet have a runtime-backed
            // DeepAgent.compile() — the streaming
            // CompiledStateGraph wiring is a future round. For
            // now we wrap a placeholder graph in
            // StreamingStateGraph.Unsupported; the demo will
            // surface a clear "not yet wired up" error.
            CompiledStateGraph graph = CompiledStateGraph.builder()
                    .addNode("start", (state, config) -> state)
                    .build();
            return StreamingStateGraph.of(graph);
        };
        return new AgentServerACP(factory);
    }
}
