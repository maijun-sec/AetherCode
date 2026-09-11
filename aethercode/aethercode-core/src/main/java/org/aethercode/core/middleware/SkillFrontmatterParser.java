package org.aethercode.core.middleware;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses YAML frontmatter from {@code SKILL.md} content.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware.skills._parse_skill_metadata}
 * helper. The Python port uses PyYAML; the Java port ships a
 * minimal YAML-subset parser that handles the
 * <em>key: value</em> shape the Agent Skills spec uses
 * (string, list, nested mapping for {@code metadata}). Consumers
 * that need full YAML can drop in SnakeYAML or another library
 * and override this class.</p>
 */
public final class SkillFrontmatterParser {
    private static final Logger LOGGER = Logger.getLogger(SkillFrontmatterParser.class.getName());

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*-\\s+(.+)$");

    private SkillFrontmatterParser() {}

    /**
     * Parse the frontmatter block at the top of {@code content}.
     * Returns {@code null} if the content is too large, has no
     * frontmatter, has invalid YAML, or is missing required
     * fields. {@code directoryName} is the parent directory of
     * the skill &mdash; the {@code name} field must match.
     */
    public static SkillMetadata parse(String content, String skillPath, String directoryName) {
        if (content == null) return null;
        if (content.length() > SkillsPrompts.MAX_SKILL_FILE_SIZE) {
            LOGGER.warning(() -> "Skipping " + skillPath
                    + ": content too large (" + content.length() + " bytes)");
            return null;
        }
        Matcher match = FRONTMATTER_PATTERN.matcher(content);
        if (!match.find()) {
            LOGGER.warning(() -> "Skipping " + skillPath
                    + ": no valid YAML frontmatter found");
            return null;
        }
        String frontmatter = match.group(1);
        Map<String, Object> parsed;
        try {
            parsed = parseYamlMap(frontmatter);
        } catch (RuntimeException e) {
            LOGGER.warning(() -> "Invalid YAML in " + skillPath + ": " + e.getMessage());
            return null;
        }
        if (parsed == null || parsed.isEmpty()) {
            LOGGER.warning(() -> "Skipping " + skillPath
                    + ": frontmatter is empty or not a mapping");
            return null;
        }
        String name = stringValue(parsed.get("name")).trim();
        String description = stringValue(parsed.get("description")).trim();
        if (name.isEmpty() || description.isEmpty()) {
            LOGGER.warning(() -> "Skipping " + skillPath
                    + ": missing required 'name' or 'description'");
            return null;
        }
        // Validate name format (warn but continue).
        SkillNameValidator.Result validation = SkillNameValidator.validate(name, directoryName);
        if (!validation.valid()) {
            LOGGER.warning(() -> "Skill '" + name + "' in " + skillPath
                    + " does not follow Agent Skills specification: "
                    + validation.error() + ". Consider renaming for spec compliance.");
        }
        final String finalDescription = description;
        if (finalDescription.length() > SkillsPrompts.MAX_SKILL_DESCRIPTION_LENGTH) {
            LOGGER.warning(() -> "Skill description in " + skillPath + " is "
                    + finalDescription.length() + " characters, over the Agent Skills "
                    + "spec limit of " + SkillsPrompts.MAX_SKILL_DESCRIPTION_LENGTH
                    + ". Keeping only the first " + SkillsPrompts.MAX_SKILL_DESCRIPTION_LENGTH
                    + " characters; the rest is dropped from what the model sees when "
                    + "deciding whether to use this skill. Shorten the 'description' field "
                    + "in the SKILL.md frontmatter to stay within the limit.");
            description = finalDescription.substring(0, SkillsPrompts.MAX_SKILL_DESCRIPTION_LENGTH);
        }
        String compatibility = stringValue(parsed.get("compatibility")).trim();
        if (compatibility.isEmpty()) compatibility = null;
        final String finalCompatibility = compatibility;
        if (finalCompatibility != null
                && finalCompatibility.length() > SkillsPrompts.MAX_SKILL_COMPATIBILITY_LENGTH) {
            LOGGER.warning(() -> "Skill compatibility in " + skillPath
                    + " is " + finalCompatibility.length() + " characters, over the Agent Skills "
                    + "spec limit of " + SkillsPrompts.MAX_SKILL_COMPATIBILITY_LENGTH
                    + ". Keeping only the first "
                    + SkillsPrompts.MAX_SKILL_COMPATIBILITY_LENGTH
                    + " characters and dropping the rest. Shorten the 'compatibility' field "
                    + "in the SKILL.md frontmatter to stay within the limit.");
            compatibility = finalCompatibility.substring(0, SkillsPrompts.MAX_SKILL_COMPATIBILITY_LENGTH);
        }
        String license = stringValue(parsed.get("license")).trim();
        if (license.isEmpty()) license = null;
        List<String> allowedTools = parseAllowedTools(parsed.get("allowed-tools"), skillPath);
        Map<String, String> metadata = parseMetadata(parsed.get("metadata"), skillPath);
        return new SkillMetadata(skillPath, name, description, license, compatibility,
                metadata, allowedTools);
    }

