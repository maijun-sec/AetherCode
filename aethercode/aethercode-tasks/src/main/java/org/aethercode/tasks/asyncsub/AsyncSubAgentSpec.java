package org.aethercode.tasks.asyncsub;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * prior round (T-320/§4.1.3 design.md): a portable description of an
 * async subagent that the main agent loop can delegate to.
 *
 * <p>Java-native port of the deepagents-java
 * {@code AsyncSubAgent} record. The fields are:
 * <ul>
 *   <li>{@code name} — unique short identifier (the main agent
 *       uses this in {@code launch_async_task(subagentType=name)}),</li>
 *   <li>{@code description} — what the subagent does; injected
 *       into the system prompt so the main agent knows when to
 *       delegate,</li>
 *   <li>{@code graphId} — the remote graph id the wire client
 *       uses when it spawns a run,</li>
 *   <li>{@code url} — optional Agent Protocol server URL,</li>
 *   <li>{@code headers} — optional HTTP headers (e.g.
 *       {@code x-auth-scheme}).</li>
 * </ul>
 *
 * <p>The record is immutable; use {@link #builder(String, String, String)}
 * to assemble one.
 */
public record AsyncSubAgentSpec(
        String name,
        String description,
        String graphId,
        String url,
        Map<String, String> headers) {

    public AsyncSubAgentSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        if (graphId == null || graphId.isBlank()) {
            throw new IllegalArgumentException("graphId must be non-blank");
        }
        if (description == null) description = "";
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static Builder builder(String name, String description, String graphId) {
        return new Builder(name, description, graphId);
    }

    public Optional<String> urlOpt() {
        return url == null ? Optional.empty() : Optional.of(url);
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private final String graphId;
        private String url;
        private Map<String, String> headers = Map.of();

        private Builder(String name, String description, String graphId) {
            this.name = name;
            this.description = description;
            this.graphId = graphId;
        }
        public Builder url(String v) { this.url = v; return this; }
        public Builder headers(Map<String, String> v) {
            this.headers = v == null ? Map.of() : Map.copyOf(v);
            return this;
        }
        public AsyncSubAgentSpec build() {
            return new AsyncSubAgentSpec(name, description, graphId, url, headers);
        }
    }

    /** Default {@code x-auth-scheme} value (mirrors deepagents-java). */
    public static final String DEFAULT_AUTH_SCHEME = "langsmith";

    /** Resolved HTTP header key (canonical lowercase). */
    public static final String AUTH_SCHEME_HEADER = "x-auth-scheme";

    /**
     * Resolve the effective HTTP headers: start with the spec's
     * headers verbatim, then add {@code x-auth-scheme: langsmith}
     * unless the spec supplied one explicitly (case-insensitive).
     * Mirrors the Python port's {@code _resolve_headers} helper.
     */
    public Map<String, String> resolveHeaders() {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                out.put(e.getKey(), e.getValue());
            }
        }
        boolean hasExplicit = false;
        for (String key : out.keySet()) {
            if (AUTH_SCHEME_HEADER.equalsIgnoreCase(key)) {
                hasExplicit = true;
                break;
            }
        }
        if (!hasExplicit) out.put(AUTH_SCHEME_HEADER, DEFAULT_AUTH_SCHEME);
        return java.util.Collections.unmodifiableMap(out);
    }

    /** Prompts the middleware injects into the main agent's system prompt. */
    public static final class Prompts {
        private Prompts() {}

        public static final String ASYNC_TASK_TOOL_DESCRIPTION = """
                Start an async subagent. The subagent runs in the background and returns a task ID immediately.

                Available async agent types:
                {available_agents}

                ## Usage notes:
                1. This tool launches a background task and returns immediately with a task ID. Report the task ID to the user and stop — do NOT immediately check status.
                2. Use `check_async_task` only when the user asks for a status update or result.
                3. Use `update_async_task` to send new instructions to a running task.
                4. Multiple async subagents can run concurrently — launch several and let them run in the background.
                5. The subagent runs on the supervisor and shares the parent's tools and capabilities.""";

        public static final String CHECK_TOOL_DESCRIPTION =
                "Check the status of an async subagent task. Returns the current status and, if complete, the result. "
                        + "Statuses shown earlier in the conversation are always stale, so call this to get the current status "
                        + "rather than reporting a status from a previous tool result.";

        public static final String UPDATE_TOOL_DESCRIPTION =
                "Send updated instructions to an async subagent. Interrupts the current run and starts "
                        + "a new one on the same task id, so the subagent sees the full history plus "
                        + "your new message. The task_id remains the same.";

        public static final String CANCEL_TOOL_DESCRIPTION =
                "Cancel a running async subagent task. Use this to stop a task that is no longer needed.";

        public static final String LIST_TOOL_DESCRIPTION =
                "List tracked async subagent tasks with their current live statuses. "
                        + "By default shows all tasks. Use `status_filter` to narrow by status "
                        + "(e.g. 'running', 'success', 'error', 'cancelled').";
    }
}
