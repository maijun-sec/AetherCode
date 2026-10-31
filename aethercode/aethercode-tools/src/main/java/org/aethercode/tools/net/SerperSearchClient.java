/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.tools.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Serper.dev search client. Alternative to Brave, used when the operator provides a
 * {@code AETHERCODE_SERPER_API_KEY}. The two backends expose the same shape so the
 * {@link WebSearchTool} can pick either at runtime.
 */
public class SerperSearchClient {

    private static final Logger LOG = LoggerFactory.getLogger(SerperSearchClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENDPOINT = "https://google.serper.dev/search";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(30))
            .build();

    private final String apiKey;

    public SerperSearchClient(String apiKey) { this.apiKey = apiKey; }

    public List<Map<String, String>> search(String query, int num) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("AETHERCODE_SERPER_API_KEY not set");
        }
        try {
            String body = "{\"q\":\"" + escape(query) + "\",\"num\":" + Math.min(Math.max(num, 1), 20) + "}";
            Request req = new Request.Builder()
                    .url(ENDPOINT)
                    .header("X-API-KEY", apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body, JSON))
                    .build();
            try (Response resp = HTTP.newCall(req).execute()) {
                if (!resp.isSuccessful()) throw new RuntimeException("HTTP " + resp.code());
                JsonNode root = MAPPER.readTree(resp.body().string());
                JsonNode organic = root.path("organic");
                List<Map<String, String>> out = new ArrayList<>();
                if (organic.isArray()) {
                    int n = 0;
                    for (JsonNode r : organic) {
                        if (n >= num) break;
                        out.add(Map.of(
                                "title",   r.path("title").asText(""),
                                "url",     r.path("link").asText(""),
                                "snippet", r.path("snippet").asText("")
                        ));
                        n++;
                    }
                }
                return out;
            }
        } catch (Exception e) {
            LOG.warn("serper search failed: {}", e.getMessage());
            throw new RuntimeException("serper search failed: " + e.getMessage(), e);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
