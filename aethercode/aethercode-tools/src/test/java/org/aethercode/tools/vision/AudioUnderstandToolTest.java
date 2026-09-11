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
 * tests for {@link AudioUnderstandTool}.
 * Same shape as {@link VideoUnderstandToolTest} — fixture
 * + canned fallback + path validation + unsupported-backend
 * error path.
 */
class AudioUnderstandToolTest {

    @TempDir
    Path tmp;

    private MockVlmClient mock;

    @BeforeEach
    void setUp() {
        mock = new MockVlmClient();
    }

    @AfterEach
    void tearDown() {
        AudioUnderstandTool.resetDefault();
        ImageUnderstandTool.resetDefault();
    }

    @Test
    void buildProducesToolWithAudioUnderstandName() {
        Tool t = AudioUnderstandTool.build(mock);
        assertNotNull(t);
        assertEquals(AudioUnderstandTool.NAME, t.name());
        assertEquals("audio_understand", t.name());
    }

    @Test
    void callReturnsFixtureDescription() throws Exception {
        Path audio = tmp.resolve("clip.wav");
        Files.write(audio, new byte[]{0, 1, 2});
        mock.registerAudio(audio.toString(), "speaker: 'deploy at 5pm'");
        Tool.ToolResult r = AudioUnderstandTool.call(
                Map.of("path", audio.toString(), "prompt", "transcribe"),
                mock, Tool.CallContext.of("test"));
        assertFalse(r.isError(), "expected success, got error: " + r.output());
        assertEquals("speaker: 'deploy at 5pm'", r.output());
        assertEquals(1, mock.audioCalls().size());
        assertEquals("transcribe", mock.audioCalls().get(0).prompt());
    }

    @Test
    void callFallsBackToCannedDescriptionWhenNoFixture() throws Exception {
        Path audio = tmp.resolve("unknown.wav");
        Files.write(audio, new byte[]{0});
        Tool.ToolResult r = AudioUnderstandTool.call(
                Map.of("path", audio.toString(), "prompt", "summarise"),
                mock, Tool.CallContext.of("test"));
        assertFalse(r.isError());
        assertTrue(r.output().toString().contains("summarise"));
    }

    @Test
    void callReturnsErrorWhenPathMissing() throws Exception {
        Tool.ToolResult r = AudioUnderstandTool.call(
                Map.of("path", tmp.resolve("nope.wav").toString(), "prompt", "x"),
                mock, Tool.CallContext.of("test"));
        assertTrue(r.isError());
        assertTrue(r.output().toString().contains("audio not found"));
    }

    @Test
    void callReturnsErrorWhenPathBlank() throws Exception {
        Tool.ToolResult r = AudioUnderstandTool.call(
                Map.of("path", "", "prompt", "x"),
                mock, Tool.CallContext.of("test"));
        assertTrue(r.isError());
        assertTrue(r.output().toString().contains("path is required"));
    }

    @Test
    void callSurfacesUnsupportedOperationAsCleanError() throws Exception {
        // Backend that only supports image.
        VlmClient imageOnly = new VlmClient() {
            @Override
            public String understand(String imagePath, String prompt) {
                return "image-only mock";
            }
        };
        Path audio = tmp.resolve("clip.wav");
        Files.write(audio, new byte[]{0});
        Tool.ToolResult r = AudioUnderstandTool.call(
                Map.of("path", audio.toString(), "prompt", "x"),
                imageOnly, Tool.CallContext.of("test"));
        assertTrue(r.isError());
        assertTrue(r.output().toString().contains("audio not supported"),
                "expected clean error, got: " + r.output());
    }
}
