package org.aethercode.a2a;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.a2a.schema.AgentCard;
import org.aethercode.a2a.schema.Message;
import org.aethercode.a2a.schema.Task;
import org.aethercode.a2a.schema.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal A2A v0.3 client. Wraps three JSON-RPC methods —
 * {@code message/send} / {@code tasks/get} / {@code tasks/cancel} — plus
 * {@code agent/authenticatedExtendedCard}.
 *
 * <p>Each method issues a single JSON-RPC request and decodes the result into a typed value. The
 * client is intentionally blocking; the streaming shape is added only once the synchronous contract
 * is well understood.</p>
 *
 * <p>{@link #subscribeMessage(Message, StreamObserver)} targets the {@code message/sendSubscribe}
 * SSE method. The observer callback receives a decoded update map for every frame, so callers
 * never need to parse the SSE wire format themselves.</p>
 */
public final class A2AClient {

    private static final Logger LOG = LoggerFactory.getLogger(A2AClient.class);

    /** Callback for {@link #subscribeMessage}; receives a decoded update map per frame. The shape
     *  matches what the server emits: {@code {taskId, event, data}}. */
    public interface StreamObserver {
        void onUpdate(Map<String, Object> update);
        /** Terminal-state hook; default is a no-op. */
        default void onComplete() {}
        /** Error hook; the default wraps the failure in a RuntimeException. */
        default void onError(Throwable t) {
            throw new RuntimeException("A2A stream error", t);
        }
    }

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI baseUri;
    private final AgentCard card;
    private final AtomicLong idCounter = new AtomicLong(0L);

    public A2AClient(URI baseUri, AgentCard card) {
        this(baseUri, card, HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build(),
                new ObjectMapper());
    }

    public A2AClient(URI baseUri, AgentCard card,
                     HttpClient http, ObjectMapper mapper) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.card = Objects.requireNonNull(card, "card");
        this.http = http;
        this.mapper = mapper;
    }

    public AgentCard card() { return card; }
    public URI baseUri() { return baseUri; }

    /** Send a message; the server creates a task and returns it
     *  (sync — completes when the task is at a terminal state,
     *  or returns it {@code working} and lets the client poll). */
    public Task sendMessage(Message message) throws Exception {
        Objects.requireNonNull(message, "message");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", message.toMap());
        JsonRpcSupport.Response resp = call("message/send", params);
        return parseTask(resp.result());
    }

    /** Look up a task by id. */
    public Task getTask(String taskId) throws Exception {
        Objects.requireNonNull(taskId, "taskId");
        Map<String, Object> params = Map.of("id", taskId);
        JsonRpcSupport.Response resp = call("tasks/get", params);
        return parseTask(resp.result());
    }

    /** Cancel a task; returns the task with state {@code canceled}. */
    public Task cancelTask(String taskId) throws Exception {
        Objects.requireNonNull(taskId, "taskId");
        Map<String, Object> params = Map.of("id", taskId);
        JsonRpcSupport.Response resp = call("tasks/cancel", params);
        return parseTask(resp.result());
    }

    // Streaming subscription

    /**
     * Subscribe to a task via {@code message/sendSubscribe} (SSE). The observer receives a decoded
     * map for every frame; the connection stays open until the server closes it (a terminal status
     * event signals end-of-stream).
     *
     * <p>This is the client-side counterpart to the server's {@code A2AHttpTransport.SseHandler};
     * the wire format is decoded here so callers only see typed maps, never raw SSE frames.</p>
     *
     * @param message  the user message.
     * @param observer the per-frame event callback.
     * @return the number of events delivered to the observer before the stream closed.
     */
    public int subscribeMessage(Message message, StreamObserver observer)
            throws Exception {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(observer, "observer");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", message.toMap());
        String requestId = "a2a-" + idCounter.incrementAndGet();
        String body = JsonRpcSupport.encodeRequest(mapper, requestId,
                "message/sendSubscribe", params);
        // The streaming endpoint lives at {scheme}://{host}/a2a/stream (same prefix as the sync RPC
        // path plus /stream). We use an absolute path here to keep URI resolution from swallowing
        // the /a2a segment (which a relative "./stream" would do).
        URI streamUri = URI.create(baseUri.getScheme()
                + "://" + baseUri.getAuthority()
                + A2AHttpTransport.STREAM_RPC_PATH);
        HttpRequest req = HttpRequest.newBuilder(streamUri)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<java.io.InputStream> resp = http.send(req,
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            String errBody = new String(resp.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            throw new RuntimeException("A2A HTTP " + resp.statusCode() + ": " + errBody);
        }
        int events = 0;
        // Walk the SSE stream. The wire format is documented
        // in A2AHttpTransport.SseHandler — frames are
        // `event:` / `id:` / `data:` lines terminated by a
        // blank line. A real client would pull in an
        // off-the-shelf SSE library; for one call site
        // (this method) a hand-rolled parser is fine.
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(resp.body(),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            String eventName = null;
            StringBuilder dataBuf = new StringBuilder();
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    if (dataBuf.length() > 0 || eventName != null) {
                        Map<String, Object> update = parseUpdate(eventName, dataBuf.toString());
                        observer.onUpdate(update);
                        events++;
                        if ("error".equals(update.get("event"))) {
                            break;
                        }
                    }
                    eventName = null;
                    dataBuf.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) continue;  // SSE comment
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String field = line.substring(0, colon);
                String value = line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                if ("event".equals(field)) {
                    eventName = value;
                } else if ("data".equals(field)) {
                    if (dataBuf.length() > 0) dataBuf.append('\n');
                    dataBuf.append(value);
                }
                // id: and retry: are ignored; reconnect is a
                // client concern we don't model here.
            }
            // Final flush if the server closed without a trailing blank.
            if (dataBuf.length() > 0 || eventName != null) {
                observer.onUpdate(parseUpdate(eventName, dataBuf.toString()));
                events++;
            }
            observer.onComplete();
        } catch (RuntimeException re) {
            observer.onError(re);
            throw re;
        }
        return events;
    }

    /** Decode one SSE frame's data line. The frame shape is
     *  documented in {@code A2AHttpTransport.SseHandler}: the
     *  data is a JSON object the server already pre-shaped
     *  for the client, so we just round-trip through
     *  Jackson. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseUpdate(String eventName, String data) {
        if (data == null || data.isEmpty()) return Map.of();
        try {
            Map<String, Object> m = mapper.readValue(data,
                    new TypeReference<Map<String, Object>>() {});
            // Stash the event name on the map so observers
            // can filter on it without having to thread a
            // separate parameter through the callback.
            m.putIfAbsent("event", eventName == null ? "message" : eventName);
            return m;
        } catch (Exception e) {
            throw new RuntimeException("A2A stream parse error: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------
    //  JSON-RPC plumbing
    // -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private JsonRpcSupport.Response call(String method, Map<String, Object> params) throws Exception {
        String requestId = "a2a-" + idCounter.incrementAndGet();
        String body = JsonRpcSupport.encodeRequest(mapper, requestId, method, params);
        HttpRequest req = HttpRequest.newBuilder(baseUri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("A2A HTTP " + resp.statusCode() + ": " + resp.body());
        }
        Object decoded = JsonRpcSupport.decode(mapper, resp.body());
        if (!(decoded instanceof JsonRpcSupport.Response rpc)) {
            throw new RuntimeException("expected json-rpc response, got " + decoded);
        }
        if (rpc.isError()) {
            throw new RuntimeException("A2A " + method + " error: "
                    + rpc.error().code() + " " + rpc.error().message());
        }
        return rpc;
    }

    @SuppressWarnings("unchecked")
    private Task parseTask(Object result) {
        if (!(result instanceof Map<?,?> rm)) {
            throw new RuntimeException("expected task map, got " + result);
        }
        return TaskMapper.fromMap((Map<String, Object>) rm, mapper);
    }
}
