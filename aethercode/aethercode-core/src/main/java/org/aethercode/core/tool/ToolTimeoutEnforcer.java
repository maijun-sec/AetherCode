package org.aethercode.core.tool;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.aethercode.core.tool.ToolHook.Context;
import org.aethercode.core.tool.ToolHook.Result;
import org.aethercode.core.tool.ToolHookRegistry.HookPreOutcome;

/**
 * per-tool timeout enforcer. Wraps a tool call in a
 * {@link Future} on a dedicated executor and cancels it if the
 * configured timeout elapses.
 *
 * <p>Default timeout is 60s; per-tool overrides can be set via
 * {@link #setTimeout(String, Duration)}. Tools that time out are
 * reported as {@code Result.error(...)} to the caller so the model
 * sees a structured failure rather than a hung invocation.
 */
public class ToolTimeoutEnforcer {

    public record Outcome(Result result, boolean timedOut, long elapsedMs) {
        public boolean isError() { return result != null && result.isError(); }
    }

    private final ExecutorService executor;
    private final Duration defaultTimeout;
    private final Map<String, Duration> perToolTimeouts = new ConcurrentHashMap<>();
    private final AtomicLong totalExecutions = new AtomicLong();
    private final AtomicLong totalTimeouts = new AtomicLong();

    public ToolTimeoutEnforcer() { this(Duration.ofSeconds(60)); }

    public ToolTimeoutEnforcer(Duration defaultTimeout) {
        this(defaultTimeout, defaultExecutor());
    }

    public ToolTimeoutEnforcer(Duration defaultTimeout, ExecutorService executor) {
        if (defaultTimeout == null || defaultTimeout.isZero() || defaultTimeout.isNegative())
            throw new IllegalArgumentException("defaultTimeout must be positive");
        if (executor == null) throw new IllegalArgumentException("executor is null");
        this.defaultTimeout = defaultTimeout;
        this.executor = executor;
    }

    public Duration defaultTimeout() { return defaultTimeout; }
    public ExecutorService executor() { return executor; }

    public ToolTimeoutEnforcer setTimeout(String toolName, Duration timeout) {
        Objects.requireNonNull(toolName, "toolName");
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("timeout must be positive");
        perToolTimeouts.put(toolName, timeout);
        return this;
    }

    public Duration getTimeout(String toolName) {
        Duration d = perToolTimeouts.get(toolName);
        return d == null ? defaultTimeout : d;
    }

    /**
     * run a tool with a timeout. {@code action} is the
     * tool body; it must not throw — return a {@link Result} instead.
     * The {@code context} is forwarded to the pre-hook pipeline.
     */
    public Outcome run(ToolHookRegistry hooks, String toolName, Map<String, Object> input, java.util.function.Supplier<Result> action) {
        Objects.requireNonNull(action, "action");
        HookPreOutcome pre = hooks.runPre(toolName, input);
        if (pre.denial() != null) {
            return new Outcome(pre.denial(), false, 0);
        }
        long started = System.currentTimeMillis();
        totalExecutions.incrementAndGet();
        Duration timeout = getTimeout(toolName);
        Future<Result> future = executor.submit(() -> action.get());
        try {
            Result r = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - started;
            Result post = hooks.runPost(pre.context(), r);
            return new Outcome(post, false, elapsed);
        } catch (TimeoutException e) {
            future.cancel(true);
            totalTimeouts.incrementAndGet();
            long elapsed = System.currentTimeMillis() - started;
            Result err = Result.error("tool '" + toolName + "' timed out after " + timeout.toMillis() + "ms");
            return new Outcome(err, true, elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return new Outcome(Result.error("tool '" + toolName + "' was interrupted"), false, System.currentTimeMillis() - started);
        } catch (ExecutionException e) {
            return new Outcome(Result.error("tool '" + toolName + "' failed: " + e.getCause()), false, System.currentTimeMillis() - started);
        }
    }

    public long totalExecutions() { return totalExecutions.get(); }
    public long totalTimeouts()   { return totalTimeouts.get(); }
    public double timeoutRate()   {
        long t = totalExecutions.get();
        return t == 0 ? 0.0 : (double) totalTimeouts.get() / (double) t;
    }

    /** shut down the executor; the enforcer is unusable afterwards. */
    public void shutdown() {
        executor.shutdownNow();
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "tool-timeout");
            t.setDaemon(true);
            return t;
        });
    }
}
