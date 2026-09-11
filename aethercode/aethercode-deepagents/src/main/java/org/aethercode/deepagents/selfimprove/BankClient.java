package org.aethercode.deepagents.selfimprove;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * R244.2 (O-10): minimal HTTP client for {@link BankServer}.
 * A non-JVM surface (TUI / desktop / IntelliJ / another
 * daemon) wraps the same JSON shape via this client to
 * read the strategy library and push self-eval feedback
 * back.
 *
 * <h2>Why a thin client, not the full A2A protocol</h2>
 *
 * <p>The A2A protocol is task-oriented ({@code
 * message/send} → {@code tasks/get}); bank operations are
 * stateless CRUD. The {@code BankServer} front-end lets
 * the data plane stay small and focused; the task plane
 * remains in {@code aethercode-a2a} and can compose the
 * two when needed.</p>
 *
 * <h2>JSON shape</h2>
 *
 * <p>Units are serialised via
 * {@link ReasoningUnit#toMap()}, which is a
 * {@code LinkedHashMap} with keys {@code id, taskKind,
 * errorPattern, fixStrategy, example, utility, uses,
 * okCount, notOkCount, createdAt}.</p>
 *
 * <h2>Failure semantics</h2>
 *
 * <p>Network and decode failures throw
 * {@link BankClientException}; the client never returns a
 * half-formed record. {@link #recallFor(String, int)} and
 * {@link #recallAllKinds(int)} return {@code Optional.empty()}
 * on a 404 and re-throw on any other status code.</p>
 */
public class BankClient {

    private static final Logger LOG = LoggerFactory.getLogger(BankClient.class);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI baseUri;
    /**
     * R247 (O-10): optional bearer token. When set, every
     * request carries {@code Authorization: Bearer <token>}.
     * The server (R247 BankServer.requireAuth) verifies the
     * token before serving {@code /bank/*} routes.
     */
    private final String authToken;

    public BankClient(URI baseUri) {
        this(baseUri, null);
    }

    /**
     * construct with an optional bearer token. Pass
     * {@code null} for unauthenticated servers (R244.2 default).
     */
    public BankClient(URI baseUri, String authToken) {
        this(baseUri, null, null, authToken);
    }

    public BankClient(URI baseUri, HttpClient http, ObjectMapper mapper) {
        this(baseUri, http, mapper, null);
    }

    public BankClient(URI baseUri, HttpClient http, ObjectMapper mapper, String authToken) {
        Objects.requireNonNull(baseUri, "baseUri");
        // strip trailing slash so we can build "{base}/bank/..." cleanly
        String s = baseUri.toString();
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        this.baseUri = URI.create(s);
        this.http = http != null ? http : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
        this.mapper = mapper != null ? mapper : new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.authToken = (authToken == null || authToken.isBlank()) ? null : authToken;
    }

    public URI baseUri() { return baseUri; }

    /** Cheap health probe. Returns {@code true} if the
     *  server returns HTTP 200 within the request timeout. */
    public boolean ping() {
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(baseUri.resolve("/healthz"))
                            .timeout(Duration.ofSeconds(2))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Recall top-N units for a given task kind. Returns
     *  empty when the server replies 404 (no such kind). */
    public List<Map<String, Object>> recallFor(String kind, int n) {
        Objects.requireNonNull(kind, "kind");
        String body = send("GET", "/bank/recall?kind="
                + urlEncode(kind) + "&n=" + n, null, "ok=true");
        return decodeList(body);
    }

    /** Recall the cross-kind top-N, matching the
     *  server-side ranking in {@link BankServer}. */
    public List<Map<String, Object>> recallAllKinds(int n) {
        String body = send("POST", "/bank/recall-all-kinds?n=" + n,
                null, "ok=true");
        return decodeList(body);
    }

    /** Bump uses/utility for a unit by id. Returns the
     *  updated wire record. */
    public Map<String, Object> touch(String id) {
        Objects.requireNonNull(id, "id");
        String body = send("POST", "/bank/touch?id=" + urlEncode(id), null, "ok=true");
        return decodeObject(body);
    }

    /** R244.1 feedback: record an ok/notOk outcome for a
     *  unit. Returns the updated wire record. */
    public Map<String, Object> recordOutcome(String id, boolean ok) {
        Objects.requireNonNull(id, "id");
        String body = send("POST", "/bank/record-outcome?id="
                + urlEncode(id) + "&ok=" + ok, null, "ok=true");
        return decodeObject(body);
    }

    /** Read a snapshot of the bank's stats: total size,
     *  per-kind counts, total ok / notOk. */
    public Map<String, Object> stats() {
        String body = send("GET", "/bank/stats", null, "ok=true");
        return decodeObject(body);
    }

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    private String send(String method, String path, byte[] body, String successMarker) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json");
            // attach bearer token when configured.
            if (authToken != null) {
                b.header("Authorization", "Bearer " + authToken);
            }
            switch (method) {
                case "GET"  -> b.GET();
                case "POST" -> b.POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
                default -> throw new IllegalArgumentException("unsupported method: " + method);
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) {
                // Caller distinguishes "no such kind" via
                // Optional.ofNullable on the parsed list /
                // map. We surface 404 as a recognised
                // outcome, not a transport error.
                if (successMarker.equals("404")) return r.body();
                throw new BankClientException(404, r.body());
            }
            if (r.statusCode() < 200 || r.statusCode() >= 300) {
                throw new BankClientException(r.statusCode(), r.body());
            }
            return r.body();
        } catch (BankClientException e) {
            throw e;
        } catch (Exception e) {
            throw new BankClientException(0,
                    "transport error: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> decodeList(String body) {
        try {
            return mapper.readValue(body, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new BankClientException(0, "decode error: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decodeObject(String body) {
        try {
            return mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new BankClientException(0, "decode error: " + e.getMessage());
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** R244.2: a network / decode error. {@code status == 0}
     *  means "transport failed before HTTP". The caller
     *  should treat 404 from {@link BankClient#recallFor}
     *  as "no such kind", not as an error. */
    public static final class BankClientException extends RuntimeException {
        private final int status;
        public BankClientException(int status, String message) {
            super("bank client (status=" + status + "): " + message);
            this.status = status;
        }
        public int status() { return status; }
    }
}
