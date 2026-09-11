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
