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
