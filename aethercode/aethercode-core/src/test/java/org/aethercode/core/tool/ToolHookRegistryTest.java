package org.aethercode.core.tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.aethercode.core.tool.ToolHook.Context;
import org.aethercode.core.tool.ToolHook.Result;
import org.aethercode.core.tool.ToolHookRegistry.HookPreOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolHookRegistryTest {

    @Test
    void emptyRegistry_passesThrough() {
        ToolHookRegistry r = new ToolHookRegistry();
        HookPreOutcome o = r.runPre("Read", Map.of("file", "/a"));
        assertNull(o.denial());
        assertEquals("/a", o.context().input().get("file"));
        Result post = r.runPost(o.context(), Result.ok("data"));
        assertEquals("data", post.output());
    }

    @Test
    void pre_mutatesInput() {
        ToolHookRegistry r = new ToolHookRegistry();
        r.add(ToolHookRegistry.transformInput(m -> Map.of("file", "/normalized/" + m.get("file"))));
        HookPreOutcome o = r.runPre("Read", Map.of("file", "x"));
        assertEquals("/normalized/x", o.context().input().get("file"));
    }

    @Test
    void deny_shortCircuits() {
        ToolHookRegistry r = new ToolHookRegistry();
        r.add(ToolHookRegistry.denyIf(ctx -> ctx.toolName().equals("Bash"),
                ctx -> Result.error("denied by policy")));
        HookPreOutcome o = r.runPre("Bash", Map.of("cmd", "rm -rf /"));
        assertNotNull(o.denial());
        assertEquals("denied by policy", o.denial().output());
    }

    @Test
    void post_transformsResult() {
        ToolHookRegistry r = new ToolHookRegistry();
        r.add(new ToolHook() {
            @Override public Result post(Context ctx, Result result) {
                return Result.ok(result.output() + " [audit]");
            }
        });
        Context ctx = new Context("Read", Map.of("file", "/a"), null);
        Result out = r.runPost(ctx, Result.ok("hello"));
        assertEquals("hello [audit]", out.output());
    }

    @Test
    void post_runsInReverseOrder() {
        ToolHookRegistry r = new ToolHookRegistry();
        AtomicReference<String> log = new AtomicReference<>("");
        r.add(new ToolHook() {
            @Override public Result post(Context ctx, Result result) {
                log.set(log.get() + "A(" + result.output() + ")");
                return Result.ok("a");
            }
        });
        r.add(new ToolHook() {
            @Override public Result post(Context ctx, Result result) {
                log.set(log.get() + "B(" + result.output() + ")");
                return Result.ok("b");
            }
        });
        r.add(new ToolHook() {
            @Override public Result post(Context ctx, Result result) {
                log.set(log.get() + "C(" + result.output() + ")");
                return Result.ok("c");
            }
        });
        Context ctx = new Context("Read", Map.of(), null);
        Result out = r.runPost(ctx, Result.ok("orig"));
        // Reverse: C sees orig -> c, B sees c -> b, A sees b -> a. Final = a.
        assertEquals("a", out.output());
        assertEquals("C(orig)B(c)A(b)", log.get());
    }

    @Test
    void toolFilter_skipsNonMatching() {
        ToolHookRegistry r = new ToolHookRegistry();
        AtomicInteger calls = new AtomicInteger();
        r.add(new ToolHook() {
            @Override public List<String> toolFilter() { return List.of("Read"); }
            @Override public Result post(Context ctx, Result result) {
                calls.incrementAndGet();
                return result;
            }
        });
        r.runPost(new Context("Bash", Map.of(), null), Result.ok("x"));
        r.runPost(new Context("Read", Map.of(), null), Result.ok("x"));
        assertEquals(1, calls.get());
    }

    @Test
    void multipleHooks_compose() {
        ToolHookRegistry r = new ToolHookRegistry();
        r.add(new ToolHook() {
            @Override public Context pre(Context ctx) {
                return new Context(ctx.toolName(), ctx.input(), ctx.attributes());
            }
        });
        r.add(new ToolHook() {
            @Override public Context pre(Context ctx) {
                Map<String, Object> augmented = new java.util.HashMap<>(ctx.input());
                augmented.put("augmented", true);
                return new Context(ctx.toolName(), augmented, ctx.attributes());
            }
        });
        HookPreOutcome o = r.runPre("Read", Map.of("file", "x"));
        assertEquals(true, o.context().input().get("augmented"));
        assertEquals("x", o.context().input().get("file"));
    }

    @Test
    void denyHook_respectsFilter() {
        ToolHookRegistry r = new ToolHookRegistry();
        r.add(new ToolHook() {
            @Override public List<String> toolFilter() { return List.of("Bash"); }
            @Override public Result deny(Context ctx) { return Result.error("nope"); }
        });
        HookPreOutcome o1 = r.runPre("Read", Map.of());
        assertNull(o1.denial());
        HookPreOutcome o2 = r.runPre("Bash", Map.of());
        assertNotNull(o2.denial());
    }

    @Test
    void clear_removesAll() {
        ToolHookRegistry r = new ToolHookRegistry();
        r.add(new ToolHook() {});
        r.add(new ToolHook() {});
        r.clear();
        assertEquals(0, r.size());
    }

    @Test
    void addAll_addsAll() {
        ToolHookRegistry r = new ToolHookRegistry();
        java.util.ArrayList<ToolHook> hooks = new java.util.ArrayList<>();
        hooks.add(new ToolHook() {});
        hooks.add(new ToolHook() {});
        hooks.add(null);
        r.addAll(hooks);
        assertEquals(2, r.size()); // null skipped
    }

    @Test
    void pre_attributesCanBeSharedBetweenHooks() {
        ToolHookRegistry r = new ToolHookRegistry();
        r.add(new ToolHook() {
            @Override public Context pre(Context ctx) {
                ctx.attributes().put("first", 1);
                return ctx;
            }
        });
        r.add(new ToolHook() {
            @Override public Context pre(Context ctx) {
                ctx.attributes().put("second", ctx.attributes().get("first"));
                return ctx;
            }
        });
        HookPreOutcome o = r.runPre("Read", Map.of());
        assertEquals(1, o.context().attributes().get("first"));
        assertEquals(1, o.context().attributes().get("second"));
    }

    @Test
    void denyIf_helper() {
        ToolHookRegistry r = new ToolHookRegistry();
        r.add(ToolHookRegistry.denyIf(ctx -> true, ctx -> Result.error("always")));
        HookPreOutcome o = r.runPre("Read", Map.of());
        assertNotNull(o.denial());
    }

    @Test
    void add_nullIgnored() {
        ToolHookRegistry r = new ToolHookRegistry();
        r.add(null);
        assertEquals(0, r.size());
    }

    @Test
    void hooks_isImmutableSnapshot() {
        ToolHookRegistry r = new ToolHookRegistry();
        r.add(new ToolHook() {});
        assertEquals(1, r.hooks().size());
        assertThrows(UnsupportedOperationException.class, () -> r.hooks().add(new ToolHook() {}));
    }
}
