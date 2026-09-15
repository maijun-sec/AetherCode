package org.aethercode.protocol.permissions;

import org.aethercode.core.permission.PermissionResult;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * regression tests for the JsonRpcPermissionPrompter
 * timeout message. legacy, the prompter's exceptionally
 * branch built the reason as {@code "timeout: " + ex.getMessage()},
 * but Java's {@code TimeoutException#getMessage()} returns null,
 * so the model received the useless string
 * {@code "timeout: null"}. The model then re-emitted the same tool
 * call, which timed out again, looping forever
 * ({@code loopStops=0, errorRate=0.89, turnsCompleted=0}).
 *
 * <p>R181 fixes this by:
 * <ol>
 *   <li>Naming the tool in the message ("permission ask for 'X' timed out...")</li>
 *   <li>Including the actual timeout duration in seconds</li>
 *   <li>Adding an explicit "DO NOT retry this tool call" hint so
 *       the model breaks the loop instead of re-running the same
 *       call indefinitely</li>
 *   <li>Distinguishing TimeoutException from other exceptions
 *       (cancelled, runtime error, ...)</li>
 * </ol>
 *
 * <p>These tests use a stubbed {@link AetherCodeMethods} that
 * returns a future which never completes, so the prompter's
 * {@code .orTimeout(...)} branch fires deterministically.
 */
class JsonRpcPermissionPrompterR181TimeoutTest {

    /** Minimal {@link Tool} stub — a file_write with a no-op call. */
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
    void r181_timeoutMessage_includesToolName() throws Exception {
        StubMethods methods = new StubMethods();
        JsonRpcPermissionPrompter prompter = new JsonRpcPermissionPrompter(methods, 200L);

        CompletableFuture<PermissionResult> r = prompter.ask(
                fakeTool("file_write"), Map.of("file_path", "D:\\tmp\\foo.txt"), "write file?");

        PermissionResult res = r.get(2, TimeUnit.SECONDS);
        assertThat(res).isInstanceOf(PermissionResult.Deny.class);
        String reason = ((PermissionResult.Deny) res).message();
        assertThat(reason)
                .as("denial must name the tool")
                .contains("'file_write'");
    }

    @Test
    void r181_timeoutMessage_explicitStopRetryingHint() throws Exception {
        StubMethods methods = new StubMethods();
        JsonRpcPermissionPrompter prompter = new JsonRpcPermissionPrompter(methods, 100L);

        CompletableFuture<PermissionResult> r = prompter.ask(
                fakeTool("file_write"), Map.of(), "x");
        PermissionResult res = r.get(2, TimeUnit.SECONDS);
        assertThat(res).isInstanceOf(PermissionResult.Deny.class);
        String reason = ((PermissionResult.Deny) res).message();
        // The legacy message was "timeout: null" — totally useless
        // for the model. R181 must include a directive the model
        // can act on: stop retrying.
        assertThat(reason)
                .as("denial must explicitly tell the model to STOP retrying")
                .contains("DO NOT retry");
        assertThat(reason)
                .as("denial must NOT contain the 历史 'timeout: null' marker")
                .doesNotContain("timeout: null");
        assertThat(reason.toLowerCase())
                .as("denial should not contain bare 'null' either")
                .doesNotContain("null");
    }

    @Test
    void r181_timeoutMessage_includesActualTimeoutDuration() throws Exception {
        // Use a 1-second timeout so the test isn't slow.
        StubMethods methods = new StubMethods();
        JsonRpcPermissionPrompter prompter = new JsonRpcPermissionPrompter(methods, 1_000L);

        CompletableFuture<PermissionResult> r = prompter.ask(
                fakeTool("bash"), Map.of(), "x");
        PermissionResult res = r.get(3, TimeUnit.SECONDS);
        assertThat(res).isInstanceOf(PermissionResult.Deny.class);
        String reason = ((PermissionResult.Deny) res).message();
        assertThat(reason)
                .as("denial must include the actual timeout duration in seconds (1s)")
                .contains("1s");
    }

    /** Stub: future never completes, so prompter's orTimeout fires. */
    static final class StubMethods extends AetherCodeMethods {
        StubMethods() {
            super(engineFor(), n -> { /* swallow notifier */ });
            // R268d: the production default flipped to true
            // (auto-approve medium + high). The R181 timeout
            // tests need the prompt to actually reach the
            // RPC layer so the timeout path can fire, so we
            // explicitly disable the new auto-approve
            // short-circuit here.
            this.setAutoApproveLowRisk(false);
            this.setAutoApproveMediumHigh(false);
        }
        private static AetherCodeEngine engineFor() {
            try {
                Path tmp = Files.createTempDirectory("r181-test");
                return new AetherCodeEngine.Builder()
                        .cwd(tmp)
                        .tools(List.<Tool>of())
                        .build();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        @Override
        public CompletableFuture<PermissionDecision> askPermission(
                String runId, String toolName, Map<String, Object> input,
                String question, String riskLevel) {
            // Never completes — the prompter's orTimeout will fire.
            return new CompletableFuture<>();
        }
    }
}
