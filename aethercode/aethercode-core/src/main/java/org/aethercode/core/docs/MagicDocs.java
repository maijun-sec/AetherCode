package org.aethercode.core.docs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * a tiny "magic docs" generator. Modelled on the TS
 * {@code MagicDocs/} — read Java source files, lift the Javadoc on top
 * of every {@code public class / public method / public record}, and
 * emit a single markdown document.
 *
 * <p>Not a full AST parser — regex-based. 对应历史 round is good enough for the
 * "show me the public API of this package" use case, and a 对应历史 round+ round
 * can wire in a real parser (JavaParser) if we need it.
 */
public final class MagicDocs {

    private static final Logger LOG = LoggerFactory.getLogger(MagicDocs.class);

    private static final Pattern CLASS_DECL = Pattern.compile(
            "\\b(public\\s+)?(abstract\\s+|final\\s+)?(class|interface|record|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern METHOD_DECL = Pattern.compile(
            "\\b(public|protected)\\s+([A-Za-z0-9_.<>?, \\[\\]]+)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final Pattern JAVADOC = Pattern.compile(
            "/\\*\\*(.*?)\\*/", Pattern.DOTALL);
    private static final Pattern JAVADOC_LINE = Pattern.compile("^\\s*\\*?\\s?(.*)$", Pattern.MULTILINE);

    public record DocEntry(String name, String kind, String signature, String javadoc) {}

    /** walk a single source file, return every public declaration + Javadoc. */
    public List<DocEntry> extract(String source) {
        List<DocEntry> out = new ArrayList<>();
        if (source == null || source.isEmpty()) return out;
        // 1. classes
        Matcher cm = CLASS_DECL.matcher(source);
        while (cm.find()) {
            String kind = cm.group(3);
            String name = cm.group(4);
            String sig = source.substring(cm.start(), Math.min(source.length(), findLineEnd(source, cm.end())));
            String doc = javadocBefore(source, cm.start());
            out.add(new DocEntry(name, kind, sig.trim(), doc == null ? "" : doc));
        }
        // 2. public/protected methods
        Matcher mm = METHOD_DECL.matcher(source);
        while (mm.find()) {
            String name = mm.group(3);
            String sig = source.substring(mm.start(), Math.min(source.length(), findLineEnd(source, mm.end())));
            if (sig.contains("class ") || sig.contains("interface ")) continue;
            String doc = javadocBefore(source, mm.start());
            out.add(new DocEntry(name, "method", sig.trim(), doc == null ? "" : doc));
        }
        return out;
    }

    /** render the entries as a markdown document. */
    public String render(String title, List<DocEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title == null ? "API" : title).append("\n\n");
        if (entries == null || entries.isEmpty()) {
            sb.append("_No public declarations found._\n");
            return sb.toString();
        }
        for (DocEntry e : entries) {
            sb.append("## `").append(e.name()).append("` (").append(e.kind).append(")\n\n");
            if (e.signature != null && !e.signature.isBlank()) {
                sb.append("```java\n").append(e.signature).append("\n```\n");
            }
            if (e.javadoc != null && !e.javadoc.isBlank()) {
                sb.append("\n").append(e.javadoc).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** walk a directory, returning every entry across every .java file. */
    public List<DocEntry> walkDir(Path dir) throws IOException {
        List<DocEntry> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (var stream = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) stream.filter(f -> f.toString().endsWith(".java"))::iterator) {
                try {
                    out.addAll(extract(Files.readString(p)));
                } catch (IOException e) {
                    LOG.debug("skip {}: {}", p, e.getMessage());
                }
            }
        }
        return out;
    }

    /** pull the Javadoc block immediately preceding {@code pos}, if any. */
    private static String javadocBefore(String source, int pos) {
        // Find the nearest "*/" before pos — that's the comment terminator.
        int end = source.lastIndexOf("*/", pos);
        if (end < 0 || (pos - end) > 200) return null;
        // walk back to the matching "/**"
        int start = source.lastIndexOf("/**", end);
        if (start < 0) return null;
        // make sure nothing between the comment and pos is a real statement
        for (int i = end + 2; i < pos; i++) {
            char c = source.charAt(i);
            if (!Character.isWhitespace(c) && c != ';') return null;
        }
        String raw = source.substring(start + 3, end);
        // strip leading "* " on each line
        Matcher m = JAVADOC_LINE.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(m.group(1));
        }
        return sb.toString().trim();
    }

    private static int findLineEnd(String source, int from) {
        for (int i = from; i < source.length(); i++) {
            if (source.charAt(i) == '\n') return i;
        }
        return source.length();
    }
}
