package org.aethercode.workflows.ssd;

import org.aethercode.workflows.ssd.SsdRunner.AbortException;
import org.aethercode.workflows.ssd.SsdRunner.LlmFn;
import org.aethercode.workflows.ssd.SsdRunner.Logger;
import org.aethercode.workflows.ssd.SsdRunner.PhaseResult;
import org.aethercode.workflows.ssd.SsdRunner.ReplFn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R236 鈥?exercises SsdRunner with mocked {@link LlmFn} and
 * {@link ReplFn}. The goal is to verify the orchestration:
 * phase ordering, revision propagation, REPL-driven loops,
 * abort semantics, and clean_output behaviour. The model is
 * never contacted.
 */
class SsdRunnerTest {

    /** Minimal config with all four required phases. The prompts
     *  don't have to be realistic; the runner doesn't read them
     *  directly 鈥?the LLM mock does. */
    private String minimalYaml() {
        return """
                version: 1
                name: test-ssd
                description: test config
                hardRules: NO-TOOLS
                maxWaitMs: 5000
                idleEndMs: 100
                artefactRoot: .aethercode/ssd
                phases:
                  - id: spec
                    order: 1
                    file: spec.md
                    title: Spec
                    systemPrompt: SYS-SPEC
                    userPromptTemplate: "SPEC user {{feature}} intent={{intent}}"
                    userRevisionTemplate: "SPEC-REV prior={{priorContent}} rev={{revision}}"
                    maxTokens: 1024
                  - id: design
                    order: 2
                    file: design.md
                    title: Design
                    systemPrompt: SYS-DESIGN
                    userPromptTemplate: "DESIGN spec={{specContent}}"
                    userRevisionTemplate: "DESIGN-REV prior={{priorContent}} rev={{revision}}"
                    maxTokens: 1024
                  - id: tasks
                    order: 3
                    file: tasks.md
                    title: Tasks
                    systemPrompt: SYS-TASKS
                    userPromptTemplate: "TASKS design={{priorContent}}"
                    userRevisionTemplate: "TASKS-REV prior={{priorContent}} rev={{revision}}"
                    maxTokens: 1024
                  - id: dev
                    order: 4
                    file: dev.log
                    title: Dev
                    systemPrompt: SYS-DEV
                    userPromptTemplate: "DEV task={{taskId}} files={{taskFiles}}"
                    userRevisionTemplate: "DEV-REV"
                    maxTokens: 1024
                """;
    }

    @Test
    void runsAllFourPhasesOnFirstInvocation(@TempDir Path tmp) throws Exception {
        SsdConfig cfg = SsdConfig.parse(minimalYaml());
        SsdRunner runner = new SsdRunner(cfg);
        AtomicInteger calls = new AtomicInteger();
        List<String> seenSystemPrompts = new ArrayList<>();
        LlmFn llm = (sys, usr, mt) -> {
            calls.incrementAndGet();
            seenSystemPrompts.add(sys);
            return "# " + sys + "\n\nbody\n";
        };
        List<String> log = new ArrayList<>();
        Logger logger = log::add;

        List<PhaseResult> results = runner.runAll(
                tmp, "feat", "add foo", 1, false, true, llm,
                (title, path, content) -> Optional.of(""), logger, 4);

        // 3 LLM calls: spec, design, tasks. The dev phase writes
        // a (mostly empty) dev.log but the mock LLM body
        // ("# SYS-DEV\n\nbody\n") doesn't parse as a task
        // table, so dev makes 0 calls. See parsesTaskTable for
        // the dev-phase LLM path.
        assertThat(calls.get()).isEqualTo(3);
        assertThat(results).hasSize(4);
        assertThat(results.get(0).phaseId()).isEqualTo("spec");
        assertThat(results.get(3).phaseId()).isEqualTo("dev");
        // The system prompts reach the LLM as-is (we just record them).
        assertThat(seenSystemPrompts).containsExactly("SYS-SPEC", "SYS-DESIGN", "SYS-TASKS");
        // Artefacts landed on disk.
        Path featureDir = tmp.resolve(".aethercode").resolve("ssd").resolve("feat");
        assertThat(Files.isRegularFile(featureDir.resolve("spec.md"))).isTrue();
        assertThat(Files.isRegularFile(featureDir.resolve("design.md"))).isTrue();
        assertThat(Files.isRegularFile(featureDir.resolve("tasks.md"))).isTrue();
        assertThat(Files.isRegularFile(featureDir.resolve("dev.log"))).isTrue();
    }

