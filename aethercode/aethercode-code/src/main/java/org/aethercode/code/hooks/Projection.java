package org.aethercode.code.hooks;

import org.aethercode.code.hooks.HookDomainEvents.Event;
import org.aethercode.code.hooks.HookDomainEvents.PermissionRequestEvent;
import org.aethercode.code.hooks.HookDomainEvents.PostToolUseEvent;
import org.aethercode.code.hooks.HookDomainEvents.PostToolUseFailureEvent;
import org.aethercode.code.hooks.HookDomainEvents.PreCompactEvent;
import org.aethercode.code.hooks.HookDomainEvents.PreToolUseEvent;
import org.aethercode.code.hooks.HookDomainEvents.SessionEndEvent;
import org.aethercode.code.hooks.HookDomainEvents.SessionStartEvent;
import org.aethercode.code.hooks.HookDomainEvents.StopEvent;
import org.aethercode.code.hooks.HookDomainEvents.SubagentStartEvent;
import org.aethercode.code.hooks.HookDomainEvents.SubagentStopEvent;
import org.aethercode.code.hooks.HookDomainEvents.UserPromptSubmitEvent;
import org.aethercode.code.hooks.HookDomainEvents.NotificationEvent;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Projection from Hooks v2 domain invocations to compatible wire input.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.projection} module. Each event class
 * triggers a {@code singledispatch} arm in Python; in Java a simple
 * {@code switch} pattern covers the same dispatch.</p>
 */
public final class Projection {

    private final Tools tools;

    public Projection() {
        this(new Tools());
    }

    public Projection(Tools tools) {
        this.tools = tools;
    }

    /** Project a domain invocation into its wire envelope. */
    public HookEnvelopeAdapter.WireEnvelope project(HookInvocation invocation, Path transcriptPath,
                                                    Path agentTranscriptPath) {
        return projectEvent(invocation.event(), invocation, transcriptPath, agentTranscriptPath);
    }

