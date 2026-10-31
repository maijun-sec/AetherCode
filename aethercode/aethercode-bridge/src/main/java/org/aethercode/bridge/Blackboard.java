/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.bridge;

import java.util.Map;
import java.util.Set;

/**
 * shared key-value store for swarm teammates. prior round used an in-process
 * {@code Map}; prior round lifts that to an interface so the same {@link SwarmCoordinator}
 * code can run against an in-memory, file-backed, or sqlite-backed store.
 *
 * <p>Implementations must be safe for concurrent use — {@link SwarmCoordinator}
 * spawns work on the common ForkJoinPool and writes from many threads.
 */
public interface Blackboard {

    /** Store {@code value} under {@code key}. The value may be a String, Number, Boolean, Map, or List. */
    void write(String key, Object value);

    /** Read the value at {@code key}, or {@code null} when missing. */
    Object read(String key);

    /** Remove the entry at {@code key}. */
    void delete(String key);

    /** Snapshot of the current key set. */
    Set<String> keys();

    /** Read-only view of all entries. */
    Map<String, Object> snapshot();

    /** Persist any in-memory state to durable storage. No-op for the in-memory backend. */
    void flush();
}
