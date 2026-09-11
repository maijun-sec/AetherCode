package org.aethercode.core.middleware;

import java.util.regex.Pattern;

/**
 * Validates skill names per the Agent Skills specification.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware.skills._validate_skill_name} helper.
 * Constraints:</p>
 *
 * <ul>
 *   <li>1-{@value SkillsPrompts#MAX_SKILL_NAME_LENGTH} characters</li>
 *   <li>Unicode lowercase alphanumeric and hyphens only</li>
 *   <li>Must not start or end with {@code -}</li>
 *   <li>Must not contain consecutive {@code --}</li>
 *   <li>Must match the parent directory name</li>
 * </ul>
 */
public final class SkillNameValidator {
    private SkillNameValidator() {}

    /** Result of a {@link #validate(String, String)} call. */
    public record Result(boolean valid, String error) {
        public static Result ok() { return new Result(true, ""); }
    }

    private static final Pattern ALLOWED_CHAR = Pattern.compile("[a-z0-9-]");

    public static Result validate(String name, String directoryName) {
        if (name == null || name.isEmpty()) {
            return new Result(false, "name is required");
        }
        if (name.length() > SkillsPrompts.MAX_SKILL_NAME_LENGTH) {
            return new Result(false, "name exceeds " + SkillsPrompts.MAX_SKILL_NAME_LENGTH + " characters");
        }
        if (name.startsWith("-") || name.endsWith("-") || name.contains("--")) {
            return new Result(false, "name must be lowercase alphanumeric with single hyphens only");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '-') continue;
            if (Character.isDigit(c)) continue;
            if (Character.isLetter(c) && Character.isLowerCase(c)) continue;
            return new Result(false, "name must be lowercase alphanumeric with single hyphens only");
        }
        if (!name.equals(directoryName)) {
            return new Result(false,
                    "name '" + name + "' must match directory name '" + directoryName + "'");
        }
        return Result.ok();
    }
}
