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

package org.aethercode.protocol.methods;

import org.aethercode.protocol.jsonrpc.JsonRpcError;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcRequest;
import org.aethercode.protocol.jsonrpc.JsonRpcResponse;
import org.aethercode.protocol.server.JsonRpcDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * R367 — the permission/* and grants/* surfaces must be REACHABLE.
 *
 * <p>This is the R720 memory/* story one module over, and the
 * {@code model/set} story from R366 in Java form: {@link
 * PermissionMethods} and {@link GrantMethods} were written, unit-tested
 * ({@code PermissionMethodsTest}, {@code GrantMethodsT118Test},
 * {@code GrantsRpcDtosT118Test}), had their Maven dependencies declared
 * in both poms — and their {@code registerAll} calls were never
 * reached, because {@code DaemonRunner} never called them.
 *
 * <p>Consequence: every grant RPC was -32601. The TUI's {@code
 * /consents} panel opened, called {@code permission/list}, got
 * method-not-found, and rendered an EMPTY list under a title promising
 * "Review and revoke stored tool grants".
 *
 * <p>Three deliberate design choices, each of which closes a hole a
 * weaker version of this test would have:
 *
 * <ol>
 *   <li><b>Nothing reads the real {@code ~/.aethercode}.</b> Every
 *       test builds its surfaces from {@code @TempDir}. The pre-R367
 *       draft used {@code System.getProperty("user.home")}, which meant
 *       {@code permission/revoke} would append to the developer's own
 *       audit log. A test that mutates the machine it runs on is not a
 *       test.</li>
 *
 *   <li><b>No {@code assumeTrue} on the source pin.</b> A skipped test
 *       is a green test. If the source cannot be found, that is a
 *       failure of this gate, not a reason to skip it — the whole
 *       point is to prove a call site exists.</li>
 *
 *   <li><b>The source pin is comment-stripped and structural.</b> A
 *       plain {@code src.contains("permRpcs.registerAll(...)")} is
 *       satisfied by a commented-out line, so deleting the wiring and
 *       commenting it would leave the gate green. We assert that a
 *       variable <i>assigned from</i> {@code PermissionMethods} has
 *       {@code .registerAll(} called on it, in code that survived
 *       comment stripping.</li>
 * </ol>
 */
class PermissionGrantWiringR367Test {

    /**
     * A dispatcher whose responses land in {@code out}.
     *
     * <p><b>Synchronous on purpose.</b> {@link JsonRpcDispatcher} runs
     * handlers on a worker pool, so {@code dispatch()} returns long
     * before the response exists. Every pre-existing test in this
     * module asserts {@code hasMethod} and <em>drops</em> the reply,
     * which is why "the class registers fine" and "the panel gets an
     * answer" were never the same claim — see
     * {@code CrossCuttingMethodsT500Test}. Pinning the answer requires
     * an executor that runs inline; {@code ownsExecutor=false} because
     * the shared daemon pool owns its own lifecycle and this stub has
     * none to shut down.
     */
    private static JsonRpcDispatcher newDispatcher(List<JsonRpcMessage> out) {
        return new JsonRpcDispatcher(out::add, new InlineExecutor(), false);
    }

