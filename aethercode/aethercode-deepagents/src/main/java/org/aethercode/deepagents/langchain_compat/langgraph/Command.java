package org.aethercode.deepagents.langchain_compat.langgraph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * LangGraph-compatible {@code Command}.
 *
 * <p>Java-native port of
 * {@code langgraph.types.Command}. Carries a state update and
 * optional {@code goto} (next node) / {@code graph} commands a
 * tool or middleware returns to the runtime.</p>
 *
 * <p>Phase A1 (2026-08-28): the shim now delegates
 * {@code update} and {@code gotoNode} storage to the native
 * langgraph4j {@link org.bsc.langgraph4j.action.Command}. The
 * shim retains only the extra {@code gotoGraph} field (which the
 * native API does not expose) and a {@code withUpdate} helper
 * for callers that build up updates incrementally. When the
 * shim's {@code gotoNode} is {@code null} the native record
 * cannot be constructed (its constructor rejects null), so the
 * shim falls back to an internal {@code Map}-only path and
 * routes through the langgraph4j record once a non-null
 * {@code gotoNode} is provided.</p>
 */
public final class Command {
    private final org.bsc.langgraph4j.action.Command delegate;
    private final String gotoGraph;

    public Command(Map<String, Object> update, String gotoNode, String gotoGraph) {
        Map<String, Object> safeUpdate = update == null ? Map.of() : Map.copyOf(update);
        this.delegate = gotoNode == null
                ? null
                : new org.bsc.langgraph4j.action.Command(gotoNode, safeUpdate);
        this.gotoGraph = gotoGraph;
        // Stash update so accessors work in the null-gotoNode case.
        if (this.delegate == null) {
            this.nullUpdate = safeUpdate;
            this.nullGotoNode = gotoNode;
        } else {
            this.nullUpdate = null;
            this.nullGotoNode = null;
        }
    }

    // transient holders used only when the native delegate could not be created
    private transient final Map<String, Object> nullUpdate;
    private transient final String nullGotoNode;

    public static Command update(Map<String, Object> update) {
        return new Command(update, null, null);
    }

    public static Command goTo(String node) {
        return new Command(Map.of(), node, null);
    }

    public static Command of(Map<String, Object> update, String gotoNode) {
        return new Command(update, gotoNode, null);
    }

    public Map<String, Object> update() {
        return delegate != null ? delegate.update() : nullUpdate;
    }
    public String gotoNode() {
        return delegate != null ? delegate.gotoNode() : nullGotoNode;
    }
    public String gotoGraph() { return gotoGraph; }

    public boolean hasUpdate() {
        Map<String, Object> u = update();
        return u != null && !u.isEmpty();
    }
    public boolean hasGoto() {
        return gotoNode() != null || gotoGraph != null;
    }

    public Command withUpdate(String key, Object value) {
        Map<String, Object> next = new LinkedHashMap<>(update());
        next.put(key, value);
        return new Command(next, gotoNode(), gotoGraph);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Command c)) return false;
        return Objects.equals(update(), c.update())
                && Objects.equals(gotoNode(), c.gotoNode())
                && Objects.equals(gotoGraph, c.gotoGraph);
    }

    @Override
    public int hashCode() {
        return Objects.hash(update(), gotoNode(), gotoGraph);
    }

    @Override
    public String toString() {
        return "Command{update=" + update()
                + ", gotoNode=" + gotoNode()
                + ", gotoGraph=" + gotoGraph + "}";
    }
}
