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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Information about installed Python extras.
 *
 * <p>Java-native port of the Python {@code deepagents_code.extras_info}
 * module. The Java port provides a tiny "is this extra available?" check
 * used by the install/upgrade paths; for the Java runtime it just reads
 * a static set of known extras so callers don't depend on Python's
 * importlib.metadata.</p>
 */
public final class ExtrasInfo {
    private ExtrasInfo() {}

    /** One installed extra. */
    public record Extra(String name, String version) {}

    private static final Map<String, Extra> EXTRAS = new LinkedHashMap<>();

    /** Return the known installed extras. */
    public static Map<String, Extra> known() {
        return Map.copyOf(EXTRAS);
    }

    /** Register an extra (used by the install-time wiring). */
    public static void register(Extra extra) {
        if (extra == null) return;
        EXTRAS.put(extra.name(), extra);
    }

    /** Whether the named extra is available. */
    public static boolean isAvailable(String name) {
        return EXTRAS.containsKey(name);
    }

    /** The set of registered extra names. */
    public static Set<String> names() {
        return Set.copyOf(EXTRAS.keySet());
    }
}
