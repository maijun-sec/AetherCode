package org.aethercode.code.hooks;

import org.aethercode.code.hooks.Client.HookFulfillmentLedger;
import org.aethercode.code.hooks.HookDomainEvents.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Session-scoped client facade for the Hooks v2 runtime.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.runtime.HooksRuntime} record. The
 * runtime owns configuration snapshot identity, transcript
 * materialization, and the {@link HookEngine}; server-owned
 * lifecycle events reach this runtime through the interrupt fulfill
 * path in {@link Client}.</p>
 */
public final class Runtime {

    private static final Logger LOG = LoggerFactory.getLogger(Runtime.class);

    private Runtime() {}

    /** Client-only materialization needed to build one hook wire envelope. */
    public record PreparedHookInvocation(
            HookInvocation invocation,
            Path transcriptPath,
            String transcriptRevision,
            Path agentTranscriptPath,
            String agentTranscriptRevision) {}

    /** The session runtime, ready to execute invocations. */
    public record HooksRuntime(
            Snapshot.HooksSnapshot snapshot,
            TranscriptStore transcripts,
            HookEngine engine,
            Path cwd,
            boolean workspaceTrusted,
            boolean projectHooksLoaded,
            String projectHooksFingerprint,
            Presenter presenter,
            HookFulfillmentLedger fulfillments) {

        public HooksRuntime {
            if (cwd == null) cwd = Path.of(".");
        }

        public String snapshotId() {
            return snapshot.snapshotId();
        }

        /** Sorted event names the server should emit for this session. */
        public List<String> configuredServerEvents() {
            return snapshot.configuredServerEvents().stream()
                    .map(HookEvent::wireName)
                    .sorted()
                    .toList();
        }

        /** Every event with at least one configured handler. */
        public Set<HookEvent> configuredEvents() {
            return snapshot.configuredEvents();
        }

        public CompletionStage<Decision> invoke(HookInvocation invocation) {
            if (projectHooksLoaded && !workspaceTrusted) {
                return CompletableFuture.failedFuture(new SecurityException(
                        "Project hooks cannot execute before workspace trust is granted"));
            }
            PreparedHookInvocation prepared = prepareInvocation(invocation);
            return CompletableFuture.supplyAsync(() -> engine.run(prepared.invocation(),
                            prepared.transcriptPath(),
                            prepared.agentTranscriptPath(),
                            presenter::updateProgress))
                    .thenApply(result -> {
                        Decision decision = result.decision();
                        presenter.presentDecision(decision);
                        return decision;
                    });
        }

        public PreparedHookInvocation prepareInvocation(HookInvocation invocation) {
            HookContext context = invocation.context();
            TranscriptStore.TranscriptHandle threadHandle = transcripts.materialize(context.threadId());
            String agentId = null;
            if (invocation.event() instanceof HookDomainEvents.SubagentStartEvent sa) {
                agentId = sa.agent().id();
            } else if (invocation.event() instanceof HookDomainEvents.SubagentStopEvent ss) {
                agentId = ss.agent().id();
            } else if (context.agent() != null) {
                agentId = context.agent().id();
            }
            Path agentPath = null;
            String agentRevision = null;
            if (agentId != null) {
                TranscriptStore.TranscriptHandle agentHandle = transcripts.materialize(
                        context.threadId(), agentId);
                agentPath = agentHandle.path();
                agentRevision = agentHandle.revision();
            }
            return new PreparedHookInvocation(invocation, threadHandle.path(), threadHandle.revision(),
                    agentPath, agentRevision);
        }
    }
}
