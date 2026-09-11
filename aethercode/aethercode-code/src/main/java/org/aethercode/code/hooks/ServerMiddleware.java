package org.aethercode.code.hooks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.code.hooks.HookDomainEvents.Decision;
import org.aethercode.code.hooks.HookDomainEvents.PostToolUseDecision;
import org.aethercode.code.hooks.HookDomainEvents.PostToolUseFailureDecision;
import org.aethercode.code.hooks.HookDomainEvents.PreCompactDecision;
import org.aethercode.code.hooks.HookDomainEvents.PreToolUseDecision;
import org.aethercode.code.hooks.HookDomainEvents.StopDecision;
import org.aethercode.code.hooks.HookDomainEvents.SubagentStartDecision;
import org.aethercode.code.hooks.HookDomainEvents.SubagentStopDecision;
import org.aethercode.code.hooks.HookTransportTypes.HookInvocationRequest;
import org.aethercode.code.hooks.HookTransportTypes.HookInvocationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server-owned Hooks v2 lifecycle middleware.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.server_middleware} module. The
 * middleware emits {@code PreCompact}, {@code PreToolUse},
 * {@code PostToolUse}, {@code PostToolUseFailure}, {@code Stop},
 * {@code SubagentStart}, and {@code SubagentStop} through the
 * interrupt channel so the client runtime can execute matching
 * handlers and return typed decisions.</p>
 *
 * <p>Because Java has no native LangGraph runtime, this port
 * supplies a self-contained facade: a {@link ServerHooksMiddleware}
 * record that exposes the same high-level decisions and a
 * {@link Middleware} functional interface callers implement to wire
 * it into a graph runtime. The {@code interrupt} mechanism is
 * abstracted behind the {@link InterruptChannel} interface, which
 * concrete graph adapters implement.</p>
 */
public final class ServerMiddleware {

