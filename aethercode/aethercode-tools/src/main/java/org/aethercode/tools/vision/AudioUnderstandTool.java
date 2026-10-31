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
 * Audio understanding tool. Delegates to {@link VlmClient#understandAudio}.
 *
 * <p>Audio has the largest backend variance of the three modalities — some
 * VLMs consume it directly, others require an ASR step first — so the
 * tool's behaviour is entirely determined by the client. The default
 * implementation throws {@link UnsupportedOperationException}, which this
 * tool catches and converts to a clean error.</p>
 *
 * <p>Pairs with {@link VideoUnderstandTool}: the video stream is handled by
 * video_understand, the audio track by audio_understand, and processing
 * multi-second recordings or meetings typically uses both together.</p>
 */
public class AudioUnderstandTool {

    public static final String NAME = "audio_understand";

    private static final Logger LOG = LoggerFactory.getLogger(AudioUnderstandTool.class);

    private static final AtomicReference<VlmClient> DEFAULT =
            new AtomicReference<>(ImageUnderstandTool.defaultClient());

    private AudioUnderstandTool() {}

    public static void setDefaultClient(VlmClient client) {
        DEFAULT.set(client);
    }

    public static VlmClient defaultClient() { return DEFAULT.get(); }

    public static void resetDefault() {
        DEFAULT.set(ImageUnderstandTool.defaultClient());
    }

    public static Tool build() {
        return build(DEFAULT.get());
    }

    public static Tool build(VlmClient client) {
        var props = new LinkedHashMap<String, Map<String, Object>>();
        props.put("path", Tools.stringProp(
                "Local path to the audio (WAV / MP3 / OGG / FLAC / M4A). " +
                "Backends that don't support audio directly will return an error."));
        props.put("prompt", Tools.stringProp(
                "What to ask about the audio. " +
                "Examples: \"transcribe this recording\", \"what's the speaker's tone?\", " +
                "\"summarise the meeting\"."));
        props.put("model", Tools.stringProp(
                "Optional model id override. Defaults to the client's default."));
        Map<String, Object> schema = Tools.objectSchema(props, "path", "prompt");
        final VlmClient captured = client;
        java.util.function.BiFunction<Map<String, Object>, Tool.CallContext,
                CompletableFuture<Tool.ToolResult>> handler =
                (input, ctx) -> CompletableFuture.completedFuture(call(input, captured, ctx));
        return Tools.build(new ToolDef(
                NAME,
                "Describe / answer a question about an audio clip. Backends that " +
                        "don't support audio directly return an error so the agent can " +
                        "fall back to a frame-based video tool or an external ASR service.",
                schema,
                handler));
    }

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
                return Tool.ToolResult.error("audio not found: " + s);
            }
        }
        try {
            String response = client.understandAudio(s, prompt, opts);
            return Tool.ToolResult.of(response);
        } catch (UnsupportedOperationException uoe) {
            LOG.warn("audio_understand unsupported by backend: {}", uoe.getMessage());
            return Tool.ToolResult.error("audio not supported by this VLM backend: " + uoe.getMessage());
        } catch (Exception e) {
            LOG.warn("audio_understand failed for {}: {}", s, e.getMessage());
            return Tool.ToolResult.error("vlm call failed: " + e.getMessage());
        }
    }
}
