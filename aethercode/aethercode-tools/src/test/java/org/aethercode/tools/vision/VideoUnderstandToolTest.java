package org.aethercode.tools.vision;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for {@link VideoUnderstandTool}.
 * Each test wires a deterministic {@link MockVlmClient} so
 * the tool's wire shape is the only thing under test.
 */
class VideoUnderstandToolTest {

    @TempDir
    Path tmp;

    private MockVlmClient mock;

    @BeforeEach
    void setUp() {
        mock = new MockVlmClient();
    }

    @AfterEach
    void tearDown() {
        VideoUnderstandTool.resetDefault();
        ImageUnderstandTool.resetDefault();
    }

    @Test
    void buildProducesToolWithVideoUnderstandName() {
        Tool t = VideoUnderstandTool.build(mock);
        assertNotNull(t);
        assertEquals(VideoUnderstandTool.NAME, t.name());
        assertEquals("video_understand", t.name());
    }

    @Test
    void callReturnsFixtureDescription() throws Exception {
        Path video = tmp.resolve("clip.mp4");
        Files.write(video, new byte[]{0, 1, 2, 3, 4});
        mock.registerVideo(video.toString(), "a cat knocks a glass off a table");
        Tool.ToolResult r = VideoUnderstandTool.call(
                Map.of("path", video.toString(), "prompt", "what happens?"),
                mock, Tool.CallContext.of("test"));
        assertFalse(r.isError(), "expected success, got error: " + r.output());
        assertEquals("a cat knocks a glass off a table", r.output());
        assertEquals(1, mock.videoCalls().size());
        assertEquals(video.toString(), mock.videoCalls().get(0).videoPath());
        assertEquals("what happens?", mock.videoCalls().get(0).prompt());
    }

    @Test
    void callFallsBackToCannedDescriptionWhenNoFixture() throws Exception {
        Path video = tmp.resolve("unknown.mp4");
        Files.write(video, new byte[]{9, 8, 7});
        Tool.ToolResult r = VideoUnderstandTool.call(
                Map.of("path", video.toString(), "prompt", "describe"),
                mock, Tool.CallContext.of("test"));
        assertFalse(r.isError());
        // The canned mock description includes the prompt verbatim.
        String out = r.output().toString();
        assertTrue(out.contains("describe"), out);
        assertTrue(out.contains("size="), out);
    }

    @Test
    void callReturnsErrorWhenPathMissing() throws Exception {
        Tool.ToolResult r = VideoUnderstandTool.call(
                Map.of("path", tmp.resolve("nope.mp4").toString(), "prompt", "x"),
                mock, Tool.CallContext.of("test"));
        assertTrue(r.isError());
        assertTrue(r.output().toString().contains("video not found"));
    }

    @Test
    void callReturnsErrorWhenPathBlank() throws Exception {
        Tool.ToolResult r = VideoUnderstandTool.call(
                Map.of("path", "  ", "prompt", "x"),
                mock, Tool.CallContext.of("test"));
        assertTrue(r.isError());
        assertTrue(r.output().toString().contains("path is required"));
    }

    @Test
    void callReturnsErrorWhenPromptMissing() throws Exception {
        Path video = tmp.resolve("clip.mp4");
        Files.write(video, new byte[]{1});
        Tool.ToolResult r = VideoUnderstandTool.call(
                Map.of("path", video.toString(), "prompt", ""),
                mock, Tool.CallContext.of("test"));
        assertTrue(r.isError());
        assertTrue(r.output().toString().contains("prompt is required"));
    }

    @Test
    void callSurfacesUnsupportedOperationAsCleanError() throws Exception {
        // Build a tool backed by a client that doesn't support
        // video. The tool's call() should catch the
        // UnsupportedOperationException and return a
        // readable error, not propagate it.
        VlmClient imageOnly = new VlmClient() {
            @Override
            public String understand(String imagePath, String prompt) {
                return "image-only mock";
            }
        };
        Path video = tmp.resolve("clip.mp4");
        Files.write(video, new byte[]{1});
        Tool.ToolResult r = VideoUnderstandTool.call(
                Map.of("path", video.toString(), "prompt", "x"),
                imageOnly, Tool.CallContext.of("test"));
        assertTrue(r.isError());
        assertTrue(r.output().toString().contains("video not supported"),
                "expected clean error, got: " + r.output());
    }
}
