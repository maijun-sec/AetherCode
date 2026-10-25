package org.aethercode.core;

import org.aethercode.core.agent.AgentRegistry;
import org.aethercode.core.app.AppState;
import org.aethercode.core.cost.CostTracker;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.transcript.Transcript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R379 — the three gaps the TUI used to REFUSE, closed at the core.
 *
 * <p>Until R379 the TUI's refusal table carried three entries, and each
 * one was a REAL gap in the daemon rather than a name mismatch:
 *
 * <ul>
 *   <li>{@code agent/setDefault} — {@link AgentRegistry} had no notion of
 *       a default agent at all;</li>
 *   <li>{@code rewind} — {@link AppState} only appended, and a transcript
 *       could not be rolled back;</li>
 *   <li>{@code session/tokens} — {@link CostTracker} accounted tokens by
 *       MODEL only, so a per-session number could not be produced.</li>
 * </ul>
 *
 * <p>These are the core-level contracts. The wire-level contracts (the
 * RPC names, the stdio registration, the HTTP route) are pinned by
 * {@code AetherCodeMethodsR379Test} and
 * {@code HttpJsonRpcServerR379Test} in the protocol module — a core
 * method that exists but is never reachable is exactly the R367
 * failure, so the two halves have to be pinned separately.
 *
 * <p><b>What each case refuses to accept:</b>
 * <ul>
 *   <li>a per-session counter that silently returns the GLOBAL total —
 *       that is the shape the pre-R379 tracker had;</li>
 *   <li>a truncate that drops the wrong tail;</li>
 *   <li>a default agent that can point at an agent which does not
 *       exist (a silent lie).</li>
 * </ul>
 */
class R379GapClosersTest {

    // ------------------------------------------------------------------
    // session/tokens — per-session accounting
    // ------------------------------------------------------------------

    @Test
    void costTrackerKeepsTokensPerSession() {
        CostTracker t = new CostTracker();
        t.record("s-1", "claude-sonnet-4-5", new CostTracker.Usage(100, 20));
        t.record("s-1", "claude-sonnet-4-5", new CostTracker.Usage(50, 5));
        t.record("s-2", "claude-sonnet-4-5", new CostTracker.Usage(7, 3));

        var s1 = t.sessionSummary("s-1");
        assertEquals(150, s1.totalInput(), "s-1 input");
        assertEquals(25, s1.totalOutput(), "s-1 output");

        var s2 = t.sessionSummary("s-2");
        assertEquals(7, s2.totalInput(), "s-2 input");
        assertEquals(3, s2.totalOutput(), "s-2 output");
    }

    @Test
    void costTrackerSessionSummaryIsNotTheGlobalSummary() {
        // The pre-R379 tracker had exactly one set of counters, so
        // "the session's usage" could only ever be the process-wide
        // total. Pin the difference explicitly: two sessions, and the
        // per-session number must be SMALLER than the global one.
        CostTracker t = new CostTracker();
        t.record("s-1", "claude-sonnet-4-5", new CostTracker.Usage(10, 1));
        t.record("s-2", "claude-sonnet-4-5", new CostTracker.Usage(90, 9));

        long global = t.summary().totalInput();
        long perSession = t.sessionSummary("s-1").totalInput();
        assertEquals(100, global, "the global total still counts every session");
        assertEquals(10, perSession, "the per-session total must not include s-2");
        assertTrue(perSession < global,
                "a per-session number equal to the global one means the session "
                        + "dimension was never added");
    }

    @Test
    void costTrackerSessionSummaryAnswersZeroRatherThanNull() {
        // "null is not 0" (Lesson: never coerce unknown to measured
        // zero) — but here the unknown IS knowable: a session that has
        // recorded nothing has recorded zero tokens. The contract is
        // that the RPC can always serialise a number.
        CostTracker t = new CostTracker();
        var s = t.sessionSummary("never-seen");
        assertNotNull(s, "a session summary must never be null");
        assertEquals(0, s.totalInput());
        assertEquals(0, s.totalOutput());
        assertTrue(s.byModel().isEmpty(), "no models were used: " + s.byModel());
    }

