package org.aethercode.memory;

import org.aethercode.memory.GraphMemoryStore.Edge;
import org.aethercode.memory.GraphMemoryStore.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GraphMemoryStoreTest {

    @Test
    void addAndGetNode() {
        GraphMemoryStore g = new GraphMemoryStore();
        Node n = g.addNode("alice");
        assertNotNull(n.id());
        assertEquals("alice", n.label());
        assertSame(n, g.getNode(n.id()));
    }

    @Test
    void addEdgeEstablishesRelations() {
        GraphMemoryStore g = new GraphMemoryStore();
        Node a = g.addNode("a");
        Node b = g.addNode("b");
        g.addEdge(a.id(), b.id(), "knows");
        assertEquals(1, g.edgeCount());
        assertTrue(g.outgoingNeighbors(a.id()).contains(b.id()));
        assertTrue(g.incomingNeighbors(b.id()).contains(a.id()));
    }

    @Test
    void selfEdgeRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Edge("a", "a", "x"));
    }

    @Test
    void removeNodeRemovesEdges() {
        GraphMemoryStore g = new GraphMemoryStore();
        Node a = g.addNode("a");
        Node b = g.addNode("b");
        g.addEdge(a.id(), b.id(), "knows");
        assertTrue(g.removeNode(a.id()));
        assertEquals(0, g.edgeCount());
        assertEquals(1, g.nodeCount(), "b should remain");
    }

    @Test
    void removeNonExistentNodeReturnsFalse() {
        GraphMemoryStore g = new GraphMemoryStore();
        assertFalse(g.removeNode("nope"));
    }

    @Test
    void reachWithinFindsMultiHop() {
        GraphMemoryStore g = new GraphMemoryStore();
        Node a = g.addNode("a");
        Node b = g.addNode("b");
        Node c = g.addNode("c");
        Node d = g.addNode("d");
        g.addEdge(a.id(), b.id(), "p");
        g.addEdge(b.id(), c.id(), "p");
        g.addEdge(c.id(), d.id(), "p");
        Set<String> reachable = g.reachWithin(a.id(), 2);
        assertTrue(reachable.contains(a.id()));
        assertTrue(reachable.contains(b.id()));
        assertTrue(reachable.contains(c.id()));
        assertFalse(reachable.contains(d.id()), "depth 2 should not reach d");
    }

    @Test
    void reachWithinZeroDepthReturnsJustStart() {
        GraphMemoryStore g = new GraphMemoryStore();
        Node a = g.addNode("a");
        Set<String> r = g.reachWithin(a.id(), 0);
        assertEquals(1, r.size());
        assertTrue(r.contains(a.id()));
    }

    @Test
    void shortestPathFindsRoute() {
        GraphMemoryStore g = new GraphMemoryStore();
        Node a = g.addNode("a");
        Node b = g.addNode("b");
        Node c = g.addNode("c");
        g.addEdge(a.id(), b.id(), "p");
        g.addEdge(b.id(), c.id(), "p");
        List<String> path = g.shortestPath(a.id(), c.id());
        assertEquals(3, path.size());
        assertEquals(a.id(), path.get(0));
        assertEquals(c.id(), path.get(2));
    }

    @Test
    void shortestPathSameNode() {
        GraphMemoryStore g = new GraphMemoryStore();
        Node a = g.addNode("a");
        assertEquals(List.of(a.id()), g.shortestPath(a.id(), a.id()));
    }

    @Test
    void shortestPathNoRoute() {
        GraphMemoryStore g = new GraphMemoryStore();
        Node a = g.addNode("a");
        Node b = g.addNode("b");
        assertTrue(g.shortestPath(a.id(), b.id()).isEmpty());
    }

    @Test
    void shortestPathUnknownNode() {
        GraphMemoryStore g = new GraphMemoryStore();
        Node a = g.addNode("a");
        assertTrue(g.shortestPath(a.id(), "nope").isEmpty());
        assertTrue(g.shortestPath("nope", a.id()).isEmpty());
    }

    @Test
    void degree() {
        GraphMemoryStore g = new GraphMemoryStore();
        Node a = g.addNode("a");
        Node b = g.addNode("b");
        Node c = g.addNode("c");
        g.addEdge(a.id(), b.id(), "p");
        g.addEdge(c.id(), a.id(), "p");
        assertEquals(2, g.degree(a.id()));
    }

    @Test
    void nodesByImportance() {
        GraphMemoryStore g = new GraphMemoryStore();
        Node a = g.addNode("a", 0.3);
        Node b = g.addNode("b", 0.9);
        Node c = g.addNode("c", 0.6);
        List<Node> sorted = g.nodesByImportance();
        assertEquals(b.id(), sorted.get(0).id());
        assertEquals(c.id(), sorted.get(1).id());
        assertEquals(a.id(), sorted.get(2).id());
    }

    @Test
    void importanceRange() {
        assertThrows(IllegalArgumentException.class, () -> new Node("a", "x", null, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new Node("a", "x", null, 1.5));
    }

    @Test
    void emptyGraphOperations() {
        GraphMemoryStore g = new GraphMemoryStore();
        assertEquals(0, g.nodeCount());
        assertEquals(0, g.edgeCount());
        assertTrue(g.edges().isEmpty());
    }

    @Test
    void negativeDepthRejected() {
        GraphMemoryStore g = new GraphMemoryStore();
        assertThrows(IllegalArgumentException.class, () -> g.reachWithin("a", -1));
    }
}
