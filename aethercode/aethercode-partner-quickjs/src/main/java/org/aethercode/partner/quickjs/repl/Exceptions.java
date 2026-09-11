package org.aethercode.partner.quickjs.repl;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * REPL-side exception types: PTC call budget overrun, task bridge
 * wrapping, and helpers for cross-thread exception handling. 1:1 port
 * of {@code _PTCCallBudgetExceededError}, {@code _TaskBridgeError},
 * and {@code _clear_exception_references} in the Python
 * <code>_repl.py</code>.
 */
public final class Exceptions {

    private Exceptions() {}

    /**
     * Raised when one eval exceeds its configured PTC call budget.
     * Mirrors the Python {@code _PTCCallBudgetExceededError}.
     */
    public static class PtcCallBudgetExceededException extends RuntimeException {
        private final int limit;
        private final int attempted;
        private final String functionName;

        public PtcCallBudgetExceededException(int limit, int attempted, String functionName) {
            super("PTC call budget exceeded (limit=" + limit
                    + ", attempted=" + attempted + ", function=" + functionName + ")");
            this.limit = limit;
            this.attempted = attempted;
            this.functionName = functionName;
        }

        public int limit() { return limit; }
        public int attempted() { return attempted; }
        public String functionName() { return functionName; }

        public String renderMessage() {
            return "PTC call budget exceeded (limit=" + limit
                    + ", attempted=" + attempted + ", function=" + functionName + ")";
        }
    }

    /**
     * Wrap errors from the top-level {@code task()} host function.
     * Mirrors the Python {@code _TaskBridgeError}.
     */
    public static class TaskBridgeException extends RuntimeException {
        private final String errorType;
        private final String errorMessage;

        public TaskBridgeException(Throwable cause) {
            super(cause == null || cause.getMessage() == null ? "" : cause.getMessage(), cause);
            this.errorType = cause == null ? "TaskBridgeError" : cause.getClass().getSimpleName();
            this.errorMessage = cause == null ? "" : (cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }

        public String errorType() { return errorType; }
        public String errorMessage() { return errorMessage; }
    }

    /**
     * Drop traceback links to avoid cross-thread GC finalizing QJS
     * handles. The Python port mutates {@code __traceback__} /
     * {@code __context__} / {@code __cause__}; the Java equivalent is
     * a no-op for the common case because Java GC handles cycles
     * across threads correctly, but the call is preserved to give
     * downstream bindings a single hook point to clear native
     * references.
     */
    public static void clearExceptionReferences(Throwable t) {
        if (t != null) {
            t.setStackTrace(new StackTraceElement[0]);
        }
    }

    /**
     * Run {@code future} and unwrap the {@link PtcCallBudgetExceededException}
     * into an {@link EvalOutcome} with the {@code PTCCallBudgetExceeded}
     * error type. Used by the {@code _aeval_async} dispatch.
     */
    public static Optional<EvalOutcome> tryUnwrapPtcBudget(Throwable t) {
        if (t instanceof PtcCallBudgetExceededException e) {
            return Optional.of(EvalOutcome.empty().withError("PTCCallBudgetExceeded", e.renderMessage(), null));
        }
        return Optional.empty();
    }

    /** Convenience: a never-resolving future used to model cancelled async paths. */
    public static <T> CompletableFuture<T> cancelledFuture() {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.cancel(true);
        return f;
    }
}
