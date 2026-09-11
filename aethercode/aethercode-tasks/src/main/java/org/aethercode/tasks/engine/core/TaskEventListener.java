package org.aethercode.tasks.engine.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Listener interface for {@link TaskEvent}s. Listeners are called
 * in registration order on the thread that produced the event
 * (so listeners must be fast and non-blocking — they should
 * hand off to a queue for slow work).
 */
@FunctionalInterface
public interface TaskEventListener {
    void onEvent(TaskEvent event);

    /** No-op listener for tests. */
    TaskEventListener NOOP = event -> { };

    /** A small in-memory list-of-listeners helper. */
    final class Multicaster implements TaskEventListener {
        private final List<TaskEventListener> listeners = new CopyOnWriteArrayList<>();

        public Multicaster add(TaskEventListener l) {
            if (l != null) listeners.add(l);
            return this;
        }

        public Multicaster add(Consumer<TaskEvent> consumer) {
            if (consumer != null) listeners.add(consumer::accept);
            return this;
        }

        public int size() { return listeners.size(); }

        public void clear() { listeners.clear(); }

        @Override
        public void onEvent(TaskEvent event) {
            for (TaskEventListener l : listeners) {
                try { l.onEvent(event); }
                catch (RuntimeException ignored) { /* listener faults are isolated */ }
            }
        }
    }
}
