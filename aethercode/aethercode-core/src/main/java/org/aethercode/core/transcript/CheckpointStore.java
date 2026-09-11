package org.aethercode.core.transcript;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.aethercode.core.message.Message;

/**
 * in-memory checkpoint store. The REPL exposes {@code /checkpoint}
 * and {@code /rewind} commands backed by this; the model never sees the
 * checkpoint surface — it just gets a re-wound transcript.
 *
 * <p>Saves are cheap (deep copy of {@link Message} records, which already
 * hold immutable data) so there's no separate snapshot class. Restoring
 * returns the captured message list; the caller is responsible for
 * replacing the live transcript.
 */
public class CheckpointStore {

    /** callback for "transcript rewind" — caller replaces the live list. */
    @FunctionalInterface
    public interface Restorer {
        void restore(List<Message> messages);
    }

    private final Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    public synchronized Checkpoint save(List<Message> messages) {
        return save("checkpoint-" + counter.incrementAndGet(), messages);
    }

    public synchronized Checkpoint save(String label, List<Message> messages) {
        Objects.requireNonNull(messages, "messages");
        List<Message> copy = new ArrayList<>(messages);
        Checkpoint cp = new Checkpoint(null, label, null, copy);
        checkpoints.put(cp.id(), cp);
        return cp;
    }

    public synchronized Optional<Checkpoint> get(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(checkpoints.get(id));
    }

    public synchronized List<Checkpoint> list() {
        List<Checkpoint> out = new ArrayList<>(checkpoints.values());
        out.sort(Comparator.comparing(Checkpoint::createdAt));
        return out;
    }

    public synchronized Optional<Checkpoint> latest() {
        if (checkpoints.isEmpty()) return Optional.empty();
        Checkpoint best = null;
        for (Checkpoint cp : checkpoints.values()) {
            if (best == null || cp.createdAt().isAfter(best.createdAt())) best = cp;
        }
        return Optional.of(best);
    }

    public synchronized boolean delete(String id) {
        return id != null && checkpoints.remove(id) != null;
    }

    public synchronized int size() { return checkpoints.size(); }

    public synchronized void clear() { checkpoints.clear(); counter.set(0); }

    /**
     * Restore by id. The returned {@code Checkpoint} (if present) is the
     * source of the message list; the caller decides what to do with it.
     */
    public synchronized Optional<Checkpoint> restore(String id, Restorer sink) {
        Optional<Checkpoint> cp = get(id);
        cp.ifPresent(c -> sink.restore(new ArrayList<>(c.messages())));
        return cp;
    }

    /** shorthand for the latest checkpoint. */
    public synchronized Optional<Checkpoint> restoreLatest(Restorer sink) {
        Optional<Checkpoint> cp = latest();
        cp.ifPresent(c -> sink.restore(new ArrayList<>(c.messages())));
        return cp;
    }

    /**
     * restore by 1-based index in the chronological list. The
     * {@code /rewind 2} command maps to {@code restoreByIndex(2, ...)}.
     */
    public synchronized Optional<Checkpoint> restoreByIndex(int oneBasedIndex, Restorer sink) {
        List<Checkpoint> sorted = list();
        if (oneBasedIndex < 1 || oneBasedIndex > sorted.size()) return Optional.empty();
        Checkpoint cp = sorted.get(oneBasedIndex - 1);
        sink.restore(new ArrayList<>(cp.messages()));
        return Optional.of(cp);
    }
}