    @Test
    void revisionReRunsTheSamePhase(@TempDir Path tmp) throws Exception {
        SsdConfig cfg = SsdConfig.parse(minimalYaml());
        SsdRunner runner = new SsdRunner(cfg);
        // LLM produces a different body on each call so we can
        // see the revision took effect.
        AtomicInteger calls = new AtomicInteger();
        LlmFn llm = (sys, usr, mt) -> {
            int n = calls.incrementAndGet();
            return "# body #" + n + "\n";
        };
        // REPL: first call returns a revision; subsequent calls
        // return "ok" (accept). The spec phase should therefore
        // be invoked twice.
        AtomicInteger replCalls = new AtomicInteger();
        ReplFn repl = (title, path, content) -> {
            int n = replCalls.incrementAndGet();
            if (n == 1) return Optional.of("add NFR-9");
            return Optional.of("");
        };
        List<PhaseResult> results = runner.runAll(
                tmp, "feat", "intent", 1, false, false, llm, repl, line -> {}, 4);

        // The mock LLM always returns "# body #N", which is not
        // a valid task table; the dev phase therefore writes an
        // empty dev.log without making an LLM call. So the
        // expected call count is: spec x 2 + design x 1 + tasks x 1
        // = 4. (A separate test, parsesTaskTable, covers the
        // dev-phase LLM call path with a real table.)
        assertThat(calls.get()).isEqualTo(4);
        assertThat(results).hasSize(4);
        assertThat(results.get(0).phaseId()).isEqualTo("spec");
        assertThat(results.get(0).revisions()).isEqualTo(1);
        assertThat(results.get(1).revisions()).isEqualTo(0);
        // The final spec.md on disk is the second LLM call's body.
        Path spec = tmp.resolve(".aethercode").resolve("ssd").resolve("feat").resolve("spec.md");
        String body = Files.readString(spec, StandardCharsets.UTF_8);
        assertThat(body).contains("body #2");
    }

    @Test
    void revisionPromptContainsPriorContentAndRevision(@TempDir Path tmp) throws Exception {
        SsdConfig cfg = SsdConfig.parse(minimalYaml());
        SsdRunner runner = new SsdRunner(cfg);
        // LLM records the user prompt of every call so we can
        // assert the revision prompt actually carried the
        // revision text and the prior content.
        List<String> userPrompts = new ArrayList<>();
        LlmFn llm = (sys, usr, mt) -> {
            userPrompts.add(usr);
            return "# ok\n";
        };
        // The repl counter makes the very first spec-phase call
        // a revision and every subsequent call an accept. The
        // spec phase is invoked twice: once for the initial
        // draft, once for the revision. Both user prompts land
        // in userPrompts[] so we can assert on the second.
        AtomicInteger replCalls = new AtomicInteger();
        ReplFn repl = (title, path, content) -> {
            // Only Spec phase gets a revision; everything else
            // accepts. The first Spec REPL returns a revision;
            // the second (post-revision) accepts.
            if (title.equals("Spec") && replCalls.incrementAndGet() == 1) {
                return Optional.of("ADD-NFR-9");
            }
            return Optional.of("");
        };
        runner.runAll(tmp, "feat", "intent", 1, false, false, llm, repl, line -> {}, 4);

        // The 2nd call to the LLM is the spec revision; user
        // prompt must contain both the prior content placeholder
        // and the revision text.
        assertThat(userPrompts.size()).isGreaterThanOrEqualTo(2);
        String revisionPrompt = userPrompts.get(1);
        assertThat(revisionPrompt).contains("ADD-NFR-9");
        assertThat(revisionPrompt).contains("prior=");
    }

