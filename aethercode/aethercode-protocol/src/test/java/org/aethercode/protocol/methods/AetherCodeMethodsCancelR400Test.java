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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R400 — the cancel RPC must actually stop things.
 *
 * <h2>★ THE DEFECT THIS GATE HOLDS ★</h2>
 *
 * <p>Pre-R400, {@code AetherCodeMethods.cancel} began with
 * {@code String runId = stringOrThrow(p, "runId");} — the run id was
 * MANDATORY. The desktop's stop button called
 * {@code rpc.cancel()} with <b>no parameters at all</b>, so the daemon
 * answered {@code -32602 Invalid params: missing required string
 * field: runId} — and the desktop swallowed that error with an empty
 * {@code catch {}}.
 *
 * <p>The visible result was exactly the user's report: the UI flipped
 * {@code isStreaming} to false (so it LOOKED stopped), the daemon kept
 * streaming, and because the run never ended with the stop reason
 * {@code cancelled}, the boulder hook's auto-continue was still allowed
 * to fire two seconds later — "明明已经停止了，结果隔了一会儿自己又
 * 继续跑".
 *
 * <h2>★ WHAT THIS GATE HOLDS ★</h2>
 *
 * <ul>
 *   <li><b>A cancel with no run id must NOT throw.</b> It must answer
 *       with a Map — the exact call shape the desktop used to make.</li>
 *   <li><b>It must find the run.</b> The daemon owns the run: the
 *       session's run lock already records which run holds the slot, so
 *       the daemon resolves "cancel this session's current run" on its
 *       own, and the run must actually end with {@code cancelled}.</li>
 *   <li><b>An unknown run id must be reported honestly</b> — not
 *       silently "cancelled".</li>
 *   <li><b>A cancel must disarm the auto-continue.</b> "Stop
 *       everything" includes the 2-second countdown that re-prompts the
 *       engine; otherwise the engine resurrects the run the user just
 *       killed.</li>
 * </ul>
 */
class AetherCodeMethodsCancelR400Test {

    /**
     * A chat client that blocks the worker thread inside
     * {@code stream()} until the cancel RPC interrupts it.
     *
     * <p>Same shape as {@code QueryEngineCancelR721Test}'s client: the
     * interrupt lands inside {@code sleep}, the flag is re-set so the
     * engine's top-of-iteration check sees it, and the empty stream
     * ends the turn. The engine then emits its terminal
     * {@code RunEnd("cancelled")}.
     */
    private static final class BlockingChatClient implements ChatClient {
        volatile Thread worker;
        @Override
        public Stream<StreamEvent> stream(List<Message> m, String sp, List<Tool> tools) {
            worker = Thread.currentThread();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return Stream.empty();
        }
        @Override public String modelId() { return "stub"; }
    }

    /** A daemon-side method table wired to a real engine. */
    private static final class Rig {
        final AetherCodeEngine engine;
        final AetherCodeMethods methods;
        final BlockingChatClient chat = new BlockingChatClient();
        final List<JsonRpcNotification> notifications = new CopyOnWriteArrayList<>();
        final AtomicReference<String> stopReason = new AtomicReference<>();

        Rig(java.nio.file.Path cwd) {
            engine = new AetherCodeEngine.Builder()
                    .cwd(cwd)
                    .tools(List.<Tool>of())
                    .build();
            engine.setChatClient(chat);
            methods = new AetherCodeMethods(engine, notifications::add);
        }

