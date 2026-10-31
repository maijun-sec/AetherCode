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

package org.aethercode.bridge.backends;

import org.aethercode.bridge.SwarmCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * backend registry. Maps a backend name to an implementation. The
 * {@code in-process} backend is the default; the others are loaded on demand.
 */
public class TeammateBackendRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(TeammateBackendRegistry.class);
    private static final Map<String, TeammateBackend> BACKENDS = new ConcurrentHashMap<>();

    static {
        // in-process is built into SwarmCoordinator — not registered here.
        register(new TmuxBackend());
        register(new ITerm2Backend());
    }

    public static void register(TeammateBackend b) { BACKENDS.put(b.name(), b); }

    public static TeammateBackend get(String name) {
        return BACKENDS.get(name);
    }

    public static java.util.Set<String> names() { return BACKENDS.keySet(); }
}
