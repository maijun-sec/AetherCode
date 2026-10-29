/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.deepagents.middleware;

/**
 * Thrown when the async subagent middleware tries to call a remote
 * Agent Protocol server but no {@link AsyncAgentProtocolClient} is
 * registered. Mirrors the Python port's "return error string" path
 * but as an exception so the Java port can keep tool calls
 * type-safe.</p>
 */
public class AsyncSubAgentUnavailableError extends RuntimeException {
    public AsyncSubAgentUnavailableError(String message) { super(message); }
    public AsyncSubAgentUnavailableError(String message, Throwable cause) { super(message, cause); }
}
