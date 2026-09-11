package org.aethercode.core.transcript;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * multi-session transcript registry. Each session lives in its own JSONL
 * file under {@code sessionsDir}; this class lists, loads, creates, and
 * deletes them.
 *
 * <p>Layout:
 * <pre>
 *   sessions/
 *     2024-01-15T10-00-00Z_<short>.jsonl
 *     2024-01-15T11-30-12Z_<short>.jsonl
 * </pre>
 *
 * <p>The id is encoded in the file name so {@link #list()} is a directory scan
 * and {@link #loadOrCreate(String)} is a single open. New sessions are
 * allocated by {@link #newSessionId()} which yields a timestamp + 8-char UUID.
 */
public final class SessionStore {

    private final Path dir;

    public SessionStore(Path dir) {
        this.dir = dir;
    }

    public Path dir() { return dir; }

    public static String newSessionId() {
        String ts = java.time.Instant.now().toString().replace(':', '-');
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return ts + "_" + suffix;
    }

    /** List known sessions, most-recently-modified first. */
    public List<SessionInfo> list() throws IOException {
        ensure();
        List<SessionInfo> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                  .forEach(p -> {
                      String id = stripExt(p.getFileName().toString());
                      long mtime = 0;
                      long size = 0;
                      try { mtime = Files.getLastModifiedTime(p).toMillis(); } catch (IOException ignored) {}
                      try { size = Files.size(p); } catch (IOException ignored) {}
                      out.add(new SessionInfo(id, p, mtime, size));
                  });
        }
        out.sort(Comparator.comparingLong(SessionInfo::lastModified).reversed());
        return out;
    }

    /** Load the named session, or create a new (empty) one with that id. */
    public Transcript loadOrCreate(String sessionId) throws IOException {
        ensure();
        Path file = pathFor(sessionId);
        return Transcript.loadOrEmpty(file);
    }

    /** grep across every session file for the given query.
     *  Returns at most {@code maxPerSession} matches per session, with
     *  the matching line + a tiny bit of context (the message JSON
     *  contains everything we need, so we just return the raw line
     *  for the UI to format). Sessions with no matches are omitted.
     *
     *  <p>Case-insensitive substring match on the raw JSON line (so
     *  structural noise like the role and id fields are also
     *  matched, which is usually fine). For more precision, the
     *  caller can post-filter by parsing the JSON.
     *
     *  <p>Always reads the file fully into memory — sessions are
     *  expected to be small (a few MB at most). If a session is
     *  larger than {@code maxFileBytes} we skip it (and add a
     *  synthetic "skipped: too large" entry to the result). */
    public List<SearchHit> search(String query, int maxPerSession, long maxFileBytes) throws IOException {
        ensure();
        if (query == null || query.isBlank()) return List.of();
        String lower = query.toLowerCase();
        List<SearchHit> out = new ArrayList<>();
        for (SessionInfo info : list()) {
            if (info.sizeBytes > maxFileBytes) {
                out.add(new SearchHit(info, "skipped: file too large (" + info.sizeBytes + " bytes)"));
                continue;
            }
            int matches = 0;
            try {
                List<String> lines = Files.readAllLines(info.file);
                for (int i = 0; i < lines.size() && matches < maxPerSession; i++) {
                    String line = lines.get(i);
                    if (line.toLowerCase().contains(lower)) {
                        out.add(new SearchHit(info, line.length() > 200
                                ? line.substring(0, 197) + "..."
                                : line));
                        matches++;
                    }
                }
            } catch (IOException e) {
                out.add(new SearchHit(info, "error: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return out;
    }

    /** a single search hit. The {@code snippet} is the
     *  matching line (truncated to 200 chars) or an error / skip
     *  marker. The {@code session} is the session the hit came from. */
    public record SearchHit(SessionInfo session, String snippet) {}

    public boolean delete(String sessionId) throws IOException {
        Path file = pathFor(sessionId);
        return Files.deleteIfExists(file);
    }

    public Path pathFor(String sessionId) {
        return dir.resolve(sessionId + ".jsonl");
    }

    private void ensure() throws IOException {
        if (!Files.exists(dir)) Files.createDirectories(dir);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** Lightweight handle for the TUI/REPL's /sessions command. */
    public record SessionInfo(String id, Path file, long lastModified, long sizeBytes) {
        public String shortId() { return id.length() > 16 ? id.substring(0, 16) + "…" : id; }
    }
}
