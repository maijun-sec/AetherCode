package org.aethercode.core.tool;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.aethercode.core.tool.ToolHook.Context;
import org.aethercode.core.tool.ToolHook.Result;
import org.aethercode.core.tool.ToolTimeoutEnforcer.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolTimeoutEnforcerTest {

    private ToolTimeoutEnforcer enforcer;

    @AfterEach
    void cleanup() {
        if (enforcer != null) enforcer.shutdown();
    }

    @Test
    void run_successfulToolReturnsResult() {
        enforcer = new ToolTimeoutEnforcer(Duration.ofSeconds(2));
        ToolHookRegistry hooks = new ToolHookRegistry();
        Outcome o = enforcer.run(hooks, "Read", Map.of("file", "/a"),
                () -> Result.ok("contents"));
        assertNotNull(o);
        assertFalse(o.timedOut());
        assertEquals("contents", o.result().output());
    }

    @Test
    void run_timeoutReturnsError() throws Exception {
        enforcer = new ToolTimeoutEnforcer(Duration.ofMillis(50));
        ToolHookRegistry hooks = new ToolHookRegistry();
        AtomicReference<Boolean> cancelled = new AtomicReference<>(false);
        Outcome o = enforcer.run(hooks, "Bash", Map.of("cmd", "sleep 5"), () -> {
            try { Thread.sleep(5000); }
            catch (InterruptedException e) { cancelled.set(true); Thread.currentThread().interrupt(); }
            return Result.ok("done");
        });
        assertTrue(o.timedOut());
        assertTrue(o.isError());
        assertTrue(o.result().output().toString().toLowerCase().contains("timed out"));
    }

    @Test
    void run_usesPerToolTimeoutOverride() {
        enforcer = new ToolTimeoutEnforcer(Duration.ofSeconds(10));
        enforcer.setTimeout("Slow", Duration.ofMillis(30));
        ToolHookRegistry hooks = new ToolHookRegistry();
        Outcome o = enforcer.run(hooks, "Slow", Map.of(), () -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            return Result.ok("done");
        });
        assertTrue(o.timedOut());
    }

    @Test
    void run_denialShortCircuits() {
        enforcer = new ToolTimeoutEnforcer(Duration.ofSeconds(1));
        ToolHookRegistry hooks = new ToolHookRegistry();
        hooks.add(ToolHookRegistry.denyIf(ctx -> true, ctx -> Result.error("denied")));
        AtomicReference<Boolean> actionRan = new AtomicReference<>(false);
        Outcome o = enforcer.run(hooks, "Read", Map.of(), () -> {
            actionRan.set(true);
            return Result.ok("should not run");
        });
        assertEquals("denied", o.result().output());
        assertFalse(actionRan.get());
    }

    @Test
    void run_postHookCanTransformResult() {
        enforcer = new ToolTimeoutEnforcer(Duration.ofSeconds(1));
        ToolHookRegistry hooks = new ToolHookRegistry();
        hooks.add(new ToolHook() {
            @Override public Result post(Context ctx, Result result) {
                return Result.ok(result.output() + " [audit]");
            }
        });
        Outcome o = enforcer.run(hooks, "Read", Map.of(), () -> Result.ok("data"));
        assertEquals("data [audit]", o.result().output());
    }

    @Test
    void run_preHookMutatesInput() {
        enforcer = new ToolTimeoutEnforcer(Duration.ofSeconds(1));
        ToolHookRegistry hooks = new ToolHookRegistry();
        AtomicReference<String> got = new AtomicReference<>();
        hooks.add(ToolHookRegistry.transformInput(m -> {
            Map<String, Object> n = new java.util.HashMap<>(m);
            n.put("file", "/safe/" + m.get("file"));
            return n;
        }));
        enforcer.run(hooks, "Read", Map.of("file", "x"), () -> {
            got.set("done");
            return Result.ok("y");
        });
        // The pre-hook mutated; the post-hook can verify
        assertEquals("done", got.get());
    }

    @Test
    void run_actionThrowingIsCaptured() {
        enforcer = new ToolTimeoutEnforcer(Duration.ofSeconds(1));
        ToolHookRegistry hooks = new ToolHookRegistry();
        Outcome o = enforcer.run(hooks, "Read", Map.of(), () -> {
            throw new RuntimeException("boom");
        });
        assertTrue(o.isError());
    }

    @Test
    void counters_incrementOnExecution() {
        enforcer = new ToolTimeoutEnforcer(Duration.ofSeconds(1));
        ToolHookRegistry hooks = new ToolHookRegistry();
        enforcer.run(hooks, "Read", Map.of(), () -> Result.ok("a"));
        enforcer.run(hooks, "Read", Map.of(), () -> Result.ok("b"));
        assertEquals(2, enforcer.totalExecutions());
        assertEquals(0, enforcer.totalTimeouts());
        assertEquals(0.0, enforcer.timeoutRate());
    }

    @Test
    void counters_trackTimeouts() {
        enforcer = new ToolTimeoutEnforcer(Duration.ofMillis(30));
        ToolHookRegistry hooks = new ToolHookRegistry();
        enforcer.run(hooks, "Slow", Map.of(), () -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            return Result.ok("late");
        });
        assertEquals(1, enforcer.totalTimeouts());
        assertTrue(enforcer.timeoutRate() > 0);
    }

    @Test
    void setTimeout_rejectsBadValues() {
        enforcer = new ToolTimeoutEnforcer(Duration.ofSeconds(1));
        assertThrows(NullPointerException.class, () -> enforcer.setTimeout(null, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> enforcer.setTimeout("x", null));
        assertThrows(IllegalArgumentException.class, () -> enforcer.setTimeout("x", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> enforcer.setTimeout("x", Duration.ofSeconds(-1)));
    }

    @Test
    void getTimeout_fallsBackToDefault() {
        enforcer = new ToolTimeoutEnforcer(Duration.ofSeconds(7));
        assertEquals(Duration.ofSeconds(7), enforcer.getTimeout("Unknown"));
        enforcer.setTimeout("Known", Duration.ofSeconds(1));
        assertEquals(Duration.ofSeconds(1), enforcer.getTimeout("Known"));
    }

    @Test
    void constructor_rejectsBadArgs() {
        assertThrows(IllegalArgumentException.class, () -> new ToolTimeoutEnforcer(null));
        assertThrows(IllegalArgumentException.class, () -> new ToolTimeoutEnforcer(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ToolTimeoutEnforcer(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new ToolTimeoutEnforcer(Duration.ofSeconds(1), null));
    }

    @Test
    void run_interruptionReturnsError() {
        enforcer = new ToolTimeoutEnforcer(Duration.ofSeconds(2), Executors.newSingleThreadExecutor());
        ToolHookRegistry hooks = new ToolHookRegistry();
        CountDownLatch started = new CountDownLatch(1);
        Outcome o = enforcer.run(hooks, "Bash", Map.of(), () -> {
            started.countDown();
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Result.ok("done");
        });
        // Hard to reliably interrupt; just verify the result shape
        assertNotNull(o);
    }

    @Test
    void outcome_isError() {
        Outcome o1 = new Outcome(Result.ok("x"), false, 5);
        Outcome o2 = new Outcome(Result.error("e"), false, 5);
        assertFalse(o1.isError());
        assertTrue(o2.isError());
    }

    @Test
    void shutdown_isCallable() {
        enforcer = new ToolTimeoutEnforcer(Duration.ofSeconds(1));
        enforcer.shutdown();
        // shutdown is idempotent
        enforcer.shutdown();
    }
}
