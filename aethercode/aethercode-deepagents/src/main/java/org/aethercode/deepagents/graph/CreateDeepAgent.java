package org.aethercode.deepagents.graph;

import org.aethercode.core.middleware.SkillsPrompts;

import org.aethercode.core.fs.backend.BackendProtocol;
import org.aethercode.core.fs.backend.StateBackend;
import org.aethercode.deepagents.middleware.AsyncSubAgent;
import org.aethercode.deepagents.middleware.AsyncSubAgentMiddleware;
import org.aethercode.deepagents.middleware.CompiledSubAgent;
import org.aethercode.deepagents.middleware.FilesystemMiddleware;
import org.aethercode.core.middleware.FilesystemPermission;
import org.aethercode.deepagents.middleware.HumanInTheLoopMiddleware;
import org.aethercode.deepagents.middleware.MemoryMiddleware;
import org.aethercode.deepagents.middleware.Middleware;
import org.aethercode.deepagents.tools.MiddlewareExclusion;
import org.aethercode.deepagents.middleware.PatchToolCallsMiddleware;
import org.aethercode.deepagents.middleware.PromptCachingMiddleware;
import org.aethercode.deepagents.middleware.PromptCachingProviderRegistry;
import org.aethercode.core.middleware.SkillSource;
import org.aethercode.deepagents.middleware.SkillsMiddleware;
import org.aethercode.deepagents.middleware.SubAgent;
import org.aethercode.deepagents.middleware.SubAgentMiddleware;
import org.aethercode.deepagents.middleware.SubAgentPrompts;
import org.aethercode.deepagents.middleware.SummarizationMiddleware;
import org.aethercode.deepagents.middleware.ToolExclusionMiddleware;
import org.aethercode.deepagents.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Graph-assembly module for deep agents.
 *
 * <p>Java-native port of
 * {@code deepagents.graph.create_deep_agent}. The entry point
 * assembles a deep agent by stacking middleware (skills,
 * filesystem, subagents, summarization, patch, async,
 * memory, exclusion, prompt caching, ...) in the same order
 * the Python port uses, and returns a
 * {@link DeepAgent} record carrying the resulting state.</p>
 *
 * <p>The actual langgraph-style {@code CompiledStateGraph}
 * compilation is deferred to a future round (prior round) when the
 * chat-model adapter is in place. The Java port returns a
 * {@link DeepAgent} record so callers can introspect the
 * assembled middleware chain today and invoke the agent through
 * the stubbed entry points.</p>
 */
public final class CreateDeepAgent {
    private static final Logger LOGGER = Logger.getLogger(CreateDeepAgent.class.getName());

    private CreateDeepAgent() {}

