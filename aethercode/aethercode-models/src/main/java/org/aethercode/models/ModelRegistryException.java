package org.aethercode.models;

/**
 * Phase 2.2 (design.md §3.7): typed exception for the model
 * registry. The CLI / RPC layer translates this into a
 * {@code model/not_found} (-32001) / {@code model/bad_yaml}
 * (-32002) / {@code model/io} (-32003) JSON-RPC error code.
 *
 * <p>Carrying a stable {@link #code()} keeps the wire contract
 * stable across the TUI/Desktop/CLI without forcing every
 * caller to parse the message.
 */
public class ModelRegistryException extends RuntimeException {

    public enum Kind {
        NOT_FOUND,
        BAD_YAML,
        IO_ERROR,
        INVALID_NAME
    }

    private final Kind kind;

    public ModelRegistryException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ModelRegistryException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind code() {
        return kind;
    }

    public static ModelRegistryException notFound(String name) {
        return new ModelRegistryException(Kind.NOT_FOUND, "model not found: " + name);
    }

    public static ModelRegistryException badYaml(String message) {
        return new ModelRegistryException(Kind.BAD_YAML, message);
    }

    public static ModelRegistryException badYaml(String message, Throwable cause) {
        return new ModelRegistryException(Kind.BAD_YAML, message, cause);
    }

    public static ModelRegistryException ioError(String message, Throwable cause) {
        return new ModelRegistryException(Kind.IO_ERROR, message, cause);
    }

    public static ModelRegistryException invalidName(String name) {
        return new ModelRegistryException(Kind.INVALID_NAME, "invalid model name: " + name);
    }
}
