package org.aethercode.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * topic dedup. When the agent wants to save a new memory, prior round first checks whether
 * an existing topic file already covers the same content. If yes, the existing file is
 * updated in place instead of creating a new one.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Tokenise the new memory's title + first paragraph</li>
 *   <li>For each existing file in the memory dir, compute a Jaccard overlap with the
 *       new memory's first paragraph</li>
 *   <li>If the best overlap exceeds {@link #JACCARD_THRESHOLD}, update that file</li>
 *   <li>Otherwise, create a new file with a slugified name</li>
 * </ol>
 *
 * <p>The matching is intentionally cheap (token set + Jaccard) — embeddings are out of
 * scope for prior round (prior round+).
 */
public class MemoryDeduplicator {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryDeduplicator.class);
    public static final double JACCARD_THRESHOLD = 0.6;
    public static final int TOP_MATCHES = 3;

    private final Path memoryDir;

    public MemoryDeduplicator(Path memoryDir) { this.memoryDir = memoryDir; }

    /** Suggest a target file: an existing one with high overlap, or a slugged new name. */
    public Path chooseTarget(String proposedTitle, String body) {
        String newTokens = tokenise(proposedTitle + " " + firstParagraph(body));
        if (newTokens.isEmpty()) return memoryDir.resolve(slugify(proposedTitle) + ".md");
        try {
            if (!Files.isDirectory(memoryDir)) return memoryDir.resolve(slugify(proposedTitle) + ".md");
            List<Match> matches = new ArrayList<>();
            try (var stream = Files.list(memoryDir)) {
                for (Path p : (Iterable<Path>) stream.filter(f -> f.toString().endsWith(".md"))::iterator) {
                    if (p.getFileName().toString().equals(MemoryPaths.ENTRYPOINT_NAME)) continue;
                    String existing = Files.readString(p);
                    String existingTokens = tokenise(firstParagraph(existing));
                    double j = jaccard(newTokens, existingTokens);
                    if (j >= JACCARD_THRESHOLD) matches.add(new Match(p, j));
                }
            }
            if (matches.isEmpty()) {
                return memoryDir.resolve(slugify(proposedTitle) + ".md");
            }
            matches.sort(Comparator.comparingDouble((Match m) -> m.score).reversed());
            LOG.info("dedup: '{}' matches existing '{}' (jaccard={})",
                    proposedTitle, matches.get(0).path.getFileName(), String.format("%.2f", matches.get(0).score));
            return matches.get(0).path;
        } catch (IOException e) {
            LOG.warn("dedup scan failed: {}", e.getMessage());
            return memoryDir.resolve(slugify(proposedTitle) + ".md");
        }
    }

    public List<Match> topMatches(String body, int n) {
        String tokens = tokenise(firstParagraph(body));
        if (tokens.isEmpty() || !Files.isDirectory(memoryDir)) return List.of();
        List<Match> out = new ArrayList<>();
        try (var stream = Files.list(memoryDir)) {
            for (Path p : (Iterable<Path>) stream.filter(f -> f.toString().endsWith(".md"))::iterator) {
                if (p.getFileName().toString().equals(MemoryPaths.ENTRYPOINT_NAME)) continue;
                String existing = Files.readString(p);
                double j = jaccard(tokens, tokenise(firstParagraph(existing)));
                if (j > 0) out.add(new Match(p, j));
            }
        } catch (IOException e) {
            return List.of();
        }
        out.sort(Comparator.comparingDouble((Match m) -> m.score).reversed());
        return out.subList(0, Math.min(n, out.size()));
    }

    public record Match(Path path, double score) {}

    /** package-private accessor used by {@link MemoryConsolidator}. */
    public static String firstParagraphPublic(String s) { return firstParagraph(s); }

    private static String firstParagraph(String s) {
        if (s == null) return "";
        int idx = s.indexOf("\n\n");
        return idx < 0 ? s : s.substring(0, idx);
    }

    private static String tokenise(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String t : s.toLowerCase().split("[^a-z0-9]+")) {
            if (t.length() < 3) continue;
            sb.append(t).append(' ');
        }
        return sb.toString().trim();
    }

    private static double jaccard(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        java.util.Set<String> setA = new java.util.HashSet<>(java.util.Arrays.asList(a.split(" ")));
        java.util.Set<String> setB = new java.util.HashSet<>(java.util.Arrays.asList(b.split(" ")));
        java.util.Set<String> inter = new java.util.HashSet<>(setA);
        inter.retainAll(setB);
        java.util.Set<String> union = new java.util.HashSet<>(setA);
        union.addAll(setB);
        if (union.isEmpty()) return 0;
        return (double) inter.size() / union.size();
    }

    public static String slugify(String s) {
        if (s == null) return "untitled";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
            else if (c == ' ' || c == '-' || c == '_') sb.append('-');
        }
        return sb.length() == 0 ? "untitled" : sb.toString();
    }
}