    /**
     * Build a deep agent.
     *
     * @param model model spec (e.g. {@code "openai:gpt-5.5"}) or a
     *              chat-model object. Stored verbatim; resolution
     *              to a real chat model is R3's job.
     * @param tools consumer-provided tools
     * @param systemPrompt optional system-prompt fragment
     * @param middleware additional user middleware
     * @param subagents subagent specs (any combination of
     *                 {@link SubAgent}, {@link CompiledSubAgent},
     *                 {@link AsyncSubAgent})
     * @param skills skill source paths
     * @param memory memory source paths (e.g. {@code AGENTS.md} files)
     * @param permissions filesystem permission rules
     * @param backend backend for file operations
     * @param interruptOn map of tool-name to interrupt config
     * @param responseFormat structured-output response format
     * @param stateSchema optional custom state schema
     * @param contextSchema optional run-context schema
     * @param name agent name (default: {@code "deep_agent"})
     * @return the assembled {@link DeepAgent}
     */
    public static DeepAgent create(Object model,
                                    List<Tool> tools,
                                    String systemPrompt,
                                    List<Middleware> middleware,
                                    List<?> subagents,
                                    List<SkillSource> skills,
                                    List<String> memory,
                                    List<FilesystemPermission> permissions,
                                    BackendProtocol backend,
                                    Map<String, ?> interruptOn,
                                    Object responseFormat,
                                    Class<?> stateSchema,
                                    Class<?> contextSchema,
                                    String name) {
        Objects.requireNonNull(model, "model");
        BackendProtocol resolvedBackend = backend == null ? new StateBackend() : backend;
        String resolvedName = name == null ? "deep_agent" : name;

        // 1) Base tools (consumer-provided + filesystem tools).
        FilesystemMiddleware fsMiddleware = new FilesystemMiddleware(
                resolvedBackend, permissions == null ? List.of() : permissions);
        // If the user supplied a FilesystemMiddleware with a tool
        // allowlist, use ITS toolset instead of the default — matches
        // the Python port's behavior where the user-supplied
        // `tools=[...]` filter is the source of truth.
        FilesystemMiddleware userFsWithAllowlist = findFilesystemMiddlewareWithAllowlist(middleware);
        FilesystemMiddleware effectiveFs = userFsWithAllowlist != null ? userFsWithAllowlist : fsMiddleware;
        List<Tool> allTools = new ArrayList<>();
        if (tools != null) allTools.addAll(tools);
        allTools.addAll(effectiveFs.toolset().tools().values());

        // 2) Build the middleware stack in the Python port's order.
        List<Middleware> chain = new ArrayList<>();

        // Base stack:
        if (skills != null && !skills.isEmpty()) {
            // R3 fix: pass the default skills system-prompt template so
            // wrapModelCall actually injects the loaded skill list into
            // the model-visible system prompt. The 2-arg SkillsMiddleware
            // constructor uses SkillsPrompts.SKILLS_SYSTEM_PROMPT
            // internally; the previous 3-arg call with a {@code null}
            // template caused wrapModelCall to short-circuit and skip
            // the injection (see the "template is null" early return in
            // SkillsMiddleware.wrapModelCall).
            chain.add(new SkillsMiddleware(resolvedBackend, skills));
        }
        chain.add(fsMiddleware);

        // Subagents: separate inline (SubAgent/CompiledSubAgent) from async (AsyncSubAgent).
        List<SubAgent> inlineSubagents = new ArrayList<>();
        List<CompiledSubAgent> compiledSubagents = new ArrayList<>();
        List<AsyncSubAgent> asyncSubagents = new ArrayList<>();
        if (subagents != null) {
            for (Object s : subagents) {
                if (s instanceof SubAgent sa) inlineSubagents.add(sa);
                else if (s instanceof CompiledSubAgent csa) compiledSubagents.add(csa);
                else if (s instanceof AsyncSubAgent asa) asyncSubagents.add(asa);
            }
        }
        // Auto-add a general-purpose subagent if none provided and no harness
        // profile has disabled it. The Java port defaults to enabled.
        boolean hasGeneralPurpose = inlineSubagents.stream()
                .anyMatch(s -> "general-purpose".equals(s.name()));
        if (!hasGeneralPurpose && inlineSubagents.isEmpty() && compiledSubagents.isEmpty()) {
            inlineSubagents.add(SubAgentPrompts.GENERAL_PURPOSE_SUBAGENT);
        }
        if (!inlineSubagents.isEmpty() || !compiledSubagents.isEmpty()) {
            List<Object> allInline = new ArrayList<>();
            allInline.addAll(inlineSubagents);
            allInline.addAll(compiledSubagents);
            chain.add(new SubAgentMiddleware(resolvedBackend, allInline));
        }
        if (!asyncSubagents.isEmpty()) {
            chain.add(new AsyncSubAgentMiddleware(asyncSubagents));
        }

        // Summarization (with a default config).
        chain.add(new SummarizationMiddleware(resolvedBackend));

        // PatchToolCalls: always added.
        chain.add(new PatchToolCallsMiddleware());

        // 3) User middleware.
        if (middleware != null) chain.addAll(middleware);

        // 4) Tail stack: tool exclusion, prompt caching, memory, hitl.
        if (interruptOn != null && !interruptOn.isEmpty()) {
            chain.add(new HumanInTheLoopMiddleware(interruptOn));
        }
        PromptCachingProviderRegistry.appendTo(chain,
                PromptCachingMiddleware.UnsupportedModelBehavior.IGNORE);
        if (memory != null && !memory.isEmpty()) {
            chain.add(new MemoryMiddleware(resolvedBackend, memory));
        }

        // 5) State schema.
        Class<?> resolvedStateSchema = stateSchema == null ? DeepAgentState.class : stateSchema;

        // 6) Compose the system prompt: the caller's fragment, then
        // the FilesystemMiddleware's custom fragment, then the
        // route-host-path hint when the execute tool is active.
        // Mirrors the Python port's
        // `_filter_unsupported_tools_and_apply_prompt` so the
        // middleware's system_prompt parameter (and the host-path
        // route prompt) actually reach the model.
        StringBuilder composed = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            composed.append(systemPrompt);
        }
        for (Middleware m : chain) {
            if (m instanceof FilesystemMiddleware fs && !fs.systemPromptFragment().isEmpty()) {
                if (composed.length() > 0) composed.append("\n\n");
                composed.append(fs.systemPromptFragment());
            }
        }
        // Append the host-path route prompt when execute is active.
        boolean executeActive = allTools.stream()
                .anyMatch(t -> "execute".equals(t.name()));
        if (executeActive) {
            String routePrompt = FilesystemMiddleware.routeHostPathPrompt(resolvedBackend);
            if (!routePrompt.isEmpty()) {
                if (composed.length() > 0) composed.append("\n\n");
                composed.append(routePrompt);
            }
        }
        String finalSystemPrompt = composed.length() == 0 ? null : composed.toString();

