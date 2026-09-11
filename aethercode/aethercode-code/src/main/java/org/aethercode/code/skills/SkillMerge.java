package org.aethercode.code.skills;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared skill-merge helper with override (name-collision) debug
 * logging.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.skills.merge} module. Merges skills by
 * name, last-one-wins. A higher-precedence skill replaces a
 * lower-precedence skill with the same name. That override behavior
 * is intentional; this helper leaves it unchanged and makes each
 * replacement observable in debug logs.</p>
 */
public final class SkillMerge {
    private static final Logger LOGGER = Logger.getLogger(SkillMerge.class.getName());

    private SkillMerge() {}

    /**
     * Merge one skill into {@code merged} by name, last-one-wins.
     *
     * <p>Callers must iterate sources in ascending precedence order
     * so the replacing skill is always the higher-precedence one.</p>
     */
    public static void mergeSkill(Map<String, Map<String, Object>> merged,
                                   Map<String, String> sourceLabels,
                                   Map<String, Object> skill,
                                   String sourceLabel) {
        Object nameObj = skill.get("name");
        if (nameObj == null) return;
        String name = nameObj.toString();
        Map<String, Object> previous = merged.get(name);
        if (previous != null) {
            LOGGER.log(Level.FINE,
                    "Skill {0} override: {1} (source: {2}) replaced by {3} (source: {4})",
                    new Object[] { name, previous.get("path"),
                            sourceLabels.getOrDefault(name, "unknown"),
                            skill.get("path"),
                            sourceLabel == null ? "unknown" : sourceLabel });
        }
        merged.put(name, skill);
        sourceLabels.put(name, sourceLabel);
    }
}
