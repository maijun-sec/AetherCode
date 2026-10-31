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

package org.aethercode.sdk;

import org.aethercode.core.agent.TaskDispatcher;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * a {@link TaskDispatcher} decorator that wraps another
 * dispatcher with a {@link CircuitBreaker}. While the breaker
 * is open, submissions are rejected immediately with a
 * {@link BreakerOpenException} — no resource is consumed.
 *
 * <p>Failures are recorded by the underlying dispatcher's
 * listener (or by the caller wrapping the runnable). Success
 * is recorded on normal completion. This decorator only
 * counts explicit {@link #recordSuccess()} / {@link
 * #recordFailure()} calls, which the caller makes from a
 * try/catch around {@code awaitIdle} or by wrapping the
 * submitted runnable.
 */
public final class BreakerGuardedPool implements TaskDispatcher {

    private final TaskDispatcher delegate;
    private final CircuitBreaker breaker;

    public BreakerGuardedPool(TaskDispatcher delegate, CircuitBreaker breaker) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        if (breaker == null) throw new IllegalArgumentException("breaker must not be null");
        this.delegate = delegate;
        this.breaker = breaker;
    }

    @Override
    public String submit(String description, Callable<?> task) {
        return submit(description, Priority.NORMAL, task);
    }

    @Override
    public String submit(String description, Priority priority, Callable<?> task) {
        if (breaker.isOpen()) {
            throw new BreakerOpenException("circuit breaker is open, refusing " + description);
        }
        return delegate.submit(description, priority, wrapWithBreakerTracking(task));
    }

    private Callable<?> wrapWithBreakerTracking(Callable<?> task) {
        return () -> {
            try {
                Object result = task.call();
                breaker.recordSuccess();
                return result;
            } catch (Exception e) {
                breaker.recordFailure();
                throw e;
            }
        };
    }

    public void recordSuccess() { breaker.recordSuccess(); }
    public void recordFailure() { breaker.recordFailure(); }
    public CircuitBreaker breaker() { return breaker; }

    @Override public boolean cancel(String taskId) { return delegate.cancel(taskId); }
    @Override public int pendingCount() { return delegate.pendingCount(); }
    @Override public int runningCount() { return delegate.runningCount(); }
    @Override public boolean awaitIdle(long timeoutMs) throws InterruptedException {
        return delegate.awaitIdle(timeoutMs);
    }
    @Override public List<String> recentTaskIds() { return delegate.recentTaskIds(); }

    public static class BreakerOpenException extends RuntimeException {
        public BreakerOpenException(String msg) { super(msg); }
    }
}
