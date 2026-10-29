/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.code.hooks.models;

/**
 * Reason a session-end event occurred.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.domain.SessionEndCause} enum.</p>
 */
public enum SessionEndCause {
    CLEAR, RESUME, PROMPT_INPUT_EXIT, OTHER
}
