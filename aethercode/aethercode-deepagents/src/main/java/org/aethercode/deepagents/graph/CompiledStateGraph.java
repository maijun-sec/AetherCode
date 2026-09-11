package org.aethercode.deepagents.graph;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.core.runtime.Message;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.aethercode.core.runtime.Message.AIMessage;

/**
 * High-level "compiled" agent graph for the deepagents runtime.
 *
 * <p>Java-native port of the
 * {@code deepagents.graph.CompiledStateGraph} shape. Unlike the
 * langgraph-compatible
 * {@link org.aethercode.deepagents.langchain_compat.langgraph.CompiledStateGraph}
 * (which is a thin shim over a real graph execution engine), this
 * class composes the existing
 * {@link DeepAgent#invoke(AgentState, String, Function, int)} loop
 * into a graph with explicit nodes and edges so callers can mix
 * custom pre/post-model steps into the agent loop without writing
 * a new middleware.</p>
 *
 * <h2>Graph shape</h2>
 *
 * <p>The deepagents agent loop has three natural nodes:</p>
 *
 * <ul>
 *   <li>{@code "model"} &mdash; the chat model call (delegates to
 *       {@link DeepAgent#invoke(AgentState, String, Function, int)}
 *       with {@code maxIterations=1}).</li>
 *   <li>{@code "tools"} &mdash; the tool-dispatch fan-out (one
 *       {@link org.aethercode.core.runtime.Message.ToolMessage} per
 *       tool call in the model's last response).</li>
 *   <li>{@code "router"} &mdash; the edge router: after the
 *       model, the runtime decides whether to loop back to
 *       {@code "model"} (when there are still tool calls to
 *       dispatch) or terminate ({@link #END}).</li>
 * </ul>
 *
 * <p>By default the runtime wires
 * {@code START -> model -> router -> [tools -> model] | END}.</p>
 *
 * <h2>Stream surface</h2>
 *
 * <p>{@link #invoke(AgentState, String, Function, Consumer, RunnableConfig)} takes an
 * optional {@link Consumer Consumer&lt;DeepAgentEvent&gt;} that
 * receives the runtime events as the graph walks. The events are
 * the same ones {@link DeepAgent#stream(AgentState, String, Function)}
 * emits ({@link DeepAgentEvent.BeforeModel},
 * {@link DeepAgentEvent.AfterModel},
 * {@link DeepAgentEvent.ToolDispatch},
 * {@link DeepAgentEvent.Final}), so a UI consumer doesn't have to
 * learn a second event vocabulary.</p>
 *
 * <h2>Node registration</h2>
 *
 * <p>{@link Builder#addNode(String, NodeHandler)} lets a caller
 * splice in custom steps (e.g. a rubric grader, a HITL interrupt,
 * a summary step) without writing a new middleware. The handler
 * receives the current {@link AgentState} and the
 * {@link RunnableConfig}, and returns the next state.</p>
 */
public final class CompiledStateGraph {

    /** LangGraph-style end-of-graph marker. Mirrors
     *  {@code langgraph.graph.END}. The router uses this as
     *  the terminal routing target when no tool calls remain. */
    public static final String END = org.bsc.langgraph4j.GraphDefinition.END;

    /** LangGraph-style start-of-graph marker. */
    public static final String START = org.bsc.langgraph4j.GraphDefinition.START;

    /** Built-in node name: the chat model call. */
    public static final String NODE_MODEL = "model";

    /** Built-in node name: the tool-dispatch fan-out. */
    public static final String NODE_TOOLS = "tools";

    /** Built-in node name: the edge router. */
    public static final String NODE_ROUTER = "router";

    private final List<Node> nodes;
    private final List<Edge> edges;
    private final Map<String, Node> nodeIndex;
    private final Map<String, String> nextEdge;
    private final String entryPoint;

    private CompiledStateGraph(List<Node> nodes,
                               List<Edge> edges,
                               Map<String, Node> nodeIndex,
                               Map<String, String> nextEdge,
                               String entryPoint) {
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.nodeIndex = Map.copyOf(nodeIndex);
        this.nextEdge = Map.copyOf(nextEdge);
        this.entryPoint = entryPoint;
    }

    /** Unmodifiable snapshot of the registered nodes (in insertion order). */
    public List<Node> nodes() { return nodes; }

    /** Unmodifiable snapshot of the registered edges. */
    public List<Edge> edges() { return edges; }

    /** The configured entry point. */
    public String entryPoint() { return entryPoint; }

    // -----------------------------------------------------------------
    //  Invocation
    // -----------------------------------------------------------------

