package org.aethercode.protocol.permissions;

import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.protocol.methods.AetherCodeMethods;
import org.aethercode.protocol.methods.AetherCodeMethods.PermissionDecision;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * daemon-side short-circuit of low-risk tool calls.
 *
 * <p>legacy, every low-risk ask went out to the renderer as
 * a {@code permission_request} notification, even when the
 * tool was a read-only glob/grep that the user had already
 * accepted the broad behaviour of. R120 lets the user enable
 * {@code autoApproveLowRisk} (default {@code true}) so the
 * prompter returns {@link PermissionResult.Allow} immediately,
 * increments the daemon's cumulative counter, and emits a
 * {@code permission_auto_approved} notification so the UI can
 * show a badge.
 *
 * <p>The tests use a {@link RecordingMethods} fake (an
 * {@link AetherCodeMethods} subclass) so the suite avoids
 * Mockito — Java 25 + Mockito 5.12's inline mockmaker cannot
 * attach the bytecode transformer on this JVM, and the
 * existing project doesn't use Mockito anywhere else. The
 * fake overrides the four methods the prompter touches
 * (isAutoApproveLowRisk, getAutoApprovedCount,
 * recordAutoApproved, askPermission) and counts the
 * interactions.
 */
class JsonRpcPermissionPrompterR120Test {

