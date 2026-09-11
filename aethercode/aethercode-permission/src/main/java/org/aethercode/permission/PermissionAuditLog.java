package org.aethercode.permission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.aethercode.core.permission.PermissionMode;
import org.aethercode.core.permission.PermissionResult;

/**
 * append-only audit log of permission decisions. Every
 * {@code Allow} / {@code Deny} / {@code Ask} flows through
 * {@link #record}, which captures who-decided-what-when-why into
 * a JSONL file. Useful for the TUI's "review" pane and for
 * post-incident analysis.
 */
public class PermissionAuditLog {

    public record Entry(
            Instant timestamp,
            String toolName,
            String actor,
            Decision decision,
            String reason,
            Map<String, Object> input
    ) {
        public enum Decision { ALLOW, DENY, ASK }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path file;
    private final List<Entry> inMemory = new CopyOnWriteArrayList<>();
    private final AtomicLong allowCount = new AtomicLong();
    private final AtomicLong denyCount = new AtomicLong();
    private final AtomicLong askCount = new AtomicLong();

    public PermissionAuditLog(Path file) {
        this.file = file;
        load();
    }

    public Path file() { return file; }

    public synchronized Entry record(String toolName, PermissionResult result, String actor, Map<String, Object> input) {
        Objects.requireNonNull(result, "result");
        Entry e = new Entry(
                Instant.now(),
                toolName == null ? "?" : toolName,
                actor == null ? "system" : actor,
                toDecision(result),
                reasonOf(result),
                input == null ? Map.of() : Map.copyOf(input)
        );
        inMemory.add(e);
        if (file != null) persist(e);
        switch (e.decision()) {
            case ALLOW -> allowCount.incrementAndGet();
            case DENY  -> denyCount.incrementAndGet();
            case ASK   -> askCount.incrementAndGet();
        }
        return e;
    }

    public List<Entry> all() { return List.copyOf(inMemory); }

    public List<Entry> byTool(String toolName) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : inMemory) if (e.toolName().equals(toolName)) out.add(e);
        return out;
    }

    public List<Entry> byDecision(Entry.Decision d) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : inMemory) if (e.decision() == d) out.add(e);
        return out;
    }

    public long allowCount() { return allowCount.get(); }
    public long denyCount()  { return denyCount.get(); }
    public long askCount()   { return askCount.get(); }
    public long totalCount() { return inMemory.size(); }

    public void clear() {
        inMemory.clear();
        allowCount.set(0);
        denyCount.set(0);
        askCount.set(0);
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", totalCount());
        out.put("allow", allowCount.get());
        out.put("deny", denyCount.get());
        out.put("ask", askCount.get());
        return out;
    }

    private static Entry.Decision toDecision(PermissionResult r) {
        return switch (r) {
            case PermissionResult.Allow ignored -> Entry.Decision.ALLOW;
            case PermissionResult.Deny  ignored -> Entry.Decision.DENY;
            case PermissionResult.Ask   ignored -> Entry.Decision.ASK;
        };
    }

    private static String reasonOf(PermissionResult r) {
        return switch (r) {
            case PermissionResult.Allow a -> "allowed";
            case PermissionResult.Deny d  -> d.decisionReason() == null ? d.message() : d.decisionReason();
            case PermissionResult.Ask a   -> a.question();
        };
    }

    private void persist(Entry e) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timestamp", e.timestamp().toString());
            m.put("toolName", e.toolName());
            m.put("actor", e.actor());
            m.put("decision", e.decision().name());
            m.put("reason", e.reason());
            m.put("input", e.input());
            String json = MAPPER.writeValueAsString(m);
            Files.writeString(file, json + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            // best-effort
        }
    }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                Map<String, Object> m = MAPPER.readValue(line, new TypeReference<>() {});
                Entry e = new Entry(
                        Instant.parse((String) m.get("timestamp")),
                        (String) m.get("toolName"),
                        (String) m.get("actor"),
                        Entry.Decision.valueOf((String) m.get("decision")),
                        (String) m.get("reason"),
                        (Map<String, Object>) m.getOrDefault("input", Map.of())
                );
                inMemory.add(e);
                switch (e.decision()) {
                    case ALLOW -> allowCount.incrementAndGet();
                    case DENY  -> denyCount.incrementAndGet();
                    case ASK   -> askCount.incrementAndGet();
                }
            }
        } catch (IOException e) {
            // corrupt log — back it up
            try { Files.move(file, file.resolveSibling(file.getFileName() + ".bak")); }
            catch (IOException ignored) {}
        }
    }
}
