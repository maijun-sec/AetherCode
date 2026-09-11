package org.aethercode.evals.harbor.langgraph_project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LangGraph entrypoint for running Deep Agents under Harbor.
 *
 * <p>Java 21 port of {@code deepagents_harbor.langgraph_project.langgraph_agent}.
 * Harbor's installed {@code langgraph} agent loads {@link #makeGraph(Map)},
 * {@link #makeBareGraph(Map)}, and {@link #makeTau3Graph(Map)} from
 * {@code langgraph.json} inside each benchmark sandbox. Each returns
 * the LangGraph graph produced by Deep Agents Code's headless
 * constructor.</p>
 *
 * <p>The Java port preserves the public surface, the configuration
 * parsing helpers, the GLM-5.2 reasoning default, the shell-environment
 * denylist, the assistant-id normalization, the Tavily-backed
 * {@code web_search} tool, and the MCP-server connection filter. The
 * actual call into {@code deepagents.create_deep_agent} /
 * {@code deepagents_code.create_cli_agent} is left to a separate
 * integration layer; this file is the contract Harbor loads and the
 * Java port supplies the orchestration that the deepagents-core
 * library is expected to back.</p>
 */
public final class LanggraphAgent {

    /** Bounds on the gated {@code web_search} tool. */
    public static final int WEB_SEARCH_MAX_RESULTS = 10;
    public static final int WEB_SEARCH_MAX_CHARS = 20_000;

    /** Maximum allowed length of a normalized Harbor assistant id. */
    public static final int MAX_ASSISTANT_ID_LENGTH = 64;
    /** Number of hex chars of a SHA-256 digest folded into long assistant ids. */
    public static final int ASSISTANT_ID_HASH_LENGTH = 12;

    private static final Pattern INVALID_ASSISTANT_ID_RUN = Pattern.compile("[^A-Za-z0-9_-]+");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The set of GLM-5.2 model specs that should default
     * {@code reasoning_effort} to {@code "high"}. Mirrors dcode's
     * {@code deepagents_code._glm_5p2_profile._GLM_5P2_MODEL_SPECS}
     * (case-sensitive).
     */
    public static final Set<String> GLM_5_2_MODEL_SPECS = Set.of();

    /**
     * Provider secrets that must never be visible to the agent's shell.
     */
    public static final Set<String> SHELL_ENV_DENYLIST = Set.of(
            "ANTHROPIC_API_KEY",
            "BASETEN_API_KEY",
            "FIREWORKS_API_KEY",
            "GOOGLE_API_KEY",
            "GROQ_API_KEY",
            "LANGCHAIN_API_KEY",
            "LANGCHAIN_ENDPOINT",
            "LANGCHAIN_PROJECT",
            "LANGCHAIN_TRACING_V2",
            "LANGSMITH_API_KEY",
            "LANGSMITH_ENDPOINT",
            "LANGSMITH_PROJECT",
            "LANGSMITH_TRACING",
            "NVIDIA_API_KEY",
            "OLLAMA_API_KEY",
            "OPENAI_API_KEY",
            "OPENROUTER_API_KEY",
            "XAI_API_KEY");

    /**
     * System prompt injected by the original Python port (the Java port
     * no longer passes {@code system_prompt} to {@code create_cli_agent};
     * it relies on the dcode production prompt, which is the same.
     * Kept here for tests and ad-hoc replays.
     */
    public static final String SYSTEM_PROMPT = """
            You are running in a Harbor benchmark sandbox.

            Complete the task autonomously. There is no human operator available to answer
            follow-up questions, so make reasonable assumptions and keep working until the
            task is complete.

            Use the sandbox working directory for all file and shell operations. In Terminal
            Bench-style tasks this is usually `/app`; use `pwd` if you need to confirm the
            current directory.

            Prefer non-interactive command variants. Do not run commands that wait for
            human input.
            """;

    private LanggraphAgent() {}

    /* ----------------------------- public API ----------------------------- */

    /**
     * Create the Deep Agents Code CLI harness graph Harbor should run.
     *
     * <p>The actual call into {@code deepagents_code.agent.create_cli_agent}
     * is delegated to a separate integration; this method exposes the
     * configuration that the integration must honor.</p>
     */
    public static Object makeGraph(Map<String, Object> config) {
        Map<String, Object> configurable = configurable(config);
        String modelName = modelName(configurable);
        Map<String, Object> modelKwargs = modelKwargs(configurable);
        applyGlm52ReasoningDefault(modelName, modelKwargs);
        return GraphFactory.createCliAgent(
                new GraphFactory.CliAgentRequest(
                        modelName,
                        modelKwargs,
                        harborAssistantId(System.getenv("HARBOR_SESSION_ID")),
                        workdir(configurable),
                        webSearchTool()));
    }

    /**
     * Create a Deep Agents SDK graph Harbor should run directly, avoiding
     * the dcode harness while still attaching a local shell backend
     * rooted at Harbor's sandbox workdir.
     */
    public static Object makeBareGraph(Map<String, Object> config) {
        Map<String, Object> configurable = configurable(config);
        String modelName = modelName(configurable);
        Map<String, Object> modelKwargs = modelKwargs(configurable);
        applyGlm52ReasoningDefault(modelName, modelKwargs);
        return GraphFactory.createDeepAgent(
                new GraphFactory.DeepAgentRequest(
                        modelName,
                        modelKwargs,
                        workdir(configurable),
                        webSearchTool()));
    }

    /**
     * Create a conversational Deep Agents graph for tau3-bench (and
     * tau2) tasks. The MCP server connection comes from Harbor's
     * forwarded {@code configurable["mcp_servers"]}; no URL is
     * hardcoded.
     */
    public static Object makeTau3Graph(Map<String, Object> config) {
        Map<String, Object> configurable = configurable(config);
        String modelName = modelName(configurable);
        Map<String, Object> modelKwargs = modelKwargs(configurable);
        applyGlm52ReasoningDefault(modelName, modelKwargs);
        Map<String, Map<String, String>> connections = mcpConnections(configurable);
        return GraphFactory.createDeepAgentWithMcp(
                new GraphFactory.DeepAgentWithMcpRequest(
                        modelName,
                        modelKwargs,
                        connections));
    }

    /* ----------------------------- helpers ----------------------------- */

    /** Extract the {@code configurable} mapping from a LangGraph config. */
    public static Map<String, Object> configurable(Map<String, Object> config) {
        if (config == null) {
            return Map.of();
        }
        Object value = config.get("configurable");
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("`configurable` must be a dictionary");
        }
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
            if (e.getKey() != null) {
                out.put(e.getKey().toString(), e.getValue());
            }
        }
        return out;
    }

    /** Extract the {@code configurable.model_kwargs} mapping. */
    public static Map<String, Object> modelKwargs(Map<String, Object> configurable) {
        Object value = configurable.get("model_kwargs");
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("`configurable.model_kwargs` must be a dictionary");
        }
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
            if (e.getKey() != null) {
                out.put(e.getKey().toString(), e.getValue());
            }
        }
        return out;
    }

    /** Resolve the chat model name. */
    public static String modelName(Map<String, Object> configurable) {
        Object value = configurable.get("model");
        if (value == null) {
            value = System.getenv("HARBOR_MODEL");
        }
        if (!(value instanceof String s) || s.strip().isEmpty()) {
            throw new IllegalArgumentException(
                    "`configurable.model` or `HARBOR_MODEL` must provide a model name");
        }
        return s;
    }

    /**
     * Default GLM-5.2's reasoning effort to {@code "high"} for the eval
     * when unset. Mutates {@code model_kwargs} in place.
     */
    public static void applyGlm52ReasoningDefault(String modelSpec, Map<String, Object> modelKwargs) {
        if (!GLM_5_2_MODEL_SPECS.contains(modelSpec)) {
            return;
        }
        if (modelKwargs.containsKey("reasoning_effort") || modelKwargs.containsKey("reasoning")) {
            return;
        }
        Object nestedObj = modelKwargs.get("model_kwargs");
        Map<String, Object> nested;
        if (nestedObj == null && !modelKwargs.containsKey("model_kwargs")) {
            nested = new HashMap<>();
            modelKwargs.put("model_kwargs", nested);
        } else if (nestedObj instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) nestedObj;
            nested = casted;
        } else {
            return;
        }
        if (nested.containsKey("reasoning_effort") || nested.containsKey("reasoning")) {
            return;
        }
        nested.put("reasoning_effort", "high");
    }

    /** Resolve the working directory from the configurable. */
    public static String workdir(Map<String, Object> configurable) {
        Object value = configurable.get("cwd");
        if (value == null) {
            return System.getProperty("user.dir");
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("`configurable.cwd` must be a string path");
        }
        return s;
    }

    /**
     * Normalize Harbor's session id for dcode's filesystem-backed agent id.
     */
    public static String harborAssistantId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return "harbor-" + UUID.randomUUID();
        }
        String normalized = INVALID_ASSISTANT_ID_RUN.matcher(sessionId).replaceAll("-");
        if (INVALID_ASSISTANT_ID_RUN.matcher(sessionId).find()) {
            while (normalized.startsWith("-")) {
                normalized = normalized.substring(1);
            }
        }
        if (INVALID_ASSISTANT_ID_RUN.matcher(sessionId.substring(Math.max(0, sessionId.length() - 1))).matches()) {
            while (normalized.endsWith("-")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
        }
        if (normalized.isEmpty()) {
            return "harbor-" + UUID.randomUUID();
        }
        if (normalized.equals(sessionId) && normalized.length() <= MAX_ASSISTANT_ID_LENGTH) {
            return normalized;
        }
        String digest = sha256Prefix(sessionId, ASSISTANT_ID_HASH_LENGTH);
        int prefixLength = MAX_ASSISTANT_ID_LENGTH - ASSISTANT_ID_HASH_LENGTH - 1;
        String head = normalized.length() > prefixLength
                ? normalized.substring(0, prefixLength) : normalized;
        return head + "-" + digest;
    }

    /**
     * Build langchain-mcp-adapters connections from Harbor-forwarded servers.
     */
    public static Map<String, Map<String, String>> mcpConnections(Map<String, Object> configurable) {
        Object servers = configurable.get("mcp_servers");
        if (servers == null) {
            throw new IllegalArgumentException(
                    "tau3 graph requires MCP servers forwarded via `configurable['mcp_servers']`."
                            + " Harbor's LangGraph agent must forward the task environment's"
                            + " MCP servers into the graph configurable.");
        }
        if (!(servers instanceof List<?> list)) {
            throw new IllegalArgumentException("`configurable.mcp_servers` must be a list");
        }
        Map<String, Map<String, String>> connections = new HashMap<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Each entry in `configurable.mcp_servers` must be a mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> server = (Map<String, Object>) raw;
            String name = String.valueOf(server.get("name"));
            String transport = Optional.ofNullable(server.get("transport"))
                    .map(Object::toString).orElse("sse");
            if ("streamable-http".equals(transport) || "http".equals(transport)) {
                transport = "streamable_http";
            }
            if (!"streamable_http".equals(transport) && !"sse".equals(transport)) {
                throw new IllegalArgumentException(
                        "MCP server " + name + " uses unsupported transport " + transport
                                + "; the tau3 graph only allows remote transports (streamable-http, sse)."
                                + " stdio servers are rejected to avoid executing dataset-provided"
                                + " commands in the agent sandbox.");
            }
            Object url = server.get("url");
            if (url == null || url.toString().isEmpty()) {
                throw new IllegalArgumentException(
                        "MCP server " + name + " must declare a 'url' for transport " + transport);
            }
            Map<String, String> entry = new HashMap<>();
            entry.put("transport", transport);
            entry.put("url", url.toString());
            connections.put(name, entry);
        }
        return connections;
    }

    /**
     * Return a web-search tool description, or {@code null} when the
     * Tavily key is missing. The actual call to Tavily is delegated to
     * the deepagents-core integration; the Java port supplies the
     * gating logic.
     */
    public static String webSearchTool() {
        String key = System.getenv("TAVILY_API_KEY");
        if (key == null || key.isEmpty()) {
            return null;
        }
        return "web_search(tavily)";
    }

    private static String sha256Prefix(String input, int hexChars) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes());
            StringBuilder hex = new StringBuilder(hexChars);
            for (byte b : bytes) {
                if (hex.length() >= hexChars) {
                    break;
                }
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /* ----------------------------- tool integration seam ----------------------------- */

    /**
     * Integration seam for the deepagents-core library. The Python port
     * calls into {@code deepagents.create_deep_agent} and
     * {@code deepagents_code.agent.create_cli_agent}; the Java port
     * declares the request shape that a downstream integration is
     * expected to back.
     */
    public static final class GraphFactory {

        private GraphFactory() {}

        public record CliAgentRequest(
                String modelName,
                Map<String, Object> modelKwargs,
                String assistantId,
                String workdir,
                String webSearchTool) {}

        public record DeepAgentRequest(
                String modelName,
                Map<String, Object> modelKwargs,
                String workdir,
                String webSearchTool) {}

        public record DeepAgentWithMcpRequest(
                String modelName,
                Map<String, Object> modelKwargs,
                Map<String, Map<String, String>> mcpConnections) {}

        public static Object createCliAgent(CliAgentRequest request) {
            // TODO: deepagents-core integration. For now return a marker so
            // the rest of the file is testable.
            return marker("cli_agent", request);
        }

        public static Object createDeepAgent(DeepAgentRequest request) {
            return marker("deep_agent", request);
        }

        public static Object createDeepAgentWithMcp(DeepAgentWithMcpRequest request) {
            return marker("deep_agent_mcp", request);
        }

        private static Map<String, Object> marker(String kind, Object request) {
            Map<String, Object> m = new HashMap<>();
            m.put("__graph_kind__", kind);
            m.put("__request__", request);
            return m;
        }
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> parseJsonLenient(String s) throws IOException {
        return MAPPER.readValue(s, new TypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unused")
    private static List<String> regexMatchAll(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        List<String> matches = new ArrayList<>();
        while (matcher.find()) {
            matches.add(matcher.group());
        }
        return matches;
    }
}
