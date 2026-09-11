package org.aethercode.tools.vision;

import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.aethercode.core.tool.Tools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Video understanding tool. Delegates to {@link VlmClient#understandVideo}
 * and mirrors the shape of {@link ImageUnderstandTool} so an agent can swap
 * the tool name without adjusting surrounding code.
 *
 * <p>Kept separate from image_understand because the two schemas differ
 * (image: PNG/JPEG/WEBP, video: MP4/MOV/WEBM); merging them into a single
 * discriminative schema only makes it easier for the model to pick the
 * wrong one.</p>
 *
 * <p>Default client: {@link #build()} reads the {@code AETHERCODE_VLM_*}
 * environment variables and falls back to {@link MockVlmClient} when none
 * are set. Tests should pass an explicit client via
 * {@link #build(VlmClient)} so the call path is fully controlled.</p>
 */
public class VideoUnderstandTool {

    public static final String NAME = "video_understand";

    private static final Logger LOG = LoggerFactory.getLogger(VideoUnderstandTool.class);

    private static final AtomicReference<VlmClient> DEFAULT =
            new AtomicReference<>(ImageUnderstandTool.defaultClient());

    private VideoUnderstandTool() {}

    /** Override the default client (test-only). */
    public static void setDefaultClient(VlmClient client) {
        DEFAULT.set(client);
    }

    public static VlmClient defaultClient() { return DEFAULT.get(); }

    public static void resetDefault() {
        DEFAULT.set(ImageUnderstandTool.defaultClient());
    }

    /** Build a tool backed by the default (env / mock) client. */
    public static Tool build() {
        return build(DEFAULT.get());
    }

    /** Build a tool backed by a caller-supplied client. */
    public static Tool build(VlmClient client) {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("path", Tools.stringProp(
                "Local path to the video (MP4 / MOV / WEBM). " +
                "Large videos may be split into chunks or frame-extracted by the VLM backend."));
        props.put("prompt", Tools.stringProp(
                "What to ask about the video. " +
                "Examples: \"summarise this clip\", \"transcribe the dialogue\", " +
                "\"what is happening at 0:42?\"."));
        props.put("model", Tools.stringProp(
                "Optional model id override. Defaults to the client's default."));
        Map<String, Object> schema = Tools.objectSchema(props, "path", "prompt");
        final VlmClient captured = client;
        java.util.function.BiFunction<Map<String, Object>, Tool.CallContext,
                CompletableFuture<Tool.ToolResult>> handler =
                (input, ctx) -> CompletableFuture.completedFuture(call(input, captured, ctx));
        return Tools.build(new ToolDef(
                NAME,
                "Describe / answer a question about a video using a multimodal model. " +
                        "Returns the model's text response. Pairs with image_understand for " +
                        "screenshots and read_file for transcripts of the video's surrounding " +
                        "directory.",
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
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            if (!Files.exists(Path.of(s))) {
                return Tool.ToolResult.error("video not found: " + s);
            }
        }
        try {
            String response = client.understandVideo(s, prompt, opts);
            return Tool.ToolResult.of(response);
        } catch (UnsupportedOperationException uoe) {
            // The backend does not support video; surface a clear error so the
            // agent can fall back to image_understand for key frames.
            LOG.warn("video_understand unsupported by backend: {}", uoe.getMessage());
            return Tool.ToolResult.error("video not supported by this VLM backend: " + uoe.getMessage());
        } catch (Exception e) {
            LOG.warn("video_understand failed for {}: {}", s, e.getMessage());
            return Tool.ToolResult.error("vlm call failed: " + e.getMessage());
        }
    }
}
