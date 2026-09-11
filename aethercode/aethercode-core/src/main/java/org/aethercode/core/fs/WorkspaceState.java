package org.aethercode.core.fs;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * a tiny key-value "workspace state" file. Stores arbitrary
 * per-workspace data (last opened file, scroll position, recent
 * commands, etc.) as a single JSON document under
 * {@code <cwd>/.aethercode/state.json}.
 */
public class WorkspaceState {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Snapshot(
            String workspaceId,
            Instant lastSavedAt,
            Map<String, Object> data
    ) {}

    private final Path file;
    private final Map<String, Object> data = new ConcurrentHashMap<>();
    private final AtomicReference<String> workspaceId = new AtomicReference<>();
    private final AtomicReference<Instant> lastSavedAt = new AtomicReference<>();

    public WorkspaceState(Path file) {
        this.file = file;
        load();
    }

    public Path file() { return file; }

    public synchronized void put(String key, Object value) {
        Objects.requireNonNull(key, "key");
        data.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        Object v = data.get(key);
        if (v == null) return Optional.empty();
        if (!type.isInstance(v)) return Optional.empty();
        return Optional.of((T) v);
    }

    public Optional<Object> get(String key) {
        return Optional.ofNullable(data.get(key));
    }

    public synchronized void remove(String key) {
        data.remove(key);
    }

    public synchronized void clear() {
        data.clear();
        workspaceId.set(null);
        lastSavedAt.set(null);
    }

    public int size() { return data.size(); }

    public synchronized Snapshot save() {
        Snapshot s = new Snapshot(workspaceId.get(), Instant.now(),
                new LinkedHashMap<>(data));
        lastSavedAt.set(s.lastSavedAt());
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(s),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("failed to save workspace state: " + e.getMessage(), e);
        }
        return s;
    }

    public synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file);
            if (json.isBlank()) return;
            Snapshot s = MAPPER.readValue(json, new TypeReference<>() {});
            if (s == null) return;
            workspaceId.set(s.workspaceId());
            lastSavedAt.set(s.lastSavedAt());
            if (s.data() != null) {
                data.clear();
                data.putAll(s.data());
            }
        } catch (IOException e) {
            // corrupt file — back it up
            try { Files.move(file, file.resolveSibling(file.getFileName() + ".bak")); }
            catch (IOException ignored) {}
        }
    }

    public String workspaceId() { return workspaceId.get(); }
    public void setWorkspaceId(String id) { workspaceId.set(id); }
    public Instant lastSavedAt() { return lastSavedAt.get(); }
}
