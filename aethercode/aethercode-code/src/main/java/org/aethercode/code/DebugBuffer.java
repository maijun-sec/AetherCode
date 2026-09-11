package org.aethercode.code;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * In-memory log buffer (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._debug_buffer}
 * module. The Java port keeps a bounded ring buffer of recent log lines
 * for the in-app debug console.</p>
 */
public final class DebugBuffer {
    private DebugBuffer() {}

    /** Default buffer capacity. */
    public static final int DEFAULT_CAPACITY = 1000;

    private static final Deque<String> BUFFER = new ArrayDeque<>(DEFAULT_CAPACITY);

    /** Append a line to the buffer. */
    public static void append(String line) {
        if (line == null) return;
        synchronized (BUFFER) {
            if (BUFFER.size() >= DEFAULT_CAPACITY) BUFFER.pollFirst();
            BUFFER.addLast(line);
        }
    }

    /** Snapshot of the buffer contents (oldest first). */
    public static String[] snapshot() {
        synchronized (BUFFER) {
            return BUFFER.toArray(new String[0]);
        }
    }

    /** Clear the buffer. */
    public static void clear() {
        synchronized (BUFFER) {
            BUFFER.clear();
        }
    }
}