    @Test
    void abortPropagates(@TempDir Path tmp) throws Exception {
        SsdConfig cfg = SsdConfig.parse(minimalYaml());
        SsdRunner runner = new SsdRunner(cfg);
        LlmFn llm = (sys, usr, mt) -> "# body\n";
        ReplFn repl = (title, path, content) -> {
            // user types 'q' on the very first REPL
            if (title.equals("Spec")) return null;
            return Optional.of("");
        };
        assertThatThrownBy(() -> runner.runAll(
                tmp, "feat", "intent", 1, false, false, llm, repl, line -> {}, 4))
                .isInstanceOf(AbortException.class)
                .hasMessageContaining("aborted");
    }

    @Test
    void fromPhaseSkipsEarlierPhases(@TempDir Path tmp) throws Exception {
        SsdConfig cfg = SsdConfig.parse(minimalYaml());
        SsdRunner runner = new SsdRunner(cfg);
        AtomicInteger calls = new AtomicInteger();
        LlmFn llm = (sys, usr, mt) -> { calls.incrementAndGet(); return "# x\n"; };
        // Start at phase 2 (design). spec should be skipped.
        runner.runAll(tmp, "feat", "intent", 2, false, true, llm,
                (t, p, c) -> Optional.of(""), line -> {}, 4);
        // design + tasks make 2 LLM calls; dev has no parseable
        // task table from "# x\n" so it makes 0. Total: 2.
        assertThat(calls.get()).isEqualTo(2);
        // spec.md was not written.
        Path spec = tmp.resolve(".aethercode").resolve("ssd").resolve("feat").resolve("spec.md");
        assertThat(Files.exists(spec)).isFalse();
    }

