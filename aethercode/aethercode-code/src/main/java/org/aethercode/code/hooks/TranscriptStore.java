package org.aethercode.code.hooks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Client-owned conversation transcript projections for Hooks v2.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.transcript} module. The store
 * materializes versioned per-thread and per-subagent JSONL files
 * that hook commands can read via {@code transcript_path} /
 * {@code agent_transcript_path}.</p>
 */
public final class TranscriptStore {

    private static final Logger LOG = LoggerFactory.getLogger(TranscriptStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    /** Schema version embedded in every record. */
    public static final int TRANSCRIPT_SCHEMA_VERSION = 1;

    /** Maximum prior {@code .bak-*} revisions retained per transcript. */
    public static final int DEFAULT_RETENTION_REVISIONS = 20;

    /** Subagent transcript id metadata key carried in stream metadata. */
    public static final String SUBAGENT_TRANSCRIPT_ID_METADATA_KEY = "dcode_subagent_id";

    private static final Set<String> INTERNAL_STREAM_SOURCES = Set.of(
            "summarization", "auto_mode_classifier");
    private static final Pattern SAFE_PREFIX_RE = Pattern.compile("[^a-z0-9]+");
    private static final int SAFE_PREFIX_LENGTH = 32;
    private static final String EMPTY_REVISION = sha256Hex(new byte[0]);

    private final Path root;
    private final int retentionRevisions;
    private final Map<List<String>, TranscriptBuffer> buffers = new ConcurrentHashMap<>();

    public TranscriptStore(Path root) {
        this(root, DEFAULT_RETENTION_REVISIONS);
    }

    public TranscriptStore(Path root, int retentionRevisions) {
        if (retentionRevisions < 0) {
            throw new IllegalArgumentException("retention_revisions must be nonnegative");
        }
        this.root = root == null
                ? Path.of(System.getProperty("user.home", "~"), ".deepagents", "transcripts")
                : root.toAbsolutePath().normalize();
        this.retentionRevisions = retentionRevisions;
        ensurePrivateDirectories(this.root, this.root);
    }

    /** Return the materialized path for a thread transcript. */
    public Path threadPath(String threadId) {
        return root.resolve(safeComponent(threadId) + ".jsonl");
    }

    /** Return the materialized path for a subagent transcript. */
    public Path agentPath(String threadId, String agentId) {
        return root.resolve(safeComponent(threadId))
                .resolve("agents")
                .resolve(safeComponent(agentId) + ".jsonl");
    }

    /**
     * Append redacted message projections to the in-memory buffer.
     */
    public void appendMessages(String threadId, List<Message> messages) {
        appendMessages(threadId, messages, null);
    }

    public void appendMessages(String threadId, List<Message> messages, String agentId) {
        if (messages == null) return;
        TranscriptBuffer buffer = bufferFor(threadId, agentId);
        for (Message message : messages) {
            TranscriptRecord record = recordFromMessage(message, threadId, agentId, buffer.records.size());
            if (record == null) continue;
            if (record.messageId() != null && buffer.recordIds.contains(record.recordId())) {
                continue;
            }
            buffer.records.add(record);
            buffer.recordIds.add(record.recordId());
            buffer.dirty = true;
        }
    }

    /**
     * Flush pending records and return the client-readable path.
     */
    public TranscriptHandle materialize(String threadId) {
        return materialize(threadId, null);
    }

    public TranscriptHandle materialize(String threadId, String agentId) {
        TranscriptBuffer buffer = bufferFor(threadId, agentId);
        Path path = agentId == null ? threadPath(threadId) : agentPath(threadId, agentId);
        ensurePrivateDirectories(root, path.getParent());
        if (Files.isRegularFile(path)) {
            try { Files.setPosixFilePermissions(path,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (Exception ignored) { /* best effort */ }
        }
        try {
            mergeDiskRecords(path, buffer);
            if (buffer.dirty || !Files.exists(path)) {
                String revision = writeTranscript(root, path, buffer.records, retentionRevisions);
                buffer.revision = revision;
                buffer.dirty = false;
            }
        } catch (IOException ex) {
            LOG.warn("Failed to materialize transcript at {}", path, ex);
        }
        return new TranscriptHandle(path, buffer.revision, threadId, agentId);
    }

    /** Return the current revision id without forcing a flush. */
    public String revision(String threadId) {
        return revision(threadId, null);
    }

    public String revision(String threadId, String agentId) {
        TranscriptBuffer buffer = bufferFor(threadId, agentId);
        if (buffer.dirty) return revisionForRecords(buffer.records);
        return buffer.revision;
    }

    private TranscriptBuffer bufferFor(String threadId, String agentId) {
        List<String> key = List.of(threadId, agentId == null ? "" : agentId);
        return buffers.computeIfAbsent(key, k -> loadBuffer(threadId, agentId));
    }

    private TranscriptBuffer loadBuffer(String threadId, String agentId) {
        TranscriptBuffer buffer = new TranscriptBuffer();
        Path path = agentId == null ? threadPath(threadId) : agentPath(threadId, agentId);
        if (Files.isRegularFile(path)) {
            ReadResult read = readTranscript(path);
            buffer.records.addAll(read.records);
            buffer.recordIds.addAll(read.records.stream()
                    .map(TranscriptRecord::recordId).collect(Collectors.toSet()));
            buffer.revision = revisionForRecords(buffer.records);
            buffer.dirty = !read.valid;
        }
        return buffer;
    }

    private void mergeDiskRecords(Path path, TranscriptBuffer buffer) {
        if (!Files.isRegularFile(path)) return;
        ReadResult disk = readTranscript(path);
        if (!disk.valid) return;
        Set<String> bufferIds = buffer.recordIds;
        if (disk.records.stream().allMatch(r -> bufferIds.contains(r.recordId()))) {
            return;
        }
        List<TranscriptRecord> merged = new ArrayList<>(buffer.records);
        Set<String> mergedIds = new java.util.HashSet<>(bufferIds);
        for (TranscriptRecord record : disk.records) {
            if (mergedIds.contains(record.recordId())) continue;
            merged.add(record.copyWithSequence(merged.size()));
            mergedIds.add(record.recordId());
        }
        buffer.records.clear();
        buffer.records.addAll(merged);
        buffer.recordIds.clear();
        buffer.recordIds.addAll(mergedIds);
        buffer.dirty = true;
    }

    private String writeTranscript(Path root, Path path, List<TranscriptRecord> records,
                                   int retentionRevisions) throws IOException {
        ensurePrivateDirectories(root, path.getParent());
        StringBuilder payload = new StringBuilder();
        for (TranscriptRecord record : records) {
            payload.append(record.toJson()).append('\n');
        }
        String revision = sha256Hex(payload.toString().getBytes(StandardCharsets.UTF_8));
        Path tmp = Files.createTempFile(path.getParent(),
                path.getFileName().toString() + ".", ".tmp");
        try {
            Files.writeString(tmp, payload.toString(), StandardCharsets.UTF_8);
            if (Files.exists(path)) {
                byte[] prior = Files.readAllBytes(path);
                String priorRevision = sha256Hex(prior);
                Path backup = path.resolveSibling(
                        path.getFileName().toString() + ".bak-" + priorRevision);
                writeBackup(backup, prior);
                pruneBackups(path, retentionRevisions);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            try { Files.setPosixFilePermissions(path,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (Exception ignored) { /* best effort */ }
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort */ }
        }
        return revision;
    }

    private void writeBackup(Path path, byte[] payload) {
        try {
            Path tmp = Files.createTempFile(path.getParent(),
                    path.getFileName().toString() + ".", ".bak.tmp");
            try {
                Files.write(tmp, payload);
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                try { Files.setPosixFilePermissions(path,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
                catch (Exception ignored) { /* best effort */ }
            } finally {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort */ }
            }
        } catch (IOException ex) {
            LOG.warn("Failed to write transcript backup {}", path, ex);
        }
    }

    private void pruneBackups(Path path, int retentionRevisions) {
        String prefix = path.getFileName().toString() + ".bak-";
        try {
            List<Path> backups = new ArrayList<>();
            try (var stream = Files.list(path.getParent())) {
                for (Path candidate : (Iterable<Path>) stream::iterator) {
                    if (candidate.getFileName().toString().startsWith(prefix)) {
                        backups.add(candidate);
                    }
                }
            }
            backups.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                } catch (IOException ex) {
                    return a.getFileName().compareTo(b.getFileName());
                }
            });
            int excess = backups.size() - retentionRevisions;
            for (int i = 0; i < Math.max(0, excess); i++) {
                try { Files.deleteIfExists(backups.get(i)); }
                catch (IOException ignored) { /* best effort */ }
            }
        } catch (IOException ex) {
            LOG.debug("Failed to prune transcript backups for {}", path, ex);
        }
    }

    private ReadResult readTranscript(Path path) {
        List<TranscriptRecord> records = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) continue;
                records.add(TranscriptRecord.fromJson(line));
            }
        } catch (Exception ex) {
            LOG.warn("Could not read transcript at {}", path, ex);
            return new ReadResult(List.of(), false);
        }
        return new ReadResult(records, true);
    }

