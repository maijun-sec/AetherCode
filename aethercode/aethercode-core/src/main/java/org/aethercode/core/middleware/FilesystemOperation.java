/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.middleware;

/**
 * Operations a filesystem tool can perform.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware.filesystem.FilesystemOperation} literal
 * type. The set is intentionally small: the filesystem middleware
 * uses these labels to match tool calls against permission rules.</p>
 */
public enum FilesystemOperation {
    READ,
    WRITE,
    EXECUTE
}
