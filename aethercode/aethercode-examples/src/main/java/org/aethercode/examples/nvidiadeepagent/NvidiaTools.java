package org.aethercode.examples.nvidiadeepagent;

import org.aethercode.tools.Tool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Research tools for the NVIDIA Deep Agent Skills example.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/nvidia_deep_agent/src/tools.py}.
 * Provides a web search tool (Tavily) and a webpage fetcher. The
 * actual Tavily call requires {@code TAVILY_API_KEY}; without it,
 * the tool returns a stub so the example can be exercised
 * without API keys.</p>
 */
public final class NvidiaTools {
    private NvidiaTools() {}

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
    private static final String TAVILY_URL = "https://api.tavily.com/search";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Fetch a webpage and convert its HTML body to a simple markdown
     * representation. Mirrors the Python port's
     * {@code fetch_webpage_content}.
     */
    public static String fetchWebpageContent(String url, double timeoutSeconds) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis((long) (timeoutSeconds * 1000)))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return "Error fetching content from " + url + ": HTTP " + response.statusCode();
            }
            return htmlToText(response.body());
        } catch (Exception e) {
            return "Error fetching content from " + url + ": " + e.getMessage();
        }
    }

    /** Convenience overload with default 10s timeout. */
    public static String fetchWebpageContent(String url) {
        return fetchWebpageContent(url, 10.0);
    }

    /**
     * Minimal HTML-to-text stripper. Good enough for the example;
     * not a full markdownify replacement.
     */
    static String htmlToText(String html) {
        if (html == null) return "";
        String stripped = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?is)<style[^>]*>.*?</style>", "")
                .replaceAll("(?is)<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
        return stripped.replaceAll("\\s+", " ").trim();
    }

    /**
     * Tavily-backed web search tool. Mirrors the Python port's
     * {@code tavily_search}. Returns a stub when {@code TAVILY_API_KEY}
     * is not set.
     */
    public static Tool tavilySearch() {
        return Tool.of("tavily_search",
                "Search the web for information on a given query. "
                        + "Uses Tavily to discover relevant URLs, then fetches and returns full webpage content as markdown.",
                (args, ctx) -> {
                    Object queryObj = args.get("query");
                    if (queryObj == null) return "Error: 'query' argument is required.";
                    String query = queryObj.toString();
                    Object maxResultsObj = args.getOrDefault("max_results", 1);
                    int maxResults = maxResultsObj instanceof Number n ? n.intValue() : 1;
                    Object topicObj = args.getOrDefault("topic", "general");
                    String topic = topicObj.toString();

                    String apiKey = System.getenv("TAVILY_API_KEY");
                    if (apiKey == null || apiKey.isBlank()) {
                        return "TAVILY_API_KEY not set; web search is disabled. (query: " + query + ")";
                    }
                    String body = "{\"api_key\":\"" + apiKey
                            + "\",\"query\":\"" + escape(query)
                            + "\",\"max_results\":" + maxResults
                            + ",\"topic\":\"" + escape(topic) + "\"}";
                    try {
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(TAVILY_URL))
                                .timeout(Duration.ofSeconds(30))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build();
                        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() >= 400) {
                            return "Tavily search failed: HTTP " + response.statusCode();
                        }
                        // The Java port keeps the response body verbatim;
                        // the Python port parses Tavily's JSON and walks
                        // each result. We return the JSON envelope so
                        // the agent (or a downstream parser) can do the
                        // same.
                        return "Found results for '" + query + "':\n\n" + response.body();
                    } catch (Exception e) {
                        return "Tavily search failed: " + e.getMessage();
                    }
                });
    }

    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
