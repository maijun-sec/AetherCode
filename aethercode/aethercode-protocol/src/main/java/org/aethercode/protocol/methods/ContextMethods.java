package org.aethercode.protocol.methods;

import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.aethercode.protocol.jsonrpc.JsonRpcProtocolException;
import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * T-500 / design.md §2.8: registers the {@code context/*}
 * JSON-RPC methods on a {@link JsonRpcDispatcher}.
 *
 * <p>Methods exposed:
 * <ul>
 *   <li>{@code context/info} — return the current
 *       context-window usage: model, max tokens, current
 *       input tokens, percent used, last sample timestamp.
 *       Backed by the engine's {@code getContextInfo}
 *       handler when available; the JVM-side default
 *       (no engine) returns zeros so the TUI can render
 *       a "no engine" state.</li>
 * </ul>
 *
 * <p>The TUI's {@code ContextMeter} component (T-180 /
 * §2.8) polls this every 2 seconds and renders the 3
 * colour bands. The current usage is the model-side
 * {@code inputTokens}; the cap is the model's
 * {@code maxTokens}. The {@code lastSampleAtMs} is
 * populated whenever the engine reports a new
 * {@code stream_event} with token counts.
 */
public final class ContextMethods {

    private static final Logger LOG = LoggerFactory.getLogger(ContextMethods.class);

    public static final String METHOD_INFO = "context/info";

    private final AtomicReference<String>  model          = new AtomicReference<>("unknown");
    private final AtomicLong                maxTokens     = new AtomicLong(200_000);
    private final AtomicLong                inputTokens   = new AtomicLong(0);
    private final AtomicLong                lastSampleAtMs= new AtomicLong(0);

    public ContextMethods() {}

    // ------------------------------------------------------------------
    //  Registration
    // ------------------------------------------------------------------

    public void registerAll(JsonRpcDispatcher dispatcher) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        dispatcher.register(METHOD_INFO, this::info);
    }

    /** Engine hook: report the latest input-token count
     *  for the current model. Called from the engine
     *  after every LLM turn. */
    public void reportInputTokens(String modelName, long input) {
        if (modelName != null && !modelName.isBlank()) {
            model.set(modelName);
        }
        inputTokens.set(Math.max(0, input));
        lastSampleAtMs.set(System.currentTimeMillis());
    }

    /** Engine hook: update the model's max-token cap. */
    public void setMaxTokens(String modelName, long max) {
        if (modelName != null && !modelName.isBlank()) {
            model.set(modelName);
        }
        if (max > 0) maxTokens.set(max);
    }

    // ------------------------------------------------------------------
    //  context/info
    // ------------------------------------------------------------------

    public Map<String, Object> info(Object params) {
        long max = maxTokens.get();
        long used = inputTokens.get();
        double pct = max > 0 ? (used * 100.0 / max) : 0.0;
        String band;
        if (pct < 50.0)      band = "green";
        else if (pct < 80.0) band = "amber";
        else                 band = "red";

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("model", model.get());
        r.put("maxTokens", max);
        r.put("inputTokens", used);
        r.put("percent", Math.round(pct * 10.0) / 10.0);
        r.put("band", band);
        r.put("lastSampleAtMs", lastSampleAtMs.get());
        return r;
    }

    private static Map<String, Object> asMap(Object params) {
        if (params == null) return java.util.Collections.emptyMap();
        if (!(params instanceof Map)) {
            throw new JsonRpcProtocolException(
                    "context/* params must be an object",
                    JsonRpcError.invalidParams("expected object"));
        }
        return (Map<String, Object>) params;
    }
}
