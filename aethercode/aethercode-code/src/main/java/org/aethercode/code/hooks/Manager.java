package org.aethercode.code.hooks;

import org.aethercode.code.hooks.ClientLifecycle.ClientHookContext;
import org.aethercode.code.hooks.ClientLifecycle.ClientHookService;
import org.aethercode.code.hooks.ClientLifecycle.ClientHooksRuntime;
import org.aethercode.code.hooks.HookDomainEvents.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Single-owner coordinator for client-side Hooks v2 state.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.manager} module. The manager owns the
 * runtime, presenter, hook service, and transcripts; a manager whose
 * configuration failed to load stays usable and answers every call
 * with a neutral result.</p>
 */
public final class Manager {

    private static final Logger LOG = LoggerFactory.getLogger(Manager.class);

    /** Live client identity projected into every hook invocation. */
    public record HookSessionIdentity(String threadId, ApprovalMode approvalMode, String promptId) {
        public HookSessionIdentity {
            if (threadId == null) threadId = "";
            if (approvalMode == null) approvalMode = ApprovalMode.MANUAL;
        }
    }

    /** Reads current session identity at invocation time, never cached. */
    @FunctionalInterface
    public interface SessionIdentityProvider extends Supplier<HookSessionIdentity> {}

    /** Result of a lifecycle hook that may halt the caller. */
    public record HookOutcome(boolean ok, String stopReason) {
        public static HookOutcome pass() { return new HookOutcome(true, null); }
        public static HookOutcome stopped(String reason) { return new HookOutcome(false, reason); }
    }

    /** Result of {@code UserPromptSubmit}, including prompt rewrites. */
    public record PromptOutcome(
            boolean ok, String stopReason, List<String> context, boolean suppressOriginalPrompt) {
        public static PromptOutcome passed(List<String> context, boolean suppress) {
            return new PromptOutcome(true, null, context, suppress);
        }
        public static PromptOutcome stopped(String reason) {
            return new PromptOutcome(false, reason, List.of(), false);
        }
    }

    /**
     * Owns the Hooks v2 runtime, presenter, hook service, and
     * transcripts.
     */
    public static final class HooksManager {
        private final SessionIdentityProvider identity;
        private final Presenter presenter;
        private final Trust.WorkspaceTrust trust;
        private Runtime.HooksRuntime runtime;
        private ClientHookService service;

        public HooksManager(SessionIdentityProvider identity, Presenter presenter,
                            Runtime.HooksRuntime runtime, Trust.WorkspaceTrust trust) {
            this.identity = identity;
            this.presenter = presenter;
            this.runtime = runtime;
            this.trust = trust;
            this.service = buildService();
        }

        public static HooksManager create(Path cwd, SessionIdentityProvider identity,
                                          Presenter.HookNoticeCallback notice,
                                          Presenter.HookStatusCallback status,
                                          Trust.WorkspaceTrust trust) {
            Trust.WorkspaceTrust policy = trust != null ? trust : Trust.WorkspaceTrust.none();
            Presenter presenter = new Presenter(notice, status);
            Runtime.HooksRuntime runtime = loadRuntime(cwd, policy, presenter, null);
            presentLoadDiagnostics(runtime);
            return new HooksManager(identity, presenter, runtime, policy);
        }

        public static HooksManager adopting(Runtime.HooksRuntime runtime,
                                            SessionIdentityProvider identity,
                                            Presenter.HookNoticeCallback notice,
                                            Presenter.HookStatusCallback status) {
            if (runtime == null) {
                return new HooksManager(identity, new Presenter(notice, status), null,
                        Trust.WorkspaceTrust.none());
            }
            if (notice != null || status != null) {
                runtime.presenter().attach(notice, status);
            }
            return new HooksManager(identity, runtime.presenter(), runtime,
                    Trust.WorkspaceTrust.none());
        }

        public static HooksManager inert() {
            return new HooksManager(() -> new HookSessionIdentity("", ApprovalMode.MANUAL, null),
                    new Presenter(), null, Trust.WorkspaceTrust.none());
        }

        public void attachOutput(Presenter.HookNoticeCallback notice,
                                 Presenter.HookStatusCallback status) {
            presenter.attach(notice, status);
            presentLoadDiagnostics(runtime);
        }

        public boolean enabled() {
            return service != null;
        }

        public boolean hasHandlers(HookEvent event) {
            return service != null && service.hasHandlers(event);
        }

