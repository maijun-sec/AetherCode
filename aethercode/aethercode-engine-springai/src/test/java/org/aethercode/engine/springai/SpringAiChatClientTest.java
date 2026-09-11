package org.aethercode.engine.springai;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.llm.ChatClient.Options;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * focused tests for the spring-ai adapter. We do NOT spin up a real LLM here;
 * these tests cover the contract (key validation, model id, request body shape
 * is delegated to spring-ai). Streaming behaviour is exercised in higher-level
 * integration tests.
 */
class SpringAiChatClientTest {

    @Test
    void constructor_rejectsBlankApiKey() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new SpringAiChatClient("MiniMax-M3", new Options(null, "https://x", 100, 1.0)));
        assertTrue(ex.getMessage().toLowerCase().contains("api key"));
    }

    @Test
    void constructor_rejectsEmptyApiKey() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new SpringAiChatClient("MiniMax-M3", new Options("", "https://x", 100, 1.0)));
        assertTrue(ex.getMessage().toLowerCase().contains("api key"));
    }

    @Test
    void modelId_returnsConstructorArg() {
        SpringAiChatClient c = new SpringAiChatClient("MiniMax-M3",
                new Options("k", "https://api.minimaxi.com/v1", 100, 1.0));
        assertEquals("MiniMax-M3", c.modelId());
    }

    @Test
    void modelId_defaultsToM3WhenBlank() {
        SpringAiChatClient c1 = new SpringAiChatClient("",
                new Options("k", "https://x", 100, 1.0));
        SpringAiChatClient c2 = new SpringAiChatClient(null,
                new Options("k", "https://x", 100, 1.0));
        assertEquals("MiniMax-M3", c1.modelId());
        assertEquals("MiniMax-M3", c2.modelId());
    }

    @Test
    void minimaxDefaults_useMinimaxEndpoint() {
        // The Options.baseUrl keeps the public /v1 suffix (so callers see the
        // familiar URL). SpringAiChatClient internally strips the trailing /v1
        // before passing it to OpenAiApi, which appends its own /v1/chat/completions.
        Options o = SpringAiChatClient.minimaxDefaults();
        assertEquals("https://api.minimaxi.com/v1", o.baseUrl());
        // R136.4: bumped from 1024 to 65_536 so the chat
        // completion isn't capped at the R15 hardcoded
        // default. MiniMax-M3 actually supports 512K; 64K
        // is a safer per-call fallback for the default
        // factory when the caller doesn't know the
        // model's ceiling.
        assertEquals(65_536, o.maxTokens());
        assertEquals(1_000_000, o.contextWindow());
    }

    @Test
    void implementsOurChatClientInterface() {
        // Smoke test that the adapter satisfies the public ChatClient contract.
        ChatClient c = new SpringAiChatClient("MiniMax-M3",
                new Options("k", "https://api.minimaxi.com/v1", 100, 1.0));
        assertNotNull(c);
        assertEquals("MiniMax-M3", c.modelId());
    }

    @Test
    void urlAssembly_stripsTrailingV1ToAvoidDoublePrefix() {
        // The bug: OpenAiApi treats baseUrl as bare host and hard-codes
        // /v1/chat/completions. So with baseUrl="https://api.minimaxi.com/v1"
        // the final URL would be "https://api.minimaxi.com/v1/v1/chat/completions"
        // which 404s. SpringAiChatClient strips the trailing /v1 internally.
        // We test via a fake-by-URL helper on the static method by reflection.
        try {
            java.lang.reflect.Method m = SpringAiChatClient.class
                    .getDeclaredMethod("stripV1", String.class);
            m.setAccessible(true);
            assertEquals("https://api.minimaxi.com",
                    m.invoke(null, "https://api.minimaxi.com/v1"));
            assertEquals("https://api.minimaxi.com",
                    m.invoke(null, "https://api.minimaxi.com/v1/"));
            assertEquals("https://api.minimaxi.com",
                    m.invoke(null, "https://api.minimaxi.com"));
            assertEquals("http://localhost:8080",
                    m.invoke(null, "http://localhost:8080/v1"));
        } catch (Exception e) {
            fail("stripV1 reflection failed: " + e.getMessage());
        }
    }

    // verify parseJsonArgs returns Map.of() for
    // the three "empty / malformed" cases the engine
    // actually hits in production. We assert the return
    // value (Map.of()) so the tests don't depend on the
    // SLF4J log capture; the log lines themselves are
    // visible in the daemon's stderr when this fires.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> callParseJsonArgs(String s) {
        try {
            Method m = SpringAiChatClient.class.getDeclaredMethod("parseJsonArgs", String.class);
            m.setAccessible(true);
            return (Map<String, Object>) m.invoke(null, s);
        } catch (Exception e) {
            fail("parseJsonArgs reflection failed: " + e.getMessage());
            return Map.of();
        }
    }

    @Test
    void parseJsonArgs_null_returnsEmpty() {
        // model emitted a tool_use with null
        // arguments. Empty map so the engine sees no
        // params and surfaces "X is required".
        assertEquals(Map.of(), callParseJsonArgs(null));
    }

    @Test
    void parseJsonArgs_blank_returnsEmpty() {
        // model emitted a tool_use with ""
        // arguments. Same effect as null.
        assertEquals(Map.of(), callParseJsonArgs(""));
        assertEquals(Map.of(), callParseJsonArgs("   "));
    }

    @Test
    void parseJsonArgs_emptyJsonObject_returnsEmpty() {
        // THE actual production case the user
        // hit. Model emits `input: {}` which Spring AI
        // hands us as the literal string "{}". parseJsonArgs
        // parses it (valid JSON) and returns the empty
        // map. Without prior round, this branch was silent;
        // prior round now logs a warn so a fresh daemon start
        // surfaces it in stderr.
        assertEquals(Map.of(), callParseJsonArgs("{}"));
    }

    @Test
    void parseJsonArgs_validJson_returnsParsedMap() {
        // working case. args is a non-empty JSON
        // object, parseJsonArgs returns the parsed map
        // and does NOT log a warn.
        Map<String, Object> r = callParseJsonArgs("{\"command\":\"ls -la\",\"cwd\":\"/tmp\"}");
        assertEquals("ls -la", r.get("command"));
        assertEquals("/tmp", r.get("cwd"));
        assertEquals(2, r.size());
    }

    @Test
    void parseJsonArgs_malformedJson_returnsEmpty() {
        // legacy this was a silent fallback
        // (we'd return Map.of() and the user would just
        // see "command is required"). prior round logs the
        // first 200 chars of the failing args so we can
        // tell whether the model was emitting Mavis XML
        // ("<invoke name=\"bash\"><command>ls</command></invoke>"),
        // bare tags, or some other non-JSON format.
        assertEquals(Map.of(), callParseJsonArgs("<invoke name=\"bash\"><command>ls</command></invoke>"));
        assertEquals(Map.of(), callParseJsonArgs("not even close to json"));
        assertEquals(Map.of(), callParseJsonArgs("[1, 2, 3]"));  // array, not object
    }
}