    @Test
    void costTrackerBucketsUnknownSessionsInsteadOfDroppingThem() {
        // A null session id must land in a REAL bucket, so that the
        // per-session numbers never disagree with the global ones by
        // silently losing tokens.
        CostTracker t = new CostTracker();
        t.record(null, "claude-sonnet-4-5", new CostTracker.Usage(11, 2));
        t.record("  ", "claude-sonnet-4-5", new CostTracker.Usage(4, 1));

        assertEquals(15, t.sessionSummary(null).totalInput(),
                "usage with no session id is still counted");
        assertEquals(15, t.sessionSummary(CostTracker.UNKNOWN_SESSION).totalInput(),
                "and it is reachable under the documented bucket key");
        assertEquals(15, t.summary().totalInput(),
                "the global total agrees with the bucket");
    }

    @Test
    void costTrackerSessionSummaryCarriesPerModelBreakdown() {
        CostTracker t = new CostTracker();
        t.record("s-1", "claude-sonnet-4-5", new CostTracker.Usage(10, 2));
        t.record("s-1", "claude-opus-4", new CostTracker.Usage(4, 1));

        var s = t.sessionSummary("s-1");
        assertEquals(2, s.byModel().size(), "two models were used: " + s.byModel());
        assertEquals(10, s.byModel().get("claude-sonnet-4-5").inputTokens());
        assertEquals(4, s.byModel().get("claude-opus-4").inputTokens());
        assertTrue(s.totalCostUsd() > 0.0,
                "a priced model must produce a non-zero cost: " + s.totalCostUsd());
    }

    // ------------------------------------------------------------------
    // rewind — transcript truncation
    // ------------------------------------------------------------------

    @Test
    void appStateTruncateKeepsTheFirstN() {
        AppState state = new AppState("s-1", Path.of("."));
        state.appendMessage(msg("m1", Role.USER));
        state.appendMessage(msg("m2", Role.ASSISTANT));
        state.appendMessage(msg("m3", Role.USER));

        int dropped = state.truncateTranscript(1);

        assertEquals(2, dropped, "two messages were dropped");
        assertEquals(1, state.transcript().size());
        assertEquals("m1", state.transcript().get(0).id(), "the HEAD is kept");
    }

    @Test
    void appStateTruncateRejectsAnOutOfRangeKeep() {
        AppState state = new AppState("s-1", Path.of("."));
        state.appendMessage(msg("m1", Role.USER));

        assertThrows(IllegalArgumentException.class, () -> state.truncateTranscript(2),
                "keeping more than there is must fail loudly, not silently no-op");
        assertThrows(IllegalArgumentException.class, () -> state.truncateTranscript(-1));
        assertEquals(1, state.transcript().size(), "a failed truncate changes nothing");
    }

