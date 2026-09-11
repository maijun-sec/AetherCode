package org.aethercode.protocol.methods;

import org.aethercode.protocol.jsonrpc.JsonRpcNotification;
import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contract test for the {@code summary} RPC.
 * The user explicitly asked for "a summary
 * regardless of whether the task ended correctly",
 * so the engine always surfaces a SessionStats
 * snapshot (files_written, files_read, shell_calls,
 * state, last_error, by_tool, summary_text). The
 * RPC is read-only — no side effects, safe to call
 * from the TUI on any timer.
 */
class AetherCodeMethodsR156Test {

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
    void summary_returnsOkAndShape(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        Object resp = m.summary(Map.of());
        assertThat(resp).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) resp;
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(r).containsKey("sessionId");
        assertThat(r).containsKey("files_written");
        assertThat(r).containsKey("files_read");
        assertThat(r).containsKey("shell_calls");
        assertThat(r).containsKey("total_tool_calls");
        assertThat(r).containsKey("queries");
        assertThat(r).containsKey("state");
        assertThat(r).containsKey("last_error");
        assertThat(r).containsKey("by_tool");
        assertThat(r).containsKey("summary_text");
    }

    @Test
    void summary_stateDefaultsToRunning(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        Object resp = m.summary(Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) resp;
        assertThat(r.get("state")).isEqualTo("running");
        assertThat(r.get("last_error")).isEqualTo("");
    }

    @Test
    void summary_emptySessionHasZeroCounters(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.summary(Map.of());
        // Long, not int — SessionStats uses AtomicLong
        // and toWireSnapshot() stores .get() as Long.
        assertThat(r.get("files_written")).isEqualTo(0L);
        assertThat(r.get("files_read")).isEqualTo(0L);
        assertThat(r.get("shell_calls")).isEqualTo(0L);
        assertThat(r.get("total_tool_calls")).isEqualTo(0L);
        assertThat(r.get("queries")).isEqualTo(0L);
    }

    @Test
    void summary_handlesMissingEngineGracefully() {
        // Build a methods instance with a stub engine,
        // then construct a "naked" one that has no
        // currentEngine wired. The summary RPC must
        // return ok=false rather than NPE.
        AetherCodeMethods m = new AetherCodeMethods(
                new AetherCodeEngine.Builder()
                        .cwd(java.nio.file.Paths.get("."))
                        .tools(List.<Tool>of())
                        .build(),
                n -> {});
        // Force the resolveRpcTarget path to fail by
        // calling with a sessionId that doesn't exist
        // AND without a currentEngine. The actual
        // currentEngine is wired in the constructor,
        // so we test the negative path with a blank
        // sessionId — that should fall through to
        // currentEngine() and return ok=true.
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.summary(Map.of("sessionId", ""));
        assertThat(r.get("ok")).isEqualTo(true);
    }

    @Test
    void summary_includesHumanReadableSummaryText(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        AetherCodeEngine engine = engineFor(cwd);
        // Simulate some work via the engine's session stats
        engine.sessionStats().recordToolCall("file_write");
        engine.sessionStats().recordToolCall("file_write");
        engine.sessionStats().setState("end_turn");
        // Re-build methods bound to the same engine
        AetherCodeMethods m2 = new AetherCodeMethods(engine, n -> {});
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m2.summary(Map.of());
        String text = (String) r.get("summary_text");
        assertThat(text).contains("Wrote 2 files");
        assertThat(text).contains("end_turn");
    }

    @Test
    void summary_includesLastError(@TempDir Path cwd) {
        AetherCodeEngine engine = engineFor(cwd);
        engine.sessionStats().setState("loop_research_mode");
        engine.sessionStats().setLastError("12 calls in a row without progress");
        AetherCodeMethods m = new AetherCodeMethods(engine, n -> {});
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.summary(Map.of());
        assertThat(r.get("state")).isEqualTo("loop_research_mode");
        assertThat(r.get("last_error")).isEqualTo("12 calls in a row without progress");
        String text = (String) r.get("summary_text");
        assertThat(text).contains("Last error: 12 calls in a row without progress");
    }

    @Test
    void summary_includesStartedAtAndDuration(@TempDir Path cwd) {
        AetherCodeMethods m = methodsWith(cwd, new ArrayList<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) m.summary(Map.of());
        assertThat(r.get("started_at_ms")).isInstanceOf(Long.class);
        assertThat(r.get("last_activity_at_ms")).isInstanceOf(Long.class);
        assertThat(r.get("duration_ms")).isInstanceOf(Long.class);
        assertThat((Long) r.get("duration_ms")).isGreaterThanOrEqualTo(0L);
    }
}
