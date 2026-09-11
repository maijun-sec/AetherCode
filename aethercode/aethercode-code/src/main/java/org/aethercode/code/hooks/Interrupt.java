package org.aethercode.code.hooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.code.hooks.HookTransportTypes.HookInvocationRequest;
import org.aethercode.code.hooks.HookTransportTypes.HookInvocationResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client↔server interrupt transport for Hooks v2 server-owned events.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.interrupt} module. The carrier
 * envelope is sent through the LangGraph interrupt channel so the
 * client runtime can fulfill the request.</p>
 */
public final class Interrupt {

    /** Type discriminator for the LangGraph interrupt payload. */
    public static final String HOOK_INVOCATION_INTERRUPT_TYPE = "hook_invocation";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Interrupt() {}

    /**
     * Build the JSON-serializable payload to pass to {@code interrupt()}.
     *
     * @return a map that round-trips through Jackson to the
     *         <code>{"type": "hook_invocation", "request": ...}</code>
     *         shape.
     */
    public static Map<String, Object> buildInterruptPayload(HookInvocationRequest request) {
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("type", HOOK_INVOCATION_INTERRUPT_TYPE);
        outer.put("request", request);
        return outer;
    }

    /**
     * Try to parse an arbitrary value as a Hooks v2 invocation request.
     *
     * @param value raw interrupt value from the LangGraph runtime
     * @return the embedded request, or {@code null} when {@code value}
     *         does not look like a hook interrupt payload
     */
    public static HookInvocationRequest parseInterruptPayload(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        Object type = map.get("type");
        if (!HOOK_INVOCATION_INTERRUPT_TYPE.equals(type)) return null;
        Object request = map.get("request");
        if (!(request instanceof HookInvocationRequest req)) return null;
        return req;
    }

    /**
     * Return whether {@code value} looks like a Hooks v2 invocation
     * interrupt payload.
     */
    public static boolean isHookInterruptPayload(Object value) {
        if (!(value instanceof Map<?, ?> map)) return false;
        return HOOK_INVOCATION_INTERRUPT_TYPE.equals(map.get("type"));
    }

    /**
     * Build the resume value to pass to {@code Command(resume=...)}.
     */
    public static Map<String, Object> buildResumeValue(HookInvocationResponse response) {
        return MAPPER.convertValue(response, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    /**
     * Validate a resumed hook response against the outstanding request.
     *
     * @throws IllegalArgumentException when the invocation id or
     *         snapshot id does not match the outstanding request
     */
    public static HookInvocationResponse parseResumeValue(Object value, UUID invocationId,
                                                          String snapshotId) {
        if (!(value instanceof HookInvocationResponse response)) {
            throw new IllegalArgumentException("Hook resume value is not a HookInvocationResponse");
        }
        if (!response.invocationId().equals(invocationId)) {
            throw new IllegalArgumentException(
                    "Hook resume invocation_id mismatch: expected " + invocationId
                            + ", got " + response.invocationId());
        }
        if (!response.snapshotId().equals(snapshotId)) {
            throw new IllegalArgumentException(
                    "Hook resume snapshot_id mismatch: expected " + snapshotId
                            + ", got " + response.snapshotId());
        }
        return response;
    }
}
