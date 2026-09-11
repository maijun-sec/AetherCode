package org.aethercode.partner.quickjs.repl;

import org.aethercode.partner.quickjs.js.JsContext;
import org.aethercode.partner.quickjs.js.JsExecutor;
import org.aethercode.partner.quickjs.js.JsRuntime;
import org.aethercode.partner.quickjs.js.JsSourceTransform;
import org.aethercode.partner.quickjs.js.JsWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Per-thread REPL registry. 1:1 port of the Python
 * {@code _Registry} dataclass in <code>_repl.py</code>.
 *
 * <p>Each LangGraph {@code thread_id} gets its own {@link Slot}
 * (worker + runtime + REPL). Eviction is driven externally via
 * {@link #evict(String)} &mdash; typically from the middleware's
 * {@code after_agent} hook.</p>
 */
public final class Registry {

    private static final Logger LOGGER = LoggerFactory.getLogger(Registry.class);

    private final JsExecutor executor;
    private final int memoryLimit;
    private final double timeout;
    private final boolean captureConsole;
    private final int maxStdoutChars;
    private final int maxPtcCalls;
    private final boolean subagentsEnabled;
    private final Set<JsSourceTransform> transformFlags;

    private final Map<String, Slot> slots = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public Registry(JsExecutor executor,
                    int memoryLimit,
                    double timeout,
                    boolean captureConsole,
                    int maxStdoutChars,
                    Integer maxPtcCalls,
                    boolean subagentsEnabled,
                    Set<JsSourceTransform> transformFlags) {
        this.executor = executor;
        this.memoryLimit = memoryLimit;
        this.timeout = timeout;
        this.captureConsole = captureConsole;
        this.maxStdoutChars = maxStdoutChars;
        this.maxPtcCalls = maxPtcCalls == null ? -1 : maxPtcCalls;
        this.subagentsEnabled = subagentsEnabled;
        this.transformFlags = transformFlags == null ? Set.of() : Set.copyOf(transformFlags);
    }

    /**
     * Return the REPL for {@code threadId}, building a fresh slot if
     * none exists. Thread-safe; only one slot is ever built for a
     * given id.
     */
    public Repl get(String threadId) {
        lock.lock();
        try {
            Slot slot = slots.get(threadId);
            if (slot == null) {
                slot = buildSlotLocked(threadId);
                slots.put(threadId, slot);
            }
            return slot.repl;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Return the existing REPL for {@code threadId} without creating
     * a new slot. Returns {@code null} when no slot is registered.
     */
    public Repl getIfExists(String threadId) {
        lock.lock();
        try {
            Slot slot = slots.get(threadId);
            return slot == null ? null : slot.repl;
        } finally {
            lock.unlock();
        }
    }

    /** Close and remove the slot for {@code threadId}. No-op if absent. */
    public void evict(String threadId) {
        Slot slot;
        lock.lock();
        try {
            slot = slots.remove(threadId);
        } finally {
            lock.unlock();
        }
        if (slot != null) closeSlot(slot);
    }

    /** Async variant of {@link #evict}. */
    public CompletableFuture<Void> aevict(String threadId) {
        Slot slot;
        lock.lock();
        try {
            slot = slots.remove(threadId);
        } finally {
            lock.unlock();
        }
        if (slot == null) return CompletableFuture.completedFuture(null);
        return acloseSlot(slot);
    }

    /**
     * Replace the slot's REPL while keeping its worker and runtime
     * alive. Used for {@code mode == "call"} so each eval gets a
     * fresh environment without paying the cost of a new runtime.
     */
    public void resetRepl(String threadId) {
        Slot slot;
        lock.lock();
        try {
            slot = slots.get(threadId);
        } finally {
            lock.unlock();
        }
        if (slot == null) return;
        try {
            slot.repl.close();
        } catch (RuntimeException ignored) {
            // best-effort
        }
        Repl newRepl = new Repl(slot.worker, slot.runtime, timeout, captureConsole,
                maxStdoutChars, maxPtcCalls, subagentsEnabled);
        lock.lock();
        try {
            Slot current = slots.get(threadId);
            if (current == slot) {
                slot.repl = newRepl;
                return;
            }
        } finally {
            lock.unlock();
        }
        // Slot was removed/replaced while rebuilding.
        try {
            newRepl.close();
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    private Slot buildSlotLocked(String threadId) {
        String name = "quickjs-worker-" + (threadId.length() >= 8 ? threadId.substring(0, 8) : threadId);
        JsWorker worker = executor.createWorker(name);
        JsRuntime runtime = worker.runSync(() -> createRuntime());
        Repl repl = new Repl(worker, runtime, timeout, captureConsole, maxStdoutChars, maxPtcCalls, subagentsEnabled);
        return new Slot(worker, runtime, repl);
    }

    private JsRuntime createRuntime() {
        // The JsExecutor surface returns a JsRuntime via a worker
        // call; concrete implementations decide how the runtime is
        // created (GraalVM context builder, native handle, etc.).
        // The default `UnsupportedJsExecutor` throws on every
        // call, so this is a future-binding point.
        throw new UnsupportedOperationException(
                "Cannot create a JsRuntime: no real JsExecutor is configured. "
                        + "Wire a GraalVM JS, javax.script, or JNI-based binding before "
                        + "instantiating a Registry that builds REPLs.");
    }

    private void closeSlot(Slot slot) {
        try {
            slot.repl.close();
        } catch (RuntimeException e) {
            LOGGER.debug("Failed to close REPL on slot eviction", e);
        }
        try {
            slot.worker.runSync((java.util.concurrent.Callable<Void>) () -> {
                slot.runtime.close();
                return null;
            });
        } catch (RuntimeException e) {
            LOGGER.debug("Failed to close JsRuntime on slot eviction", e);
        } catch (Exception e) {
            LOGGER.debug("Failed to close JsRuntime on slot eviction", e);
        }
        try {
            slot.worker.close();
        } catch (RuntimeException e) {
            LOGGER.debug("Failed to close JsWorker on slot eviction", e);
        }
    }

    private CompletableFuture<Void> acloseSlot(Slot slot) {
        return slot.repl.aclose()
                .thenCompose(v -> slot.worker.<Void>runAsync(() -> {
                    slot.runtime.close();
                    return null;
                }))
                .whenComplete((v, t) -> slot.worker.close());
    }

    /** Close every slot and free the underlying resources. */
    public void close() {
        List<Slot> snapshot;
        lock.lock();
        try {
            snapshot = new ArrayList<>(slots.values());
            slots.clear();
        } finally {
            lock.unlock();
        }
        for (Slot slot : snapshot) closeSlot(slot);
    }

    /** One LangGraph thread's private QuickJS stack. */
    public static final class Slot {
        public final JsWorker worker;
        public final JsRuntime runtime;
        public Repl repl;

        public Slot(JsWorker worker, JsRuntime runtime, Repl repl) {
            this.worker = worker;
            this.runtime = runtime;
            this.repl = repl;
        }
    }
}
