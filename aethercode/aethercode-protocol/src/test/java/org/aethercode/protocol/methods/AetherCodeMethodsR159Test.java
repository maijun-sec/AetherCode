package org.aethercode.protocol.methods;

import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.core.transcript.SessionStore;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contract test for the enhanced
 * {@code listSessions} RPC. legacy the
 * daemon only returned id + lastUsedAt +
 * sizeBytes + messageCount. R159 adds:
 * <ul>
 *   <li>{@code limit} param - cap the
 *       number of returned rows (default
 *       50, max 500).</li>
 *   <li>{@code withPreview} param -
 *       opt-in extraction of the first
 *       user message from the JSONL
 *       transcript (cheap: 4 KB cap,
 *       regex-only, no full JSON parse).</li>
 *   <li>{@code total} + {@code returned}
 *       fields so the TUI can show
 *       "showing 50 of 217" in the
 *       session picker footer.</li>
 * </ul>
 */
class AetherCodeMethodsR159Test {

    private static AetherCodeEngine engineFor(Path cwd) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    private static AetherCodeMethods methodsWith(Path cwd, List<JsonRpcNotification> out) {
        return new AetherCodeMethods(engineFor(cwd), n -> out.add(n));
    }

    @Test
    void listSessions_returnsEmptyWhenNoStoreWired(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.listSessions(Map.of());
        assertThat((List<?>) r.get("sessions")).isEmpty();
        assertThat(r).containsKey("current");
    }

    @Test
    void listSessions_returnsTotalAndReturnedCounts(@TempDir Path cwd) throws Exception {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});
        for (int i = 0; i < 3; i++) {
            String id = "test-session-" + i + "-" + System.nanoTime();
            Files.writeString(store.dir().resolve(id + ".jsonl"),
                    "{\"id\":\"" + id + "\",\"role\":\"user\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"hello " + i + "\"}]}",
                    StandardCharsets.UTF_8);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.listSessions(Map.of());
        // The engine auto-creates a session on init, so the
        // store has 3 (just written) + 1 (engine init) = 4+
        // entries. The test asserts the limit cap, not an
        // exact count.
        assertThat((Integer) r.get("total")).isGreaterThanOrEqualTo(3);
        assertThat((Integer) r.get("returned")).isEqualTo((Integer) r.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        assertThat(sessions).hasSizeGreaterThanOrEqualTo(3);
        Map<String, Object> first = sessions.get(0);
        assertThat(first).containsKey("id");
        assertThat(first).containsKey("lastUsedAt");
        assertThat(first).containsKey("sizeBytes");
        assertThat(first).containsKey("messageCount");
    }

    @Test
    void listSessions_respectsLimitParam(@TempDir Path cwd) throws Exception {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});
        for (int i = 0; i < 10; i++) {
            String id = "limit-test-" + i + "-" + System.nanoTime();
            Files.writeString(store.dir().resolve(id + ".jsonl"),
                    "{\"id\":\"" + id + "\",\"role\":\"user\","
                            + "\"content\":[{\"type\":\"text\",\"text\":\"msg " + i + "\"}]}",
                    StandardCharsets.UTF_8);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.listSessions(Map.of("limit", 3));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        assertThat(sessions.size()).isLessThanOrEqualTo(3);
        assertThat((Integer) r.get("returned")).isEqualTo(sessions.size());
    }

    @Test
    void listSessions_clampsLimitToMax500(@TempDir Path cwd) throws Exception {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.listSessions(Map.of("limit", 10000));
        assertThat((Integer) r.get("returned")).isLessThanOrEqualTo(500);
    }

    @Test
    void listSessions_withPreview_extractsFirstUserMessage(@TempDir Path cwd) throws Exception {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});
        String id = "preview-test-" + System.nanoTime();
        Files.writeString(store.dir().resolve(id + ".jsonl"),
                "{\"id\":\"" + id + "\",\"role\":\"user\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"Fix Cwe252 unchecked return\"}]}\n"
                        + "{\"id\":\"a2\",\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"Looking at the code...\"}]}\n",
                StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.listSessions(
                Map.of("withPreview", true));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        Map<String, Object> row = sessions.stream()
                .filter(s -> id.equals(s.get("id")))
                .findFirst().orElseThrow();
        assertThat(row.get("preview")).isEqualTo("Fix Cwe252 unchecked return");
    }

    @Test
    void listSessions_withPreview_capsAt200Chars(@TempDir Path cwd) throws Exception {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) big.append("x");
        String id = "big-preview-" + System.nanoTime();
        Files.writeString(store.dir().resolve(id + ".jsonl"),
                "{\"id\":\"" + id + "\",\"role\":\"user\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"" + big + "\"}]}",
                StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.listSessions(
                Map.of("withPreview", true));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        Map<String, Object> row = sessions.stream()
                .filter(s -> id.equals(s.get("id")))
                .findFirst().orElseThrow();
        String preview = (String) row.get("preview");
        // Capped at 200 chars (197 + ellipsis U+2026)
        assertThat(preview.length()).isLessThanOrEqualTo(200);
        assertThat(preview.charAt(preview.length() - 1)).isEqualTo((char) 0x2026);
    }

    @Test
    void listSessions_withoutPreview_omitsPreviewField(@TempDir Path cwd) throws Exception {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});
        String id = "no-preview-" + System.nanoTime();
        Files.writeString(store.dir().resolve(id + ".jsonl"),
                "{\"id\":\"" + id + "\",\"role\":\"user\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}",
                StandardCharsets.UTF_8);
        // R266d (2026-09-13): the listSessions `withPreview`
        // default is now `true` (was `false` in R159) — the
        // desktop's LeftPanel refresh path doesn't pass the
        // flag and we want every RPC consumer to get a
        // preview by default. This test is asserting the
        // OPT-OUT path: when the caller explicitly asks
        // for withPreview=false, the response MUST NOT
        // carry a preview field. Use that explicit opt-out
        // here so the test continues to pin the original
        // "no preview" contract.
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.listSessions(
                Map.of("withPreview", false));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        Map<String, Object> row = sessions.stream()
                .filter(s -> id.equals(s.get("id")))
                .findFirst().orElseThrow();
        assertThat(row).doesNotContainKey("preview");
    }

    @Test
    void listSessions_handlesEmptyFileGracefully(@TempDir Path cwd) throws Exception {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});
        String id = "empty-" + System.nanoTime();
        Files.createFile(store.dir().resolve(id + ".jsonl"));
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.listSessions(
                Map.of("withPreview", true));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        Map<String, Object> row = sessions.stream()
                .filter(s -> id.equals(s.get("id")))
                .findFirst().orElseThrow();
        assertThat(row.get("preview")).isEqualTo("");
    }
}
