/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.tools.plugin;

/** parse/load failure. */
public class PluginManifestException extends RuntimeException {
    public PluginManifestException(String message) { super(message); }
    public PluginManifestException(String message, Throwable cause) { super(message, cause); }
}
