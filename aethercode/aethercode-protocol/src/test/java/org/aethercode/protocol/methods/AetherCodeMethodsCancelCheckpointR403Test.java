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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.transcript.CodeCheckpointStore;
import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R403 — the daemon's cancel must leave a point behind.
 *
 * <h2>★ WHAT THIS GATE HOLDS ★</h2>
 *
 * <ul>
 *   <li><b>A cancel that stopped a run leaves a CANCEL POINT.</b> The
 *       user pressed stop because the run was half way through; the
 *       turn-boundary checkpoint is "before this turn started", which
 *       is not where the task stopped.</li>
 *   <li><b>The cancel's answer carries the point's id.</b> Otherwise
 *       the client cannot point at the thing the daemon just
 *       created.</li>
 *   <li><b>A cancel against a run that is not there leaves NOTHING.</b>
 *       Every stop button press would otherwise litter the session
 *       with points the user never asked for.</li>
 *   <li><b>The point is visible on the wire</b>, with its kind — a
 *       renderer has to be able to tell a cancel point from a turn
 *       point, and only the daemon knows which one it is.</li>
 * </ul>
 */
class AetherCodeMethodsCancelCheckpointR403Test {

    /**
     * A chat client that blocks the worker thread inside
     * {@code stream()} until the cancel RPC interrupts it — the same
     * shape as {@code AetherCodeMethodsCancelR400Test}'s client.
     */
    private static final class BlockingChatClient
            implements org.aethercode.core.llm.ChatClient {
        volatile Thread worker;

        @Override
        public java.util.stream.Stream<StreamEvent> stream(
                List<Message> m, String sp, List<Tool> tools) {
            worker = Thread.currentThread();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return java.util.stream.Stream.empty();
        }

        @Override
        public String modelId() {
            return "stub";
        }
    }

    private static final class Rig {
        final AetherCodeEngine engine;
        final AetherCodeMethods methods;
        final BlockingChatClient chat = new BlockingChatClient();
        final List<JsonRpcNotification> notifications = new CopyOnWriteArrayList<>();

        Rig(java.nio.file.Path cwd) {
            engine = new AetherCodeEngine.Builder()
                    .cwd(cwd)
                    .agentsDir(cwd)
                    .tools(List.<Tool>of())
                    .build();
            engine.setChatClient(chat);
            methods = new AetherCodeMethods(engine, notifications::add);
        }

        /** Start a query and wait until the worker is really blocked. */
        void startQuery(String prompt) throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> accepted =
                    (Map<String, Object>) methods.query(Map.of("prompt", prompt));
            assertEquals(true, accepted.get("accepted"));
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && chat.worker == null) {
                Thread.sleep(10);
            }
            assertNotNull(chat.worker,
                    "the chat client never started — the run is not in flight");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    void aCancelThatStoppedARunLeavesACancelPoint(@TempDir java.nio.file.Path root)
            throws Exception {
        Rig rig = new Rig(root.toAbsolutePath());
        rig.startQuery("do something long");

        Map<String, Object> cancelled = asMap(rig.methods.cancel(Map.of()));
        assertEquals(true, cancelled.get("cancelled"),
                "the run must actually be cancelled: " + cancelled);
        String checkpointId = (String) cancelled.get("checkpointId");
        assertNotNull(checkpointId,
                "the cancel must report the point it left behind: " + cancelled);

        // ★ THE POINT THE CANCEL REPORTED MUST BE THE POINT ON DISK ★
        // There is also a TURN point (the turn boundary fired when the
        // run started) — so the count is not the assertion; the identity
        // of the reported point is.
        var points = rig.engine.codeCheckpoints();
        var cp = points.stream()
                .filter(p -> p.id().equals(checkpointId))
                .findFirst()
                .orElse(null);
        assertNotNull(cp,
                "the reported id must name a real point: " + points);
        assertEquals(CodeCheckpointStore.KIND_CANCEL, cp.kind(),
                "the point must be a CANCEL point, not a turn point");
        assertTrue(cp.isCancelPoint());
    }

    @Test
    void theCancelPointIsVisibleOnTheWireWithItsKind(@TempDir java.nio.file.Path root)
            throws Exception {
        Rig rig = new Rig(root.toAbsolutePath());
        rig.startQuery("do something long");
        rig.methods.cancel(Map.of());

        Map<String, Object> res = asMap(rig.methods.checkpointsList(Map.of()));
        assertEquals(true, res.get("ok"));
        List<?> cps = (List<?>) res.get("checkpoints");
        assertNotNull(cps, "the response must carry a checkpoints array: " + res);
        boolean sawCancel = false;
        for (Object o : cps) {
            if ("cancel".equals(asMap(o).get("kind"))) sawCancel = true;
        }
        assertTrue(sawCancel,
                "the renderer must be able to tell a cancel point from a turn point: " + res);
    }

    @Test
    void aCancelWithNoRunLeavesNoPoint(@TempDir java.nio.file.Path root) {
        Rig rig = new Rig(root.toAbsolutePath());
        Map<String, Object> res = asMap(rig.methods.cancel(Map.of()));
        assertEquals(false, res.get("cancelled"),
                "there was no run to cancel: " + res);
        assertNull(res.get("checkpointId"),
                "a cancel that stopped nothing must not leave a point behind: " + res);
        assertTrue(rig.engine.codeCheckpoints().isEmpty(),
                "the session must not collect points nobody asked for");
    }

