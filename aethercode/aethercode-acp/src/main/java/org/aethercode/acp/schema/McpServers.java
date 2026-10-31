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

package org.aethercode.acp.schema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ACP MCP server configuration records.
 *
 * <p>Java-native port of the {@code acp.schema.McpServer*} family.
 * ACP supports HTTP, SSE, and stdio MCP transports; the Java
 * port models them as a sealed {@link McpServer} hierarchy.</p>
 */
public final class McpServers {
    private McpServers() {}

    /**
     * Common MCP server metadata. Mirrors
     * {@code acp.schema.McpServer} base class.
     */
    public sealed interface McpServer
            permits HttpMcpServer, SseMcpServer, McpServerStdio {
        /** Stable identifier (used for tool-name prefixes). */
        String name();
    }

    /** HTTP transport. */
    public record HttpMcpServer(
            String name,
            String url,
            List<String> headers) implements McpServer {
        public HttpMcpServer {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(url, "url");
            headers = headers == null ? List.of() : List.copyOf(headers);
        }
    }

    /** Server-Sent Events transport. */
    public record SseMcpServer(
            String name,
            String url,
            List<String> headers) implements McpServer {
        public SseMcpServer {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(url, "url");
            headers = headers == null ? List.of() : List.copyOf(headers);
        }
    }

    /** Local subprocess transport. */
    public record McpServerStdio(
            String name,
            String command,
            List<String> args,
            Optional<List<String>> env) implements McpServer {
        public McpServerStdio {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(command, "command");
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Optional.empty() : env;
        }
    }
}
