package org.aethercode.partner.quickjs.subagent;

import java.util.Map;
import java.util.Optional;

/**
 * A subagent raised before returning inside a <code>js_eval</code> call.
 *
 * <p>1:1 port of the Python
 * <code>SubagentErrorEvent</code> TypedDict in
 * <code>_subagent.py</code>.</p>
 */
public record SubagentErrorEvent(
        String id,
        long durationMs,
        String error,
        Optional<String> evalId,
        Map<String, Object> additionalKwargs
) implements SubagentStreamEvent {

    public SubagentErrorEvent {
        additionalKwargs = additionalKwargs == null ? Map.of() : Map.copyOf(additionalKwargs);
        evalId = evalId == null ? Optional.empty() : evalId;
    }

    public String type() {
        return "subagent";
    }

    public String phase() {
        return "error";
    }
}