    @Test
    void transcriptTruncateRewritesTheFile() throws Exception {
        // The in-memory list and the JSONL file have to agree: a file
        // that still holds the dropped messages would resurrect them
        // on the next loadSession.
        Path dir = Files.createTempDirectory("r379-transcript");
        Path file = dir.resolve("t.jsonl");
        Transcript t = new Transcript(file);
        t.append(msg("m1", Role.USER));
        t.append(msg("m2", Role.ASSISTANT));
        t.append(msg("m3", Role.USER));

        t.truncateTo(1);

        assertEquals(1, t.messages().size(), "in-memory transcript shrank");
        assertEquals("m1", t.messages().get(0).id());
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1, lines.size(), "the file has exactly one line left: " + lines);
        Transcript reloaded = Transcript.loadOrEmpty(file);
        assertEquals(1, reloaded.messages().size(),
                "reloading from disk must not resurrect the dropped messages");
        assertEquals("m1", reloaded.messages().get(0).id());
    }

    // ------------------------------------------------------------------
    // agent/setDefault — the default agent
    // ------------------------------------------------------------------

    @Test
    void agentRegistryRoundTripsTheDefaultAgent(@TempDir Path agentsDir) throws Exception {
        writeAgent(agentsDir, "alpha");
        AgentRegistry reg = new AgentRegistry(agentsDir, java.time.Duration.ofMillis(50));

        assertTrue(reg.defaultAgent().isEmpty(), "no default is marked yet");

        reg.setDefaultAgent("alpha");

        assertEquals("alpha", reg.defaultAgent().orElse(null));
    }

    @Test
    void agentRegistryDefaultSurvivesAFreshRegistry(@TempDir Path agentsDir) throws Exception {
        // The marker is persisted, so a daemon restart keeps the
        // user's choice. A fresh registry over the same directory must
        // read it back.
        writeAgent(agentsDir, "alpha");
        AgentRegistry first = new AgentRegistry(agentsDir, java.time.Duration.ofMillis(50));
        first.setDefaultAgent("alpha");

        AgentRegistry second = new AgentRegistry(agentsDir, java.time.Duration.ofMillis(50));

        assertEquals("alpha", second.defaultAgent().orElse(null),
                "the default agent must survive a restart");
    }

    @Test
    void agentRegistryRejectsAnUnknownDefaultAgent(@TempDir Path agentsDir) throws Exception {
        writeAgent(agentsDir, "alpha");
        AgentRegistry reg = new AgentRegistry(agentsDir, java.time.Duration.ofMillis(50));

        assertThrows(IllegalArgumentException.class, () -> reg.setDefaultAgent("nope"),
                "a default that points at nothing would be a silent lie");
        assertTrue(reg.defaultAgent().isEmpty(), "the failed set changed nothing");
    }

    @Test
    void agentRegistryClearsTheDefaultOnAnEmptyName(@TempDir Path agentsDir) throws Exception {
        writeAgent(agentsDir, "alpha");
        AgentRegistry reg = new AgentRegistry(agentsDir, java.time.Duration.ofMillis(50));
        reg.setDefaultAgent("alpha");

        reg.setDefaultAgent(null);

        assertTrue(reg.defaultAgent().isEmpty(), "an empty name clears the default");
        assertFalse(Files.exists(agentsDir.resolve(AgentRegistry.DEFAULT_AGENT_MARKER)),
                "the marker file is gone too");
    }

    @Test
    void deletingTheDefaultAgentClearsTheMarker(@TempDir Path agentsDir) throws Exception {
        writeAgent(agentsDir, "alpha");
        AgentRegistry reg = new AgentRegistry(agentsDir, java.time.Duration.ofMillis(50));
        reg.setDefaultAgent("alpha");

        reg.delete("alpha");

        assertTrue(reg.defaultAgent().isEmpty(),
                "a marker pointing at a deleted agent must not survive");
    }

    @Test
    void theDefaultMarkerIsNotMistakenForAnAgent(@TempDir Path agentsDir) throws Exception {
        // The marker lives INSIDE the agents directory. If the scan
        // ever picked it up as an agent, the agent list would grow a
        // phantom entry every time a default was set.
        writeAgent(agentsDir, "alpha");
        AgentRegistry reg = new AgentRegistry(agentsDir, java.time.Duration.ofMillis(50));
        reg.setDefaultAgent("alpha");

        assertEquals(1, reg.list().size(),
                "exactly one agent is registered, got: " + reg.list());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Message msg(String id, Role role) {
        return new Message(id, role,
                List.of(new ContentBlock.TextBlock("body of " + id)),
                Instant.now(), Map.of());
    }

    private static void writeAgent(Path agentsDir, String name) throws Exception {
        Path dir = agentsDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("agent.md"),
                "---\nname: " + name + "\ndescription: the " + name + " agent\n---\n\n"
                        + "body of " + name + "\n",
                StandardCharsets.UTF_8);
    }
}
