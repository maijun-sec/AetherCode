package org.aethercode.tools.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GoogleScholarClient} -- academic paper search via the
 * Semantic Scholar Graph API.
 *
 * <p>Two layers of coverage:
 * <ul>
 *   <li>{@code toRecord} static helper is exercised with hand-built JSON
 *       (no network).</li>
 *   <li>The live HTTP path is exercised against a tiny in-JVM
 *       {@link HttpServer} using the test-seam constructor
 *       {@link GoogleScholarClient#GoogleScholarClient(String, String)}.
 *       No real network is required.</li>
 * </ul>
 */
class GoogleScholarClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        lastPath.set(ex.getRequestURI().getPath());
        lastQuery.set(ex.getRequestURI().getQuery());
        lastAuth.set(ex.getRequestHeaders().getFirst("x-api-key"));
        String path = ex.getRequestURI().getPath();
        // The lookup URL embeds the idType:idValue in the path; OkHttp may
        // or may not percent-encode the colon depending on how the
        // request was built.  Match by substring so we cover both forms
        // and the notfound-lookup test (which routes the lookup under a
        // non-default base path).
        boolean isLookup = path.contains("/paper/");
        String body;
        if (path.equals("/search")) {
            body = """
                    {
                      "total": 3,
                      "offset": 0,
                      "data": [
                        {
                          "paperId": "abc123",
                          "title": "Memory in the Age of AI Agents",
                          "abstract": "A comprehensive survey of agent memory mechanisms across LLM-based systems.",
                          "year": 2025,
                          "venue": "arXiv preprint",
                          "authors": [
                            {"authorId": "a1", "name": "Alice"},
                            {"authorId": "a2", "name": "Bob"}
                          ],
                          "externalIds": {"ArXiv": "2512.13564v2", "DOI": "10.x/y"},
                          "url": "https://www.semanticscholar.org/paper/abc123",
                          "citationCount": 42
                        }
                      ]
                    }
                    """;
        } else if (isLookup && !path.contains("notfound")) {
            body = """
                    {
                      "paperId": "xyz789",
                      "title": "Lookup Result",
                      "year": 2024,
                      "authors": [{"name": "Carol"}]
                    }
                    """;
        } else if (isLookup && path.contains("notfound")) {
            ex.sendResponseHeaders(404, -1);
            body = null;
        } else {
            ex.sendResponseHeaders(500, 0);
            ex.getResponseBody().close();
            body = null;
        }
        if (body != null) {
            byte[] bytes = body.getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private String searchEndpoint() {
        return "http://127.0.0.1:" + port + "/search";
    }

    private String lookupEndpoint() {
        // Lookup strips "/paper/search" and appends "/paper/{type}:{value}".
        // Configure the client with an endpoint such that after the strip
        // the URL is "http://host/paper/ArXiv:2512.13564v2".
        return "http://127.0.0.1:" + port + "/paper/search";
    }

    private String notFoundLookupEndpoint() {
        return "http://127.0.0.1:" + port + "/notfound-lookup/paper/search";
    }

    /* ----------------------- search() ----------------------- */

    @Test
    void searchBuildsUrlAndReturnsPapers() {
        GoogleScholarClient client = new GoogleScholarClient("test-key", searchEndpoint());
        List<Map<String, String>> papers = client.search("agent memory", 5);
        assertEquals(1, papers.size());
        Map<String, String> p = papers.get(0);
        assertEquals("Memory in the Age of AI Agents", p.get("title"));
        assertEquals("2025", p.get("year"));
        assertEquals("Alice, Bob", p.get("authors"));
        assertEquals("2512.13564v2", p.get("externalIds.ArXiv"));
        assertEquals("10.x/y", p.get("externalIds.DOI"));
        assertEquals("42", p.get("citationCount"));
        assertEquals("https://www.semanticscholar.org/paper/abc123", p.get("url"));

        String q = lastQuery.get();
        assertNotNull(q);
        assertTrue(q.contains("query=agent+memory") || q.contains("query=agent%20memory"),
                "query string must encode the search term: " + q);
        assertTrue(q.contains("limit=5"), "must request 5 results: " + q);
        assertTrue(q.contains("fields="), "must ask for full field set: " + q);
        assertEquals("test-key", lastAuth.get(), "api key must be passed as x-api-key");
    }

    @Test
    void searchClampsNumToFifty() {
        GoogleScholarClient client = new GoogleScholarClient("k", searchEndpoint());
        client.search("x", 999);
        assertTrue(lastQuery.get().contains("limit=50"),
                "num > 50 must clamp to 50: " + lastQuery.get());
    }

    @Test
    void searchClampsNumToOne() {
        GoogleScholarClient client = new GoogleScholarClient("k", searchEndpoint());
        client.search("x", 0);
        assertTrue(lastQuery.get().contains("limit=1"),
                "num < 1 must clamp to 1: " + lastQuery.get());
    }

    @Test
    void searchRejectsBlankQuery() {
        GoogleScholarClient client = new GoogleScholarClient("k", searchEndpoint());
        assertThrows(IllegalArgumentException.class, () -> client.search("", 5));
        assertThrows(IllegalArgumentException.class, () -> client.search("   ", 5));
        assertThrows(IllegalArgumentException.class, () -> client.search(null, 5));
    }

    @Test
    void searchWithoutApiKeyOmitsHeader() {
        GoogleScholarClient client = new GoogleScholarClient(null, searchEndpoint());
        client.search("x", 1);
        assertNull(lastAuth.get(),
                "no x-api-key header must be sent when apiKey is null: " + lastAuth.get());
    }

    @Test
    void searchHandlesHttpError() {
        // /error is not in the dispatch list -> 500.
        GoogleScholarClient client = new GoogleScholarClient("k", "http://127.0.0.1:" + port + "/error");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.search("x", 1));
        assertTrue(ex.getMessage().contains("HTTP 500"),
                "error message must include the HTTP status: " + ex.getMessage());
    }

    /* ----------------------- lookupByExternalId() ----------------------- */

    @Test
    void lookupByExternalIdReturnsRecordOnSuccess() {
        GoogleScholarClient client = new GoogleScholarClient("k", lookupEndpoint());
        Map<String, String> paper = client.lookupByExternalId("ArXiv", "2512.13564v2");
        assertNotNull(paper);
        assertEquals("xyz789", paper.get("paperId"));
        assertEquals("Lookup Result", paper.get("title"));
        assertEquals("2024", paper.get("year"));
        assertEquals("Carol", paper.get("authors"));
    }

    @Test
    void lookupByExternalIdReturns404AsNull() {
        // Map the lookup to /notfound-lookup/paper/search so that after
        // stripping "/paper/search" the request goes to /notfound-lookup
        // which returns 404.
        GoogleScholarClient client = new GoogleScholarClient("k", notFoundLookupEndpoint());
        Map<String, String> paper = client.lookupByExternalId("ArXiv", "2512.13564v2");
        assertNull(paper, "404 must be mapped to null per the contract");
    }

    @Test
    void lookupByExternalIdRejectsBlankArgs() {
        GoogleScholarClient client = new GoogleScholarClient("k", searchEndpoint());
        assertThrows(IllegalArgumentException.class, () -> client.lookupByExternalId(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> client.lookupByExternalId("", "x"));
        assertThrows(IllegalArgumentException.class, () -> client.lookupByExternalId("ArXiv", null));
        assertThrows(IllegalArgumentException.class, () -> client.lookupByExternalId("ArXiv", ""));
    }

    /* ----------------------- toRecord() (pure, no network) ----------------------- */

    @Test
    void toRecordFlattensAuthors() throws Exception {
        JsonNode paper = MAPPER.readTree("""
                {
                  "paperId": "p1",
                  "title": "T",
                  "authors": [
                    {"authorId": "a", "name": "Alice"},
                    {"name": "Bob"},
                    {"authorId": "c", "name": "Carol"}
                  ]
                }
                """);
        Map<String, String> r = GoogleScholarClient.toRecord(paper);
        assertEquals("p1", r.get("paperId"));
        assertEquals("T", r.get("title"));
        assertEquals("Alice, Bob, Carol", r.get("authors"));
    }

    @Test
    void toRecordFlattensExternalIds() throws Exception {
        JsonNode paper = MAPPER.readTree("""
                {
                  "title": "X",
                  "externalIds": {"ArXiv": "2512.13564v2", "DOI": "10.x/y", "MAG": "12345"}
                }
                """);
        Map<String, String> r = GoogleScholarClient.toRecord(paper);
        assertEquals("2512.13564v2", r.get("externalIds.ArXiv"));
        assertEquals("10.x/y", r.get("externalIds.DOI"));
        assertEquals("12345", r.get("externalIds.MAG"));
    }

    @Test
    void toRecordOmitsNullAndMissing() throws Exception {
        JsonNode paper = MAPPER.readTree("""
                {
                  "title": "Only title",
                  "year": 2024,
                  "abstract": null,
                  "venue": "",
                  "citationCount": 7
                }
                """);
        Map<String, String> r = GoogleScholarClient.toRecord(paper);
        assertEquals("Only title", r.get("title"));
        assertEquals("2024", r.get("year"));
        assertEquals("7", r.get("citationCount"));
        assertSame(null, r.get("abstract"));
        assertSame(null, r.get("venue"));
    }

    @Test
    void toRecordEmptyAuthorsProducesNoKey() throws Exception {
        JsonNode paper = MAPPER.readTree("""
                {"title": "T", "authors": []}
                """);
        Map<String, String> r = GoogleScholarClient.toRecord(paper);
        assertSame(null, r.get("authors"));
    }

    @Test
    void toRecordEmptyExternalIdsProducesNoKeys() throws Exception {
        JsonNode paper = MAPPER.readTree("""
                {"title": "T", "externalIds": {}}
                """);
        Map<String, String> r = GoogleScholarClient.toRecord(paper);
        for (String key : r.keySet()) {
            assertTrue(!key.startsWith("externalIds."),
                    "no externalIds.* keys when source map is empty: " + key);
        }
    }

    /* ----------------------- constructors ----------------------- */

    @Test
    void noArgConstructorIsAllowed() {
        // The no-arg constructor must work so callers that want anonymous
        // access don't have to fish an api key out of env themselves.
        GoogleScholarClient client = new GoogleScholarClient();
        assertNotNull(client);
    }

    @Test
    void blankEndpointFallsBackToDefault() {
        // A blank endpoint string should not break the constructor.
        GoogleScholarClient client = new GoogleScholarClient("k", "");
        assertNotNull(client);
        // And single-arg constructor still works (uses default endpoint).
        GoogleScholarClient client2 = new GoogleScholarClient("k");
        assertNotNull(client2);
    }

    @Test
    void blankApiKeyIsTreatedAsMissing() {
        GoogleScholarClient client = new GoogleScholarClient("   ", searchEndpoint());
        client.search("x", 1);
        assertNull(lastAuth.get(),
                "blank api key must not send x-api-key header: " + lastAuth.get());
    }
}
