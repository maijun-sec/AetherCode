/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.talon;

/**
 * Version marker for the Java Talon port.
 *
 * <p>Mirror of the Python {@code deepagents_talon._version} module. The
 * {@link #VERSION} string follows the upstream Python release (currently
 * {@code 0.0.3}).</p>
 */
public final class Version {
    private Version() {}

    /** Current release version. Mirrors the Python {@code __version__}. */
    public static final String VERSION = "0.0.3";
}