    @Test
    void existingFileAsksForReuseOrRevision(@TempDir Path tmp) throws Exception {
        SsdConfig cfg = SsdConfig.parse(minimalYaml());
        SsdRunner runner = new SsdRunner(cfg);
        // Pre-write spec.md so the runner sees it on disk.
        Path featureDir = tmp.resolve(".aethercode").resolve("ssd").resolve("feat");
        Files.createDirectories(featureDir);
        Path spec = featureDir.resolve("spec.md");
        Files.writeString(spec, "# existing\n");
        // Track LLM calls (design + tasks will still run, but
        // spec is REUSED so the LLM is never called for it).
        AtomicInteger calls = new AtomicInteger();
        LlmFn llm = (sys, usr, mt) -> { calls.incrementAndGet(); return "# x\n"; };
        // REPL: type "" to keep.
        ReplFn repl = (t, p, c) -> Optional.of("");
        runner.runAll(tmp, "feat", "intent", 1, false, false, llm, repl, line -> {}, 4);
        // Existing file is unchanged.
        assertThat(Files.readString(spec, StandardCharsets.UTF_8)).isEqualTo("# existing\n");
        // spec was reused, so only design + tasks called the LLM.
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void forceRegeneratesEvenWhenFileExists(@TempDir Path tmp) throws Exception {
        SsdConfig cfg = SsdConfig.parse(minimalYaml());
        SsdRunner runner = new SsdRunner(cfg);
        Path featureDir = tmp.resolve(".aethercode").resolve("ssd").resolve("feat");
        Files.createDirectories(featureDir);
        Path spec = featureDir.resolve("spec.md");
        Files.writeString(spec, "# existing\n");
        AtomicInteger calls = new AtomicInteger();
        LlmFn llm = (sys, usr, mt) -> { calls.incrementAndGet(); return "# brand new\n"; };
        // --force=true, auto=true (no REPL).
        runner.runAll(tmp, "feat", "intent", 1, true, true, llm,
                (t, p, c) -> Optional.of(""), line -> {}, 4);
        // spec + design + tasks = 3 calls; dev has no parseable
        // table from "# brand new\n" so 0. Total: 3.
        assertThat(calls.get()).isEqualTo(3);
        assertThat(Files.readString(spec, StandardCharsets.UTF_8)).isEqualTo("# brand new\n");
    }

    @Test
    void parsesTaskTable(@TempDir Path tmp) throws Exception {
        SsdConfig cfg = SsdConfig.parse(minimalYaml());
        SsdRunner runner = new SsdRunner(cfg);
        Path featureDir = tmp.resolve(".aethercode").resolve("ssd").resolve("feat");
        Files.createDirectories(featureDir);
        Files.writeString(featureDir.resolve("spec.md"), "# s\n");
        Files.writeString(featureDir.resolve("design.md"), "# d\n");
        // tasks.md with two task rows in the canonical layout.
        Files.writeString(featureDir.resolve("tasks.md"), """
                # Phase 3
                ## M-3.1
                | ID | Title | Files | Est. min | Acceptance |
                | --- | --- | --- | --- | --- |
                | T-3.1.1 | First | `a.java`, `b.java` | 30 | compiles |
                | T-3.1.2 | Second | `c.java` | 15 | runs |
                """);
        AtomicInteger llmCalls = new AtomicInteger();
        List<String> userPrompts = new ArrayList<>();
        LlmFn llm = (sys, usr, mt) -> {
            llmCalls.incrementAndGet();
            userPrompts.add(usr);
            // The LLM emits a markdown body that survives
            // clean_output (starts with `#`). The marker is
            // tacked on at the end so we can verify the
            // runner did not strip it.
            return "# Task Implementation\n\nWrote a.java and b.java.\n\nTASK DONE\n";
        };
        // Start from phase 4 (dev) only.
        runner.runAll(tmp, "feat", "intent", 4, false, true, llm,
                (t, p, c) -> Optional.of(""), line -> {}, 4);
        // 2 tasks -> 2 LLM calls.
        assertThat(llmCalls.get()).isEqualTo(2);
        // Each dev LLM call received its own task id in the user prompt.
        assertThat(userPrompts).anyMatch(p -> p.contains("T-3.1.1") && p.contains("a.java"));
        assertThat(userPrompts).anyMatch(p -> p.contains("T-3.1.2") && p.contains("c.java"));
        Path devLog = featureDir.resolve("dev.log");
        String body = Files.readString(devLog, StandardCharsets.UTF_8);
        assertThat(body).contains("T-3.1.1");
        assertThat(body).contains("T-3.1.2");
        assertThat(body).contains("TASK DONE");
    }

    @Test
    void cleanOutputStripsThinkBlocksAndPreamble() {
        String raw = "<think>\nlet me think\n</think>\n"
                + "Draft: this is a draft\n"
                + "Note: ignore this\n"
                + "I will do this\n"
                + "\n"
                + "# Real Header\n"
                + "body\n";
        String cleaned = SsdRunner.cleanOutput(raw);
        assertThat(cleaned).contains("# Real Header");
        assertThat(cleaned).contains("body");
        assertThat(cleaned).doesNotContain("Draft:");
        assertThat(cleaned).doesNotContain("<think>");
        assertThat(cleaned).doesNotContain("Note:");
    }

    @Test
    void cleanOutputCollapsesBlankLines() {
        String raw = "# H\n\n\n\n\nbody\n\n\n\n\nbody2\n";
        String cleaned = SsdRunner.cleanOutput(raw);
        assertThat(cleaned).doesNotMatch("\n\n\n");
    }

    @Test
    void cleanOutputHandlesEmptyAndNull() {
        assertThat(SsdRunner.cleanOutput(null)).isEmpty();
        assertThat(SsdRunner.cleanOutput("")).isEmpty();
        assertThat(SsdRunner.cleanOutput("   \n   ")).isEmpty();
    }

    @Test
    void cleanOutputStripsThinkBlocksEvenWhenSplitAcrossLines() {
        // The do-while loop in cleanOutput handles the
        // "model emits multiple think blocks back to back" case
        // 鈥?each iteration peels off the first block until
        // the text is stable. This is the realistic version
        // of the corner case; the truly-nested (inner block
        // has its own <think> tag) case is rare and we accept
        // a partial removal there.
        String raw = "<think>first think</think>"
                + "<think>second think</think>"
                + "# H\nbody\n";
        String cleaned = SsdRunner.cleanOutput(raw);
        assertThat(cleaned).doesNotContain("<think>");
        assertThat(cleaned).contains("# H");
    }

    @Test
    void parseTasksHandlesBacktickedFileLists() {
        String body = """
                | T-3.1.1 | First | `a.java`, `b.java` | 30 | compiles |
                """;
        List<SsdRunner.TaskRow> tasks = SsdRunner.parseTasks(body);
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).id()).isEqualTo("T-3.1.1");
        assertThat(tasks.get(0).files()).containsExactly("a.java", "b.java");
    }

    @Test
    void parseTasksFallsBackToNumberedList() {
        // No table; the parser falls back to a numbered list so a
        // slightly-misformatted tasks.md still gets implemented.
        String body = "1. first thing\n2. second thing\n";
        List<SsdRunner.TaskRow> tasks = SsdRunner.parseTasks(body);
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).title()).isEqualTo("first thing");
        assertThat(tasks.get(0).id()).isEqualTo("T-fallback.1");
    }

    @Test
    void artefactDirResolvesAgainstCwd(@TempDir Path tmp) {
        SsdConfig cfg = SsdConfig.parse(minimalYaml());
        SsdRunner runner = new SsdRunner(cfg);
        Path d = runner.artefactDir(tmp, "foo");
        assertThat(d).isEqualTo(tmp.resolve(".aethercode").resolve("ssd").resolve("foo"));
    }

    @Test
    void toPhaseStopsAfterDesign(@TempDir Path tmp) throws Exception {
        SsdConfig cfg = SsdConfig.parse(minimalYaml());
        SsdRunner runner = new SsdRunner(cfg);
        AtomicInteger calls = new AtomicInteger();
        LlmFn llm = (sys, usr, mt) -> { calls.incrementAndGet(); return "# x\n"; };
        // Stop after phase 2 (design). spec + design = 2 calls.
        // tasks + dev should NOT run.
        List<PhaseResult> results = runner.runAll(tmp, "feat", "intent", 1, false, true, llm,
                (t, p, c) -> Optional.of(""), line -> {}, 2);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).phaseId()).isEqualTo("spec");
        assertThat(results.get(1).phaseId()).isEqualTo("design");
        // tasks.md was not written.
        Path tasks = tmp.resolve(".aethercode").resolve("ssd").resolve("feat").resolve("tasks.md");
        assertThat(Files.exists(tasks)).isFalse();
    }

    @Test
    void toPhaseValidatesRange(@TempDir Path tmp) {
        SsdConfig cfg = SsdConfig.parse(minimalYaml());
        SsdRunner runner = new SsdRunner(cfg);
        LlmFn llm = (sys, usr, mt) -> "# x\n";
        // fromPhase > toPhase is a hard fail.
        assertThatThrownBy(() -> runner.runAll(tmp, "feat", "intent", 3, false, true, llm,
                (t, p, c) -> Optional.of(""), line -> {}, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be <=");
        // toPhase out of range is a hard fail.
        assertThatThrownBy(() -> runner.runAll(tmp, "feat", "intent", 1, false, true, llm,
                (t, p, c) -> Optional.of(""), line -> {}, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toPhase");
    }
}

