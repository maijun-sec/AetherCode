package org.aethercode.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.a2a.schema.AgentCard;
import org.aethercode.a2a.schema.Artifact;
import org.aethercode.a2a.schema.Message;
import org.aethercode.a2a.schema.Task;
import org.aethercode.a2a.schema.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * In-process A2A server. Implements four JSON-RPC methods required by the v0.3 spec:
 *
 * <ul>
 *   <li>{@code message/send} — create or resume a task from a user message. Runs the agent
 *       synchronously and returns the task in {@code completed} state.</li>
 *   <li>{@code tasks/get} — return a task by id.</li>
 *   <li>{@code tasks/cancel} — transition a task to {@code canceled}.</li>
 *   <li>{@code agent/authenticatedExtendedCard} — return the full {@link AgentCard} the server publishes.</li>
 * </ul>
 *
 * <p>Tasks are stored in-memory; the user message is dispatched through a caller-supplied
 * {@link Function} and the result is packaged as a single {@link Artifact} carrying a
 * {@code text/plain} part. Real agents would back this with a deep-agent runtime.</p>
 */
public class A2AServer {

    private static final Logger LOG = LoggerFactory.getLogger(A2AServer.class);

    private final AgentCard card;
    private final Function<Message, Artifact> handler;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Optional streaming handler. When {@code null}, {@link #handleStreamSubscribe} falls back
     *  to the synchronous {@link #handler} and emits a synthetic three-step stream
     *  (working → artifact → completed). */
    private volatile StreamingHandler streamingHandler;

    /** Null-safe encoder that emits a JSON-RPC envelope matching the A2A wire shape.
     *  Hand-rolled rather than relying on Jackson defaults to avoid record + {@code @JsonInclude(NON_NULL)}
     *  rendering a missing error field as the non-spec {@code "error":false}. */
    private String encode(JsonRpcSupport.Response r) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    mapper.createObjectNode();
            root.put("jsonrpc", JsonRpcSupport.VERSION);
            if (r.id() != null) {
                root.set("id", mapper.valueToTree(r.id()));
            }
            if (r.isError()) {
                com.fasterxml.jackson.databind.node.ObjectNode err =
                        mapper.createObjectNode();
                err.put("code", r.error().code());
                err.put("message", r.error().message());
                root.set("error", err);
            } else {
                root.set("result", mapper.valueToTree(r.result()));
            }
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"" + JsonRpcSupport.VERSION + "\",\"id\":null,\"error\":"
                    + "{\"code\":-32603,\"message\":\"encode-failed: " + e.getMessage() + "\"}}";
        }
    }

    /**
     * @param card    the Agent Card the server advertises.
     * @param handler the agent logic. Receives the inbound user
     *                message, returns a single Artifact that
     *                becomes the task's output.
     */
    public A2AServer(AgentCard card, Function<Message, Artifact> handler) {
        this.card = Objects.requireNonNull(card, "card");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public AgentCard card() { return card; }

    /**
     * Install a streaming handler. When installed, {@code message/sendSubscribe} routes through
     * this handler instead of the synchronous {@link #handler}. The handler must emit a terminal
     * status update ({@code completed} / {@code failed} / {@code canceled}) before returning so
     * the SSE transport can close the response stream.
     *
     * @return this, for chaining.
     */
    public A2AServer installStreamingHandler(StreamingHandler h) {
        this.streamingHandler = Objects.requireNonNull(h, "streaming handler");
        return this;
    }

    /** @return the installed streaming handler, or {@code null}
     *          if the server is in single-step fallback mode. */
    public StreamingHandler streamingHandler() { return streamingHandler; }

    /**
     * Dispatch a single JSON-RPC line. Returns the JSON-RPC
     * response string, or {@code null} for notifications (the
     * A2A spec does not currently use notifications, so a null
     * return here just means "the line was a request, not a
     * notification"; callers can treat that as a protocol
     * violation).
     */
    public String handleLine(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            Object msg = JsonRpcSupport.decode(mapper, line);
            if (!(msg instanceof JsonRpcSupport.Request req)) {
                return null;
            }
            try {
                Object result = invoke(req.method(),
                        JsonRpcSupport.paramsAsMap(mapper, req.params()));
                return encode(JsonRpcSupport.Response.ok(req.id(), result));
            } catch (IllegalArgumentException iae) {
                return encode(JsonRpcSupport.Response.err(req.id(),
                        JsonRpcSupport.Codes.INVALID_PARAMS, iae.getMessage()));
            } catch (UnsupportedOperationException uoe) {
                return encode(JsonRpcSupport.Response.err(req.id(),
                        JsonRpcSupport.Codes.METHOD_NOT_FOUND, uoe.getMessage()));
            } catch (Exception e) {
                LOG.error("rpc {} failed: {}", req.method(), e.getMessage(), e);
                return encode(JsonRpcSupport.Response.err(req.id(),
                        JsonRpcSupport.Codes.INTERNAL_ERROR, e.getMessage()));
            }
        } catch (Exception e) {
            return encode(JsonRpcSupport.Response.err(null,
                    JsonRpcSupport.Codes.PARSE_ERROR, e.getMessage()));
        }
    }

