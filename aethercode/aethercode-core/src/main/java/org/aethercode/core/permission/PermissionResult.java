package org.aethercode.core.permission;

import java.util.Map;

/**
 * The result of asking the permission system whether a tool invocation should be allowed,
 * denied, or surfaced to the user.
 *
 * <p>Mirrors the TS {@code PermissionResult} discriminated union. The {@code ask} variant
 * carries user-facing question text; the {@code deny} variant carries a {@code decisionReason}
 * that explains why (which the model receives as a tool_result error).
 */
public sealed interface PermissionResult {

    /** Always allow. {@code updatedInput} may rewrite the input before the tool runs. */
    record Allow(Map<String, Object> updatedInput) implements PermissionResult {}

    /** Always deny. {@code message} is sent to the model; {@code decisionReason} is for logs. */
    record Deny(String message, String decisionReason) implements PermissionResult {
        public static Deny of(String message) { return new Deny(message, message); }
    }

    /** Surface to the user. The UI / TUI is responsible for rendering the question. */
    record Ask(String question, Map<String, Object> options) implements PermissionResult {
        public Ask(String question) { this(question, Map.of()); }
    }
}
