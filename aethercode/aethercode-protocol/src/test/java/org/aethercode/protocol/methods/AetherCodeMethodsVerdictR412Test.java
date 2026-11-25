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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.profile.ProfileRegistry;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R412 — THE VERDICT ON THE POLLED STATE.
 *
 * <h2>★ THE GAP THIS GATE HOLDS ★</h2>
 *
 * <p>Until R412 the daemon's verdict (what kind of turn this is, which
 * posture it picked, WHY) travelled ONLY on the {@code run_start}
 * event. That made it visible for exactly as long as a run was
 * streaming. After a reload, a reconnect or a session switch the
 * desktop had nothing to show — and those are precisely the moments
 * a user asks "why did it do that?".
 *
 * <p>R401 promised the user a one-click correction for a
 * mis-classified turn. A correction needs a PREMISE: the user has to
 * SEE what the agent decided. Without the verdict on the polled
 * state, the promise is only payable while the run is live.
 *
 * <h2>★ WHAT IS PINNED ★</h2>
 *
 * <ul>
 *   <li><b>The verdict is on {@code getState}</b> — the RPC the
 *       desktop actually polls. Not on some other method that the
 *       desktop does not call (the R383 lesson: the verdict has to
 *       come from the daemon, on the wire the client reads).</li>
 *   <li><b>The key names are the EVENT's.</b> {@code intent} /
 *       {@code intentLabel} / {@code profile} / {@code profileLabel}
 *       are the names {@code run_start} already uses, so a client
 *       reads the verdict from either carrier with ONE parser and the
 *       two cannot drift.</li>
 *   <li><b>The REASON travels too.</b> A label ("未识别") says what
 *       the agent decided; only the reason ("no intent signal") says
 *       why, and "why" is the question a user asks when the decision
 *       looks wrong.</li>
 *   <li><b>The vocabulary travels too.</b> {@code profiles} is the
 *       registry's list — the "one click to correct" picker is empty
 *       after a reload unless the daemon re-sends it.</li>
 * </ul>
 */
class AetherCodeMethodsVerdictR412Test {

    private static final class RecordingClient implements ChatClient {
        @Override
        public Stream<StreamEvent> stream(List<Message> m, String sp, List<Tool> tools) {
            return Stream.of(
                    new StreamEvent.RunStart("r1", "stub"),
                    new StreamEvent.TextDelta("ok"),
                    new StreamEvent.RunEnd("end_turn", List.of()));
        }
        @Override public String modelId() { return "stub"; }
    }

    private static final class Rig {
        final AetherCodeEngine engine;
        final AetherCodeMethods methods;
        final List<JsonRpcNotification> notifications = new CopyOnWriteArrayList<>();

        Rig(Path cwd) {
            engine = new AetherCodeEngine.Builder()
                    .cwd(cwd)
                    .tools(List.of(stubTool("file_read")))
                    .build();
            engine.setChatClient(new RecordingClient());
            methods = new AetherCodeMethods(engine, notifications::add);
        }
    }

