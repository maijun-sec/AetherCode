package org.aethercode.protocol.permissions;

import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.tool.Tool;
import org.aethercode.protocol.methods.AetherCodeMethods;
import org.aethercode.protocol.methods.AetherCodeMethods.PermissionDecision;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R277 regression tests (2026-09-16): the JsonRpcPermissionPrompter
 * must respect the live {@link PermissionMode} as the source of truth.
 *
 * <p>Background: R268d flipped the daemon's
 * {@code autoApproveMediumHigh} default from {@code false} to
 * {@code true} to fix a batch-workflow idle-freeze (5min permission
 * timeout on every file_write when the user wasn't at the keyboard).
 * Side effect: even when the user picked "主动询问" (= ASK_BEFORE_TOOL
 * / DEFAULT / PLAN) from the dropdown, every medium/high-risk tool
 * call still went through without a prompt, because the flag silently
 * overrode the mode. The user reported "选了 主动询问 但没真询问".
 *
 * <p>R277 fix: the medium/high short-circuit now also checks
 * {@code AetherCodeMethods#isAskMode()} and skips the short-circuit
 * when the mode is an explicit-ask tier. The flag still wins in
 * BYPASS_PERMISSIONS / ACCEPT_EDITS so the batch-workflow safety net
 * (R268d) stays intact — only the explicit-ask modes get the
 * "honour the mode" behaviour.
 */
class JsonRpcPermissionPrompterR277ModeTest {

    private static Tool fakeTool(String name) {
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

    @Test
    void r277_askBeforeTool_modeWithFlagOn_mustStillAsk_mediumRisk() throws Exception {
        // The user's complaint, distilled: mode = "ask", flag = true,
        // medium-risk call → was Allow (R268d bug). Now must be Allow
        // only if the future resolves Allow; in the stub we resolve
        // Allow so the test asserts the ask path was taken (decision
        // consumed), not the auto-approve short-circuit.
        RecordingMethods methods = new RecordingMethods(
                /* autoApproveLowRisk */ false,
                /* autoApproveMediumHigh */ true,
                /* mode */ PermissionMode.ASK_BEFORE_TOOL,
                /* decision */ new PermissionDecision(AetherCodeMethods.DECISION_ALLOW, "user said yes"));

        JsonRpcPermissionPrompter p = new JsonRpcPermissionPrompter(methods, 1000L);
        Tool t = fakeTool("file_write");
        PermissionResult r = p.ask(t, Map.of("file_path", "/tmp/x"), "write?").get(2, TimeUnit.SECONDS);

        // The result reflects the user's Allow (decision-driven), but
        // the prompter MUST have gone through the ask path, NOT the
        // autoApproveMediumHigh short-circuit.
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        assertThat(methods.askPermissionCallCount.get())
                .as("ASK_BEFORE_TOOL mode + flag=true → ask path must fire (not the R268d short-circuit)")
                .isEqualTo(1);
        assertThat(methods.recordAutoApprovedCallCount.get())
                .as("autoApproveMediumHigh short-circuit must NOT fire in ask mode")
                .isZero();
    }

    @Test
    void r277_default_modeWithFlagOn_mustStillAsk_highRisk() throws Exception {
        // Same for DEFAULT mode (the other "ask" tier).
        RecordingMethods methods = new RecordingMethods(
                false,
                true,
                PermissionMode.DEFAULT,
                new PermissionDecision(AetherCodeMethods.DECISION_ALLOW, "ok"));
        JsonRpcPermissionPrompter p = new JsonRpcPermissionPrompter(methods, 1000L);
        Tool t = fakeTool("bash");
        PermissionResult r = p.ask(t, Map.of("command", "ls -la"), "run?").get(2, TimeUnit.SECONDS);
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        assertThat(methods.askPermissionCallCount.get())
                .as("DEFAULT mode + flag=true → ask path must fire")
                .isEqualTo(1);
        assertThat(methods.recordAutoApprovedCallCount.get()).isZero();
    }

    @Test
    void r277_plan_modeWithFlagOn_mustStillAsk() throws Exception {
        RecordingMethods methods = new RecordingMethods(
                false, true,
                PermissionMode.PLAN,
                new PermissionDecision(AetherCodeMethods.DECISION_DENY, "plan declined"));
        JsonRpcPermissionPrompter p = new JsonRpcPermissionPrompter(methods, 1000L);
        Tool t = fakeTool("bash");
        PermissionResult r = p.ask(t, Map.of("command", "echo hi"), "run?").get(2, TimeUnit.SECONDS);
        assertThat(r).isInstanceOf(PermissionResult.Deny.class);
        assertThat(methods.askPermissionCallCount.get())
                .as("PLAN mode + flag=true → ask path must fire")
                .isEqualTo(1);
    }

    @Test
    void r277_bypassMode_flagOn_stillAutoApproves_mediumRisk() throws Exception {
        // Sanity: BYPASS_PERMISSIONS mode + flag=true → still auto-approve
        // (don't regress the R268d batch-workflow safety net).
        RecordingMethods methods = new RecordingMethods(
                false, true,
                PermissionMode.BYPASS_PERMISSIONS,
                new PermissionDecision(AetherCodeMethods.DECISION_DENY, "should not reach"));
        JsonRpcPermissionPrompter p = new JsonRpcPermissionPrompter(methods, 1000L);
        Tool t = fakeTool("file_write");
        PermissionResult r = p.ask(t, Map.of("file_path", "/tmp/y"), "write?").get(2, TimeUnit.SECONDS);
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        assertThat(methods.askPermissionCallCount.get())
                .as("BYPASS mode + flag=true → short-circuit (no ask) is still the intended path")
                .isZero();
        assertThat(methods.recordAutoApprovedCallCount.get())
                .as("and the short-circuit must record the auto-approval")
                .isEqualTo(1);
    }

    @Test
    void r277_bypassMode_flagOff_stillAutoApproves_mediumRisk() throws Exception {
        // Sanity 2: BYPASS mode + flag=false → JsonRpcPermissionPrompter
        // doesn't short-circuit (the flag is what gates the
        // short-circuit). The mode itself is consulted by the
        // upstream ProjectPermissionPolicy; by the time we reach the
        // prompter in BYPASS mode, the engine only sends us
        // mutating calls (the policy already auto-allowed the
        // read-only ones). For an Ask call to reach us at all, the
        // engine explicitly wants a prompt; flag=false means ask.
        RecordingMethods methods = new RecordingMethods(
                false, false,
                PermissionMode.BYPASS_PERMISSIONS,
                new PermissionDecision(AetherCodeMethods.DECISION_ALLOW, "ok"));
        JsonRpcPermissionPrompter p = new JsonRpcPermissionPrompter(methods, 1000L);
        Tool t = fakeTool("file_write");
        p.ask(t, Map.of("file_path", "/tmp/z"), "write?").get(2, TimeUnit.SECONDS);
        assertThat(methods.askPermissionCallCount.get())
                .as("flag=false → ask path always fires, regardless of mode")
                .isEqualTo(1);
    }

    @Test
    void r277_criticalRisk_alwaysAsk_evenWithFlagOn() throws Exception {
        // Safety floor: rm -rf / sudo / mkfs / dd are classified as
        // 'critical' by classifyRisk, and the JsonRpcPermissionPrompter
        // never short-circuits critical risk. R277 keeps that.
        // (The short-circuit guard above checks medium|high, so
        // critical falls through to askPermission.)
        RecordingMethods methods = new RecordingMethods(
                false, true,
                PermissionMode.BYPASS_PERMISSIONS,
                new PermissionDecision(AetherCodeMethods.DECISION_DENY, "denied"));
        JsonRpcPermissionPrompter p = new JsonRpcPermissionPrompter(methods, 1000L);
        Tool t = fakeTool("bash");
        PermissionResult r = p.ask(t, Map.of("command", "rm -rf /tmp/x"), "destruct?").get(2, TimeUnit.SECONDS);
        assertThat(r).isInstanceOf(PermissionResult.Deny.class);
        assertThat(methods.askPermissionCallCount.get())
                .as("critical risk is NEVER auto-approved (rm -rf must ask)")
                .isEqualTo(1);
        assertThat(methods.recordAutoApprovedCallCount.get()).isZero();
    }

    @Test
    void r277_isAskMode_helper_coversAllAskTiers() {
        // Source-pin the helper. A future refactor that drops
        // ASK_BEFORE_TOOL / DEFAULT / PLAN from isAskMode would
        // silently regress this fix; pin the literals.
        for (PermissionMode askMode : new PermissionMode[]{
                PermissionMode.ASK_BEFORE_TOOL, PermissionMode.DEFAULT, PermissionMode.PLAN}) {
            RecordingMethods m = new RecordingMethods(
                    false, false, askMode,
                    new PermissionDecision(AetherCodeMethods.DECISION_ALLOW, "ok"));
            assertThat(m.isAskMode())
                    .as("isAskMode must be true for " + askMode)
                    .isTrue();
        }
        for (PermissionMode notAsk : new PermissionMode[]{
                PermissionMode.BYPASS_PERMISSIONS, PermissionMode.ACCEPT_EDITS,
                PermissionMode.ACCEPT_TASK}) {
            RecordingMethods m = new RecordingMethods(
                    false, false, notAsk,
                    new PermissionDecision(AetherCodeMethods.DECISION_ALLOW, "ok"));
            assertThat(m.isAskMode())
                    .as("isAskMode must be false for " + notAsk)
                    .isFalse();
        }
    }

    /** Stub AetherCodeMethods with controllable flags + mode + a recorded
     *  askPermission response. Mirrors the pattern from
     *  JsonRpcPermissionPrompterR126Test.RecordingMethods but adds
     *  the R277 mode plumbing. The engine is built with
     *  {@code Builder.permissionMode(mode)} so the live
     *  {@code engine.appState().permissionMode()} already reflects the
     *  desired mode at construction time — no reflection needed. */
    static final class RecordingMethods extends AetherCodeMethods {
        final AtomicInteger askPermissionCallCount = new AtomicInteger(0);
        final AtomicInteger recordAutoApprovedCallCount = new AtomicInteger(0);
        final PermissionDecision cannedDecision;

        RecordingMethods(
                boolean autoApproveLowRisk,
                boolean autoApproveMediumHigh,
                PermissionMode mode,
                PermissionDecision cannedDecision) {
            super(engineFor(mode), n -> { /* swallow */ });
            this.cannedDecision = cannedDecision;
            this.setAutoApproveLowRisk(autoApproveLowRisk);
            this.setAutoApproveMediumHigh(autoApproveMediumHigh);
        }

        @Override
        public CompletableFuture<PermissionDecision> askPermission(
                String runId, String toolName, Map<String, Object> input,
                String question, String riskLevel) {
            askPermissionCallCount.incrementAndGet();
            return CompletableFuture.completedFuture(cannedDecision);
        }

        @Override
        public long recordAutoApproved(
                String toolName, Map<String, Object> input, String reason, String riskLevel) {
            recordAutoApprovedCallCount.incrementAndGet();
            return 0L;
        }
    }

    private static AetherCodeEngine engineFor(PermissionMode mode) {
        try {
            Path tmp = Files.createTempDirectory("r277-test");
            return new AetherCodeEngine.Builder()
                    .cwd(tmp)
                    .tools(List.<Tool>of())
                    .permissionMode(mode)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}