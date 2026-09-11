package org.aethercode.tools.net;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Academic paper search. The {@code google_scholar} name reflects what the
 * model expects ("the Google Scholar web UI"); the backend is the public
 * Semantic Scholar Graph API because Google Scholar has no public
 * programmatic API. See {@link GoogleScholarClient} for the trade-off.
 *
 * <p>Returns a human-readable list of paper title / authors / year / abstract
 * / ArXiv id / DOI / url, one paper per block. Default cap is 5, max 50.</p>
 *
 * <p>Configuration: optionally set {@code SEMANTIC_SCHOLAR_API_KEY} to lift
 * the rate limit from 50 req / 5 min (anonymous) to 100 req / min (key).</p>
 */
public class ScholarSearchTool {

    public static final String NAME = "google_scholar";

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("query",       Tools.stringProp("Search query: title words, author name, topic, ArXiv id, or DOI."));
        props.put("num_results", Tools.intProp("Optional cap on number of results. Default 5. Max 50."));
        Map<String, Object> schema = Tools.objectSchema(props, "query");
        return Tools.build(new ToolDef(
                NAME,
                "Search academic papers. Backed by the Semantic Scholar Graph API " +
                        "(https://api.semanticscholar.org). Returns title / authors / year / abstract / " +
                        "ArXiv id / DOI / url. Optional SEMANTIC_SCHOLAR_API_KEY lifts rate limit.",
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
        if (num > 50) num = 50;
        String apiKey = System.getenv("SEMANTIC_SCHOLAR_API_KEY");
        try {
            List<Map<String, String>> papers = new GoogleScholarClient(apiKey).search(query, num);
            if (papers.isEmpty()) {
                return Tool.ToolResult.of("(no papers for \"" + query + "\")");
            }
            StringBuilder sb = new StringBuilder("scholar results (query=\"").append(query).append("\", ")
                    .append(papers.size()).append(" of cap ").append(num).append("):\n\n");
            int i = 1;
            for (Map<String, String> p : papers) {
                sb.append(i++).append(". ").append(orEmpty(p, "title", "(no title)")).append('\n');
                String authors = orEmpty(p, "authors", "");
                String year = orEmpty(p, "year", "");
                String venue = orEmpty(p, "venue", "");
                if (!authors.isEmpty() || !year.isEmpty() || !venue.isEmpty()) {
                    sb.append("   ");
                    if (!authors.isEmpty()) sb.append(authors);
                    if (!year.isEmpty()) {
                        if (!authors.isEmpty()) sb.append(" (");
                        sb.append(year);
                        if (!authors.isEmpty()) sb.append(")");
                    }
                    if (!venue.isEmpty()) sb.append(" — ").append(venue);
                    sb.append('\n');
                }
                String abs = p.get("abstract");
                if (abs != null && !abs.isBlank()) {
                    String oneLine = abs.replaceAll("\\s+", " ").trim();
                    if (oneLine.length() > 240) oneLine = oneLine.substring(0, 240) + "…";
                    sb.append("   ").append(oneLine).append('\n');
                }
                String arxiv = p.get("externalIds.ArXiv");
                String doi = p.get("externalIds.DOI");
                String url = orEmpty(p, "url", "");
                if (arxiv != null && !arxiv.isBlank()) sb.append("   arXiv:").append(arxiv).append('\n');
                if (doi != null && !doi.isBlank()) sb.append("   DOI:").append(doi).append('\n');
                if (!url.isEmpty()) sb.append("   ").append(url).append('\n');
                String cites = p.get("citationCount");
                if (cites != null && !cites.isBlank()) {
                    sb.append("   cited by ").append(cites).append(" paper(s)\n");
                }
                sb.append('\n');
            }
            return Tool.ToolResult.of(sb.toString());
        } catch (Exception e) {
            return Tool.ToolResult.error("scholar search failed for \"" + query + "\": " + e.getMessage());
        }
    }

    public static boolean isReadOnly(Map<String, Object> input) { return true; }

    private static String orEmpty(Map<String, String> m, String key, String fallback) {
        String v = m.get(key);
        return v == null || v.isEmpty() ? fallback : v;
    }
}
