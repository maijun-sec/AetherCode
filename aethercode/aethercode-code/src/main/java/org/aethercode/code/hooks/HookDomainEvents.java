package org.aethercode.code.hooks;

import java.util.List;
import java.util.Map;

/**
 * All Hooks v2 lifecycle events and the matching decisions, modelled as one
 * sealed hierarchy so {@code switch} expressions can exhaustively cover the
 * domain.
 *
 * <p>Java-native port of the {@code HookDomainEvent} and
 * {@code HookDecision} base types in
 * {@code deepagents_code.hooks.models.domain}. Each Python class
 * (<code>SessionStartEvent</code>, <code>PreToolUseDecision</code>, etc.)
 * corresponds to one record in this file; common fields are inherited from
 * {@link Event} and {@link Decision}.</p>
 */
public final class HookDomainEvents {

    private HookDomainEvents() {}

    /** Identifier for a tool-use or subagent invocation. */
    public record BackgroundTask(String taskId, String status) {}

    /** Persisted cron descriptor for a session. */
    public record SessionCron(String id, String expression) {}

    // -- Base event / decision records ---------------------------------

    /** Common base for every domain event. */
    public sealed interface Event
            permits SessionStartEvent, UserPromptSubmitEvent, SessionEndEvent,
                    PermissionRequestEvent, NotificationEvent, PreToolUseEvent,
                    PostToolUseEvent, PostToolUseFailureEvent, PreCompactEvent,
                    StopEvent, SubagentStartEvent, SubagentStopEvent {
        HookEvent event();
    }

    /** Common base for every reduced decision. */
    public sealed interface Decision
            permits SessionStartDecision, UserPromptSubmitDecision,
                    SessionEndDecision, PermissionRequestDecision,
                    NotificationDecision, PreToolUseDecision,
                    PostToolUseDecision, PostToolUseFailureDecision,
                    PreCompactDecision, StopDecision,
                    SubagentStartDecision, SubagentStopDecision {
        HookEvent event();

        /** Common base fields carried by every decision. */
        BaseFields base();

        /** Diagnostics emitted while reducing this decision. */
        default List<HookDiagnostic> diagnostics() {
            BaseFields b = base();
            return b == null ? List.of() : b.diagnostics();
        }

        /** User-facing notices to surface after reduction. */
        default List<String> userNotices() {
            BaseFields b = base();
            return b == null ? List.of() : b.userNotices();
        }

        /** Terminal escape sequences to flush after reduction. */
        default List<String> terminalSequences() {
            BaseFields b = base();
            return b == null ? List.of() : b.terminalSequences();
        }

        /** Convenience: the {@code continue_processing} flag from the base. */
        default boolean continueProcessing() {
            BaseFields b = base();
            return b == null || b.continueProcessing();
        }

        /** Convenience: the stop-reason from the base, if any. */
        default String stopReason() {
            BaseFields b = base();
            return b == null ? null : b.stopReason();
        }
    }

