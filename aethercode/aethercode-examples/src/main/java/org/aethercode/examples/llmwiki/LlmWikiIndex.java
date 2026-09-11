package org.aethercode.examples.llmwiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Index-specific helpers for wiki content catalog generation.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/llm-wiki/index.py}. Generates the
 * {@code /wiki/index.md} catalog page from the current set of
 * markdown files under {@code /wiki/}. Mirrors the category
 * bucketing, page-title extraction, and metadata extraction the
 * Python port performs.</p>
 */
public final class LlmWikiIndex {
    private LlmWikiIndex() {}

    /** Canonical category order, matching the Python port's tuple. */
    public static final List<String> INDEX_CATEGORY_ORDER = List.of(
            "Entities",
            "Concepts",
            "Sources",
            "Timelines",
            "Queries",
            "Syntheses",
            "Other Pages");

    /** Directory → category mapping. */
    public static final Map<String, String> INDEX_DIRECTORY_CATEGORIES = Map.ofEntries(
            Map.entry("entity", "Entities"),
            Map.entry("entities", "Entities"),
            Map.entry("concept", "Concepts"),
            Map.entry("concepts", "Concepts"),
            Map.entry("source", "Sources"),
            Map.entry("sources", "Sources"),
            Map.entry("timeline", "Timelines"),
            Map.entry("timelines", "Timelines"),
            Map.entry("query", "Queries"),
            Map.entry("queries", "Queries"),
            Map.entry("synthesis", "Syntheses"),
            Map.entry("syntheses", "Syntheses"));

    private static final Pattern INDEX_DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern INDEX_SOURCE_REF_PATTERN = Pattern.compile("/raw/([A-Za-z0-9._/\\-]+)");
    private static final Pattern INLINE_LINK = Pattern.compile("\\[([^\\]]+)\\]\\([^)]+\\)");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern INLINE_EMPH = Pattern.compile("[*_~]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Build default index markdown for empty wikis. */
    public static String emptyIndexText(String topic) {
        List<String> lines = List.of(
                "# " + topic + " Wiki",
                "",
                "Content catalog for wiki navigation and retrieval.",
                "Read this page first during query workflows.",
                "",
                "## Other Pages",
                "",
                "- _No pages yet._");
        return String.join("\n", lines) + "\n";
    }

    /** Create a human-readable title from a markdown file path. */
    static String indexFallbackTitle(Path path) {
        String stem = path.getFileName().toString();
        int dot = stem.lastIndexOf('.');
        if (dot >= 0) stem = stem.substring(0, dot);
        return stem.replace('-', ' ').replace('_', ' ').strip();
    }

    /** Strip basic inline markdown to plain text for index snippets. */
    static String stripMarkdownInline(String text) {
        if (text == null) return "";
        String stripped = INLINE_LINK.matcher(text).replaceAll("$1");
        stripped = INLINE_CODE.matcher(stripped).replaceAll("$1");
        stripped = INLINE_EMPH.matcher(stripped).replaceAll("");
        stripped = WHITESPACE.matcher(stripped).replaceAll(" ");
        return stripped.strip().replaceAll("^[-:]+|[-:]+$", "");
    }

    /** Extract a display title for an index entry. */
    static String pageTitleForIndex(Path relativePath, String content) {
        for (String line : content.split("\n")) {
            String stripped = line.strip();
            if (!stripped.startsWith("#")) continue;
            String heading = stripMarkdownInline(stripped.replaceFirst("^#+", "").strip());
            if (!heading.isEmpty()) return heading;
        }
        return indexFallbackTitle(relativePath);
    }

    /** Extract a one-line summary for an index entry from page content. */
    static String pageSummaryForIndex(String content) {
        boolean inCode = false;
        for (String line : content.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("```")) { inCode = !inCode; continue; }
            if (inCode || stripped.isEmpty() || stripped.startsWith("#")) continue;
            String candidate = stripMarkdownInline(stripped.replaceFirst("^[-*+]\\s+", "").strip());
            if (candidate.isEmpty()) continue;
            if (candidate.length() > 150) {
                return candidate.substring(0, 147).strip() + "...";
            }
            return candidate;
        }
        return "No summary available.";
    }

