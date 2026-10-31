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

import org.aethercode.core.tool.ToolResultCache;
import org.aethercode.tasks.Task;

import java.util.Optional;

/**
 * a {@link PlanExecutor.StepExecutor} decorator that
 * short-circuits a step when the tool result is already in the
 * cache. Useful for plans with repeated tool calls — e.g.
 * "read file X" appears as a step in two plans, and the second
 * plan can skip the I/O if the cache is still warm.
 *
 * <p>Cache key format: {@code "<stepIndex>:<title>"}. The step
 * index is included so different positions in the same plan
 * don't collide. The caller is responsible for putting the
 * first result into the cache; the decorator never writes.
 */
public final class CachedStepExecutor implements PlanExecutor.StepExecutor {

    private final PlanExecutor.StepExecutor delegate;
    private final ToolResultCache cache;

    public CachedStepExecutor(PlanExecutor.StepExecutor delegate, ToolResultCache cache) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        if (cache == null) throw new IllegalArgumentException("cache must not be null");
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public String execute(int stepIndex, String title, Task task) throws Exception {
        String key = keyFor(stepIndex, title);
        Optional<String> hit = cache.get(key);
        if (hit.isPresent()) {
            return "[cached] " + hit.get();
        }
        return delegate.execute(stepIndex, title, task);
    }

    /** check whether a given step would be a cache hit,
     *  without actually invoking the delegate. Useful for plan
     *  preview UIs that want to show "(cached)" badges. */
    public boolean wouldHit(int stepIndex, String title) {
        return cache.containsKey(keyFor(stepIndex, title));
    }

    /** derive the cache key for a step. Public so callers
     *  can pre-populate the cache before a plan starts. */
    public static String keyFor(int stepIndex, String title) {
        return stepIndex + ":" + (title == null ? "" : title);
    }
}