        public CompletionStage<HookOutcome> onSessionStart(
                org.aethercode.code.hooks.SessionStartCause cause, String model) {
            if (service == null || !service.hasHandlers(HookEvent.SESSION_START)) {
                return CompletableFuture.completedFuture(HookOutcome.pass());
            }
            return service.sessionStart(context(), cause, model)
                    .thenApply(decision -> {
                        if (decision.continueProcessing()) return HookOutcome.pass();
                        return HookOutcome.stopped(decision.stopReason() == null
                                || decision.stopReason().isEmpty()
                                ? "Session start was stopped by a hook."
                                : decision.stopReason());
                    })
                    .exceptionally(ex -> {
                        LOG.warn("SessionStart hook invocation failed", ex);
                        return HookOutcome.pass();
                    });
        }

        public CompletionStage<Void> onSessionEnd(org.aethercode.code.hooks.SessionEndCause cause,
                                                  String threadId) {
            if (service == null || !service.hasHandlers(HookEvent.SESSION_END)) {
                return CompletableFuture.completedFuture(null);
            }
            ClientHookContext ctx = context(threadId);
            return service.sessionEnd(ctx, cause)
                    .thenApply(decision -> (Void) null)
                    .exceptionally(ex -> {
                        LOG.warn("SessionEnd hook invocation failed", ex);
                        return null;
                    });
        }

        public CompletionStage<PromptOutcome> onUserPrompt(String prompt) {
            if (service == null || !service.hasHandlers(HookEvent.USER_PROMPT_SUBMIT)) {
                return CompletableFuture.completedFuture(PromptOutcome.passed(List.of(), false));
            }
            return service.userPromptSubmit(context(), prompt)
                    .thenApply(decision -> {
                        if (!decision.continueProcessing()) {
                            return PromptOutcome.stopped(decision.stopReason() == null
                                    || decision.stopReason().isEmpty()
                                    ? "User prompt submission stopped by hook"
                                    : decision.stopReason());
                        }
                        return PromptOutcome.passed(decision.context(),
                                decision.suppressOriginalPrompt());
                    })
                    .exceptionally(ex -> {
                        LOG.warn("UserPromptSubmit hook invocation failed", ex);
                        return PromptOutcome.passed(List.of(), false);
                    });
        }

        public CompletionStage<HookOutcome> onPreCompact(CompactTrigger trigger,
                                                          String customInstructions) {
            if (service == null || !service.hasHandlers(HookEvent.PRE_COMPACT)) {
                return CompletableFuture.completedFuture(HookOutcome.pass());
            }
            return service.preCompact(context(), trigger, customInstructions)
                    .thenApply(decision -> {
                        if (decision.continueProcessing()) return HookOutcome.pass();
                        return HookOutcome.stopped(decision.stopReason() == null
                                || decision.stopReason().isEmpty()
                                ? "Compaction stopped by hook"
                                : decision.stopReason());
                    })
                    .exceptionally(ex -> {
                        LOG.warn("PreCompact hook invocation failed", ex);
                        return HookOutcome.pass();
                    });
        }

        public CompletionStage<Permissions.PermissionPlan> onPermissionRequest(
                List<ToolCallData> calls) {
            if (service == null || !service.hasHandlers(HookEvent.PERMISSION_REQUEST)) {
                List<Permissions.PermissionHookOutcome> neutral = new ArrayList<>();
                for (int i = 0; i < calls.size(); i++) {
                    neutral.add(new Permissions.PermissionHookOutcome(null, false));
                }
                return CompletableFuture.completedFuture(new Permissions.PermissionPlan(neutral));
            }
            ClientHookContext ctx = context();
            List<CompletableFuture<Permissions.PermissionHookOutcome>> futures = new ArrayList<>();
            for (ToolCallData call : calls) {
                if (call == null) {
                    futures.add(CompletableFuture.completedFuture(
                            new Permissions.PermissionHookOutcome(null, false)));
                } else {
                    CompletionStage<Permissions.PermissionHookOutcome> stage =
                            service.resolvePermission(ctx, call);
                    futures.add(stage.toCompletableFuture()
                            .exceptionally(ex -> {
                                LOG.warn("PermissionRequest hook invocation failed", ex);
                                return new Permissions.PermissionHookOutcome(null, false);
                            }));
                }
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> new Permissions.PermissionPlan(
                            futures.stream().map(CompletableFuture::join).toList()));
        }

