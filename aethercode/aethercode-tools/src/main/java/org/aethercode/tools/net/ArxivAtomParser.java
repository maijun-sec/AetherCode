package org.aethercode.tools.net;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Atom XML parser for arXiv API responses.
 *
 * <p>The arXiv API returns Atom XML (application/atom+xml). A full
 * StAX / JAXB parser would be overkill for the few fields we need, and
 * would add a dependency. This file is a thin regex-based extractor
 * that pulls the few fields a model needs to cite a paper.</p>
 *
 * <p>Recognized fields:</p>
 * <ul>
 *   <li>{@code <title>}  -- the paper title (text content)</li>
 *   <li>{@code <summary>}  -- the abstract</li>
 *   <li>{@code <name>}  -- author names (joined with ", ")</li>
 *   <li>{@code <arxiv:doi>}  -- DOI if present</li>
 *   <li>{@code <category>}  -- primary category (the {@code term} attr of
 *       the entry's first {@code <category>})</li>
 *   <li>{@code <published>}  -- publication date (first 10 chars)</li>
 *   <li>{@code <id>}  -- the canonical arXiv URL (e.g. https://arxiv.org/abs/...)</li>
 * </ul>
 *
 * <p>The regex approach is fragile by construction -- a title containing
 * literal {@code <} or {@code &} will break it -- but the arXiv API
 * escapes these (e.g. {@code &lt;}, {@code &amp;}), so the simple regex
 * is robust enough in practice. If the parser returns {@code null} the
 * caller should fall back to the raw body.</p>
 */
public final class ArxivAtomParser {

    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>",
            Pattern.DOTALL);
    private static final Pattern SUMMARY = Pattern.compile("<summary>(.*?)</summary>",
            Pattern.DOTALL);
    private static final Pattern NAME = Pattern.compile("<name>(.*?)</name>",
            Pattern.DOTALL);
    private static final Pattern DOI = Pattern.compile("<arxiv:doi[^>]*>(.*?)</arxiv:doi>",
            Pattern.DOTALL);
    private static final Pattern PRIMARY_CATEGORY = Pattern.compile(
            "<arxiv:primary_category[^>]*term=\"([^\"]*)\"");
    private static final Pattern PUBLISHED = Pattern.compile("<published>(.*?)</published>",
            Pattern.DOTALL);
    private static final Pattern ID_TAG = Pattern.compile("<id>(.*?)</id>",
            Pattern.DOTALL);

    private ArxivAtomParser() {}

    /**
     * Extract a Markdown block summarizing the first {@code <entry>} in
     * the response. Returns {@code null} when no recognizable fields
     * are present (e.g. an error response from the API).
     */
    public static String parse(String body) {
        if (body == null || body.isBlank()) return null;
        String title = match(TITLE, body);
        String summary = match(SUMMARY, body);
        StringBuilder authors = new StringBuilder();
        Matcher m = NAME.matcher(body);
        int count = 0;
        while (m.find()) {
            if (count > 0) authors.append(", ");
            authors.append(decode(m.group(1).trim()));
            count++;
        }
        if (title == null && summary == null && count == 0) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        if (title != null) {
            out.append("title: ").append(decode(title.trim())).append('\n');
        }
        if (count > 0) {
            out.append("authors: ").append(authors).append('\n');
        }
        String id = match(ID_TAG, body);
        if (id != null) {
            String idStr = decode(id.trim());
            // The first <id> is the feed's own URL; the entry's <id> is later.
            // Take the LAST id, which is the entry's canonical URL.
            Matcher all = ID_TAG.matcher(body);
            while (all.find()) {
                idStr = decode(all.group(1).trim());
            }
            out.append("id: ").append(idStr).append('\n');
        }
        String published = match(PUBLISHED, body);
        if (published != null && published.length() >= 10) {
            out.append("published: ").append(published.substring(0, 10)).append('\n');
        }
        Matcher cat = PRIMARY_CATEGORY.matcher(body);
        if (cat.find()) {
            out.append("primary_category: ").append(cat.group(1)).append('\n');
        }
        String doi = match(DOI, body);
        if (doi != null) {
            out.append("doi: ").append(doi.trim()).append('\n');
        }
        if (summary != null) {
            String abs = decode(summary.trim());
            // Collapse whitespace so the abstract is one readable block.
            abs = abs.replaceAll("\\s+", " ");
            out.append("\nabstract: ").append(abs).append('\n');
        }
        return out.toString();
    }

    private static String match(Pattern p, String body) {
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Decode the four XML entities that show up in arXiv Atom responses.
     * Doing it by hand avoids pulling in a general-purpose XML decoder.
     */
    static String decode(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                if (s.startsWith("&amp;", i)) { out.append('&'); i += 5; continue; }
                if (s.startsWith("&lt;", i))   { out.append('<'); i += 4; continue; }
                if (s.startsWith("&gt;", i))   { out.append('>'); i += 4; continue; }
                if (s.startsWith("&quot;", i)) { out.append('"'); i += 6; continue; }
                if (s.startsWith("&apos;", i)) { out.append('\''); i += 6; continue; }
                if (s.startsWith("&#", i)) {
                    int semi = s.indexOf(';', i);
                    if (semi > i + 2 && semi - i <= 8) {
                        try {
                            int code = Integer.parseInt(s.substring(i + 2, semi));
                            out.append((char) code);
                            i = semi + 1;
                            continue;
                        } catch (NumberFormatException ex) {
                            // fall through, treat as literal
                        }
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
