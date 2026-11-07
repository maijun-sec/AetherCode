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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R388m — the permission DEADLINE, and "nobody can approve".
 *
 * <h2>★ The deadline must be the DAEMON's ★</h2>
 *
 * <p>The client wants to show a countdown on the permission card. It used
 * to have to GUESS the timeout, and a guess is a race it can lose in both
 * directions:
 *
 * <ul>
 *   <li>a guess that is too SHORT denies a call the user was about to
 *       approve — the user's keystroke arrives after the client already
 *       decided;</li>
 *   <li>a guess that is too LONG leaves the user watching a prompt the
 *       daemon already resolved.</li>
 * </ul>
 *
 * <p>So the daemon publishes the deadline on the ask, and the client counts
 * down on THAT. This test pins the published values against the prompter's
 * own timeout — one number, not two.
 *
 * <h2>★ "Nobody can approve" must say so, and must say DO NOT RETRY ★</h2>
 *
 * <p>When there is no client, the ask cannot be answered by a human. The
 * model's default reaction to a denial is to try again — and a retry
 * against a permission wall fails identically, forever. The deny reason
 * must therefore say BOTH: that nobody can approve, and that retrying is
 * pointless. It must also offer a way forward, or the model has nothing
 * to do but retry.
 *
 * <h2>★ A client-side gate cannot stand in for this one ★</h2>
 *
 * <p>The TUI gate proves the client renders and sends. This test proves
 * the daemon PUBLISHES and that the no-client denial is honest. A
 * deadline the daemon never sends is a countdown the client invents.
 */
class JsonRpcPermissionPrompterR388mDeadlineTest {

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

    /** Captures the emitted notification so the test can read the REAL payload. */
    private static final class StubMethods extends AetherCodeMethods {
        private final List<JsonRpcNotification> out;

        StubMethods(Path cwd) {
            this(cwd, new ArrayList<>());
        }

        private StubMethods(Path cwd, List<JsonRpcNotification> sink) {
            super(new AetherCodeEngine.Builder()
                            .cwd(cwd)
                            .tools(List.<Tool>of())
                            .build(),
                    sink::add);
            this.out = sink;
        }

        /** The params of the first emitted notification. */
        @SuppressWarnings("unchecked")
        Map<String, Object> firstParams() {
            return (Map<String, Object>) out.get(0).params();
        }

        String firstRequestId() {
            return String.valueOf(firstParams().get("requestId"));
        }
    }

    // --------------------------------------------------------------
    // The deadline is published.
    // --------------------------------------------------------------

    @Test
    void theAsk_publishesTheDeadline(@TempDir Path cwd) {
        StubMethods methods = new StubMethods(cwd);
        methods.askPermissionWithDeadline("run-1", "bash", Map.of("command", "ls"), "risky", "high", 120_000L);

        Map<String, Object> p = methods.firstParams();
        assertThat(p).containsKey("timeoutMs");
        assertThat(p.get("timeoutMs")).isEqualTo(120_000L);
        assertThat(p).containsKey("expiresAtMs");
        assertThat((Long) p.get("expiresAtMs")).isPositive();
    }

    @Test
    void theDeadline_isThePrompterOwnTimeout(@TempDir Path cwd) throws Exception {
        StubMethods methods = new StubMethods(cwd);
        JsonRpcPermissionPrompter prompter = new JsonRpcPermissionPrompter(methods, 77_000L);

        CompletableFuture<PermissionResult> r = prompter.ask(
                fakeTool("bash"), Map.of("command", "ls"), "run it?");

        Map<String, Object> p = methods.firstParams();
        // ★ The number the client counts down MUST be the number the
        // daemon enforces. Two numbers = a race.
        assertThat(p.get("timeoutMs")).isEqualTo(77_000L);

        long expires = (Long) p.get("expiresAtMs");
        long before = System.currentTimeMillis();
        assertThat(expires).isBetween(before + 70_000L, before + 77_000L);

        // answer so the future does not linger
        methods.permissionResponse(Map.of(
                "requestId", methods.firstRequestId(),
                "decision", AetherCodeMethods.DECISION_ALLOW,
                "reason", "test"));
        r.get(2, TimeUnit.SECONDS);
    }

    @Test
    void anAskWithNoTimeout_publishesNoDeadline(@TempDir Path cwd) {
        StubMethods methods = new StubMethods(cwd);
        // timeoutMs = 0 means "no deadline published" — a client must
        // show NO countdown rather than invent one.
        methods.askPermissionWithDeadline("run-1", "bash", Map.of(), "risky", "high", 0L);

        Map<String, Object> p = methods.firstParams();
        assertThat(p).doesNotContainKey("timeoutMs");
        assertThat(p).doesNotContainKey("expiresAtMs");
    }

    @Test
    void theLegacyOverload_publishesNoDeadline(@TempDir Path cwd) {
        StubMethods methods = new StubMethods(cwd);
        methods.askPermission("run-1", "bash", Map.of(), "risky", "high");

        Map<String, Object> p = methods.firstParams();
        // ★ A client that predates R388m must not see a field it does
        // not understand — and a client that does understand must not
        // get a deadline the daemon does not enforce.
        assertThat(p).doesNotContainKey("timeoutMs");
        assertThat(p).doesNotContainKey("expiresAtMs");
    }

    // --------------------------------------------------------------
    // "Nobody can approve" — the no-client denial.
    // --------------------------------------------------------------

    @Test
    void withNoClient_theDenySaysNobodyCanApproveAndDoNotRetry(@TempDir Path cwd) throws Exception {
        // methods == null → no client → nobody can approve.
        JsonRpcPermissionPrompter prompter = new JsonRpcPermissionPrompter(null, 1_000L);

        PermissionResult res = prompter.ask(
                fakeTool("file_write"), Map.of("file_path", "a.txt"), "write it?")
                .get(2, TimeUnit.SECONDS);

        assertThat(res).isInstanceOf(PermissionResult.Deny.class);
        // `message` is what the executor hands the MODEL.
        String reason = ((PermissionResult.Deny) res).message();
        assertThat(reason)
                .as("the model must be told nobody can approve")
                .contains("no one can approve");
        assertThat(reason)
                .as("the model must be told NOT to retry — a retry fails identically")
                .contains("DO NOT retry");
        assertThat(reason)
                .as("the model must be given a way forward")
                .contains("tell the user");
        assertThat(reason)
                .as("the tool must be named")
                .contains("file_write");
        // ★ The OLD one-word reason is the defect this round removes.
        assertThat(reason).isNotEqualTo("no JSON-RPC connection");
    }

    @Test
    void theTimeoutDeny_alsoSaysDoNotRetry(@TempDir Path cwd) throws Exception {
        StubMethods methods = new StubMethods(cwd);
        // a timeout so short it fires immediately
        JsonRpcPermissionPrompter prompter = new JsonRpcPermissionPrompter(methods, 50L);

        PermissionResult res = prompter.ask(
                fakeTool("bash"), Map.of("command", "rm -rf /"), "really?")
                .get(5, TimeUnit.SECONDS);

        assertThat(res).isInstanceOf(PermissionResult.Deny.class);
        String reason = ((PermissionResult.Deny) res).message();
        assertThat(reason).contains("DO NOT retry");
        assertThat(reason).contains("timed out");
    }
}
