package org.aethercode.code.hooks;

import org.aethercode.code.hooks.HookTransportTypes.HookInvocationRequest;
import org.aethercode.code.hooks.HookTransportTypes.HookInvocationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Client-side fulfillment for server-owned Hooks v2 interrupts.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.client} module. The ledger deduplicates
 * concurrent and repeated deliveries of the same interrupt so the
 * client never runs a hook twice for the same (snapshot, invocation)
 * pair.</p>
 */
public final class Client {

    private static final Logger LOG = LoggerFactory.getLogger(Client.class);

    private Client() {}

    /** Deduplicate hook fulfillment for one client session. */
    public static final class HookFulfillmentLedger {
        private final Map<FulfillmentKey, CompletableFuture<HookInvocationResponse>> inFlight =
                new ConcurrentHashMap<>();
        private final Map<FulfillmentKey, HookInvocationResponse> completed = new ConcurrentHashMap<>();

        /**
         * Return one shared result for concurrent and repeated delivery.
         */
        public CompletableFuture<HookInvocationResponse> fulfill(
                FulfillmentKey key,
                Supplier<CompletionStage<HookInvocationResponse>> operation) {
            HookInvocationResponse already = completed.get(key);
            if (already != null) {
                return CompletableFuture.completedFuture(already);
            }
            synchronized (this) {
                if (completed.containsKey(key)) {
                    return CompletableFuture.completedFuture(completed.get(key));
                }
                CompletableFuture<HookInvocationResponse> existing = inFlight.get(key);
                if (existing != null) return existing;
                CompletableFuture<HookInvocationResponse> fresh = new CompletableFuture<>();
                inFlight.put(key, fresh);
                operation.get().whenComplete((response, error) -> {
                    if (error != null) {
                        inFlight.remove(key);
                        fresh.completeExceptionally(unwrap(error));
                    } else {
                        completed.put(key, response);
                        inFlight.remove(key);
                        fresh.complete(response);
                    }
                });
                return fresh;
            }
        }

        private static Throwable unwrap(Throwable error) {
            return error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
        }
    }

    /** Composite key used by {@link HookFulfillmentLedger}. */
    public record FulfillmentKey(String snapshotId, UUID invocationId) {}

    /**
     * Execute a server-owned hook request and return a resume payload.
     *
     * @throws IllegalArgumentException when the request snapshot does
     *         not match this session
     */
    public static CompletableFuture<Map<String, Object>> fulfillHookInvocation(
            Runtime.HooksRuntime runtime, HookInvocationRequest request) {
        if (!request.snapshotId().equals(runtime.snapshotId())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Hook snapshot mismatch: request " + request.snapshotId()
                            + " != runtime " + runtime.snapshotId()));
        }
        Supplier<CompletionStage<HookInvocationResponse>> op = () -> {
            try {
                return runtime.invoke(request.invocation())
                        .thenApply(decision -> new HookInvocationResponse(
                                1, request.invocationId(), request.snapshotId(), decision));
            } catch (RuntimeException ex) {
                return CompletableFuture.failedFuture(ex);
            }
        };
        return runtime.fulfillments().fulfill(
                new FulfillmentKey(request.snapshotId(), request.invocationId()), op)
                .thenApply(Interrupt::buildResumeValue);
    }

    /**
     * Fulfill a raw interrupt value when it is a hook invocation.
     */
    public static CompletableFuture<Map<String, Object>> fulfillHookInterrupt(
            Runtime.HooksRuntime runtime, Object interruptValue) {
        HookInvocationRequest request = Interrupt.parseInterruptPayload(interruptValue);
        if (request == null) return CompletableFuture.completedFuture(null);
        return fulfillHookInvocation(runtime, request);
    }

    /**
     * Fulfill pending hook interrupts into a resume map keyed by
     * interrupt id.
     */
    public static CompletableFuture<Map<String, Map<String, Object>>> fulfillPendingHookInterrupts(
            Runtime.HooksRuntime runtime, Map<String, Object> pending) {
        if (pending == null || pending.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> entry : pending.entrySet()) {
            CompletableFuture<Map<String, Object>> resume = fulfillHookInterrupt(runtime, entry.getValue());
            futures.add(resume.thenApply(value -> {
                if (value == null) {
                    throw new IllegalStateException("Failed to parse hook interrupt " + entry.getKey());
                }
                synchronized (result) { result.put(entry.getKey(), value); }
                return null;
            }));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> result);
    }
}
