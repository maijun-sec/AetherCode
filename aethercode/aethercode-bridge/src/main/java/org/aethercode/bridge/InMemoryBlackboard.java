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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * in-process {@link Blackboard} backed by a {@link ConcurrentHashMap}. This is the
 * default when the caller does not specify a path — same behaviour as the prior round
 * {@code SwarmCoordinator} had.
 */
public final class InMemoryBlackboard implements Blackboard {

    private final Map<String, Object> store = new ConcurrentHashMap<>();

    @Override public void write(String key, Object value) { store.put(key, value); }
    @Override public Object read(String key) { return store.get(key); }
    @Override public void delete(String key) { store.remove(key); }
    @Override public Set<String> keys() { return new HashSet<>(store.keySet()); }
    @Override public Map<String, Object> snapshot() { return Map.copyOf(store); }
    @Override public void flush() { /* no-op */ }
}
