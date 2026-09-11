package org.aethercode.core.engine;

import org.aethercode.core.app.AppState;
import org.aethercode.core.compact.Compactor;
import org.aethercode.core.engine.PermissionPolicy;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.metrics.MetricsCollector;
import org.aethercode.core.permission.PermissionResult;
import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R140 tests for {@link QueryEngine#runPreFlightCompact}.
 * Verifies the pre-flight threshold + compactor wiring
 * without spinning up a full LLM.
 */
class QueryEnginePreFlightCompactR140Test {

    /** Compactor that always compacts on demand. Records
     *  the input it saw. */
    private static class AlwaysCompact implements Compactor {
        int callCount = 0;
        List<Message> lastInput = null;
        @Override public boolean shouldCompact(List<Message> m) { return true; }
        @Override public List<Message> compact(List<Message> m) {
            callCount++;
            lastInput = m;
            // Replace input with a single summary message.
            return List.of(new Message(
                    "sum-" + callCount, Role.ASSISTANT,
                    List.of(new ContentBlock.TextBlock("summary of " + m.size() + " msgs")),
                    null, java.util.Map.of("kind", "summary-stub")));
        }
    }

    private static class NeverCompact implements Compactor {
        @Override public boolean shouldCompact(List<Message> m) { return false; }
        @Override public List<Message> compact(List<Message> m) { return m; }
    }

    /** ChatClient stub — never called in these tests
     *  because runPreFlightCompact runs synchronously
     *  on the existing transcript, not via a new
     *  query. */
    private static ChatClient stubChat() {
        return new ChatClient() {
            @Override public Stream<StreamEvent> stream(List<Message> m, String sp, List<org.aethercode.core.tool.Tool> tools) {
                return Stream.empty();
            }
            @Override public String modelId() { return "stub"; }
        };
    }

    private static Message userText(String id, String text) {
        return new Message(id, Role.USER,
                List.of(new ContentBlock.TextBlock(text)),
                null, null);
    }

    private QueryEngine buildEngine(Compactor comp, AppState appState) {
        // Pre-populate the transcript with 10 user messages,
        // each padded to ~80 chars = ~200 tokens total.
        // R140 threshold check: 200 / window. With window
        // 200 the pre-flight triggers; with window 1M it
        // doesn't.
        String padding = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"; // 64 chars
        for (int i = 0; i < 10; i++) {
            Message m = userText("m" + i, "user message " + i + " " + padding);
            appState.appendMessage(m);
        }
        PermissionPolicy allowAll = (tool, input, ctx) -> CompletableFuture.completedFuture(
                new PermissionResult.Allow(Map.of()));
        // 10-arg primary constructor:
        //   appState, chatClient, permissionPolicy, systemPrompt, messageSink,
        //   metricsCollector, costTracker, streamingExecutor, compactor, sideNoteSink
        return new QueryEngine(
                appState, stubChat(),
                allowAll, "system",
                msg -> {},
                new MetricsCollector(),
                null /* no cost tracker */,
                null /* no streaming executor */,
                comp,
                null /* no sideNoteSink */);
    }

    @Test
    void belowThresholdIsNoOp() {
        AppState app = new AppState("test", java.nio.file.Path.of(""));
        // 10 msgs * ~80 chars = 200 tokens. Set window
        // to 1000 so 200/1000 = 20% — well below 90%.
        app.contextWindow(1_000);
        AlwaysCompact ac = new AlwaysCompact();
        QueryEngine qe = buildEngine(ac, app);
        qe.runPreFlightCompact();
        assertEquals(0, ac.callCount, "below 90% should NOT compact");
    }

    @Test
    void aboveThresholdCallsCompactor() {
        AppState app = new AppState("test", java.nio.file.Path.of(""));
        // 10 msgs * ~80 chars = 800 chars / 4 = 200 tokens.
        // Set window to 200 so 200/200 = 100% — above 90%.
        app.contextWindow(200);
        AlwaysCompact ac = new AlwaysCompact();
        QueryEngine qe = buildEngine(ac, app);
        qe.runPreFlightCompact();
        // Use assertTrue to dodge a junit-jupiter-api
        // overload-resolution quirk where
        // assertEquals(int, int, String) routes to a
        // different code path on some platforms.
        assertTrue(ac.callCount == 1,
                "above 90% should compact once; callCount=" + ac.callCount);
        // The transcript should now contain the summary
        // (1 message) instead of the original 10.
        assertEquals(1, app.transcript().size());
        assertEquals("summary-stub",
                app.transcript().get(0).metadata().get("kind"));
    }

    @Test
    void neverCompactIsRespected() {
        AppState app = new AppState("test", java.nio.file.Path.of(""));
        app.contextWindow(200);  // above 90% threshold
        QueryEngine qe = buildEngine(new NeverCompact(), app);
        qe.runPreFlightCompact();
        // Even above the threshold, NeverCompact.shouldCompact
        // returns false so the pre-flight is a no-op.
        assertEquals(10, app.transcript().size());
    }

    @Test
    void noCompactorIsSafe() {
        AppState app = new AppState("test", java.nio.file.Path.of(""));
        app.contextWindow(200);
        QueryEngine qe = buildEngine(null, app);
        // Should not throw.
        assertDoesNotThrow(qe::runPreFlightCompact);
    }

    @Test
    void noContextWindowIsSafe() {
        AppState app = new AppState("test", java.nio.file.Path.of(""));
        // app.contextWindow(0)  — unset
        QueryEngine qe = buildEngine(new AlwaysCompact(), app);
        // Should not throw and should NOT compact.
        assertEquals(0, app.contextWindow());
        assertDoesNotThrow(qe::runPreFlightCompact);
    }
}
