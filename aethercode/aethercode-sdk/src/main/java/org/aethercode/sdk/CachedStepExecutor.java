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
