package org.aethercode.deepagents.langchain_compat.langgraph;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LangGraph-compatible {@code CompiledStateGraph}.
 *
 * <p>Java-native port of
 * {@code langgraph.graph.state.CompiledStateGraph}. The
 * Java port is a thin facade that the runtime wires in
 * place of LangGraph's real graph execution. Phase A3
 * (2026-08-28) splits the shim into an abstract base class
 * plus two concrete implementations so the existing
 * 3005-test contract keeps working while the runtime
 * delegates to the native langgraph4j
 * {@link org.bsc.langgraph4j.CompiledGraph} for execution.</p>
 *
 * <ul>
 *   <li>{@link CompiledGraphSyncAdapter} &mdash; the default
 *       {@link #builder() Builder} output. Wraps the
 *       native langgraph4j {@code CompiledGraph} in a
 *       synchronous, shim-compatible surface. Internally
 *       builds a {@link org.bsc.langgraph4j.StateGraph}
 *       from the shim's {@code addNode}/{@code addEdge}
 *       calls and exposes the same
 *       {@link #invoke(Map, Map) invoke(input, config)}
 *       signature callers have always used.</li>
 *   <li>{@link LegacyCompiledStateGraph} &mdash; the
 *       original hand-rolled in-memory walk. Preserved
 *       for tests and as a safe fallback. Available via
 *       {@link Builder#legacy()}.</li>
 * </ul>
 *
 * <p>The shim's public API contract is unchanged: every
 * caller continues to compile and run without
 * modification. The default {@link #builder() builder} now
 * returns the langgraph4j-backed adapter; flip to the
 * legacy walk with {@code CompiledStateGraph.builder().legacy()}.</p>
 */
public abstract class CompiledStateGraph {
    /** LangGraph-style end-of-graph marker. Mirrors
     *  {@code langgraph.graph.END}. The shim uses this as
     *  the fallback routing target when no outgoing edge
     *  matches. */
    public static final String END = org.bsc.langgraph4j.GraphDefinition.END;
    /** LangGraph-style start-of-graph marker. The shim
     *  uses this to wire the entry point into the native
     *  langgraph4j {@code StateGraph}. */
    public static final String START = org.bsc.langgraph4j.GraphDefinition.START;

    protected CompiledStateGraph() {}

    public abstract List<Node> nodes();
    public abstract List<Edge> edges();
    public abstract Checkpointer checkpointer();
    public abstract String entryPoint();

    /**
     * Synchronous entry point. Mirrors the LangGraph
     * {@code invoke(input, config)} call. Implementations
     * walk the configured graph from {@link #entryPoint()}
     * to the end (no matching outgoing edge), then persist
     * the final state to {@link #checkpointer()} when one
     * is set.
     *
     * @param input initial state; must not be {@code null}
     * @param config per-run configuration. When non-null
     *               and {@code config["configurable"]} is a
     *               string, it is used as the checkpointer
     *               thread id (and as the langgraph4j
     *               {@code RunnableConfig.threadId()}).
     */
    public abstract Map<String, Object> invoke(Map<String, Object> input,
                                               Map<String, Object> config);

    public static Builder builder() { return new Builder(); }

    /** Functional node: name + handler that produces the next state. */
    public record Node(String name, NodeHandler handler) {
        public Node {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(handler, "handler");
        }
    }

    /** Functional edge: from -> to. The first edge from a node is followed. */
    public record Edge(String from, String to) {
        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /** Node handler signature: (state, config) -> newState. */
    @FunctionalInterface
    public interface NodeHandler {
        Map<String, Object> apply(Map<String, Object> state, Map<String, Object> config);
    }

    public static final class Builder {
        // Package-private so the package-local
        // {@link CompiledGraphSyncAdapter} and
        // {@link LegacyCompiledStateGraph} implementations
        // can read the assembled graph data without
        // exposing a wide getter surface publicly.
        final List<Node> nodes = new java.util.ArrayList<>();
        final List<Edge> edges = new java.util.ArrayList<>();
        Checkpointer checkpointer;
        String entryPoint;
        boolean useLegacy = false;

        public Builder addNode(String name, NodeHandler handler) {
            nodes.add(new Node(name, handler));
            return this;
        }

        public Builder addEdge(String from, String to) {
            edges.add(new Edge(from, to));
            return this;
        }

        public Builder setEntryPoint(String name) {
            this.entryPoint = name;
            return this;
        }

        public Builder withCheckpointer(Checkpointer checkpointer) {
            this.checkpointer = checkpointer;
            return this;
        }

        /**
         * Force the builder to produce a
         * {@link LegacyCompiledStateGraph} (the pre-A3
         * hand-rolled walk) instead of the default
         * langgraph4j-backed adapter. Used by tests that
         * exercise the legacy path and as a quick
         * opt-out if the adapter regresses.
         */
        public Builder legacy() {
            this.useLegacy = true;
            return this;
        }

        public CompiledStateGraph build() {
            if (useLegacy) {
                return new LegacyCompiledStateGraph(this);
            }
            return new CompiledGraphSyncAdapter(this);
        }
    }
}
