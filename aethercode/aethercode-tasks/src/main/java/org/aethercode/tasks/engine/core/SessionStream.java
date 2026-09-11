package org.aethercode.tasks.engine.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-session message stream used by the supervisor to append
 * post-turn text (e.g. the auto-injected summary footer). The
 * stream is in-memory for the deepagents-tasks port; the full
 * persistence story is the supervisor's {@code child_events}
 * table (see design.md §3.4).
 *
 * <p>Each entry is a {@code Map<String,Object>} carrying
 * {@code role} + {@code content} (matching the JSON-RPC wire
 * format). Subscribers receive append notifications so the
 * desktop / TUI can stream the post-turn text live.
 */
public final class SessionStream {

    private static final Logger LOG = LoggerFactory.getLogger(SessionStream.class);

    private final Map<String, CopyOnWriteArrayList<Map<String, Object>>> streams = new ConcurrentHashMap<>();
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    /**
     * Append an assistant text chunk to {@code sessionId}. The
     * chunk is split on newlines and each line is emitted as a
     * separate event so the desktop's word-wrap is reliable.
     */
    public void appendAssistantText(String sessionId, String text) {
        if (sessionId == null || text == null) return;
        CopyOnWriteArrayList<Map<String, Object>> list = streams.computeIfAbsent(
                sessionId, k -> new CopyOnWriteArrayList<>());
        Map<String, Object> entry = Map.of(
                "role", "assistant",
                "content", text,
                "tsMs", System.currentTimeMillis());
        list.add(entry);
        for (Subscriber s : subscribers) s.onAppend(sessionId, entry);
        LOG.debug("stream append: session={} len={}", sessionId, text.length());
    }

    /**
     * Append an arbitrary message (used by tests to seed
     * recent-message context for the summary hook).
     */
    public void appendRaw(String sessionId, Map<String, Object> entry) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(entry, "entry");
        CopyOnWriteArrayList<Map<String, Object>> list = streams.computeIfAbsent(
                sessionId, k -> new CopyOnWriteArrayList<>());
        list.add(Map.copyOf(entry));
        for (Subscriber s : subscribers) s.onAppend(sessionId, entry);
    }

    /** All entries for {@code sessionId}, oldest first. */
    public List<Map<String, Object>> messages(String sessionId) {
        CopyOnWriteArrayList<Map<String, Object>> list = streams.get(sessionId);
        if (list == null) return List.of();
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    /** Drop everything for {@code sessionId}. */
    public void clear(String sessionId) {
        streams.remove(sessionId);
    }

    public void clearAll() { streams.clear(); }

    public int size(String sessionId) {
        CopyOnWriteArrayList<Map<String, Object>> list = streams.get(sessionId);
        return list == null ? 0 : list.size();
    }

    public void subscribe(Subscriber s) {
        if (s != null) subscribers.add(s);
    }

    public void unsubscribe(Subscriber s) { subscribers.remove(s); }

    @FunctionalInterface
    public interface Subscriber {
        void onAppend(String sessionId, Map<String, Object> entry);
    }
}
