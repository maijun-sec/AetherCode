package org.aethercode.core.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactorTest {

    @Test
    void isSensitive_password() {
        Redactor r = new Redactor();
        assertTrue(r.isSensitive("password"));
        assertTrue(r.isSensitive("PASSWORD"));
        assertTrue(r.isSensitive("user_password"));
    }

    @Test
    void isSensitive_token() {
        Redactor r = new Redactor();
        assertTrue(r.isSensitive("api_token"));
        assertTrue(r.isSensitive("token"));
    }

    @Test
    void isSensitive_secret() {
        Redactor r = new Redactor();
        assertTrue(r.isSensitive("client_secret"));
    }

    @Test
    void isSensitive_nonSensitive() {
        Redactor r = new Redactor();
        assertEquals(false, r.isSensitive("username"));
        assertEquals(false, r.isSensitive("display_name"));
    }

    @Test
    void isSensitive_nullKey() {
        Redactor r = new Redactor();
        assertEquals(false, r.isSensitive(null));
    }

    @Test
    void redactMap_redactsPassword() {
        Redactor r = new Redactor();
        Map<String, Object> input = Map.of("username", "alice", "password", "secret123");
        Map<String, Object> out = r.redactMap(input);
        assertEquals("alice", out.get("username"));
        assertEquals(Redactor.PLACEHOLDER, out.get("password"));
    }

    @Test
    void redactMap_redactsNestedMap() {
        Redactor r = new Redactor();
        Map<String, Object> input = Map.of("api", Map.of("api_key", "sk-123", "endpoint", "https://api.example.com"));
        Map<String, Object> out = r.redactMap(input);
        @SuppressWarnings("unchecked")
        Map<String, Object> api = (Map<String, Object>) out.get("api");
        assertEquals(Redactor.PLACEHOLDER, api.get("api_key"));
        assertEquals("https://api.example.com", api.get("endpoint"));
    }

    @Test
    void redactMap_redactsListOfMaps() {
        Redactor r = new Redactor();
        Map<String, Object> server = Map.of("password", "x");
        Map<String, Object> input = Map.of("servers", List.of(server));
        Map<String, Object> out = r.redactMap(input);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> servers = (List<Map<String, Object>>) out.get("servers");
        assertEquals(Redactor.PLACEHOLDER, servers.get(0).get("password"));
    }

    @Test
    void redactMap_emptyMap() {
        Redactor r = new Redactor();
        assertEquals(0, r.redactMap(Map.of()).size());
    }

    @Test
    void redactMap_nullReturnsEmpty() {
        Redactor r = new Redactor();
        assertEquals(0, r.redactMap(null).size());
    }

    @Test
    void redactString_redactsQuotedPassword() {
        Redactor r = new Redactor();
        String input = "{\"username\":\"alice\",\"password\":\"secret123\"}";
        String out = r.redactString(input);
        assertTrue(out.contains("\"password\":\"***\""));
        assertTrue(out.contains("\"username\":\"alice\""));
        assertEquals(false, out.contains("secret123"));
    }

    @Test
    void redactString_preservesNonSensitive() {
        Redactor r = new Redactor();
        String input = "{\"name\":\"alice\",\"age\":30}";
        String out = r.redactString(input);
        assertEquals(input, out);
    }

    @Test
    void redactString_handlesNull() {
        Redactor r = new Redactor();
        assertNull(r.redactString(null));
    }

    @Test
    void redactString_handlesEmpty() {
        Redactor r = new Redactor();
        assertEquals("", r.redactString(""));
    }

    @Test
    void withCustomKeys_addsNewPattern() {
        Redactor r = Redactor.withCustomKeys("my_custom_key");
        assertTrue(r.isSensitive("my_custom_key"));
        assertTrue(r.isSensitive("password")); // default still works
    }

    @Test
    void customKeys_onlyMatchesProvidedPatterns() {
        Redactor r = new Redactor(List.of("custom"));
        assertTrue(r.isSensitive("custom"));
        assertEquals(false, r.isSensitive("password"));
    }

    @Test
    void redactMap_preservesInsertionOrder() {
        Redactor r = new Redactor();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("a", "1");
        input.put("password", "x");
        input.put("b", "2");
        Map<String, Object> out = r.redactMap(input);
        var iter = out.keySet().iterator();
        assertEquals("a", iter.next());
        assertEquals("password", iter.next());
        assertEquals("b", iter.next());
    }

    @Test
    void sensitiveKeys_returnsConfiguredSet() {
        Redactor r = new Redactor();
        assertTrue(r.sensitiveKeys().contains("password"));
        assertTrue(r.sensitiveKeys().contains("token"));
    }

    @Test
    void redactMap_doesNotMutateInput() {
        Redactor r = new Redactor();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("password", "secret");
        r.redactMap(input);
        assertEquals("secret", input.get("password"));
    }
}
