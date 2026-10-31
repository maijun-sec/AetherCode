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

package org.aethercode.core.fs.backend;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory {@link FilesStateStore}.
 *
 * <p>For tests and for the simple "no graph runtime" case. All reads
 * see the writes already sent: the {@code send} method mutates the
 * same map the {@code read} method returns.</p>
 */
public class InMemoryFilesStateStore implements FilesStateStore {

    private final Map<String, FileData> files = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public InMemoryFilesStateStore() {}

    public InMemoryFilesStateStore(Map<String, FileData> initial) {
        Objects.requireNonNull(initial, "initial");
        lock.writeLock().lock();
        try {
            files.putAll(initial);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Map<String, FileData> read(boolean fresh) {
        lock.readLock().lock();
        try {
            // Defensive copy so callers can't mutate the live map.
            return new HashMap<>(files);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void send(Map<String, FileData> update) {
        if (update == null || update.isEmpty()) return;
        lock.writeLock().lock();
        try {
            for (Map.Entry<String, FileData> e : update.entrySet()) {
                if (e.getValue() == null) {
                    files.remove(e.getKey());
                } else {
                    files.put(e.getKey(), e.getValue());
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Seed initial files (test convenience). */
    public InMemoryFilesStateStore putAll(Map<String, FileData> initial) {
        send(initial);
        return this;
    }
}
