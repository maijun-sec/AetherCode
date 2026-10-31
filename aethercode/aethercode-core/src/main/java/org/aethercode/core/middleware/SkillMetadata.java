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

package org.aethercode.core.middleware;

import java.util.List;
import java.util.Map;

/**
 * Metadata for a skill per the Agent Skills specification.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware.skills.SkillMetadata} TypedDict. The
 * record carries the (path, name, description) triple plus
 * optional license, compatibility, metadata, and allowed_tools
 * fields, each constrained per the spec.</p>
 */
public record SkillMetadata(
        String path,
        String name,
        String description,
        String license,
        String compatibility,
        Map<String, String> metadata,
        List<String> allowedTools) {

    public SkillMetadata {
        if (path == null) path = "";
        if (name == null) name = "";
        if (description == null) description = "";
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }
}
