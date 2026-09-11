package org.aethercode.code.hooks.models;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * External JSON-compatible hook input and output models.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.wire} module. Provides the
 * wire-format records used to communicate with external handlers.</p>
 */
public final class WireModels {
    private WireModels() {}

    /** Wire permission modes exposed by the compatible wire protocol. */
    public enum WirePermissionMode {
        DEFAULT("default"),
        PLAN("plan"),
        ACCEPT_EDITS("acceptEdits"),
        AUTO("auto"),
        DONT_ASK("dontAsk"),
        BYPASS_PERMISSIONS("bypassPermissions");

        private final String wire;
        WirePermissionMode(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    /** Notification matcher values exposed on the wire. */
    public enum WireNotificationType {
        PERMISSION_PROMPT("permission_prompt"),
        IDLE_PROMPT("idle_prompt"),
        AUTH_SUCCESS("auth_success"),
        ELICITATION_DIALOG("elicitation_dialog"),
        ELICITATION_COMPLETE("elicitation_complete"),
        ELICITATION_RESPONSE("elicitation_response"),
        AGENT_NEEDS_INPUT("agent_needs_input"),
        AGENT_COMPLETED("agent_completed"),
        COLD_CACHE_WARNING("cold_cache_warning");

        private final String wire;
        WireNotificationType(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    /** Permission-update destination scope. */
    public enum PermissionDestination {
        SESSION("session"),
        LOCAL("localSettings"),
        PROJECT("projectSettings"),
        USER("userSettings");

        private final String wire;
        PermissionDestination(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    /** Wire representation of model effort. */
    public record Effort(String level) {}

    /** Permission rule returned by a hook. */
    public record PermissionRule(String toolName, String ruleContent) {}

    /** Permission update that adds rules. */
    public record AddRulesUpdate(String type, List<PermissionRule> rules, String behavior,
                                  PermissionDestination destination) {
        public AddRulesUpdate {
            type = "addRules";
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    /** Permission update that replaces rules. */
    public record ReplaceRulesUpdate(String type, List<PermissionRule> rules, String behavior,
                                      PermissionDestination destination) {
        public ReplaceRulesUpdate {
            type = "replaceRules";
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    /** Permission update that removes rules. */
    public record RemoveRulesUpdate(String type, List<PermissionRule> rules, String behavior,
                                     PermissionDestination destination) {
        public RemoveRulesUpdate {
            type = "removeRules";
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    /** Permission update that changes the active mode. */
    public record SetModeUpdate(String type, String mode, PermissionDestination destination) {
        public SetModeUpdate { type = "setMode"; }
    }

    /** Permission update that adds allowed directories. */
    public record AddDirectoriesUpdate(String type, List<String> directories,
                                        PermissionDestination destination) {
        public AddDirectoriesUpdate {
            type = "addDirectories";
            directories = directories == null ? List.of() : List.copyOf(directories);
        }
    }

    /** Permission update that removes allowed directories. */
    public record RemoveDirectoriesUpdate(String type, List<String> directories,
                                           PermissionDestination destination) {
        public RemoveDirectoriesUpdate {
            type = "removeDirectories";
            directories = directories == null ? List.of() : List.copyOf(directories);
        }
    }

    /** Background task snapshot exposed to hook handlers. */
    public record BackgroundTaskWire(String id, String type, String status, String description,
                                      String command, String agentType, String server,
                                      String tool, String name) {}

    /** Scheduled session prompt exposed to hook handlers. */
    public record SessionCronWire(String id, String schedule, boolean recurring, String prompt) {}

    /** Base fields common to every hook input. */
    public record BaseHookWireInput(
            String sessionId,
            String transcriptPath,
            String cwd,
            HookEvent hookEventName,
            UUID promptId,
            WirePermissionMode permissionMode,
            Effort effort,
            String agentId,
            String agentType) {}

    /** Wire input for {@code SessionStart}. */
    public record SessionStartWireInput(
            String sessionId, String transcriptPath, String cwd,
            SessionStartCause source, String model, String sessionTitle)
            implements HookWireInputMarker {
        @Override public HookEvent hookEventName() { return HookEvent.SESSION_START; }
    }

    /** Wire input for {@code UserPromptSubmit}. */
    public record UserPromptSubmitWireInput(
            String sessionId, String transcriptPath, String cwd, String prompt)
            implements HookWireInputMarker {
        @Override public HookEvent hookEventName() { return HookEvent.USER_PROMPT_SUBMIT; }
    }

    /** Wire input for {@code SessionEnd}. */
    public record SessionEndWireInput(
            String sessionId, String transcriptPath, String cwd, SessionEndCause reason)
            implements HookWireInputMarker {
        @Override public HookEvent hookEventName() { return HookEvent.SESSION_END; }
    }

    /** Wire input for {@code PermissionRequest}. */
    public record PermissionRequestWireInput(
            String sessionId, String transcriptPath, String cwd,
            String toolName, Map<String, Object> toolInput, List<AddRulesUpdate> permissionSuggestions)
            implements HookWireInputMarker {
        public PermissionRequestWireInput {
            toolInput = toolInput == null ? Map.of() : Map.copyOf(toolInput);
            permissionSuggestions = permissionSuggestions == null
                    ? List.of() : List.copyOf(permissionSuggestions);
        }
        @Override public HookEvent hookEventName() { return HookEvent.PERMISSION_REQUEST; }
    }

    /** Wire input for {@code Notification}. */
    public record NotificationWireInput(
            String sessionId, String transcriptPath, String cwd,
            String message, WireNotificationType notificationType, String title)
            implements HookWireInputMarker {
        @Override public HookEvent hookEventName() { return HookEvent.NOTIFICATION; }
    }

    /** Wire input for {@code PreToolUse}. */
    public record PreToolUseWireInput(
            String sessionId, String transcriptPath, String cwd,
            String toolName, Map<String, Object> toolInput, String toolUseId)
            implements HookWireInputMarker {
        public PreToolUseWireInput {
            toolInput = toolInput == null ? Map.of() : Map.copyOf(toolInput);
        }
        @Override public HookEvent hookEventName() { return HookEvent.PRE_TOOL_USE; }
    }

    /** Wire input for {@code PostToolUse}. */
    public record PostToolUseWireInput(
            String sessionId, String transcriptPath, String cwd,
            String toolName, Map<String, Object> toolInput, Object toolResponse,
            String toolUseId, Integer durationMs)
            implements HookWireInputMarker {
        public PostToolUseWireInput {
            toolInput = toolInput == null ? Map.of() : Map.copyOf(toolInput);
        }
        @Override public HookEvent hookEventName() { return HookEvent.POST_TOOL_USE; }
    }

    /** Wire input for {@code PostToolUseFailure}. */
    public record PostToolUseFailureWireInput(
            String sessionId, String transcriptPath, String cwd,
            String toolName, Map<String, Object> toolInput, String toolUseId,
            String error, Boolean isInterrupt, Integer durationMs)
            implements HookWireInputMarker {
        public PostToolUseFailureWireInput {
            toolInput = toolInput == null ? Map.of() : Map.copyOf(toolInput);
        }
        @Override public HookEvent hookEventName() { return HookEvent.POST_TOOL_USE_FAILURE; }
    }

    /** Wire input for {@code PreCompact}. */
    public record PreCompactWireInput(
            String sessionId, String transcriptPath, String cwd,
            CompactTrigger trigger, String customInstructions)
            implements HookWireInputMarker {
        public PreCompactWireInput {
            customInstructions = customInstructions == null ? "" : customInstructions;
        }
        @Override public HookEvent hookEventName() { return HookEvent.PRE_COMPACT; }
    }

    /** Wire input for {@code Stop}. */
    public record StopWireInput(
            String sessionId, String transcriptPath, String cwd,
            boolean stopHookActive, String lastAssistantMessage,
            List<BackgroundTaskWire> backgroundTasks, List<SessionCronWire> sessionCrons)
            implements HookWireInputMarker {
        public StopWireInput {
            backgroundTasks = backgroundTasks == null ? List.of() : List.copyOf(backgroundTasks);
            sessionCrons = sessionCrons == null ? List.of() : List.copyOf(sessionCrons);
        }
        @Override public HookEvent hookEventName() { return HookEvent.STOP; }
    }

    /** Wire input for {@code SubagentStart}. */
    public record SubagentStartWireInput(
            String sessionId, String transcriptPath, String cwd,
            String agentId, String agentType)
            implements HookWireInputMarker {
        @Override public HookEvent hookEventName() { return HookEvent.SUBAGENT_START; }
    }

    /** Wire input for {@code SubagentStop}. */
    public record SubagentStopWireInput(
            String sessionId, String transcriptPath, String cwd,
            boolean stopHookActive, String agentId, String agentType,
            String agentTranscriptPath, String lastAssistantMessage,
            List<BackgroundTaskWire> backgroundTasks, List<SessionCronWire> sessionCrons)
            implements HookWireInputMarker {
        public SubagentStopWireInput {
            backgroundTasks = backgroundTasks == null ? List.of() : List.copyOf(backgroundTasks);
            sessionCrons = sessionCrons == null ? List.of() : List.copyOf(sessionCrons);
        }
        @Override public HookEvent hookEventName() { return HookEvent.SUBAGENT_STOP; }
    }

    /**
     * Marker interface for every {@code *WireInput} record.
     */
    public sealed interface HookWireInputMarker
            permits SessionStartWireInput, UserPromptSubmitWireInput, SessionEndWireInput,
                    PermissionRequestWireInput, NotificationWireInput, PreToolUseWireInput,
                    PostToolUseWireInput, PostToolUseFailureWireInput, PreCompactWireInput,
                    StopWireInput, SubagentStartWireInput, SubagentStopWireInput {
        HookEvent hookEventName();
    }

    /** Compatible hook output with retained extension fields. */
    public record HookWireOutput(
            boolean continueProcessing,
            String stopReason,
            boolean suppressOutput,
            String systemMessage,
            String terminalSequence,
            String decision,
            String reason,
            Object hookSpecificOutput) {
        public HookWireOutput {
            // Default: continue = true. The Python pydantic default is true.
        }
        public static HookWireOutput passThrough() {
            return new HookWireOutput(true, null, false, null, null, null, null, null);
        }
    }
}
