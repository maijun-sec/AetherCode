package org.aethercode.core.agent;

import org.aethercode.core.app.AppState;
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSummaryTest {

    @Test
    void emptySessionReturnsEmptyCard() {
        AgentSummary s = new AgentSummary();
        AgentSummary.Card c = s.generate(new AppState("sid", Path.of("/x")));
        assertThat(c.userMessages()).isZero();
        assertThat(c.title()).isEqualTo("(untitled session)");
    }

    @Test
    void nullAppStateHandledGracefully() {
        AgentSummary s = new AgentSummary();
        AgentSummary.Card c = s.generate(null);
        assertThat(c).isNotNull();
    }

    @Test
    void firstUserMessageIsTitle() {
        AppState app = new AppState("sid", Path.of("/x"));
        app.appendMessage(userMsg("Refactor the cache layer to support TTL."));
        app.appendMessage(assistantMsg("ok I'll start"));
        AgentSummary s = new AgentSummary();
        AgentSummary.Card c = s.generate(app);
        assertThat(c.title()).isEqualTo("Refactor the cache layer to support TTL.");
    }

    @Test
    void countsUserAssistantTool() {
        AppState app = new AppState("sid", Path.of("/x"));
        app.appendMessage(userMsg("hi"));
        app.appendMessage(assistantMsg("ok"));
        app.appendMessage(userMsg("bye"));
        app.appendMessage(assistantMsg("bye"));
        app.appendMessage(toolResultMsg("ok"));
        AgentSummary s = new AgentSummary();
        AgentSummary.Card c = s.generate(app);
        assertThat(c.userMessages()).isEqualTo(2);
        assertThat(c.assistantMessages()).isEqualTo(2);
        assertThat(c.toolMessages()).isEqualTo(1);
    }

    @Test
    void extractsFilePaths() {
        AppState app = new AppState("sid", Path.of("/x"));
        app.appendMessage(userMsg("Open src/main/java/Foo.java and src/main/java/Bar.java"));
        AgentSummary s = new AgentSummary();
        AgentSummary.Card c = s.generate(app);
        assertThat(c.fileTouched()).contains("src/main/java/Foo.java", "src/main/java/Bar.java");
    }

    @Test
    void extractsToolNames() {
        AppState app = new AppState("sid", Path.of("/x"));
        app.appendMessage(assistantToolUse("file_read", Map.of("file_path", "/x")));
        app.appendMessage(assistantToolUse("bash", Map.of("command", "ls")));
        AgentSummary s = new AgentSummary();
        AgentSummary.Card c = s.generate(app);
        assertThat(c.toolsUsed()).contains("file_read", "bash");
    }

    @Test
    void renderBodyIncludesAllSections() {
        AppState app = new AppState("sid", Path.of("/x"));
        app.appendMessage(userMsg("read src/main/java/foo.md"));
        app.appendMessage(assistantToolUse("file_read", Map.of()));
        AgentSummary s = new AgentSummary();
        AgentSummary.Card c = s.generate(app);
        String out = s.render(c);
        assertThat(out).contains("AgentSummary");
        assertThat(out).contains("messages:");
        assertThat(out).contains("tokens:");
        assertThat(out).contains("tools used:");
        assertThat(out).contains("files:");
    }

    @Test
    void toolPoolToolsAreIncluded() {
        AppState app = new AppState("sid", Path.of("/x"));
        AgentSummary s = new AgentSummary();
        // even with no messages, tools in the pool show up in toolsUsed
        AgentSummary.Card c = s.generate(app);
        // pool is empty by default
        assertThat(c.toolsUsed()).isEmpty();
    }

    @Test
    void multiBulletToolsListCapped() {
        AppState app = new AppState("sid", Path.of("/x"));
        for (int i = 0; i < 12; i++) {
            app.appendMessage(assistantToolUse("tool" + i, Map.of()));
        }
        AgentSummary s = new AgentSummary();
        AgentSummary.Card c = s.generate(app);
        assertThat(c.toolsUsed()).hasSize(12);
        // body has at most 8 explicit + "(+N more)"
        String body = s.render(c);
        assertThat(body).contains("+4 more");
    }

    @Test
    void tokenEstimateIsRoughlyCharQuartile() {
        AppState app = new AppState("sid", Path.of("/x"));
        String fourChars = "abcd"; // ~1 token
        app.appendMessage(userMsg(fourChars.repeat(20))); // 80 chars
        AgentSummary s = new AgentSummary();
        AgentSummary.Card c = s.generate(app);
        assertThat(c.totalTokens()).isBetween(15, 25);
    }

    @Test
    void multiLineFirstUserMessageTrimsToFirstLine() {
        AppState app = new AppState("sid", Path.of("/x"));
        app.appendMessage(userMsg("title-line\nsecond line ignored\nthird line"));
        AgentSummary s = new AgentSummary();
        AgentSummary.Card c = s.generate(app);
        assertThat(c.title()).isEqualTo("title-line");
    }

    // ----- helpers -----

    private static Message userMsg(String text) {
        return new Message("u", Role.USER, List.of(new ContentBlock.TextBlock(text)), Instant.now(), Map.of());
    }
    private static Message assistantMsg(String text) {
        return new Message("a", Role.ASSISTANT, List.of(new ContentBlock.TextBlock(text)), Instant.now(), Map.of());
    }
    private static Message assistantToolUse(String name, Map<String, Object> input) {
        return new Message("a", Role.ASSISTANT, List.of(new ContentBlock.ToolUseBlock("id-" + name, name, input)),
                Instant.now(), Map.of());
    }
    private static Message toolResultMsg(String content) {
        return new Message("t", Role.TOOL_RESULT,
                List.of(new ContentBlock.ToolResultBlock("id-1", content, false)),
                Instant.now(), Map.of());
    }
}
