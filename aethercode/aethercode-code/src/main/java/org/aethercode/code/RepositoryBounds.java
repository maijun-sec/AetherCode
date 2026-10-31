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
 * Repository bounds (stub).
 *
 * <p>Java-native port of the Python {@code deepagents_code._repository_bounds}
 * module. The Java port exposes the surface used to constrain the agent
 * to a project root; the full implementation lands with the
 * deepagents-core backend port.</p>
 */
public final class RepositoryBounds {
    private RepositoryBounds() {}

    /** Repository-bounds config. */
    public record Bounds(
            String projectRoot,
            boolean readOnly,
            java.util.List<String> allowedPaths) {
    }

    /** Read the bounds for a project root. */
    public static Bounds forRoot(String projectRoot) {
        return new Bounds(projectRoot, false, java.util.List.of());
    }
}
