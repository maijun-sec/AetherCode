/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.deepagents.langchain_compat.langgraph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pre-Phase-A3 implementation of {@link CompiledStateGraph}.
 *
 * <p>Phase A3 (2026-08-28) splits the shim's behaviour
 * into an abstract base class plus two concrete
 * implementations. This class preserves the original
 * hand-rolled in-memory walk (held node list, linear
 * {@code addEdge} traversal, fire-and-forget semantics)
 * as a safe fallback. The default
 * {@link CompiledStateGraph#builder() builder} now
 * produces the langgraph4j-backed
 * {@link CompiledGraphSyncAdapter}; flip to this
 * implementation with
 * {@code CompiledStateGraph.builder().legacy()}.</p>
 *
 * <p>Behaviour matches the shim's pre-A3 contract:</p>
 * <ul>
 *   <li>{@code entryPoint} defaults to the first node's
 *       name when none is set.</li>
 *   <li>Edges are followed in insertion order; the
 *       first {@code Edge} whose {@code from} matches the
 *       current node is taken.</li>
 *   <li>When the current node has no outgoing edge the
 *       walk terminates and the final state is returned.</li>
 *   <li>When a {@link Checkpointer} is set, the final
 *       state is persisted under
 *       {@code config["configurable"]} (or {@code "default"}
 *       when absent).</li>
 * </ul>
 */
public final class LegacyCompiledStateGraph extends CompiledStateGraph {
    private final List<Node> nodes;
    private final List<Edge> edges;
    private final Checkpointer checkpointer;
    private final String entryPoint;

    LegacyCompiledStateGraph(CompiledStateGraph.Builder b) {
        this.nodes = List.copyOf(b.nodes);
        this.edges = List.copyOf(b.edges);
        this.checkpointer = b.checkpointer;
        this.entryPoint = b.entryPoint == null
                ? (nodes.isEmpty() ? null : nodes.get(0).name())
                : b.entryPoint;
    }

    @Override public List<Node> nodes() { return nodes; }
    @Override public List<Edge> edges() { return edges; }
    @Override public Checkpointer checkpointer() { return checkpointer; }
    @Override public String entryPoint() { return entryPoint; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> input, Map<String, Object> config) {
        Objects.requireNonNull(input, "input");
        Map<String, Object> state = new LinkedHashMap<>(input);
        String current = entryPoint;
        while (current != null) {
            Node node = findNode(current);
            if (node == null) break;
            state = node.handler().apply(state, config == null ? Map.of() : config);
            current = nextNode(current);
        }
        if (checkpointer != null) {
            String threadId = config == null ? null
                    : Objects.toString(config.get("configurable"), null);
            // Use the whole configurable as the key when present;
            // otherwise the "default" thread.
            checkpointer.put(threadId == null ? "default" : threadId, state, Map.of());
        }
        return state;
    }

    private Node findNode(String name) {
        for (Node n : nodes) {
            if (n.name().equals(name)) return n;
        }
        return null;
    }

    private String nextNode(String current) {
        for (Edge e : edges) {
            if (e.from().equals(current)) return e.to();
        }
        return null;
    }
}
