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

package org.aethercode.permission;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * a simple "quick-allow" list for tool permissions. The
 * user can add tool names at runtime via {@code /allow <tool>},
 * and the permission checker consults the list before prompting.
 *
 * <p>Two scopes:
 * <ul>
 *   <li>{@code add(tool)} — session-scoped; cleared on close.</li>
 *   <li>{@code pin(tool)} — survives across sessions, persisted
 *       to the settings file by the caller (this class is in-
 *       memory; persistence is the caller's job).</li>
 * </ul>
 *
 * <p>Lookup is exact match. Wildcard support is intentionally
 * out of scope — callers should add multiple specific tools if
 * they need fine-grained control. The set is bounded by
 * {@code maxSize} to prevent runaway growth.
 */
public final class QuickAllowList {

    private final Set<String> allowed = new LinkedHashSet<>();
    private final int maxSize;

    public QuickAllowList() { this(128); }

    public QuickAllowList(int maxSize) {
        if (maxSize < 1) throw new IllegalArgumentException("maxSize must be >= 1");
        this.maxSize = maxSize;
    }

    public synchronized boolean add(String tool) {
        if (tool == null || tool.isBlank()) return false;
        if (allowed.contains(tool)) return true; // already present, no-op success
        if (allowed.size() >= maxSize) return false;
        return allowed.add(tool);
    }

    public synchronized boolean remove(String tool) {
        if (tool == null) return false;
        return allowed.remove(tool);
    }

    public synchronized boolean isAllowed(String tool) {
        if (tool == null) return false;
        return allowed.contains(tool);
    }

    public synchronized int size() { return allowed.size(); }
    public synchronized int maxSize() { return maxSize; }
    public synchronized Set<String> snapshot() {
        return Set.copyOf(allowed);
    }
    public synchronized void clear() { allowed.clear(); }
}