        public CompletionStage<Void> notify(DcodeNotificationKind kind, String message, String title) {
            if (service == null) return CompletableFuture.completedFuture(null);
            ClientHookContext ctx = context();
            return service.notification(ctx, kind, message, title)
                    .exceptionally(ex -> {
                        if (ex.getCause() instanceof ClientLifecycle.ClientHookStopError stop) {
                            throw new CompletionException(stop);
                        }
                        LOG.warn("Notification hook invocation failed", ex);
                        return null;
                    });
        }

        public List<String> takePendingContext() {
            return takePendingContext(null);
        }

        public List<String> takePendingContext(String threadId) {
            if (service == null) return List.of();
            return service.takeSessionContext(threadId != null ? threadId : identity.get().threadId());
        }

        public void applyGraphContext(Map<String, Object> context) {
            String promptId = identity.get().promptId();
            Context.applyHooksContext(context, runtime, promptId);
        }

        public CompletionStage<Map<String, Object>> fulfillInterrupt(Object payload) {
            if (runtime == null) {
                CompletableFuture<Map<String, Object>> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException(
                        "Received hook invocation interrupt without a HooksRuntime"));
                return failed;
            }
            return Client.fulfillHookInterrupt(runtime, payload)
                    .thenApply(value -> {
                        if (value == null) {
                            throw new IllegalStateException("Failed to parse hook interrupt");
                        }
                        return value;
                    });
        }

        public CompletionStage<Map<String, Map<String, Object>>> fulfillPendingInterrupts(
                Map<String, Object> pending) {
            if (runtime == null) {
                CompletableFuture<Map<String, Map<String, Object>>> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException(
                        "Received hook invocation interrupt without a HooksRuntime"));
                return failed;
            }
            return Client.fulfillPendingHookInterrupts(runtime, pending);
        }

        private ClientHookService buildService() {
            Runtime.HooksRuntime snapshot = this.runtime;
            if (snapshot == null) return null;
            ClientHooksRuntime view = new ClientHooksRuntime() {
                @Override public Path cwd() { return snapshot.cwd(); }
                @Override public Presenter presenter() { return snapshot.presenter(); }
                @Override public Set<HookEvent> configuredEvents() { return snapshot.configuredEvents(); }
                @Override public CompletionStage<Decision> invoke(HookInvocation invocation) {
                    return snapshot.invoke(invocation);
                }
            };
            return new ClientHookService(view);
        }

        private ClientHookContext context() {
            return context(null);
        }

        private ClientHookContext context(String threadId) {
            HookSessionIdentity ident = identity.get();
            UUID promptUuid = ident.promptId() == null ? null : parseUuid(ident.promptId());
            return ClientHookContext.create(
                    threadId != null ? threadId : ident.threadId(),
                    ident.approvalMode(),
                    promptUuid);
        }

        private static UUID parseUuid(String value) {
            try { return UUID.fromString(value); } catch (Exception ex) { return null; }
        }
    }

    private static Runtime.HooksRuntime loadRuntime(Path cwd, Trust.WorkspaceTrust trust,
                                                    Presenter presenter, Object plugins) {
        try {
            // Without a full ProjectContext + plugin discovery implementation
            // we fall back to the user scope only; tests pass a custom
            // transcript root via runtime.create() when they need plugins.
            Loading.LoadedHooksConfig loaded = Loading.loadHooksConfig(
                    cwd == null ? Path.of(".") : cwd,
                    trust.allows(cwd, null),
                    null,
                    null,
                    List.of(),
                    List.of());
            Snapshot.HooksSnapshot snapshot = Snapshot.HooksSnapshot.fromConfig(
                    loaded.config(), loaded.groups(), loaded.diagnostics(), loaded.snapshotId());
            TranscriptStore transcripts = new TranscriptStore(
                    Path.of(System.getProperty("user.home", "~"),
                            ".deepagents", "transcripts"));
            HookEngine engine = new HookEngine(snapshot);
            return new Runtime.HooksRuntime(
                    snapshot,
                    transcripts,
                    engine,
                    cwd == null ? Path.of(".") : cwd,
                    trust.allows(cwd, null),
                    loaded.projectSourceLoaded(),
                    loaded.projectSourceFingerprint(),
                    presenter,
                    new Client.HookFulfillmentLedger());
        } catch (RuntimeException ex) {
            LOG.error("Failed to load hook configuration; hooks disabled", ex);
            return null;
        }
    }

    private static void presentLoadDiagnostics(Runtime.HooksRuntime runtime) {
        if (runtime == null) return;
        runtime.presenter().presentDiagnostics(runtime.snapshot().diagnostics());
    }
}
