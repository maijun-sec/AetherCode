package org.aethercode.examples.deepresearch;

import org.aethercode.tools.Tool;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Research tools.
 *
 * <p>Java port of {@code deepagents-main/examples/deep_research/research_agent/tools.py}.
 * Provides a web search tool (Tavily) and a think tool. The port uses
 * {@link java.net.http.HttpClient} for HTTP, mirrors the markdown
 * stripping (a tiny inline-stripping regex), and keeps the same
 * output format the Python port produces.</p>
 *
 * <p>Tavily access is gated on {@code TAVILY_API_KEY}; if missing, the
 * tool returns a stub response so the example can be exercised
 * without API keys.</p>
 */
public final class ResearchTools {
    private ResearchTools() {}

    private static final Pattern INLINE_LINK = Pattern.compile("\\[([^\\]]+)\\]\\([^)]+\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern INLINE_EMPH = Pattern.compile("[*_~]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
    private static final String TAVILY_URL = "https://api.tavily.com/search";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Fetch a webpage and convert its HTML body to a simple markdown
     * representation. Mirrors {@code fetch_webpage_content}.
     *
     * <p>The Python port uses {@code markdownify} (a full HTML-to-markdown
     * library). The Java port uses a small inline-stripping helper that
     * drops tags and decodes common HTML entities; this is illustrative
     * and not byte-for-byte equivalent.</p>
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
            return markdownify(response.body());
        } catch (Exception e) {
            return "Error fetching content from " + url + ": " + e.getMessage();
        }
    }

    /** Convenience overload with the default 10s timeout. */
    public static String fetchWebpageContent(String url) {
        return fetchWebpageContent(url, 10.0);
    }

    /**
     * Minimal HTML-to-markdown stripper. Strips script/style blocks,
     * drops all tags, decodes common entities, and collapses
     * whitespace. Good enough for the example; not a full
     * markdownify replacement.
     */
    static String markdownify(String html) {
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
        return WHITESPACE.matcher(stripped).replaceAll(" ").trim();
    }

    /**
     * The web search tool. Mirrors the {@code @tool} decorated
     * {@code tavily_search} from the Python port. The tool calls
     * Tavily for URL discovery and then fetches each URL's content.
     *
     * <p>If {@code TAVILY_API_KEY} is not set, the tool returns a
     * placeholder so the agent loop can be exercised in isolation.</p>
     */
    public static Tool tavilySearch() {
        return Tool.of(
                "tavily_search",
                "Search the web for information on a given query. Uses Tavily to discover relevant URLs, then fetches and returns full webpage content as markdown.",
                (args, ctx) -> tavilySearchImpl(args));
    }

    @SuppressWarnings("unchecked")
    private static String tavilySearchImpl(Map<String, Object> args) {
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

        // Call Tavily.
        String body = "{\"api_key\":\"" + apiKey
                + "\",\"query\":\"" + escape(query)
                + "\",\"max_results\":" + maxResults
                + ",\"topic\":\"" + escape(topic) + "\"}";
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(TAVILY_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return "Tavily search failed: HTTP " + response.statusCode() + " " + response.body();
            }
            Map<String, Object> payload = MiniJson.parseObject(response.body());
            Object resultsObj = payload.get("results");
            if (!(resultsObj instanceof List<?> rawList)) {
                return "Found 0 result(s) for '" + query + "'";
            }
            List<Map<String, Object>> results = rawList.stream()
                    .filter(e -> e instanceof Map)
                    .map(e -> (Map<String, Object>) e)
                    .toList();

            String resultTexts = results.stream().map(r -> {
                Object url = r.get("url");
                Object title = r.get("title");
                String urlStr = url == null ? "" : url.toString();
                String titleStr = title == null ? urlStr : title.toString();
                String content = fetchWebpageContent(urlStr);
                return "## " + titleStr
                        + "\n**URL:** " + urlStr
                        + "\n\n" + content
                        + "\n\n---\n";
            }).collect(Collectors.joining(""));

            return "Found " + results.size() + " result(s) for '" + query + "':\n\n" + resultTexts;
        } catch (Exception e) {
            return "Tavily search failed: " + e.getMessage();
        }
    }

    /**
     * The think tool. Mirrors the {@code @tool} decorated
     * {@code think_tool} from the Python port. The tool just echoes
     * the reflection back so the agent's transcript keeps a record of
     * the strategic thought.
     */
    public static Tool thinkTool() {
        return Tool.of(
                "think_tool",
                "Tool for strategic reflection on research progress and decision-making. "
                        + "Use this tool after each search to analyze results and plan next steps.",
                (args, ctx) -> {
                    Object reflection = args.get("reflection");
                    if (reflection == null) return "Reflection recorded (no content).";
                    return "Reflection recorded: " + reflection;
                });
    }

    /** Minimal JSON string escaper for the Tavily request body. */
    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Mini JSON parser used to keep the example self-contained
     * (no Jackson dependency in the tool itself). The parser handles
     * the small subset of JSON Tavily returns: a top-level object
     * with string values and a {@code results} array of objects.
     */
    static final class MiniJson {
        static Map<String, Object> parseObject(String json) {
            // Find first '{' and matching '}'
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end < 0 || end <= start) return Map.of();
            String body = json.substring(start + 1, end);
            return parseObjectBody(body);
        }

        private static Map<String, Object> parseObjectBody(String body) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            int i = 0;
            while (i < body.length()) {
                int keyStart = body.indexOf('"', i);
                if (keyStart < 0) break;
                int keyEnd = body.indexOf('"', keyStart + 1);
                if (keyEnd < 0) break;
                String key = body.substring(keyStart + 1, keyEnd);
                int colon = body.indexOf(':', keyEnd);
                if (colon < 0) break;
                int valueStart = skipWs(body, colon + 1);
                if (valueStart >= body.length()) break;
                char c = body.charAt(valueStart);
                int[] next = new int[]{valueStart};
                Object value;
                if (c == '"') {
                    value = parseString(body, next);
                } else if (c == '[') {
                    value = parseArray(body, next);
                } else if (c == '{') {
                    value = parseObjectValue(body, next);
                } else {
                    value = parseScalar(body, next);
                }
                out.put(key, value);
                i = next[0];
                int comma = body.indexOf(',', i);
                if (comma < 0) break;
                i = comma + 1;
            }
            return out;
        }

        private static int skipWs(String s, int from) {
            int i = from;
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            return i;
        }

        private static String parseString(String body, int[] next) {
            int i = body.indexOf('"', next[0] + 1);
            if (i < 0) {
                next[0] = body.length();
                return "";
            }
            String s = body.substring(next[0] + 1, i);
            next[0] = i + 1;
            return s;
        }

        private static List<Object> parseArray(String body, int[] next) {
            List<Object> out = new java.util.ArrayList<>();
            int i = next[0] + 1;
            while (i < body.length()) {
                i = skipWs(body, i);
                if (i >= body.length() || body.charAt(i) == ']') {
                    next[0] = i + 1;
                    return out;
                }
                char c = body.charAt(i);
                int[] cursor = new int[]{i};
                Object v;
                if (c == '"') v = parseString(body, cursor);
                else if (c == '{') v = parseObjectValue(body, cursor);
                else if (c == '[') v = parseArray(body, cursor);
                else v = parseScalar(body, cursor);
                out.add(v);
                i = skipWs(body, cursor[0]);
                if (i < body.length() && body.charAt(i) == ',') i++;
            }
            next[0] = body.length();
            return out;
        }

        private static Map<String, Object> parseObjectValue(String body, int[] next) {
            int close = findMatching(body, next[0], '{', '}');
            String inner = body.substring(next[0] + 1, close);
            next[0] = close + 1;
            return parseObjectBody(inner);
        }

        private static int findMatching(String body, int from, char open, char close) {
            int depth = 0;
            for (int i = from; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == '"') {
                    // skip the string body
                    int j = i + 1;
                    while (j < body.length() && body.charAt(j) != '"') {
                        if (body.charAt(j) == '\\') j++;
                        j++;
                    }
                    i = j;
                    continue;
                }
                if (c == open) depth++;
                else if (c == close) {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return body.length() - 1;
        }

        private static Object parseScalar(String body, int[] next) {
            int i = next[0];
            int end = i;
            while (end < body.length()
                    && body.charAt(end) != ','
                    && body.charAt(end) != '}'
                    && body.charAt(end) != ']'
                    && !Character.isWhitespace(body.charAt(end))) {
                end++;
            }
            String token = body.substring(i, end);
            next[0] = end;
            if ("true".equals(token)) return Boolean.TRUE;
            if ("false".equals(token)) return Boolean.FALSE;
            if ("null".equals(token)) return null;
            try { return Long.parseLong(token); } catch (NumberFormatException ignore) { }
            try { return Double.parseDouble(token); } catch (NumberFormatException ignore) { }
            return token;
        }
    }
}
