package org.aethercode.workflows.sdd;

import org.aethercode.workflows.sdd.SddConfig.PhaseId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R292 — tests for {@link SddRunner}. Drives the 6-phase
 * orchestrator with a mock {@link SddRunner.LlmFn} + mock
 * {@link SddRunner.ReplFn} + mock {@link SddRunner.Logger},
 * exercising:
 *
 * <ul>
 *   <li>happy-path 6-phase run ({@code --auto}) — every phase
 *       produces an artefact and the runner writes a
 *       convergence.json with the converge-phase output.</li>
 *   <li>abort propagation — when the mock REPL returns null
 *       the runner throws {@link SddRunner.AbortException}.</li>
 *   <li>revision loop — the mock REPL returns revision text
 *       once then accepts; the runner runs the phase twice
 *       and reports {@code revisions=1}.</li>
 *   <li>{@code nextSlug} SEQUENTIAL policy — the second call
 *       increments from 001 to 002.</li>
 * </ul>
 */
class SddRunnerTest {

    private Path cwd;

    @BeforeEach
    void setUp() throws Exception {
        cwd = Files.createTempDirectory("sdd-runner-test");
    }

    @Test
    void auto_run_walks_all_6_phases_and_writes_artefacts() throws Exception {
        SddConfig config = SddConfig.fromBundled();
        SddRunner runner = new SddRunner(config);
        MockLlm llm = new MockLlm("# Generated\n\nbody");
        MockRepl repl = new MockRepl(/*auto*/ true, /*revisions*/ 0);
        TestLogger log = new TestLogger();

        List<SddRunner.PhaseResult> results = runner.runAll(
                cwd, "foo", "add a foo feature",
                /*fromOrder*/ 0, /*force*/ false, /*auto*/ true,
                llm, repl, log, /*toOrder*/ 7);

        // 6 required + 2 optional = 8 phases all touched
        assertEquals(8, results.size(), "all 8 phases should produce a PhaseResult");
        assertEquals("constitution", results.get(0).phaseId());
        assertEquals("specify", results.get(1).phaseId());
        assertEquals("clarify", results.get(2).phaseId());
        assertEquals("plan", results.get(3).phaseId());
        assertEquals("analyze", results.get(4).phaseId());
        assertEquals("tasks", results.get(5).phaseId());
        assertEquals("implement", results.get(6).phaseId());
        assertEquals("converge", results.get(7).phaseId());

        // artefacts were written to disk (R294: AetherCode-internal
        // .aethercode/ssd/<slug>/ layout, R236 SSD file names:
        // spec.md / design.md / tasks.md / dev.log).
        assertTrue(Files.isRegularFile(cwd.resolve(".aethercode/ssd/foo/constitution.md")));
        assertTrue(Files.isRegularFile(cwd.resolve(".aethercode/ssd/foo/spec.md")));
        assertTrue(Files.isRegularFile(cwd.resolve(".aethercode/ssd/foo/design.md")));
        assertTrue(Files.isRegularFile(cwd.resolve(".aethercode/ssd/foo/tasks.md")));
        assertTrue(Files.isRegularFile(cwd.resolve(".aethercode/ssd/foo/dev.log")));
        assertTrue(Files.isRegularFile(cwd.resolve(".aethercode/ssd/foo/convergence.json")));

        // LLM was called once per required phase; optional phases
        // are skipped under auto mode (clarify, analyze, converge).
        // We assert >= 5 calls (constitution, specify, plan, tasks,
        // implement) without counting the optional ones strictly.
        assertTrue(llm.callCount.get() >= 5,
                "expected at least 5 LLM calls (one per required phase), got " + llm.callCount.get());
    }

