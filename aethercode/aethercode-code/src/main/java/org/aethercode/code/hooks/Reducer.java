package org.aethercode.code.hooks;

import org.aethercode.code.hooks.HookDomainEvents.BaseFields;
import org.aethercode.code.hooks.HookDomainEvents.Decision;
import org.aethercode.code.hooks.HookDomainEvents.Event;
import org.aethercode.code.hooks.HookDomainEvents.NotificationDecision;
import org.aethercode.code.hooks.HookDomainEvents.PermissionRequestDecision;
import org.aethercode.code.hooks.HookDomainEvents.PostToolUseDecision;
import org.aethercode.code.hooks.HookDomainEvents.PostToolUseFailureDecision;
import org.aethercode.code.hooks.HookDomainEvents.PreCompactDecision;
import org.aethercode.code.hooks.HookDomainEvents.PreToolUseDecision;
import org.aethercode.code.hooks.HookDomainEvents.SessionEndDecision;
import org.aethercode.code.hooks.HookDomainEvents.SessionStartDecision;
import org.aethercode.code.hooks.HookDomainEvents.StopDecision;
import org.aethercode.code.hooks.HookDomainEvents.SubagentStartDecision;
import org.aethercode.code.hooks.HookDomainEvents.SubagentStopDecision;
import org.aethercode.code.hooks.HookDomainEvents.UserPromptSubmitDecision;
import org.aethercode.code.hooks.WireTypes.HookSpecificOutput;
import org.aethercode.code.hooks.WireTypes.HookWireOutput;
import org.aethercode.code.hooks.WireTypes.PermissionAllow;
import org.aethercode.code.hooks.WireTypes.PermissionDecision;
import org.aethercode.code.hooks.WireTypes.PermissionRequestSpecificOutput;
import org.aethercode.code.hooks.WireTypes.PermissionDeny;
import org.aethercode.code.hooks.WireTypes.PostToolUseFailureSpecificOutput;
import org.aethercode.code.hooks.WireTypes.PostToolUseSpecificOutput;
import org.aethercode.code.hooks.WireTypes.PreToolUseSpecificOutput;
import org.aethercode.code.hooks.WireTypes.SessionStartSpecificOutput;
import org.aethercode.code.hooks.WireTypes.StopSpecificOutput;
import org.aethercode.code.hooks.WireTypes.SubagentStartSpecificOutput;
import org.aethercode.code.hooks.WireTypes.SubagentStopSpecificOutput;
import org.aethercode.code.hooks.WireTypes.UserPromptSubmitSpecificOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Event-aware reduction for Hooks v2 command output.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.reducer} module. The reducer walks
 * ordered handler results, merges their side effects, and produces an
 * event-specific {@link Decision}.</p>
 */
public final class Reducer {

    private static final Logger LOG = LoggerFactory.getLogger(Reducer.class);

    /** Maximum number of times a {@code Stop} hook may continue the loop. */
    public static final int MAX_STOP_CONTINUATIONS = 8;

    private static final Map<PermissionEffect.Behavior, Integer> PERMISSION_RANK = new EnumMap<>(
            PermissionEffect.Behavior.class);
    static {
        PERMISSION_RANK.put(PermissionEffect.Behavior.NONE, 0);
        PERMISSION_RANK.put(PermissionEffect.Behavior.ALLOW, 1);
        PERMISSION_RANK.put(PermissionEffect.Behavior.ASK, 2);
        PERMISSION_RANK.put(PermissionEffect.Behavior.DENY, 3);
    }

    /** Mutable reduction state. */
    public static final class State {
        public boolean continueProcessing = true;
        public String stopReason;
        public final List<String> userNotices = new ArrayList<>();
        public final List<String> terminalSequences = new ArrayList<>();
        public final List<HookDiagnostic> diagnostics = new ArrayList<>();
        public final List<String> context = new ArrayList<>();
        public final List<String> feedback = new ArrayList<>();
        public PermissionEffect permission = PermissionEffect.of(PermissionEffect.Behavior.NONE);
        public boolean continueLoop;
        public boolean suppressOriginalPrompt;
    }

