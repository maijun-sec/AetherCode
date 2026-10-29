/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.protocol.methods;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.aethercode.protocol.jsonrpc.JsonRpcMessage;
import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.sdk.AetherCodeEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Spliterators;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the chat client throws mid-stream (upstream 4xx/5xx/timeout), the
 * engine MUST terminate the run with exactly one {@code run_end} event that
 * carries {@code isError: true} and a {@code stopReason} naming the
 * failure.
 *
 * <h2>Why this test was rewritten (R750d)</h2>
 *
 * <p>This test asserted that {@code AetherCodeMethods.query()}'s
 * {@code catch (Throwable)} would emit a <em>synthetic</em> {@code run_end}
 * with {@code stopReason: "error: ..."} and {@code isError: true}. It had
 * been failing since R735a, and the recorded explanation was "pre-existing,
 * out of scope". That framing was wrong in a way worth stating plainly:
 *
 * <p><b>The path it asserted has been unreachable since R735a.</b> A throw
 * raised inside the engine lands in {@code QueryEngine.tryAdvance}'s outer
 * catch, which converts it into a terminal {@code RunEnd} and returns
 * normally. The exception therefore never propagates to
 * {@code AetherCodeMethods}, and the synthetic handler never runs. The
 * engine's own event is the only terminal event this shape produces.
 *
 * <p>So the test was not "waiting for a follow-up fix" — it was pinning a
 * branch that had become dead code, and it would have kept failing forever
 * while describing behaviour no user could reach.
 *
 * <h2>What was actually broken</h2>
 *
 * <p>Having established that the engine's RunEnd is authoritative, the real
 * defect fell out: {@code eventToMap}'s RunEnd branch emitted only
 * {@code type} + {@code stopReason}. It never set {@code isError}. The only
 * producer of that field on the wire was the unreachable synthetic handler,
 * so <em>every</em> engine-terminated failure reached consumers with no
 * error flag at all.
 *
 * <p>The desktop happened to cope because its {@code run_end} handler also
 * prefix-matches the stopReason (R735b). A stream-json consumer has no such
 * fallback — Multica classifies success from {@code is_error} alone — so an
 * upstream 500 was reported to the scheduler as a completed run.
 *
 * <p>R750d fixes it at the layer that owns the truth:
 * {@code StreamEvent.isErrorOutcome()} is now the single definition, and
 * both {@code eventToMap} and {@code ClaudeStreamJsonEmitter} delegate to
 * it. That also removed an allowlist/denylist disagreement the two layers
 * already had (see {@code ClaudeStreamJsonEmitter}).
 *
 * <p>The protocol's {@code catch (Throwable)} is deliberately KEPT. It is
 * still correct and still reachable for throws raised <em>outside</em> the
 * engine's stream — a broken notifier, a transcript-store failure while
 * appending the assistant message, and so on. This test asserts exactly one
 * terminal event so a future change cannot make both layers fire and end
 * the run twice.
 */
class QueryErrorNotificationTest {

    /** Build a stream whose first {@code tryAdvance} throws.
     *  The engine's QueryEngine Spliterator drives its
     *  tryAdvance on each call; the throw lands there and
     *  propagates to AetherCodeMethods.query()'s forEach
     *  → catch (Throwable) branch. */
    private static Stream<StreamEvent> throwingStream(RuntimeException boom) {
        Spliterators.AbstractSpliterator<StreamEvent> sp =
                new Spliterators.AbstractSpliterator<StreamEvent>(
                        Long.MAX_VALUE, java.util.Spliterator.ORDERED) {
            @Override
            public boolean tryAdvance(java.util.function.Consumer<? super StreamEvent> action) {
                throw boom;
            }
        };
        return StreamSupport.stream(sp, false);
    }

