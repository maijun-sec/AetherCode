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

package org.aethercode.protocol.permissions;

import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.tool.Tool;
import org.aethercode.protocol.methods.AetherCodeMethods;
import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R388l — the permission COMMENT reaches the model.
 *
 * <p>Claude Code lets a user attach a free-text comment to a permission
 * answer, and the comment reaches the model: on a denial it IS the reason,
 * on an approval it is context next to the tool's output. A boolean gate
 * becomes a communication channel.
 *
 * <h2>★ The defect this test exists to hold ★</h2>
 *
 * <p>The wire already had a {@code reason}. The DENY path already carried
 * it to the model. But the APPROVAL path DROPPED it: the prompter did
 * {@code new PermissionResult.Allow(input)} and the reason evaporated.
 *
 * <p>A user who wrote "yes, but write to /tmp first" would have had their
 * comment reach the daemon and then vanish — the silent-degradation shape:
 * the user believes they said something the model never heard.
 *
 * <h2>★ Why the wire has TWO fields ★</h2>
 *
 * <p>{@code reason} is the mechanical audit trail ("TUI picked deny").
 * {@code comment} is the USER'S OWN WORDS. They are separate because the
 * executor must present the human sentence to the model WITHOUT dressing
 * boilerplate up as the user's voice. Collapsing them would make
 * "TUI picked deny" indistinguishable from "no — use the read-only tool".
 *
 * <h2>★ A client-side gate cannot stand in for this one ★</h2>
 *
 * <p>The TUI gate proves the client SENDS the comment. This test proves the
 * daemon CARRIES it. A client that sends a comment the daemon drops is a
 * comment that reaches the model never.
 */
class JsonRpcPermissionPrompterR388lCommentTest {

    /** Minimal {@link Tool} stub. */
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

    /**
     * A stub that lets the daemon's REAL permission machinery run.
     *
     * <p>It does NOT override {@code askPermission}: the requestId that
     * {@code permissionResponse} matches on is minted inside the real
     * method, so overriding it would leave the daemon's pending queue
     * empty and every reply would answer "unknown requestId" — the
     * test would then be measuring a timeout instead of the contract.
     *
     * <p>What the stub DOES do is capture the emitted notification, so
     * the test can read the real {@code requestId} and answer with the
     * exact wire payload a client would send.
     */
    private static final class StubMethods extends AetherCodeMethods {
        // The list is a LOCAL of the constructor, not a field: an
        // instance field cannot be referenced before the super()
        // call. The field below is assigned after super() returns.
        private final java.util.List<JsonRpcNotification> out;

        StubMethods(Path cwd) {
            this(cwd, new java.util.ArrayList<>());
        }

        private StubMethods(Path cwd, java.util.List<JsonRpcNotification> sink) {
            super(new AetherCodeEngine.Builder()
                            .cwd(cwd)
                            .tools(List.<Tool>of())
                            .build(),
                    sink::add);
            this.out = sink;
        }

        /** The requestId of the first pending ask. */
        String firstRequestId() {
            return String.valueOf(((Map<?, ?>) out.get(0).params()).get("requestId"));
        }
    }

    // --------------------------------------------------------------
    // The wire: permissionResponse carries the comment.
    // --------------------------------------------------------------

    @Test
    void permissionResponse_carriesTheCommentIntoTheDecision(@TempDir Path cwd) throws Exception {
        StubMethods methods = new StubMethods(cwd);
        CompletableFuture<AetherCodeMethods.PermissionDecision> pending =
                methods.askPermission("run-1", "bash", Map.of("command", "rm -rf build"),
                        "risky", "high");

        // Exactly what a client sends when the user typed a comment.
        methods.permissionResponse(Map.of(
                "requestId", methods.firstRequestId(),
                "decision", AetherCodeMethods.DECISION_ALLOW,
                "reason", "TUI picked allow",
                "comment", "yes, but dry-run it first"));

        AetherCodeMethods.PermissionDecision d = pending.get(2, TimeUnit.SECONDS);
        assertThat(d.comment()).isEqualTo("yes, but dry-run it first");
        // The reason keeps its own meaning — the audit trail.
        assertThat(d.reason()).isEqualTo("TUI picked allow");
    }

    @Test
    void permissionResponse_withoutACommentIsUnchanged(@TempDir Path cwd) throws Exception {
        StubMethods methods = new StubMethods(cwd);
        CompletableFuture<AetherCodeMethods.PermissionDecision> pending =
                methods.askPermission("run-1", "bash", Map.of(), "risky", "high");

        // A client that predates R388l omits the field entirely.
        methods.permissionResponse(Map.of(
                "requestId", methods.firstRequestId(),
                "decision", AetherCodeMethods.DECISION_ALLOW,
                "reason", "TUI picked allow"));

        AetherCodeMethods.PermissionDecision d = pending.get(2, TimeUnit.SECONDS);
        assertThat(d.comment()).isNull();
        assertThat(d.reason()).isEqualTo("TUI picked allow");
    }

    @Test
    void permissionResponse_aBlankCommentIsNoComment(@TempDir Path cwd) throws Exception {
        StubMethods methods = new StubMethods(cwd);
        CompletableFuture<AetherCodeMethods.PermissionDecision> pending =
                methods.askPermission("run-1", "bash", Map.of(), "risky", "high");

        methods.permissionResponse(Map.of(
                "requestId", methods.firstRequestId(),
                "decision", AetherCodeMethods.DECISION_ALLOW,
                "reason", "TUI picked allow",
                "comment", "   "));

        AetherCodeMethods.PermissionDecision d = pending.get(2, TimeUnit.SECONDS);
        assertThat(d.comment()).isNull();
    }