    /** Extract compact optional metadata for an index entry. */
    static List<String> pageMetadataForIndex(String content) {
        List<String> metadata = new ArrayList<>();
        TreeSet<String> dates = new TreeSet<>();
        Matcher m = INDEX_DATE_PATTERN.matcher(content);
        while (m.find()) dates.add(m.group());
        if (!dates.isEmpty()) metadata.add("date: " + dates.last());
        TreeSet<String> sourceRefs = new TreeSet<>();
        Matcher sr = INDEX_SOURCE_REF_PATTERN.matcher(content);
        while (sr.find()) {
            sourceRefs.add(sr.group(1).replaceAll("[.,;:)}]+$", ""));
        }
        if (!sourceRefs.isEmpty()) metadata.add("sources: " + sourceRefs.size());
        return metadata;
    }

    /** Determine an index category label for a wiki page path. */
    static String indexCategoryForPage(Path relativePath) {
        if (relativePath.getNameCount() <= 1) return "Other Pages";
        String first = relativePath.getName(0).toString().toLowerCase();
        return INDEX_DIRECTORY_CATEGORIES.getOrDefault(first, "Other Pages");
    }

    /** Build the index markdown for the current set of wiki pages. */
    public static String buildIndexText(String topic, Path wikiDir) {
        List<Path> pages = new ArrayList<>();
        try (var stream = Files.walk(wikiDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("index.md"))
                    .sorted()
                    .forEach(pages::add);
        } catch (IOException exc) {
            return emptyIndexText(topic);
        }
        if (pages.isEmpty()) return emptyIndexText(topic);
        Map<String, List<String>> sectionLines = new LinkedHashMap<>();
        for (String c : INDEX_CATEGORY_ORDER) sectionLines.put(c, new ArrayList<>());
        for (Path page : pages) {
            Path relativePath = wikiDir.relativize(page);
            String content;
            try { content = Files.readString(page); }
            catch (IOException exc) { continue; }
            String title = pageTitleForIndex(relativePath, content);
            String summary = pageSummaryForIndex(content);
            List<String> metadata = pageMetadataForIndex(content);
            String entry = "- [" + title + "](" + relativePath.toString().replace('\\', '/')
                    + ") - " + summary;
            if (!metadata.isEmpty()) {
                entry = entry + " _(" + String.join("; ", metadata) + ")_";
            }
            sectionLines.get(indexCategoryForPage(relativePath)).add(entry);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(topic).append(" Wiki\n\n");
        sb.append("Content catalog for wiki navigation and retrieval.\n");
        sb.append("Read this page first during query workflows.\n\n");
        for (String category : INDEX_CATEGORY_ORDER) {
            List<String> entries = sectionLines.get(category);
            if (entries.isEmpty()) continue;
            sb.append("## ").append(category).append("\n\n");
            for (String e : entries) sb.append(e).append("\n");
            sb.append("\n");
        }
        return sb.toString().stripTrailing() + "\n";
    }

    /** Rebuild {@code /wiki/index.md}. Mirrors the Python port's {@code refresh_index}. */
    public static void refreshIndex(String topic, Path workspaceDir, java.util.function.BiConsumer<Path, String> writeText) {
        Path wikiDir = workspaceDir.resolve("wiki");
        try { Files.createDirectories(wikiDir); }
        catch (IOException exc) { throw new RuntimeException("cannot create " + wikiDir, exc); }
        Path indexPath = wikiDir.resolve("index.md");
        String content = buildIndexText(topic, wikiDir);
        if (writeText == null) {
            try { Files.writeString(indexPath, content); }
            catch (IOException exc) { throw new RuntimeException("cannot write " + indexPath, exc); }
            return;
        }
        writeText.accept(indexPath, content);
    }
}
