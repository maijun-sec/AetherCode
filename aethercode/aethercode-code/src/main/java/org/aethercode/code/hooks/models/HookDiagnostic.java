package org.aethercode.code.hooks.models;

import java.util.Objects;

/**
 * Structured diagnostic produced while processing a hook.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.domain.HookDiagnostic} record.</p>
 */
public record HookDiagnostic(
        String code,
        String severity,
        String message,
        String handlerId,
        String field) {

    public HookDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }

    public enum Severity { DEBUG, WARNING, ERROR }
}