    // --------------------------------------------------------------
    // The prompter: the comment survives into the Allow / Deny.
    //
    // These drive the REAL round trip: the prompter asks, the test
    // answers through the REAL `permissionResponse` (so the wire
    // payload is exercised, not a hand-built future), and the result
    // is inspected.
    // --------------------------------------------------------------

    @Test
    void prompter_allow_carriesTheCommentAsTheNote(@TempDir Path cwd) throws Exception {
        StubMethods methods = new StubMethods(cwd);
        JsonRpcPermissionPrompter prompter = new JsonRpcPermissionPrompter(methods, 5_000L);

        CompletableFuture<PermissionResult> r = prompter.ask(
                fakeTool("bash"), Map.of("command", "ls"), "run it?");

        methods.permissionResponse(Map.of(
                "requestId", methods.firstRequestId(),
                "decision", AetherCodeMethods.DECISION_ALLOW,
                "reason", "TUI picked allow",
                "comment", "yes, but dry-run it first"));

        PermissionResult res = r.get(2, TimeUnit.SECONDS);
        assertThat(res).isInstanceOf(PermissionResult.Allow.class);
        // ★ THE DEFECT: before R388l this was null — the comment
        // reached the daemon and evaporated.
        assertThat(((PermissionResult.Allow) res).note())
                .isEqualTo("yes, but dry-run it first");
    }

    @Test
    void prompter_deny_putsTheCommentFirst(@TempDir Path cwd) throws Exception {
        StubMethods methods = new StubMethods(cwd);
        JsonRpcPermissionPrompter prompter = new JsonRpcPermissionPrompter(methods, 5_000L);

        CompletableFuture<PermissionResult> r = prompter.ask(
                fakeTool("bash"), Map.of("command", "rm -rf build"), "delete?");

        methods.permissionResponse(Map.of(
                "requestId", methods.firstRequestId(),
                "decision", AetherCodeMethods.DECISION_DENY,
                "reason", "line mode picked deny",
                "comment", "use the clean target instead"));

        PermissionResult res = r.get(2, TimeUnit.SECONDS);
        assertThat(res).isInstanceOf(PermissionResult.Deny.class);
        String message = ((PermissionResult.Deny) res).message();
        // The MODEL reads this message. The user's words must be the
        // headline, and the mechanical reason must not be mistaken for
        // something the user said.
        assertThat(message).contains("use the clean target instead");
        assertThat(message).startsWith("user said:");
        assertThat(message).contains("line mode picked deny");
    }

    @Test
    void prompter_allowWithoutACommentHasNoNote(@TempDir Path cwd) throws Exception {
        StubMethods methods = new StubMethods(cwd);
        JsonRpcPermissionPrompter prompter = new JsonRpcPermissionPrompter(methods, 5_000L);

        CompletableFuture<PermissionResult> r = prompter.ask(
                fakeTool("bash"), Map.of("command", "ls"), "run it?");

        methods.permissionResponse(Map.of(
                "requestId", methods.firstRequestId(),
                "decision", AetherCodeMethods.DECISION_ALLOW,
                "reason", "TUI picked allow"));

        PermissionResult res = r.get(2, TimeUnit.SECONDS);
        assertThat(((PermissionResult.Allow) res).note()).isNull();
    }

    @Test
    void prompter_denyWithoutACommentKeepsTheOldMessage(@TempDir Path cwd) throws Exception {
        StubMethods methods = new StubMethods(cwd);
        JsonRpcPermissionPrompter prompter = new JsonRpcPermissionPrompter(methods, 5_000L);

        CompletableFuture<PermissionResult> r = prompter.ask(
                fakeTool("bash"), Map.of("command", "rm -rf build"), "delete?");

        methods.permissionResponse(Map.of(
                "requestId", methods.firstRequestId(),
                "decision", AetherCodeMethods.DECISION_DENY,
                "reason", "line mode picked deny"));

        PermissionResult res = r.get(2, TimeUnit.SECONDS);
        // ★ Backward compatibility: a client with no comment produces
        // the PRE-R388l message, byte for byte.
        assertThat(((PermissionResult.Deny) res).message())
                .isEqualTo("line mode picked deny");
    }

    // --------------------------------------------------------------
    // The executor: the note reaches the tool_result the model reads.
    // --------------------------------------------------------------

    @Test
    void executor_appendsTheNoteToTheToolResult() {
        // A String tool output gains the note as a trailing block.
        Object out = org.aethercode.core.engine.StreamingToolExecutor.appendApprovalNote(
                "file written", "but keep the original header");
        assertThat(out).isInstanceOf(String.class);
        assertThat((String) out)
                .contains("file written")
                .contains("[user's note] but keep the original header");
    }

    @Test
    void executor_withoutANoteLeavesTheOutputAlone() {
        Object out = org.aethercode.core.engine.StreamingToolExecutor.appendApprovalNote(
                "file written", null);
        assertThat(out).isEqualTo("file written");
        assertThat(org.aethercode.core.engine.StreamingToolExecutor.appendApprovalNote(
                "file written", "   ")).isEqualTo("file written");
    }

    @Test
    void executor_anEmptyToolOutputStillCarriesTheNote() {
        Object out = org.aethercode.core.engine.StreamingToolExecutor.appendApprovalNote(
                "", "no output, but fine");
        assertThat((String) out).contains("[user's note] no output, but fine");
    }

    @Test
    void executor_aStructuredToolOutputIsStillNotSwallowed() {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("rows", 3);
        Object out = org.aethercode.core.engine.StreamingToolExecutor.appendApprovalNote(
                structured, "three rows is fine");
        assertThat(out).isInstanceOf(String.class);
        assertThat((String) out)
                .contains("rows")
                .contains("[user's note] three rows is fine");
    }
}
