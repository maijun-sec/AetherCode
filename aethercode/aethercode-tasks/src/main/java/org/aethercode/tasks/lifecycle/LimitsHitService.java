package org.aethercode.tasks.lifecycle;

import org.aethercode.tasks.limits.Limits;
import org.aethercode.tasks.limits.LimitsEnforcer;
import org.aethercode.tasks.supervisor.ChildEventRecord;
import org.aethercode.tasks.supervisor.ChildRecord;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * prior round (T-331/§4.6 design.md): pause-on-limits glue. The limits
 * enforcer is pure; this service is the side-effectful wrapper
 * that:
 * <ol>
 *   <li>evaluates the child's {@link Limits} against its current
 *       {@link LimitsEnforcer.Usage},</li>
 *   <li>if any cap is tripped, transitions the child to
 *       {@code PAUSED} via {@link TaskStateMachine#pause},</li>
 *   <li>stores the JSON trip list in {@code children.limits_hit}
 *       so the TUI can show "raised wallClock to 10min — resume?",</li>
 *   <li>appends a {@code limits_hit} event so subscribed TUIs see
 *       the trip in their replay log.</li>
 * </ol>
 *
 * <p>Returning the {@link Verdict} lets the caller decide whether
 * to ask the user, raise the cap, or accept the partial result.
 */
public final class LimitsHitService {

    private static final Logger LOG = LoggerFactory.getLogger(LimitsHitService.class);

    private final SupervisorStore store;
    private final TaskStateMachine stateMachine;

    public LimitsHitService(SupervisorStore store, TaskStateMachine stateMachine) {
        this.store = Objects.requireNonNull(store, "store");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
    }

    /**
     * Evaluate the child's limits and, if any cap is tripped, pause
     * it. Returns the verdict (empty {@code tripped()} means the
     * child is still within budget and no state change happened).
     *
     * @param childId   the supervised child
     * @param limits    the cap to enforce (parsed from the child's
     *                  {@code config} blob by the caller)
     * @param usage     the child's current resource usage
     */
    public LimitsEnforcer.Verdict check(String childId, Limits limits,
                                        LimitsEnforcer.Usage usage) throws SQLException {
        Objects.requireNonNull(childId, "childId");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(usage, "usage");
        LimitsEnforcer.Verdict verdict = LimitsEnforcer.evaluate(limits, usage);
        if (!verdict.any()) return verdict;
        // Trip! Persist + transition.
        store.setLimitsHit(childId, verdict.toJsonList());
        // Per §4.2: "limits hit" path pauses the child; the user
        // decides whether to resume, raise the cap, or kill.
        stateMachine.pause(childId, "limits hit: " + summarise(verdict));
        // Emit a dedicated limits_hit event (ChildEventRecord.TYPE_LIMITS_HIT)
        // so the streaming layer can short-circuit and ask the user.
        Map<String, Object> payload = Map.of(
                "tripped", verdict.tripped().stream().map(LimitsEnforcer.LimitHit::toMap).toList()
        );
        String json = toJsonArray(payload);
        store.appendEvent(childId, ChildEventRecord.TYPE_LIMITS_HIT, json);
        LOG.info("child {} paused: limits hit ({})", childId, summarise(verdict));
        return verdict;
    }

    /** Read the {@code config} blob's {@code limits} field for the child. */
    public Optional<Limits> loadLimits(String childId) throws SQLException {
        Optional<ChildRecord> r = store.getChild(childId);
        if (r.isEmpty()) return Optional.empty();
        String cfg = r.get().configJson();
        if (cfg == null || cfg.isBlank()) return Optional.of(Limits.unlimited());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(cfg, Map.class);
            Object raw = parsed.get("limits");
            if (raw instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) m;
                return Optional.of(Limits.fromMap(typed));
            }
        } catch (Exception e) {
            LOG.warn("bad config json for child {}: {}", childId, e.getMessage());
        }
        return Optional.of(Limits.unlimited());
    }

    private static String summarise(LimitsEnforcer.Verdict v) {
        StringBuilder sb = new StringBuilder();
        List<LimitsEnforcer.LimitHit> hits = v.tripped();
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) sb.append(",");
            LimitsEnforcer.LimitHit h = hits.get(i);
            sb.append(h.name()).append('=').append(h.actual()).append('/').append(h.limit());
        }
        return sb.toString();
    }

    /**
     * Render {@code {"tripped":[{name,limit,actual},...]}}. Built
     * with a small hand-rolled writer to avoid pulling a JSON
     * dependency into the lifecycle package (Jackson stays in
     * {@code limits} via {@link Limits#toMap} and is also OK
     * here, but a hand-rolled writer is faster and keeps the
     * payload shape identical across versions).
     */
    private static String toJsonArray(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder("{\"tripped\":[");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tripped =
                (List<Map<String, Object>>) payload.get("tripped");
        for (int i = 0; i < tripped.size(); i++) {
            if (i > 0) sb.append(',');
            Map<String, Object> m = tripped.get(i);
            sb.append("{\"name\":\"").append(escape(m.get("name").toString()))
                    .append("\",\"limit\":").append(m.get("limit"))
                    .append(",\"actual\":").append(m.get("actual"))
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
