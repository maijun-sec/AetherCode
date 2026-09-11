package org.aethercode.core.transcript;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * per-session metadata — title, tags, arbitrary key/value
 * attributes, and timestamps. Persisted alongside the transcript
 * (in a sidecar {@code <sessionId>.meta.json} file) so the TUI's
 * {@code /sessions} list can show "fix login bug" rather than
 * "2024-01-15T10-00-00Z_a3f9c2".
 */
public class SessionMetadata {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final String sessionId;
    private volatile String title = "";
    private final List<String> tags = new ArrayList<>();
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile Instant createdAt = Instant.now();
    private volatile Instant updatedAt = Instant.now();
    private volatile long messageCount = 0;

    public SessionMetadata(String sessionId) {
        this.sessionId = sessionId == null ? UUID.randomUUID().toString() : sessionId;
    }

    public String sessionId() { return sessionId; }
    public String title() { return title; }
    public SessionMetadata title(String t) { this.title = t == null ? "" : t; touch(); return this; }
    public List<String> tags() { return List.copyOf(tags); }
    public Map<String, Object> attributes() { return Map.copyOf(attributes); }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long messageCount() { return messageCount; }

    public SessionMetadata addTag(String tag) {
        if (tag != null && !tag.isBlank() && !tags.contains(tag)) {
            tags.add(tag); touch();
        }
        return this;
    }

    public SessionMetadata removeTag(String tag) {
        if (tags.remove(tag)) touch();
        return this;
    }

    public SessionMetadata setAttribute(String key, Object value) {
        attributes.put(key, value); touch();
        return this;
    }

    public Object getAttribute(String key) { return attributes.get(key); }

    public SessionMetadata setMessageCount(long n) { this.messageCount = n; touch(); return this; }

    private void touch() { this.updatedAt = Instant.now(); }

    @JsonIgnore
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("title", title);
        m.put("tags", tags);
        m.put("attributes", attributes);
        m.put("createdAt", createdAt.toString());
        m.put("updatedAt", updatedAt.toString());
        m.put("messageCount", messageCount);
        return m;
    }

    public String toJson() {
        try { return MAPPER.writeValueAsString(toMap()); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    public static SessionMetadata fromJson(String json) {
        try {
            Map<String, Object> m = MAPPER.readValue(json, new TypeReference<>() {});
            SessionMetadata sm = new SessionMetadata((String) m.get("sessionId"));
            sm.title = (String) m.getOrDefault("title", "");
            Object tags = m.get("tags");
            if (tags instanceof List<?> ts) {
                for (Object t : ts) if (t != null) sm.tags.add(t.toString());
            }
            Object attrs = m.get("attributes");
            if (attrs instanceof Map<?, ?> am) {
                for (Map.Entry<?, ?> e : am.entrySet()) {
                    sm.attributes.put(e.getKey().toString(), e.getValue());
                }
            }
            String created = (String) m.get("createdAt");
            if (created != null) sm.createdAt = Instant.parse(created);
            String updated = (String) m.get("updatedAt");
            if (updated != null) sm.updatedAt = Instant.parse(updated);
            Object mc = m.get("messageCount");
            if (mc instanceof Number n) sm.messageCount = n.longValue();
            return sm;
        } catch (Exception e) {
            throw new RuntimeException("failed to parse metadata: " + e.getMessage(), e);
        }
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof SessionMetadata that)) return false;
        return Objects.equals(sessionId, that.sessionId)
                && Objects.equals(title, that.title)
                && Objects.equals(tags, that.tags)
                && messageCount == that.messageCount;
    }

    @Override public int hashCode() { return Objects.hash(sessionId, title, tags, messageCount); }
}