    @Test
    void query_catchEmitsRunEndAndErrorLog(@TempDir Path cwd) throws Exception {
        // R750d — rewritten. See the class javadoc: the assertion this test
        // used to make was not merely failing, it was describing a path that
        // has been unreachable since R735a.
        //
        // 1. Build a real engine and inject a broken
        //    chat client via setChatClient (the only
        //    public seam for swapping the LLM). The
        //    engine's full lifecycle (metrics, traces,
        //    appState) is real; only the LLM call is
        //    faked.
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        RuntimeException boom = new RuntimeException("upstream 503: gateway timeout");
        ChatClient throwing = new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
                return throwingStream(boom);
            }
            @Override public String modelId() { return "MiniMax-M3"; }
        };
        engine.setChatClient(throwing);

        // 2. Capture every notification the method
        //    emits. The dispatcher's notifier is
        //    consumer<JsonRpcNotification>.
        List<JsonRpcNotification> notifications = new CopyOnWriteArrayList<>();
        AetherCodeMethods methods = new AetherCodeMethods(engine, notifications::add);

        // 3. Fire the query. AetherCodeMethods.query()
        //    returns synchronously after spawning the
        //    worker thread; the work happens async.
        Object accepted = methods.query(Map.of("prompt", "write a poem"));
        assertThat(accepted).as("query must accept synchronously").isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> ar = (Map<String, Object>) accepted;
        assertThat(ar).containsEntry("accepted", true);
        String runId = (String) ar.get("runId");
        assertThat(runId).isNotNull();

        // 4. Wait for the worker thread to drain.
        //    R735a turns the throw into a terminal RunEnd
        //    inside the ENGINE, so the wait condition is
        //    "a run_end arrived" — not "a synthetic run_end
        //    arrived", which could never happen for a throw
        //    raised inside QueryEngine.
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline
                && notifications.stream().noneMatch(QueryErrorNotificationTest::isRunEnd)) {
            Thread.sleep(50);
        }
        if (notifications.isEmpty()) {
            throw new AssertionError("no notifications received in 5s — query() did not even start");
        }

        // 5. Exactly ONE terminal run_end, and it must carry the failure
        //    bit. Two terminal events would mean the protocol's synthetic
        //    handler fired on top of the engine's own — the desktop would
        //    then see the run end twice.
        List<Map<String, Object>> runEnds = notifications.stream()
                .filter(n -> AetherCodeMethods.NOTIFY_STREAM_EVENT.equals(n.method()))
                .map(QueryErrorNotificationTest::eventOf)
                .filter(java.util.Objects::nonNull)
                .filter(ev -> "run_end".equals(ev.get("type")))
                .toList();
        assertThat(runEnds)
                .as("a thrown stream MUST terminate with exactly one run_end "
                        + "(R735a: the engine catches it and emits its own)")
                .hasSize(1);
        Map<String, Object> runEndEvent = runEnds.get(0);

        // 6. R750d: the wire must carry isError=true. Before this round
        //    eventToMap emitted only type + stopReason, so the only producer
        //    of isError was the protocol catch — unreachable for engine
        //    throws — and an upstream 503 was indistinguishable from a clean
        //    end_turn at the field level. Multica classifies success from
        //    is_error alone, so it reported failures as successes.
        assertThat(runEndEvent)
                .as("run_end must carry isError=true so the renderer colours the entry "
                        + "and a stream-json consumer does not read a failure as success")
                .containsEntry("isError", true);
        assertThat((String) runEndEvent.get("stopReason"))
                .as("stopReason must name the failure so the desktop's failed-pill "
                        + "and the scheduler's diagnostics can read it")
                .startsWith("runtime_exception")
                .contains("upstream 503: gateway timeout");
        assertThat((String) runEndEvent.get("error"))
                .as("error field carries the detail for the retry affordance")
                .contains("upstream 503: gateway timeout");

        // 7. The wrapper carries runId so the renderer correlates the
        //    terminal event with its query.
        @SuppressWarnings("unchecked")
        Map<String, Object> runEndWrap = notifications.stream()
                .filter(n -> AetherCodeMethods.NOTIFY_STREAM_EVENT.equals(n.method()))
                .map(n -> (Map<String, Object>) n.params())
                .filter(p -> runEndEvent.equals(p.get("event")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("terminal run_end had no wrapper"));
        assertThat(runEndWrap)
                .as("the wrapper carries the runId so the renderer can correlate with its query")
                .containsEntry("runId", runId);
    }

    /** the inner `event` map of a stream_event notification, or null. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> eventOf(JsonRpcNotification n) {
        Object params = n.params();
        if (!(params instanceof Map)) return null;
        Object ev = ((Map<String, Object>) params).get("event");
        return ev instanceof Map ? (Map<String, Object>) ev : null;
    }

    private static boolean isRunEnd(JsonRpcNotification n) {
        Map<String, Object> ev = eventOf(n);
        return ev != null && "run_end".equals(ev.get("type"));
    }
}
