package org.aethercode.code;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Trace labels that mark interrupt-resume rounds within a turn.
 *
 * <p>Grouping itself comes from callers reusing one turn's stream config, so
 * every round carries the same {@code thread_id}/{@code turn_id}/{@code turn_number}
 * (see {@code build_stream_config}). This class only adds the marker that
 * tells a resume round apart from the initial run.</p>
 *
 * <p>Java-native port of the Python {@code deepagents_code._tracing} module.</p>
 */
public final class Tracing {
    private Tracing() {}

    /**
     * Consumed by LangSmith, not by this repository: saved views and cost
     * reports filter on this literal to fold a turn's sibling root runs back
     * together. Treat the string as an external contract — renaming it
     * silently breaks those saved filters.
     */
    public static final String RESUME_TRACE_TAG = "dcode:resume";

    /**
     * Mark a graph invocation as an interrupt-resume round when it is one.
     *
     * <p>A turn's initial run stays untagged, so the tag identifies
     * continuations rather than the turn itself. Keep that asymmetry: tagging
     * unconditionally would erase the distinction this exists to draw.</p>
     *
     * <p>LangGraph treats {@code tags} as inheritable and unions them onto
     * every child config, so the tag also reaches the model, tool, and
     * subagent runs beneath a resume. Pair it with an {@code is_root} filter
     * to select resume roots alone.</p>
     *
     * @param config      map carrying this round's {@code tags} (and any other
     *                    trace metadata); not mutated
     * @param streamInput graph input for this round; a non-{@code null} marker
     *                    object that is not the {@code COMMAND} placeholder
     *                    marks a resume
     * @return {@code config} itself for an initial run; for a resume, a
     *         shallow copy whose {@code tags} carries {@link #RESUME_TRACE_TAG}
     *         exactly once. The copy shares the rest of {@code config} by
     *         reference.
     */
    public static Map<String, Object> streamTraceConfig(
            Map<String, Object> config, Object streamInput) {
        if (config == null) {
            return null;
        }
        if (!isCommand(streamInput)) {
            return config;
        }
        List<String> tags = new ArrayList<>();
        Object existing = config.get("tags");
        if (existing instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    tags.add(o.toString());
                }
            }
        }
        if (!tags.contains(RESUME_TRACE_TAG)) {
            tags.add(RESUME_TRACE_TAG);
        }
        // Defensive copy: callers share the rest of `config` by reference, but
        // the new `tags` list must be safe to mutate.
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>(config);
        copy.put("tags", List.copyOf(tags));
        return copy;
    }

    /**
     * Marker-class predicate. The Java port does not have a shared
     * {@code langgraph.types.Command} class to {@code instanceof} against, so
     * callers must pass {@code true}/{@code false} via the helper. This is
     * a tiny wrapper to keep the public API symmetric with the Python
     * {@code isinstance(streamInput, Command)} check.
     */
    private static boolean isCommand(Object value) {
        if (value == null) {
            return false;
        }
        // Heuristic: the upstream Python module keys off `langgraph.types.Command`.
        // The Java port's callers pass a marker so we can identify it without
        // depending on a specific class. By convention the marker is a
        // Map containing a `goto` or `update` key, OR a non-null non-String
        // object that isn't a `List`/`Map` of message inputs.
        return value instanceof java.util.Map<?, ?> map && (map.containsKey("goto") || map.containsKey("update"));
    }
}
