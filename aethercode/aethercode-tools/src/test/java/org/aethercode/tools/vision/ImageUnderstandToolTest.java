package org.aethercode.tools.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolDef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prior round (O-7): tests for the VLM tool surface. Three concerns:
 *
 * <ol>
 *   <li>{@link MockVlmClient} — deterministic fixture model, used
 *       in dev / CI when no real VLM key is configured.</li>
 *   <li>{@link OpenAiCompatibleVlmClient} — payload shape and
 *       response parsing against an in-process HTTP server.</li>
 *   <li>{@link ImageUnderstandTool} — argument validation,
 *       default-client resolution, and tool-result shape.</li>
 * </ol>
 */
class ImageUnderstandToolTest {

    @TempDir
    Path tmp;

    // -- MockVlmClient -----------------------------------------------

    @Test
    void mockReturnsRegisteredFixture() throws Exception {
        MockVlmClient m = new MockVlmClient()
                .register("/img/foo.png", "two red buttons on a dark background");
        String r = m.understand("/img/foo.png", "describe");
        assertEquals("two red buttons on a dark background", r);
        assertEquals(1, m.totalCalls());
    }

    @Test
    void mockCannedDescriptionForUnregisteredPath(@TempDir Path t) throws Exception {
        Path img = t.resolve("real.png");
        Files.write(img, new byte[]{0x10, 0x20, 0x30, 0x40, 0x50});
        MockVlmClient m = new MockVlmClient();
        String r = m.understand(img.toString(), "describe");
        assertTrue(r.contains("mock VLM description of"));
        assertTrue(r.contains(img.toString()));
        assertTrue(r.contains("prompt=\"describe\""));
        assertTrue(m.calls().get(0).prompt().equals("describe"));
    }

