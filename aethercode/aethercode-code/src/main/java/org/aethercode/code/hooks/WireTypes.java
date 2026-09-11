package org.aethercode.code.hooks;

import java.util.List;
import java.util.Map;

/**
 * Wire (Claude-compatible) projections and shared transport enums.
 *
 * <p>Java-native port of the {@code deepagents_code.hooks.models.wire}
 * module. The wire types mirror the upstream JSON shape; the
 * {@code *Input} classes share a small set of common fields defined by
 * {@link BaseWireFields}.</p>
 */
public final class WireTypes {

    private WireTypes() {}

    /** Permission mode the transport advertises. */
    public enum WirePermissionMode {
        DEFAULT,
        ACCEPT_EDITS,
        AUTO,
        BYPASS_PERMISSIONS,
        PLAN
    }

    /** Notification matcher name the wire recognizes. */
    public enum WireNotificationType {
        PERMISSION_PROMPT,
        AGENT_NEEDS_INPUT,
        AGENT_COMPLETED,
        COLD_CACHE_WARNING;

        /** Lower-case wire name (e.g. {@code permission_prompt}). */
        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Optional effort hint for the model. */
    public record Effort(String level) {}

    /** Background task descriptor on the wire. */
    public record BackgroundTaskWire(String taskId, String status) {}

    /** Cron descriptor on the wire. */
    public record SessionCronWire(String id, String expression) {}

    /** Common carrier for the shared fields every wire input carries. */
    public record BaseWireFields(
            String sessionId,
            String transcriptPath,
            String cwd,
            WirePermissionMode permissionMode,
            String promptId,
            Effort effort,
            String agentId,
            String agentType) {}

    // -- Specific wire inputs -----------------------------------------

    public record SessionStartWireInput(
            BaseWireFields base, String hookEventName, SessionStartCause source, String model) {}

    public record UserPromptSubmitWireInput(
            BaseWireFields base, String hookEventName, String prompt) {}

    public record SessionEndWireInput(
            BaseWireFields base, String hookEventName, SessionEndCause reason) {}

    public record PermissionRequestWireInput(
            BaseWireFields base, String hookEventName,
            String toolName, Map<String, Object> toolInput) {}

    public record NotificationWireInput(
            BaseWireFields base, String hookEventName,
            String message, String title, WireNotificationType notificationType) {}

    public record PreToolUseWireInput(
            BaseWireFields base, String hookEventName,
            String toolName, Map<String, Object> toolInput, String toolUseId) {}

    public record PostToolUseWireInput(
            BaseWireFields base, String hookEventName,
            String toolName, Map<String, Object> toolInput,
            Object toolResponse, String toolUseId, int durationMs) {}

    public record PostToolUseFailureWireInput(
            BaseWireFields base, String hookEventName,
            String toolName, Map<String, Object> toolInput,
            String toolUseId, String error, boolean isInterrupt, int durationMs) {}

    public record PreCompactWireInput(
            BaseWireFields base, String hookEventName,
            CompactTrigger trigger, String customInstructions) {}

    public record StopWireInput(
            BaseWireFields base, String hookEventName,
            boolean stopHookActive, String lastAssistantMessage,
            List<BackgroundTaskWire> backgroundTasks,
            List<SessionCronWire> sessionCrons) {
        public StopWireInput {
            if (backgroundTasks == null) backgroundTasks = List.of();
            if (sessionCrons == null) sessionCrons = List.of();
        }
    }

    public record SubagentStartWireInput(
            BaseWireFields base, String hookEventName) {}

    public record SubagentStopWireInput(
            BaseWireFields base, String hookEventName,
            boolean stopHookActive, String agentTranscriptPath,
            String lastAssistantMessage,
            List<BackgroundTaskWire> backgroundTasks,
            List<SessionCronWire> sessionCrons) {
        public SubagentStopWireInput {
            if (backgroundTasks == null) backgroundTasks = List.of();
            if (sessionCrons == null) sessionCrons = List.of();
        }
    }

    // -- Wire outputs --------------------------------------------------
    // Permitted outputs are declared first so the sealed interfaces below
    // can see them. Each record explicitly `implements` its sealed parent.

    public record SessionStartSpecificOutput(String hookEventName, String additionalContext)
            implements HookSpecificOutput {}

    public record UserPromptSubmitSpecificOutput(String hookEventName, String additionalContext,
                                                 boolean suppressOriginalPrompt, String sessionTitle)
            implements HookSpecificOutput {}

    public record PreToolUseSpecificOutput(String hookEventName, String additionalContext,
                                           String permissionDecision,
                                           String permissionDecisionReason,
                                           Map<String, Object> updatedInput)
            implements HookSpecificOutput {}

    public record PermissionRequestSpecificOutput(String hookEventName,
                                                 PermissionDecision decision,
                                                 boolean updatedInput,
                                                 boolean updatedPermissions)
            implements HookSpecificOutput {}

    public record PostToolUseSpecificOutput(String hookEventName, String additionalContext,
                                            Object updatedToolOutput,
                                            Object updatedMcpToolOutput)
            implements HookSpecificOutput {}

    public record PostToolUseFailureSpecificOutput(String hookEventName,
                                                   String additionalContext)
            implements HookSpecificOutput {}

    public record StopSpecificOutput(String hookEventName, String additionalContext,
                                     boolean continueLoop)
            implements HookSpecificOutput {}

    public record SubagentStartSpecificOutput(String hookEventName, String additionalContext)
            implements HookSpecificOutput {}

    public record SubagentStopSpecificOutput(String hookEventName, String additionalContext)
            implements HookSpecificOutput {}

    public record PermissionAllow(boolean updatedInput) implements PermissionDecision {}
    public record PermissionDeny(String message, boolean interrupt) implements PermissionDecision {}

    public sealed interface HookSpecificOutput
            permits SessionStartSpecificOutput, UserPromptSubmitSpecificOutput,
                    PreToolUseSpecificOutput, PermissionRequestSpecificOutput,
                    PostToolUseSpecificOutput, PostToolUseFailureSpecificOutput,
                    StopSpecificOutput, SubagentStartSpecificOutput,
                    SubagentStopSpecificOutput {
        String hookEventName();
    }

    public sealed interface PermissionDecision
            permits PermissionAllow, PermissionDeny {}

    /** Standard wire output envelope. */
    public record HookWireOutput(
            boolean continue_,
            String stopReason,
            String systemMessage,
            String terminalSequence,
            boolean suppressOutput,
            String decision,
            String reason,
            HookSpecificOutput hookSpecificOutput) {

        public HookWireOutput {
            // JSON compatibility: the field is named `continue` in Python.
            // Java keyword handling is done at the adapter layer.
        }

        public boolean isContinue() {
            return continue_;
        }
    }
}
