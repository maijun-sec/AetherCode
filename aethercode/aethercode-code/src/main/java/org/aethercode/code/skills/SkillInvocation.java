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
