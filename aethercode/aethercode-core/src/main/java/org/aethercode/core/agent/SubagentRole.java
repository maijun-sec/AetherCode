package org.aethercode.core.agent;

import org.aethercode.core.tool.Tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * subagent role presets. Modelled on
 * oh-my-opencode's {@code builtin-agents/*} (explore,
 * general-purpose, coder) but in a much smaller form
 * because AetherCode is a single-process engine rather
 * than a multi-agent orchestrator.
 *
 * <p>A role preset is a {@code (systemPrompt, toolPolicy)}
 * pair: when the model calls {@code spawn_agent} with a
 * {@code role}, the subagent uses the role's system
 * prompt as its preamble and is restricted to the role's
 * allowed tool set. The role's prompt is appended AFTER
 * the engine's base system prompt so the role specialises
 * the parent without replacing it.
 *
 * <p>Three roles ship by default:
 * <ul>
 *   <li>{@link #EXPLORE} — read-only investigation. No
 *       file_write, no file_edit, no web fetch. Used for
 *       "where is X?" / "find the code that does Y" tasks.
 *       Mirrors OMO's {@code explore} agent's tool
 *       restrictions.</li>
 *   <li>{@link #GENERAL_PURPOSE} — the default. Full tool
 *       set, no specialisation. Mirrors OMO's
 *       {@code general-purpose} agent.</li>
 *   <li>{@link #CODER} — write-focused. file_write +
 *       file_edit + bash (for build/test commands). No
 *       web fetch, no web search (the model shouldn't
 *       pull external content while writing code).
 *       Mirrors OMO's intent for the {@code coder} role.</li>
 * </ul>
 *
 * <p>Roles are looked up by lowercase name; an unknown
 * role name falls back to {@link #GENERAL_PURPOSE} (a
 * generous default — the model's mistake is a too-broad
 * subagent, not a refusal). Custom roles can be
 * registered via {@link #register(String, RolePreset)}
 * at startup; the registry is a plain
 * {@link ConcurrentHashMap} so concurrent
 * registration is safe.
 */
public final class SubagentRole {

    private SubagentRole() {}

    /** A role definition: a system-prompt preamble + the
     *  set of tools the subagent may use. {@code null}
     *  {@code allowedTools} = all tools allowed (the
     *  "general" case). An empty set = no tools at all
     *  (the subagent is text-only — equivalent to the
     *  legacy single-shot mode). */
    public record RolePreset(
            String name,
            String description,
            String systemPrompt,
            /** {@code null} = no restrictions. Otherwise the
             *  set of tool names the subagent may use. */
            Set<String> allowedTools,
            /** Tools the subagent MUST NOT call even if they
             *  would otherwise be available. Empty set by
             *  default. */
            Set<String> deniedTools
    ) {
        public RolePreset {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("role name is required");
            }
            // Defensive copies; null → null (means unrestricted)
            if (allowedTools != null) {
                allowedTools = Set.copyOf(allowedTools);
            }
            if (deniedTools != null) {
                deniedTools = Set.copyOf(deniedTools);
            }
        }
    }

    /** Read-only investigator: searches, reads, greps, runs
     *  bash for inspection (ls / cat / find). No edits,
     *  no web access. */
    public static final RolePreset EXPLORE = new RolePreset(
            "explore",
            "Read-only investigator: searches the codebase, reads files, runs inspection commands. No edits, no web access.",
            """
            You are the AetherCode explore subagent. Your job is to answer \
            questions about a codebase by reading, searching, and running \
            read-only inspection commands. You MUST NOT modify files, run \
            build commands, or fetch external content.

            When answering a question, structure your response as:
              1. Intent — restate what the parent agent is really after
              2. Findings — file paths + 1-2 line excerpts that matter
              3. Answer — direct prose that lets the parent proceed

            Use file_read, glob, grep, and bash (read-only commands) in \
            parallel whenever possible. Do not call spawn_agent again. \
            Do not call file_write or file_edit. When done, return a \
            concise summary — the parent will integrate it into its own work.
            """.strip(),
            Set.of(
                    "file_read",
                    "glob",
                    "grep",
                    "list_files",
                    "list_dir",
                    "bash",
                    "web_search",
                    "web_fetch"
            ),
            Set.of(
                    "file_write",
                    "file_edit",
                    "file_create",
                    "spawn_agent",
                    "todo_write"
            )
    );

    /** Default role: full tool set, no specialisation.
     *  Equivalent to the legacy "single multi-step subagent"
     *  that spawn_agent has used since prior round. */
    public static final RolePreset GENERAL_PURPOSE = new RolePreset(
            "general-purpose",
            "Default multi-purpose subagent with access to the full tool pool.",
            """
            You are a subagent invoked by the main AetherCode agent. You \
            have the same tool pool the parent has. Complete the parent's \
            task concisely. When you are done, return a short summary of \
            what you did and what you found.
            """.strip(),
            null,   // no restrictions — all tools allowed
            Set.of()  // nothing denied
    );

    /** Write-focused: file_write + file_edit + bash for
     *  build / test commands. No web access. The model
     *  should not pull external content while in a
     *  coding task — it should focus on writing. */
    public static final RolePreset CODER = new RolePreset(
            "coder",
            "Write-focused subagent: file_write + file_edit + bash for build/test. No web access.",
            """
            You are the AetherCode coder subagent. Your job is to make \
            code changes — implement functions, refactor, add tests, fix \
            bugs. Use file_write / file_edit for changes; use bash for \
            build / test commands. Do NOT fetch external content; the \
            parent already has the context it needs.

            When making changes:
              - Read the file first (file_write refuses to overwrite \
                existing files unless you've read them this session).
              - Make minimal, targeted edits — prefer file_edit over \
                full file_write.
              - After each change, briefly verify the result (e.g. \
                re-read a section or run the test the change enables).
              - When done, return a short summary of files touched + \
                test status.
            """.strip(),
            null,   // all tools allowed…
            Set.of(  // …except web fetch + spawn_agent
                    "web_fetch",
                    "web_search",
                    "spawn_agent"
            )
    );

    /** Look up a role by name. The match is case-insensitive
     *  and trims whitespace. Unknown names fall back to
     *  {@link #GENERAL_PURPOSE} so a stale or misspelled
     *  role name is recoverable. The role is returned as a
     *  defensive copy so callers can't mutate the
     *  registry. */
    public static RolePreset lookup(String name) {
        if (name == null) return GENERAL_PURPOSE;
        RolePreset p = REGISTRY.get(name.toLowerCase().trim());
        return p == null ? GENERAL_PURPOSE : p;
    }

    /** All role names known to the engine, in registration
     *  order. Used by {@code /agents} slash command and by
     *  tests. Returns an unmodifiable view. */
    public static List<String> names() {
        return java.util.Collections.unmodifiableList(
                new java.util.ArrayList<>(REGISTRY.keySet()));
    }

    /** Register a custom role. Throws on collision so
     *  duplicate-name bugs are caught at boot, not at
     *  first call. */
    public static synchronized void register(RolePreset preset) {
        if (preset == null) throw new IllegalArgumentException("preset is null");
        String key = preset.name().toLowerCase();
        if (REGISTRY.containsKey(key)) {
            throw new IllegalStateException("role already registered: " + preset.name());
        }
        REGISTRY.put(key, preset);
    }

    /** Build the subagent's effective system prompt by
     *  appending the role's preamble to the base prompt.
     *  The role preamble is marked with
     *  {@code <subagent-role name="...">...</subagent-role>}
     *  so the model can tell which role it's playing. */
    public static String buildSystemPrompt(String basePrompt, RolePreset role) {
        if (role == null || role == GENERAL_PURPOSE) {
            // No preamble — the role doesn't add anything.
            return basePrompt;
        }
        if (basePrompt == null) basePrompt = "";
        StringBuilder sb = new StringBuilder(basePrompt);
        if (sb.length() > 0) sb.append("\n\n");
        sb.append("<subagent-role name=\"").append(escape(role.name())).append("\">\n");
        sb.append(role.systemPrompt()).append("\n");
        sb.append("</subagent-role>");
        return sb.toString();
    }

    /** Filter a parent tool pool to the role's allowed set.
     *  Tools the role denies are removed; tools the role
     *  doesn't mention (and the role has an allow-list) are
     *  removed; tools the role doesn't mention and the
     *  role has no allow-list (GENERAL_PURPOSE) are
     *  unchanged. */
    public static List<Tool> filterTools(List<Tool> parentTools, RolePreset role) {
        if (role == null || parentTools == null) return parentTools;
        if (role.allowedTools() == null && (role.deniedTools() == null || role.deniedTools().isEmpty())) {
            return parentTools;
        }
        Map<String, Tool> out = new LinkedHashMap<>();
        for (Tool t : parentTools) {
            if (role.deniedTools() != null && role.deniedTools().contains(t.name())) {
                continue;
            }
            if (role.allowedTools() != null && !role.allowedTools().contains(t.name())) {
                continue;
            }
            out.put(t.name(), t);
        }
        return List.copyOf(out.values());
    }

    /** backing map for the registry. The map is
     *  mutable so {@link #register(RolePreset)} can add
     *  custom roles at boot. {@link #names()} returns an
     *  unmodifiable view so callers can't mutate the
     *  registry directly. */
    private static final java.util.Map<String, RolePreset> REGISTRY = new java.util.LinkedHashMap<>();
    static {
        REGISTRY.put("explore",          EXPLORE);
        REGISTRY.put("general-purpose",  GENERAL_PURPOSE);
        REGISTRY.put("coder",            CODER);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