    private HookEnvelopeAdapter.WireEnvelope projectEvent(Event event, HookInvocation invocation,
                                                          Path transcriptPath,
                                                          Path agentTranscriptPath) {
        HookEnvelopeAdapter.BaseWireFields base = baseFields(invocation, transcriptPath, null);
        if (event instanceof SessionStartEvent s) {
            return build(base, "SessionStart", s.cause(), Map.of(
                    "source", s.cause().name().toLowerCase(),
                    "model", s.model() == null ? "" : s.model()));
        }
        if (event instanceof UserPromptSubmitEvent u) {
            return build(base, "UserPromptSubmit", null, Map.of("prompt", u.prompt()));
        }
        if (event instanceof SessionEndEvent s) {
            return build(base, "SessionEnd", null, Map.of(
                    "reason", s.cause().name().toLowerCase()));
        }
        if (event instanceof PermissionRequestEvent p) {
            return build(base, "PermissionRequest", null, toolFields(p.call()));
        }
        if (event instanceof NotificationEvent n) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("message", n.notification().message());
            if (n.notification().title() != null) {
                extra.put("title", n.notification().title());
            }
            extra.put("notification_type", toWireNotificationType(n.notification().type()).name().toLowerCase());
            return build(base, "Notification", null, extra);
        }
        if (event instanceof PreToolUseEvent p) {
            Map<String, Object> extra = new LinkedHashMap<>(toolFields(p.call()));
            extra.put("tool_use_id", p.call().id());
            return build(base, "PreToolUse", null, extra);
        }
        if (event instanceof PostToolUseEvent p) {
            Map<String, Object> extra = new LinkedHashMap<>(toolFields(p.call()));
            extra.put("tool_response", p.toolResponse());
            extra.put("tool_use_id", p.call().id());
            extra.put("duration_ms", p.durationMs());
            return build(base, "PostToolUse", null, extra);
        }
        if (event instanceof PostToolUseFailureEvent p) {
            Map<String, Object> extra = new LinkedHashMap<>(toolFields(p.call()));
            extra.put("tool_use_id", p.call().id());
            extra.put("error", p.error());
            extra.put("is_interrupt", p.isInterrupt());
            extra.put("duration_ms", p.durationMs());
            return build(base, "PostToolUseFailure", null, extra);
        }
        if (event instanceof PreCompactEvent p) {
            return build(base, "PreCompact", null, Map.of(
                    "trigger", p.trigger().name().toLowerCase(),
                    "custom_instructions", p.customInstructions() == null ? "" : p.customInstructions()));
        }
        if (event instanceof StopEvent s) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("stop_hook_active", s.continuationCount() > 0);
            extra.put("last_assistant_message", s.lastAssistantMessage() == null ? "" : s.lastAssistantMessage());
            extra.put("background_tasks", s.backgroundTasks().stream().map(t -> Map.of(
                    "task_id", t.taskId(), "status", t.status())).toList());
            extra.put("session_crons", s.sessionCrons().stream().map(c -> Map.of(
                    "id", c.id(), "expression", c.expression())).toList());
            return build(base, "Stop", null, extra);
        }
        if (event instanceof SubagentStartEvent s) {
            return build(base, "SubagentStart", s.agent(), Map.of());
        }
        if (event instanceof SubagentStopEvent s) {
            if (agentTranscriptPath == null) {
                throw new IllegalArgumentException(
                        "SubagentStop requires a materialized agent transcript path");
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("stop_hook_active", s.continuationCount() > 0);
            extra.put("agent_transcript_path", agentTranscriptPath.toString());
            extra.put("last_assistant_message", s.lastAssistantMessage() == null ? "" : s.lastAssistantMessage());
            extra.put("background_tasks", s.backgroundTasks().stream().map(t -> Map.of(
                    "task_id", t.taskId(), "status", t.status())).toList());
            extra.put("session_crons", s.sessionCrons().stream().map(c -> Map.of(
                    "id", c.id(), "expression", c.expression())).toList());
            return build(base, "SubagentStop", s.agent(), extra);
        }
        throw new IllegalArgumentException("Unsupported hook event: " + event.getClass().getSimpleName());
    }

    private static HookEnvelopeAdapter.WireEnvelope build(
            HookEnvelopeAdapter.BaseWireFields base, String hookEventName, Object extra,
            Map<String, Object> specific) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("hook_event_name", hookEventName);
        fields.putAll(specific);
        if (extra != null) {
            fields.put("__extra__", extra);
        }
        return new HookEnvelopeAdapter.WireEnvelope(base, hookEventName, null, fields);
    }

    private static Map<String, Object> toolFields(ToolCallData call) {
        Map<String, Object> out = new LinkedHashMap<>();
        String wireName = Tools.toWireToolName(call.name(), call.mcpServer());
        out.put("tool_name", wireName);
        out.put("tool_input", Tools.toWireToolInput(call.name(), call.args()));
        return out;
    }

    /** Build the shared base fields used by every wire envelope. */
    public HookEnvelopeAdapter.BaseWireFields baseFields(HookInvocation invocation, Path transcriptPath,
                                                         AgentIdentity agentOverride) {
        HookContext context = invocation.context();
        AgentIdentity identity = agentOverride != null ? agentOverride : context.agent();
        WireTypes.WirePermissionMode mode;
        try {
            mode = switch (context.approvalMode()) {
                case MANUAL -> WireTypes.WirePermissionMode.DEFAULT;
                case AUTO -> WireTypes.WirePermissionMode.AUTO;
                case YOLO -> WireTypes.WirePermissionMode.BYPASS_PERMISSIONS;
            };
        } catch (Exception ex) {
            mode = WireTypes.WirePermissionMode.DEFAULT;
        }
        WireTypes.Effort effort = context.effort() == null ? null : new WireTypes.Effort(context.effort());
        String promptId = context.promptId() == null ? null : context.promptId().toString();
        return new HookEnvelopeAdapter.BaseWireFields(
                context.threadId(),
                transcriptPath.toString(),
                context.cwd().toString(),
                mode,
                promptId,
                effort,
                identity == null ? null : identity.id(),
                identity == null ? null : identity.name());
    }

    /**
     * Map a domain notification type to the canonical wire notification
     * matcher.
     */
    public static WireTypes.WireNotificationType toWireNotificationType(DcodeNotificationKind value) {
        if (value == null) {
            throw new IllegalArgumentException("Unsupported notification type: null");
        }
        return switch (value) {
            case PERMISSION_REQUIRED -> WireTypes.WireNotificationType.PERMISSION_PROMPT;
            case AGENT_NEEDS_INPUT -> WireTypes.WireNotificationType.AGENT_NEEDS_INPUT;
            case AGENT_COMPLETED -> WireTypes.WireNotificationType.AGENT_COMPLETED;
            case COLD_CACHE_WARNING -> WireTypes.WireNotificationType.COLD_CACHE_WARNING;
        };
    }

    /** Overload that accepts the wire value verbatim for round-tripping. */
    public static WireTypes.WireNotificationType toWireNotificationType(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Unsupported notification type: null");
        }
        try {
            return WireTypes.WireNotificationType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported notification type: " + value, ex);
        }
    }
}
