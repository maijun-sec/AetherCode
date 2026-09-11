package org.aethercode.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

/**
 * utility that runs a {@link Callable} under a
 * {@link RetryPolicy}. On each failure, it sleeps for the
 * policy's backoff (using a {@link Sleeper} that defaults to
 * {@code Thread.sleep} but can be replaced for tests) and
 * retries up to {@code maxAttempts} times.
 *
 * <p>The result includes the number of attempts used and the
 * last error (if any). Use this for transient failures (network
 * blips, rate limits, temporary resource contention). For
 * deterministic failures (bad input, missing file), the policy
 * still retries but it's wasteful — callers should detect those
 * cases upstream and avoid the retry.
 */
public final class RetryHelper {

    private static final Logger LOG = LoggerFactory.getLogger(RetryHelper.class);

    /** pluggable sleeper. Tests substitute a no-op to keep
     *  test time short. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** default sleeper uses {@link Thread#sleep(long)}. */
    public static final Sleeper REAL_SLEEPER = Thread::sleep;

    private RetryHelper() {}

    /** run a callable with the given policy. The sleeper
     *  defaults to {@link #REAL_SLEEPER}. Returns a {@link Result}
     *  that captures the outcome. */
    public static <V> Result<V> run(Callable<V> task, RetryPolicy policy) {
        return run(task, policy, REAL_SLEEPER);
    }

    public static <V> Result<V> run(Callable<V> task, RetryPolicy policy, Sleeper sleeper) {
        if (task == null) throw new IllegalArgumentException("task");
        if (policy == null) throw new IllegalArgumentException("policy");
        Throwable lastError = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            try {
                V value = task.call();
                return new Result<>(value, attempt, null);
            } catch (Exception e) {
                lastError = e;
                LOG.warn("对应历史 round attempt {} of {} failed: {}", attempt, policy.maxAttempts(), e.getMessage());
                if (!policy.shouldRetry(attempt)) break;
                long backoff = policy.backoffFor(attempt + 1);
                if (backoff > 0) {
                    try {
                        sleeper.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return new Result<>(null, attempt, ie);
                    }
                }
            }
        }
        return new Result<>(null, policy.maxAttempts(), lastError);
    }

    /** outcome of a retried run. {@code attempts} is 1-based
     *  — the value is 1 when the task succeeded on the first try. */
    public record Result<V>(V value, int attempts, Throwable error) {
        public boolean isSuccess() { return error == null; }
        public boolean isFailure() { return error != null; }
    }
}
