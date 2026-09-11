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
 * when the chat client throws mid-stream
 * (e.g. an upstream 4xx / 5xx / timeout), the
 * engine's {@code forEach} consumer throws, the
 * {@code query()} catch block fires, and we must:
 * <ol>
 *   <li>emit a {@code NOTIFY_LOG} with
 *       {@code level: "error"} so the desktop
 *       surfaces the message in the chat (prior round
 *       fixes the renderer to honour that field);
 *   <li>emit a {@code NOTIFY_STREAM_EVENT}
 *       shaped as a synthetic {@code run_end}
 *       with {@code stopReason: "error: ..."}
 *       and {@code isError: true} so the
 *       renderer's {@code run_end} handler
 *       flips {@code isStreaming} back to
 *       {@code false} (without this the input
 *       box stays disabled and the user reads
 *       it as "the task died silently").</li>
 * </ol>
 *
 * <p>The previous (legacy-R) behaviour emitted
 * only the {@code NOTIFY_LOG} and never a
 * synthetic {@code run_end}, so the renderer's
 * input box stayed disabled forever and the
 * {@code NOTIFY_LOG} was silently swallowed by
 * a too-eager "skip if level is info" default
 * in the renderer.
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
        //    The catch block is reached on the first
        //    LLM iterator step (the broken stream
        //    throws immediately on tryAdvance), so a
        //    short poll is enough.
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline
                && notifications.stream().noneMatch(n ->
                        AetherCodeMethods.NOTIFY_STREAM_EVENT.equals(n.method())
                                && ((Map<String, Object>) n.params()).get("event") != null
                                && "run_end".equals(((Map<String, Object>) ((Map<String, Object>) n.params()).get("event")).get("type"))
                                && Boolean.TRUE.equals(((Map<String, Object>) ((Map<String, Object>) n.params()).get("event")).get("isError")))) {
            Thread.sleep(50);
        }
        if (notifications.isEmpty()) {
            throw new AssertionError("no notifications received in 5s — query() did not even start");
        }
        System.err.println("[对应历史 round test] all notifications: " + notifications.stream()
                .map(n -> n.method() + " " + n.params())
                .toList());

        // 5. a synthetic run_end event MUST
        //    be present (this is the field that flips
        //    isStreaming back to false in the
        //    renderer).
        List<JsonRpcNotification> streamEvents = notifications.stream()
                .filter(n -> AetherCodeMethods.NOTIFY_STREAM_EVENT.equals(n.method()))
                .toList();
        assertThat(streamEvents)
                .as("query catch MUST emit at least one stream_event (对应历史 round synthetic run_end)")
                .isNotEmpty();
        // Find the synthetic run_end — the engine
        // may have emitted a normal run_end first
        // (unlikely with our broken client, but be
        // defensive). The synthetic one has isError
        // and stopReason starting with "error: ".
        @SuppressWarnings("unchecked")
        Map<String, Object> runEndWrap = streamEvents.stream()
                .map(n -> (Map<String, Object>) n.params())
                .filter(p -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ev = (Map<String, Object>) p.get("event");
                    return ev != null
                            && "run_end".equals(ev.get("type"))
                            && Boolean.TRUE.equals(ev.get("isError"));
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no synthetic run_end with isError=true found in stream_events; got: "
                                + streamEvents.stream()
                                        .map(n -> n.params() == null ? "null" : n.params().toString())
                                        .toList()));
        @SuppressWarnings("unchecked")
        Map<String, Object> runEndEvent = (Map<String, Object>) runEndWrap.get("event");
        assertThat((String) runEndEvent.get("stopReason"))
                .as("stopReason must start with 'error: ' and include the throwable message")
                .startsWith("error: ")
                .contains("upstream 503: gateway timeout");
        assertThat(runEndEvent)
                .as("event must carry isError=true so the renderer can colour the entry")
                .containsEntry("isError", true);
        assertThat(runEndWrap)
                .as("the wrapper carries the runId so the renderer can correlate with its synthetic query")
                .containsEntry("runId", runId);

        // 6. the matching NOTIFY_LOG must
        //    carry level=error so the renderer's
        //    (now-fixed) log handler surfaces the
        //    line in the chat as a system message.
        List<JsonRpcNotification> logNotifs = notifications.stream()
                .filter(n -> AetherCodeMethods.NOTIFY_LOG.equals(n.method()))
                .toList();
        assertThat(logNotifs)
                .as("query catch MUST emit a NOTIFY_LOG with level=error (对应历史 round contract)")
                .isNotEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> logParams = (Map<String, Object>)
                logNotifs.get(logNotifs.size() - 1).params();
        assertThat(logParams)
                .as("NOTIFY_LOG params must include level=error so the renderer doesn't swallow it")
                .containsEntry("level", "error");
        assertThat((String) logParams.get("error"))
                .as("error field must include the throwable message")
                .contains("upstream 503: gateway timeout");
    }
}
