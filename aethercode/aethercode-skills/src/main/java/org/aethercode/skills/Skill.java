package org.aethercode.skills;

import java.util.List;
import java.util.Map;

/**
 * One skill declaration. Modelled after the TS {@code Skill} type — a Markdown file under
 * {@code .claude/skills/} with YAML-ish front matter.
 *
 * <p>prior round representation: simple POJO with name, description, body, and optional allowed tools.
 * The body is the skill's prompt; when activated, the body is injected into the conversation
 * and any of the {@code allowedTools} are added to the pool.
 */
public record Skill(
        String name,
        String description,
        String body,
        List<String> allowedTools,
        Map<String, Object> metadata
) {
    public Skill {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("skill name is required");
        if (body == null) body = "";
        if (allowedTools == null) allowedTools = List.of();
        if (metadata == null) metadata = Map.of();
    }
}