        DeepAgentState initial = DeepAgentState.empty();
        // Derive the provider tag from a "provider:model" spec; null
        // for chat-model objects (the model itself carries the tag).
        String provider = extractProviderTag(model);
        return new DeepAgent(
                resolvedName,
                model,
                resolvedBackend,
                allTools,
                chain,
                finalSystemPrompt,
                initial,
                null,
                provider);
    }

    /**
     * Extract a lower-cased provider tag from a model spec. Accepts:
     * <ul>
     *   <li>{@code "provider:model"} &mdash; returns {@code "provider"}.</li>
     *   <li>Other strings &mdash; returns {@code null} (callers should
     *       pass a real chat model with the tag attached).</li>
     *   <li>Non-string objects &mdash; returns {@code null}.</li>
     * </ul>
     * Mirrors the Python port's
     * {@code ModelResolver.getModelProvider}.
     */
    static String extractProviderTag(Object model) {
        if (!(model instanceof String s)) return null;
        int colon = s.indexOf(':');
        if (colon <= 0) return null;
        String tag = s.substring(0, colon).trim().toLowerCase();
        return tag.isEmpty() ? null : tag;
    }

    /** Convenience overload with the most common arguments. */
    public static DeepAgent create(Object model) {
        return create(model, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    /** Convenience overload with the most common arguments. */
    public static DeepAgent create(Object model, List<Tool> tools, BackendProtocol backend) {
        return create(model, tools, null, null, null, null, null,
                null, backend, null, null, null, null, null);
    }

    /**
     * Alias for {@link #create(Object)} using the Java-native
     * camelCase form. The Python port exposes this as
     * {@code create_deep_agent}; both names refer to the same
     * factory.
     */
    public static DeepAgent createDeepAgent(Object model) {
        return create(model);
    }

    /** CamelCase alias for {@link #create(Object, List, BackendProtocol)}. */
    public static DeepAgent createDeepAgent(Object model,
                                            List<Tool> tools,
                                            BackendProtocol backend) {
        return create(model, tools, backend);
    }

    /** CamelCase alias for the full-args factory. */
    public static DeepAgent createDeepAgent(Object model,
                                            List<Tool> tools,
                                            String systemPrompt,
                                            List<Middleware> middleware,
                                            List<?> subagents,
                                            List<SkillSource> skills,
                                            List<String> memory,
                                            List<FilesystemPermission> permissions,
                                            BackendProtocol backend,
                                            Map<String, ?> interruptOn,
                                            Object responseFormat,
                                            Class<?> stateSchema,
                                            Class<?> contextSchema,
                                            String name) {
        return create(model, tools, systemPrompt, middleware, subagents, skills,
                memory, permissions, backend, interruptOn, responseFormat,
                stateSchema, contextSchema, name);
    }

    /**
     * Walk the user-supplied middleware list looking for a
     * {@link FilesystemMiddleware} with a non-null tool allowlist.
     * Returns the first such middleware, or null if none.
     */
    private static FilesystemMiddleware findFilesystemMiddlewareWithAllowlist(
            List<Middleware> middleware) {
        if (middleware == null) return null;
        for (Middleware m : middleware) {
            if (m instanceof FilesystemMiddleware fsm && fsm.toolset().enabledTools() != null) {
                return fsm;
            }
        }
        return null;
    }
}
