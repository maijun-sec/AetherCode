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
