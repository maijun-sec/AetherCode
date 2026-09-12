package org.aethercode.evals.sdk.a2a;

import org.aethercode.a2a.schema.AgentCard;
import org.aethercode.a2a.schema.Artifact;
import org.aethercode.a2a.schema.Message;
import org.aethercode.a2a.schema.Part;
import org.aethercode.a2a.schema.Task;
import org.aethercode.a2a.schema.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-sdk-7: AetherCode A2A Schema Interface conformance.
 *
 * <p>Companion to {@code A2AMultiAgentTest} (R-eval-7). The capability
 * suite proves the A2A design (AgentCard / Task / streaming / ensemble /
 * bridge) on a self-contained model. This suite proves the actual
 * {@code aethercode-a2a} schema records that wire-compat clients
 * (a2a-python, a2a-js) parse — getting a field name or state string
 * wrong here silently breaks interop with every A2A peer.</p>
 */
class SdkA2AInterfaceTest {

    /* ---------------- AgentCard ---------------- */

    @Test
    void agentCardRequiresNameAndUrl() {
        assertThrows(NullPointerException.class,
                () -> new AgentCard(null, "d", "v", "u", null, null, null, null));
        assertThrows(NullPointerException.class,
                () -> new AgentCard("n", "d", "v", null, null, null, null, null));
    }

    @Test
    void agentCardDefaultsAreFilledIn() {
        AgentCard card = new AgentCard("aethercode", null, null,
                "https://example.com", null, null, null, null);
        // Defaults applied:
        assertEquals("", card.description());
        assertEquals("0.1.0", card.version());
        assertEquals(List.of(), card.skills());
        assertNotNull(card.capabilities());
        assertNotNull(card.authentication());
    }

    @Test
    void agentCardToMapEmitsExpectedFields() {
        AgentCard card = new AgentCard(
                "aethercode", "AI agent runtime", "1.0.0",
                "https://aethercode.example",
                new AgentCard.Provider("Acme", "https://acme.example"),
                List.of(new AgentCard.Skill("scholar", "scholar_search",
                        "Search arXiv", List.of("text"), List.of("text"))),
                AgentCard.Capabilities.defaults(),
                AgentCard.Authentication.open());
        Map<String, Object> map = card.toMap();
        assertEquals("aethercode", map.get("name"));
        assertEquals("AI agent runtime", map.get("description"));
        assertEquals("1.0.0", map.get("version"));
        assertEquals("https://aethercode.example", map.get("url"));
        assertNotNull(map.get("provider"));
        assertNotNull(map.get("skills"));
        assertNotNull(map.get("capabilities"));
        assertNotNull(map.get("authentication"));
    }

    @Test
    void agentCardSkillRequiresIdAndName() {
        assertThrows(NullPointerException.class,
                () -> new AgentCard.Skill(null, "name", null, null, null));
        assertThrows(NullPointerException.class,
                () -> new AgentCard.Skill("id", null, null, null, null));
    }

    @Test
    void agentCardSkillDefaultsInputOutputToText() {
        AgentCard.Skill s = new AgentCard.Skill("id", "name", null, null, null);
        assertEquals(List.of("text"), s.inputModes());
        assertEquals(List.of("text"), s.outputModes());
    }

    @Test
    void agentCardCapabilitiesDefaultsStreamingFalsePushFalseHistoryTrue() {
        AgentCard.Capabilities c = AgentCard.Capabilities.defaults();
        assertFalse(c.streaming());
        assertFalse(c.pushNotifications());
        assertTrue(c.stateTransitionHistory());
    }

    /* ---------------- TaskStatus ---------------- */

    @Test
    void taskStatusStateStringsAreStable() {
        // Per A2A v0.3 / v1.0 spec; the wire format locks these
        // strings — a change here silently breaks interop.
        assertEquals("submitted", TaskStatus.STATE_SUBMITTED);
        assertEquals("working", TaskStatus.STATE_WORKING);
        assertEquals("input-required", TaskStatus.STATE_INPUT_REQUIRED);
        assertEquals("completed", TaskStatus.STATE_COMPLETED);
        assertEquals("failed", TaskStatus.STATE_FAILED);
        assertEquals("canceled", TaskStatus.STATE_CANCELED);
        assertEquals("rejected", TaskStatus.STATE_REJECTED);
    }

    @Test
    void taskStatusRejectsUnknownState() {
        assertThrows(IllegalArgumentException.class, () -> TaskStatus.of("running"));
        assertThrows(IllegalArgumentException.class, () -> TaskStatus.of("done"));
    }

    @Test
    void taskStatusTerminalFlag() {
        assertFalse(TaskStatus.submitted().isTerminal());
        assertFalse(TaskStatus.working().isTerminal());
        assertTrue(TaskStatus.completed().isTerminal());
        assertTrue(TaskStatus.failed().isTerminal());
        assertTrue(TaskStatus.canceled().isTerminal());
        // "input-required" is a pause, not a terminal state.
        assertFalse(TaskStatus.of("input-required").isTerminal());
    }

    @Test
    void taskStatusFactoriesProduceValidRecords() {
        TaskStatus s1 = TaskStatus.submitted();
        assertEquals(TaskStatus.STATE_SUBMITTED, s1.state());
        TaskStatus s2 = TaskStatus.completed();
        assertEquals(TaskStatus.STATE_COMPLETED, s2.state());
    }

    @Test
    void taskStatusToMapHasState() {
        Map<String, Object> map = TaskStatus.working().toMap();
        assertEquals("working", map.get("state"));
    }

    /* ---------------- Task ---------------- */

    @Test
    void taskRequiresIdAndStatus() {
        assertThrows(NullPointerException.class,
                () -> new Task(null, "ctx", TaskStatus.submitted(), null, null));
        assertThrows(NullPointerException.class,
                () -> new Task("id", "ctx", null, null, null));
    }

    @Test
    void taskNewTaskAutoAssignsUuid() {
        Task t = Task.newTask("ctx-1", TaskStatus.submitted());
        assertNotNull(t.id());
        assertFalse(t.id().isBlank());
        // Should look like a UUID (length 36 with dashes).
        assertEquals(36, t.id().length());
    }

    @Test
    void taskWithStatusPreservesIdAndContext() {
        Task t = Task.newTask("ctx-1", TaskStatus.submitted());
        Task t2 = t.withStatus(TaskStatus.working());
        assertEquals(t.id(), t2.id());
        assertEquals("ctx-1", t2.contextId());
        assertEquals("working", t2.status().state());
    }

    @Test
    void taskWithAppendedMessageAndArtifact() {
        Task t = Task.newTask("ctx-1", TaskStatus.working());
        Message m = Message.user(Part.TextPart.of("hi"));
        Task t2 = t.withAppendedMessage(m);
        assertEquals(1, t2.history().size());
        // Original task immutable.
        assertEquals(0, t.history().size());

        Artifact a = Artifact.of("note", Part.TextPart.of("contents"));
        Task t3 = t2.withAppendedArtifact(a);
        assertEquals(1, t3.artifacts().size());
        assertEquals(1, t3.history().size(), "history is preserved across withAppendedArtifact");
    }

    @Test
    void taskToMapHasKindTask() {
        Task t = Task.newTask("ctx-1", TaskStatus.submitted());
        Map<String, Object> map = t.toMap();
        assertEquals("task", map.get("kind"));
        assertNotNull(map.get("status"));
        assertEquals(t.id(), map.get("id"));
    }
}
