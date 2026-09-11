package org.aethercode.core.middleware;

/**
 * Package-level re-exports for the {@code deepagents.middleware} package.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware.__init__} module. As the individual
 * middleware classes are ported, this class grows to re-export them
 * under a single import.</p>
 *
 * <p>Currently exported:</p>
 * <ul>
 *   <li>{@link Middleware} &mdash; the base interface</li>
 *   <li>{@link PatchToolCallsMiddleware} &mdash; patches dangling tool calls</li>
 *   <li>{@link ToolExclusionMiddleware} &mdash; filters excluded tools</li>
 *   <li>{@link MiddlewareUtils} &mdash; helpers (e.g. {@code appendToSystemMessage})</li>
 * </ul>
 */
public final class MiddlewarePackage {
    private MiddlewarePackage() {}
}
