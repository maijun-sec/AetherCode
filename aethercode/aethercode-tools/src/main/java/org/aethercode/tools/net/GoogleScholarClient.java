package org.aethercode.tools.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Academic paper search. Wraps the public <a href="https://api.semanticscholar.org/">Semantic
 * Scholar Graph API</a> (not the Google Scholar web UI -- Google Scholar has no public
 * programmatic API; scraping it is against their ToS).
 *
 * <p>Why Semantic Scholar over alternatives:</p>
 * <ul>
 *   <li><b>Free, no key required</b> for the basic search endpoint (50 req / 5 min).</li>
 *   <li>Higher rate limits with an optional {@code SEMANTIC_SCHOLAR_API_KEY}
 *       (100 req / min sustained). Sign up at
 *       https://www.semanticscholar.org/product/api#api-key-form .</li>
 *   <li>Returns structured fields: title / authors / year / abstract / externalIds (DOI,
 *       ArXiv, MAG) / citationCount / url. The exact shape an agent needs to cite
 *       a paper without re-parsing HTML.</li>
 *   <li>Aligned with this repo's existing {@code reference/papers/} workflow (papers
 *       are stored by arXiv id, e.g. {@code 2512.13564v2}).</li>
 * </ul>
 *
 * <p>Endpoint: {@code https://api.semanticscholar.org/graph/v1/paper/search?query=...}
 * Auth (optional): {@code x-api-key: <api-key>}.</p>
 */
public class GoogleScholarClient {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleScholarClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_ENDPOINT = "https://api.semanticscholar.org/graph/v1/paper/search";
    private static final String[] DEFAULT_FIELDS = new String[]{
            "paperId", "title", "abstract", "year", "venue", "publicationDate",
            "authors", "externalIds", "url", "citationCount", "referenceCount"
    };
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(30))
            .build();

    private final String apiKey;
    private final String endpoint;

    public GoogleScholarClient() { this(null, null); }

    public GoogleScholarClient(String apiKey) { this(apiKey, null); }

    /**
     * Test seam: pass a custom endpoint (e.g. an in-JVM {@code HttpServer})
     * to drive the HTTP path without a real network. Production callers
     * use the no-arg or single-arg constructor.
     */
    public GoogleScholarClient(String apiKey, String endpoint) {
        this.apiKey = apiKey;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint;
    }

    /**
     * Search Semantic Scholar for papers matching {@code query}.
     *
     * @param query free-form query (title words, author, topic)
     * @param num   cap on returned papers (clamped to [1, 50])
     * @return ordered list of papers, each as a flat key/value map
     */
    public List<Map<String, String>> search(String query, int num) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must be non-blank");
        }
        int cap = Math.min(Math.max(num, 1), 50);
        try {
            String url = endpoint
                    + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&limit=" + cap
                    + "&fields=" + URLEncoder.encode(String.join(",", DEFAULT_FIELDS), StandardCharsets.UTF_8);
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .get();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("x-api-key", apiKey);
            }
            try (Response resp = HTTP.newCall(builder.build()).execute()) {
                if (!resp.isSuccessful()) {
                    String err = resp.body() == null ? "" : resp.body().string();
                    throw new RuntimeException("HTTP " + resp.code() + " " + err);
                }
                JsonNode root = MAPPER.readTree(resp.body().string());
                JsonNode data = root.path("data");
                List<Map<String, String>> out = new ArrayList<>();
                if (data.isArray()) {
                    int n = 0;
                    for (JsonNode p : data) {
                        if (n >= cap) break;
                        out.add(toRecord(p));
                        n++;
                    }
                }
                return out;
            }
        } catch (Exception e) {
            LOG.warn("scholar search failed: {}", e.getMessage());
            throw new RuntimeException("scholar search failed: " + e.getMessage(), e);
        }
    }

    /**
     * Look up a single paper by an external id (DOI, ArXiv, MAG, PMID, etc.).
     *
     * @param idType  one of "DOI", "ArXiv", "MAG", "PMID", "PMCID", "URL"
     * @param idValue the id value
     * @return the paper as a flat key/value map, or {@code null} if not found
     */
    public Map<String, String> lookupByExternalId(String idType, String idValue) {
        if (idType == null || idType.isBlank() || idValue == null || idValue.isBlank()) {
            throw new IllegalArgumentException("idType and idValue must be non-blank");
        }
        try {
            // Build the lookup URL by stripping "/paper/search" from the
            // configured endpoint and appending "/paper/{type}:{value}".
            // For the default endpoint this yields
            // https://api.semanticscholar.org/graph/v1/paper/{type}:{value}.
            // For a test endpoint like http://localhost/paper/search we get
            // http://localhost/paper/{type}:{value} which is what the
            // in-JVM HttpServer dispatches in tests.
            String base = endpoint.endsWith("/paper/search")
                    ? endpoint.substring(0, endpoint.length() - "/paper/search".length())
                    : endpoint;
            String url = base + "/paper/" + idType.toUpperCase()
                    + ":" + URLEncoder.encode(idValue, StandardCharsets.UTF_8)
                    + "?fields=" + URLEncoder.encode(String.join(",", DEFAULT_FIELDS), StandardCharsets.UTF_8);
            Request.Builder builder = new Request.Builder().url(url).header("Accept", "application/json").get();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("x-api-key", apiKey);
            }
            try (Response resp = HTTP.newCall(builder.build()).execute()) {
                if (resp.code() == 404) {
                    return null;
                }
                if (!resp.isSuccessful()) {
                    String err = resp.body() == null ? "" : resp.body().string();
                    throw new RuntimeException("HTTP " + resp.code() + " " + err);
                }
                return toRecord(MAPPER.readTree(resp.body().string()));
            }
        } catch (Exception e) {
            LOG.warn("scholar lookup failed for {}:{}: {}", idType, idValue, e.getMessage());
            throw new RuntimeException("scholar lookup failed: " + e.getMessage(), e);
        }
    }

    /**
     * Flatten a Semantic Scholar paper JSON node to a {@code Map<String, String>}.
     *
     * <p>List-valued fields (authors) are joined with {@code ", "}. Nested maps
     * (externalIds) are flattened into dotted keys ({@code "externalIds.DOI"},
     * {@code "externalIds.ArXiv"}). Null fields are omitted so the model only
     * sees what was actually present.</p>
     */
    static Map<String, String> toRecord(JsonNode p) {
        Map<String, String> out = new LinkedHashMap<>();
        putIfPresent(out, "paperId", p.path("paperId"));
        putIfPresent(out, "title", p.path("title"));
        putIfPresent(out, "abstract", p.path("abstract"));
        if (p.hasNonNull("year")) {
            out.put("year", p.path("year").asText());
        }
        putIfPresent(out, "venue", p.path("venue"));
        putIfPresent(out, "publicationDate", p.path("publicationDate"));
        putIfPresent(out, "url", p.path("url"));
        if (p.hasNonNull("citationCount")) {
            out.put("citationCount", p.path("citationCount").asText());
        }
        if (p.hasNonNull("referenceCount")) {
            out.put("referenceCount", p.path("referenceCount").asText());
        }
        // authors
        JsonNode authors = p.path("authors");
        if (authors.isArray() && authors.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < authors.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(authors.path(i).path("name").asText(""));
            }
            out.put("authors", sb.toString());
        }
        // externalIds
        JsonNode ext = p.path("externalIds");
        if (ext.isObject()) {
            Iterator<String> fields = ext.fieldNames();
            while (fields.hasNext()) {
                String key = fields.next();
                JsonNode v = ext.path(key);
                if (!v.isMissingNode() && !v.isNull()) {
                    out.put("externalIds." + key, v.asText());
                }
            }
        }
        return out;
    }

    private static void putIfPresent(Map<String, String> out, String key, JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return;
        }
        String text = value.asText();
        if (text != null && !text.isEmpty()) {
            out.put(key, text);
        }
    }
}
