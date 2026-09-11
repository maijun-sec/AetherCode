package org.aethercode.core.format;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * extract sections from a Markdown document. A section
 * starts at a heading line and continues until the next heading of
 * equal or higher level. Returns a flat list of {@link Section}
 * records with level, title, body, and byte offset.
 */
public final class MarkdownSections {

    public record Section(int level, String title, String body, int startOffset, int endOffset) {
        public boolean isTopLevel() { return level == 1; }
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    private MarkdownSections() {}

    public static List<Section> parse(String markdown) {
        if (markdown == null || markdown.isEmpty()) return List.of();
        String[] lines = markdown.split("\\r?\\n", -1);
        List<Section> out = new ArrayList<>();
        int offset = 0;
        Integer currentStart = null;
        int currentLevel = 0;
        String currentTitle = null;
        StringBuilder currentBody = new StringBuilder();
        int currentStartOffset = 0;

        for (String line : lines) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                // Close the previous section
                if (currentStart != null) {
                    out.add(new Section(currentLevel, currentTitle, currentBody.toString().strip(),
                            currentStartOffset, offset));
                }
                currentStart = m.start();
                currentStartOffset = offset;
                currentLevel = m.group(1).length();
                currentTitle = m.group(2).trim();
                currentBody = new StringBuilder();
            } else if (currentStart != null) {
                if (currentBody.length() > 0) currentBody.append('\n');
                currentBody.append(line);
            }
            offset += line.length() + 1; // +1 for the newline
        }
        if (currentStart != null) {
            out.add(new Section(currentLevel, currentTitle, currentBody.toString().strip(),
                    currentStartOffset, offset));
        }
        return out;
    }

    /** extract a section by its title (case-insensitive). */
    public static Section findByTitle(List<Section> sections, String title) {
        if (title == null) return null;
        for (Section s : sections) {
            if (s.title().equalsIgnoreCase(title)) return s;
        }
        return null;
    }

    /** get all top-level (level 1) sections. */
    public static List<Section> topLevel(List<Section> sections) {
        List<Section> out = new ArrayList<>();
        for (Section s : sections) if (s.isTopLevel()) out.add(s);
        return out;
    }
}
