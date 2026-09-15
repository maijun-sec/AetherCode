package org.aethercode.protocol.methods;

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
 * contract test for the R270 (2026-09-15) extension to
 * {@code listSessions}: each row now carries a
 * {@code lastAgentEvent} field — a one-line summary of
 * the most recent agent activity in the transcript.
 * The companion of {@code preview} which captures the
 * FIRST user message; together they power the
 * Claude Code / OpenCode style two-line summary in
 * the desktop SessionListRow.
 *
 * <p>Test data layout (per session):
 * <pre>
 *   line 1: {role:user, text:hello}
 *   line 2: {role:assistant, text:thinking...}
 *   line 3: {role:assistant, tool_use:[{name:file_write, input:{file_path:...}}]}
 *   line 4: {role:user, tool_result:[{...}]}
 *   line 5: {role:assistant, text:done}
 * </pre>
 * lastAgentEvent picks the LAST assistant line (line 5
 * when present, or line 3 if line 5 is missing) and
 * renders either:
 *   - tool_use → "tool_name path_tail"
 *   - text     → first 80 chars
 */
class AetherCodeMethodsR270Test {

    private static AetherCodeEngine engineFor(Path cwd) {
        return new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
    }

    private static AetherCodeMethods methodsWith(Path cwd, List<Object> out) {
        return new AetherCodeMethods(engineFor(cwd), n -> out.add(n));
    }

    @Test
    void listSessions_lastAgentEvent_summarizesLastToolUse(@TempDir Path cwd) throws Exception {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});

        String id = "tool-use-session-" + System.nanoTime();
        Path file = store.dir().resolve(id + ".jsonl");
        Files.writeString(file, String.join("\n", List.of(
                "{\"id\":\"m1\",\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"请排序\"}]}",
                "{\"id\":\"m2\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"好的我来写\"}]}",
                "{\"id\":\"m3\",\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"tu1\",\"name\":\"file_write\",\"input\":{\"file_path\":\"D:\\\\tmp\\\\abc_1\\\\src\\\\test\\\\HeapSortTest.java\",\"content\":\"class HeapSortTest\"}}]}",
                "{\"id\":\"m4\",\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"tu1\",\"content\":\"ok\"}]}",
                "{\"id\":\"m5\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"已完成\"}]}"
        )), StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.listSessions(Map.of("withPreview", true));
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        Map<String, Object> target = sessions.stream()
                .filter(s -> id.equals(s.get("id")))
                .findFirst().orElseThrow();
        String lastEvent = (String) target.get("lastAgentEvent");
        // The very last assistant line is text "已完成"; the
        // tool_use on line 3 is older. extractLastAgentEvent
        // walks backwards and picks the LAST assistant line
        // that has either a tool_use or text — so this is "已完成".
        assertThat(lastEvent).isEqualTo("已完成");
    }

    @Test
    void listSessions_lastAgentEvent_fallsBackToToolUseWhenLastLineIsPlainText(@TempDir Path cwd) throws Exception {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});

        String id = "tool-tail-" + System.nanoTime();
        Path file = store.dir().resolve(id + ".jsonl");
        // The very last line is an empty assistant (just a
        // tool_use inside, no text). We expect the scan to
        // keep walking backwards and find the tool_use.
        Files.writeString(file, String.join("\n", List.of(
                "{\"id\":\"m1\",\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"go\"}]}",
                "{\"id\":\"m2\",\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"tu1\",\"name\":\"bash\",\"input\":{\"command\":\"ls -la\",\"cwd\":\"/tmp\"}}]}",
                "{\"id\":\"m3\",\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"tu2\",\"name\":\"file_read\",\"input\":{\"file_path\":\"/var/log/app.log\"}}]}"
        )), StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.listSessions(Map.of("withPreview", true));
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        Map<String, Object> target = sessions.stream()
                .filter(s -> id.equals(s.get("id")))
                .findFirst().orElseThrow();
        String lastEvent = (String) target.get("lastAgentEvent");
        // The very last assistant line is m3: file_read on
        // /var/log/app.log. We trim the path tail so the
        // preview is human-readable.
        assertThat(lastEvent).isEqualTo("file_read app.log");
    }

    @Test
    void listSessions_lastAgentEvent_emptyForBrandNewSession(@TempDir Path cwd) throws Exception {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});

        String id = "empty-session-" + System.nanoTime();
        Files.writeString(store.dir().resolve(id + ".jsonl"), "",
                StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.listSessions(Map.of("withPreview", true));
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        Map<String, Object> target = sessions.stream()
                .filter(s -> id.equals(s.get("id")))
                .findFirst().orElseThrow();
        // No assistant message → empty string. The desktop
        // renders nothing in the second line.
        assertThat(target.get("lastAgentEvent")).isEqualTo("");
    }

    @Test
    void listSessions_lastAgentEvent_capsAt80Chars(@TempDir Path cwd) throws Exception {
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});

        String id = "long-text-" + System.nanoTime();
        Path file = store.dir().resolve(id + ".jsonl");
        String longText = "我".repeat(120);  // 120-char Chinese text
        Files.writeString(file, String.join("\n", List.of(
                "{\"id\":\"m1\",\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"go\"}]}",
                "{\"id\":\"m2\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\""
                        + longText + "\"}]}"
        )), StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.listSessions(Map.of("withPreview", true));
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        Map<String, Object> target = sessions.stream()
                .filter(s -> id.equals(s.get("id")))
                .findFirst().orElseThrow();
        String lastEvent = (String) target.get("lastAgentEvent");
        // 80-char cap + ellipsis. The cap is applied AFTER
        // whitespace-collapse so the trim always reads clean.
        assertThat(lastEvent.length()).isEqualTo(80);
        assertThat(lastEvent).endsWith("…");
    }

    @Test
    void listSessions_lastAgentEvent_missingWhenWithPreviewFalse(@TempDir Path cwd) throws Exception {
        // When the caller passes withPreview=false, neither
        // preview nor lastAgentEvent are populated.
        // lastAgentEvent is bundled with the existing
        // withPreview gate so we don't do two transcript
        // scans per listSessions. The caller must opt in
        // explicitly (or omit the param — withPreview
        // defaults to true since R224).
        AetherCodeEngine engine = engineFor(cwd);
        SessionStore store = new SessionStore(cwd.resolve(".aethercode/sessions"));
        engine.setSessionStore(store);
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});

        String id = "no-with-preview-" + System.nanoTime();
        Files.writeString(store.dir().resolve(id + ".jsonl"),
                "{\"id\":\"m1\",\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"tu1\",\"name\":\"bash\",\"input\":{\"command\":\"ls\"}}]}",
                StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.listSessions(Map.of("withPreview", false));
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) r.get("sessions");
        Map<String, Object> target = sessions.stream()
                .filter(s -> id.equals(s.get("id")))
                .findFirst().orElseThrow();
        assertThat(target).doesNotContainKey("lastAgentEvent");
        assertThat(target).doesNotContainKey("preview");
    }
}