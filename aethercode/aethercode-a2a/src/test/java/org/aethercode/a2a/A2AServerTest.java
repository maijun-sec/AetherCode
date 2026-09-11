package org.aethercode.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.a2a.schema.AgentCard;
import org.aethercode.a2a.schema.Artifact;
import org.aethercode.a2a.schema.Message;
import org.aethercode.a2a.schema.Part;
import org.aethercode.a2a.schema.Task;
import org.aethercode.a2a.schema.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * in-process E2E tests for {@link A2AServer}. Each test
 * fires a JSON-RPC line through {@link A2AServer#handleLine} and
 * asserts the typed decoded response.
 */
class A2AServerTest {

    private static AgentCard defaultCard() {
        return new AgentCard(
                "test-agent",
                "A test agent",
                "0.1.0",
                "http://localhost:0/a2a",
                null,
                List.of(),
                null,
                null);
    }

    private static A2AServer defaultServer() {
        return new A2AServer(defaultCard(), A2AServer.echoHandler());
    }

    /** Helper: encode a single jsonrpc request and decode the response. */
    private static Map<String, Object> fire(A2AServer s, String method, Map<String, Object> params) throws Exception {
        ObjectMapper m = new ObjectMapper();
        String req = JsonRpcSupport.encodeRequest(m, "test-1", method, params);
        String reply = s.handleLine(req);
        assertNotNull(reply, "expected json-rpc response");
        return m.readValue(reply, Map.class);
    }

    // -- message/send --------------------------------------------------

    @Test
    void messageSendReturnsCompletedTaskWithArtifact() throws Exception {
        A2AServer s = defaultServer();
        Map<String, Object> params = Map.of("message", Map.of(
                "role", "user",
                "parts", List.of(Map.of("kind", "text", "text", "hello world"))));
        Map<String, Object> reply = fire(s, "message/send", params);
        assertNull(reply.get("error"), "no error expected: " + reply);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) reply.get("result");
        assertNotNull(result);
        String id = (String) result.get("id");
        assertNotNull(id);
        assertEquals("task", result.get("kind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) result.get("status");
        assertEquals(TaskStatus.STATE_COMPLETED, status.get("state"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) result.get("artifacts");
        assertEquals(1, artifacts.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) artifacts.get(0).get("parts");
        @SuppressWarnings("unchecked")
        Map<String, Object> part0 = (Map<String, Object>) parts.get(0);
        assertEquals("echo: hello world", part0.get("text"));
    }

    @Test
    void messageSendMissingMessageReturnsInvalidParams() throws Exception {
        A2AServer s = defaultServer();
        Map<String, Object> reply = fire(s, "message/send", Map.of());
        assertNotNull(reply.get("error"));
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) reply.get("error");
        assertEquals(JsonRpcSupport.Codes.INVALID_PARAMS, err.get("code"));
    }

    // -- tasks/get ----------------------------------------------------

    @Test
    void tasksGetReturnsExistingTask() throws Exception {
        A2AServer s = defaultServer();
        Map<String, Object> sent = fire(s, "message/send", Map.of("message", Map.of(
                "role", "user",
                "parts", List.of(Map.of("kind", "text", "text", "again")))));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) sent.get("result");
        String id = (String) result.get("id");
        Map<String, Object> got = fire(s, "tasks/get", Map.of("id", id));
        @SuppressWarnings("unchecked")
        Map<String, Object> gotResult = (Map<String, Object>) got.get("result");
        assertEquals(id, gotResult.get("id"));
    }

    @Test
    void tasksGetMissingIdReturnsInvalidParams() throws Exception {
        A2AServer s = defaultServer();
        Map<String, Object> reply = fire(s, "tasks/get", Map.of());
        assertNotNull(reply.get("error"));
    }

    // -- tasks/cancel -------------------------------------------------

    @Test
    void tasksCancelTransitionsToCanceled() throws Exception {
        A2AServer s = defaultServer();
        Map<String, Object> sent = fire(s, "message/send", Map.of("message", Map.of(
                "role", "user",
                "parts", List.of(Map.of("kind", "text", "text", "x")))));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) sent.get("result");
        String id = (String) result.get("id");
        Map<String, Object> reply = fire(s, "tasks/cancel", Map.of("id", id));
        assertNull(reply.get("error"));
        @SuppressWarnings("unchecked")
        Map<String, Object> canceled = (Map<String, Object>) reply.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) canceled.get("status");
        assertEquals(TaskStatus.STATE_CANCELED, status.get("state"));
        // The task is still in history; getTask still works.
        Optional<Task> back = s.getTask(id);
        assertTrue(back.isPresent());
        assertTrue(back.get().status().isTerminal());
    }

    @Test
    void tasksCancelUnknownIdReturnsInvalidParams() throws Exception {
        A2AServer s = defaultServer();
        Map<String, Object> reply = fire(s, "tasks/cancel", Map.of("id", "nope"));
        assertNotNull(reply.get("error"));
    }

    // -- agent/authenticatedExtendedCard ------------------------------

    @Test
    void agentCardMethodReturnsCard() throws Exception {
        A2AServer s = defaultServer();
        Map<String, Object> reply = fire(s, "agent/authenticatedExtendedCard", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) reply.get("result");
        assertNotNull(card);
        assertEquals("test-agent", card.get("name"));
        assertEquals("http://localhost:0/a2a", card.get("url"));
        @SuppressWarnings("unchecked")
        Map<String, Object> caps = (Map<String, Object>) card.get("capabilities");
        assertNotNull(caps);
        assertEquals(false, caps.get("streaming"));
        assertEquals(false, caps.get("pushNotifications"));
    }

    // -- unknown method -----------------------------------------------

    @Test
    void unknownMethodReturnsMethodNotFound() throws Exception {
        A2AServer s = defaultServer();
        Map<String, Object> reply = fire(s, "foo/bar", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) reply.get("error");
        assertNotNull(err);
        assertEquals(JsonRpcSupport.Codes.METHOD_NOT_FOUND, err.get("code"));
    }

    // -- malformed line -----------------------------------------------

    @Test
    void malformedLineReturnsParseError() throws Exception {
        A2AServer s = defaultServer();
        String reply = s.handleLine("not json {");
        assertNotNull(reply);
        ObjectMapper m = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> r = m.readValue(reply, Map.class);
        assertNotNull(r.get("error"));
    }

    // -- blank line ---------------------------------------------------

    @Test
    void blankLineReturnsNull() {
        A2AServer s = defaultServer();
        assertNull(s.handleLine(""));
        assertNull(s.handleLine(null));
    }

    // -- mapper coverage: round trip ---------------------------------

    @Test
    void taskMapperRoundTripPreservesState() {
        Task src = Task.newTask("ctx-1", TaskStatus.working())
                .withStatus(TaskStatus.completed())
                .withAppendedMessage(Message.user(Part.TextPart.of("hi")))
                .withAppendedMessage(Message.agent(Part.TextPart.of("hi back")))
                .withAppendedArtifact(Artifact.of("response", Part.TextPart.of("hi back")));
        Map<String, Object> map = src.toMap();
        Task back = TaskMapper.fromMap(map, new ObjectMapper());
        assertEquals(src.id(), back.id());
        assertEquals(src.status().state(), back.status().state());
        assertEquals(1, back.artifacts().size());
        assertEquals(2, back.history().size());
        assertEquals("hi", ((Part.TextPart) back.history().get(0).parts().get(0)).text());
    }
}
