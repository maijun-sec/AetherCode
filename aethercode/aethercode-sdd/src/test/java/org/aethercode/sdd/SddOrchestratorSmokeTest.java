package org.aethercode.sdd;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R700 — End-to-end smoke test for {@link SddOrchestrator}.
 *
 * <p>This is the cheapest possible test that exercises the whole daemon-side
 * SDD pipeline without hitting the real LLM. We:
 * <ol>
 *   <li>Write a fake {@link SddBundleLoader} that returns canned phase
 *       reference markdown, so {@code SddOrchestrator} doesn't depend on
 *       the jar resource layout (which is exercised by
 *       {@link SddBundleLoaderTest}, separately).</li>
 *   <li>Stub the {@link ChatClient} with a {@link FakeChatClient} that
 *       writes the expected artefact file to disk as a "side effect" of the
 *       stream, simulating the LLM calling {@code write_file}.</li>
 *   <li>Drive {@code start → advance(approve) → ...} and assert the
 *       phase-state.json + artefacts land in the expected shape.</li>
 * </ol>
 */
class SddOrchestratorSmokeTest {

    private Path tmpRoot;
    private SddOrchestrator orchestrator;
    private FakeChatClient fakeChat;

    @BeforeEach
    void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("sdd-smoke-");
        fakeChat = new FakeChatClient();
        orchestrator = new SddOrchestrator(
                FakeBundleLoader.canned(),
                fakeChat,
                List.of());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.isDirectory(tmpRoot)) {
            // recursive delete
            try (var paths = Files.walk(tmpRoot)) {
                paths.sorted((a, b) -> b.toString().length() - a.toString().length())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignore) {} });
            }
        }
    }

    @Test
    void start_runs_phase_1_and_writes_constitution() throws IOException {
        SddPhaseState after = orchestrator.start("Build a sorting library", tmpRoot);
        // The state should be on phase 1, pending_confirm after the artefact landed.
        assertThat(after.status).isEqualTo(SddPhaseState.RunStatus.running);
        assertThat(after.currentPhase).isEqualTo(1);
        var p1 = after.phaseByNumber(1);
        assertThat(p1.state()).isEqualTo(SddPhaseState.PhaseState.pending_confirm);
        assertThat(p1.path()).isNotNull();

        Path artefact = Path.of(p1.path());
        assertThat(Files.isRegularFile(artefact)).isTrue();
        assertThat(artefact.getFileName().toString()).isEqualTo("constitution.md");

        // phase-state.json lives next to it.
        Path stateFile = artefact.getParent().resolve("phase-state.json");
        assertThat(Files.isRegularFile(stateFile)).isTrue();

        // SddPhaseState round-trips through JSON.
        SddPhaseState reloaded = SddPhaseState.load(tmpRoot, after.slug);
        assertThat(reloaded.slug).isEqualTo(after.slug);
        assertThat(reloaded.phaseByNumber(1).state())
                .isEqualTo(SddPhaseState.PhaseState.pending_confirm);
    }

    @Test
    void advance_approve_walks_through_all_required_phases() throws IOException {
        SddPhaseState after = orchestrator.start("Build a sorting library", tmpRoot);
        String slug = after.slug;

        // Approve through phases 1, 2 to land in phase 3 (optional —
// clarify). Skip 3 → 4 → skip 5 → 6 → 7 → skip 8.
// Each approve() internally advances and runs the next phase up
// to pending_confirm, so we go: approve (→ phase 2 done, phase 2
// runs and lands in pending_confirm) → approve (→ phase 3 done,
// phase 3 pending_confirm) → skip (→ phase 4 done, phase 4
// pending_confirm) → ... — wait, that's not right either.
//
// advanceApprove marks the *current* phase done then runs the
// *next* phase up to pending_confirm. So a single approve()
// progresses by exactly one phase.
orchestrator.advance(slug, "approve", null);   // 1 → 2 pending_confirm
        orchestrator.advance(slug, "approve", null);   // 2 → 3 pending_confirm (clarify, OPTIONAL)
        orchestrator.advance(slug, "skip", null);      // 3 → 4 pending_confirm
        orchestrator.advance(slug, "approve", null);   // 4 → 5 pending_confirm (analyze, OPTIONAL)
        orchestrator.advance(slug, "skip", null);      // 5 → 6 pending_confirm
        orchestrator.advance(slug, "approve", null);   // 6 → 7 pending_confirm
        orchestrator.advance(slug, "approve", null);   // 7 → 8 pending_confirm (converge, OPTIONAL)
        orchestrator.advance(slug, "skip", null);      // 8 → done

        SddPhaseState done = orchestrator.status(slug, tmpRoot);
        assertThat(done.status).isEqualTo(SddPhaseState.RunStatus.done);
        assertThat(done.currentPhase).isNull();
        for (int n = 1; n <= SddPhaseSpec.TOTAL_PHASES; n++) {
            var p = done.phaseByNumber(n);
            assertThat(p.state())
                    .as("phase %d (%s)", n, p.id())
                    .isIn(SddPhaseState.PhaseState.done,
                          SddPhaseState.PhaseState.skipped);
        }

        // Artefacts on disk: 7 of 8 (skip sentinels also count as artefacts).
        Path sddDir = tmpRoot.resolve(".aethercode").resolve("sdd").resolve(slug);
        try (var stream = Files.list(sddDir)) {
            long artefactCount = stream
                    .filter(p -> !p.getFileName().toString().equals("phase-state.json"))
                    .count();
            assertThat(artefactCount).isEqualTo(8);
        }
    }

    @Test
    void skip_on_required_phase_throws() throws IOException {
        SddPhaseState start = orchestrator.start("Build a sorting library", tmpRoot);
        // Phase 1 is REQUIRED — skip should fail.
        try {
            orchestrator.advance(start.slug, "skip", null);
            org.junit.jupiter.api.Assertions.fail("expected SKIP_REQUIRED_PHASE");
        } catch (SddException se) {
            assertThat(se.code()).isEqualTo(SddException.Code.SKIP_REQUIRED_PHASE);
        }
    }

    @Test
    void modify_reruns_and_overwrites_artefact() throws IOException {
        SddPhaseState after = orchestrator.start("Build a sorting library", tmpRoot);
        Path p1Path = Path.of(after.phaseByNumber(1).path());
        String originalBody = Files.readString(p1Path);

        // Configure the fake chat client to write "MODIFIED" on its next call.
        fakeChat.nextWriteBody = "MODIFIED version of constitution";
        SddPhaseState modified = orchestrator.advance(after.slug, "modify", "add field X");
        assertThat(modified.phaseByNumber(1).state())
                .isEqualTo(SddPhaseState.PhaseState.pending_confirm);

        String newBody = Files.readString(p1Path);
        assertThat(newBody).contains("MODIFIED");
        assertThat(newBody).isNotEqualTo(originalBody);
    }

    @Test
    void abort_marks_run_aborted_and_clears_current() throws IOException {
        SddPhaseState after = orchestrator.start("Build a sorting library", tmpRoot);
        SddPhaseState aborted = orchestrator.abort(after.slug, tmpRoot);
        assertThat(aborted.status).isEqualTo(SddPhaseState.RunStatus.aborted);
        assertThat(aborted.currentPhase).isNull();
    }

    // -------- fakes --------

    /** Minimal ChatClient that pretends to be the model. The orchestrator
     *  uses {@link StreamEvent.RunEnd} as the loop's natural terminator;
     *  the fake also writes the expected artefact file under
     *  {@code <cwd>/.aethercode/sdd/<slug>/<phase-output>} so the
     *  orchestrator's "artefact must exist" post-condition holds. */
    static final class FakeChatClient implements ChatClient {
        String nextWriteBody = "Generated artefact body\n";
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Stream<StreamEvent> stream(List<Message> messages, String systemPrompt, List<Tool> tools) {
            int callNo = calls.incrementAndGet();
            // Find the artefact path from the user prompt (it always includes
            // "Output (the ONLY file you may write this phase)" + the path).
            String path = extractArtefactPath(messages.get(messages.size() - 1).textContent());
            // Write the artefact synchronously — the real LLM does this via
            // a tool_use block, but the orchestrator's contract is just
            // "the file exists on disk by the time we return", which the
            // fake satisfies here.
            try {
                Files.createDirectories(Path.of(path).getParent());
                Files.writeString(Path.of(path), nextWriteBody + " [call " + callNo + "]");
            } catch (IOException ioe) {
                throw new RuntimeException("fake write failed", ioe);
            }
            return Stream.of(
                    new StreamEvent.TextDelta("running phase " + callNo),
                    new StreamEvent.RunEnd("end_turn", List.of()));
        }

        @Override
        public String modelId() { return "fake-model"; }

        private static String extractArtefactPath(String userPrompt) {
            // The orchestrator's prompt always ends the artefact section
            // with a backtick-wrapped absolute path. Find the marker
            // "## Output" first to skip any earlier backticks (e.g. the
            // markdown ``` blocks used for the phase reference).
            int marker = userPrompt.indexOf("## Output");
            if (marker < 0) {
                throw new IllegalStateException("fake chat: no ## Output marker in prompt");
            }
            int start = userPrompt.indexOf('`', marker);
            int end   = userPrompt.indexOf('`', start + 1);
            return userPrompt.substring(start + 1, end);
        }
    }
}