    @Test
    void mockThrowsForMissingFile() {
        MockVlmClient m = new MockVlmClient();
        try {
            m.understand("/no/such/path.png", "describe");
            org.junit.jupiter.api.Assertions.fail("expected IOException");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("not found"), e.getMessage());
        }
    }

    // -- ImageUnderstandTool ---------------------------------------

    @Test
    void toolRequiresPath() {
        MockVlmClient m = new MockVlmClient();
        Tool tool = ImageUnderstandTool.build(m);
        Tool.ToolResult r = ImageUnderstandTool.call(
                Map.of("prompt", "x"), m, Tool.CallContext.of("test"));
        assertTrue(r.isError());
        assertTrue(r.output().toString().contains("path is required"));
    }

    @Test
    void toolRequiresPrompt() {
        MockVlmClient m = new MockVlmClient();
        Tool tool = ImageUnderstandTool.build(m);
        Tool.ToolResult r = ImageUnderstandTool.call(
                Map.of("path", "/img/foo.png"), m, Tool.CallContext.of("test"));
        assertTrue(r.isError());
        assertTrue(r.output().toString().contains("prompt is required"));
    }

    @Test
    void toolSurfacesMissingFile() {
        MockVlmClient m = new MockVlmClient();
        Tool.ToolResult r = ImageUnderstandTool.call(
                Map.of("path", "/no/such/img.png", "prompt", "describe"),
                m, Tool.CallContext.of("test"));
        assertTrue(r.isError());
        assertTrue(r.output().toString().contains("image not found"));
    }

    @Test
    void toolSuccessfulCallReturnsVlmText(@TempDir Path t) throws Exception {
        Path img = t.resolve("hello.png");
        Files.write(img, new byte[]{0x01, 0x02, 0x03});
        MockVlmClient m = new MockVlmClient()
                .register(img.toString(), "a happy little image");
        Tool.ToolResult r = ImageUnderstandTool.call(
                Map.of("path", img.toString(), "prompt", "describe"),
                m, Tool.CallContext.of("test"));
        assertFalse(r.isError());
        assertEquals("a happy little image", r.output());
    }

    @Test
    void toolHasExpectedSchema() {
        MockVlmClient m = new MockVlmClient();
        Tool tool = ImageUnderstandTool.build(m);
        assertEquals("image_understand", tool.name());
        assertNotNull(tool.description());
        Map<String, Object> schema = tool.inputSchema();
        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("path"));
        assertTrue(props.containsKey("prompt"));
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("path"));
        assertTrue(required.contains("prompt"));
    }

    @Test
    void toolWrapsVlmExceptionAsErrorResult(@TempDir Path t) throws Exception {
        Path img = t.resolve("ok.png");
        Files.write(img, new byte[]{0x00});
        VlmClient alwaysFails = new VlmClient() {
            @Override public String understand(String p, String q) throws Exception {
                throw new RuntimeException("kaboom");
            }
        };
        Tool.ToolResult r = ImageUnderstandTool.call(
                Map.of("path", img.toString(), "prompt", "x"),
                alwaysFails, Tool.CallContext.of("test"));
        assertTrue(r.isError());
        assertTrue(r.output().toString().contains("kaboom"));
    }

    // -- OpenAiCompatibleVlmClient --------------------------------

    @Test
    void openAiPayloadShape() throws Exception {
        OpenAiCompatibleVlmClient c = new OpenAiCompatibleVlmClient(
                java.net.URI.create("https://api.example.com/v1"),
                "test-key",
                "gpt-4o",
                new OkHttpClient());
        com.fasterxml.jackson.databind.node.ObjectNode payload = c.buildPayload(
                "what colour?",
                "data:image/png;base64,AAAA",
                new VlmClient.Options("custom-model", 256, 0.2));
        assertEquals("custom-model", payload.get("model").asText());
        assertEquals(256, payload.get("max_tokens").asInt());
        assertEquals(0.2, payload.get("temperature").asDouble());
        assertEquals("user", payload.get("messages").get(0).get("role").asText());
        assertEquals("text", payload.get("messages").get(0).get("content").get(0).get("type").asText());
        assertEquals("image_url", payload.get("messages").get(0).get("content").get(1).get("type").asText());
        assertTrue(payload.get("messages").get(0).get("content").get(1).get("image_url").get("url").asText()
                .startsWith("data:image/png;base64,"));
    }

    @Test
    void openAiSuccessfulHttpCall(@TempDir Path t) throws Exception {
        // In-process HTTP server that mimics OpenAI's
        // /v1/chat/completions response shape.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger(0);
        server.createContext("/chat/completions", ex -> {
            calls.incrementAndGet();
            String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"a translucent teal rectangle\"}}]}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            Path img = t.resolve("teal.png");
            Files.write(img, new byte[]{0x01});
            OpenAiCompatibleVlmClient c = new OpenAiCompatibleVlmClient(
                    java.net.URI.create("http://127.0.0.1:"
                            + server.getAddress().getPort() + "/v1"),
                    "test-key",
                    "gpt-4o",
                    new OkHttpClient());
            String r = c.understand(img.toString(), "describe",
                    new VlmClient.Options(null, null, null));
            assertEquals("a translucent teal rectangle", r);
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiHttpErrorSurfacesAsException() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", ex -> {
            String body = "{\"error\":\"unauthorized\"}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(401, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            Path img = tmp.resolve("x.png");
            Files.write(img, new byte[]{0x01});
            OpenAiCompatibleVlmClient c = new OpenAiCompatibleVlmClient(
                    java.net.URI.create("http://127.0.0.1:"
                            + server.getAddress().getPort() + "/v1"),
                    "test-key",
                    "gpt-4o",
                    new OkHttpClient());
            try {
                c.understand(img.toString(), "describe",
                        new VlmClient.Options(null, null, null));
                org.junit.jupiter.api.Assertions.fail("expected IOException");
            } catch (Exception e) {
                assertTrue(e.getMessage().contains("401"), e.getMessage());
            }
        } finally {
            server.stop(0);
        }
    }
}
