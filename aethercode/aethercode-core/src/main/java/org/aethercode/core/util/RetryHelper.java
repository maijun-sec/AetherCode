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

package org.aethercode.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

/**
 * R362 Round 3: utility that runs a {@link Callable}
 * under a {@link RetryPolicy}. On each failure, it
 * sleeps for the policy's backoff (using a
 * {@link Sleeper} that defaults to
 * {@code Thread.sleep} but can be replaced for tests)
 * and retries up to {@code maxAttempts} times.
 *
 * <p>The result includes the number of attempts used
 * and the last error (if any). Use this for transient
 * failures (network blips, rate limits, temporary
 * resource contention). For deterministic failures
 * (bad input, missing file), the policy still retries
 * but it's wasteful — callers should detect those
 * cases upstream and avoid the retry.
 *
 * <p>This is a copy of
 * {@code org.aethercode.sdk.RetryHelper}. The SDK
 * module depends on the tools module, so the tools
 * module cannot depend back on the SDK (would create
 * a cycle). The copy lives in core (which both depend
 * on) so the tools can use it. The SDK copy is kept
 * for backwards compatibility — a future round can
 * deprecate it.
 */
public final class RetryHelper {

    private static final Logger LOG = LoggerFactory.getLogger(RetryHelper.class);

    /** pluggable sleeper. Tests substitute a no-op
     *  to keep test time short. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** default sleeper uses
     *  {@link Thread#sleep(long)}. */
    public static final Sleeper REAL_SLEEPER = Thread::sleep;

    private RetryHelper() {}

    /** run a callable with the given policy. The
     *  sleeper defaults to {@link #REAL_SLEEPER}.
     *  Returns a {@link Result} that captures the
     *  outcome. */
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
                LOG.warn("attempt {} of {} failed: {}", attempt, policy.maxAttempts(), e.getMessage());
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

    /** outcome of a retried run. {@code attempts}
     *  is 1-based — the value is 1 when the task
     *  succeeded on the first try. */
    public record Result<V>(V value, int attempts, Throwable error) {
        public boolean isSuccess() { return error == null; }
        public boolean isFailure() { return error != null; }
    }
}