package org.aethercode.code.tui.widgets;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Command history manager for input persistence.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.history.HistoryManager}.
 * The Python class manages a JSON-lines history file with append-only
 * writes for concurrent safety, query-aware navigation via up/down arrows,
 * and filtering rules (skip empty input, skip slash commands, allow
 * {@code /skill:<name>}).</p>
 *
 * <p>The Java port preserves the same persistence and navigation
 * semantics, using {@link Path} for the file (replacing
 * {@code pathlib.Path}) and {@link Deque} for the in-memory list. The
 * line-based JSON parser is hand-written to avoid depending on a JSON
 * library: each line is either a JSON-quoted string (the common case,
 * which is just the unescaped text) or a non-string value (fall back to
 * its {@code toString} form).</p>
 *
 * <p>Concurrent safety: the Python version uses append-only file writes.
 * The Java port keeps that contract: {@link #add(String)} calls
 * {@link Files#newBufferedWriter(Path, java.nio.file.StandardOpenOption...)}
 * with {@code CREATE} + {@code APPEND}. Multiple writers racing each
 * other may interleave bytes, but each entry fits on a single line and
 * the reader tolerates partially-written trailing lines by falling back
 * to the verbatim text.</p>
 */
public class HistoryManager {

    private final Path historyFile;
    private final int maxEntries;
    private final List<String> entries = new ArrayList<>();
    private int currentIndex = -1;
    private String tempInput = "";
    private String query = "";

    public HistoryManager(Path historyFile, int maxEntries) {
        this.historyFile = historyFile;
        this.maxEntries = maxEntries;
        load();
    }

    public HistoryManager(Path historyFile) {
        this(historyFile, 100);
    }

    /** All loaded entries, oldest first. The list is a defensive copy. */
    public List<String> entries() {
        return new ArrayList<>(entries);
    }

    public int maxEntries() { return maxEntries; }
    public Path historyFile() { return historyFile; }

    /**
     * Add a command to history.
     *
     * <p>Mirrors the Python {@code add} method: empty input and slash
     * commands (except the {@code /skill:} form) are skipped, duplicates
     * of the last entry are skipped, the entry is appended to the
     * in-memory list, the file is appended, and the navigation state is
     * reset.</p>
     */
    public void add(String text) {
        if (text == null) return;
        String trimmed = text.strip();
        if (trimmed.isEmpty()) return;
        if (trimmed.startsWith("/") && !trimmed.toLowerCase(Locale.ROOT).startsWith("/skill:")) {
            return;
        }
        if (!entries.isEmpty() && entries.get(entries.size() - 1).equals(trimmed)) {
            return;
        }
        entries.add(trimmed);
        appendToFile(trimmed);
        if (entries.size() > maxEntries * 2) {
            // Compact in-memory list to the most-recent maxEntries.
            List<String> kept = new ArrayList<>(
                    entries.subList(entries.size() - maxEntries, entries.size()));
            entries.clear();
            entries.addAll(kept);
            compact();
        }
        resetNavigation();
    }

    /**
     * Get the previous history entry matching a substring query.
     *
     * <p>The query is captured on the first call of a navigation session
     * (when {@code currentIndex == -1}) and reused for all subsequent
     * calls until {@link #resetNavigation()} is called.</p>
     */
    public String getPrevious(String currentInput, String query) {
        if (entries.isEmpty()) return null;
        if (currentIndex == -1) {
            tempInput = currentInput;
            currentIndex = entries.size();
            this.query = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        }
        for (int i = currentIndex - 1; i >= 0; i--) {
            if (this.query.isEmpty() || entries.get(i).toLowerCase(Locale.ROOT).contains(this.query)) {
                currentIndex = i;
                return entries.get(i);
            }
        }
        return null;
    }

    /** Get the next history entry matching the stored query. */
    public String getNext() {
        if (currentIndex == -1) return null;
        for (int i = currentIndex + 1; i < entries.size(); i++) {
            if (query.isEmpty() || entries.get(i).toLowerCase(Locale.ROOT).contains(query)) {
                currentIndex = i;
                return entries.get(i);
            }
        }
        // Return to the original input at the end.
        String result = tempInput;
        resetNavigation();
        return result;
    }

    /** Whether the user is currently navigating history entries. */
    public boolean inHistory() {
        return currentIndex >= 0;
    }

    /** Reset navigation state. Called after {@link #add(String)} and at the end of a session. */
    public void resetNavigation() {
        currentIndex = -1;
        tempInput = "";
        query = "";
    }

    // -- File I/O ------------------------------------------------------------

    private void load() {
        if (!Files.exists(historyFile)) return;
        try {
            List<String> raw = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
            for (String line : raw) {
                if (line.isEmpty()) continue;
                String parsed = parseLine(line);
                if (parsed != null) entries.add(parsed);
            }
            if (entries.size() > maxEntries) {
                entries.subList(0, entries.size() - maxEntries).clear();
            }
        } catch (IOException | RuntimeException e) {
            // Match Python: warn and start with an empty history.
            System.err.println("Warning: failed to load history from " + historyFile
                    + ": " + e.getMessage());
            entries.clear();
        }
    }

    private void appendToFile(String text) {
        try {
            Files.createDirectories(historyFile.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(historyFile,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND)) {
                w.write(encodeLine(text));
                w.newLine();
            }
        } catch (IOException e) {
            System.err.println("Warning: failed to append history entry: " + e.getMessage());
        }
    }

    private void compact() {
        try {
            Files.createDirectories(historyFile.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(historyFile,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                for (String e : entries) {
                    w.write(encodeLine(e));
                    w.newLine();
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: failed to compact history file: " + e.getMessage());
        }
    }

    /** Encode a single text as a JSON-line. */
    private static String encodeLine(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 2);
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Parse a single history line.
     *
     * <p>Accepts a JSON string literal (the common case) or a raw string
     * (legacy fall-back, matches the Python behavior). Returns
     * {@code null} when the line is unparseable.</p>
     */
    private static String parseLine(String line) {
        if (line.isEmpty()) return null;
        if (line.charAt(0) == '"') {
            // Manual unescape: same set as encodeLine above.
            StringBuilder sb = new StringBuilder(line.length());
            for (int i = 1; i < line.length() - 1; i++) {
                char c = line.charAt(i);
                if (c == '\\' && i + 1 < line.length() - 1) {
                    char next = line.charAt(++i);
                    switch (next) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 < line.length() - 1) {
                                String hex = line.substring(i + 1, i + 5);
                                try {
                                    sb.append((char) Integer.parseInt(hex, 16));
                                    i += 4;
                                } catch (NumberFormatException nfe) {
                                    sb.append(c);
                                }
                            }
                        }
                        default -> sb.append(next);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        // Legacy: bare text.
        return line;
    }
}
