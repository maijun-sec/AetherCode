package org.aethercode.hooks;

import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class HookRegistryTest {

    @Test
    void emptyRegistryAllows() {
        HookRegistry r = new HookRegistry();
        Hook.Outcome o = r.runAll(Hook.Kind.PRE_TOOL_USE,
                Hook.HookContext.forPre("s", "bash", Map.of("command", "ls"))).join();
        assertThat(o).isInstanceOf(Hook.Outcome.Continue.class);
    }

    @Test
    void blockHaltsChain() {
        HookRegistry r = new HookRegistry();
        r.register(new Hook() {
            public Kind kind() { return Kind.PRE_TOOL_USE; }
            public CompletableFuture<Outcome> run(HookContext ctx) {
                return CompletableFuture.completedFuture(new Outcome.Block("nope"));
            }
        });
        r.register(new Hook() {
            public Kind kind() { return Kind.PRE_TOOL_USE; }
            public CompletableFuture<Outcome> run(HookContext ctx) {
                throw new RuntimeException("should not reach");
            }
        });
        Hook.Outcome o = r.runAll(Hook.Kind.PRE_TOOL_USE,
                Hook.HookContext.forPre("s", "bash", Map.of())).join();
        assertThat(o).isInstanceOf(Hook.Outcome.Block.class);
    }

    @Test
    void onlyMatchingKindFires() {
        HookRegistry r = new HookRegistry();
        r.register(new Hook() {
            public Kind kind() { return Kind.POST_TOOL_USE; }
            public CompletableFuture<Outcome> run(HookContext ctx) {
                throw new RuntimeException("should not run for pre");
            }
        });
        Hook.Outcome o = r.runAll(Hook.Kind.PRE_TOOL_USE,
                Hook.HookContext.forPre("s", "bash", Map.of())).join();
        assertThat(o).isInstanceOf(Hook.Outcome.Continue.class);
    }
}
