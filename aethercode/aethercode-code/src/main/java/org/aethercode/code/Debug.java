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

package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-app debug console (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._debug} module.
 * The Java port exposes the small listener-driven API the TUI uses to
 * pipe log lines into the in-app debug console.</p>
 */
public final class Debug {
    private Debug() {}

    private static final Logger LOG = LoggerFactory.getLogger(Debug.class);

    private static final CopyOnWriteArrayList<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();

    /** Add a debug-line listener. */
    public static void addListener(Consumer<String> listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    /** Remove a debug-line listener. */
    public static void removeListener(Consumer<String> listener) {
        LISTENERS.remove(listener);
    }

    /** Emit a debug line. */
    public static void emit(String line) {
        if (line == null) return;
        LOG.debug(line);
        for (Consumer<String> l : LISTENERS) {
            try { l.accept(line); } catch (Exception ignored) {}
        }
    }
}
