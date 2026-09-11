package org.aethercode.core.engine;

import org.aethercode.core.app.AppState;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingToolExecutorTest {

    @Test
    void concurrentBatchAbortsSiblingsOnFailure() {
        Tool ok = Tools.build(new ToolDef("ok", "ok", Map.of(), (in, ctx) -> {
            try { Thread.sleep(50); } catch (Exception e) {}
            return CompletableFuture.completedFuture(Tool.ToolResult.of("ok"));
        }));
        // override to concurrency-safe
        Tool safeOk = new Tool() {
            public String name() { return "ok"; }
            public String description() { return "ok"; }
            public Map<String, Object> inputSchema() { return Map.of(); }
            public boolean isConcurrencySafe(Map<String, Object> input) { return true; }
            public CompletableFuture<PermissionResult> checkPermissions(Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            public CompletableFuture<ToolResult> call(Map<String, Object> input, CallContext ctx) {
                return ok.call(input, ctx);
            }
        };
        Tool fail = new Tool() {
            public String name() { return "fail"; }
            public String description() { return "fail"; }
            public Map<String, Object> inputSchema() { return Map.of(); }
            public boolean isConcurrencySafe(Map<String, Object> input) { return true; }
            public CompletableFuture<PermissionResult> checkPermissions(Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            public CompletableFuture<ToolResult> call(Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(Tool.ToolResult.error("boom"));
            }
        };
        AppState st = new AppState("s1", Path.of(""));
        st.toolPool().add(safeOk);
        st.toolPool().add(fail);
        StreamingToolExecutor exec = new StreamingToolExecutor(PermissionPolicy.allowAll(), 4);
        var events = exec.run(
                List.of(
                        new ContentBlock.ToolUseBlock("1", "ok", Map.of()),
                        new ContentBlock.ToolUseBlock("2", "fail", Map.of()),
                        new ContentBlock.ToolUseBlock("3", "ok", Map.of())
                ),
                st
        ).collect(Collectors.toList());
        // Three events total: 2 OK completions and 1 failure (or 1 OK + 1 fail + 1 abort = 3)
        long completed = events.stream().filter(e -> e instanceof StreamingToolExecutor.Event.Completed).count();
        assertThat(completed).isGreaterThanOrEqualTo(2);
        // The fail tool's result must be flagged as error
        assertThat(events).anyMatch(e -> e instanceof StreamingToolExecutor.Event.Completed
                && ((StreamingToolExecutor.Event.Completed) e).isError());
    }

    @Test
    void preHookCanBlockTool() {
        AtomicInteger ran = new AtomicInteger();
        Tool t = Tools.build(new ToolDef("t", "t", Map.of(),
                (in, ctx) -> {
                    ran.incrementAndGet();
                    return CompletableFuture.completedFuture(Tool.ToolResult.of("ok"));
                }));
        AppState st = new AppState("s1", Path.of(""));
        st.toolPool().add(t);
        StreamingToolExecutor exec = new StreamingToolExecutor(PermissionPolicy.allowAll(), 4);
        exec.withPreHook((phase, tool, id, input, result, appState) -> false);
        var events = exec.run(
                List.of(new ContentBlock.ToolUseBlock("1", "t", Map.of())),
                st
        ).collect(Collectors.toList());
        assertThat(ran.get()).isZero();
        assertThat(events).anyMatch(e -> e instanceof StreamingToolExecutor.Event.Completed
                && ((StreamingToolExecutor.Event.Completed) e).output().toString().contains("blocked"));
    }
}
