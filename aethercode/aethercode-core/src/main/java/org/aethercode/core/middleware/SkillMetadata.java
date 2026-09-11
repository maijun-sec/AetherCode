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
