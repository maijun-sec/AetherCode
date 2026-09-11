package org.aethercode.deepagents.selfimprove;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * R241.2 (O-3): a deterministic, in-process {@link Reflector}
 * for tests and offline dev. Behaves like a tiny reflective
 * table: a fixed prompt → fixed response map, with a
 * default fallback.
 *
 * <p>Use {@link #register(String, String)} to preset a
 * response for a given user prompt (exact match) and
 * {@link #registerDefault(String)} to control what happens
 * when no preset matches. Tests can also assert on
 * {@link #calls()} to confirm the middleware reached the
 * reflector.
 */
public class StubReflector implements Reflector {

    private static final Logger LOG = LoggerFactory.getLogger(StubReflector.class);

    private final Map<String, String> presets = new LinkedHashMap<>();
    private volatile String defaultResponse = "";
    private final java.util.List<CallRecord> calls = new java.util.ArrayList<>();
    private final AtomicLong totalCalls = new AtomicLong(0L);

    /** Record of one reflection call. */
    public record CallRecord(String systemPrompt, String userPrompt) {}

    public StubReflector() {}

    /** Exact-match preset. Subsequent calls with the same
     *  {@code userPrompt} return {@code response}. */
    public StubReflector register(String userPrompt, String response) {
        presets.put(Objects.requireNonNull(userPrompt, "userPrompt"),
                Objects.requireNonNull(response, "response"));
        return this;
    }

    /** Set the default response returned when no preset
     *  matches. If unset, an empty string is returned. */
    public StubReflector registerDefault(String response) {
        this.defaultResponse = Objects.requireNonNull(response, "response");
        return this;
    }

    public List<CallRecord> calls() {
        return List.copyOf(calls);
    }

    public long totalCalls() { return totalCalls.get(); }

    @Override
    public String reflect(String systemPrompt, String userPrompt) {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userPrompt, "userPrompt");
        calls.add(new CallRecord(systemPrompt, userPrompt));
        totalCalls.incrementAndGet();
        String preset = presets.get(userPrompt);
        if (preset != null) {
            LOG.debug("stub reflector: preset hit for user-prompt ({} chars)",
                    userPrompt.length());
            return preset;
        }
        LOG.debug("stub reflector: default response ({} chars user-prompt)",
                userPrompt.length());
        return defaultResponse;
    }
}
