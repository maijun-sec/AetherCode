package org.aethercode.memory;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for the working-memory wiring + strategy LLM
 * extraction on {@link MemoryLifecycle}.
 *
 * <p>prior round lands the {@code wm_*} tools (covered separately in
 * {@code WorkingMemoryToolsTest}). prior round adds LLM-backed strategy
 * extraction at Tier 3; this file focuses on the lifecycle side:
 * setter wiring, the {@code maybeExtractStrategy} short-circuits,
 * and the response parsing for "NONE" and well-formed output.
 */
class MemoryLifecycleR233Test {

    private Path tmp;
    private LayeredMemoryStore store;
    private SessionMemoryStore session;
    private MemoryAudit audit;
    private MemoryLifecycle lifecycle;
    private Path projectCwd;

    @BeforeEach
    void setUp() throws Exception {
        tmp = Files.createTempDirectory("memory-lifecycle-r233-");
        Path memoryBase = tmp.resolve("base");
        Path sessionDb = tmp.resolve("sessions.db");
        session = new SessionMemoryStore(sessionDb);
        ProjectMemoryCompressor.ChatClient noop = p -> java.util.Optional.empty();
        ProjectMemoryCompressor compressor = new ProjectMemoryCompressor(noop);
        store = new LayeredMemoryStore(memoryBase, "test-agent", session, compressor,
                50, 10, true);
        audit = new MemoryAudit(tmp.resolve("audit.log"));
        audit.setEnabled(true);
        MemoryAudit.setInstanceForTesting(audit);
        projectCwd = tmp.resolve("projA");
        Files.createDirectories(projectCwd);
        MemoryLifecycle.Config cfg = new MemoryLifecycle.Config(
                true, 50L /*decayIntervalMs*/, 100, 2000, 1500, 1, true, true);
        lifecycle = new MemoryLifecycle("sess-r233", "test-agent", store,
                ForgettingPolicy.defaults(), audit, cfg);
        lifecycle.setProjectCwd(projectCwd.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (lifecycle != null) lifecycle.stop();
        MemoryAudit.clearForTesting();
        if (session != null) session.close();
        if (tmp != null) {
            Files.walk(tmp)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) {} });
        }
    }

    @Test
    void setStrategyChatClientStoresAndRetrieves() {
        assertTrue(lifecycle.strategyChatClient().isEmpty(),
                "no strategy chat client should be wired by default");
        ChatClient stub = stubChat("rule: do X then Y");
        lifecycle.setStrategyChatClient(stub);
        assertTrue(lifecycle.strategyChatClient().isPresent());
        assertSame(stub, lifecycle.strategyChatClient().get());
    }

    @Test
    void setStrategyChatClientNullIsNoOp() {
        // Contract: null setter is idempotent — it doesn't clobber an
        // already-wired chat client. (We don't expose a clear() method
        // because the strategy client is daemon-scoped: once wired, it
        // stays for the lifetime of the process.)
        lifecycle.setStrategyChatClient(stubChat("hello"));
        assertTrue(lifecycle.strategyChatClient().isPresent());
        lifecycle.setStrategyChatClient(null);
        assertTrue(lifecycle.strategyChatClient().isPresent(),
                "null setter should be a no-op when one was already wired");
    }

    @Test
    void maybeExtractStrategyReturnsNullWithoutChatClient() {
        List<Message> transcript = List.of(
                Message.userText("how do I debug maven?"),
                Message.assistantText("mvn -X is the verbose flag"));
        assertNull(lifecycle.maybeExtractStrategy(transcript, Instant.now()),
                "no chat client wired -> null");
    }

    @Test
    void maybeExtractStrategyReturnsNullOnEmptyTranscript() {
        lifecycle.setStrategyChatClient(stubChat("never reached"));
        assertNull(lifecycle.maybeExtractStrategy(List.of(), Instant.now()));
    }

    @Test
    void maybeExtractStrategyReturnsNullOnNullTranscript() {
        lifecycle.setStrategyChatClient(stubChat("never reached"));
        assertNull(lifecycle.maybeExtractStrategy(null, Instant.now()));
    }

    @Test
    void maybeExtractStrategyReturnsNullOnTooShortTranscript() {
        lifecycle.setStrategyChatClient(stubChat("never reached"));
        // 30 char threshold — a single word is well below
        assertNull(lifecycle.maybeExtractStrategy(
                List.of(Message.userText("hi")), Instant.now()));
    }

    @Test
    void maybeExtractStrategyReturnsNullWhenLlmSaysNone() {
        lifecycle.setStrategyChatClient(stubChat("NONE"));
        List<Message> transcript = makeTranscript(
                "the maven build keeps failing on dependency resolution",
                "try mvn -U to force update",
                "great, that fixed it");
        assertNull(lifecycle.maybeExtractStrategy(transcript, Instant.now()));
    }

    @Test
    void maybeExtractStrategyReturnsNullWhenLlmSaysNoneCaseInsensitive() {
        lifecycle.setStrategyChatClient(stubChat("  none  "));
        List<Message> transcript = makeTranscript(
                "the maven build keeps failing on dependency resolution",
                "try mvn -U to force update",
                "great, that fixed it");
        assertNull(lifecycle.maybeExtractStrategy(transcript, Instant.now()));
    }

    @Test
    void maybeExtractStrategyReturnsNullWhenLlmReturnsEmpty() {
        lifecycle.setStrategyChatClient(stubChat(""));
        List<Message> transcript = makeTranscript(
                "the maven build keeps failing on dependency resolution",
                "try mvn -U to force update",
                "great, that fixed it");
        assertNull(lifecycle.maybeExtractStrategy(transcript, Instant.now()));
    }

    @Test
    void maybeExtractStrategyProducesRecord() {
        lifecycle.setStrategyChatClient(stubChat(
                "When facing maven dependency resolution failures, run mvn -U first."));
        List<Message> transcript = makeTranscript(
                "the maven build keeps failing on dependency resolution",
                "try mvn -U to force update",
                "great, that fixed it");
        ExperienceRecord rec = lifecycle.maybeExtractStrategy(transcript, Instant.now());
        assertNotNull(rec, "should produce a record when LLM returns strategy text");
        assertEquals(ExperienceKind.STRATEGY, rec.kind());
        assertTrue(rec.title().contains("strategy"),
                "title should be prefixed with 'strategy', got: " + rec.title());
        assertTrue(rec.body().contains("mvn -U"),
                "body should include LLM output, got: " + rec.body());
        assertEquals("sess-r233", rec.sourceSessionId());
    }

    @Test
    void maybeExtractStrategyTruncatesLongLlmOutput() {
        // Build a 5000-char response (well over 1500 cap)
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) big.append("abcdefghij");
        lifecycle.setStrategyChatClient(stubChat(big.toString()));
        List<Message> transcript = makeTranscript(
                "a".repeat(40), "b".repeat(40), "c".repeat(40));
        ExperienceRecord rec = lifecycle.maybeExtractStrategy(transcript, Instant.now());
        assertNotNull(rec);
        // The distilled strategy section is capped at strategyBodyMaxChars
        assertTrue(rec.body().contains("…"),
                "long body should be truncated with ellipsis, got length: " + rec.body().length());
    }

    @Test
    void maybeExtractStrategySwallowsLlmException() {
        lifecycle.setStrategyChatClient(brokenChat());
        List<Message> transcript = makeTranscript(
                "a".repeat(40), "b".repeat(40), "c".repeat(40));
        // Must not throw — returns null on stream failure
        assertNull(lifecycle.maybeExtractStrategy(transcript, Instant.now()));
    }

    @Test
    void maybeExtractStrategyRecordsAudit() throws Exception {
        lifecycle.setStrategyChatClient(stubChat("Always run mvn -U before retrying."));
        List<Message> transcript = makeTranscript(
                "the maven build keeps failing on dependency resolution",
                "try mvn -U to force update",
                "great, that fixed it");
        ExperienceRecord rec = lifecycle.maybeExtractStrategy(transcript, Instant.now());
        assertNotNull(rec);
        var log = tmp.resolve("audit.log");
        assertTrue(Files.exists(log));
        String content = Files.readString(log);
        assertTrue(content.contains("\"kind\":\"strategy\""),
                "audit should log strategy extraction, got: " + content);
        assertTrue(content.contains(rec.id()),
                "audit should include the record id, got: " + content);
    }

    // ---- helpers ----

    /** Build a transcript of three messages — one user, one assistant, one user. */
    private static List<Message> makeTranscript(String user1, String asst, String user2) {
        List<Message> t = new ArrayList<>();
        t.add(Message.userText(user1));
        t.add(Message.assistantText(asst));
        t.add(Message.userText(user2));
        return t;
    }

    /** Build a {@link ChatClient} whose {@code stream()} yields a fixed text response
     *  followed by {@code RunEnd}. */
    private static ChatClient stubChat(String responseText) {
        return new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<org.aethercode.core.tool.Tool> tools) {
                List<StreamEvent> evs = new ArrayList<>();
                evs.add(new StreamEvent.RunStart("test-run", "stub-model"));
                if (responseText != null && !responseText.isEmpty()) {
                    evs.add(new StreamEvent.TextDelta(responseText));
                }
                evs.add(new StreamEvent.RunEnd("end_turn", List.of()));
                return evs.stream();
            }
            @Override
            public String modelId() { return "stub-model"; }
        };
    }

    /** ChatClient that throws on stream — used to test the catch-all path. */
    private static ChatClient brokenChat() {
        return new ChatClient() {
            @Override
            public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<org.aethercode.core.tool.Tool> tools) {
                throw new RuntimeException("simulated LLM outage");
            }
            @Override
            public String modelId() { return "broken-stub"; }
        };
    }
}
