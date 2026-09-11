package org.aethercode.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * memory snapshot. Modelled on the TS
 * {@code tools/AgentTool/agentMemorySnapshot.ts}.
 *
 * <p>A snapshot is a compact, signed manifest of the agent-memory directory:
 * <pre>
 *   {
 *     "agent_type": "code-reviewer",
 *     "scope": "user",
 *     "created_at": "2026-08-04T12:00:00Z",
 *     "files": {
 *       "MEMORY.md": { "size": 1234, "sha256": "ab12...", "mtime": "..." },
 *       "conventions.md": { "size": 456, "sha256": "cd34...", "mtime": "..." }
 *     }
 *   }
 * </pre>
 *
 * <p>Snapshots are how the {@code CLAUDE_COWORK_MEMORY_EXTRA_GUIDELINES} hook decides
 * to nudge the user that "a new snapshot is available". prior round ships the read / write
 * side; the sync transport is prior round.
 */
public class MemorySnapshot {

    private static final Logger LOG = LoggerFactory.getLogger(MemorySnapshot.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public final String agentType;
    public final MemoryScope scope;
    public final Instant createdAt;
    public final Map<String, FileEntry> files;

    public MemorySnapshot(String agentType, MemoryScope scope, Instant createdAt, Map<String, FileEntry> files) {
        this.agentType = agentType;
        this.scope = scope;
        this.createdAt = createdAt;
        this.files = files;
    }

    public record FileEntry(long size, String sha256, Instant mtime) {}

    /** Build a snapshot by scanning the directory. */
    public static MemorySnapshot build(String agentType, MemoryScope scope, Path memoryDir) {
        Map<String, FileEntry> files = new java.util.LinkedHashMap<>();
        if (Files.isDirectory(memoryDir)) {
            try (var stream = Files.list(memoryDir)) {
                for (Path p : (Iterable<Path>) stream.filter(f -> f.toString().endsWith(".md"))::iterator) {
                    String name = p.getFileName().toString();
                    long size = Files.size(p);
                    String sha = sha256(p);
                    Instant mtime = Files.getLastModifiedTime(p).toInstant();
                    files.put(name, new FileEntry(size, sha, mtime));
                }
            } catch (IOException e) {
                LOG.warn("snapshot scan failed: {}", e.getMessage());
            }
        }
        return new MemorySnapshot(agentType, scope, Instant.now(), files);
    }

    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("agent_type", agentType);
        root.put("scope", scope.name().toLowerCase());
        root.put("created_at", createdAt.toString());
        ObjectNode filesNode = root.putObject("files");
        files.forEach((name, entry) -> {
            ObjectNode n = filesNode.putObject(name);
            n.put("size", entry.size);
            n.put("sha256", entry.sha256);
            n.put("mtime", entry.mtime.toString());
        });
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root); }
        catch (Exception e) { return "{}"; }
    }

    public void writeTo(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, toJson(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static MemorySnapshot readFrom(Path file) throws IOException {
        ObjectNode root = (ObjectNode) MAPPER.readTree(Files.readString(file));
        String at = root.path("agent_type").asText();
        MemoryScope scope = MemoryScope.valueOf(root.path("scope").asText("user").toUpperCase());
        Instant created = Instant.parse(root.path("created_at").asText());
        Map<String, FileEntry> map = new java.util.HashMap<>();
        var filesNode = root.path("files");
        filesNode.fields().forEachRemaining(e -> {
            var n = e.getValue();
            map.put(e.getKey(), new FileEntry(
                    n.path("size").asLong(),
                    n.path("sha256").asText(),
                    Instant.parse(n.path("mtime").asText())));
        });
        return new MemorySnapshot(at, scope, created, map);
    }

    /** True if this snapshot is newer than {@code other} and has any file diff. */
    public boolean isNewerAndDifferent(MemorySnapshot other) {
        if (!this.createdAt.equals(other.createdAt)) return this.createdAt.isAfter(other.createdAt);
        return !this.files.equals(other.files);
    }

    private static String sha256(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return "";
        }
    }
}
