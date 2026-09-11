package org.aethercode.workflows;

import java.util.Objects;

/**
 * One validation finding produced by {@link WorkflowValidator}.
 * The validator accumulates these into a {@code List<ValidationError>}
 * (rather than throwing on the first failure) so the user sees every
 * problem with the workflow in one pass.
 *
 * <p>{@link #path()} is a dotted JSON-path-ish pointer to the
 * offending field ({@code "prompts[2].content"}, {@code "limits.tokens"}),
 * which the CLI prints so the user can fix the file in their editor.
 */
public record ValidationError(String path, String message) {

    public ValidationError {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
    }

    /** Static factory for a top-level error. */
    public static ValidationError of(String message) {
        return new ValidationError("(root)", message);
    }

    @Override
    public String toString() {
        return path + ": " + message;
    }
}
