package org.aethercode.workflows;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Raised when a workflow YAML cannot be parsed or fails a structural
 * check that the YAML loader owns (unreadable file, wrong root
 * type, missing required field, unknown tag, etc.). Validation
 * errors that a human can fix in the YAML file are surfaced here so
 * the CLI can show them with line numbers.
 *
 * <p>Semantic checks (kebab-case names, prompt content non-empty,
 * limits positive) live in {@link WorkflowValidator} and accumulate
 * into a {@code List<ValidationError>} instead of throwing.
 */
public class WorkflowParserException extends RuntimeException {

    private final Path source;

    public WorkflowParserException(String message) {
        this(message, null, null);
    }

    public WorkflowParserException(String message, Path source) {
        this(message, source, null);
    }

    public WorkflowParserException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public WorkflowParserException(String message, Path source, Throwable cause) {
        super(formatMessage(message, source), cause);
        this.source = source;
    }

    /** File the failure came from, or {@code null} if it was a
     *  string/byte-array load. */
    public Path source() { return source; }

    private static String formatMessage(String message, Path source) {
        Objects.requireNonNull(message, "message");
        if (source == null) return message;
        return source + ": " + message;
    }
}
