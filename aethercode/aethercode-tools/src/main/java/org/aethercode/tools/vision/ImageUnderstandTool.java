package org.aethercode.tools.vision;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Prior round (O-7): a {@link Tool} that delegates to a {@link VlmClient}
 * and returns the model's text description of the image.
 *
 * <p>Static {@link #build()} wires the {@link OpenAiCompatibleVlmClient}
 * loaded from environment ({@code AETHERCODE_VLM_API_KEY}, etc.)
 * and falls back to a {@link MockVlmClient} when no key is set.
 * This is the safe default: tools that can be tested in CI
 * without external services, while production swaps in the
 * real client by setting the env vars.
 *
 * <p>For test suites that need a deterministic description, use
 * {@link #build(VlmClient)} and pass a {@link MockVlmClient}
 * pre-loaded with fixtures.
 */
public class ImageUnderstandTool {

    public static final String NAME = "image_understand";

    private static final Logger LOG = LoggerFactory.getLogger(ImageUnderstandTool.class);

    /** Per-process default client. Tests can swap it via {@link #setDefaultClient}. */
    private static final AtomicReference<VlmClient> DEFAULT = new AtomicReference<>(resolveDefault());

    private ImageUnderstandTool() {}

    /** Replace the default client (test-only). */
    public static void setDefaultClient(VlmClient client) {
        DEFAULT.set(client);
    }

    /** Visible for tests: read the current default. */
    public static VlmClient defaultClient() {
        return DEFAULT.get();
    }

    /** Visible for tests / advanced wiring. */
    public static void resetDefault() {
        DEFAULT.set(resolveDefault());
    }

    private static VlmClient resolveDefault() {
        VlmClient env = OpenAiCompatibleVlmClient.fromEnvOrNull();
        if (env != null) return env;
        LOG.info("R242: no VLM env vars set, falling back to MockVlmClient");
        return new MockVlmClient();
    }

    /** Build a tool backed by the default (env / mock) client. */
    public static Tool build() {
        return build(DEFAULT.get());
    }

    /** Build a tool backed by a caller-supplied client. */
    public static Tool build(VlmClient client) {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("path", Tools.stringProp(
                "Local path to the image (PNG / JPEG / WEBP / GIF). " +
                "Some VLM backends also accept HTTP(S) URLs — see VlmClient docs."));
        props.put("prompt", Tools.stringProp(
                "What to ask about the image. " +
                "Examples: \"describe this UI\", \"is this button red?\", " +
                "\"transcribe the text in this screenshot\"."));
        props.put("model", Tools.stringProp(
                "Optional model id override. Defaults to the client's default."));
        Map<String, Object> schema = Tools.objectSchema(props, "path", "prompt");
        final VlmClient captured = client;
        java.util.function.BiFunction<Map<String, Object>, Tool.CallContext,
                CompletableFuture<Tool.ToolResult>> handler =
                (input, ctx) -> CompletableFuture.completedFuture(call(input, captured, ctx));
        return Tools.build(new ToolDef(
                NAME,
                "Describe / answer a question about an image using a Vision-Language Model. " +
                        "Returns the model's text response. Local paths are read and base64-encoded " +
                        "into an OpenAI-style data URL. Pairs well with the read_file tool when the " +
                        "model needs to know what a screenshot or PDF page contains.",
                schema,
                handler));
    }

    /** Synchronous call path. Public for the test suite. */
    public static Tool.ToolResult call(Map<String, Object> input, VlmClient client, Tool.CallContext ctx) {
        Object rawPath = input.get("path");
        if (!(rawPath instanceof String s) || s.isBlank()) {
            return Tool.ToolResult.error("path is required");
        }
        Object rawPrompt = input.get("prompt");
        if (!(rawPrompt instanceof String prompt) || prompt.isBlank()) {
            return Tool.ToolResult.error("prompt is required");
        }
        VlmClient.Options opts = Optional.ofNullable((String) input.get("model"))
                .filter(m -> !m.isBlank())
                .map(m -> new VlmClient.Options(m, null, null))
                .orElseGet(VlmClient.Options::defaultOptions);
        // Local path pre-flight: surface a clear error if the
        // file is missing before the VLM call (which would
        // produce a less useful network-level failure).
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            if (!Files.exists(Path.of(s))) {
                return Tool.ToolResult.error("image not found: " + s);
            }
        }
        try {
            String response = client.understand(s, prompt, opts);
            return Tool.ToolResult.of(response);
        } catch (Exception e) {
            LOG.warn("image_understand failed for {}: {}", s, e.getMessage());
            return Tool.ToolResult.error("vlm call failed: " + e.getMessage());
        }
    }
}