    /**
     * Reduce ordered handler results into an event-specific decision.
     */
    public Decision reduce(HookInvocation invocation, List<HookEnvelopeAdapter.HandlerResult> results,
                           List<HookDiagnostic> initialDiagnostics) {
        State state = new State();
        if (initialDiagnostics != null) state.diagnostics.addAll(initialDiagnostics);
        Capabilities.HookEventSpec spec = Capabilities.getEventSpec(invocation.event().event());
        Capabilities.PlainOutputPolicy plainPolicy = spec.plainOutputPolicy();
        if (results != null) {
            for (HookEnvelopeAdapter.HandlerResult result : results) {
                if (result.diagnostics() != null) state.diagnostics.addAll(result.diagnostics());
                if (result.plainOutput() != null) {
                    if (plainPolicy == Capabilities.PlainOutputPolicy.CONTEXT) {
                        state.context.add(result.plainOutput());
                    } else {
                        state.diagnostics.add(new HookDiagnostic(
                                "malformed_json",
                                HookDiagnostic.Severity.WARNING,
                                "Hook output is not valid JSON",
                                result.handlerId(),
                                null));
                    }
                }
                if (result.output() != null) {
                    mergeOutput(invocation, state, result.handlerId(), result.output());
                }
            }
        }
        return decision(invocation, state);
    }

    private void mergeOutput(HookInvocation invocation, State state, String handlerId,
                             HookWireOutput output) {
        state.continueProcessing = state.continueProcessing && output.continue_();
        if (output.stopReason() != null) {
            if (output.continue_()) {
                state.diagnostics.add(new HookDiagnostic(
                        "ignored_stop_reason",
                        HookDiagnostic.Severity.WARNING,
                        "stopReason is ignored while continue is true",
                        handlerId, "stopReason"));
            } else if (state.stopReason == null) {
                state.stopReason = output.stopReason();
            } else {
                state.diagnostics.add(new HookDiagnostic(
                        "additional_stop_reason",
                        HookDiagnostic.Severity.WARNING,
                        "A later stopReason was ignored; the first reason wins",
                        handlerId, "stopReason"));
            }
        }
        if (output.systemMessage() != null && !output.suppressOutput()) {
            state.userNotices.add(output.systemMessage());
        }
        if (output.terminalSequence() != null) {
            String validated = ValidateTerminalSequence.validate(output.terminalSequence());
            if (validated == null) {
                state.diagnostics.add(new HookDiagnostic(
                        "invalid_terminal_sequence",
                        HookDiagnostic.Severity.WARNING,
                        "terminalSequence rejected; only OSC 0/1/2/9/99/777 and BEL are allowed",
                        handlerId, "terminalSequence"));
            } else {
                state.terminalSequences.add(validated);
            }
        }
        if ("block".equals(output.decision())) {
            mergeBlock(invocation, state, handlerId, output.reason());
        }
        HookSpecificOutput specific = output.hookSpecificOutput();
        if (specific == null) return;
        if (!specific.hookEventName().equals(invocation.event().event().wireName())) {
            state.diagnostics.add(new HookDiagnostic(
                    "mismatched_output",
                    HookDiagnostic.Severity.WARNING,
                    "Hook-specific output does not match the invoked event",
                    handlerId, "hookSpecificOutput.hookEventName"));
            return;
        }
        mergeSpecific(specific, invocation, state, handlerId);
    }