    private static final Logger LOG = LoggerFactory.getLogger(ServerMiddleware.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(600);
    private static final String STOP_STATE_KEY = "_hooks_stop_continuation_count";
    private static final String PRE_TOOL_STATE_KEY = "_hooks_pre_tool_outcomes";
    private static final String PENDING_POST_TOOL_STATE_KEY = "_hooks_pending_post_tools";
    private static final String TASK_TOOL_NAME = "task";
    private static final String COMPACT_TOOL_NAME = "compact_conversation";
    private static final UUID INVOCATION_NAMESPACE = UUID.fromString("f2896d18-cf2a-4e7d-b11a-d5b10fc0e335");

    /** Default reason a PreToolUse hook may attach when denying a call. */
    public static final String DEFAULT_DENY_REASON = "Blocked by PreToolUse hook";

    private ServerMiddleware() {}

    /** Behavior the PreToolUse gate may attach to a call. */
    public enum PreToolBehavior { ALLOW, DENY, NONE }

    /**
     * Outcome of a {@code PreToolUse} gate: either the call was
     * denied with a reason, or it was allowed/passed through with
     * optional context.
     */
    public sealed interface PreToolState
            permits PreToolState.Denied, PreToolState.Passed {

        record Denied(String reason, List<String> context) implements PreToolState {
            public Denied {
                context = context == null ? List.of() : List.copyOf(context);
            }
        }
        record Passed(String behavior, List<String> context) implements PreToolState {
            public Passed {
                context = context == null ? List.of() : List.copyOf(context);
            }
        }
    }

    /**
     * Per-call post-execution record. A duration of {@code null} means
     * "no post-tool hook is needed" (the call was denied or no events
     * are enabled); otherwise the value is the measured wall time and
     * the post hook is awaited at the next safe boundary.
     */
    public record PendingPostTool(String callId, Integer durationMs) {
        public boolean isTombstone() { return durationMs == null; }
    }

    /** Session hook gate derived from the runtime context. */
    public record SessionHookGate(String snapshotId, Set<String> events) {
        public boolean isEventEnabled(HookEvent event) {
            return events.contains(event.wireName());
        }
    }

    /** Functional interface the runtime implements to dispatch interrupts. */
    @FunctionalInterface
    public interface InterruptChannel {
        /**
         * Submit a {@link HookInvocationRequest} to the client and
         * return the typed decision it produced.
         */
        Decision emit(HookInvocationRequest request);
    }

    /**
     * State carrier for the middleware; kept as a record so the
     * graph runtime can checkpoint the relevant fields.
     */
    public record ServerHooksState(
            Integer stopContinuationCount,
            Map<String, PreToolState> preToolOutcomes,
            Map<String, Integer> pendingPostTools) {

        public ServerHooksState {
            preToolOutcomes = preToolOutcomes == null ? Map.of() : Map.copyOf(preToolOutcomes);
            pendingPostTools = pendingPostTools == null ? Map.of() : Map.copyOf(pendingPostTools);
        }

        public static ServerHooksState initial() {
            return new ServerHooksState(0, Map.of(), Map.of());
        }
    }

    /**
     * The middleware itself. Java has no LangGraph runtime, so this
     * record mirrors the upstream API and forwards invocations to a
     * caller-supplied {@link InterruptChannel}.
     */
    public record ServerHooksMiddleware(
            Path cwd,
            Duration defaultDeadline,
            boolean emitStop,
            Map<String, String> mcpServers,
            InterruptChannel channel) {

        public ServerHooksMiddleware(Path cwd, Duration defaultDeadline, boolean emitStop,
                                     Map<String, String> mcpServers, InterruptChannel channel) {
            this.cwd = cwd == null ? Path.of(".") : cwd;
            this.defaultDeadline = defaultDeadline == null ? DEFAULT_DEADLINE : defaultDeadline;
            this.emitStop = emitStop;
            this.mcpServers = mcpServers == null ? Map.of() : Map.copyOf(mcpServers);
            this.channel = channel == null ? request -> newEmptyDecision(request.invocation().event().event()) : channel;
        }

        public ServerHooksMiddleware(Path cwd) {
            this(cwd, DEFAULT_DEADLINE, true, Map.of(), null);
        }

        /**
         * Run {@code PreCompact} for one tool call.
         */
        public PreToolState runPreCompact(ServerHooksState state, SessionHookGate gate,
                                          HookContext context, ToolCallData call, String logicalEventId) {
            if (!gate.isEventEnabled(HookEvent.PRE_COMPACT)) {
                return new PreToolState.Passed("none", List.of());
            }
            HookInvocationRequest request = buildRequest(gate, context,
                    new HookDomainEvents.PreCompactEvent(HookEvent.PRE_COMPACT,
                            CompactTrigger.MANUAL, ""), logicalEventId);
            Decision decision = channel.emit(request);
            PreCompactDecision typed = requireDecision(decision, PreCompactDecision.class);
            if (!typed.continueProcessing()) {
                String reason = typed.stopReason() == null || typed.stopReason().isEmpty()
                        ? "Blocked by PreCompact hook" : typed.stopReason();
                return new PreToolState.Denied(reason, List.of());
            }
            return new PreToolState.Passed("allow", List.of());
        }

        /**
         * Run {@code PreToolUse} for one tool call.
         */
        public PreToolState runPreToolUse(ServerHooksState state, SessionHookGate gate,
                                          HookContext context, ToolCallData call) {
            if (!gate.isEventEnabled(HookEvent.PRE_TOOL_USE)) {
                return new PreToolState.Passed("none", List.of());
            }
            HookInvocationRequest request = buildRequest(gate, context,
                    new HookDomainEvents.PreToolUseEvent(HookEvent.PRE_TOOL_USE, call), null);
            Decision decision = channel.emit(request);
            PreToolUseDecision typed = requireDecision(decision, PreToolUseDecision.class);
            PermissionEffect permission = typed.permission();
            if (!typed.continueProcessing() || permission.behavior() == PermissionEffect.Behavior.DENY) {
                String reason = permission.reason();
                if (reason == null || reason.isEmpty()) reason = typed.stopReason();
                if (reason == null || reason.isEmpty()) reason = DEFAULT_DENY_REASON;
                return new PreToolState.Denied(reason, typed.context());
            }
            if (permission.behavior() == PermissionEffect.Behavior.ALLOW) {
                return new PreToolState.Passed("allow", typed.context());
            }
            return new PreToolState.Passed("none", typed.context());
        }

        /**
         * Run {@code PostToolUse} / {@code PostToolUseFailure} after
         * one call completes.
         */
        public PostToolResult runPostToolUse(ServerHooksState state, SessionHookGate gate,
                                             HookContext context, ToolCallData call,
                                             Object result, Integer durationMs) {
            if (!gate.isEventEnabled(HookEvent.POST_TOOL_USE)
                    && !gate.isEventEnabled(HookEvent.POST_TOOL_USE_FAILURE)) {
                return new PostToolResult(null, null);
            }
            // The decision depends on whether the call succeeded.
            HookInvocationRequest request;
            if (result instanceof String error) {
                request = buildRequest(gate, context,
                        new HookDomainEvents.PostToolUseFailureEvent(
                                HookEvent.POST_TOOL_USE_FAILURE, call, error, false,
                                durationMs == null ? 0 : durationMs), null);
            } else {
                request = buildRequest(gate, context,
                        new HookDomainEvents.PostToolUseEvent(
                                HookEvent.POST_TOOL_USE, call, result,
                                durationMs == null ? 0 : durationMs), null);
            }
            Decision decision = channel.emit(request);
            if (decision instanceof PostToolUseDecision p) {
                return new PostToolResult(p, null);
            } else if (decision instanceof PostToolUseFailureDecision p) {
                return new PostToolResult(p, null);
            }
            throw new IllegalStateException(
                    "Post-tool hooks must return a PostToolUse[Failure]Decision, got "
                            + decision.getClass().getSimpleName());
        }

        /**
         * Run {@code SubagentStart} for a task tool call.
         */
        public PreToolState runSubagentStart(ServerHooksState state, SessionHookGate gate,
                                            HookContext context, ToolCallData call) {
            if (call.name() == null || !call.name().equals(TASK_TOOL_NAME)
                    || !gate.isEventEnabled(HookEvent.SUBAGENT_START)) {
                return new PreToolState.Passed("none", List.of());
            }
            AgentIdentity agent = taskAgentIdentity(call);
            HookInvocationRequest request = buildRequest(gate, context,
                    new HookDomainEvents.SubagentStartEvent(HookEvent.SUBAGENT_START, agent), null);
            Decision decision = channel.emit(request);
            SubagentStartDecision typed = requireDecision(decision, SubagentStartDecision.class);
            if (!typed.continueProcessing()) {
                String reason = typed.stopReason() == null || typed.stopReason().isEmpty()
                        ? "Blocked by SubagentStart hook" : typed.stopReason();
                return new PreToolState.Denied(reason, List.of());
            }
            return new PreToolState.Passed("allow", typed.context());
        }

        /**
         * Run {@code SubagentStop} for a task tool call.
         */
        public SubagentStopDecision runSubagentStop(ServerHooksState state, SessionHookGate gate,
                                                    HookContext context, ToolCallData call,
                                                    String lastAssistantMessage) {
            if (call.name() == null || !call.name().equals(TASK_TOOL_NAME)
                    || !gate.isEventEnabled(HookEvent.SUBAGENT_STOP)) {
                return null;
            }
            AgentIdentity agent = taskAgentIdentity(call);
            HookInvocationRequest request = buildRequest(gate, context,
                    new HookDomainEvents.SubagentStopEvent(HookEvent.SUBAGENT_STOP, agent, 0,
                            lastAssistantMessage, List.of(), List.of()), null);
            Decision decision = channel.emit(request);
            return requireDecision(decision, SubagentStopDecision.class);
        }

        /**
         * Run {@code Stop} when the agent reaches a natural end.
         */
        public StopDecision runStop(ServerHooksState state, SessionHookGate gate,
                                    HookContext context, String lastAssistantMessage) {
            if (!emitStop || !gate.isEventEnabled(HookEvent.STOP)) {
                return null;
            }
            int continuation = state.stopContinuationCount() == null ? 0
                    : state.stopContinuationCount();
            HookInvocationRequest request = buildRequest(gate, context,
                    new HookDomainEvents.StopEvent(HookEvent.STOP, continuation,
                            lastAssistantMessage, List.of(), List.of()), null);
            Decision decision = channel.emit(request);
            StopDecision typed = requireDecision(decision, StopDecision.class);
            return typed;
        }

        /** Build a {@link HookInvocationRequest} for the given event. */
        public HookInvocationRequest buildRequest(SessionHookGate gate, HookContext context,
                                                 HookDomainEvents.Event event,
                                                 String logicalEventId) {
            UUID invocationId = invocationId(gate.snapshotId(), context, event, logicalEventId);
            return new HookInvocationRequest(
                    1,
                    invocationId,
                    gate.snapshotId(),
                    context.threadId(),
                    new HookInvocation(context, event),
                    Instant.now().plus(defaultDeadline));
        }

        private static AgentIdentity taskAgentIdentity(ToolCallData call) {
            Object subagentType = call.args() == null ? null : call.args().get("subagent_type");
            String name = (subagentType instanceof String s && !s.isEmpty()) ? s : "unknown";
            String id = call.id() == null || call.id().isEmpty() ? name : call.id();
            return new AgentIdentity(id, name);
        }
    }

    /**
     * Result of running a post-tool hook.
     */
    public record PostToolResult(Decision decision, SubagentStopDecision subagentStop) {}

    /**
     * Whether a hook decided permission for {@code callId}.
     */
    public static boolean hookDecidedPermission(Map<String, ?> state, String callId) {
        return hookPermissionBehavior(state, callId) != null;
    }

    /**
     * Return the explicit pre-execution hook permission for
     * {@code callId}.
     */
    public static String hookPermissionBehavior(Map<String, ?> state, String callId) {
        if (state == null) return null;
        Object raw = state.get(PRE_TOOL_STATE_KEY);
        if (!(raw instanceof Map<?, ?> outcomes)) return null;
        Object outcome = outcomes.get(callId);
        if (!(outcome instanceof PreToolState state2)) return null;
        if (state2 instanceof PreToolState.Denied) return "deny";
        if (state2 instanceof PreToolState.Passed p) {
            if ("allow".equals(p.behavior())) return "allow";
        }
        return null;
    }

    /** A neutral decision for a given event type. */
    public static Decision newEmptyDecision(HookEvent event) {
        return switch (event) {
            case SESSION_START -> new org.aethercode.code.hooks.HookDomainEvents.SessionStartDecision(
                    event, HookDomainEvents.BaseFields.neutral(), List.of());
            case SESSION_END -> new org.aethercode.code.hooks.HookDomainEvents.SessionEndDecision(
                    event, HookDomainEvents.BaseFields.neutral());
            case USER_PROMPT_SUBMIT -> new org.aethercode.code.hooks.HookDomainEvents.UserPromptSubmitDecision(
                    event, HookDomainEvents.BaseFields.neutral(), List.of(), false);
            case PERMISSION_REQUEST -> new org.aethercode.code.hooks.HookDomainEvents.PermissionRequestDecision(
                    event, HookDomainEvents.BaseFields.neutral(),
                    PermissionEffect.of(PermissionEffect.Behavior.NONE));
            case NOTIFICATION -> new org.aethercode.code.hooks.HookDomainEvents.NotificationDecision(
                    event, HookDomainEvents.BaseFields.neutral());
            case PRE_TOOL_USE -> new org.aethercode.code.hooks.HookDomainEvents.PreToolUseDecision(
                    event, HookDomainEvents.BaseFields.neutral(),
                    PermissionEffect.of(PermissionEffect.Behavior.NONE), List.of());
            case POST_TOOL_USE -> new org.aethercode.code.hooks.HookDomainEvents.PostToolUseDecision(
                    event, HookDomainEvents.BaseFields.neutral(), List.of(), List.of());
            case POST_TOOL_USE_FAILURE -> new org.aethercode.code.hooks.HookDomainEvents.PostToolUseFailureDecision(
                    event, HookDomainEvents.BaseFields.neutral(), List.of(), List.of());
            case PRE_COMPACT -> new org.aethercode.code.hooks.HookDomainEvents.PreCompactDecision(
                    event, HookDomainEvents.BaseFields.neutral());
            case STOP -> new org.aethercode.code.hooks.HookDomainEvents.StopDecision(
                    event, HookDomainEvents.BaseFields.neutral(), false, List.of());
            case SUBAGENT_START -> new org.aethercode.code.hooks.HookDomainEvents.SubagentStartDecision(
                    event, HookDomainEvents.BaseFields.neutral(), List.of());
            case SUBAGENT_STOP -> new org.aethercode.code.hooks.HookDomainEvents.SubagentStopDecision(
                    event, HookDomainEvents.BaseFields.neutral(), List.of());
        };
    }

    /** Return a {@link SessionHookGate} from a runtime context map. */
    public static Optional<SessionHookGate> sessionGate(Map<String, Object> context) {
        if (context == null) return Optional.empty();
        Object snapshotId = context.get("hooks_snapshot_id");
        Object eventsRaw = context.get("hooks_server_events");
        if (!(snapshotId instanceof String s) || s.isEmpty()) return Optional.empty();
        if (!(eventsRaw instanceof List<?> events) || events.isEmpty()) return Optional.empty();
        Set<String> set = new java.util.LinkedHashSet<>();
        for (Object event : events) set.add(String.valueOf(event));
        return Optional.of(new SessionHookGate(s, set));
    }

    /** Build a deterministic invocation id from the request identity. */
    public static UUID invocationId(String snapshotId, HookContext context,
                                    HookDomainEvents.Event event, String logicalEventId) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("thread_id", context.threadId());
        identity.put("snapshot_id", snapshotId);
        identity.put("prompt_id", context.promptId() == null ? "" : context.promptId().toString());
        identity.put("event", event.event().wireName());
        identity.put("logical_event", logicalEventIdentity(event, logicalEventId));
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(identity);
            return UUID.nameUUIDFromBytes(bytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to derive invocation id", ex);
        }
    }

