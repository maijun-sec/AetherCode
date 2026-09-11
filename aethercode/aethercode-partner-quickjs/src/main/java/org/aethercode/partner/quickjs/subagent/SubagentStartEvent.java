package org.aethercode.partner.quickjs.subagent;

import java.util.Map;
import java.util.Optional;

/**
 * A subagent began running inside a <code>js_eval</code> call.
 *
 * <p>1:1 port of the Python
 * <code>SubagentStartEvent</code> TypedDict in
 * <code>_subagent.py</code>. Emitted on LangGraph's custom stream so
 * UIs can render a live fan-out panel.</p>
 */
public record SubagentStartEvent(
        String id,
        String subagentType,
        String label,
        String description,
        Optional<String> evalId,
        Map<String, Object> additionalKwargs
) implements SubagentStreamEvent {

    public SubagentStartEvent {
        additionalKwargs = additionalKwargs == null ? Map.of() : Map.copyOf(additionalKwargs);
        evalId = evalId == null ? Optional.empty() : evalId;
    }

    public String type() {
        return "subagent";
    }

    public String phase() {
        return "start";
    }
}
