/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.message;

/**
 * Message role — the three that matter for an LLM conversation.
 *
 * <p>Mirrors the TS original's `message.role` literal union.
 */
public enum Role {
    USER,
    ASSISTANT,
    SYSTEM,
    /** synthetic "user" message carrying a tool result; not the model role. */
    TOOL_RESULT
}
