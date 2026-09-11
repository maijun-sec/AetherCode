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
import java.util.List;
import java.util.Map;

/**
 * Brave Search API client. prior round wired the tool stub; R3 fills in the real backend.
 *
 * <p>Endpoint: {@code https://api.search.brave.com/res/v1/web/search?q=...}
 * Auth: {@code X-Subscription-Token: <api-key>}
 */
public class BraveSearchClient {

    private static final Logger LOG = LoggerFactory.getLogger(BraveSearchClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENDPOINT = "https://api.search.brave.com/res/v1/web/search";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(30))
            .build();

    private final String apiKey;

    public BraveSearchClient(String apiKey) {
        this.apiKey = apiKey;
    }

    public List<Map<String, String>> search(String query, int num) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("AETHERCODE_BRAVE_API_KEY not set");
        }
        try {
            String url = ENDPOINT
                    + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&count=" + Math.min(Math.max(num, 1), 20);
            Request req = new Request.Builder()
                    .url(url)
                    .header("X-Subscription-Token", apiKey)
                    .header("Accept", "application/json")
                    .get()
                    .build();
            try (Response resp = HTTP.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    throw new RuntimeException("HTTP " + resp.code());
                }
                JsonNode root = MAPPER.readTree(resp.body().string());
                JsonNode web = root.path("web").path("results");
                List<Map<String, String>> out = new ArrayList<>();
                if (web.isArray()) {
                    int n = 0;
                    for (JsonNode r : web) {
                        if (n >= num) break;
                        out.add(Map.of(
                                "title",   r.path("title").asText(""),
                                "url",     r.path("url").asText(""),
                                "snippet", r.path("description").asText("")
                        ));
                        n++;
                    }
                }
                return out;
            }
        } catch (Exception e) {
            LOG.warn("brave search failed: {}", e.getMessage());
            throw new RuntimeException("brave search failed: " + e.getMessage(), e);
        }
    }
}
