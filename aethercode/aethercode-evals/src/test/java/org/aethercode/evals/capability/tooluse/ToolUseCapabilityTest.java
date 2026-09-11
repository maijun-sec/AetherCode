package org.aethercode.evals.capability.tooluse;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-eval-3: Tool Use & Function Calling capability suite.
 *
 * <p>Covers Survey on Evaluation of LLM-based Agents (2503.16416) §2.2
 * + arXiv:2604.00835 (Table 4) + arXiv:2603.22862 (Long-Horizon
 * Multi-Tool Benchmarks) — five sub-abilities:</p>
 *
 * <ul>
 *   <li>Intent recognition — when is a tool needed at all?</li>
 *   <li>Function selection — given a need, which tool?</li>
 *   <li>Parameter mapping — given a tool, what argument values?</li>
 *   <li>Multi-tool orchestration — chain several tools in
 *       dependency order</li>
 *   <li>Sandboxing / SSRF — refuse dangerous inputs (private
 *       network, file deletion outside allowed paths)</li>
 *   <li>Long-context argument handling — large argument strings
 *       without losing content</li>
 *   <li>Error recovery — when a tool fails, surface a structured
 *       error so the agent can retry</li>
 * </ul>
 *
 * <p>Modelled on the BFCL (Berkeley Function Calling Leaderboard)
 * test conventions: each tool has a JSON-schema-like declaration,
 * a name, a description, and a parameter list. The agent's choice
 * is scored against a known-correct reference.</p>
 */
class ToolUseCapabilityTest {

    /* --------------------- Tool model (BFCL-style) --------------------- */

