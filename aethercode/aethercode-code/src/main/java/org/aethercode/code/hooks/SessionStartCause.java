/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.code.hooks;

/**
 * Reason a session started.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.SessionStartCause}
 * {@code StrEnum}.</p>
 */
public enum SessionStartCause {
    STARTUP,
    RESUME,
    CLEAR,
    COMPACT
}
