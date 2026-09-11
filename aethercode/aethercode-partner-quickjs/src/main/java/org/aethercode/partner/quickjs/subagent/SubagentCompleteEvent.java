package org.aethercode.partner.quickjs.subagent;

import java.util.Map;
import java.util.Optional;

/**
 * A subagent finished successfully inside a <code>js_eval</code> call.
 *
 * <p>1:1 port of the Python
 * <code>SubagentCompleteEvent</code> TypedDict in
 * <code>_subagent.py</code>.</p>
 */
public record SubagentCompleteEvent(
        String id,
        long durationMs,
        Optional<String> evalId,
        Map<String, Object> additionalKwargs
) implements SubagentStreamEvent {

    public SubagentCompleteEvent {
        additionalKwargs = additionalKwargs == null ? Map.of() : Map.copyOf(additionalKwargs);
        evalId = evalId == null ? Optional.empty() : evalId;
    }

    public String type() {
        return "subagent";
    }

    public String phase() {
        return "complete";
    }
}
