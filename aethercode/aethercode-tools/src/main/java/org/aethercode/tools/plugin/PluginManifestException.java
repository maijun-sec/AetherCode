package org.aethercode.tools.plugin;

/** parse/load failure. */
public class PluginManifestException extends RuntimeException {
    public PluginManifestException(String message) { super(message); }
    public PluginManifestException(String message, Throwable cause) { super(message, cause); }
}
