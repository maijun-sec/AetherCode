package org.aethercode.tasks.channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * R-P1-T08 (app-spec/tasks.md §1.1): a per-connection channel
 * that blocks a tool-call until the user acknowledges (or
 * declines) the prompt. The supervisor emits a
 * {@code needs_consent} event with a monotonically increasing
 * {@code seq}, the APP shows the consent modal, and the user's
 * choice comes back over the socket as
 * {@code task/consent <eventId>, <choice>}. The channel
 * completes the {@code CompletableFuture} returned by
 * {@link #awaitAck(long, long)} when the matching choice
 * arrives.
 *
 * <p>If the user does not reply within {@code timeoutMs}, the
 * future completes with {@link Decision#DENY} — the spec calls
 * this "auto-deny on timeout" so a disconnected APP cannot
 * keep a tool call blocked forever.
 *
 * <p>Thread-safety: every method is safe to call from any
 * thread. The internal map of pending acks is a
 * {@link ConcurrentHashMap}; the timeout scheduler uses a
 * daemon thread.
 */
public interface ResumableChannel extends AutoCloseable {

    /**
     * The user's choice on a consent prompt. The semantics are
     * the same as the {@code aethercode-permission} grant
     * model: {@code ALWAYS_*} persists a grant so the same
     * choice is reused for future calls in the same category.
     */
    enum Decision {
        ALLOW,
        DENY,
        ALWAYS_ALLOW,
        ALWAYS_DENY
    }

    /**
     * Block the caller until the user replies to the consent
     * prompt for event {@code seq}, or until {@code timeoutMs}
     * elapses, whichever comes first.
     *
     * <p>On user reply, the future completes with the chosen
     * {@link Decision}. On timeout it completes with
     * {@link Decision#DENY} (auto-deny).
     *
     * <p>If the same {@code seq} is already pending, the
     * existing future is returned (idempotent: two callers
     * awaiting the same event get the same future, and the
     * first reply completes both).
     */
    CompletableFuture<Decision> awaitAck(long seq, long timeoutMs);

    /**
     * Resolve the pending ack for {@code seq} with the user's
     * decision. Completes the future returned by
     * {@link #awaitAck(long, long)}; if no caller is waiting,
     * the decision is dropped (the next {@code awaitAck} for
     * the same {@code seq} will not see it — the spec doesn't
     * require buffering).
     */
    void resolve(long seq, Decision decision);

    /** Number of acks currently waiting for a reply. */
    int pendingCount();

    /**
     * Cancel every pending ack with {@link Decision#DENY}.
     * Called when the connection drops so callers blocked on
     * {@link #awaitAck(long, long)} unblock immediately.
     */
    void cancelAll();

    /** Cancel this channel's scheduler. Idempotent. */
    @Override
    void close();

    // -- default impl -----------------------------------------------------

    /**
     * Reference in-memory implementation backed by a
     * {@link ConcurrentHashMap} and a shared scheduled
     * executor for the timeouts. The executor is daemon and
     * is shut down on {@link #close()}.
     */
    final class InMemory implements ResumableChannel {

        private final String connectionId;
        private final ScheduledExecutorService ownsScheduler;
        private final ScheduledExecutorService scheduler;
        private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
        private final AtomicInteger timeouts = new AtomicInteger(0);
        private volatile boolean closed = false;

        public InMemory(String connectionId) {
            this(connectionId, defaultScheduler(), true);
        }

        public InMemory(String connectionId, ScheduledExecutorService scheduler) {
            this(connectionId, scheduler, false);
        }

        private InMemory(String connectionId, ScheduledExecutorService scheduler,
                         boolean ownsScheduler) {
            this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
            this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
            this.ownsScheduler = ownsScheduler ? scheduler : null;
        }

        public String connectionId() { return connectionId; }
        public int timeoutCount() { return timeouts.get(); }

        @Override
        public CompletableFuture<Decision> awaitAck(long seq, long timeoutMs) {
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("timeoutMs must be > 0, got " + timeoutMs);
            }
            if (closed) {
                // Reject new awaits after close — return a
                // future already completed with DENY so callers
                // don't hang on a closed channel.
                return CompletableFuture.completedFuture(Decision.DENY);
            }
            Pending p = pending.computeIfAbsent(seq, s -> new Pending(new CompletableFuture<>()));
            if (p.timeoutFuture == null && !p.future.isDone()) {
                ScheduledFuture<?> sf = scheduler.schedule(
                        () -> timeout(seq),
                        timeoutMs, TimeUnit.MILLISECONDS);
                // The Pending may have been removed by a resolve
                // before the schedule call; CAS the timeout handle.
                Pending current = pending.get(seq);
                if (current == p) {
                    p.timeoutFuture = sf;
                } else {
                    sf.cancel(false);
                }
            }
            return p.future;
        }

        @Override
        public void resolve(long seq, Decision decision) {
            Objects.requireNonNull(decision, "decision");
            Pending p = pending.remove(seq);
            if (p == null) return;
            if (p.timeoutFuture != null) p.timeoutFuture.cancel(false);
            p.future.complete(decision);
        }

        @Override
        public int pendingCount() { return pending.size(); }

        @Override
        public void cancelAll() {
            // Copy the keys because resolve() mutates the map.
            for (Long seq : new ArrayList<>(pending.keySet())) {
                resolve(seq, Decision.DENY);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            cancelAll();
            if (ownsScheduler != null) ownsScheduler.shutdownNow();
        }

        private void timeout(long seq) {
            Pending p = pending.remove(seq);
            if (p == null) return; // resolved first
            timeouts.incrementAndGet();
            p.future.complete(Decision.DENY);
        }

        /** Pending ack: a future plus its cancellation handle. */
        private static final class Pending {
            final CompletableFuture<Decision> future;
            volatile ScheduledFuture<?> timeoutFuture;
            Pending(CompletableFuture<Decision> future) {
                this.future = future;
            }
        }
    }

    // -- helpers ----------------------------------------------------------

    /**
     * Convenience: block the caller on the future for at most
     * {@code timeoutMs}, returning {@link Decision#DENY} on
     * any exception. Useful for synchronous supervisors that
     * don't want to plumb {@link CompletableFuture} through.
     */
    static Decision await(CompletableFuture<Decision> future, long timeoutMs) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return Decision.DENY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Decision.DENY;
        } catch (Exception e) {
            return Decision.DENY;
        }
    }

    private static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aethercode-resumable");
            t.setDaemon(true);
            return t;
        });
    }
}
