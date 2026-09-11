package org.aethercode.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * cross-session shared memory. Modelled on the TS
 * {@code services/teamMemorySync/}. Multiple AetherCode sessions (across
 * projects, across machines on a shared filesystem) can publish notes into
 * one directory, and other sessions can read the union on recall.
 *
 * <p>Wire format: one {@code .md} file per note, named with a timestamp +
 * short id. Front-matter (the first 4 lines) carries structured metadata:
 *
 * <pre>
 *   ---
 *   id: 2026-08-04T07-12-00_xxxxxxxx
 *   author: alice@laptop
 *   session: engine-session-id
 *   created_at: 2026-08-04T07:12:00Z
 *   ---
 *   # Note body here
 * </pre>
 *
 * <p>{@link #put(String, String)} is append-only and idempotent at the
 * {@link #list()} level — it never overwrites a teammate's note. {@link #list()}
 * unions the local agent's memory directory and the team directory, deduped by
 * note id.
 */
public class TeamMemorySync {

    private static final Logger LOG = LoggerFactory.getLogger(TeamMemorySync.class);

    private final Path teamDir;
    private final Path localDir;
    private final String author;

    public TeamMemorySync(Path teamDir, Path localDir, String author) {
        this.teamDir = teamDir;
        this.localDir = localDir;
        this.author = author == null ? "anonymous" : author;
    }

    /** write a note to the team directory. Returns the generated id. */
    public synchronized String put(String title, String body) throws IOException {
        Files.createDirectories(teamDir);
        String id = Instant.now().toString().replace(':', '-') + "_" + UUID.randomUUID().toString().substring(0, 8);
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("id: ").append(id).append('\n');
        sb.append("author: ").append(author).append('\n');
        sb.append("created_at: ").append(Instant.now()).append('\n');
        sb.append("---\n\n");
        sb.append("# ").append(title == null ? "untitled" : title).append("\n\n");
        sb.append(body == null ? "" : body);
        Files.writeString(teamDir.resolve(id + ".md"), sb.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        LOG.info("team-memory: put {} ({} bytes)", id, sb.length());
        return id;
    }

    /** union of team + local notes, deduped by id, newest first. */
    public List<Note> list() {
        List<Note> out = new ArrayList<>();
        out.addAll(scanDir(teamDir, Source.TEAM));
        out.addAll(scanDir(localDir, Source.LOCAL));
        // dedup by id, prefer TEAM
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Note> deduped = new ArrayList<>();
        for (Note n : out) {
            if (seen.add(n.id())) deduped.add(n);
        }
        deduped.sort(Comparator.comparing(Note::createdAt).reversed());
        return deduped;
    }

    private List<Note> scanDir(Path dir, Source src) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<Note> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream.filter(f -> f.toString().endsWith(".md"))::iterator) {
                if (p.getFileName().toString().equals(MemoryPaths.ENTRYPOINT_NAME)) continue;
                try {
                    Note n = parse(p, src);
                    if (n != null) out.add(n);
                } catch (IOException e) {
                    LOG.debug("skip malformed team-memory file {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.debug("scan {} failed: {}", dir, e.getMessage());
        }
        return out;
    }

    private static Note parse(Path p, Source src) throws IOException {
        String body = Files.readString(p);
        String[] lines = body.split("\n", 5);
        if (lines.length < 4 || !"---".equals(lines[0].trim())) return null;
        String id = null;
        String author = null;
        String createdAt = null;
        for (int i = 1; i < 4; i++) {
            String l = lines[i].trim();
            if (l.startsWith("id:")) id = l.substring(3).trim();
            else if (l.startsWith("author:")) author = l.substring(7).trim();
            else if (l.startsWith("created_at:")) createdAt = l.substring(11).trim();
        }
        if (id == null) id = p.getFileName().toString().replace(".md", "");
        if (createdAt == null) {
            try { createdAt = Files.getLastModifiedTime(p).toInstant().toString(); }
            catch (IOException e) { createdAt = Instant.EPOCH.toString(); }
        }
        return new Note(id, author, createdAt, p, src);
    }

    public enum Source { TEAM, LOCAL }

    public record Note(String id, String author, String createdAt, Path file, Source source) {}

    /** convenience — pull a team note's body text on demand. */
    public static String readBody(Note n) {
        try { return Files.readString(n.file()); }
        catch (IOException e) { return ""; }
    }
}
