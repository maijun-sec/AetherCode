package org.aethercode.partner.quickjs.js;

/**
 * Base class for every QuickJS exception surfaced to Java. 1:1 port of
 * <code>quickjs_rs.JSError</code> (and the surrounding exception
 * hierarchy). Concrete subclasses map to the well-known error types
 * the REPL distinguishes in {@code EvalOutcome.errorType}.
 */
public class JsError extends RuntimeException {

    private final String name;
    private final String message;
    private final String stack;

    public JsError(String name, String message, String stack) {
        super(message);
        this.name = name == null ? "Error" : name;
        this.message = message == null ? "" : message;
        this.stack = stack;
    }

    public JsError(String message) {
        this("Error", message, null);
    }

    /** The JS error type (e.g. {@code "TypeError"}). */
    public String name() {
        return name;
    }

    @Override
    public String getMessage() {
        return message;
    }

    /** The JS error stack, or {@code null} if not provided. */
    public String stack() {
        return stack;
    }
}