    // -----------------------------------------------------------------
    // Minimal YAML-subset parser
    // -----------------------------------------------------------------

    /**
     * Parse a {@code key: value} mapping from {@code yaml}. The
     * parser supports strings, lists of strings, and one level of
     * nested mapping (for the {@code metadata} key). Anything more
     * exotic should be replaced with a real YAML library.
     */
    static Map<String, Object> parseYamlMap(String yaml) {
        Map<String, Object> result = new LinkedHashMap<>();
        String[] lines = yaml.split("\\r?\\n");
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String stripped = line.stripLeading();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                i++; continue;
            }
            int colon = findTopLevelColon(stripped);
            if (colon < 0) { i++; continue; }
            String key = stripped.substring(0, colon).trim();
            String rest = stripped.substring(colon + 1).trim();
            if (rest.isEmpty()) {
                // Could be a list or nested mapping on the following lines.
                int j = i + 1;
                List<String> collected = new ArrayList<>();
                Map<String, String> nested = new LinkedHashMap<>();
                boolean isList = false;
                boolean isMap = false;
                while (j < lines.length) {
                    String next = lines[j];
                    if (next.isBlank()) { j++; continue; }
                    int nextIndent = next.length() - next.stripLeading().length();
                    int sourceIndent = line.length() - line.stripLeading().length();
                    if (nextIndent <= sourceIndent) break;
                    String nextStripped = next.stripLeading();
                    if (nextStripped.startsWith("- ")) {
                        isList = true;
                        collected.add(nextStripped.substring(2).trim());
                    } else if (nextStripped.contains(":")) {
                        isMap = true;
                        int nc = findTopLevelColon(nextStripped);
                        if (nc > 0) {
                            nested.put(nextStripped.substring(0, nc).trim(),
                                    unquote(nextStripped.substring(nc + 1).trim()));
                        }
                    }
                    j++;
                }
                if (isMap) result.put(key, nested);
                else if (isList) result.put(key, unquoteList(collected));
                i = j;
            } else {
                result.put(key, unquote(rest));
                i++;
            }
        }
        return result;
    }

    private static int findTopLevelColon(String s) {
        // Colon must not be inside quotes.
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == ':' && !inSingle && !inDouble) return i;
        }
        return -1;
    }

    private static String unquote(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() >= 2
                && ((t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static List<String> unquoteList(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            String t = unquote(s);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Parse the {@code allowed-tools} value (string or list). */
    @SuppressWarnings("unchecked")
    static List<String> parseAllowedTools(Object raw, String skillPath) {
        if (raw == null) return List.of();
        if (raw instanceof String s) {
            // Space- or comma-separated list.
            String[] parts = s.split("[\\s,]+");
            List<String> out = new ArrayList<>();
            for (String p : parts) if (!p.isEmpty()) out.add(p);
            return out;
        }
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String str && !str.isBlank()) out.add(str.trim());
            }
            return out;
        }
        LOGGER.warning(() -> "Ignoring 'allowed-tools' in " + skillPath
                + ": expected a string or list, got " + raw.getClass().getSimpleName());
        return List.of();
    }

    /** Parse the {@code metadata} value (string -> string mapping). */
    @SuppressWarnings("unchecked")
    static Map<String, String> parseMetadata(Object raw, String skillPath) {
        if (raw == null) return Map.of();
        if (raw instanceof Map<?, ?> m) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            return out;
        }
        LOGGER.warning(() -> "Ignoring non-dict metadata in " + skillPath
                + " (got " + raw.getClass().getSimpleName() + ")");
        return Map.of();
    }

    private static String stringValue(Object o) { return o == null ? "" : o.toString(); }
}
