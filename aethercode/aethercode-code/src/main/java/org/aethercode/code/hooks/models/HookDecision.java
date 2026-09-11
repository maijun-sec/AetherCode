package org.aethercode.code.hooks.models;

import java.util.List;

/**
 * Sealed union of every hook decision payload.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.domain.HookDecision}
 * discriminated-union type.</p>
 */
public sealed interface HookDecision
        permits HookDecision.SessionStartDecision,
                HookDecision.UserPromptSubmitDecision,
                HookDecision.SessionEndDecision,
                HookDecision.PermissionRequestDecision,
                HookDecision.NotificationDecision,
                HookDecision.PreToolUseDecision,
                HookDecision.PostToolUseDecision,
                HookDecision.PostToolUseFailureDecision,
                HookDecision.PreCompactDecision,
                HookDecision.StopDecision,
                HookDecision.SubagentStartDecision,
                HookDecision.SubagentStopDecision {

    HookEvent event();
    boolean continueProcessing();
    String stopReason();
    List<String> userNotices();
    List<String> terminalSequences();
    List<HookDiagnostic> diagnostics();

    record SessionStartDecision(
            boolean continueProcessing,
            String stopReason,
            List<String> userNotices,
            List<String> terminalSequences,
            List<HookDiagnostic> diagnostics,
            List<String> context) implements HookDecision {
        public SessionStartDecision {
            context = context == null ? List.of() : List.copyOf(context);
        }
        @Override public HookEvent event() { return HookEvent.SESSION_START; }
    }

    record UserPromptSubmitDecision(
            boolean continueProcessing,
            String stopReason,
            List<String> userNotices,
            List<String> terminalSequences,
            List<HookDiagnostic> diagnostics,
            List<String> context,
            boolean suppressOriginalPrompt) implements HookDecision {
        public UserPromptSubmitDecision {
            context = context == null ? List.of() : List.copyOf(context);
        }
        @Override public HookEvent event() { return HookEvent.USER_PROMPT_SUBMIT; }
    }

    record SessionEndDecision(
            boolean continueProcessing,
            String stopReason,
            List<String> userNotices,
            List<String> terminalSequences,
            List<HookDiagnostic> diagnostics) implements HookDecision {
        @Override public HookEvent event() { return HookEvent.SESSION_END; }
    }

    record PermissionRequestDecision(
            boolean continueProcessing,
            String stopReason,
            List<String> userNotices,
            List<String> terminalSequences,
            List<HookDiagnostic> diagnostics,
            PermissionEffect permission) implements HookDecision {
        @Override public HookEvent event() { return HookEvent.PERMISSION_REQUEST; }
    }

    record NotificationDecision(
            boolean continueProcessing,
            String stopReason,
            List<String> userNotices,
            List<String> terminalSequences,
            List<HookDiagnostic> diagnostics) implements HookDecision {
        @Override public HookEvent event() { return HookEvent.NOTIFICATION; }
    }

    record PreToolUseDecision(
            boolean continueProcessing,
            String stopReason,
            List<String> userNotices,
            List<String> terminalSequences,
            List<HookDiagnostic> diagnostics,
            PermissionEffect permission,
            List<String> context) implements HookDecision {
        public PreToolUseDecision {
            context = context == null ? List.of() : List.copyOf(context);
        }
        @Override public HookEvent event() { return HookEvent.PRE_TOOL_USE; }
    }

    record PostToolUseDecision(
            boolean continueProcessing,
            String stopReason,
            List<String> userNotices,
            List<String> terminalSequences,
            List<HookDiagnostic> diagnostics,
            List<String> feedback,
            List<String> context) implements HookDecision {
        public PostToolUseDecision {
            feedback = feedback == null ? List.of() : List.copyOf(feedback);
            context = context == null ? List.of() : List.copyOf(context);
        }
        @Override public HookEvent event() { return HookEvent.POST_TOOL_USE; }
    }

    record PostToolUseFailureDecision(
            boolean continueProcessing,
            String stopReason,
            List<String> userNotices,
            List<String> terminalSequences,
            List<HookDiagnostic> diagnostics,
            List<String> feedback,
            List<String> context) implements HookDecision {
        public PostToolUseFailureDecision {
            feedback = feedback == null ? List.of() : List.copyOf(feedback);
            context = context == null ? List.of() : List.copyOf(context);
        }
        @Override public HookEvent event() { return HookEvent.POST_TOOL_USE_FAILURE; }
    }

    record PreCompactDecision(
            boolean continueProcessing,
            String stopReason,
            List<String> userNotices,
            List<String> terminalSequences,
            List<HookDiagnostic> diagnostics) implements HookDecision {
        @Override public HookEvent event() { return HookEvent.PRE_COMPACT; }
    }

    record StopDecision(
            boolean continueProcessing,
            String stopReason,
            List<String> userNotices,
            List<String> terminalSequences,
            List<HookDiagnostic> diagnostics,
            boolean continueLoop,
            List<String> feedback) implements HookDecision {
        public StopDecision {
            feedback = feedback == null ? List.of() : List.copyOf(feedback);
        }
        @Override public HookEvent event() { return HookEvent.STOP; }
    }

    record SubagentStartDecision(
            boolean continueProcessing,
            String stopReason,
            List<String> userNotices,
            List<String> terminalSequences,
            List<HookDiagnostic> diagnostics,
            List<String> context) implements HookDecision {
        public SubagentStartDecision {
            context = context == null ? List.of() : List.copyOf(context);
        }
        @Override public HookEvent event() { return HookEvent.SUBAGENT_START; }
    }

    record SubagentStopDecision(
            boolean continueProcessing,
            String stopReason,
            List<String> userNotices,
            List<String> terminalSequences,
            List<HookDiagnostic> diagnostics,
            List<String> context) implements HookDecision {
        public SubagentStopDecision {
            context = context == null ? List.of() : List.copyOf(context);
        }
        @Override public HookEvent event() { return HookEvent.SUBAGENT_STOP; }
    }
}
