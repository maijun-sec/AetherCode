package org.aethercode.talon.interfaces;

/**
 * Operator decision on a {@link ToolApprovalRequest}.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_talon.interfaces.ToolApprovalDecision} literal type.</p>
 */
public enum ToolApprovalDecision {
    APPROVE,
    REJECT;

    public String value() {
        return name().toLowerCase();
    }

    public static ToolApprovalDecision fromValue(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase()) {
            case "approve" -> APPROVE;
            case "reject" -> REJECT;
            default -> null;
        };
    }
}
