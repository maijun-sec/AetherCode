package org.aethercode.deepagents.langchain_compat.langgraph;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.state.AgentState;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Phase A3 (2026-08-28) implementation of
 * {@link CompiledStateGraph} backed by the native
 * langgraph4j {@link CompiledGraph}.
 *
 * <p>The shim's public API is synchronous
 * ({@link #invoke(Map, Map) invoke(input, config)});
 * langgraph4j's {@link CompiledGraph#invoke} is also
 * blocking (it returns the final {@link AgentState}
 * directly, not a {@link java.util.concurrent.CompletableFuture}).
 * This adapter bridges the two by:</p>
 *
 * <ol>
 *   <li>Building a langgraph4j {@link StateGraph} with
 *       no channels and a single
 *       {@link org.bsc.langgraph4j.state.AgentState AgentState}
 *       wrapping a {@link Map}, so a node's returned map
 *       acts as a delta against the previous state.</li>
 *   <li>Wrapping each shim {@link NodeHandler} in an
 *       {@link AsyncNodeActionWithConfig} that pulls the
 *       shim's {@code config} map out of the
 *       {@link RunnableConfig} metadata (key
 *       {@value #SHIM_CONFIG_METADATA_KEY}) and hands it
 *       to the handler as the second argument.</li>
 *   <li>Converting each shim {@link Edge} into a
 *       langgraph4j conditional edge whose action
 *       implements the shim's "first matching edge or
 *       {@link #END}" semantics. {@link #END} is always
 *       in the mapping so the action can always return
 *       a valid target.</li>
 *   <li>Wiring the entry point with
 *       {@link #START} &rarr; entryPoint, falling back to
 *       the first node when none is set &mdash; matches
 *       the shim's pre-A3 default.</li>
 *   <li>For empty graphs (no nodes) the adapter skips
 *       langgraph4j compilation and behaves like the
 *       legacy walk: {@link #invoke} echoes the input
 *       state and persists to the checkpointer when one
 *       is set.</li>
 * </ol>
 *
 * <p>Node handler return semantics: the shim's
 * {@link NodeHandler#apply} contract is "return the
 * full new state", but the adapter treats the returned
 * map as a delta that langgraph4j merges onto the
 * previous state. This is a no-op for the current
 * call sites (every test and real caller either returns
 * the state unchanged or returns
 * {@code new LinkedHashMap<>(state) + new key}), and the
 * 3005-test suite pins the behaviour.</p>
 */
public final class CompiledGraphSyncAdapter extends CompiledStateGraph {
    /** Metadata key under which the shim stores the
     *  per-run {@code config} map in the
     *  {@link RunnableConfig} so wrapped node handlers
     *  can read it back. */
    static final String SHIM_CONFIG_METADATA_KEY = "shim_config";
    /** Default thread id used when {@code config} is
     *  {@code null} or has no {@code "configurable"}
     *  entry. Matches the shim's pre-A3 default. */
    static final String DEFAULT_THREAD_ID = "default";

    private final List<Node> nodes;
    private final List<Edge> edges;
    private final Checkpointer checkpointer;
    private final String entryPoint;
    /** Native compiled graph. {@code null} when the
     *  shim has no nodes (the adapter short-circuits
     *  in {@link #invoke}). */
    private final CompiledGraph<AgentState> compiled;

    CompiledGraphSyncAdapter(CompiledStateGraph.Builder b) {
        this.nodes = List.copyOf(b.nodes);
        this.edges = List.copyOf(b.edges);
        this.checkpointer = b.checkpointer;
        this.entryPoint = b.entryPoint == null
                ? (nodes.isEmpty() ? null : nodes.get(0).name())
                : b.entryPoint;

        if (nodes.isEmpty()) {
            this.compiled = null;
            return;
        }
        this.compiled = compileNativeGraph();
    }

    private CompiledGraph<AgentState> compileNativeGraph() {
        try {
            StateGraph<AgentState> sg = new StateGraph<>(AgentState::new);
            // Register every shim node as a langgraph4j node.
            for (Node n : nodes) {
                sg.addNode(n.name(), wrapHandler(n.handler()));
            }
            // Wire the entry point.
            sg.addEdge(GraphDefinition.START, entryPoint);
            // Per-node conditional edge implementing the
            // shim's "first matching edge or END" semantics.
            for (Node n : nodes) {
                sg.addConditionalEdges(n.name(),
                        firstMatchEdgeAction(n.name()),
                        buildEdgeMapping(n.name()));
            }
            return sg.compile();
        } catch (GraphStateException ex) {
            throw new IllegalStateException(
                    "Failed to compile shim state graph with langgraph4j: "
                            + ex.getMessage(), ex);
        }
    }

    /** Build a langgraph4j conditional-edge mapping for
     *  {@code fromNode}: every unique outgoing target
     *  plus {@link #END} (so the action always has a
     *  valid return value). */
    private Map<String, String> buildEdgeMapping(String fromNode) {
        Set<String> targets = new LinkedHashSet<>();
        targets.add(END);
        for (Edge e : edges) {
            if (e.from().equals(fromNode)) {
                targets.add(e.to());
            }
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String t : targets) {
            mapping.put(t, t);
        }
        return mapping;
    }

    /** Action returning the shim's first matching edge
     *  target for {@code fromNode}, or {@link #END}
     *  when no edge matches. The langgraph4j conditional
     *  edge contract is "return a key present in the
     *  mapping", so the {@link #END} fallback keeps the
     *  adapter safe for terminal nodes. */
    private AsyncEdgeAction<AgentState> firstMatchEdgeAction(String fromNode) {
        return state -> CompletableFuture.completedFuture(nextNode(fromNode));
    }

    /** Wrap a shim {@link NodeHandler} into a langgraph4j
     *  node action. Reads the shim's per-run {@code config}
     *  map from the {@link RunnableConfig} metadata
     *  (written by {@link #invoke}) and feeds it to the
     *  handler as the second argument. */
    private static AsyncNodeActionWithConfig<AgentState> wrapHandler(NodeHandler shimHandler) {
        return (state, rc) -> {
            Map<String, Object> shimConfig = readShimConfig(rc);
            try {
                Map<String, Object> delta = shimHandler.apply(state.data(), shimConfig);
                return CompletableFuture.completedFuture(
                        delta == null ? Map.of() : delta);
            } catch (RuntimeException ex) {
                return CompletableFuture.failedFuture(ex);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readShimConfig(RunnableConfig rc) {
        if (rc == null) return Map.of();
        return rc.metadata(SHIM_CONFIG_METADATA_KEY)
                .filter(Map.class::isInstance)
                .map(m -> (Map<String, Object>) m)
                .orElse(Map.of());
    }

    @Override public List<Node> nodes() { return nodes; }
    @Override public List<Edge> edges() { return edges; }
    @Override public Checkpointer checkpointer() { return checkpointer; }
    @Override public String entryPoint() { return entryPoint; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> input, Map<String, Object> config) {
        Objects.requireNonNull(input, "input");
        String threadId = config == null ? null
                : Objects.toString(config.get("configurable"), null);

        Map<String, Object> finalState;
        if (compiled == null) {
            // Empty graph: echo the input. Matches the
            // pre-A3 shim, which short-circuits when
            // there are no nodes.
            finalState = new LinkedHashMap<>(input);
        } else {
            RunnableConfig rc = buildRunnableConfig(threadId, config);
            AgentState result = compiled.invoke(input, rc)
                    .orElseThrow(() -> new IllegalStateException(
                            "langgraph4j CompiledGraph.invoke returned no state for input "
                                    + input));
            finalState = result.data();
        }

        if (checkpointer != null) {
            checkpointer.put(
                    threadId == null ? DEFAULT_THREAD_ID : threadId,
                    finalState,
                    Map.of());
        }
        return finalState;
    }

    private static RunnableConfig buildRunnableConfig(String threadId,
                                                      Map<String, Object> config) {
        RunnableConfig.Builder b = RunnableConfig.builder();
        if (threadId != null) {
            b.threadId(threadId);
        }
        if (config != null) {
            b.putMetadata(SHIM_CONFIG_METADATA_KEY, config);
        }
        return b.build();
    }

    /** The shim's "first matching edge for this from"
     *  rule. Visible for tests. */
    String nextNode(String current) {
        for (Edge e : edges) {
            if (e.from().equals(current)) return e.to();
        }
        return END;
    }
}
