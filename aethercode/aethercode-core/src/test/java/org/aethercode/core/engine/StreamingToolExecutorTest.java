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

    /**
     * R266h: when the model emits a tool_use with
     * missing required parameters, the executor must
     * reject it pre-invoke (no side effect, no
     * unnecessary timeout) with a precise error message
     * that names the missing field and shows the
     * expected JSON shape. The tool's {@code call()}
     * function must not be invoked at all.
     */
    @Test
    void missingRequiredParamsRejectedPreInvoke() {
        AtomicInteger ran = new AtomicInteger();
        // schema requires "command" (a string)
        java.util.LinkedHashMap<String, Map<String, Object>> props = new java.util.LinkedHashMap<>();
        props.put("command", Tools.stringProp("the shell command to run"));
        Map<String, Object> schema = Tools.objectSchema(props, "command");
        Tool bash = Tools.build(new ToolDef("bash", "run a shell command", schema,
                (in, ctx) -> {
                    ran.incrementAndGet();
                    return CompletableFuture.completedFuture(Tool.ToolResult.of("ok"));
                }));
        AppState st = new AppState("s1", Path.of(""));
        st.toolPool().add(bash);
        StreamingToolExecutor exec = new StreamingToolExecutor(PermissionPolicy.allowAll(), 4);
        // empty input map — the v0.2.66 desktop
        // transcript showed the model emitting exactly
        // this 20+ times in a row.
        var events = exec.run(
                List.of(new ContentBlock.ToolUseBlock("call_1", "bash", Map.of())),
                st
        ).collect(Collectors.toList());
        // the tool's call() must NOT have run.
        assertThat(ran.get()).as("call() must not run when required params are missing").isZero();
        // an Event.Completed must surface with the
        // precise error message: name the missing
        // field, show the JSON shape.
        assertThat(events).anyMatch(e -> e instanceof StreamingToolExecutor.Event.Completed
                && ((StreamingToolExecutor.Event.Completed) e).isError());
        String errMsg = events.stream()
                .filter(e -> e instanceof StreamingToolExecutor.Event.Completed
                        && ((StreamingToolExecutor.Event.Completed) e).isError())
                .map(e -> String.valueOf(((StreamingToolExecutor.Event.Completed) e).output()))
                .findFirst().orElse("");
        assertThat(errMsg).contains("bash");
        assertThat(errMsg).contains("command");
        assertThat(errMsg).contains("expected tool_use shape");
        assertThat(errMsg).contains("\"name\":\"bash\"");
        // the placeholders line must show the shape
        // the model needs to emit (string "<value>").
        assertThat(errMsg).contains("\"command\":\"<value>\"");
    }

    /**
     * R266h: when the model emits a tool_use WITH all
     * required parameters filled in, the tool's
     * {@code call()} must run as normal. The
     * missing-required check must not false-positive
     * on a legitimate call.
     */
    @Test
    void validInputStillReachesTool() {
        AtomicInteger ran = new AtomicInteger();
        java.util.LinkedHashMap<String, Map<String, Object>> props = new java.util.LinkedHashMap<>();
        props.put("command", Tools.stringProp("the shell command to run"));
        Map<String, Object> schema = Tools.objectSchema(props, "command");
        Tool bash = Tools.build(new ToolDef("bash", "run a shell command", schema,
                (in, ctx) -> {
                    ran.incrementAndGet();
                    return CompletableFuture.completedFuture(Tool.ToolResult.of("ran " + in.get("command")));
                }));
        AppState st = new AppState("s1", Path.of(""));
        st.toolPool().add(bash);
        StreamingToolExecutor exec = new StreamingToolExecutor(PermissionPolicy.allowAll(), 4);
        var events = exec.run(
                List.of(new ContentBlock.ToolUseBlock("call_1", "bash",
                        Map.of("command", "dir"))),
                st
        ).collect(Collectors.toList());
        assertThat(ran.get()).as("call() must run when required params are present").isEqualTo(1);
        assertThat(events).anyMatch(e -> e instanceof StreamingToolExecutor.Event.Completed
                && !((StreamingToolExecutor.Event.Completed) e).isError()
                && ((StreamingToolExecutor.Event.Completed) e).output().toString().contains("ran dir"));
    }
}