    /**
     * ★ THE RACE THIS PINS ★
     *
     * <p>The interrupt makes the worker finish, and the worker's cleanup
     * removes the run from the daemon's tracking maps. If the cancel
     * looked up the run's owner AFTER the interrupt, the lookup would
     * race that cleanup — and the point would silently not be marked
     * whenever the worker was quick.
     *
     * <p>That is not a hypothetical: it showed up as a flaky test in
     * this very round (the baseline run was RED while the single run
     * was GREEN). The fix is to resolve the owner BEFORE the
     * interrupt.
     */
    @Test
    void everyCancelLeavesItsPoint(@TempDir java.nio.file.Path root) throws Exception {
        Rig rig = new Rig(root.toAbsolutePath());
        int missing = 0;
        for (int i = 0; i < 8; i++) {
            rig.startQuery("turn " + i);
            Map<String, Object> cancelled = asMap(rig.methods.cancel(Map.of()));
            assertEquals(true, cancelled.get("cancelled"),
                    "iteration " + i + ": " + cancelled);
            if (cancelled.get("checkpointId") == null) {
                missing++;
                System.out.println("R403: iteration " + i + " left no point: " + cancelled);
            }
            // let the run settle so the next query can take the slot
            Thread.sleep(250);
        }
        assertEquals(0, missing,
                "every cancel must leave a point — " + missing + " of 8 did not");
    }

    // ------------------------------------------------------------------
    // ★ THE ORDER PIN ★
    // ------------------------------------------------------------------

    private static final String METHODS_SRC =
            "src/main/java/org/aethercode/protocol/methods/AetherCodeMethods.java";

    private static String readSource() {
        try {
            String[] candidates = new String[] {
                    METHODS_SRC,
                    "aethercode-protocol/" + METHODS_SRC,
                    "../aethercode-protocol/" + METHODS_SRC,
            };
            for (String c : candidates) {
                java.io.File f = new java.io.File(c);
                if (f.exists()) {
                    return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            throw new java.io.FileNotFoundException(
                    "AetherCodeMethods.java not found under " + java.util.Arrays.toString(candidates));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The body of the method whose signature starts with {@code marker}.
     *
     * <p>★ THE BRACE MATCHER SKIPS LITERALS AND COMMENTS ★ A brace
     * inside a string literal would close the body early — and then
     * every assertion over the rest of the method would be reading
     * nothing at all (the R735 lesson).
     */
    private static String methodBody(String src, String marker) {
        int start = src.indexOf(marker);
        if (start < 0) throw new IllegalStateException("method not found: " + marker);
        int open = src.indexOf('{', start);
        if (open < 0) throw new IllegalStateException("no body: " + marker);
        int depth = 0;
        boolean inStr = false;
        boolean inChar = false;
        boolean inLine = false;
        boolean inBlock = false;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            char n = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') inLine = false;
                continue;
            }
            if (inBlock) {
                if (c == '*' && n == '/') { inBlock = false; i++; }
                continue;
            }
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (inChar) {
                if (c == '\\') { i++; continue; }
                if (c == '\'') inChar = false;
                continue;
            }
            if (c == '/' && n == '/') { inLine = true; i++; continue; }
            if (c == '/' && n == '*') { inBlock = true; i++; continue; }
            if (c == '"') { inStr = true; continue; }
            if (c == '\'') { inChar = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(open, i + 1);
            }
        }
        throw new IllegalStateException("unbalanced body: " + marker);
    }

    /**
     * ★ THE ORDER INVARIANT ★
     *
     * <p>The cancel reads the run's owner from the tracking map
     * EXACTLY ONCE, and it does so BEFORE it interrupts the worker.
     *
     * <ul>
     *   <li><b>Exactly once</b> — a second read would be the one that
     *       races the teardown (the worker removes the run from the
     *       map as it finishes).</li>
     *   <li><b>Before the interrupt</b> — the interrupt is what makes
     *       the worker finish, so a read after it is a read that can
     *       lose the race.</li>
     * </ul>
     *
     * <p>★ THE PIN DOES NOT NAME THE VARIABLE ★ It counts the reads
     * of the tracking map and compares their position with the
     * interrupt — so a rename of the local variable does not turn this
     * pin red, while the defect it exists for does.
     */
    @Test
    void theRunsOwnerIsReadOnceAndBeforeTheInterrupt() {
        String src = readSource();
        String cancel = methodBody(src, "public Object cancel(Object params)");

        int first = cancel.indexOf("runEngines.get(runId)");
        assertTrue(first >= 0,
                "the cancel must read the run's owner from the tracking map");
        int second = cancel.indexOf("runEngines.get(runId)", first + 1);
        assertEquals(-1, second,
                "the owner must be read EXACTLY ONCE — a second read is the one "
                        + "that races the worker's teardown");
        int interrupt = cancel.indexOf(".interrupt()");
        assertTrue(interrupt >= 0, "the cancel must interrupt the worker");
        assertTrue(first < interrupt,
                "the owner must be captured BEFORE the interrupt — the interrupt "
                        + "is what makes the worker tear the run down");
    }
}
