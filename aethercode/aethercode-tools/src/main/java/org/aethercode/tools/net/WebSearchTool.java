package org.aethercode.tools.net;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Web search. R3 supports two backends:
 *
 * <ul>
 *   <li>{@code AETHERCODE_BRAVE_API_KEY}  → Brave Search</li>
 *   <li>{@code AETHERCODE_SERPER_API_KEY} → Serper.dev (Google results)</li>
 * </ul>
 *
 * <p>If neither is set, the tool surfaces a clear configuration hint.
 */
public class WebSearchTool {

    public static final String NAME = "web_search";

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("query",       Tools.stringProp("Search query."));
        props.put("num_results", Tools.intProp("Optional cap. Default 5. Capped at 20."));
        Map<String, Object> schema = Tools.objectSchema(props, "query");
        return Tools.build(new ToolDef(
                NAME,
                "Search the web. Backends: Brave (AETHERCODE_BRAVE_API_KEY, preferred) " +
                        "or Serper (AETHERCODE_SERPER_API_KEY). Returns titles + URLs + snippets.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        String query = (String) input.get("query");
        if (query == null || query.isBlank()) {
            return Tool.ToolResult.error("query is required");
        }
        int num = input.get("num_results") instanceof Number n ? n.intValue() : 5;
        if (num < 1) num = 1;
        if (num > 20) num = 20;  // hard cap so a runaway model doesn't burn API quota
        String brave = System.getenv("AETHERCODE_BRAVE_API_KEY");
        String serper = System.getenv("AETHERCODE_SERPER_API_KEY");
        if (brave == null || brave.isBlank()) {
            if (serper == null || serper.isBlank()) {
                return Tool.ToolResult.error(
                        "no search backend configured. Set AETHERCODE_BRAVE_API_KEY or AETHERCODE_SERPER_API_KEY.");
            }
        }
        try {
            List<Map<String, String>> results;
            String backend;
            if (brave != null && !brave.isBlank()) {
                results = new BraveSearchClient(brave).search(query, num);
                backend = "brave";
            } else {
                results = new SerperSearchClient(serper).search(query, num);
                backend = "serper";
            }
            if (results.isEmpty()) {
                return Tool.ToolResult.of("(no results for \"" + query + "\" via " + backend + ")");
            }
            StringBuilder sb = new StringBuilder("results (").append(backend)
                    .append(", query=\"").append(query).append("\"):\n");
            int i = 1;
            for (Map<String, String> r : results) {
                String title = r.get("title");
                String url = r.get("url");
                String snippet = r.get("snippet");
                if (title == null) title = "(no title)";
                if (url == null) url = "(no url)";
                if (snippet == null) snippet = "";
                sb.append(i++).append(". ").append(title).append('\n')
                        .append("   ").append(url).append('\n')
                        .append("   ").append(snippet).append("\n\n");
            }
            return Tool.ToolResult.of(sb.toString());
        } catch (Exception e) {
            return Tool.ToolResult.error("search failed for \"" + query + "\": " + e.getMessage());
        }
    }

    public static boolean isReadOnly(Map<String, Object> input) { return true; }
}
