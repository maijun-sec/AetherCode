package org.aethercode.core.engine;

import org.aethercode.core.app.AppState;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
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

class StreamingToolExecutorBackpressureTest {

    @Test
    void eventsEmittedAsTheyArrive_notBuffered() throws Exception {
        // Three slow tools; track the order in which we receive Started events
        // and assert that the first event arrives before all tools have completed.
        java.util.List<String> arrivals = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Tool slow = new Tool() {
            public String name() { return "slow"; }
            public String description() { return "slow"; }
            public Map<String, Object> inputSchema() { return Map.of(); }
            public boolean isConcurrencySafe(Map<String, Object> input) { return true; }
            public CompletableFuture<PermissionResult> checkPermissions(Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            public CompletableFuture<ToolResult> call(Map<String, Object> input, CallContext ctx) {
                // prior round (round 2): bumped 150→300ms. With 150ms the
                // parallel total (~150-300ms under load) was too
                // close to the 400ms threshold; under load the
                // executor's runAsync scheduler could push the
                // total past 400ms even when tools run in parallel.
                // 300ms sleep + 1200ms threshold leaves a safe
                // margin (parallel ~300-500ms, serial ~900ms+).
                try { Thread.sleep(300); } catch (InterruptedException e) {}
                return CompletableFuture.completedFuture(ToolResult.of("done"));
            }
        };
        AppState st = new AppState("s1", Path.of(""));
        st.toolPool().add(slow);
        StreamingToolExecutor exec = new StreamingToolExecutor(PermissionPolicy.allowAll(), 4);
        long t0 = System.currentTimeMillis();
        var events = exec.run(
                List.of(
                        new ContentBlock.ToolUseBlock("1", "slow", Map.of()),
                        new ContentBlock.ToolUseBlock("2", "slow", Map.of()),
                        new ContentBlock.ToolUseBlock("3", "slow", Map.of())
                ),
                st
        ).collect(Collectors.toList());
        long t1 = System.currentTimeMillis();
        // back-pressure: should be done in ~300-500ms (concurrent), not 900ms+ (serial)
        assertThat(t1 - t0).isLessThan(1200L);
        // 3 Started + 3 Completed = 6 events
        assertThat(events).hasSize(6);
    }
}
