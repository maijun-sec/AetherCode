// Copyright (c) 2026 maijun
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package org.aethercode.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * R388k — the SDD orchestrator must be installed on BOTH transports.
 *
 * <h2>The defect this gate exists to hold</h2>
 *
 * <p>The five {@code sdd.*} RPCs are registered by
 * {@code AetherCodeMethods.registerAll(...)}, which BOTH transports call — so
 * the methods were <b>advertised</b> on stdio.
 *
 * <p>But the orchestrator itself — the object that actually does the work —
 * was installed only on the HTTP path ({@code http.methods().setSddOrchestrator}).
 * On stdio it stayed null, so every call fell into
 * {@code requireSdd()} and threw:
 *
 * <pre>
 *   rpc error -32603: Internal error: sdd orchestrator not configured
 * </pre>
 *
 * <p>The TUI's <b>line</b> renderer talks to the daemon over stdio, so the
 * entire SDD surface was unreachable from a pure terminal — and the failure
 * looked like a server error, not like "this transport has no SDD".
 *
 * <h2>Why this is the R720 shape again</h2>
 *
 * <p>R720 was the same defect one module out: {@code memory/*} was registered
 * but the store was never installed, so every call answered {@code -32601
 * method not found}. Here the registry is shared and the wiring is not — which
 * is worse: the method is <b>known</b> and then fails at runtime.
 *
 * <h2>★ A METHOD REGISTERED ON ONE TRANSPORT AND NOT WIRED IS ADVERTISED AND
 * BROKEN ★</h2>
 *
 * <p>The registry is shared; the wiring is per-transport. So "is the method
 * registered?" is the wrong question — it is true on both. The question is
 * "does the CALL succeed on each transport?", and only a real call can answer
 * that.
 *
 * <p>→ <b>Lesson 1327 — a shared registry makes a per-transport wiring gap
 * INVISIBLE.</b> The gate has to ask about each transport separately, and it
 * has to ask about the WIRING, not the registration.
 */
class SddOrchestratorWiringR388kTest {

    /** The daemon's own source. */
    private static Path daemonRunner() {
        Path p = Path.of("src", "main", "java", "org", "aethercode", "cli", "DaemonRunner.java");
        assertTrue(Files.exists(p),
                "DaemonRunner.java not found at " + p.toAbsolutePath()
                        + " — run this test from the aethercode-cli module directory");
        return p;
    }

    private static String source() {
        try {
            return Files.readString(daemonRunner());
        } catch (Exception e) {
            throw new AssertionError("could not read DaemonRunner.java", e);
        }
    }

    /**
     * Extract the body of a method, by brace counting that skips string
     * literals.
     *
     * <p>The brace counting matters: a {@code "}"} inside a string literal
     * would close the body early and make every later assertion pass
     * VACUOUSLY (Lesson 1234).
     */
    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        if (start < 0) {
            return null;
        }
        int open = src.indexOf('{', start);
        if (open < 0) {
            return null;
        }
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open, i + 1);
                }
            }
        }
        return null;
    }
    /**
     * Extract the body of the method whose DECLARATION matches.
     *
     * <p>★ The overload trap ★ — {@code runHttp} has FOUR overloads and
     * {@code runInternal} is private. A naive {@code indexOf("static int
     * runHttp(")} finds the 2-arg DELEGATE first, whose body is one
     * line, and every assertion over it passes VACUOUSLY (or falsely
     * fails). The same trap bit R388d, and this file's own header
     * documents it.
     *
     * <p>So the search takes the <b>LAST</b> declaration whose body
     * actually contains the signature — the real implementation is the
     * one with the most parameters, and it is the one that does the
     * work.
     *
     * @param signaturePrefix e.g. {@code "static int runHttp("}
     * @param marker          a string that MUST appear in the real body
     */
    private static String implementationBody(String src, String signaturePrefix, String marker) {
        int best = -1;
        String bestBody = null;
        int from = 0;
        while (true) {
            int start = src.indexOf(signaturePrefix, from);
            if (start < 0) {
                break;
            }
            from = start + signaturePrefix.length();
            String body = methodBody(src.substring(start), signaturePrefix);
            if (body == null) {
                continue;
            }
            if (body.contains(marker)) {
                // A later declaration that ALSO contains the marker is
                // the real implementation (the delegates do not).
                best = start;
                bestBody = body;
            }
        }
        return bestBody;
    }

    @Test
    void theStdioPathInstallsTheSddOrchestrator() {
        String src = source();
        // The stdio entry point. `runInternal` is private, and the
        // public `run(...)` overloads are one-line DELEGATES to it —
        // so the body must be found by the marker, not by the first
        // declaration.
        String stdio = implementationBody(src, "private static int runInternal(", "setSddOrchestrator");
        assertTrue(stdio != null,
                "the STDIO daemon never installs the SDD orchestrator — every sdd.* call "
                        + "on stdio answers -32603 'sdd orchestrator not configured', so the "
                        + "TUI's line mode cannot drive SDD at all (R388k)");
        assertTrue(stdio.contains("methods.setSddOrchestrator("),
                "the stdio daemon's SDD wiring calls setSddOrchestrator on something else "
                        + "— the engine's HTTP `methods` object is not in scope here (R388k)");
    }

    @Test
    void theHttpPathInstallsTheSddOrchestrator() {
        String src = source();
        String http = implementationBody(src, "static int runHttp(", "setSddOrchestrator");
        assertTrue(http != null,
                "the HTTP daemon no longer installs the SDD orchestrator — the desktop "
                        + "loses SDD (R388k)");
    }

    @Test
    void bothTransportsReattachTheActiveRunsOnBoot() {
        String src = source();
        // R709-B: a run that was mid-flight when the daemon went down must
        // still be advanceable after a restart. That needs the cwd → slug
        // backref, which comes from reloadActiveRuns(cwd).
        int reloads = countOccurrences(src, "reloadActiveRuns");
        assertTrue(reloads >= 2,
                "reloadActiveRuns is called " + reloads + " time(s); BOTH transports must "
                        + "re-attach the active runs on boot, or a run that was mid-flight "
                        + "when the daemon died answers RUN_NOT_FOUND forever (R388k)");
    }

    @Test
    void theSddMethodsAreRegisteredOnTheSharedRegistry() {
        // The registry is shared, which is exactly why the per-transport
        // wiring gap was invisible. Pin the shape so a future refactor that
        // splits the registry has to think about both transports.
        String src = source();
        assertTrue(src.contains("methods.registerAll(server.dispatcher())"),
                "the stdio daemon no longer registers the engine methods (R388k)");
    }

    @Test
    void theProtocolLayerStillGuardsAgainstAMissingOrchestrator() {
        // The guard must STAY: a daemon built without the aethercode-sdd
        // module has no orchestrator, and the error message has to say so
        // rather than throwing a NullPointerException.
        Path p = Path.of("..", "aethercode-protocol", "src", "main", "java",
                "org", "aethercode", "protocol", "methods", "AetherCodeMethods.java");
        assertTrue(Files.exists(p),
                "AetherCodeMethods.java not found at " + p.toAbsolutePath() + " (R388k)");
        String src;
        try {
            src = Files.readString(p);
        } catch (Exception e) {
            throw new AssertionError("could not read AetherCodeMethods.java", e);
        }
        assertTrue(src.contains("sdd orchestrator not configured"),
                "the 'sdd orchestrator not configured' message is gone — a daemon without "
                        + "the sdd module would throw a NullPointerException instead (R388k)");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