    @Test
    void auto_run_writes_empty_clarify_when_no_questions() throws Exception {
        SddConfig config = SddConfig.fromBundled();
        SddRunner runner = new SddRunner(config);
        // Mock LLM returns "Q: NONE" for the clarify question prompt
        // so no questions are surfaced.
        MockLlm llm = new MockLlm("Q: NONE");
        MockRepl repl = new MockRepl(true, 0);
        TestLogger log = new TestLogger();

        runner.runAll(cwd, "foo", "intent", 0, false, true,
                llm, repl, log, 7);

        Path clarify = cwd.resolve(".aethercode/ssd/foo/clarify.json");
        assertTrue(Files.exists(clarify), "clarify.json should be written even when no questions surfaced");
        String body = Files.readString(clarify, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"questions\": 0"));
    }

    @Test
    void revise_then_accept_runs_phase_twice() throws Exception {
        SddConfig config = SddConfig.fromBundled()
                // Disable optional gates so the test focuses on the
                // revision loop for `specify` only.
                .withEnableClarify(false)
                .withEnableAnalyze(false)
                .withEnableConverge(false);
        SddRunner runner = new SddRunner(config);
        MockLlm llm = new MockLlm("# spec draft\n\nbody");
        // First call returns revision; second returns accept.
        MockRepl repl = new MockRepl(false, /*reviseOnce*/ true, /*abortOnFirst*/ false);
        TestLogger log = new TestLogger();

        List<SddRunner.PhaseResult> results = runner.runAll(
                cwd, "foo", "add foo", 0, false, /*auto*/ false,
                llm, repl, log, 7);

        // specify phase should report 1 revision (re-run after revise).
        SddRunner.PhaseResult specify = results.stream()
                .filter(r -> r.phaseId().equals("specify")).findFirst().orElseThrow();
        assertEquals(1, specify.revisions(), "specify should have 1 revision");
    }

    @Test
    void abort_in_first_phase_throws() throws Exception {
        SddConfig config = SddConfig.fromBundled()
                .withEnableClarify(false).withEnableAnalyze(false).withEnableConverge(false);
        SddRunner runner = new SddRunner(config);
        MockLlm llm = new MockLlm("# x");
        // First call aborts.
        MockRepl repl = new MockRepl(false, /*reviseOnce*/ false, /*abortOnFirst*/ true);
        TestLogger log = new TestLogger();

        assertThrows(SddRunner.AbortException.class, () -> runner.runAll(
                cwd, "foo", "intent", 0, false, false,
                llm, repl, log, 7));
    }

    @Test
    void next_slug_sequential_uses_raw_name_then_appends_dash_n_on_conflict() throws Exception {
        SddConfig config = SddConfig.fromBundled();
        SddRunner runner = new SddRunner(config);

        // No existing dir → first slug is just the feature name
        // (R294: dropped the NNN- prefix; AetherCode's internal
        // SDD layout uses the raw feature slug).
        assertEquals("foo", runner.nextSlug(cwd, "foo"));

        // After we create foo/, the next one with the same name
        // appends "-2".
        Files.createDirectories(cwd.resolve(".aethercode/ssd/foo"));
        assertEquals("foo-2", runner.nextSlug(cwd, "foo"));
        assertEquals("bar", runner.nextSlug(cwd, "bar"));
    }

    @Test
    void next_slug_timestamp_uses_yyyymmdd_hhmmss() throws Exception {
        SddConfig config = SddConfig.fromBundled().withSlugPolicy(SddConfig.SlugPolicy.TIMESTAMP);
        SddRunner runner = new SddRunner(config);
        String slug = runner.nextSlug(cwd, "my-feature");
        assertTrue(slug.matches("\\d{8}-\\d{6}-my-feature"),
                "TIMESTAMP slug should match YYYYMMDD-HHMMSS-<name>, got: " + slug);
    }

    @Test
    void parse_tasks_handles_spec_kit_row_format() {
        String body = """
                # Tasks: foo
                ## Phase 1
                - [ ] T001 [P] [US1] Setup project in `src/main/`
                - [ ] T002 [US1] Implement service in `src/service.ts`
                ## Phase 2
                - [ ] T003 [P] Add tests in `tests/`
                """;
        List<SddRunner.TaskRow> tasks = SddRunner.parseTasks(body);
        assertEquals(3, tasks.size());
        assertEquals("T001", tasks.get(0).id());
        // The checklist form keeps the full description as the
        // title (no separate title / files columns); files are
        // pulled from back-ticked paths.
        assertTrue(tasks.get(0).title().startsWith("Setup project"),
                "title should start with the description, got: " + tasks.get(0).title());
        assertEquals(List.of("src/main/"), tasks.get(0).files());
        assertEquals("T003", tasks.get(2).id());
    }

    @Test
    void parse_tasks_falls_back_to_numbered_list() {
        String body = """
                1. Implement the parser
                2. Add unit tests
                """;
        List<SddRunner.TaskRow> tasks = SddRunner.parseTasks(body);
        assertEquals(2, tasks.size());
        assertEquals("T-fallback.1", tasks.get(0).id());
    }

    @Test
    void clean_output_strips_think_blocks_and_collapses_blanks() {
        String input = "<think>hidden reasoning</think># Title\n\n\n\n\nbody\n";
        String out = SddRunner.cleanOutput(input);
        assertEquals("# Title\n\nbody\n", out);
    }

    @Test
    void clean_output_strips_chatty_preamble() {
        String input = "Sure, here is the spec you asked for.\n\n# Spec\n\nbody";
        String out = SddRunner.cleanOutput(input);
        assertEquals("# Spec\n\nbody\n", out);
    }

    // ---- mocks ---------------------------------------------------

    /** Mock LlmFn — returns a fixed body for every call (or, if
     *  a per-call override is set, uses that). Records the
     *  total call count so tests can assert on it. */
    private static final class MockLlm implements SddRunner.LlmFn {
        private final String defaultBody;
        private final AtomicInteger callCount = new AtomicInteger();

        MockLlm(String defaultBody) { this.defaultBody = defaultBody; }
        @Override public String generate(String system, String user, int maxTokens) {
            callCount.incrementAndGet();
            return defaultBody;
        }
    }

    /** Mock ReplFn — when {@code auto} is true, always accepts.
     *  When {@code reviseOnce} is true, the first call to the
     *  {@code specify} phase returns a revision text; subsequent
     *  calls (and all calls to other phases) accept.
     *  When {@code abortOnFirst} is true, the first call returns
     *  null to abort the run. */
    private static final class MockRepl implements SddRunner.ReplFn {
        private final boolean auto;
        private final boolean reviseOnce;
        private final boolean abortOnFirst;
        private boolean specifyHasRevised = false;

        /** Auto-accept everything. */
        MockRepl(boolean auto, int revisions) {
            this(auto, revisions > 0, false);
        }

        /** Single ctor: takes the three booleans directly. The
         *  int-revisions overload above exists for readability
         *  in tests. */
        MockRepl(boolean auto, boolean reviseOnce, boolean abortOnFirst) {
            this.auto = auto;
            this.reviseOnce = reviseOnce;
            this.abortOnFirst = abortOnFirst;
        }

        @Override public Optional<String> confirm(PhaseId phase, Path artefact, String content) {
            if (abortOnFirst && !specifyHasRevised && phase.specKitId().equals("constitution")) {
                return null;
            }
            if (reviseOnce && !specifyHasRevised && phase.specKitId().equals("specify")) {
                specifyHasRevised = true;
                return Optional.of("add an NFR about audit logging");
            }
            return Optional.empty();
        }
    }

    /** Mock Logger — collects lines into a list for assertion. */
    private static final class TestLogger implements SddRunner.Logger {
        final List<String> lines = new ArrayList<>();
        @Override public void log(String line) { lines.add(line); }
    }
}