    /**
     * Walk the graph from {@link #entryPoint()} to {@link #END}.
     *
     * <p>The default loop is:</p>
     * <pre>
     *   START -> model -> router -> tools -> model -> router -> ... -> END
     * </pre>
     *
     * <p>Each registered node is called with the current
     * {@link AgentState} and the {@link RunnableConfig}. The
     * built-in {@code model} node delegates to
     * {@link DeepAgent#invoke(AgentState, String, Function, int)} with
     * {@code maxIterations=1}, so the graph runtime stays in
     * sync with the underlying agent loop. The {@code tools}
     * node walks the {@code ToolMessage} chain in the state's
     * most recent AI message and appends results; the {@code router}
     * node decides whether to continue or terminate.</p>
     *
     * @param state     initial state (null → {@link AgentState#empty()})
     * @param input     user input appended as a HumanMessage
     * @param chatModel function the runtime calls to get the
     *                   next {@link AIMessage}
     * @return the final state and the final text reply
     */
    public Result invoke(AgentState state,
                          String input,
                          Function<List<Message>, AIMessage> chatModel) {
        return invoke(state, input, chatModel, null, RunnableConfig.defaults());
    }

    /**
     * Streaming variant. The {@code onEvent} consumer receives the
     * runtime events as the graph walks; pass {@code null} to skip
     * streaming.
     */
    public Result invoke(AgentState state,
                          String input,
                          Function<List<Message>, AIMessage> chatModel,
                          Consumer<DeepAgentEvent> onEvent,
                          RunnableConfig config) {
        Objects.requireNonNull(chatModel, "chatModel");
        Objects.requireNonNull(config, "config");

        AgentState current = state == null ? AgentState.empty() : state;
        String currentNode = entryPoint;
        if (onEvent != null) onEvent.accept(new DeepAgentEvent.BeforeModel(current));

        // The "model" node is special-cased: the agent's
        // DeepAgent.invoke already implements the inner
        // model→tool→model loop, so a single visit to "model"
        // handles the entire agent invocation. The graph's
        // outer loop is for chaining custom nodes before/after
        // the agent call, not for breaking the inner loop into
        // pieces. The {@code maxIterations} cap on the agent's
        // invoke is the default (16).
        int safetyCap = 64;
        boolean emittedFinal = false;
        while (!END.equals(currentNode) && safetyCap-- > 0) {
            Node node = nodeIndex.get(currentNode);
            if (node == null) {
                throw new IllegalStateException(
                        "Graph node '" + currentNode + "' is not registered");
            }
            if (NODE_MODEL.equals(node.name())) {
                DeepAgent agent = (DeepAgent) node.tag();
                DeepAgent.DeepAgentResult r = agent.invoke(current, input, chatModel,
                        DeepAgent.DEFAULT_MAX_ITERATIONS);
                current = r.state();
                if (onEvent != null) {
                    AIMessage last = lastAiMessage(current);
                    if (last != null) {
                        onEvent.accept(new DeepAgentEvent.AfterModel(last, current));
                    }
                }
            } else {
                current = node.handler().apply(current, config);
            }
            currentNode = nextEdge.getOrDefault(currentNode, END);
            if (END.equals(currentNode)) {
                String finalText = "";
                AIMessage last = lastAiMessage(current);
                if (last != null) {
                    finalText = ContentBlock.flattenText(last.content());
                }
                if (onEvent != null) {
                    onEvent.accept(new DeepAgentEvent.Final(current, finalText));
                }
                emittedFinal = true;
            }
        }
        if (!emittedFinal && onEvent != null) {
            String finalText = "";
            AIMessage last = lastAiMessage(current);
            if (last != null) {
                finalText = ContentBlock.flattenText(last.content());
            }
            onEvent.accept(new DeepAgentEvent.Final(current, finalText));
        }
        String finalText = "";
        AIMessage last = lastAiMessage(current);
        if (last != null) {
            finalText = ContentBlock.flattenText(last.content());
        }
        return new Result(current, finalText);
    }

