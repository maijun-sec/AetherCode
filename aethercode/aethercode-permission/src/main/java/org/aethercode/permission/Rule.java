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

import java.util.List;
import java.util.Map;

/**
 * One rule in the project / user permission settings. Mirrors the TS rules array:
 *
 * <pre>
 *   "permissions": {
 *     "allow": [{ "tool": "Bash", "prompt": "git status" }],
 *     "deny":  [{ "tool": "Bash", "prompt": "rm -rf /" }],
 *     "ask":   [{ "tool": "Bash" }]
 *   }
 * </pre>
 *
 * <p>{@code promptMatcher} is a regex that tests the tool input's relevant field. For Bash, it
 * matches the command. For FileEdit/FileWrite, it matches the path. For other tools, it is
 * unused.
 */
public record Rule(String tool, String promptMatcher, String reason) {

    public boolean matchesTool(String toolName) {
        return tool != null && (tool.equals("*") || tool.equalsIgnoreCase(toolName));
    }

    public boolean matchesPrompt(String prompt) {
        if (promptMatcher == null || promptMatcher.isBlank()) return true;
        if (prompt == null) return false;
        return prompt.matches(promptMatcher);
    }

    public static List<Rule> fromList(List<Map<String, Object>> raw) {
        if (raw == null) return List.of();
        return raw.stream().map(Rule::fromMap).toList();
    }

    @SuppressWarnings("unchecked")
    public static Rule fromMap(Map<String, Object> m) {
        if (m == null) return new Rule(null, null, null);
        Object p = m.get("prompt");
        Object r = m.get("reason");
        Object t = m.get("tool");
        return new Rule(
                t == null ? null : t.toString(),
                p == null ? null : p.toString(),
                r == null ? null : r.toString()
        );
    }
}