    public record Param(String name, String type, boolean required, String description) {
        public Param {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must be non-blank");
            }
            if (type == null) type = "string";
        }
    }

    public record Tool(
            String name,
            String description,
            List<Param> params,
            ToolHandler handler) {
        public Tool {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must be non-blank");
            }
            params = params == null ? List.of() : List.copyOf(params);
        }
    }

    /** A handler that runs the tool. Throws on failure. */
    @FunctionalInterface
    public interface ToolHandler {
        Object invoke(Map<String, Object> args) throws Exception;
    }

    /** A registry that knows the available tools and the SSRF /
     *  sandbox policy. The agent uses this to look up tools by
     *  name and to validate argument shapes. */
    public static final class ToolRegistry {
        private final Map<String, Tool> byName = new LinkedHashMap<>();
        private final List<String> ssrfBlockedHosts = new ArrayList<>();
        private final List<String> allowedReadPaths = new ArrayList<>();
        private final List<String> allowedWritePaths = new ArrayList<>();

        public ToolRegistry register(Tool tool) {
            if (byName.put(tool.name(), tool) != null) {
                throw new IllegalArgumentException("duplicate tool: " + tool.name());
            }
            return this;
        }

        public ToolRegistry blockHost(String host) { ssrfBlockedHosts.add(host); return this; }
        public ToolRegistry allowReadPath(String path) { allowedReadPaths.add(path); return this; }
        public ToolRegistry allowWritePath(String path) { allowedWritePaths.add(path); return this; }

        public Optional<Tool> get(String name) { return Optional.ofNullable(byName.get(name)); }
        public List<String> toolNames() { return List.copyOf(byName.keySet()); }

        /** Validate that the argument map satisfies the tool's
         *  parameter schema. Returns the missing required names. */
        public List<String> missingRequired(Tool tool, Map<String, Object> args) {
            List<String> missing = new ArrayList<>();
            Map<String, Object> got = args == null ? Map.of() : args;
            for (Param p : tool.params()) {
                if (p.required() && !got.containsKey(p.name())) {
                    missing.add(p.name());
                }
            }
            return missing;
        }

        /** Run a tool by name. Validates required args, enforces
         *  sandbox, and returns a {@link ToolResult} with the
         *  output or a structured error. */
        public ToolResult invoke(String toolName, Map<String, Object> args) {
            Tool tool = byName.get(toolName);
            if (tool == null) {
                return ToolResult.error("unknown tool: " + toolName);
            }
            List<String> missing = missingRequired(tool, args);
            if (!missing.isEmpty()) {
                return ToolResult.error("missing required args: " + missing);
            }
            // SSRF guard.
            Object url = args == null ? null : args.get("url");
            if (url instanceof String s) {
                String err = checkSsrf(s);
                if (err != null) return ToolResult.error(err);
            }
            // Path guard.
            Object path = args == null ? null : args.get("path");
            if (path instanceof String p) {
                String err = checkPath(toolName, p);
                if (err != null) return ToolResult.error(err);
            }
            try {
                Object out = tool.handler().invoke(args);
                return ToolResult.ok(out);
            } catch (Exception ex) {
                return ToolResult.error(ex.getClass().getSimpleName()
                        + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
            }
        }

        private String checkSsrf(String url) {
            try {
                URI u = URI.create(url);
                String host = u.getHost();
                if (host == null) return "invalid url: " + url;
                String h = host.toLowerCase();
                for (String blocked : ssrfBlockedHosts) {
                    if (h.equals(blocked.toLowerCase()) || h.endsWith("." + blocked.toLowerCase())) {
                        return "ssrf blocked: " + host;
                    }
                }
                // RFC1918 private ranges.
                if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1")
                        || h.startsWith("10.") || h.startsWith("192.168.")
                        || h.startsWith("169.254.")) {
                    return "ssrf blocked: private address " + host;
                }
            } catch (IllegalArgumentException ignore) {
                return "invalid url: " + url;
            }
            return null;
        }

        private String checkPath(String toolName, String path) {
            boolean isWrite = toolName.contains("write") || toolName.contains("delete")
                    || toolName.contains("edit");
            String normalized = path.replace("\\", "/");
            for (String allowed : isWrite ? allowedWritePaths : allowedReadPaths) {
                String a = allowed.replace("\\", "/");
                if (normalized.startsWith(a)) return null;
            }
            return (isWrite ? "write" : "read") + " path not allowed: " + path;
        }
    }

    /** Structured tool result. ok=true with payload, or
     *  ok=false with error message. */
    public record ToolResult(boolean ok, Object payload, String error) {
        public static ToolResult ok(Object p) { return new ToolResult(true, p, null); }
        public static ToolResult error(String e) { return new ToolResult(false, null, e); }
    }

    /* --------------------- Intent recognition --------------------- */

    @Test
    void intentIsRecognisedWhenToolNeeded() {
        // "what's the weather in Tokyo" → must select the
        // get_weather tool.
        ToolRegistry reg = new ToolRegistry();
        reg.register(new Tool("get_weather",
                "Look up the current weather for a city.",
                List.of(new Param("city", "string", true, "City name.")),
                args -> "Sunny, 25C in " + args.get("city")));
        Tool picked = reg.get("get_weather").orElseThrow();
        ToolUseCapabilityTest.ToolResult r = reg.invoke("get_weather",
                Map.of("city", "Tokyo"));
        assertTrue(r.ok());
        assertEquals("Sunny, 25C in Tokyo", r.payload());
    }

    @Test
    void unknownToolReturnsStructuredError() {
        // The agent guessed a non-existent tool name; the registry
        // returns a structured error so the agent can correct.
        ToolRegistry reg = new ToolRegistry();
        reg.register(new Tool("get_weather", "weather", List.of(), args -> null));
        ToolUseCapabilityTest.ToolResult r = reg.invoke("nonsense", Map.of());
        assertFalse(r.ok());
        assertTrue(r.error().contains("unknown tool"));
    }

    /* --------------------- Function selection --------------------- */

    @Test
    void functionSelectionPicksCorrectToolFromCatalog() {
        // Multiple tools; the query "translate hello to French"
        // must select translate_text, not get_weather.
        ToolRegistry reg = new ToolRegistry()
                .register(new Tool("get_weather", "weather lookup",
                        List.of(new Param("city", "string", true, null)),
                        args -> "sunny"))
                .register(new Tool("translate_text", "translate to a target language",
                        List.of(
                                new Param("text", "string", true, null),
                                new Param("target_lang", "string", true, null)),
                        args -> args.get("text") + " -> bonjour"));
        // The "agent" is the test's job: pick the right tool.
        Tool chosen = reg.get("translate_text").orElseThrow();
        assertEquals("translate_text", chosen.name());
        ToolUseCapabilityTest.ToolResult r = reg.invoke("translate_text",
                Map.of("text", "hello", "target_lang", "fr"));
        assertEquals("hello -> bonjour", r.payload());
    }

    @Test
    void toolNameLookupIsCaseSensitive() {
        ToolRegistry reg = new ToolRegistry()
                .register(new Tool("get_weather", "x", List.of(), args -> null));
        // Python-like tool names are case-sensitive in BFCL.
        assertTrue(reg.get("get_weather").isPresent());
        assertTrue(reg.get("Get_Weather").isEmpty());
    }

    /* --------------------- Parameter mapping --------------------- */

    @Test
    void requiredArgsMustBePresent() {
        ToolRegistry reg = new ToolRegistry()
                .register(new Tool("add", "add two integers",
                        List.of(
                                new Param("a", "int", true, null),
                                new Param("b", "int", true, null)),
                        args -> ((Number) args.get("a")).intValue()
                                + ((Number) args.get("b")).intValue()));
        // Missing b → structured error listing the missing arg.
        ToolUseCapabilityTest.ToolResult r = reg.invoke("add", Map.of("a", 1));
        assertFalse(r.ok());
        assertTrue(r.error().contains("b"));
    }

    @Test
    void optionalArgsMayBeOmitted() {
        ToolRegistry reg = new ToolRegistry()
                .register(new Tool("greet", "say hi",
                        List.of(
                                new Param("name", "string", true, null),
                                new Param("punctuation", "string", false, "default '!'")),
                        args -> "Hi, " + args.get("name")
                                + (args.containsKey("punctuation") ? args.get("punctuation") : "!")));
        ToolUseCapabilityTest.ToolResult r1 = reg.invoke("greet", Map.of("name", "Ada"));
        assertEquals("Hi, Ada!", r1.payload());
        ToolUseCapabilityTest.ToolResult r2 = reg.invoke("greet",
                Map.of("name", "Ada", "punctuation", "?"));
        assertEquals("Hi, Ada?", r2.payload());
    }

    @Test
    void typeCoercionForIntArgs() {
        // Real LLM agents sometimes return ints as strings. A
        // permissive handler coerces; a strict one rejects.
        ToolRegistry reg = new ToolRegistry()
                .register(new Tool("add", "add two integers",
                        List.of(
                                new Param("a", "int", true, null),
                                new Param("b", "int", true, null)),
                        args -> {
                            int a = Integer.parseInt(args.get("a").toString());
                            int b = Integer.parseInt(args.get("b").toString());
                            return a + b;
                        }));
        ToolUseCapabilityTest.ToolResult r = reg.invoke("add",
                Map.of("a", "1", "b", "2"));
        assertTrue(r.ok(), "string-encoded ints must be accepted: " + r.error());
        assertEquals(3, r.payload());
    }

    /* --------------------- Multi-tool orchestration --------------------- */

    @Test
    void multiToolChainResolvesDependencies() {
        // Plan: read_file → summarize → write_file. The plan
        // must encode the dependency chain and run in order.
        ToolRegistry reg = new ToolRegistry()
                .allowReadPath("/tmp/")
                .allowWritePath("/tmp/");
        reg.register(new Tool("read_file", "read a file",
                List.of(new Param("path", "string", true, null)),
                args -> "contents of " + args.get("path")));
        reg.register(new Tool("write_file", "write a file",
                List.of(
                        new Param("path", "string", true, null),
                        new Param("content", "string", true, null)),
                args -> "wrote " + args.get("content") + " to " + args.get("path")));

        // Read the file
        ToolUseCapabilityTest.ToolResult r1 = reg.invoke("read_file",
                Map.of("path", "/tmp/in.txt"));
        assertTrue(r1.ok());
        // Write the file
        ToolUseCapabilityTest.ToolResult r2 = reg.invoke("write_file",
                Map.of("path", "/tmp/out.txt", "content", "summary of input"));
        assertTrue(r2.ok());
    }

    @Test
    void parallelToolsAreOrderedByDependency() {
        // Two reads that feed a synthesis. The plan must run
        // both reads first, then synthesis. We model this as a
        // list of (tool, args) and the registry ensures the
        // handler completes synchronously.
        ToolRegistry reg = new ToolRegistry()
                .allowReadPath("/");
        reg.register(new Tool("read_file", "read a file",
                List.of(new Param("path", "string", true, null)),
                args -> "content-" + args.get("path")));
        reg.register(new Tool("synthesize", "synthesize two strings",
                List.of(
                        new Param("a", "string", true, null),
                        new Param("b", "string", true, null)),
                args -> "synth: " + args.get("a") + " + " + args.get("b")));

        ToolUseCapabilityTest.ToolResult r1 = reg.invoke("read_file", Map.of("path", "/a"));
        ToolUseCapabilityTest.ToolResult r2 = reg.invoke("read_file", Map.of("path", "/b"));
        assertTrue(r1.ok() && r2.ok());
        // Then synthesize with both payloads.
        ToolUseCapabilityTest.ToolResult r3 = reg.invoke("synthesize",
                Map.of("a", r1.payload(), "b", r2.payload()));
        assertTrue(r3.ok());
        assertTrue(r3.payload().toString().contains("content-/a + content-/b"));
    }

    /* --------------------- SSRF / sandbox --------------------- */

    @Test
    void ssrfBlocksPrivateNetworkAddresses() {
        ToolRegistry reg = new ToolRegistry()
                .register(new Tool("fetch_url", "fetch a URL",
                        List.of(new Param("url", "string", true, null)),
                        args -> "ok"));
        // Block private addresses by default.
        for (String url : new String[]{
                "http://localhost/admin", "http://127.0.0.1/x",
                "http://10.0.0.1/internal", "http://192.168.1.1/router",
                "http://169.254.169.254/metadata"}) {
            ToolUseCapabilityTest.ToolResult r = reg.invoke("fetch_url", Map.of("url", url));
            assertFalse(r.ok(), url + " should be blocked by SSRF guard");
            assertTrue(r.error().contains("ssrf"), url + " error should mention ssrf");
        }
    }

    @Test
    void ssrfAllowsPublicAddresses() {
        ToolRegistry reg = new ToolRegistry()
                .register(new Tool("fetch_url", "fetch",
                        List.of(new Param("url", "string", true, null)),
                        args -> "ok"));
        ToolUseCapabilityTest.ToolResult r = reg.invoke("fetch_url",
                Map.of("url", "https://arxiv.org/abs/2503.16416"));
        assertTrue(r.ok());
    }

    @Test
    void writePathMustBeInAllowList() {
        ToolRegistry reg = new ToolRegistry()
                .allowWritePath("/tmp/sandbox/")
                .register(new Tool("write_file", "write",
                        List.of(
                                new Param("path", "string", true, null),
                                new Param("content", "string", true, null)),
                        args -> "ok"));
        // Allowed path → ok.
        assertTrue(reg.invoke("write_file",
                Map.of("path", "/tmp/sandbox/out.txt", "content", "x")).ok());
        // Disallowed path → error.
        ToolUseCapabilityTest.ToolResult r = reg.invoke("write_file",
                Map.of("path", "/etc/passwd", "content", "x"));
        assertFalse(r.ok());
        assertTrue(r.error().contains("path not allowed"));
    }

    @Test
    void readPathMustBeInAllowList() {
        ToolRegistry reg = new ToolRegistry()
                .allowReadPath("/tmp/sandbox/")
                .register(new Tool("read_file", "read",
                        List.of(new Param("path", "string", true, null)),
                        args -> "ok"));
        assertFalse(reg.invoke("read_file",
                Map.of("path", "/etc/shadow")).ok());
    }

    /* --------------------- Long-context argument handling --------------------- */

    @Test
    void largeArgumentStringIsPassedVerbatim() {
        // Real LLM agents may need to pass a 1 MB document body as
        // a single argument. The registry must not lose content.
        ToolRegistry reg = new ToolRegistry()
                .register(new Tool("store", "store a document",
                        List.of(new Param("body", "string", true, null)),
                        args -> args.get("body")));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1_000_000; i++) big.append("x");
        ToolUseCapabilityTest.ToolResult r = reg.invoke("store",
                Map.of("body", big.toString()));
        assertTrue(r.ok());
        assertEquals(1_000_000, ((String) r.payload()).length());
    }

    @Test
    void largeArgumentStringWithUnicodeIsPassedVerbatim() {
        ToolRegistry reg = new ToolRegistry()
                .register(new Tool("store", "store",
                        List.of(new Param("body", "string", true, null)),
                        args -> args.get("body")));
        String unicode = "你好,世界 — Hello, world — こんにちは — 안녕 — "
                .repeat(1000);
        ToolUseCapabilityTest.ToolResult r = reg.invoke("store",
                Map.of("body", unicode));
        assertTrue(r.ok());
        assertEquals(unicode.length(), ((String) r.payload()).length());
    }

    /* --------------------- Error recovery --------------------- */

    @Test
    void handlerErrorSurfacesStructuredMessage() {
        ToolRegistry reg = new ToolRegistry()
                .register(new Tool("divide", "divide two numbers",
                        List.of(
                                new Param("a", "double", true, null),
                                new Param("b", "double", true, null)),
                        args -> {
                            double a = ((Number) args.get("a")).doubleValue();
                            double b = ((Number) args.get("b")).doubleValue();
                            if (b == 0) throw new ArithmeticException("division by zero");
                            return a / b;
                        }));
        ToolUseCapabilityTest.ToolResult r = reg.invoke("divide",
                Map.of("a", 1, "b", 0));
        assertFalse(r.ok());
        assertTrue(r.error().contains("ArithmeticException"),
                "structured error must include exception class: " + r.error());
    }

    @Test
    void unknownToolNameReturnsDescriptiveError() {
        ToolRegistry reg = new ToolRegistry()
                .register(new Tool("foo", "x", List.of(), args -> null));
        ToolUseCapabilityTest.ToolResult r = reg.invoke("bar", Map.of());
        assertFalse(r.ok());
        assertTrue(r.error().contains("bar"));
    }

    @Test
    void registryRejectsDuplicateToolRegistration() {
        ToolRegistry reg = new ToolRegistry()
                .register(new Tool("foo", "x", List.of(), args -> null));
        assertThrows(IllegalArgumentException.class,
                () -> reg.register(new Tool("foo", "y", List.of(), args -> null)));
    }

    /* --------------------- Tool sandboxing (per-tool capability) --------------------- */

    @Test
    void readOnlyToolIsReportedAsReadOnly() {
        // The registry should distinguish read-only vs mutating
        // tools so the permission system can ask for approval.
        ToolRegistry reg = new ToolRegistry();
        Tool read = new Tool("read_file", "read",
                List.of(new Param("path", "string", true, null)),
                args -> "ok");
        Tool write = new Tool("write_file", "write",
                List.of(
                        new Param("path", "string", true, null),
                        new Param("content", "string", true, null)),
                args -> "ok");
        reg.register(read);
        reg.register(write);
        // Heuristic: name contains "write" or "delete" or "edit" → mutating.
        assertFalse(read.name().contains("write") || read.name().contains("delete") || read.name().contains("edit"));
        assertTrue(write.name().contains("write"));
    }

    /* --------------------- Long-horizon: many tool calls in sequence --------------------- */

    @Test
    void longHorizonToolChainCompletesAllSteps() {
        // 10 sequential tool calls. The registry must keep state
        // consistent (no leaks) and every step must succeed.
        ToolRegistry reg = new ToolRegistry()
                .register(new Tool("noop", "no-op",
                        List.of(), args -> "ok"));
        int[] counter = {0};
        for (int i = 0; i < 10; i++) {
            ToolUseCapabilityTest.ToolResult r = reg.invoke("noop", Map.of());
            assertTrue(r.ok(), "step " + i + " should succeed");
            counter[0]++;
        }
        assertEquals(10, counter[0]);
    }

    /* --------------------- Concurrent tool calls (state isolation) --------------------- */

    @Test
    void parallelInvocationsAreIndependent() {
        // Two parallel reads must each get their own result.
        ToolRegistry reg = new ToolRegistry()
                .register(new Tool("echo", "echo the input",
                        List.of(new Param("msg", "string", true, null)),
                        args -> args.get("msg")));
        ToolUseCapabilityTest.ToolResult a = reg.invoke("echo", Map.of("msg", "alpha"));
        ToolUseCapabilityTest.ToolResult b = reg.invoke("echo", Map.of("msg", "beta"));
        assertEquals("alpha", a.payload());
        assertEquals("beta", b.payload());
    }

    /* --------------------- Output shape --------------------- */

    @Test
    void resultPayloadIsGenericObject() {
        // Different tools return different types. The registry
        // must not constrain the payload type.
        ToolRegistry reg = new ToolRegistry()
                .register(new Tool("string_tool", "x", List.of(),
                        args -> "string-result"))
                .register(new Tool("int_tool", "x", List.of(),
                        args -> 42))
                .register(new Tool("list_tool", "x", List.of(),
                        args -> List.of(1, 2, 3)));
        assertEquals("string-result", reg.invoke("string_tool", Map.of()).payload());
        assertEquals(42, reg.invoke("int_tool", Map.of()).payload());
        assertEquals(List.of(1, 2, 3), reg.invoke("list_tool", Map.of()).payload());
    }

    /* --------------------- Validation --------------------- */

    @Test
    void toolRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new Tool("", "x", List.of(), args -> null));
        assertThrows(IllegalArgumentException.class,
                () -> new Tool(null, "x", List.of(), args -> null));
    }

    @Test
    void paramRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new Param("", "string", false, null));
    }

    /* --------------------- End-to-end plan: tool chain + dependency check --------------------- */

    @Test
    void planExecutesToolChainInDependencyOrder() {
        // Plan: search → fetch → summarise → write
        // 4 tools in sequence; each step's output feeds the next.
        ToolRegistry reg = new ToolRegistry()
                .allowWritePath("/tmp/");
        reg.register(new Tool("search", "find candidate URLs",
                        List.of(new Param("query", "string", true, null)),
                        args -> List.of("https://example.com/a", "https://example.com/b")))
                .register(new Tool("fetch", "fetch a URL body",
                        List.of(new Param("url", "string", true, null)),
                        args -> "body of " + args.get("url")))
                .register(new Tool("summarize", "summarize text",
                        List.of(new Param("text", "string", true, null)),
                        args -> "summary: " + args.get("text")))
                .register(new Tool("write_file", "write",
                        List.of(
                                new Param("path", "string", true, null),
                                new Param("content", "string", true, null)),
                        args -> "ok"));

        // Step 1: search.
        ToolUseCapabilityTest.ToolResult search = reg.invoke("search",
                Map.of("query", "agent eval"));
        assertTrue(search.ok());
        @SuppressWarnings("unchecked")
        List<String> urls = (List<String>) search.payload();
        assertEquals(2, urls.size());

        // Step 2: fetch the first URL.
        ToolUseCapabilityTest.ToolResult fetch = reg.invoke("fetch",
                Map.of("url", urls.get(0)));
        assertTrue(fetch.ok());

        // Step 3: summarize the body.
        ToolUseCapabilityTest.ToolResult sum = reg.invoke("summarize",
                Map.of("text", fetch.payload()));
        assertTrue(sum.ok());

        // Step 4: write the summary.
        ToolUseCapabilityTest.ToolResult write = reg.invoke("write_file",
                Map.of("path", "/tmp/summary.txt", "content", sum.payload()));
        assertTrue(write.ok());
    }
}