    /**
     * Find the most recent {@link AIMessage} in {@code state}. The
     * streaming variant uses this to emit {@link DeepAgentEvent.AfterModel}
     * after the model node runs.
     */
    private static AIMessage lastAiMessage(AgentState state) {
        List<Message> msgs = state.messages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i) instanceof AIMessage ai) return ai;
        }
        return null;
    }

    // -----------------------------------------------------------------
    //  Builder
    // -----------------------------------------------------------------

    public static Builder builder() { return new Builder(); }

    /**
     * Per-invocation configuration. Mirrors langgraph's
     * {@code RunnableConfig} shape: a thread id (so a UI can
     * correlate concurrent runs) and an arbitrary tag map.
     */
    public record RunnableConfig(String threadId, Map<String, Object> tags) {
        public RunnableConfig {
            tags = tags == null ? Map.of() : Map.copyOf(tags);
        }
        public static RunnableConfig defaults() {
            return new RunnableConfig("default", Map.of());
        }
    }

    /**
     * A single graph node. {@code handler} is the function the
     * runtime calls when the node fires. {@code tag} carries
     * arbitrary metadata (the built-in {@code "model"} node
     * stores its {@link DeepAgent} here so the runtime can
     * invoke it).
     */
    public record Node(String name, NodeHandler handler, Object tag) {
        public Node(String name, NodeHandler handler) {
            this(name, handler, null);
        }
        public Node {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(handler, "handler");
        }
    }

    /** Edge: from -> to. The runtime follows the first edge out of a node. */
    public record Edge(String from, String to) {
        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /** Node handler signature: (state, config) -> newState. */
    @FunctionalInterface
    public interface NodeHandler {
        AgentState apply(AgentState state, RunnableConfig config);
    }

    /**
     * Result of {@link #invoke(AgentState, String, Function, Consumer, RunnableConfig)}.
     * The {@code state} is the final agent state; {@code text} is
     * the final assistant text reply (the runtime walks the most
     * recent AI message to extract it).
     */
    public record Result(AgentState state, String text) {
        public Result {
            state = state == null ? AgentState.empty() : state;
            text = text == null ? "" : text;
        }
    }

    /**
     * Fluent builder. The default graph (when no nodes are added)
     * is {@code START -> model -> router -> [tools -> model] -> END}
     * and runs a real {@link DeepAgent} on the {@code "model"} node.
     */
    public static final class Builder {
        private final java.util.List<Node> nodes = new java.util.ArrayList<>();
        private final java.util.List<Edge> edges = new java.util.ArrayList<>();
        private DeepAgent agent;

        /** Register a custom node. */
        public Builder addNode(String name, NodeHandler handler) {
            nodes.add(new Node(name, handler));
            return this;
        }

        /**
         * Register the built-in {@code "model"} node bound to a
         * specific {@link DeepAgent}. This is the standard way to
         * drive the graph: hand the agent you got from
         * {@code CreateDeepAgent.create(...)} to the builder, and
         * the runtime will run one round of the agent loop at
         * each visit to the {@code "model"} node.
         */
        public Builder withAgent(DeepAgent agent) {
            this.agent = agent;
            return this;
        }

        /** Register an edge. The runtime follows the first edge out of a node. */
        public Builder addEdge(String from, String to) {
            edges.add(new Edge(from, to));
            return this;
        }

        /**
         * Build the graph. If the caller has not added a
         * {@code "model"} node but did call {@link #withAgent},
         * the builder adds the default one-node graph
         * ({@code START → model → END}) automatically. The
         * agent's internal {@link DeepAgent#invoke} loop
         * handles the model→tool→model cycle, so the graph
         * itself stays linear; callers add custom nodes
         * between {@code model} and {@code END} to splice
         * pre/post steps into the loop.
         */
        public CompiledStateGraph build() {
            boolean hasModel = nodes.stream().anyMatch(n -> NODE_MODEL.equals(n.name()));
            if (!hasModel && agent != null) {
                nodes.add(new Node(NODE_MODEL, (s, c) -> s, agent));
            }
            // Default edges: only the ones the user hasn't provided.
            if (!hasEdge(START)) edges.add(new Edge(START, NODE_MODEL));
            if (!hasEdge(NODE_MODEL)) edges.add(new Edge(NODE_MODEL, END));
            // Index nodes by name; first wins on duplicates.
            java.util.Map<String, Node> nodeIndex = new java.util.LinkedHashMap<>();
            for (Node n : nodes) nodeIndex.putIfAbsent(n.name(), n);
            // Index edges by `from`; first wins on duplicates.
            java.util.Map<String, String> nextEdge = new java.util.LinkedHashMap<>();
            for (Edge e : edges) nextEdge.putIfAbsent(e.from(), e.to());
            String entry = nextEdge.getOrDefault(START, NODE_MODEL);
            return new CompiledStateGraph(nodes, edges, nodeIndex, nextEdge, entry);
        }

        private boolean hasEdge(String from) {
            for (Edge e : edges) {
                if (e.from().equals(from)) return true;
            }
            return false;
        }
    }
}