    /** Internal: handle one method, with params coerced to a map. */
    protected Object invoke(String method, Map<String, Object> params) {
        return switch (method) {
            case "message/send" -> handleMessageSend(params);
            case "tasks/get"    -> handleTasksGet(params);
            case "tasks/cancel" -> handleTasksCancel(params);
            case "agent/authenticatedExtendedCard" -> card.toMap();
            default -> throw new UnsupportedOperationException("unknown method: " + method);
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleMessageSend(Map<String, Object> params) {
        Object raw = params.get("message");
        if (!(raw instanceof Map<?,?>)) {
            throw new IllegalArgumentException("message/send requires 'message' object");
        }
        Message userMessage = TaskMapper.messageFromMap((Map<String, Object>) raw, mapper);
        // Hand off to the caller-supplied handler.
        Artifact result = handler.apply(userMessage);
        // Synthesize a single-turn task: history records the user message, the artifact
        // slot carries whatever the handler returned.
        Task task = Task.newTask(UUID.randomUUID().toString(), TaskStatus.working());
        task = task
                .withStatus(TaskStatus.completed())
                .withAppendedMessage(userMessage)
                .withAppendedMessage(Message.agent(
                        org.aethercode.a2a.schema.Part.TextPart.of(
                                "agent finished; see artifact '" + result.name() + "'")))
                .withAppendedArtifact(result);
        tasks.put(task.id(), task);
        return task.toMap();
    }

    private Map<String, Object> handleTasksGet(Map<String, Object> params) {
        String id = stringOrThrow(params, "id");
        return getTask(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown task: " + id))
                .toMap();
    }

    private Map<String, Object> handleTasksCancel(Map<String, Object> params) {
        String id = stringOrThrow(params, "id");
        Task existing = tasks.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("unknown task: " + id);
        }
        Task canceled = existing.withStatus(TaskStatus.canceled());
        tasks.put(id, canceled);
        return canceled.toMap();
    }

    public Optional<Task> getTask(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    // Streaming: message/sendSubscribe over SSE

    /**
     * Dispatch a streaming JSON-RPC request. The transport (typically
     * {@link A2AHttpTransport}'s {@code SseHandler}) passes the body and a sink; the sink
     * receives one decoded event map per emit, events are pre-encoded as maps
     * (the transport performs the SSE serialization).
     *
     * <p>Supported method: {@code message/sendSubscribe}. Any other method is reported via
     * a single error event and the sink is not called again.</p>
     *
     * @param line the JSON-RPC body, same shape as {@link #handleLine(String)}; the
     *             request id is captured here so the transport can echo it back in error frames.
     * @param emit callback receiving one event map per update; the transport is expected
     *             to flush after each emit.
     */
    public void handleStreamSubscribe(String line, Consumer<Map<String, Object>> emit) {
        Objects.requireNonNull(emit, "emit");
        if (line == null || line.isBlank()) {
            emit.accept(streamError(null, -32700, "empty body"));
            return;
        }
        Object parsed;
        try {
            parsed = JsonRpcSupport.decode(mapper, line);
        } catch (Exception e) {
            emit.accept(streamError(null, -32700, "parse error: " + e.getMessage()));
            return;
        }
        if (!(parsed instanceof JsonRpcSupport.Request req)) {
            // Response or anything else — not a request.
            emit.accept(streamError(null, -32600, "expected json-rpc request, got response"));
            return;
        }
        if (!"message/sendSubscribe".equals(req.method())) {
            emit.accept(streamError(req.id(), JsonRpcSupport.Codes.METHOD_NOT_FOUND,
                    "unknown method for streaming: " + req.method()));
            return;
        }
        Map<String, Object> params;
        try {
            params = JsonRpcSupport.paramsAsMap(mapper, req.params());
        } catch (Exception e) {
            emit.accept(streamError(req.id(), JsonRpcSupport.Codes.INVALID_PARAMS, e.getMessage()));
            return;
        }
        Object raw = params.get("message");
        if (!(raw instanceof Map<?,?>)) {
            emit.accept(streamError(req.id(), JsonRpcSupport.Codes.INVALID_PARAMS,
                    "message/sendSubscribe requires 'message' object"));
            return;
        }
        Message userMessage;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> mm = (Map<String, Object>) raw;
            userMessage = TaskMapper.messageFromMap(mm, mapper);
        } catch (Exception e) {
            emit.accept(streamError(req.id(), JsonRpcSupport.Codes.INVALID_PARAMS,
                    "invalid message: " + e.getMessage()));
            return;
        }
        // Tag the event-id stream with the request id, so the SSE transport can stamp each
        // event's `id:` field with a monotonic per-request counter derived from the request id.
        String taskId = "stream-" + UUID.randomUUID();
        StreamingHandler sh = this.streamingHandler;
        if (sh != null) {
            sh.handle(userMessage, update -> emit.accept(wrapUpdate(taskId, update)));
            return;
        }
        // Fallback: synthesize a three-step stream (working / artifact / completed) from the
        // synchronous handler so the SSE transport is fully exercised even without a real agent.
        try {
            emit.accept(wrapUpdate(taskId, TaskUpdate.status("working", null)));
            Artifact result = handler.apply(userMessage);
            emit.accept(wrapUpdate(taskId, TaskUpdate.artifact(result)));
            emit.accept(wrapUpdate(taskId, TaskUpdate.status("completed", null)));
        } catch (Exception e) {
            LOG.error("streaming fallback failed: {}", e.getMessage(), e);
            emit.accept(wrapUpdate(taskId, TaskUpdate.status("failed", e.getMessage())));
        }
    }

    /** Wrap a {@link TaskUpdate} as a transport-friendly map
     *  with the SSE {@code event:} name. */
    private static Map<String, Object> wrapUpdate(String taskId, TaskUpdate u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", taskId);
        m.put("event", u.eventName());
        m.put("data", u.toMap());
        return m;
    }

    /** Build a single-error event map. */
    private static Map<String, Object> streamError(Object requestId, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", "error");
        m.put("requestId", requestId);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        m.put("data", err);
        return m;
    }

    private static String stringOrThrow(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) throw new IllegalArgumentException("missing required field: " + k);
        return v.toString();
    }

    /** Convenience: a default handler that echoes the user's text
     *  back inside a single text artifact. */
    public static Function<Message, Artifact> echoHandler() {
        return userMessage -> {
            StringBuilder sb = new StringBuilder();
            for (org.aethercode.a2a.schema.Part p : userMessage.parts()) {
                if (p instanceof org.aethercode.a2a.schema.Part.TextPart tp) {
                    sb.append(tp.text()).append('\n');
                }
            }
            return Artifact.of("echo", org.aethercode.a2a.schema.Part.TextPart.of(
                    "echo: " + sb.toString().trim()));
        };
    }

    // Streaming types

    /**
     * Streaming handler. Receives the user message and an emit callback; each emit produces
     * one SSE event.
     *
     * <p>The handler must emit a terminal status update ({@code completed} / {@code failed} /
     * {@code canceled}) before returning; otherwise the SSE transport cannot cleanly close
     * the response stream.</p>
     */
    @FunctionalInterface
    public interface StreamingHandler {
        void handle(Message userMessage, Consumer<TaskUpdate> emit);
    }

    /**
     * A single update emitted by a {@link StreamingHandler}. Mirrors the two event types from
     * the A2A 0.3 streaming spec ({@code TaskStatusUpdateEvent} and {@code TaskArtifactUpdateEvent}),
     * with an additional {@code error} shape for protocol-level failures.
     */
    public static final class TaskUpdate {
        private final String kind;       // "status" | "artifact"
        private final String state;      // status only
        private final String message;    // status only (optional)
        private final Artifact artifact; // artifact only

        private TaskUpdate(String kind, String state, String message, Artifact artifact) {
            this.kind = kind;
            this.state = state;
            this.message = message;
            this.artifact = artifact;
        }

        public static TaskUpdate status(String state, String message) {
            return new TaskUpdate("status", state, message, null);
        }
        public static TaskUpdate artifact(Artifact a) {
            return new TaskUpdate("artifact", null, null, Objects.requireNonNull(a, "artifact"));
        }
        public String eventName() { return kind; }
        public String state() { return state; }
        public String message() { return message; }
        public Artifact artifact() { return artifact; }

        /** Wire-shape serialization. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if ("status".equals(kind)) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("state", state);
                if (message != null) s.put("message", message);
                m.put("status", s);
            } else if ("artifact".equals(kind)) {
                m.put("artifact", artifact.toMap());
            }
            return m;
        }

        @Override
        public String toString() {
            return "TaskUpdate{" + kind
                    + ("status".equals(kind) ? ":" + state : ":" + (artifact == null ? "null" : artifact.name()))
                    + "}";
        }
    }
}
