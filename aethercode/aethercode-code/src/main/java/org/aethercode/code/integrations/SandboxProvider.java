/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.code.integrations;

/**
 * Contract every sandbox provider implements.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.integrations.sandbox_provider} module.</p>
 */
public interface SandboxProvider {

    /** Provider display name. */
    String name();

    /** Whether this provider supports an async creation path. */
    boolean supportsAsync();

    /** Create a sandbox with the given configuration. */
    SandboxHandle create(SandboxConfig config) throws Exception;

    /**
     * Lightweight handle to a created sandbox.
     */
    interface SandboxHandle extends AutoCloseable {
        String id();
        Object session();
        @Override void close() throws Exception;
    }
}