    private static String revisionForRecords(List<TranscriptRecord> records) {
        StringBuilder payload = new StringBuilder();
        for (TranscriptRecord record : records) {
            payload.append(record.toJson()).append('\n');
        }
        return sha256Hex(payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String safeComponent(String identifier) {
        if (identifier == null) identifier = "";
        String normalized = java.text.Normalizer.normalize(identifier, java.text.Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        String cleaned = SAFE_PREFIX_RE.matcher(normalized.toLowerCase(Locale.ROOT))
                .replaceAll("-")
                .replaceAll("(^-+)|(-+$)", "");
        String prefix = cleaned.substring(0, Math.min(SAFE_PREFIX_LENGTH, cleaned.length()));
        String digest = sha256Hex(identifier.getBytes(StandardCharsets.UTF_8));
        return (prefix.isEmpty() ? "id" : prefix) + "--" + digest;
    }

    private static void ensurePrivateDirectories(Path root, Path target) {
        try {
            Files.createDirectories(target);
            try { Files.setPosixFilePermissions(target,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
            catch (Exception ignored) { /* best effort */ }
        } catch (IOException ex) {
            LOG.debug("Could not ensure private directories for {}", target, ex);
        }
    }

    private static TranscriptRecord recordFromMessage(Message message, String threadId,
                                                     String agentId, int sequence) {
        String role = message.role();
        if (role == null) return null;
        Object content = redactTranscriptValue(message.content());
        String recordId = message.id() != null ? message.id() : role + ":" + sequence;
        return new TranscriptRecord(sequence, recordId, message.timestamp(),
                threadId, agentId, role, message.id(), content, message.name());
    }

    /** Redact secret-like strings inside transcript content. */
    public static Object redactTranscriptValue(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return redactText(s);
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(redactTranscriptValue(item));
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (Env.isSecretEnv(key)) {
                    out.put(key, "[redacted]");
                } else {
                    out.put(key, redactTranscriptValue(entry.getValue()));
                }
            }
            return out;
        }
        return value;
    }

    private static final Pattern SECRET_ASSIGNMENT_RE = Pattern.compile(
            "(?i)\\b([A-Z][A-Z0-9_]*(?:API[_-]?KEY|KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL)[A-Z0-9_]*)\\s*=\\s*([^\\s,;]+)");
    private static final Pattern BEARER_RE = Pattern.compile(
            "(?i)\\b(Bearer)\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern PREFIXED_TOKEN_RE = Pattern.compile(
            "(?<![A-Za-z0-9])(?:sk-(?:ant-)?|sk_(?:live|test)_|pk_(?:live|test)_|gh[pousr]_|github_pat_|glpat-|xox[baprs]-|hf_|npm_|AIza|AKIA)[A-Za-z0-9._-]{8,}");
    private static final Pattern JWT_RE = Pattern.compile(
            "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}");
    private static final Pattern URL_RE = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);

    private static String redactText(String text) {
        if (text == null) return null;
        String redacted = SECRET_ASSIGNMENT_RE.matcher(text).replaceAll("$1=[redacted]");
        Matcher m;
        m = BEARER_RE.matcher(redacted);
        redacted = m.replaceAll("$1 [redacted]");
        m = PREFIXED_TOKEN_RE.matcher(redacted);
        redacted = m.replaceAll("[redacted]");
        m = JWT_RE.matcher(redacted);
        redacted = m.replaceAll("[redacted]");
        m = URL_RE.matcher(redacted);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(redactUrl(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String redactUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            String scheme = uri.getScheme();
            String host = uri.getHost() == null ? "" : uri.getHost();
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
            int port = uri.getPort();
            String netloc = port == -1 ? host : host + ":" + port;
            String path = uri.getPath() == null || uri.getPath().isEmpty() ? "" : "/[redacted]";
            String query = "";
            if (uri.getRawQuery() != null) {
                StringBuilder qb = new StringBuilder();
                boolean first = true;
                for (String pair : uri.getRawQuery().split("&")) {
                    int eq = pair.indexOf('=');
                    String key = eq == -1 ? pair : pair.substring(0, eq);
                    if (!first) qb.append('&');
                    qb.append(key).append("=[redacted]");
                    first = false;
                }
                query = qb.toString();
            }
            String fragment = uri.getRawFragment() == null || uri.getRawFragment().isEmpty()
                    ? "" : "[redacted]";
            return new java.net.URI(scheme, netloc, path, query, fragment).toString();
        } catch (Exception ex) {
            return "[redacted URL]";
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Identity of a materialized transcript file. */
    public record TranscriptHandle(Path path, String revision, String threadId, String agentId) {}

    /** One JSONL record. */
    public record TranscriptRecord(
            int sequence,
            String recordId,
            String timestamp,
            String threadId,
            String agentId,
            String role,
            String messageId,
            Object content,
            String name) {

        public TranscriptRecord copyWithSequence(int newSequence) {
            return new TranscriptRecord(newSequence, recordId, timestamp, threadId, agentId, role,
                    messageId, content, name);
        }

        public String toJson() {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("schemaVersion", TRANSCRIPT_SCHEMA_VERSION);
                body.put("sequence", sequence);
                body.put("recordId", recordId);
                if (timestamp != null) body.put("timestamp", timestamp);
                body.put("threadId", threadId);
                if (agentId != null) body.put("agentId", agentId);
                body.put("role", role);
                if (messageId != null) body.put("messageId", messageId);
                body.put("content", content);
                if (name != null) body.put("name", name);
                return MAPPER.writeValueAsString(body);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to serialize transcript record", ex);
            }
        }

        public static TranscriptRecord fromJson(String line) {
            try {
                Map<String, Object> body = MAPPER.readValue(line, JSON_OBJECT);
                Integer sequence = ((Number) body.getOrDefault("sequence", 0)).intValue();
                String recordId = (String) body.get("recordId");
                Object timestampRaw = body.get("timestamp");
                String timestamp = timestampRaw == null ? null : timestampRaw.toString();
                String threadId = (String) body.get("threadId");
                String agentId = (String) body.get("agentId");
                String role = (String) body.get("role");
                String messageId = (String) body.get("messageId");
                Object content = body.get("content");
                String name = (String) body.get("name");
                return new TranscriptRecord(sequence, recordId, timestamp, threadId, agentId, role,
                        messageId, content, name);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to parse transcript record", ex);
            }
        }
    }

    /** In-memory buffer for one (thread, agent) pair. */
    public static final class TranscriptBuffer {
        public final List<TranscriptRecord> records = new ArrayList<>();
        public final Set<String> recordIds = ConcurrentHashMap.newKeySet();
        public boolean dirty;
        public String revision = EMPTY_REVISION;
    }

    /** Outcome of {@link #readTranscript(Path)}. */
    private record ReadResult(List<TranscriptRecord> records, boolean valid) {}

    /** Lightweight message description accepted by the store. */
    public record Message(
            String id, String role, Object content, String name, String timestamp) {

        public Message {
            if (role == null) role = "";
            if (name != null && name.isEmpty()) name = null;
            if (id != null && id.isEmpty()) id = null;
        }
    }
}
