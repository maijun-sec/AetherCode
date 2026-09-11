package org.aethercode.sdk;

import org.aethercode.core.tool.ToolResultCache;
import org.aethercode.tasks.Task;
import org.aethercode.tasks.TaskRegistry;
import org.aethercode.tasks.TaskStatus;
import org.aethercode.tasks.TaskType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link CachedStepExecutor}. Verifies cache
 * hit short-circuits the delegate, and cache miss passes through.
 */
class CachedStepExecutorTest {

    @Test
    void cacheHit_skipsDelegate() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        PlanExecutor.StepExecutor delegate = (idx, title, task) -> {
            callCount.incrementAndGet();
            return "fresh-result-" + idx;
        };
        ToolResultCache cache = new ToolResultCache(10, 60_000L);
        String key = CachedStepExecutor.keyFor(0, "readme");
        cache.put(key, "cached-value");

        CachedStepExecutor cse = new CachedStepExecutor(delegate, cache);
        TaskRegistry reg = TaskRegistry.instance();
        Task t = reg.create(TaskType.WORKFLOW, "readme", null);
        reg.updateStatus(t.id(), TaskStatus.RUNNING);

        String result = cse.execute(0, "readme", t);
        assertEquals("[cached] cached-value", result);
        assertEquals(0, callCount.get(), "delegate should not be called on cache hit");
    }

    @Test
    void cacheMiss_callsDelegate() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        PlanExecutor.StepExecutor delegate = (idx, title, task) -> {
            callCount.incrementAndGet();
            return "computed-" + idx;
        };
        ToolResultCache cache = new ToolResultCache(10, 60_000L);
        CachedStepExecutor cse = new CachedStepExecutor(delegate, cache);

        TaskRegistry reg = TaskRegistry.instance();
        Task t = reg.create(TaskType.WORKFLOW, "first", null);
        reg.updateStatus(t.id(), TaskStatus.RUNNING);

        String result = cse.execute(0, "first", t);
        assertEquals("computed-0", result);
        assertEquals(1, callCount.get());
    }

    @Test
    void differentStepsHaveDifferentKeys() {
        // Same title, different stepIndex → different keys.
        assertNotEquals(
                CachedStepExecutor.keyFor(0, "readme"),
                CachedStepExecutor.keyFor(1, "readme"));
        // Different title, same stepIndex → different keys.
        assertNotEquals(
                CachedStepExecutor.keyFor(0, "readme"),
                CachedStepExecutor.keyFor(0, "todo"));
    }

    // wouldHit preview

    @Test
    void wouldHit_returnsTrueWhenCached() {
        ToolResultCache cache = new ToolResultCache(10, 60_000L);
        CachedStepExecutor cse = new CachedStepExecutor((idx, t, task) -> "x", cache);
        assertFalse(cse.wouldHit(0, "step"));
        cache.put(CachedStepExecutor.keyFor(0, "step"), "value");
        assertTrue(cse.wouldHit(0, "step"));
    }
}