    /** Minimal {@link Tool} stub for prompter tests. */
    private static Tool tool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> inputSchema() { return Map.of(); }
            @Override public CompletableFuture<PermissionResult> checkPermissions(
                    Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(new PermissionResult.Allow(input));
            }
            @Override public CompletableFuture<Tool.ToolResult> call(
                    Map<String, Object> input, CallContext ctx) {
                return CompletableFuture.completedFuture(Tool.ToolResult.of("ok"));
            }
        };
    }

    /** Counts the four prompter-relevant interactions. */
    static final class RecordingMethods extends AetherCodeMethods {
        private final AtomicReference<Boolean> flag;
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicInteger recordCalls = new AtomicInteger(0);
        private final AtomicInteger askCalls = new AtomicInteger(0);
        private final PermissionDecision decision;
        RecordingMethods(boolean autoApprove, PermissionDecision decision) {
            super(engineFor(), n -> { /* swallow */ });
            this.flag = new AtomicReference<>(autoApprove);
            this.decision = decision;
        }
        private static AetherCodeEngine engineFor() {
            // The prompter never touches engine methods, so a
            // minimal engine (no tools, temp cwd) is enough.
            try {
                Path tmp = java.nio.file.Files.createTempDirectory("r120-test");
                return new AetherCodeEngine.Builder()
                        .cwd(tmp)
                        .tools(List.<org.aethercode.core.tool.Tool>of())
                        .build();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        @Override public boolean isAutoApproveLowRisk() { return flag.get(); }
        void setFlag(boolean v) { flag.set(v); }
        @Override public long getAutoApprovedCount() { return count.get(); }
        @Override public long recordAutoApproved(String toolName, Map<String, Object> input, String reason, String riskLevel) {
            recordCalls.incrementAndGet();
            long n = count.incrementAndGet();
            // emit the notification like the real impl does
            try {
                java.lang.reflect.Method m = AetherCodeMethods.class
                        .getDeclaredMethod("notifyCustom", String.class, Map.class);
                m.setAccessible(true);
                m.invoke(this, "permission_auto_approved", Map.of(
                        "requestId", "auto-" + java.util.UUID.randomUUID(),
                        "tool", toolName,
                        "input", input,
                        "reason", reason,
                        "riskLevel", riskLevel,
                        "atMs", System.currentTimeMillis(),
                        "autoApprovedCount", n,
                        "autoApprovedElevatedCount", 0L));
            } catch (Exception ignore) {
                // The fake's notifier swallows anyway; OK to ignore.
            }
            return n;
        }
        @Override public CompletableFuture<PermissionDecision> askPermission(
                String runId, String toolName, Map<String, Object> input,
                String question, String riskLevel) {
            askCalls.incrementAndGet();
            return CompletableFuture.completedFuture(decision);
        }
        int recordCalls() { return recordCalls.get(); }
        int askCalls() { return askCalls.get(); }
    }

    @Test
    void lowRisk_autoApproveTrue_returnsAllowWithoutAsking() throws Exception {
        // file_read is classified "low". With autoApprove=true,
        // ask() should return Allow immediately, never call
        // askPermission, and record the auto-approval.
        RecordingMethods m = new RecordingMethods(true,
                new PermissionDecision(AetherCodeMethods.DECISION_ALLOW, "ok"));
        JsonRpcPermissionPrompter p = new JsonRpcPermissionPrompter(m);

        Tool t = tool("file_read");
        Map<String, Object> input = Map.of("path", "/tmp/a");
        PermissionResult r = p.ask(t, input, "read foo").get();

        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        assertThat(m.askCalls()).isZero();
        assertThat(m.recordCalls()).isEqualTo(1);
        assertThat(m.getAutoApprovedCount()).isEqualTo(1L);
    }

    @Test
    void lowRisk_autoApproveFalse_fallsThroughToAsker() throws Exception {
        // The user has opted out. The prompter must NOT
        // short-circuit, even though the call is low-risk.
        RecordingMethods m = new RecordingMethods(false,
                new PermissionDecision(AetherCodeMethods.DECISION_ALLOW, "ok"));
        JsonRpcPermissionPrompter p = new JsonRpcPermissionPrompter(m);

        Tool t = tool("file_read");
        Map<String, Object> input = Map.of("path", "/tmp/a");
        PermissionResult r = p.ask(t, input, "read foo").get();

        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        assertThat(m.askCalls()).isEqualTo(1);
        assertThat(m.recordCalls()).isZero();
    }

    @Test
    void highRisk_bypassesShortCircuit() throws Exception {
        // bash is classified "high". Even with autoApprove=true,
        // the prompter must NOT short-circuit — the user still
        // sees a prompt.
        RecordingMethods m = new RecordingMethods(true,
                new PermissionDecision(AetherCodeMethods.DECISION_DENY, "nope"));
        JsonRpcPermissionPrompter p = new JsonRpcPermissionPrompter(m);

        Tool t = tool("bash");
        Map<String, Object> input = Map.of("command", "ls -la");
        PermissionResult r = p.ask(t, input, "run").get();

        assertThat(r).isInstanceOf(PermissionResult.Deny.class);
        assertThat(m.askCalls()).isEqualTo(1);
        assertThat(m.recordCalls()).isZero();
    }

    @Test
    void criticalRisk_bypassesShortCircuit() throws Exception {
        // bash + rm -rf is "critical". Bypass even with
        // autoApprove=true — short-circuit is for read-only.
        RecordingMethods m = new RecordingMethods(true,
                new PermissionDecision(AetherCodeMethods.DECISION_DENY, "no"));
        JsonRpcPermissionPrompter p = new JsonRpcPermissionPrompter(m);

        Tool t = tool("bash");
        Map<String, Object> input = Map.of("command", "rm -rf /");
        PermissionResult r = p.ask(t, input, "run").get();

        assertThat(r).isInstanceOf(PermissionResult.Deny.class);
        assertThat(m.recordCalls()).isZero();
    }

    @Test
    void lowRisk_toolsAreExercised() {
        // Pin the classification: every low-risk tool the
        // R120 doc lists (read / list / glob / grep / search /
        // stat / get) must classify as "low" so the
        // short-circuit catches them.
        for (String n : new String[]{
                "file_read", "list_dir", "glob_files", "grep", "code_search",
                "file_stat", "get_metadata", "search_files"}) {
            assertThat(JsonRpcPermissionPrompter.classifyRisk(n, Map.of()))
                    .as("%s should classify as low", n)
                    .isEqualTo("low");
        }
    }

    @Test
    void autoApprove_canBeToggledAtRuntime() throws Exception {
        // Same methods instance, two asks with the flag
        // flipped between them. The volatile read on each
        // ask means the second ask sees the new value
        // without rebuilding the prompter.
        RecordingMethods m = new RecordingMethods(true,
                new PermissionDecision(AetherCodeMethods.DECISION_DENY, "no"));
        JsonRpcPermissionPrompter p = new JsonRpcPermissionPrompter(m);
        Tool t = tool("file_read");

        PermissionResult r1 = p.ask(t, Map.of(), "first").get();
        assertThat(r1).isInstanceOf(PermissionResult.Allow.class);

        // Flip flag — second ask goes through the asker.
        m.setFlag(false);
        PermissionResult r2 = p.ask(t, Map.of(), "second").get();
        assertThat(r2).isInstanceOf(PermissionResult.Deny.class);
        assertThat(m.askCalls()).isEqualTo(1);
        assertThat(m.recordCalls()).isEqualTo(1);
    }

    @Test
    void methodsIsNull_stillDenies() throws Exception {
        // The pre-existing null-guard: a prompter built
        // without methods (test fixture) must Deny
        // without NPE. R120 must not regress this.
        JsonRpcPermissionPrompter p = new JsonRpcPermissionPrompter(null, 1_000L);
        Tool t = tool("file_read");
        PermissionResult r = p.ask(t, Map.of(), "?").get();
        assertThat(r).isNotNull();
        assertThat(r).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    void recordAutoApproved_emitsNotification() {
        // The short-circuit path emits a notification via
        // the notifier wired in the constructor. The fake
        // counts via recordCalls(); the real notification
        // emission is covered by AetherCodeMethodsR120Test.
        RecordingMethods m = new RecordingMethods(true,
                new PermissionDecision(AetherCodeMethods.DECISION_ALLOW, "ok"));
        JsonRpcPermissionPrompter p = new JsonRpcPermissionPrompter(m);
        Tool t = tool("file_read");
        p.ask(t, Map.of("path", "/tmp/a"), "read").join();
        assertThat(m.recordCalls()).isEqualTo(1);
    }

    @Test
    void consecutiveAutoApproves_incrementCounter() throws Exception {
        // Three reads in a row should yield count=3 — no
        // counter that resets between calls.
        RecordingMethods m = new RecordingMethods(true,
                new PermissionDecision(AetherCodeMethods.DECISION_ALLOW, "ok"));
        JsonRpcPermissionPrompter p = new JsonRpcPermissionPrompter(m);
        Tool t = tool("file_read");
        p.ask(t, Map.of(), "1").get();
        p.ask(t, Map.of(), "2").get();
        p.ask(t, Map.of(), "3").get();
        assertThat(m.recordCalls()).isEqualTo(3);
        assertThat(m.getAutoApprovedCount()).isEqualTo(3L);
    }
}
