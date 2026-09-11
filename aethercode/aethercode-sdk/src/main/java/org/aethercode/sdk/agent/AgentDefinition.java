package org.aethercode.sdk.agent;

import java.util.List;
import java.util.Map;

/**
 * a custom agent definition loaded from {@code .aethercode/agents/*.md}.
 * Modelled on the TS original's {@code agentDefinitions.ts}. Each definition
 * is a Markdown file with YAML frontmatter:
 *
 * <pre>
 *   ---
 *   name: code-reviewer
 *   description: Reviews code for quality and security issues
 *   model: MiniMax-M3
 *   tools:
 *     - file_read
 *     - glob
 *     - grep
 *   ---
 *
 *   You are a meticulous code reviewer. For every change, check:
 *   1. Correctness — does it actually do what it claims?
 *   2. Security — any input validation gaps, injection vectors?
 *   3. Tests — are the changes covered by tests?
 *   ...
 * </pre>
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code name} — required, unique key for {@code /agent name}</li>
 *   <li>{@code description} — shown in {@code /agents} listing</li>
 *   <li>{@code model} — optional model id override; falls back to the
 *       engine's main model when absent</li>
 *   <li>{@code tools} — optional list of tool names to enable. When
 *       present, ONLY these tools are available (defense in depth —
 *       a code-reviewer agent shouldn't have file_write). When
 *       absent, the full default tool pool is used.</li>
 *   <li>{@code system} — the rest of the file (after the frontmatter
 *       close {@code ---}) is the system prompt</li>
 * </ul>
 */
public record AgentDefinition(
        String name,
        String description,
        String model,
        List<String> tools,
        String system
) {
    /** Empty system placeholder. Used by tests. */
    public AgentDefinition {
        if (tools == null) tools = List.of();
    }
}
