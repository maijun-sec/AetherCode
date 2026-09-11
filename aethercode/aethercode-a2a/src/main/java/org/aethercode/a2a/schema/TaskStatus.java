package org.aethercode.a2a.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * a snapshot of an A2A {@link Task}'s lifecycle. The state
 * machine is:
 *
 * <pre>
 *   submitted → working → (input-required) → completed
 *                                  └────→ failed / canceled / rejected
 * </pre>
 *
 * <p>Per v0.3 / v1.0 spec; older v0.1 used a different spelling
 * ({@code "running"} instead of {@code "working"}). This port
 * follows the post-v0.3 convention because that is what the
 * official SDKs (a2a-python, a2a-js) emit today.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskStatus(
        @JsonProperty("state") String state,
        @JsonProperty("message") Message message,
        @JsonProperty("timestamp") String timestamp) {

    public static final String STATE_SUBMITTED     = "submitted";
    public static final String STATE_WORKING       = "working";
    public static final String STATE_INPUT_REQUIRED = "input-required";
    public static final String STATE_COMPLETED     = "completed";
    public static final String STATE_FAILED        = "failed";
    public static final String STATE_CANCELED      = "canceled";
    public static final String STATE_REJECTED      = "rejected";

    public TaskStatus {
        Objects.requireNonNull(state, "state");
        if (!isKnownState(state)) {
            throw new IllegalArgumentException("unknown state: " + state);
        }
    }

    public static TaskStatus of(String state) {
        return new TaskStatus(state, null, null);
    }
    public static TaskStatus submitted() { return new TaskStatus(STATE_SUBMITTED, null, null); }
    public static TaskStatus working()   { return new TaskStatus(STATE_WORKING, null, null); }
    public static TaskStatus completed() { return new TaskStatus(STATE_COMPLETED, null, null); }
    public static TaskStatus failed()    { return new TaskStatus(STATE_FAILED, null, null); }
    public static TaskStatus canceled()  { return new TaskStatus(STATE_CANCELED, null, null); }

    public boolean isTerminal() {
        return STATE_COMPLETED.equals(state)
                || STATE_FAILED.equals(state)
                || STATE_CANCELED.equals(state)
                || STATE_REJECTED.equals(state);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        if (message != null) m.put("message", message.toMap());
        if (timestamp != null) m.put("timestamp", timestamp);
        return m;
    }

    private static boolean isKnownState(String s) {
        return STATE_SUBMITTED.equals(s)
                || STATE_WORKING.equals(s)
                || STATE_INPUT_REQUIRED.equals(s)
                || STATE_COMPLETED.equals(s)
                || STATE_FAILED.equals(s)
                || STATE_CANCELED.equals(s)
                || STATE_REJECTED.equals(s);
    }
}
