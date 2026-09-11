package org.aethercode.acp;

import org.aethercode.langchain_compat.langgraph.Checkpointer;
import org.aethercode.langchain_compat.langgraph.Command;
import org.aethercode.langchain_compat.langgraph.CompiledStateGraph;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Streaming / stateful LangGraph operations that the
 * {@link AgentServerACP} relies on.
 *
 * <p>Java-native port of the streaming subset of
 * {@code langgraph.graph.state.CompiledStateGraph}. The base
 * {@link CompiledStateGraph} in {@code deepagents-core} only
 * exposes a synchronous {@code invoke(input, config)}; the
 * ACP server needs {@code astream}, {@code agetState},
 * {@code aupdateState}, and {@code agetStateHistory} in
 * addition. The Java port declares them here so the ACP
 * server can be written against a single interface and the
 * graph runtime can be upgraded to implement it without
 * breaking the ACP code.</p>
 */
public interface StreamingStateGraph {

    /** The base graph. Provided for callers that need
     *  {@code invoke(input, config)}. */
    CompiledStateGraph base();

    /** The graph's checkpointer, if any. Mirrors
     *  {@code CompiledStateGraph.checkpointer}. */
    Checkpointer checkpointer();

    /**
     * Stream graph events.
     *
     * <p>Mirrors
     * {@code CompiledStateGraph.astream(input, config, stream_mode, subgraphs)}.
     * The Java port returns a reactive {@link Flow.Publisher} so
     * callers can iterate with back-pressure.</p>
     */
    Flow.Publisher<StreamEvent> astream(
            Object input,
            Map<String, Object> config,
            List<String> streamMode,
            boolean subgraphs);

    /** Read the current state. */
    CompletableFuture<StateSnapshot> agetState(Map<String, Object> config);

    /** Update the state. */
    CompletableFuture<Void> aupdateState(
            Map<String, Object> config,
            Map<String, Object> values,
            String asNode);

    /** Read the state history (most recent first). */
    CompletableFuture<List<StateSnapshot>> agetStateHistory(
            Map<String, Object> config);

    /**
     * One stream event. The discriminator follows the Python
     * {@code (namespace, stream_mode, data)} tuple; the Java
     * port flattens it into a single record so call sites can
     * switch on {@link #streamMode()}.
     */
    record StreamEvent(
            String namespace,
            String streamMode,
            Object data) {
    }

    /**
     * A no-op stub that always throws
     * {@link UnsupportedOperationException}. Useful as a
     * placeholder until the deepagents-core graph runtime
     * ships the streaming / state-history methods.
     */
    final class Unsupported implements StreamingStateGraph {
        private final CompiledStateGraph base;
        public Unsupported(CompiledStateGraph base) {
            this.base = base;
        }
        @Override public CompiledStateGraph base() { return base; }
        @Override public Checkpointer checkpointer() {
            return base.checkpointer();
        }
        @Override public Flow.Publisher<StreamEvent> astream(
                Object input, Map<String, Object> config,
                List<String> streamMode, boolean subgraphs) {
            throw new UnsupportedOperationException(
                    "StreamingStateGraph.astream: not yet wired up in the deepagents-core graph runtime. " +
                            "Replace this StreamingStateGraph.Unsupported wrapper with a runtime-backed " +
                            "implementation when the langgraph4j-backed graph is online.");
        }
        @Override public CompletableFuture<StateSnapshot> agetState(
                Map<String, Object> config) {
            throw new UnsupportedOperationException(
                    "StreamingStateGraph.agetState: not yet wired up in the deepagents-core graph runtime.");
        }
        @Override public CompletableFuture<Void> aupdateState(
                Map<String, Object> config,
                Map<String, Object> values,
                String asNode) {
            throw new UnsupportedOperationException(
                    "StreamingStateGraph.aupdateState: not yet wired up in the deepagents-core graph runtime.");
        }
        @Override public CompletableFuture<List<StateSnapshot>> agetStateHistory(
                Map<String, Object> config) {
            throw new UnsupportedOperationException(
                    "StreamingStateGraph.agetStateHistory: not yet wired up in the deepagents-core graph runtime.");
        }
    }

    /**
     * Wrap a plain {@link CompiledStateGraph} as a
     * {@link StreamingStateGraph}. The returned instance throws
     * on every streaming call until the graph runtime grows
     * the necessary methods. This is the safe default; the
     * full demo wires the real runtime in once it exists.
     */
    static StreamingStateGraph of(CompiledStateGraph graph) {
        return new Unsupported(graph);
    }

    /**
     * Convenience helper that re-emits a {@link Command} as the
     * input expected by {@link #astream}. Mirrors the
     * {@code Command(resume=...)} / {@code {"messages": [...]}}
     * dispatch in the Python server.
     */
    static Object asInput(Command command, List<Map<String, Object>> messages) {
        if (command != null) return command;
        return Map.of("messages", messages);
    }
}
