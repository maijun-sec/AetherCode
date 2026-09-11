package org.aethercode.partner.quickjs;

/**
 * Input schema for the {@code eval} tool. 1:1 port of the Python
 * {@code EvalSchema} Pydantic model in <code>middleware.py</code>.
 *
 * <p>The Java port uses a {@code record} instead of a Pydantic model
 * because the Java runtime treats tool input as a {@code Map<String,
 * Object>} keyed by argument name. The {@code code} field
 * description matches the Python version verbatim so the model sees
 * the same documentation.</p>
 */
public record EvalSchema(String code) {

    /** Default description matching the Python port. */
    public static final String CODE_DESCRIPTION =
            "JavaScript expression or statement(s) to evaluate. "
                    + "No fs/network/real-clock access.";

    public EvalSchema {
        if (code == null) {
            throw new IllegalArgumentException("`code` is required");
        }
    }
}
