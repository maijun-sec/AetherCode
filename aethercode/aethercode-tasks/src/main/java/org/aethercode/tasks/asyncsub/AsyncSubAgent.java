package org.aethercode.tasks.asyncsub;

import org.aethercode.tasks.limits.Limits;
import org.aethercode.tasks.lifecycle.TaskState;
import org.aethercode.tasks.lifecycle.TaskStateMachine;
import org.aethercode.tasks.limits.LimitsEnforcer;
import org.aethercode.tasks.supervisor.ChildEventRecord;
import org.aethercode.tasks.supervisor.ChildRecord;
import org.aethercode.tasks.supervisor.SupervisorStore;
import org.aethercode.tasks.supervisor.SupervisorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * prior round (T-320/§4.1.3 design.md): the driver that talks to the
 * supervisor on behalf of an async subagent. Mirrors the four
 * methods the design doc lists for the TS class:
 * <ul>
 *   <li>{@link #launch(String, String, Limits, AsyncSubAgentSpec)} — spawn a new
 *       child via {@code task/spawn} and start polling,</li>
 *   <li>{@link #check(String)} — fetch the current status from the
 *       store,</li>
 *   <li>{@link #update(String, String, String)} — append a new
 *       message to the child's {@code state.events} blob,</li>
 *   <li>{@link #cancel(String, String)} — call {@code task/kill} on
 *       the child,</li>
 *   <li>{@link #streamEvents(String)} — return a reactive stream of
 *       events for the child.</li>
 * </ul>
 *
 * <p>Usage from an agent loop:
 * <pre>{@code
 *   AsyncSubAgent driver = new AsyncSubAgent(service, store, stateMachine);
 *   String id = driver.launch("summarise the README",
 *                             "/home/user/proj",
 *                             Limits.builder().wallClockMs(60_000).build(),
 *                             spec).get();
 *   String status = driver.check(id).get().status().name();
 *   driver.cancel(id, "user aborted");
 * }</pre>
 */
public final class AsyncSubAgent {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncSubAgent.class);

    private final SupervisorService service;
    private final SupervisorStore store;
    private final TaskStateMachine stateMachine;
    private final AtomicLong launchCounter = new AtomicLong(0L);

    public AsyncSubAgent(SupervisorService service,
                         SupervisorStore store,
                         TaskStateMachine stateMachine) {
        this.service = Objects.requireNonNull(service, "service");
        this.store = Objects.requireNonNull(store, "store");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
    }

    public SupervisorService service() { return service; }
    public SupervisorStore store()     { return store; }
    public TaskStateMachine stateMachine() { return stateMachine; }

    // -- launch ------------------------------------------------------------

    /**
     * Spawn a new child for the given spec / prompt / cwd. The
     * {@code limits} blob is folded into the child's {@code config}
     * JSON so {@link org.aethercode.tasks.lifecycle.LimitsHitService}
     * can find it later.
     */
    public CompletableFuture<LaunchResult> launch(String prompt,
                                                  String cwd,
                                                  Limits limits,
                                                  AsyncSubAgentSpec spec) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(spec, "spec");
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("prompt", prompt);
                params.put("cwd", cwd);
                params.put("model", spec.name());
                // Build the config JSON by hand (Jackson-free) so
                // the limits blob is parseable later by
                // LimitsHitService.loadLimits. We also embed the
                // model name so the persisted child row records
                // which subagent spec spawned it.
                StringBuilder cfg = new StringBuilder("{");
                if (limits != null && !limits.isUnlimited()) {
                    cfg.append("\"limits\":").append(jsonOfMap(limits.toMap()));
                    cfg.append(',');
                }
                cfg.append("\"model\":\"").append(escape(spec.name())).append("\"");
                cfg.append('}');
                params.put("config", cfg.toString());
                Map<String, Object> reply = service.taskSpawn(params);
                String childId = (String) reply.get("childId");
                long local = launchCounter.incrementAndGet();
                LOG.info("launched async subagent '{}' as {} (local#{})", spec.name(), childId, local);
                return new LaunchResult(childId, spec, local);
            } catch (SQLException e) {
                throw new RuntimeException("launch failed: " + e.getMessage(), e);
            }
        });
    }

    private static String jsonOfMap(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number) sb.append(v.toString());
            else sb.append('"').append(escape(v.toString())).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    // -- check -------------------------------------------------------------

    public CompletableFuture<StatusReport> check(String childId) {
        Objects.requireNonNull(childId, "childId");
        return CompletableFuture.supplyAsync(() -> {
            try {
                ChildRecord r = store.getChild(childId)
                        .orElseThrow(() -> new IllegalArgumentException("unknown childId: " + childId));
                TaskState state = TaskState.fromSupervisor(r.status());
                String err = r.error();
                long lastEventId = 0L;
                List<ChildEventRecord> events = store.listEvents(childId, 0L, 1);
                if (!events.isEmpty()) lastEventId = events.get(events.size() - 1).id();
                return new StatusReport(childId, state, err, lastEventId, r.endedAtMs());
            } catch (SQLException e) {
                throw new RuntimeException("check failed: " + e.getMessage(), e);
            }
        });
    }

    // -- update ------------------------------------------------------------

    /**
     * Append a new message to the child's tracked state. Returns
     * the new event id (visible in {@code child_events}). The wire
     * format mirrors what the deepagents-java
     * {@code update_async_task} tool would send: a single
     * {@code model_message} event with the new instruction.
     */
    public CompletableFuture<Long> update(String childId, String agentType, String message) {
        Objects.requireNonNull(childId, "childId");
        Objects.requireNonNull(message, "message");
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("childId", childId);
                params.put("type", ChildEventRecord.TYPE_MODEL_MESSAGE);
                String payload = "{\"agent\":\"" + escape(agentType == null ? "" : agentType)
                        + "\",\"message\":\"" + escape(message) + "\",\"update\":true}";
                params.put("payload", payload);
                Map<String, Object> reply = service.taskAppendEvent(params);
                Object id = reply.get("eventId");
                return id instanceof Number n ? n.longValue() : 0L;
            } catch (SQLException e) {
                throw new RuntimeException("update failed: " + e.getMessage(), e);
            }
        });
    }

    // -- cancel ------------------------------------------------------------

    public CompletableFuture<Boolean> cancel(String childId, String reason) {
        Objects.requireNonNull(childId, "childId");
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("childId", childId);
                if (reason != null) params.put("reason", reason);
                Map<String, Object> reply = service.taskKill(params);
                Object ok = reply.get("ok");
                return Boolean.TRUE.equals(ok);
            } catch (SQLException e) {
                throw new RuntimeException("cancel failed: " + e.getMessage(), e);
            }
        });
    }

    // -- stream ------------------------------------------------------------

    /** Open a {@link org.aethercode.tasks.streaming.ChildEventStream} for the child. */
    public org.aethercode.tasks.streaming.ChildEventStream streamEvents(String childId) {
        return new org.aethercode.tasks.streaming.ChildEventStream(store, childId);
    }

    // -- helpers -----------------------------------------------------------

    /**
     * Force-evaluate limits for the child. Convenience that pulls
     * the {@link Limits} from the child's {@code config} blob and
     * runs the enforcer; returns the verdict. Used by tests and
     * by the supervisor's loop driver.
     */
    public Optional<LimitsEnforcer.Verdict> checkLimits(String childId, LimitsEnforcer.Usage usage)
            throws SQLException {
        Optional<ChildRecord> r = store.getChild(childId);
        if (r.isEmpty()) return Optional.empty();
        String cfg = r.get().configJson();
        Limits limits = Limits.unlimited();
        if (cfg != null && !cfg.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(cfg, Map.class);
                Object raw = parsed.get("limits");
                if (raw instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) m;
                    limits = Limits.fromMap(typed);
                }
            } catch (Exception e) {
                LOG.warn("bad config json for child {}: {}", childId, e.getMessage());
            }
        }
        return Optional.of(LimitsEnforcer.evaluate(limits, usage));
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

    // -- result types ------------------------------------------------------

    /** Returned by {@link #launch}. */
    public record LaunchResult(String childId, AsyncSubAgentSpec spec, long localSeq) {}

    /** Returned by {@link #check}. */
    public record StatusReport(String childId, TaskState status, String error,
                               long lastEventId, Long endedAtMs) {
        public boolean isTerminal() { return status.isTerminal(); }
    }
}