    private void mergeBlock(HookInvocation invocation, State state, String handlerId, String reason) {
        String message = reason == null || reason.isEmpty() ? "Blocked by hook" : reason;
        HookEvent event = invocation.event().event();
        Capabilities.ExitCodePolicy policy = Capabilities.getEventSpec(event).exitCodePolicy();
        switch (policy) {
            case BLOCK -> {
                state.continueProcessing = false;
                if (state.stopReason == null) state.stopReason = message;
            }
            case DENY -> mergePermission(state, new PermissionEffect(
                    PermissionEffect.Behavior.DENY, message, false));
            case FEEDBACK -> state.feedback.add(message);
            case CONTINUE_LOOP -> applyStopContinuation(invocation, state, message);
            case IGNORE -> { /* no-op */ }
            case CONTEXT -> {
                if (invocation.event() instanceof HookDomainEvents.SubagentStopEvent s
                        && s.continuationCount() > 0) {
                    state.diagnostics.add(loopGuardDiagnostic());
                    return;
                }
                state.diagnostics.add(new HookDiagnostic(
                        "unsupported_block",
                        HookDiagnostic.Severity.WARNING,
                        "Blocking SubagentStop is not supported yet; retained as parent context: " + message,
                        handlerId, "decision"));
                state.context.add(message);
            }
            default -> state.diagnostics.add(new HookDiagnostic(
                    "unsupported_block",
                    HookDiagnostic.Severity.WARNING,
                    "Block/exit 2 is not supported for " + event.wireName() + ": " + message,
                    handlerId, "decision"));
        }
    }

    private void applyStopContinuation(HookInvocation invocation, State state, String message) {
        if (!(invocation.event() instanceof HookDomainEvents.StopEvent stop)) return;
        if (stop.continuationCount() >= MAX_STOP_CONTINUATIONS) {
            state.diagnostics.add(new HookDiagnostic(
                    "continuation_cap",
                    HookDiagnostic.Severity.WARNING,
                    "Ignored Stop continuation after " + MAX_STOP_CONTINUATIONS
                            + " consecutive attempts",
                    null, null));
            return;
        }
        state.continueLoop = true;
        state.feedback.add(message);
    }

    private static void mergeSpecific(HookSpecificOutput specific, HookInvocation invocation,
                                      State state, String handlerId) {
        if (specific instanceof SessionStartSpecificOutput s) {
            appendIfNotNull(state.context, s.additionalContext());
        } else if (specific instanceof UserPromptSubmitSpecificOutput u) {
            appendIfNotNull(state.context, u.additionalContext());
            state.suppressOriginalPrompt |= u.suppressOriginalPrompt();
        } else if (specific instanceof PreToolUseSpecificOutput p) {
            appendIfNotNull(state.context, p.additionalContext());
            String behavior = p.permissionDecision();
            if ("defer".equals(behavior)) {
                diagnoseUnsupportedField(state, handlerId, "permissionDecision", "defer");
                behavior = null;
            }
            if (p.updatedInput() != null) {
                diagnoseUnsupportedUpdatedInput(state, handlerId);
                if ("allow".equals(behavior) || "ask".equals(behavior)) behavior = null;
            }
            if (behavior != null) {
                PermissionEffect.Behavior b;
                try {
                    b = PermissionEffect.Behavior.valueOf(behavior.toUpperCase());
                } catch (IllegalArgumentException ex) {
                    b = null;
                }
                if (b != null) {
                    mergePermission(state, new PermissionEffect(b,
                            p.permissionDecisionReason(), false));
                }
            }
        } else if (specific instanceof PermissionRequestSpecificOutput pr) {
            PermissionDecision decision = pr.decision();
            if (decision instanceof PermissionAllow allow) {
                if (allow.updatedInput()) {
                    diagnoseUnsupportedUpdatedInput(state, handlerId);
                }
                if (pr.updatedPermissions()) {
                    diagnoseUnsupportedField(state, handlerId, "updatedPermissions", null);
                }
                mergePermission(state, PermissionEffect.of(PermissionEffect.Behavior.ALLOW));
            } else if (decision instanceof PermissionDeny deny) {
                mergePermission(state, new PermissionEffect(
                        PermissionEffect.Behavior.DENY, deny.message(), deny.interrupt()));
            } else {
                mergePermission(state, new PermissionEffect(
                        PermissionEffect.Behavior.DENY, null, false));
            }
        } else if (specific instanceof PostToolUseSpecificOutput po) {
            appendIfNotNull(state.context, po.additionalContext());
        } else if (specific instanceof PostToolUseFailureSpecificOutput pf) {
            appendIfNotNull(state.context, pf.additionalContext());
        } else if (specific instanceof StopSpecificOutput s) {
            if (s.additionalContext() != null) {
                applyStopContinuationStatic0(invocation, state, s.additionalContext());
            }
        } else if (specific instanceof SubagentStartSpecificOutput s) {
            appendIfNotNull(state.context, s.additionalContext());
        } else if (specific instanceof SubagentStopSpecificOutput s) {
            appendIfNotNull(state.context, s.additionalContext());
        }
    }

