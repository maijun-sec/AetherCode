package org.aethercode.code.hooks;

import org.aethercode.code.hooks.HookDomainEvents.Decision;
import org.aethercode.code.hooks.HookDomainEvents.NotificationDecision;
import org.aethercode.code.hooks.HookDomainEvents.PermissionRequestDecision;
import org.aethercode.code.hooks.HookDomainEvents.PreCompactDecision;
import org.aethercode.code.hooks.HookDomainEvents.SessionEndDecision;
import org.aethercode.code.hooks.HookDomainEvents.SessionStartDecision;
import org.aethercode.code.hooks.HookDomainEvents.UserPromptSubmitDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Client-owned Hooks v2 lifecycle facade.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.client_lifecycle} module. The service
 * runs client-owned events through the runtime and applies their
 * common side effects.</p>
 */
public final class ClientLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ClientLifecycle.class);

    /** Raised when a client-owned hook stops lifecycle processing. */
    public static class ClientHookStopError extends RuntimeException {
        public ClientHookStopError(String message) { super(message); }
    }

    /** Client state required to create a domain hook invocation. */
    public record ClientHookContext(
            String threadId, ApprovalMode approvalMode, UUID promptId) {

        public static ClientHookContext create(
                String threadId, ApprovalMode approvalMode, UUID promptId) {
            if (approvalMode == null) approvalMode = ApprovalMode.MANUAL;
            return new ClientHookContext(threadId, approvalMode, promptId);
        }
    }

    /** Minimal runtime surface the service depends on. */
    public interface ClientHooksRuntime {
        java.nio.file.Path cwd();
        Presenter presenter();
        java.util.Set<HookEvent> configuredEvents();
        CompletionStage<Decision> invoke(HookInvocation invocation);
    }

    /**
     * Execute client-owned events and apply their common side
     * effects.
     */
    public static final class ClientHookService {
        private final ClientHooksRuntime runtime;
        private final Map<String, List<String>> pendingContext = new LinkedHashMap<>();

        public ClientHookService(ClientHooksRuntime runtime) {
            this.runtime = runtime;
        }

        public CompletionStage<SessionStartDecision> sessionStart(
                ClientHookContext context, SessionStartCause cause, String model) {
            if (!hasHandlers(HookEvent.SESSION_START)) {
                return CompletableFuture.completedFuture(emptyDecision(HookEvent.SESSION_START,
                        SessionStartDecision.class));
            }
            return invoke(context,
                    new HookDomainEvents.SessionStartEvent(HookEvent.SESSION_START, cause, model))
                    .thenApply(decision -> {
                        if (!(decision instanceof SessionStartDecision ss)) {
                            throw new IllegalStateException(
                                    "Expected SessionStartDecision, got " + decision.getClass().getSimpleName());
                        }
                        if (!ss.context().isEmpty()) {
                            pendingContext.computeIfAbsent(context.threadId(), k -> new ArrayList<>())
                                    .addAll(ss.context());
                        }
                        return ss;
                    });
        }

        public CompletionStage<SessionEndDecision> sessionEnd(
                ClientHookContext context, SessionEndCause cause) {
            if (!hasHandlers(HookEvent.SESSION_END)) {
                pendingContext.remove(context.threadId());
                return CompletableFuture.completedFuture(emptyDecision(HookEvent.SESSION_END,
                        SessionEndDecision.class));
            }
            return invoke(context, new HookDomainEvents.SessionEndEvent(HookEvent.SESSION_END, cause))
                    .thenApply(decision -> {
                        if (!(decision instanceof SessionEndDecision se)) {
                            throw new IllegalStateException(
                                    "Expected SessionEndDecision, got " + decision.getClass().getSimpleName());
                        }
                        pendingContext.remove(context.threadId());
                        return se;
                    });
        }

        public CompletionStage<UserPromptSubmitDecision> userPromptSubmit(
                ClientHookContext context, String prompt) {
            if (!hasHandlers(HookEvent.USER_PROMPT_SUBMIT)) {
                return CompletableFuture.completedFuture(emptyUserPromptDecision());
            }
            return invoke(context, new HookDomainEvents.UserPromptSubmitEvent(
                    HookEvent.USER_PROMPT_SUBMIT, prompt))
                    .thenApply(decision -> {
                        if (!(decision instanceof UserPromptSubmitDecision ups)) {
                            throw new IllegalStateException(
                                    "Expected UserPromptSubmitDecision, got "
                                            + decision.getClass().getSimpleName());
                        }
                        return ups;
                    });
        }

        public CompletionStage<PreCompactDecision> preCompact(
                ClientHookContext context, CompactTrigger trigger, String customInstructions) {
            if (!hasHandlers(HookEvent.PRE_COMPACT)) {
                return CompletableFuture.completedFuture(emptyDecision(HookEvent.PRE_COMPACT,
                        PreCompactDecision.class));
            }
            return invoke(context, new HookDomainEvents.PreCompactEvent(
                    HookEvent.PRE_COMPACT, trigger, customInstructions))
                    .thenApply(decision -> {
                        if (!(decision instanceof PreCompactDecision pc)) {
                            throw new IllegalStateException(
                                    "Expected PreCompactDecision, got " + decision.getClass().getSimpleName());
                        }
                        return pc;
                    });
        }

        public CompletionStage<PermissionRequestDecision> permissionRequest(
                ClientHookContext context, ToolCallData call) {
            if (!hasHandlers(HookEvent.PERMISSION_REQUEST)) {
                return CompletableFuture.completedFuture(new PermissionRequestDecision(
                        HookEvent.PERMISSION_REQUEST,
                        HookDomainEvents.BaseFields.neutral(),
                        PermissionEffect.of(PermissionEffect.Behavior.NONE)));
            }
            return invoke(context, new HookDomainEvents.PermissionRequestEvent(
                    HookEvent.PERMISSION_REQUEST, call))
                    .thenApply(decision -> {
                        if (!(decision instanceof PermissionRequestDecision pr)) {
                            throw new IllegalStateException(
                                    "Expected PermissionRequestDecision, got "
                                            + decision.getClass().getSimpleName());
                        }
                        return pr;
                    });
        }

        public CompletionStage<Permissions.PermissionHookOutcome> resolvePermission(
                ClientHookContext context, ToolCallData call) {
            return permissionRequest(context, call).thenApply(decision -> {
                Permissions.PermissionHookOutcome outcome = Permissions.permissionHookOutcome(decision);
                if (outcome.decision() != null) {
                    PermissionEffect effect = decision.continueProcessing()
                            ? decision.permission()
                            : new PermissionEffect(PermissionEffect.Behavior.DENY,
                                    decision.stopReason() == null || decision.stopReason().isEmpty()
                                            ? "Permission stopped by hook" : decision.stopReason(),
                                    true);
                    runtime.presenter().presentPermission(call.name(), effect);
                }
                return outcome;
            });
        }

        public CompletionStage<Void> notification(ClientHookContext context,
                                                  DcodeNotificationKind kind, String message,
                                                  String title) {
            if (!hasHandlers(HookEvent.NOTIFICATION)) {
                return CompletableFuture.completedFuture(null);
            }
            DcodeNotification notification = new DcodeNotification(kind, message, title);
            return invoke(context, new HookDomainEvents.NotificationEvent(
                    HookEvent.NOTIFICATION, notification))
                    .thenAccept(decision -> {
                        if (!(decision instanceof NotificationDecision n)) {
                            throw new IllegalStateException(
                                    "Expected NotificationDecision, got "
                                            + decision.getClass().getSimpleName());
                        }
                        if (!n.continueProcessing()) {
                            String reason = n.stopReason() == null || n.stopReason().isEmpty()
                                    ? "Notification stopped by hook" : n.stopReason();
                            throw new ClientHookStopError(reason);
                        }
                    });
        }

        public List<String> takeSessionContext(String threadId) {
            List<String> removed = pendingContext.remove(threadId);
            return removed == null ? List.of() : List.copyOf(removed);
        }

        public boolean hasHandlers(HookEvent event) {
            return runtime.configuredEvents().contains(event);
        }

        public void presentPermission(String toolName, PermissionEffect permission) {
            runtime.presenter().presentPermission(toolName, permission);
        }

        private CompletionStage<Decision> invoke(ClientHookContext context, HookDomainEvents.Event event) {
            HookContext hookContext = new HookContext(context.threadId(), runtime.cwd(),
                    context.promptId(), context.approvalMode(), null, null);
            HookInvocation invocation = new HookInvocation(hookContext, event);
            return runtime.invoke(invocation).thenApply(decision -> {
                runtime.presenter().presentDecision(decision);
                return decision;
            });
        }

        @SuppressWarnings("unchecked")
        private <T extends Decision> T emptyDecision(HookEvent event, Class<T> type) {
            if (type == SessionStartDecision.class) {
                return (T) new SessionStartDecision(event,
                        HookDomainEvents.BaseFields.neutral(), List.of());
            }
            if (type == SessionEndDecision.class) {
                return (T) new SessionEndDecision(event, HookDomainEvents.BaseFields.neutral());
            }
            if (type == PreCompactDecision.class) {
                return (T) new PreCompactDecision(event, HookDomainEvents.BaseFields.neutral());
            }
            throw new IllegalArgumentException("unsupported empty decision type: " + type);
        }

        private UserPromptSubmitDecision emptyUserPromptDecision() {
            return new UserPromptSubmitDecision(HookEvent.USER_PROMPT_SUBMIT,
                    HookDomainEvents.BaseFields.neutral(), List.of(), false);
        }
    }
}
