package org.aethercode.code.skills;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Helpers for loading and formatting skill invocations.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.skills.invocation} module.</p>
 */
public final class SkillInvocation {
    private SkillInvocation() {}

    /**
     * Structured prompt and checkpoint metadata for a skill
     * invocation.
     */
    public record SkillInvocationEnvelope(String prompt, Map<String, Object> messageKwargs) {
        public SkillInvocationEnvelope {
            messageKwargs = messageKwargs == null ? Map.of() : Map.copyOf(messageKwargs);
        }
    }

    /**
     * Build the wrapped prompt and persisted metadata for a skill.
     */
    public static SkillInvocationEnvelope buildSkillInvocationEnvelope(
            Map<String, Object> skill, String content, String args) {
        Object nameObj = skill.get("name");
        String name = nameObj == null ? "" : nameObj.toString();
        StringBuilder prompt = new StringBuilder();
        prompt.append("I'm invoking the skill `").append(name).append("`. ")
                .append("Below are the full instructions from the skill's SKILL.md file. ")
                .append("Follow these instructions to complete the task.\n\n---\n")
                .append(content).append("\n---");
        if (args != null && !args.isEmpty()) {
            prompt.append("\n\n**User request:** ").append(args);
        }
        Map<String, Object> additionalKwargs = new LinkedHashMap<>();
        Map<String, Object> skillMeta = new LinkedHashMap<>();
        skillMeta.put("name", name);
        skillMeta.put("description", String.valueOf(skill.getOrDefault("description", "")));
        skillMeta.put("source", String.valueOf(skill.getOrDefault("source", "")));
        skillMeta.put("args", args == null ? "" : args);
        additionalKwargs.put("__skill", skillMeta);
        Map<String, Object> messageKwargs = new LinkedHashMap<>();
        messageKwargs.put("additional_kwargs", additionalKwargs);
        return new SkillInvocationEnvelope(prompt.toString(), messageKwargs);
    }
}
