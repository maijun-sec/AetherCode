/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