    /** Runs every submitted task on the calling thread. */
    static final class InlineExecutor extends java.util.concurrent.AbstractExecutorService {
        private volatile boolean stopped;

        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; return List.of(); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }
        @Override public void execute(Runnable command) { command.run(); }
    }

    /**
     * The production wiring shape, rooted at {@code tmp} instead of
     * {@code ~/.aethercode}. Mirrors DaemonRunner: PermissionMethods
     * first, then GrantMethods delegating to it.
     */
    private static JsonRpcDispatcher wired(Path aethercodeHome, Path cwd,
                                           List<JsonRpcMessage> out) {
        JsonRpcDispatcher d = newDispatcher(out);
        PermissionMethods perm =
                PermissionMethods.defaults(aethercodeHome, "r367-test", cwd);
        perm.registerAll(d);
        // null permissionsFile = "don't write the preset", documented
        // in GrantMethods.from. Nothing else in this test writes.
        GrantMethods.from(perm, null).registerAll(d);
        return d;
    }

    // --- the surface exists -----------------------------------------------

    @Test
    void permissionMethodsRegistersItsFiveNames(@TempDir Path tmp) {
        JsonRpcDispatcher d = newDispatcher(new ArrayList<>());
        PermissionMethods.defaults(tmp, "p", tmp).registerAll(d);

        assertEquals(5, d.methodNames().size(),
                "PermissionMethods registers exactly five methods — got " + d.methodNames());
        for (String name : new String[]{
                "permission/check", "permission/prompt", "permission/list",
                "permission/revoke", "permission/clear"}) {
            assertTrue(d.hasMethod(name), "expected " + name + " to be registered");
        }
    }

    @Test
    void grantMethodsRegistersItsFourNames(@TempDir Path tmp) {
        JsonRpcDispatcher d = newDispatcher(new ArrayList<>());
        PermissionMethods perm = PermissionMethods.defaults(tmp, "p", tmp);
        GrantMethods.from(perm, null).registerAll(d);

        assertEquals(4, d.methodNames().size(),
                "GrantMethods registers exactly four methods — got " + d.methodNames());
        for (String name : new String[]{
                "grants/list", "grants/revoke", "grants/clear", "grants/setPreset"}) {
            assertTrue(d.hasMethod(name), "expected " + name + " to be registered");
        }
    }

    // --- the surface ANSWERS (this is what the panel actually hits) -------

    @Test
    void permissionListAnswersInsteadOfMethodNotFound(@TempDir Path tmp) {
        List<JsonRpcMessage> out = new ArrayList<>();
        JsonRpcDispatcher d = wired(tmp, tmp, out);

        d.dispatch(new JsonRpcRequest(null, "1", "permission/list", Map.of()));

        JsonRpcResponse r = oneResponse(out);
        // The exact pre-R367 regression: -32601.
        assertNotEqualsMethodNotFound(r);
        assertNotNull(r.result(),
                "permission/list returned an error: " + r.error());
        // An empty grant store legitimately answers [] — but it must be
        // a real List, not a null or a stringly-typed surprise that the
        // panel would render as "0 rows, no error".
        assertTrue(r.result() instanceof List,
                "permission/list must answer a List so the panel can render rows, got: "
                        + r.result());
    }

    @Test
    void grantsListAnswersInsteadOfMethodNotFound(@TempDir Path tmp) {
        List<JsonRpcMessage> out = new ArrayList<>();
        JsonRpcDispatcher d = wired(tmp, tmp, out);

        d.dispatch(new JsonRpcRequest(null, "1", "grants/list", Map.of()));

        JsonRpcResponse r = oneResponse(out);
        assertNotEqualsMethodNotFound(r);
        assertNotNull(r.result(), "grants/list returned an error: " + r.error());
    }

    @Test
    void revokeWithNoSuchGrantIsNotFoundNotMethodNotFound(@TempDir Path tmp) {
        // Two failures that look alike to the user and are not alike to
        // the code: "you passed an id I don't have" (-32604, a LIVE
        // endpoint answering correctly) and "this method does not
        // exist" (-32601, a dead one). The panel words them
        // differently, so collapsing them is the bug — and a naive
        // "it didn't throw" assertion would miss it.
        //
        // -32604 is asserted, not assumed. `permission/revoke` throws
        // it from an explicit not-found throw, and `grants/revoke`
        // routes the SAME call through a `Consumer<Map>` adapter
        // (GrantHandlers.revoke → perm::revoke) that has no error
        // channel — it survives only because JsonRpcProtocolException
        // is a RuntimeException. That is load-bearing and worth a pin.
        List<JsonRpcMessage> out = new ArrayList<>();
        JsonRpcDispatcher d = wired(tmp, tmp, out);

        d.dispatch(new JsonRpcRequest(null, "1", "permission/revoke",
                Map.of("id", "definitely-not-a-real-grant")));

        JsonRpcResponse r = oneResponse(out);
        assertNotEqualsMethodNotFound(r);
        assertEquals(-32604, r.error().code(),
                "a missing grant id must be reported as not-found, got: " + r.error());
    }

    @Test
    void grantsRevokeReportsNotFoundRatherThanFakingSuccess(@TempDir Path tmp) {
        // GrantHandlers.revoke builds `{ok: true, id}` UNCONDITIONALLY
        // after the adapter returns. The only thing standing between
        // "no such grant" and a confident `✓ revoked` in the panel is
        // the exception propagating out of the Consumer. If that ever
        // stops being a RuntimeException, this surface starts lying —
        // which is the exact failure shape this codebase keeps hitting.
        List<JsonRpcMessage> out = new ArrayList<>();
        JsonRpcDispatcher d = wired(tmp, tmp, out);

        d.dispatch(new JsonRpcRequest(null, "1", "grants/revoke",
                Map.of("id", "definitely-not-a-real-grant")));

        JsonRpcResponse r = oneResponse(out);
        assertNotEqualsMethodNotFound(r);
        assertEquals(-32604, r.error().code(),
                "grants/revoke must not answer ok:true for a grant that does "
                        + "not exist — got: " + (r.error() != null ? r.error() : r.result()));
    }

    @Test
    void revokeRejectsAMissingIdAsInvalidParams(@TempDir Path tmp) {
        // The other half of honest failure: a malformed call is the
        // client's fault and says so, rather than silently revoking
        // nothing and reporting success.
        List<JsonRpcMessage> out = new ArrayList<>();
        JsonRpcDispatcher d = wired(tmp, tmp, out);

        d.dispatch(new JsonRpcRequest(null, "1", "permission/revoke", Map.of()));

        JsonRpcResponse r = oneResponse(out);
        assertNotEqualsMethodNotFound(r);
        assertEquals(JsonRpcError.INVALID_PARAMS, r.error().code(),
                "revoking without an id is a client error, got: " + r.error());
    }

    @Test
    void anUnwiredDispatcherStillAnswersMethodNotFound() {
        // The control. Without it, every test above would also pass if
        // the dispatcher answered literally anything.
        List<JsonRpcMessage> out = new ArrayList<>();
        JsonRpcDispatcher d = newDispatcher(out);

        d.dispatch(new JsonRpcRequest(null, "1", "permission/list", Map.of()));

        JsonRpcResponse r = oneResponse(out);
        assertNotNull(r.error(), "an unwired dispatcher must reject");
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, r.error().code(),
                "this is exactly what the TUI got before R367");
    }

    // --- the missing half: the daemon has to CALL registerAll ------------

    @Test
    void daemonRunnerRegistersPermissionMethodsOnItsDispatcher() {
        String src = commentStripped(readDaemonRunner());

        String var = variableAssignedFrom(src, "PermissionMethods");
        assertNotNull(var,
                "DaemonRunner must construct a PermissionMethods — otherwise "
                        + "permission/{list,revoke} stay -32601");
        assertTrue(src.contains(var + ".registerAll("),
                "DaemonRunner must call " + var + ".registerAll(...) — "
                        + "PermissionMethods is constructed but never registered");
    }

    @Test
    void daemonRunnerRegistersGrantMethodsOnItsDispatcher() {
        String src = commentStripped(readDaemonRunner());

        String var = variableAssignedFrom(src, "GrantMethods");
        assertNotNull(var,
                "DaemonRunner must construct a GrantMethods — otherwise "
                        + "grants/{list,revoke,clear,setPreset} stay -32601");
        assertTrue(src.contains(var + ".registerAll("),
                "DaemonRunner must call " + var + ".registerAll(...) — "
                        + "GrantMethods is constructed but never registered");
    }

    @Test
    void daemonRunnerPinsTheConventionalAethercodeRootNotTheHomeDir() {
        // PermissionMethods.defaults documents its first argument as the
        // `~/.aethercode` ROOT. Passing `~` silently writes
        // `~/permissions.json` one level too high — grants that the UI
        // never reads back. This is the R364 shape in Java: the call
        // compiles, the parameter name is plausible, the path is wrong.
        //
        // TWO HOPS, deliberately. A first draft asserted only
        // `src.contains(".resolve(\".aethercode\")")` and mutation-2
        // (dropping that .resolve from the aethercodeHome assignment)
        // left it GREEN — the same string appears twice more in this
        // file for unrelated paths, so a file-wide substring scan is
        // satisfied by the wrong occurrence. Substring gates must be
        // scoped to the construct they claim to check; that is the same
        // trap as `listProjects` matching `listProjectsDISABLED`.
        String src = commentStripped(readDaemonRunner());

        // Hop 1 — where does the root variable come from?
        String rootVar = rootVariable(src);
        assertNotNull(rootVar,
                "DaemonRunner should hold the aethercode root in a local so "
                        + "both the permission surface and the grants file "
                        + "agree on one directory");

        // Hop 2 — and does THAT expression actually reach ~/.aethercode?
        String rhs = assignmentRhs(src, rootVar);
        assertNotNull(rhs, "could not read the right-hand side of `" + rootVar + " = ...`");
        assertTrue(rhs.contains("\".aethercode\""),
                "`" + rootVar + "` must resolve the .aethercode segment — got: "
                        + rhs.trim() + ". A bare user.home writes grants to ~/, one "
                        + "level above where the UI reads them.");

        // And the surface must actually receive that variable, not a
        // freshly-computed path that could drift from it.
        List<String> defaultsArgs = callArguments(src, "PermissionMethods.defaults");
        assertFalse(defaultsArgs.isEmpty(),
                "could not parse the PermissionMethods.defaults(...) call");
        assertTrue(defaultsArgs.get(0).contains(rootVar),
                "PermissionMethods.defaults must be handed `" + rootVar
                        + "` so the two hops cannot drift apart — got: "
                        + defaultsArgs.get(0).trim());

        // The grants file must hang off the same root, or revoking
        // through one surface and reading through the other diverge.
        List<String> fromArgs = callArguments(src, "GrantMethods.from");
        assertFalse(fromArgs.isEmpty(), "could not parse the GrantMethods.from(...) call");
        assertTrue(fromArgs.get(1).contains(rootVar)
                        && fromArgs.get(1).contains("permissions.json"),
                "GrantMethods.from must be given `" + rootVar
                        + ".resolve(\"permissions.json\")` — got: " + fromArgs.get(1).trim());
    }

    // --- helpers -----------------------------------------------------------

    private static JsonRpcResponse oneResponse(List<JsonRpcMessage> out) {
        assertEquals(1, out.size(),
                "expected exactly one JSON-RPC response, got " + out.size());
        assertTrue(out.get(0) instanceof JsonRpcResponse,
                "expected a response, got " + out.get(0));
        return (JsonRpcResponse) out.get(0);
    }

    private static void assertNotEqualsMethodNotFound(JsonRpcResponse r) {
        int code = r.error() == null ? 0 : r.error().code();
        assertTrue(code != JsonRpcError.METHOD_NOT_FOUND,
                "endpoint is DEAD: it answered -32601 method-not-found, "
                        + "which is the exact pre-R367 failure");
    }

    /**
     * Locate and read {@code DaemonRunner.java} by walking up from the
     * module directory to the Maven reactor root. Hard failure if it is
     * not found — never a skip.
     */
    private static String readDaemonRunner() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("aethercode-cli").resolve("src").resolve("main")
                    .resolve("java").resolve("org").resolve("aethercode")
                    .resolve("cli").resolve("DaemonRunner.java");
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("could not read " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        return fail("DaemonRunner.java not found by walking up from "
                + Path.of("").toAbsolutePath()
                + " — this gate cannot prove the wiring, so it must not pass silently");
    }

    /**
     * Return the variable name assigned from a {@code <Type>} factory /
     * constructor call, e.g. {@code PermissionMethods permRpcs = ...}
     * yields {@code permRpcs}. Null when the type is never instantiated.
     */
    private static String variableAssignedFrom(String src, String type) {
        Matcher m = Pattern.compile(
                "\\b" + Pattern.quote(type) + "\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=")
                .matcher(src);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The name of the local that holds the {@code ~/.aethercode} root.
     * Found by its TYPE and its use site, not by its name — hard-coding
     * {@code "aethercodeHome"} would make the gate a rename detector
     * rather than a wiring detector, and renaming is not the bug.
     */
    private static String rootVariable(String src) {
        Matcher m = Pattern.compile(
                "([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*java\\.nio\\.file\\.Path\\s*\\.of\\s*\\(")
                .matcher(src);
        if (!m.find()) return null;
        String name = m.group(1);
        // Confirm this really is the aethercode root, not an unrelated
        // Path.of(...) local that happens to come first.
        String rhs = assignmentRhs(src, name);
        return (rhs != null && rhs.contains("\".aethercode\"")) ? name : null;
    }

    /**
     * Text between {@code <var> =} and its terminating {@code ;},
     * skipping nested parentheses, string literals and generics.
     */
    static String assignmentRhs(String src, String var) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(var) + "\\s*=").matcher(src);
        if (!m.find()) return null;
        int i = m.end();
        int n = src.length();
        int depth = 0;
        int start = i;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                char q = c;
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '\\') { i += 2; continue; }
                    i++;
                    if (d == q) break;
                }
                continue;
            }
            if (c == '(' || c == '[' || c == '{') { depth++; i++; continue; }
            if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) return src.substring(start, i);
                depth--;
                i++;
                continue;
            }
            if (c == ';' && depth == 0) return src.substring(start, i);
            i++;
        }
        return src.substring(start, n);
    }

    /**
     * Top-level arguments of a {@code qualified.method(...)} call,
     * split on commas that are not nested inside brackets or strings.
     * Returns an empty list when the call cannot be located.
     */
    static List<String> callArguments(String src, String qualifiedName) {
        int open = src.indexOf(qualifiedName + "(");
        if (open < 0) return List.of();
        int i = open + qualifiedName.length() + 1;
        int n = src.length();
        int depth = 0;
        int argStart = i;
        List<String> args = new ArrayList<>();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                char q = c;
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '\\') { i += 2; continue; }
                    i++;
                    if (d == q) break;
                }
                continue;
            }
            if (c == '(' || c == '[' || c == '{') { depth++; i++; continue; }
            if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    args.add(src.substring(argStart, i));
                    return args;
                }
                depth--;
                i++;
                continue;
            }
            if (c == ',' && depth == 0) {
                args.add(src.substring(argStart, i));
                argStart = i + 1;
                i++;
                continue;
            }
            i++;
        }
        return args;
    }

    /**
     * Replace Java comments with spaces, preserving offsets so
     * positions stay meaningful and so string literals containing
     * {@code //} or {@code /*} survive. Mirrors the R364 lesson: the
     * old regex stripper ate 916 real lines of {@code line.ts} because
     * a string literal contained {@code /*}.
     */
    static String commentStripped(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') {
                out.append(c);
                i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '"') {
                // string literal — copy through, honouring escapes
                out.append(c);
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        out.append(src.charAt(i));
                        i++;
                    } else if (d == '"') {
                        break;
                    } else if (d == '\n') {
                        break;
                    }
                }
            } else if (c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n) {
                        out.append(src.charAt(i));
                        i++;
                    } else if (d == '\'' || d == '\n') {
                        break;
                    }
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int close = src.indexOf("*/", i + 2);
                int end = (close < 0) ? n : close + 2;
                for (int k = i; k < end; k++) {
                    out.append(src.charAt(k) == '\n' ? '\n' : ' ');
                }
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}