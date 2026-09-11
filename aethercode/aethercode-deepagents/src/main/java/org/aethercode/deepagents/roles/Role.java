package org.aethercode.deepagents.roles;

import org.aethercode.deepagents.middleware.SubAgent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * prior round.2 (O-9): a named role inside a multi-agent workflow.
 * Inspired by Paper 6 (Brahmi 2025) §IV and Paper 8
 * (Peykani 2026) §3.2, where role-driven decomposition is the
 * primary way to break complex work into subagents.
 *
 * <p>A {@code Role} carries three things a {@link SubAgent}
 * already has plus one extra:
 *
 * <ul>
 *   <li>{@link #name} — short stable identifier
 *       (e.g. {@code "planner"}). Used by the main agent when
 *       it calls the role's tool.</li>
 *   <li>{@link #description} — when to use this role; injected
 *       into the system prompt so the main agent knows when
 *       to delegate.</li>
 *   <li>{@link #systemPrompt} — the role's own system prompt,
 *       steering its behavior (e.g. "you are a code reviewer;
 *       check for bugs and style"). Mirrors the CrewAI /
 *       AutoGen convention.</li>
 *   <li>{@link #tools} — explicit allow-list of tool names the
 *       role may call. The main agent has all tools; a
 *       reviewer role should not be able to {@code rm -rf}.
 *       Empty means "all tools".</li>
 * </ul>
 *
 * <p>The {@link #toSubAgent()} helper compiles a {@code Role}
 * to the lower-level {@link SubAgent} record that
 * {@code CreateDeepAgent} already understands. The lower-level
 * representation does not carry the tool allow-list — that
 * constraint is enforced by the role's own
 * {@code PermissionFilterMiddleware} (a future round, see
 * R243).
 */
public record Role(
        String name,
        String description,
        String systemPrompt,
        java.util.List<String> tools) {

    public Role {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("name must be non-blank");
        Objects.requireNonNull(description, "description");
        if (systemPrompt == null) systemPrompt = "";
        if (tools == null) tools = java.util.List.of();
        else tools = java.util.List.copyOf(tools);
    }

    /** Render to the lower-level {@link SubAgent} record. */
    public SubAgent toSubAgent() {
        return SubAgent.builder(name, description, systemPrompt).build();
    }

    /** Read-only tool allow-list. Empty means "all tools". */
    public java.util.List<String> tools() { return tools; }

    /** True when this role can call the given tool name. */
    public boolean allows(String toolName) {
        if (tools.isEmpty()) return true; // open allow
        return tools.contains(toolName);
    }

    /** JSON-friendly view for debugging / log lines. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("systemPrompt", systemPrompt);
        m.put("tools", tools);
        return m;
    }

    public static Builder builder(String name, String description) {
        return new Builder(name, description);
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private String systemPrompt = "";
        private java.util.List<String> tools = java.util.List.of();

        private Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }
        public Builder systemPrompt(String v) { this.systemPrompt = v == null ? "" : v; return this; }
        public Builder tools(String... names) {
            this.tools = names == null ? java.util.List.of() : java.util.List.of(names);
            return this;
        }
        public Builder tools(java.util.List<String> names) {
            this.tools = names == null ? java.util.List.of() : java.util.List.copyOf(names);
            return this;
        }
        public Role build() {
            return new Role(name, description, systemPrompt, tools);
        }
    }

    /** Optional. Used by RoleRegistry to record the source
     *  file the role was loaded from (e.g. {@code roles.yaml}). */
    public record Source(String path, int line) {
        public Source {
            Objects.requireNonNull(path, "path");
            if (line < 0) throw new IllegalArgumentException("line must be >= 0");
        }
    }
}
