package org.aethercode.protocol.methods;

import org.aethercode.sdk.AetherCodeEngine;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contract test for the {@code getSystemPrompt}
 * JSON-RPC method. The method is what the TUI's
 * {@code /prompt} slash command calls; the test
 * locks the response shape so a future refactor
 * cannot silently break the surface.
 */
class GetSystemPromptRpcTest {

    @Test
    void getSystemPrompt_returnsStructuredPayload(@TempDir Path cwd) throws Exception {
        // Stage a real .aethercode/rules file so the
        // payload includes both built-in sections and
        // a user rules layer.
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("policy.md"),
                "R94E-SENTINEL\nuse tabs");

        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) new AetherCodeMethods(engine, n -> {}).getSystemPrompt(null);

        // Top-level shape: text / totalChars / sectionCount / sections
        assertThat(payload).containsKeys("text", "totalChars", "sectionCount", "sections");
        String text = (String) payload.get("text");
        assertThat(text)
                .as("getSystemPrompt.text should include the staged rules")
                .contains("R94E-SENTINEL")
                .contains("policy.md");
        assertThat((int) payload.get("totalChars")).isEqualTo(text.length());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) payload.get("sections");
        assertThat(sections).isNotEmpty();
        assertThat(sections).allSatisfy(s -> {
            assertThat(s).containsKeys("name", "length", "source", "firstLine");
            // The prior round contract: section source is a
            // free-form provenance label, never null.
            assertThat(s.get("source")).isNotNull();
        });
        // The prior round rules section must be present.
        boolean hasRules = sections.stream().anyMatch(s -> "rules".equals(s.get("name")));
        assertThat(hasRules)
                .as("sections list must include a 'rules' entry when rules are staged")
                .isTrue();
    }

    @Test
    void getSystemPrompt_firstLineIsShort(@TempDir Path cwd) throws Exception {
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) new AetherCodeMethods(engine, n -> {}).getSystemPrompt(null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) payload.get("sections");
        // Every firstLine must be presentable in a
        // single TUI row (<= 80 chars + ellipsis).
        for (Map<String, Object> s : sections) {
            String firstLine = (String) s.get("firstLine");
            assertThat(firstLine.length())
                    .as("section %s firstLine is too long: %s", s.get("name"), firstLine)
                    .isLessThanOrEqualTo(83);  // 80 + "..."
        }
    }

    @Test
    void getSystemPrompt_emptyRulesStillReturnsIdentityAndWorkflow(@TempDir Path cwd) throws Exception {
        // No rules directory staged — the payload must
        // still include the built-in identity and
        // workflow sections.
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) new AetherCodeMethods(engine, n -> {}).getSystemPrompt(null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) payload.get("sections");
        assertThat(sections).extracting(s -> s.get("name"))
                .contains("identity", "workflow");
    }

    // -------------------------------------------------------------------
    // getSystemPromptSection(name) drill-down
    // -------------------------------------------------------------------

    @Test
    void getSystemPromptSection_returnsFullTextForKnownName(@TempDir Path cwd) {
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>)
                new AetherCodeMethods(engine, n -> {}).getSystemPromptSection(Map.of("name", "workflow"));
        assertThat(payload)
                .as("workflow drill-down must succeed")
                .containsEntry("ok", true)
                .containsEntry("name", "workflow");
        // Full text comes back (no firstLine cap). The
        // default workflow is 9,717 chars so the text
        // must be a long string — well above the 80-char
        // firstLine cap that the table view uses.
        String text = (String) payload.get("text");
        assertThat(text).isNotNull();
        assertThat(text.length())
                .as("drill-down must return the full text, not the firstLine preview")
                .isGreaterThan(80);
        assertThat(payload.get("source")).isNotNull();
        assertThat((int) payload.get("length")).isEqualTo(text.length());
    }

    @Test
    void getSystemPromptSection_isCaseInsensitive(@TempDir Path cwd) {
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        // The TUI may pass the section name in any case
        // (the user types it). Drill-down must be
        // case-insensitive on the name.
        @SuppressWarnings("unchecked")
        Map<String, Object> lower = (Map<String, Object>)
                new AetherCodeMethods(engine, n -> {}).getSystemPromptSection(Map.of("name", "workflow"));
        @SuppressWarnings("unchecked")
        Map<String, Object> upper = (Map<String, Object>)
                new AetherCodeMethods(engine, n -> {}).getSystemPromptSection(Map.of("name", "WORKFLOW"));
        @SuppressWarnings("unchecked")
        Map<String, Object> mixed = (Map<String, Object>)
                new AetherCodeMethods(engine, n -> {}).getSystemPromptSection(Map.of("name", "WorkFlow"));
        assertThat(lower).containsEntry("ok", true);
        assertThat(upper).containsEntry("ok", true);
        assertThat(mixed).containsEntry("ok", true);
    }

    @Test
    void getSystemPromptSection_unknownNameReturnsAvailableList(@TempDir Path cwd) {
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>)
                new AetherCodeMethods(engine, n -> {}).getSystemPromptSection(Map.of("name", "rulez"));
        assertThat(payload)
                .as("drill-down on an unknown section must return ok=false")
                .containsEntry("ok", false);
        assertThat((String) payload.get("error"))
                .contains("section not found")
                .contains("rulez");
        // The `available` list lets the TUI show
        // "did you mean…?" without a second RPC.
        @SuppressWarnings("unchecked")
        List<String> available = (List<String>) payload.get("available");
        assertThat(available).isNotEmpty();
        assertThat(available).contains("identity", "workflow");
    }

    @Test
    void getSystemPromptSection_missingNameReturnsOkFalse(@TempDir Path cwd) {
        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        // params is null — drill-down has nothing to
        // search for; the contract is ok=false with a
        // clear error (NOT a crash, NOT an exception).
        @SuppressWarnings("unchecked")
        Map<String, Object> nullParams = (Map<String, Object>)
                new AetherCodeMethods(engine, n -> {}).getSystemPromptSection(null);
        assertThat(nullParams).containsEntry("ok", false);
        assertThat((String) nullParams.get("error")).contains("name");
        // Empty map — same outcome.
        @SuppressWarnings("unchecked")
        Map<String, Object> emptyMap = (Map<String, Object>)
                new AetherCodeMethods(engine, n -> {}).getSystemPromptSection(Map.of());
        assertThat(emptyMap).containsEntry("ok", false);
        // Bare string is a tolerated shortcut (treated as
        // the name). A bare "" string must still be
        // rejected as missing.
        @SuppressWarnings("unchecked")
        Map<String, Object> emptyStr = (Map<String, Object>)
                new AetherCodeMethods(engine, n -> {}).getSystemPromptSection("");
        assertThat(emptyStr).containsEntry("ok", false);
    }

    // -------------------------------------------------------------------
    // getSystemPrompt attaches rule file paths
    // -------------------------------------------------------------------

    @Test
    void getSystemPrompt_attachesPathsToRulesSection(@TempDir Path cwd) throws Exception {
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("style.md"), "use tabs");
        Files.writeString(rulesDir.resolve("api.md"), "be immutable");

        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) new AetherCodeMethods(engine, n -> {}).getSystemPrompt(null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) payload.get("sections");
        Map<String, Object> rules = sections.stream()
                .filter(s -> "rules".equals(s.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("rules section must be present"));
        // the rules section carries the file paths
        // that contributed. The TUI uses this to render
        // the paths column in /prompt.
        @SuppressWarnings("unchecked")
        List<String> paths = (List<String>) rules.get("paths");
        assertThat(paths)
                .as("rules section must carry the file paths the loader visited")
                .isNotNull()
                .hasSize(2);
        assertThat(paths).anyMatch(p -> p.endsWith("style.md"));
        assertThat(paths).anyMatch(p -> p.endsWith("api.md"));
    }

    @Test
    void getSystemPrompt_otherSectionsDoNotCarryPaths(@TempDir Path cwd) throws Exception {
        // Even when rules files are present, the
        // identity / workflow / environment / tooling
        // sections must NOT carry a `paths` field —
        // those sections are engine-internal, not
        // file-derived.
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("style.md"), "use tabs");

        AetherCodeEngine engine = new AetherCodeEngine.Builder()
                .cwd(cwd)
                .tools(List.<Tool>of())
                .build();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) new AetherCodeMethods(engine, n -> {}).getSystemPrompt(null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) payload.get("sections");
        for (Map<String, Object> s : sections) {
            if (!"rules".equals(s.get("name"))) {
                assertThat(s)
                        .as("non-rules section %s must not carry paths", s.get("name"))
                        .doesNotContainKey("paths");
            }
        }
    }
}
