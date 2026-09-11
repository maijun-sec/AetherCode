package org.aethercode.deepagents.selfimprove;

import java.util.Objects;

/**
 * R241.2 (O-3): SPI for the {@link SelfReflectMiddleware}.
 * Tells the middleware what task kind a failure belongs
 * to (so the resulting {@link ReasoningUnit} lands in the
 * right slot of the {@link ReasoningBank}) and lets callers
 * decide what counts as a "failure" worth reflecting on.
 *
 * <p>The default {@link #always()} implementation treats
 * any thrown exception as a reflection candidate and
 * tags it with a single task kind. More sophisticated
 * implementations can look at the exception type, the tool
 * name, or the recent transcript to assign finer-grained
 * kinds (e.g. {@code "file_edit"}, {@code "build"},
 * {@code "test_run"}).
 */
@FunctionalInterface
public interface FailureClassifier {

    /**
     * One classification result. {@code taskKind} becomes
     * the bank index; {@code description} is included in
     * the reflector prompt so the model sees a one-line
     * summary of the failure alongside the raw exception.
     */
    record Classification(String taskKind, String description) {
        public Classification {
            Objects.requireNonNull(taskKind, "taskKind");
            Objects.requireNonNull(description, "description");
        }
    }

    /**
     * @param toolName the name of the tool that failed
     *                  (e.g. {@code "file_edit"}, or
     *                  {@code null} for a non-tool failure)
     * @param arguments the arguments the tool was called
     *                  with (may be {@code null} or empty)
     * @param error     the exception (or a {@code
     *                  String} wrapping a model-side
     *                  failure message)
     */
    Classification classify(String toolName, java.util.Map<String, Object> arguments,
                            Throwable error);

    /** Default: every failure gets kind {@code "tool_error"}
     *  and a description that includes the exception type
     *  and message. */
    static FailureClassifier always() {
        return (toolName, arguments, error) -> {
            StringBuilder desc = new StringBuilder();
            if (toolName != null) {
                desc.append("tool=").append(toolName).append("; ");
            }
            if (error != null) {
                desc.append(error.getClass().getSimpleName());
                String msg = error.getMessage();
                if (msg != null && !msg.isBlank()) {
                    if (msg.length() > 200) msg = msg.substring(0, 197) + "...";
                    desc.append(": ").append(msg);
                }
            } else {
                desc.append("unspecified failure");
            }
            return new Classification("tool_error", desc.toString());
        };
    }
}