        /** Start a query and wait until the worker is really blocked. */
        String startQuery(String prompt) throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> accepted =
                    (Map<String, Object>) methods.query(Map.of("prompt", prompt));
            assertThat(accepted).containsEntry("accepted", true);
            String runId = (String) accepted.get("runId");
            assertThat(runId).isNotNull();
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && chat.worker == null) {
                Thread.sleep(10);
            }
            assertThat(chat.worker)
                    .as("the chat client never started — the run is not in flight")
                    .isNotNull();
            return runId;
        }

        /** Wait for the terminal run_end and remember its stop reason. */
        void awaitRunEnd() throws Exception {
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && stopReason.get() == null) {
                for (JsonRpcNotification n : notifications) {
                    Object params = n.params();
                    if (!(params instanceof Map)) continue;
                    Object ev = ((Map<?, ?>) params).get("event");
                    if (!(ev instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) ev;
                    if ("run_end".equals(m.get("type"))) {
                        stopReason.set((String) m.get("stopReason"));
                    }
                }
                if (stopReason.get() != null) return;
                Thread.sleep(20);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    /**
     * ★ THE REGRESSION PIN ★
     *
     * <p>This is the desktop's exact call: {@code rpc.cancel()} with
     * {@code params: {}}. Pre-R400 it raised
     * {@code JsonRpcProtocolException(-32602)} at the first line of the
     * handler, and the desktop's {@code catch {}} ate it. The stop
     * button therefore did NOTHING — the single most expensive defect
     * in the chain, because every other symptom ("输出还在跑", "自己又
     * 继续跑") was downstream of it.
     */
    @Test
    void cancelWithNoParamsDoesNotThrowInvalidParams(@TempDir java.nio.file.Path cwd) {
        Rig rig = new Rig(cwd);

        assertThatCode(() -> rig.methods.cancel(Map.of()))
                .as("a cancel with no params must NOT throw — the desktop sends exactly "
                        + "this shape, and pre-R400 the daemon answered -32602 "
                        + "'missing required string field: runId'")
                .doesNotThrowAnyException();

        Map<String, Object> res = asMap(rig.methods.cancel(Map.of()));
        assertThat(res)
                .as("with no run in flight the answer is an honest 'not cancelled'")
                .containsEntry("cancelled", false);
        assertThat(res.get("reason")).isNotNull();
    }

    /**
     * ★ THE FALLBACK ★
     *
     * <p>A cancel with no run id must resolve the session's CURRENT run
     * and stop it. The daemon owns the run — the session's run lock
     * already records which run holds the slot — so the client never
     * has to know the id.
     */
    @Test
    void cancelWithNoParamsStopsTheSessionsCurrentRun(@TempDir java.nio.file.Path cwd)
            throws Exception {
        Rig rig = new Rig(cwd);
        String runId = rig.startQuery("hello");

        Map<String, Object> res = asMap(rig.methods.cancel(Map.of()));

        assertThat(res)
                .as("the cancel must resolve the session's current run on its own")
                .containsEntry("cancelled", true);
        assertThat(res.get("runId"))
                .as("the resolved run id must be the one the query returned")
                .isEqualTo(runId);

        rig.awaitRunEnd();
        assertThat(rig.stopReason.get())
                .as("the run must END with the stop reason 'cancelled' — that is what "
                        + "the boulder hook's isDeterministicHardStopOf gate reads, and "
                        + "without it the auto-continue resurrects the killed run")
                .isEqualTo("cancelled");
    }

    /**
     * An unknown run id is reported honestly. Pre-R400 this shape was
     * impossible to reach (the id was mandatory), so the "no such run"
     * path had never been exercised through the wire.
     */
    @Test
    void cancelWithAnUnknownRunIdIsHonest(@TempDir java.nio.file.Path cwd) {
        Rig rig = new Rig(cwd);

        Map<String, Object> res = asMap(rig.methods.cancel(Map.of("runId", "run-does-not-exist")));

        assertThat(res)
                .as("an unknown run id must not be reported as cancelled")
                .containsEntry("cancelled", false);
        assertThat((String) res.get("reason")).contains("no such runId");
    }

    /**
     * ★ "STOP EVERYTHING" ★
     *
     * <p>A cancel must also disarm the session's automatic continuation.
     * The boulder hook schedules a 2-second countdown after a run ends
     * with unfinished todos and then re-prompts the engine. If the
     * cancel only killed the run, the countdown would fire two seconds
     * later and the engine would start a fresh run — which is precisely
     * the "自己又继续跑了" the user reported.
     *
     * <p>This has to live in the DAEMON. A client that issued two RPCs
     * (cancel, then setContinuationStopped) would leave a window in
     * which the countdown could fire.
     */
    @Test
    void cancelDisarmsTheAutoContinue(@TempDir java.nio.file.Path cwd) throws Exception {
        Rig rig = new Rig(cwd);
        rig.startQuery("hello");
        String sessionId = rig.engine.appState().sessionId();

        assertThat(rig.methods.continuationDispatcher().isContinuationStopped(sessionId))
                .as("precondition: the auto-continue is armed")
                .isFalse();

        rig.methods.cancel(Map.of());

        assertThat(rig.methods.continuationDispatcher().isContinuationStopped(sessionId))
                .as("a cancel must disarm the session's auto-continue — otherwise the "
                        + "boulder hook's 2s countdown resurrects the run the user just killed")
                .isTrue();
    }

    /**
     * The flag is per SESSION, and it is the NEXT user prompt that
     * re-arms it. A cancel therefore lasts until the user says
     * something — not forever.
     */
    @Test
    void theNextUserPromptReArmsTheAutoContinue(@TempDir java.nio.file.Path cwd)
            throws Exception {
        Rig rig = new Rig(cwd);
        rig.startQuery("hello");
        String sessionId = rig.engine.appState().sessionId();

        rig.methods.cancel(Map.of());
        assertThat(rig.methods.continuationDispatcher().isContinuationStopped(sessionId))
                .as("the cancel disarms it")
                .isTrue();

        // A new user prompt re-arms it. The query handler resets the
        // flag and cancels any pending countdown, so the user is never
        // locked out of the auto-continue by an old cancel.
        rig.methods.query(Map.of("prompt", "next"));
        assertThat(rig.methods.continuationDispatcher().isContinuationStopped(sessionId))
                .as("a fresh user prompt re-arms the auto-continue")
                .isFalse();
    }
}
