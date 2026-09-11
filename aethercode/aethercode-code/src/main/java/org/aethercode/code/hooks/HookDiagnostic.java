package org.aethercode.code.hooks;

/**
 * Structured diagnostic emitted by hook loading, matching, or execution.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.HookDiagnostic} record.
 * Diagnostics are returned alongside decisions and presented to the user
 * with appropriate severity.</p>
 */
public record HookDiagnostic(
        String code,
        Severity severity,
        String message,
        String handlerId,
        String field) {

    /** Diagnostic severity, mirroring the Python literal type. */
    public enum Severity {
        DEBUG,
        WARNING,
        ERROR
    }

    public HookDiagnostic {
        if (code == null) {
            code = "";
        }
        if (severity == null) {
            severity = Severity.WARNING;
        }
        if (message == null) {
            message = "";
        }
    }
}