    /** Static helper that delegates to the instance method. */
    private static void applyStopContinuationStatic0(HookInvocation invocation, State state, String message) {
        Reducer.INSTANCE.applyStopContinuationInstance(invocation, state, message);
    }

    /** Singleton holder used by the static helper to reach instance state. */
    private static final Reducer INSTANCE = new Reducer();

    /** Instance helper. Made package-private so the static wrapper can call it. */
    void applyStopContinuationInstance(HookInvocation invocation, State state, String message) {
        applyStopContinuation(invocation, state, message);
    }

    private static void mergePermission(State state, PermissionEffect effect) {
        Integer current = PERMISSION_RANK.get(state.permission.behavior());
        Integer next = PERMISSION_RANK.get(effect.behavior());
        if (current == null || next == null) return;
        if (next > current) state.permission = effect;
    }

    private static void appendIfNotNull(List<String> values, String value) {
        if (value != null) values.add(value);
    }

    private static void diagnoseUnsupportedField(State state, String handlerId, String field,
                                                String value) {
        String subject = value == null ? field : field + " value '" + value + "'";
        state.diagnostics.add(new HookDiagnostic(
                "unsupported_field",
                HookDiagnostic.Severity.WARNING,
                subject + " is not supported and was ignored",
                handlerId, field));
    }

    private static void diagnoseUnsupportedUpdatedInput(State state, String handlerId) {
        state.diagnostics.add(new HookDiagnostic(
                "unsupported_field",
                HookDiagnostic.Severity.WARNING,
                "updatedInput is not supported; the mutated tool input was ignored",
                handlerId, "updatedInput"));
    }

    private static HookDiagnostic loopGuardDiagnostic() {
        return new HookDiagnostic(
                "continuation_guard",
                HookDiagnostic.Severity.WARNING,
                "Ignored recursive stop-hook continuation",
                null, null);
    }

    private Decision decision(HookInvocation invocation, State state) {
        BaseFields base = new BaseFields(state.continueProcessing, state.stopReason,
                state.userNotices, state.terminalSequences, state.diagnostics);
        HookEvent event = invocation.event().event();
        return switch (event) {
            case SESSION_START -> new SessionStartDecision(event, base, List.copyOf(state.context));
            case USER_PROMPT_SUBMIT -> new UserPromptSubmitDecision(event, base,
                    List.copyOf(state.context), state.suppressOriginalPrompt);
            case SESSION_END -> new SessionEndDecision(event, base);
            case PERMISSION_REQUEST -> new PermissionRequestDecision(event, base, state.permission);
            case NOTIFICATION -> new NotificationDecision(event, base);
            case PRE_TOOL_USE -> new PreToolUseDecision(event, base, state.permission,
                    List.copyOf(state.context));
            case POST_TOOL_USE -> new PostToolUseDecision(event, base, List.copyOf(state.feedback),
                    List.copyOf(state.context));
            case POST_TOOL_USE_FAILURE -> new PostToolUseFailureDecision(event, base,
                    List.copyOf(state.feedback), List.copyOf(state.context));
            case PRE_COMPACT -> new PreCompactDecision(event, base);
            case STOP -> new StopDecision(event, base, state.continueLoop, List.copyOf(state.feedback));
            case SUBAGENT_START -> new SubagentStartDecision(event, base, List.copyOf(state.context));
            case SUBAGENT_STOP -> new SubagentStopDecision(event, base, List.copyOf(state.context));
        };
    }
}