    /** Common fields shared by every concrete decision. */
    public record BaseFields(
            boolean continueProcessing,
            String stopReason,
            List<String> userNotices,
            List<String> terminalSequences,
            List<HookDiagnostic> diagnostics) {
        public BaseFields {
            userNotices = userNotices == null ? List.of() : List.copyOf(userNotices);
            terminalSequences = terminalSequences == null ? List.of() : List.copyOf(terminalSequences);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        /** Neutral defaults used when no handlers ran. */
        public static BaseFields neutral() {
            return new BaseFields(true, null, List.of(), List.of(), List.of());
        }
    }

    // -- Events --------------------------------------------------------

    public record SessionStartEvent(HookEvent event, SessionStartCause cause, String model)
            implements Event {}

    public record UserPromptSubmitEvent(HookEvent event, String prompt)
            implements Event {}

    public record SessionEndEvent(HookEvent event, SessionEndCause cause)
            implements Event {}

    public record PermissionRequestEvent(HookEvent event, ToolCallData call)
            implements Event {}

    public record NotificationEvent(HookEvent event, DcodeNotification notification)
            implements Event {}

    public record PreToolUseEvent(HookEvent event, ToolCallData call)
            implements Event {}

    public record PostToolUseEvent(HookEvent event, ToolCallData call, Object toolResponse,
                                   int durationMs) implements Event {}

    public record PostToolUseFailureEvent(HookEvent event, ToolCallData call, String error,
                                          boolean isInterrupt, int durationMs) implements Event {}

    public record PreCompactEvent(HookEvent event, CompactTrigger trigger,
                                   String customInstructions) implements Event {}

    public record StopEvent(HookEvent event, int continuationCount, String lastAssistantMessage,
                            List<BackgroundTask> backgroundTasks,
                            List<SessionCron> sessionCrons) implements Event {
        public StopEvent {
            if (backgroundTasks == null) backgroundTasks = List.of();
            if (sessionCrons == null) sessionCrons = List.of();
        }
    }

    public record SubagentStartEvent(HookEvent event, AgentIdentity agent) implements Event {}

    public record SubagentStopEvent(HookEvent event, AgentIdentity agent, int continuationCount,
                                    String lastAssistantMessage,
                                    List<BackgroundTask> backgroundTasks,
                                    List<SessionCron> sessionCrons) implements Event {
        public SubagentStopEvent {
            if (backgroundTasks == null) backgroundTasks = List.of();
            if (sessionCrons == null) sessionCrons = List.of();
        }
    }

    // -- Decisions -----------------------------------------------------

    public record SessionStartDecision(HookEvent event, BaseFields base, List<String> context)
            implements Decision {
        public SessionStartDecision {
            if (context == null) context = List.of();
            else context = List.copyOf(context);
        }
    }

    public record UserPromptSubmitDecision(HookEvent event, BaseFields base, List<String> context,
                                           boolean suppressOriginalPrompt) implements Decision {
        public UserPromptSubmitDecision {
            if (context == null) context = List.of();
            else context = List.copyOf(context);
        }
    }

    public record SessionEndDecision(HookEvent event, BaseFields base) implements Decision {}

    public record PermissionRequestDecision(HookEvent event, BaseFields base,
                                            PermissionEffect permission) implements Decision {}

    public record NotificationDecision(HookEvent event, BaseFields base) implements Decision {}

    public record PreToolUseDecision(HookEvent event, BaseFields base, PermissionEffect permission,
                                     List<String> context) implements Decision {
        public PreToolUseDecision {
            if (context == null) context = List.of();
            else context = List.copyOf(context);
        }
    }

    public record PostToolUseDecision(HookEvent event, BaseFields base, List<String> feedback,
                                      List<String> context) implements Decision {
        public PostToolUseDecision {
            if (feedback == null) feedback = List.of();
            else feedback = List.copyOf(feedback);
            if (context == null) context = List.of();
            else context = List.copyOf(context);
        }
    }

    public record PostToolUseFailureDecision(HookEvent event, BaseFields base,
                                             List<String> feedback,
                                             List<String> context) implements Decision {
        public PostToolUseFailureDecision {
            if (feedback == null) feedback = List.of();
            else feedback = List.copyOf(feedback);
            if (context == null) context = List.of();
            else context = List.copyOf(context);
        }
    }

    public record PreCompactDecision(HookEvent event, BaseFields base) implements Decision {}

    public record StopDecision(HookEvent event, BaseFields base, boolean continueLoop,
                               List<String> feedback) implements Decision {
        public StopDecision {
            if (feedback == null) feedback = List.of();
            else feedback = List.copyOf(feedback);
        }
    }

    public record SubagentStartDecision(HookEvent event, BaseFields base,
                                        List<String> context) implements Decision {
        public SubagentStartDecision {
            if (context == null) context = List.of();
            else context = List.copyOf(context);
        }
    }

    public record SubagentStopDecision(HookEvent event, BaseFields base,
                                       List<String> context) implements Decision {
        public SubagentStopDecision {
            if (context == null) context = List.of();
            else context = List.copyOf(context);
        }
    }

    // -- Convenience accessors for the common fields -------------------

    /** Return the {@link BaseFields} carried by a decision, or null. */
    public static BaseFields baseOf(Decision d) {
        if (d == null) return null;
        return switch (d) {
            case SessionStartDecision v -> v.base();
            case UserPromptSubmitDecision v -> v.base();
            case SessionEndDecision v -> v.base();
            case PermissionRequestDecision v -> v.base();
            case NotificationDecision v -> v.base();
            case PreToolUseDecision v -> v.base();
            case PostToolUseDecision v -> v.base();
            case PostToolUseFailureDecision v -> v.base();
            case PreCompactDecision v -> v.base();
            case StopDecision v -> v.base();
            case SubagentStartDecision v -> v.base();
            case SubagentStopDecision v -> v.base();
        };
    }

    /** Empty payload used as a placeholder when no contextual data applies. */
    public static Map<String, Object> emptyArgs() {
        return Map.of();
    }
}