    private static Tool stubTool(String name) {
        return org.aethercode.core.tool.Tools.build(new org.aethercode.core.tool.ToolDef(
                name, name, Map.of("type", "object", "properties", Map.of()),
                (input, ctx) -> java.util.concurrent.CompletableFuture.completedFuture(
                        Tool.ToolResult.of("ok"))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    // ------------------------------------------------------------------
    // the polled state carries the verdict
    // ------------------------------------------------------------------

    @Test
    void thePolledStateCarriesTheIntent(@TempDir Path cwd) throws Exception {
        Rig rig = new Rig(cwd);
        awaitCodeTurn(rig);

        Map<String, Object> state = asMap(rig.methods.getState(Map.of()));
        assertThat(state.get("intent")).isEqualTo("code_change");
        assertThat(state.get("intentLabel")).isEqualTo("代码生成");
    }

    @Test
    void thePolledStateCarriesTheProfile(@TempDir Path cwd) throws Exception {
        Rig rig = new Rig(cwd);
        awaitCodeTurn(rig);

        Map<String, Object> state = asMap(rig.methods.getState(Map.of()));
        assertThat(state.get("profile")).isEqualTo("builder");
        assertThat(state.get("profileLabel")).isEqualTo("写代码");
    }

    @Test
    void thePolledStateCarriesTheReason(@TempDir Path cwd) throws Exception {
        Rig rig = new Rig(cwd);
        awaitCodeTurn(rig);

        Map<String, Object> state = asMap(rig.methods.getState(Map.of()));
        // ★ NOT EMPTY, NOT BLANK ★ a reason of "" would render as an
        // empty "判定依据：" line — a second copy of "no information"
        // that looks like information.
        assertThat(state.get("intentReason"))
                .as("the state must carry WHY the agent decided that")
                .isNotNull();
        assertThat((String) state.get("intentReason")).isNotBlank();
        // and it must be the classifier's own reason, not a copy of the
        // label: the label is "代码生成", the reason names the signal.
        assertThat((String) state.get("intentReason"))
                .isNotEqualTo(state.get("intentLabel"));
    }

    @Test
    void theEventAndTheStateAgreeOnTheVerdict(@TempDir Path cwd) throws Exception {
        // ★ ONE VOCABULARY, TWO CARRIERS ★ the event and the polled
        // state must agree, otherwise the client needs two parsers and
        // the two will drift — and the chip would show one verdict while
        // the run was another.
        Rig rig = new Rig(cwd);
        awaitCodeTurn(rig);

        Map<String, Object> runStart = awaitRunStart(rig);
        Map<String, Object> state = asMap(rig.methods.getState(Map.of()));

        for (String key : List.of("intent", "intentLabel", "profile", "profileLabel")) {
            assertThat(state).containsKey(key);
            assertThat(runStart).containsKey(key);
            assertThat(state.get(key))
                    .as("the state and the event must carry the SAME value for " + key)
                    .isEqualTo(runStart.get(key));
        }
    }

    @Test
    void theEventCarriesTheReasonToo(@TempDir Path cwd) throws Exception {
        Rig rig = new Rig(cwd);
        awaitCodeTurn(rig);

        Map<String, Object> runStart = awaitRunStart(rig);
        assertThat(runStart.get("intentReason")).isNotNull();
        assertThat((String) runStart.get("intentReason")).isNotBlank();
    }

    // ------------------------------------------------------------------
    // the override + the vocabulary travel with it
    // ------------------------------------------------------------------

    @Test
    void thePolledStateCarriesTheOverrideAndTheVocabulary(@TempDir Path cwd) {
        Rig rig = new Rig(cwd);
        Map<String, Object> state = asMap(rig.methods.getState(Map.of()));

        // ★ THE LOCK ICON MUST NOT LIE ★ an override that this client
        // set is known to it; after a reload it is NOT, and the daemon
        // is the only one that knows. So the daemon has to say it.
        assertThat(state).containsKey("profileOverride");
        assertThat(state.get("profileOverride")).isNull();

        rig.methods.setProfile(Map.of("profile", "researcher"));
        Map<String, Object> after = asMap(rig.methods.getState(Map.of()));
        assertThat(after.get("profileOverride")).isEqualTo("researcher");
    }

    @Test
    void thePolledStateCarriesTheWholeProfileVocabulary(@TempDir Path cwd) {
        Rig rig = new Rig(cwd);
        Map<String, Object> state = asMap(rig.methods.getState(Map.of()));
        List<?> profiles = (List<?>) state.get("profiles");
        // ★ THE LIST IS THE REGISTRY'S ★ not a copy, not a subset.
        // The picker's entries are the daemon's, so a profile the
        // daemon added appears without a renderer change.
        assertThat(profiles).hasSize(ProfileRegistry.names().size());
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Object o : profiles) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            names.add((String) m.get("name"));
            assertThat((String) m.get("label")).isNotBlank();
        }
        assertThat(names).containsExactlyElementsOf(ProfileRegistry.names());
    }

    // ------------------------------------------------------------------
    // a session that has not run yet
    // ------------------------------------------------------------------

    @Test
    void aSessionThatHasNotRunYetHasNoVerdict(@TempDir Path cwd) {
        Rig rig = new Rig(cwd);
        Map<String, Object> state = asMap(rig.methods.getState(Map.of()));

        // no verdict — that is the truth, and the client must be able
        // to tell "no verdict" from "no field at all".
        assertThat(state).containsKey("intent");
        assertThat(state.get("intent")).isNull();
        assertThat(state.get("intentLabel")).isNull();
        assertThat(state.get("intentReason")).isNull();
        assertThat(state.get("profile")).isNull();
        assertThat(state.get("profileLabel")).isNull();

        // ★ BUT THE PICKER MUST STILL WORK ★ the "one click to
        // correct" promise does not need a verdict to be actionable:
        // the user may want to PRE-empt a verdict. Without the
        // vocabulary the dropdown is empty and the promise is
        // unpayable before the first turn.
        assertThat((List<?>) state.get("profiles"))
                .hasSize(ProfileRegistry.names().size());
    }

    @Test
    void theStateIsStillUsableWhenTheSessionIsUnknown(@TempDir Path cwd) {
        Rig rig = new Rig(cwd);
        Map<String, Object> r = asMap(rig.methods.getState(Map.of("sessionId", "no-such-session")));
        // the honest answer — not an exception, not a half-filled map.
        assertThat(r).containsEntry("ok", false);
        assertThat((String) r.get("error")).contains("no-such-session");
    }

    /**
     * ★ THE LEAK THIS ROUND FOUND ★ one engine serves MANY sessions
     * (the daemon registers the same engine under a new session id), so
     * the verdict — and the profile that outlives it — are shared. A
     * session that has not run yet therefore reported the PREVIOUS
     * session's verdict on the wire, and the desktop's "clear the chip
     * on a session switch" was undone by the next poll.
     */
    @Test
    void aSessionSwitchTakesTheVerdictWithIt(@TempDir Path cwd) throws Exception {
        // ★ THE STORE LIVES OUTSIDE @TempDir ★ the SQLite index file can
        // be held open for a moment after the last connection closes (the
        // driver, or a Windows indexer scanning the fresh file), and a
        // locked file makes JUnit's @TempDir cleanup FAIL — which fails
        // the test for a reason that has nothing to do with the verdict.
        // The store gets its own temp dir, deleted best-effort below.
        Path storeDir = java.nio.file.Files.createTempDirectory("aethercode-r412-store");
        try {
            AetherCodeEngine engine = new AetherCodeEngine.Builder()
                    .cwd(cwd)
                    .tools(List.of(stubTool("file_read")))
                    .sessionId("s1")
                    .sessionStore(new org.aethercode.core.transcript.SessionStore(storeDir))
                    .build();
            engine.setChatClient(new RecordingClient());
            AetherCodeMethods methods = new AetherCodeMethods(engine, n -> { });

            @SuppressWarnings("unchecked")
            Map<String, Object> accepted =
                    (Map<String, Object>) methods.query(Map.of("prompt", "实现一个 LRU 缓存"));
            assertThat(accepted).containsEntry("accepted", true);

            // the turn's verdict is on the wire
            Map<String, Object> before = asMap(methods.getState(Map.of()));
            assertThat(before.get("intent")).isEqualTo("code_change");
            assertThat(before.get("profile")).isEqualTo("builder");

            // the user walks away to another session.
            String other = engine.createSession(cwd.toString());
            engine.loadSession(other);

            Map<String, Object> after = asMap(methods.getState(Map.of()));
            // ★ NO VERDICT ★ it described the previous session's turn.
            assertThat(after.get("intent")).isNull();
            assertThat(after.get("intentLabel")).isNull();
            assertThat(after.get("intentReason")).isNull();
            // ★ AND NO PROFILE EITHER ★ the verdict is a UNIT: the profile
            // alone can outlive the turn it belongs to (it is deliberately
            // not cleared — it is the live restriction a running parent turn
            // needs), so reporting it alone would report another session's
            // posture as this one's.
            assertThat(after.get("profile")).isNull();
            assertThat(after.get("profileLabel")).isNull();
            // but the vocabulary still travels — the picker must work.
            assertThat((List<?>) after.get("profiles"))
                    .hasSize(ProfileRegistry.names().size());
        } finally {
            deleteTreeQuietly(storeDir);
        }
    }

    /**
     * delete a tree, retrying while a file is still locked.
     *
     * <p>★ WHY THE RETRY ★ the SQLite index can be held for a moment
     * after the last connection closes; a locked file makes the delete
     * fail, and a leaked temp dir is better than a red build.
     */
    private static void deleteTreeQuietly(Path root) {
        for (int attempt = 0; attempt < 25; attempt++) {
            try {
                if (!java.nio.file.Files.exists(root)) return;
                try (var walk = java.nio.file.Files.walk(root)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) { }
                    });
                }
                if (!java.nio.file.Files.exists(root)) return;
            } catch (Exception ignored) { }
            try { Thread.sleep(100); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** run one code-change turn and wait for its run_start frame. */
    private static Map<String, Object> awaitCodeTurn(Rig rig) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> accepted =
                (Map<String, Object>) rig.methods.query(Map.of("prompt", "实现一个 LRU 缓存"));
        assertThat(accepted).containsEntry("accepted", true);
        return awaitRunStart(rig);
    }

    private static Map<String, Object> awaitRunStart(Rig rig) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            for (JsonRpcNotification n : rig.notifications) {
                Object params = n.params();
                if (!(params instanceof Map)) continue;
                Object ev = ((Map<?, ?>) params).get("event");
                if (!(ev instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) ev;
                if ("run_start".equals(m.get("type"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> copy = new java.util.LinkedHashMap<>((Map<String, Object>) m);
                    return copy;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no run_start frame arrived; notifications: " + rig.notifications);
    }
}
