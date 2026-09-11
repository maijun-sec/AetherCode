package org.aethercode.code.hooks.models;

import java.util.List;
import java.util.Map;

/**
 * Sealed union of every domain hook event payload.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.domain.HookDomainEvent}
 * discriminated-union type. Implementations match the Python sealed
 * hierarchy one-for-one.</p>
 */
public sealed interface HookDomainEvent
        permits HookDomainEvent.SessionStart,
                HookDomainEvent.UserPromptSubmit,
                HookDomainEvent.SessionEnd,
                HookDomainEvent.PermissionRequest,
                HookDomainEvent.Notification,
                HookDomainEvent.PreToolUse,
                HookDomainEvent.PostToolUse,
                HookDomainEvent.PostToolUseFailure,
                HookDomainEvent.PreCompact,
                HookDomainEvent.Stop,
                HookDomainEvent.SubagentStart,
                HookDomainEvent.SubagentStop {

    HookEvent event();

    record SessionStart(SessionStartCause cause, String model) implements HookDomainEvent {
        @Override public HookEvent event() { return HookEvent.SESSION_START; }
    }

    record UserPromptSubmit(String prompt) implements HookDomainEvent {
        @Override public HookEvent event() { return HookEvent.USER_PROMPT_SUBMIT; }
    }

    record SessionEnd(SessionEndCause cause) implements HookDomainEvent {
        @Override public HookEvent event() { return HookEvent.SESSION_END; }
    }

    record PermissionRequest(ToolCallData call) implements HookDomainEvent {
        @Override public HookEvent event() { return HookEvent.PERMISSION_REQUEST; }
    }

    record Notification(String type, String message, String title) implements HookDomainEvent {
        @Override public HookEvent event() { return HookEvent.NOTIFICATION; }
    }

    record PreToolUse(ToolCallData call) implements HookDomainEvent {
        @Override public HookEvent event() { return HookEvent.PRE_TOOL_USE; }
    }

    record PostToolUse(ToolCallData call, Object result, Integer durationMs)
            implements HookDomainEvent {
        @Override public HookEvent event() { return HookEvent.POST_TOOL_USE; }
    }

    record PostToolUseFailure(ToolCallData call, String error, boolean isInterrupt,
                              Integer durationMs) implements HookDomainEvent {
        @Override public HookEvent event() { return HookEvent.POST_TOOL_USE_FAILURE; }
    }

    record PreCompact(CompactTrigger trigger, String customInstructions)
            implements HookDomainEvent {
        @Override public HookEvent event() { return HookEvent.PRE_COMPACT; }
    }

    record Stop(int continuationCount, String lastAssistantMessage,
                List<BackgroundTaskSnapshot> backgroundTasks,
                List<SessionCronSnapshot> sessionCrons) implements HookDomainEvent {
        @Override public HookEvent event() { return HookEvent.STOP; }
    }

    record SubagentStart(AgentIdentity agent) implements HookDomainEvent {
        @Override public HookEvent event() { return HookEvent.SUBAGENT_START; }
    }

    record SubagentStop(AgentIdentity agent, int continuationCount,
                        String lastAssistantMessage, String transcriptRevision,
                        List<BackgroundTaskSnapshot> backgroundTasks,
                        List<SessionCronSnapshot> sessionCrons) implements HookDomainEvent {
        @Override public HookEvent event() { return HookEvent.SUBAGENT_STOP; }
    }

    /**
     * Background task snapshot, compatible with the Stop/SubagentStop
     * wire context. Omit or leave empty until a trustworthy source
     * exists.
     */
    record BackgroundTaskSnapshot(
            String id,
            String type,
            String status,
            String description,
            String command,
            String agentType,
            String server,
            String tool,
            String name) {
    }

    /**
     * Scheduled session prompt snapshot, compatible with the
     * Stop/SubagentStop wire context.
     */
    record SessionCronSnapshot(
            String id,
            String schedule,
            boolean recurring,
            String prompt) {
    }
}
