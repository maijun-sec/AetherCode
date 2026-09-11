package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Rubric middleware retry helpers for transient grader transport failures.
 *
 * <p>Java-native port of the Python {@code deepagents_code.reliable_rubric}
 * module. The Java port exposes the transport-error classifier and the
 * control-message filter that the upstream {@code ReliableRubricMiddleware}
 * uses to decide whether to retry the nested grader.</p>
 */
public final class ReliableRubric {
    private ReliableRubric() {}

    private static final Logger LOG = LoggerFactory.getLogger(ReliableRubric.class);

    /** Source label stamped onto synthetic human messages that drive the grader. */
    public static final String RUBRIC_GRADER_MESSAGE_SOURCE = "rubric_grader";

    /** Whether the given throwable chain contains a transient transport error. */
    public static boolean isTransientGraderTransportError(Throwable exc) {
        Set<Integer> seen = new HashSet<>();
        return scan(exc, seen);
    }

    private static boolean scan(Throwable exc, Set<Integer> seen) {
        if (exc == null) return false;
        if (!seen.add(System.identityHashCode(exc))) return false;
        String typeName = exc.getClass().getName();
        if (typeName.endsWith(".ReadError")
                || typeName.endsWith(".RemoteProtocolError")) {
            return true;
        }
        if (typeName.startsWith("httpcore.")
                && (typeName.endsWith(".ReadError") || typeName.endsWith(".RemoteProtocolError"))) {
            return true;
        }
        if (typeName.equals("aiohttp.http_exceptions.TransferEncodingError")
                && exc.getMessage() != null
                && exc.getMessage().contains("Not enough data to satisfy transfer length header")) {
            return true;
        }
        // Unwrap cause / context.
        if (exc.getCause() != null && scan(exc.getCause(), seen)) return true;
        if (exc.getSuppressed() != null) {
            for (Throwable t : exc.getSuppressed()) {
                if (scan(t, seen)) return true;
            }
        }
        return false;
    }

    /**
     * Filter out internal control messages from a state map before building
     * grader evidence. The Java port uses {@code goal_state_notice} as the
     * upstream authority for "is this an internal control message?".
     */
    public static <T> java.util.List<T> withoutInternalControlMessages(java.util.List<T> messages) {
        if (messages == null) return java.util.List.of();
        java.util.List<T> out = new java.util.ArrayList<>(messages.size());
        for (T msg : messages) {
            if (GoalStateNotice.isConversationControlMessage(msg)) continue;
            out.add(msg);
        }
        return out;
    }
}
