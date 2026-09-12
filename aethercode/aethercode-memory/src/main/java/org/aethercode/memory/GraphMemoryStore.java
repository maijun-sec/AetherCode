package org.aethercode.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Paper 2608.28978 Graph-based memory store.
 * <p>
 * Stores memories as a knowledge graph (nodes = memory records, edges =
 * typed relations). Supports multi-hop retrieval and basic graph operations.
 */
public final class GraphMemoryStore {

    /** Edge type. */
    public record Edge(String fromId, String toId, String type) {
        public Edge {
            Objects.requireNonNull(fromId, "fromId");
            Objects.requireNonNull(toId, "toId");
            Objects.requireNonNull(type, "type");
            if (fromId.equals(toId)) {
                throw new IllegalArgumentException("self-edge not allowed");
            }
        }
    }

    /** Node with importance score. */
    public record Node(String id, String label, Map<String, Object> attributes, double importance) {
        public Node {
            if (id == null || id.isBlank()) {
                id = UUID.randomUUID().toString();
            }
            attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
            if (importance < 0 || importance > 1) {
                throw new IllegalArgumentException("importance 0..1");
            }
            Objects.requireNonNull(label, "label");
        }
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<String, Set<String>> outgoing = new HashMap<>();
    private final Map<String, Set<String>> incoming = new HashMap<>();

    public Node addNode(Node n) {
        nodes.put(n.id(), n);
        outgoing.computeIfAbsent(n.id(), k -> new LinkedHashSet<>());
        incoming.computeIfAbsent(n.id(), k -> new LinkedHashSet<>());
        return n;
    }

    public Node addNode(String label) {
        return addNode(new Node(null, label, Map.of(), 0.5));
    }

    public Node addNode(String label, double importance) {
        return addNode(new Node(null, label, Map.of(), importance));
    }

    public void addEdge(Edge e) {
        edges.add(e);
        outgoing.computeIfAbsent(e.fromId(), k -> new LinkedHashSet<>()).add(e.toId());
        incoming.computeIfAbsent(e.toId(), k -> new LinkedHashSet<>()).add(e.fromId());
    }

    public void addEdge(String fromId, String toId, String type) {
        addEdge(new Edge(fromId, toId, type));
    }

    public Node getNode(String id) {
        return nodes.get(id);
    }

    public boolean removeNode(String id) {
        Node removed = nodes.remove(id);
        if (removed == null) return false;
        // Remove edges using iterator (avoid ConcurrentModification)
        java.util.Iterator<Edge> it = edges.iterator();
        while (it.hasNext()) {
            Edge e = it.next();
            if (e.fromId().equals(id) || e.toId().equals(id)) {
                it.remove();
            }
        }
        outgoing.remove(id);
        incoming.remove(id);
        // Update neighbor sets
        for (Set<String> set : outgoing.values()) set.remove(id);
        for (Set<String> set : incoming.values()) set.remove(id);
        return true;
    }

    public List<Edge> edges() {
        return List.copyOf(edges);
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    /** Get outgoing neighbors of a node. */
    public Set<String> outgoingNeighbors(String id) {
        return Collections.unmodifiableSet(outgoing.getOrDefault(id, Set.of()));
    }

    /** Get incoming neighbors. */
    public Set<String> incomingNeighbors(String id) {
        return Collections.unmodifiableSet(incoming.getOrDefault(id, Set.of()));
    }

    /** Multi-hop BFS: all nodes reachable within {@code depth} hops from {@code startId}. */
    public Set<String> reachWithin(String startId, int depth) {
        Objects.requireNonNull(startId, "startId");
        if (depth < 0) throw new IllegalArgumentException("depth must be >= 0");
        Set<String> visited = new LinkedHashSet<>();
        Set<String> frontier = new LinkedHashSet<>();
        frontier.add(startId);
        for (int i = 0; i <= depth; i++) {
            Set<String> next = new LinkedHashSet<>();
            for (String n : frontier) {
                if (visited.add(n)) {
                    next.addAll(outgoingNeighbors(n));
                    next.addAll(incomingNeighbors(n));
                }
            }
            if (next.isEmpty()) break;
            // exclude already visited
            next.removeAll(visited);
            frontier = next;
        }
        return visited;
    }

    /** Find shortest path between two nodes (BFS). Empty list if not connected. */
    public List<String> shortestPath(String fromId, String toId) {
        Objects.requireNonNull(fromId, "fromId");
        Objects.requireNonNull(toId, "toId");
        if (!nodes.containsKey(fromId) || !nodes.containsKey(toId)) return List.of();
        if (fromId.equals(toId)) return List.of(fromId);
        Map<String, String> parent = new HashMap<>();
        Set<String> visited = new HashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add(fromId);
        visited.add(fromId);
        boolean found = false;
        while (!queue.isEmpty() && !found) {
            String current = queue.poll();
            for (String next : outgoingNeighbors(current)) {
                if (!visited.contains(next)) {
                    visited.add(next);
                    parent.put(next, current);
                    if (next.equals(toId)) {
                        found = true;
                        break;
                    }
                    queue.add(next);
                }
            }
        }
        if (!found) return List.of();
        List<String> path = new ArrayList<>();
        String cur = toId;
        while (cur != null) {
            path.add(0, cur);
            cur = parent.get(cur);
        }
        return path;
    }

    /** Degree centrality: in-degree + out-degree. */
    public int degree(String id) {
        return outgoingNeighbors(id).size() + incomingNeighbors(id).size();
    }

    /** All nodes, sorted by importance (desc). */
    public List<Node> nodesByImportance() {
        List<Node> sorted = new ArrayList<>(nodes.values());
        sorted.sort((a, b) -> Double.compare(b.importance(), a.importance()));
        return sorted;
    }
}