    private static String logicalEventIdentity(HookDomainEvents.Event event, String logicalEventId) {
        if (event instanceof HookDomainEvents.PreToolUseEvent p) {
            return p.call().id();
        }
        if (event instanceof HookDomainEvents.PostToolUseEvent p) {
            return p.call().id();
        }
        if (event instanceof HookDomainEvents.PostToolUseFailureEvent p) {
            return p.call().id();
        }
        if (event instanceof HookDomainEvents.PreCompactEvent) {
            if (logicalEventId != null && !logicalEventId.isEmpty()) return logicalEventId;
            throw new IllegalArgumentException("PreCompact requires a stable tool-call identity");
        }
        if (event instanceof HookDomainEvents.SubagentStartEvent s) {
            return s.agent().id();
        }
        if (event instanceof HookDomainEvents.SubagentStopEvent s) {
            return s.agent().id() + ":" + s.continuationCount();
        }
        if (event instanceof HookDomainEvents.StopEvent s) {
            String hash = sha256Hex(s.lastAssistantMessage() == null
                    ? new byte[0] : s.lastAssistantMessage().getBytes());
            return s.continuationCount() + ":" + hash;
        }
        return "";
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Decision> T requireDecision(Decision decision, Class<T> type) {
        if (!type.isInstance(decision)) {
            throw new IllegalStateException(
                    "Expected " + type.getSimpleName() + ", got "
                            + decision.getClass().getSimpleName());
        }
        return (T) decision;
    }

    /**
     * A decision contract for middleware handlers that need a typed
     * response.
     */
    public interface Middleware {
        /**
         * Apply middleware to a {@code ServerHooksState}, returning
         * an updated state (and optional graph message updates).
         */
        ServerHooksState apply(ServerHooksState state, HookContext context);
    }
}
