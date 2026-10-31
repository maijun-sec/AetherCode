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

/**
 * Dependency-floor check (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._dep_floor_check}
 * module. The Java port exposes the surface used by the startup-time
 * dependency-floors guard; the full port checks {@code deepagents-core},
 * the partner SDKs, etc. against a minimum version.</p>
 */
public final class DepFloorCheck {
    private DepFloorCheck() {}

    /** A single dependency floor. */
    public record Floor(
            String packageName,
            String minVersion) {
    }

    /** Default dependency floors. */
    public static final Map<String, Floor> FLOORS = Map.of(
            "deepagents-core", new Floor("deepagents-core", "0.1.0"),
            "deepagents-acp", new Floor("deepagents-acp", "0.1.0"),
            "deepagents-cli", new Floor("deepagents-cli", "0.1.0"),
            "deepagents-talon", new Floor("deepagents-talon", "0.1.0"));
}
