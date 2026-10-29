/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core;

/**
 * Version information for the Java deepagents port.
 *
 * <p>Mirror of the Python {@code deepagents._version} module. The
 * {@link #VERSION} string follows the upstream Python release (currently
 * {@code 0.7.8}).</p>
 */
public final class Version {
    private Version() {}

    /** Current release version. Mirrors the Python {@code __version__}. */
    public static final String VERSION = "0.7.8";
}
