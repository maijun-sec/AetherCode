package org.aethercode.core.app;

import org.junit.jupiter.api.Test;
import org.aethercode.core.message.Message;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * tests for the {@code onMessageAppend} listener
 * fan-out that the engine's {@code transcriptPush} wires
 * into. The contract the engine relies on:
 * <ol>
 *   <li>Every {@code appendMessage} after the
 *       subscription fires the consumer exactly once.</li>
 *   <li>Multiple listeners fan out in order.</li>
 *   <li>A listener that throws does NOT break the
 *       next listener (best-effort fan-out).</li>
 * </ol>
 * The detach path isn't tested because {@code AppState}
 * has no public remove API — the engine replaces the
 * listener list implicitly when {@code loadSession}
 * mutates the transcript directly (not via
 * {@code appendMessage}).
 */
class AppStateListenersTest {

    @Test
    void onMessageAppend_firesForEachAppend() {
        AppState s = new AppState("sid-1", Path.of("/tmp"));
        AtomicInteger count = new AtomicInteger();
        s.onMessageAppend(m -> count.incrementAndGet());
        s.appendMessage(Message.userText("a"));
        s.appendMessage(Message.userText("b"));
        s.appendMessage(Message.userText("c"));
        assertEquals(3, count.get());
    }

    @Test
    void onMessageAppend_multipleListenersFanOutInOrder() {
        AppState s = new AppState("sid-1", Path.of("/tmp"));
        List<String> order = new CopyOnWriteArrayList<>();
        s.onMessageAppend(m -> order.add("first:" + m.textContent()));
        s.onMessageAppend(m -> order.add("second:" + m.textContent()));
        s.appendMessage(Message.userText("hi"));
        // Both listeners see the same Message; the
        // order is the subscription order.
        assertEquals(2, order.size());
        assertEquals("first:hi",  order.get(0));
        assertEquals("second:hi", order.get(1));
    }

    @Test
    void onMessageAppend_throwingListenerDoesNotBreakPeers() {
        AppState s = new AppState("sid-1", Path.of("/tmp"));
        AtomicInteger peerCount = new AtomicInteger();
        // First listener throws. Second listener (the
        // engine's actual transcriptPush) must still
        // fire — appendMessage is best-effort fan-out
        // and a misbehaving listener must not corrupt
        // the transcript log.
        s.onMessageAppend(m -> { throw new RuntimeException("boom"); });
        s.onMessageAppend(m -> peerCount.incrementAndGet());
        s.appendMessage(Message.userText("hi"));
        assertEquals(1, peerCount.get());
    }

    @Test
    void onMessageAppend_payloadShapeMatchesR108_1Contract() {
        // The engine's listener wrapper reads
        // {@code m.toMap()} and wraps it in a
        // {@code {action:"append", sessionId, message}}
        // map. The Message itself reaches the
        // listener unchanged; this test pins the
        // contract that the listener gets the
        // canonical Message (not a stale or shared
        // instance).
        AppState s = new AppState("sid-x", Path.of("/tmp"));
        List<Message> received = new ArrayList<>();
        s.onMessageAppend(received::add);
        Message m = Message.userText("hello");
        s.appendMessage(m);
        assertEquals(1, received.size());
        // Same id, same role, same content — the
        // listener sees the canonical Message the
        // engine pushed.
        assertEquals(m.id(), received.get(0).id());
        assertEquals(m.role(), received.get(0).role());
        assertEquals("hello", received.get(0).textContent());
    }
}
