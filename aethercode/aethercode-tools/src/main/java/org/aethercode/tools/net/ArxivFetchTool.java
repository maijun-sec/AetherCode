package org.aethercode.tools.net;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Fetch an arXiv paper. The model already has the {@code web_fetch} tool
 * for arbitrary URLs, but the arXiv URL space is opinionated enough that
 * a dedicated tool is worth the indirection: the model doesn't have to
 * remember which path is "abstract" vs "PDF" vs "API", and we get to
 * layer on arXiv-specific niceties (id validation, version stripping,
 * structured Atom XML parsing) that wouldn't fit a generic fetch.
 *
 * <p>Two response modes:</p>
 * <ul>
 *   <li>{@code format=html} (default) — fetch the abstract page
 *       ({@code https://arxiv.org/abs/<id>}) and return its text content
 *       (delegates to {@link WebFetchTool}).</li>
 *   <li>{@code format=api} — query the arXiv Atom API
 *       ({@code http://export.arxiv.org/api/query?id_list=<id>}) and
 *       return the title / authors / abstract / categories / DOI as a
 *       Markdown block. Cheaper than HTML scraping and machine-readable.</li>
 * </ul>
 *
 * <p>Id normalization:</p>
 * <ul>
 *   <li>Accepts {@code 2512.13564}, {@code 2512.13564v2}, {@code arXiv:2512.13564v2},
 *       and full URLs ({@code https://arxiv.org/abs/2512.13564v2}).</li>
 *   <li>Strips the version suffix when querying the API (the API treats
 *       each version as a separate document, which is rarely what the
 *       model wants for a one-shot lookup).</li>
 *   <li>Rejects malformed ids with a clear error.</li>
 * </ul>
 */
public class ArxivFetchTool {

    public static final String NAME = "arxiv_fetch";

    public static Tool build() {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("arxiv_id", Tools.stringProp(
                "arXiv paper id. Accepts bare id (2512.13564), versioned (2512.13564v2), " +
                "arXiv: prefix, or a full arxiv.org/abs/ URL."));
        props.put("format", Tools.stringProp(
                "Optional response format. Default 'html' (abstract page text). " +
                "Set 'api' to get structured Atom XML fields (title/authors/abstract/DOI)."));
        Map<String, Object> schema = Tools.objectSchema(props, "arxiv_id");
        return Tools.build(new ToolDef(
                NAME,
                "Fetch an arXiv paper by id. Returns the abstract page text (default) or " +
                        "structured fields via the arXiv API (format=api). Pairs with google_scholar " +
                        "for paper discovery: scholar_search → arxiv_fetch.",
                schema,
                (input, ctx) -> CompletableFuture.completedFuture(call(input, ctx))
        ));
    }

    public static Tool.ToolResult call(Map<String, Object> input, Tool.CallContext ctx) {
        String raw = (String) input.get("arxiv_id");
        if (raw == null || raw.isBlank()) {
            return Tool.ToolResult.error("arxiv_id is required");
        }
        String id = normalizeId(raw);
        if (id == null) {
            return Tool.ToolResult.error("invalid arxiv_id: " + raw);
        }
        String format = input.get("format") instanceof String s ? s.toLowerCase() : "html";
        return switch (format) {
            case "api" -> callApi(id);
            case "html" -> callHtml(id);
            default -> Tool.ToolResult.error("invalid format: " + format + " (expected 'html' or 'api')");
        };
    }

    public static boolean isReadOnly(Map<String, Object> input) { return true; }

    /**
     * Normalize any of the accepted id forms to {@code YYMM.NNNNN[vN]}.
     *
     * @return the normalized id, or {@code null} if the input is not a
     *         recognizable arXiv id
     */
    static String normalizeId(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // Full URL form
        if (s.startsWith("http://") || s.startsWith("https://")) {
            int idxSlash = s.lastIndexOf('/');
            if (idxSlash < 0) return null;
            s = s.substring(idxSlash + 1);
        }
        // arXiv: prefix
        if (s.toLowerCase().startsWith("arxiv:")) {
            s = s.substring("arxiv:".length());
        }
        // The shape is YYMM.NNNNN (5 digits) optionally followed by vN.
        // We accept the new style only -- the old-style cs.AI/YYMMDD ids
        // are rare and not in this repo's reference corpus.
        int dot = s.indexOf('.');
        if (dot != 4 && dot != 5) return null;
        // Year 2 or 4 digits, dot, 4-5 digit paper number
        if (dot < 2 || dot > 4) return null;
        int secondDot = s.indexOf('.', dot + 1);
        if (secondDot != -1) return null;
        // The portion after the dot must be digits, optionally followed by
        // 'v' and a number.
        String afterDot = s.substring(dot + 1).toLowerCase();
        int vIdx = afterDot.indexOf('v');
        String numberPart = vIdx < 0 ? afterDot : afterDot.substring(0, vIdx);
        if (numberPart.length() < 4 || numberPart.length() > 5) return null;
        for (int i = 0; i < numberPart.length(); i++) {
            if (!Character.isDigit(numberPart.charAt(i))) return null;
        }
        if (vIdx >= 0) {
            String versionPart = afterDot.substring(vIdx + 1);
            for (int i = 0; i < versionPart.length(); i++) {
                if (!Character.isDigit(versionPart.charAt(i))) return null;
            }
        }
        return s.toLowerCase();
    }

    private static Tool.ToolResult callHtml(String id) {
        String url = "https://arxiv.org/abs/" + id;
        // Delegate to WebFetchTool for the actual HTTP + HTML strip.
        Tool.ToolResult fetched = WebFetchTool.call(
                Map.of("url", url, "max_chars", 20_000),
                Tool.CallContext.of("arxiv_fetch"));
        if (fetched.isError()) {
            return Tool.ToolResult.error("arxiv_fetch failed for " + id + ": "
                    + fetched.output().toString());
        }
        String body = fetched.output().toString();
        // Prepend a header so the model knows what it has.
        return Tool.ToolResult.of("arXiv " + id + " (abstract page, html):\n" + body);
    }

    private static Tool.ToolResult callApi(String id) {
        // The arXiv API treats vN as a separate doc; for a one-shot lookup
        // the model usually wants the latest version, so strip the suffix.
        String apiId = stripVersion(id);
        String url = "https://export.arxiv.org/api/query?id_list=" + apiId;
        Tool.ToolResult fetched = WebFetchTool.call(
                Map.of("url", url, "max_chars", 30_000),
                Tool.CallContext.of("arxiv_fetch"));
        if (fetched.isError()) {
            return Tool.ToolResult.error("arxiv_fetch (api) failed for " + apiId + ": "
                    + fetched.output().toString());
        }
        String body = fetched.output().toString();
        String parsed = ArxivAtomParser.parse(body);
        if (parsed == null || parsed.isBlank()) {
            // Fall back to the raw body if parsing didn't find anything
            // recognizable -- the model still gets a useful response.
            return Tool.ToolResult.of("arXiv " + id + " (api, unparsed):\n" + body);
        }
        return Tool.ToolResult.of("arXiv " + id + " (api):\n" + parsed);
    }

    static String stripVersion(String id) {
        int v = id.toLowerCase().indexOf('v');
        if (v < 0) return id;
        // Only strip if the character after 'v' is a digit (so we don't
        // accidentally truncate an id that has a literal 'v' in it).
        if (v + 1 < id.length() && Character.isDigit(id.charAt(v + 1))) {
            return id.substring(0, v);
        }
        return id;
    }
}
