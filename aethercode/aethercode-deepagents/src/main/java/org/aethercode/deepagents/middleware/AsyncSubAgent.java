package org.aethercode.deepagents.middleware;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Specification for an async subagent running on a remote Agent
 * Protocol server.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.async_subagents.AsyncSubAgent}. The
 * record captures the (name, description, graph_id) triple the main
 * agent uses to decide when to delegate, plus optional configuration
 * for the remote server URL and HTTP headers.</p>
 */
public record AsyncSubAgent(
        String name,
        String description,
        String graphId,
        String url,
        Map<String, String> headers) {

    public AsyncSubAgent {
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
        public Builder headers(Map<String, String> v) { this.headers = v; return this; }
        public AsyncSubAgent build() {
            return new AsyncSubAgent(name, description, graphId, url, headers);
        }
    }
}
