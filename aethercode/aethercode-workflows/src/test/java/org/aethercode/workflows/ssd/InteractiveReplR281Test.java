package org.aethercode.workflows.ssd;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R281 unit tests for {@link InteractiveRepl}. Covers the wire
 * contract (outbound JSON events + inbound JSON commands) and the
 * REPL contract ({@link SsdRunner.ReplFn#confirm}).
 */
class InteractiveReplR281Test {

    private static List<SsdConfig.Phase> fakePhases() {
        // The REPL doesn't render the prompt templates — it only
        // needs the (id, order, title) tuple. A Phase with empty
        // prompts is enough for testing the JSON event flow.
        SsdConfig.Phase spec = new SsdConfig.Phase(
                "spec", 1, "Spec", "spec.md", "", "", "", 8000);
        SsdConfig.Phase design = new SsdConfig.Phase(
                "design", 2, "Design", "design.md", "", "", "", 8000);
        return List.of(spec, design);
    }

    /** parse the newline-delimited JSON events the REPL emits. */
    private static List<String> events(ByteArrayOutputStream out) throws Exception {
        String s = out.toString(StandardCharsets.UTF_8);
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String l : s.split("\n")) {
            if (!l.isBlank()) lines.add(l);
        }
        return lines;
    }

    @Test
    void emitPhaseList_emitsOneEventWithBothPhases() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl r = new InteractiveRepl(new ByteArrayInputStream(new byte[0]), out, "feat", fakePhases());
        r.emitPhaseList();
        var evs = events(out);
        assertEquals(1, evs.size());
        assertTrue(evs.get(0).contains("\"event\":\"phase-list\""), evs.get(0));
        assertTrue(evs.get(0).contains("\"feature\":\"feat\""), evs.get(0));
        assertTrue(evs.get(0).contains("\"spec\""), evs.get(0));
        assertTrue(evs.get(0).contains("\"design\""), evs.get(0));
    }

    @Test
    void confirm_accept_emitsDraftAndAccepted() throws Exception {
        // Driver sends {"action":"accept"} then closes stdin
        String driver = "{\"action\":\"accept\"}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl r = new InteractiveRepl(
                new ByteArrayInputStream(driver.getBytes(StandardCharsets.UTF_8)),
                out, "feat", fakePhases());
        Path fakeArtefact = Files.createTempFile("r281-spec-", ".md");
        Files.writeString(fakeArtefact, "# Spec\n\nbody content");
        Optional<String> reply = r.confirm("Spec", fakeArtefact, "# Spec\n\nbody content");
        // accept returns empty Optional — runner interprets
        // Optional.empty() and Optional.of("") the same way
        // ("accept, no revision").
        assertFalse(reply.isPresent());
        var evs = events(out);
        // phase-draft + phase-accepted
        assertEquals(2, evs.size());
        assertTrue(evs.get(0).contains("\"event\":\"phase-draft\""), evs.get(0));
        assertTrue(evs.get(0).contains("\"bytes\":20"), "byte count wrong: " + evs.get(0));
        assertTrue(evs.get(0).contains("\"preview\":\"# Spec\\n\\nbody content\""), evs.get(0));
        assertTrue(evs.get(1).contains("\"event\":\"phase-accepted\""), evs.get(1));
        assertTrue(evs.get(1).contains("\"revisionCount\":0"), evs.get(1));
        assertFalse(r.isAborted());
        Files.deleteIfExists(fakeArtefact);
    }

    @Test
    void confirm_revise_returnsRevisionTextAndBumpsCounter() throws Exception {
        String driver = "{\"action\":\"revise\",\"text\":\"add NFR-3\"}\n{\"action\":\"accept\"}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl r = new InteractiveRepl(
                new ByteArrayInputStream(driver.getBytes(StandardCharsets.UTF_8)),
                out, "feat", fakePhases());
        Path fakeArtefact = Files.createTempFile("r281-spec2-", ".md");
        Files.writeString(fakeArtefact, "first draft");
        // First call: driver says "revise" → reply should be the revision text
        Optional<String> first = r.confirm("Spec", fakeArtefact, "first draft");
        assertTrue(first.isPresent());
        assertEquals("add NFR-3", first.get());
        // Manually bump the counter (the runner does this; the REPL
        // also exposes it via recordRevision for symmetry).
        r.recordRevision("Spec");
        // Second call: driver says "accept" → reply is empty Optional
        Files.writeString(fakeArtefact, "second draft");
        Optional<String> second = r.confirm("Spec", fakeArtefact, "second draft");
        assertFalse(second.isPresent());
        var evs = events(out);
        // 2 phase-drafts + 1 revising + 1 accepted = 4 events
        assertEquals(4, evs.size());
        assertTrue(evs.get(0).contains("\"event\":\"phase-draft\""));
        assertTrue(evs.get(1).contains("\"event\":\"phase-revising\""));
        assertTrue(evs.get(1).contains("\"revision\":\"add NFR-3\""));
        assertTrue(evs.get(2).contains("\"event\":\"phase-draft\""));
        assertTrue(evs.get(3).contains("\"event\":\"phase-accepted\""));
        assertTrue(evs.get(3).contains("\"revisionCount\":1"));
        Files.deleteIfExists(fakeArtefact);
    }

    @Test
    void confirm_quit_returnsNullAndMarksAborted() throws Exception {
        String driver = "{\"action\":\"quit\"}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl r = new InteractiveRepl(
                new ByteArrayInputStream(driver.getBytes(StandardCharsets.UTF_8)),
                out, "feat", fakePhases());
        Path fakeArtefact = Files.createTempFile("r281-quit-", ".md");
        Optional<String> reply = r.confirm("Spec", fakeArtefact, "x");
        assertNull(reply);
        assertTrue(r.isAborted());
        var evs = events(out);
        // phase-draft + phase-confirm-required (none) + abort (no abort event before this)
        // Actually: confirm emits phase-draft then waits for reply; reply is "quit"
        // → emit abort. So we get: phase-draft, abort.
        assertEquals(2, evs.size());
        assertTrue(evs.get(0).contains("\"event\":\"phase-draft\""));
        assertTrue(evs.get(1).contains("\"event\":\"abort\""));
        assertTrue(evs.get(1).contains("\"reason\":\"user-quit\""));
        Files.deleteIfExists(fakeArtefact);
    }

    @Test
    void confirm_eofOnStdinTreatsAsQuit() throws Exception {
        // No commands on stdin; the REPL should treat EOF as quit
        // and return null (signalling "abort") rather than block.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl r = new InteractiveRepl(
                new ByteArrayInputStream(new byte[0]),
                out, "feat", fakePhases());
        Path fakeArtefact = Files.createTempFile("r281-eof-", ".md");
        Optional<String> reply = r.confirm("Spec", fakeArtefact, "x");
        assertNull(reply);
        assertTrue(r.isAborted());
        Files.deleteIfExists(fakeArtefact);
    }

    @Test
    void confirm_ignoresMalformedInboundJson() throws Exception {
        // First line is bad JSON (ignored), second is a real accept
        String driver = "this is not json\n{\"action\":\"accept\"}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl r = new InteractiveRepl(
                new ByteArrayInputStream(driver.getBytes(StandardCharsets.UTF_8)),
                out, "feat", fakePhases());
        Path fakeArtefact = Files.createTempFile("r281-bad-", ".md");
        Optional<String> reply = r.confirm("Spec", fakeArtefact, "x");
        // The bad JSON is silently dropped and the next line (the
        // real accept) is consumed. Empty Optional = accepted.
        assertFalse(reply.isPresent());
        Files.deleteIfExists(fakeArtefact);
    }

    @Test
    void previewTruncatedAt4kb() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Path fake = Files.createTempFile("r281-big-", ".md");
        // 8 KB content (mix of x's and newlines so we can spot the
        // truncation more reliably than counting raw bytes)
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 8192; i++) big.append('x');
        Files.writeString(fake, big.toString());
        String driver = "{\"action\":\"accept\"}\n";
        InteractiveRepl r = new InteractiveRepl(
                new ByteArrayInputStream(driver.getBytes(StandardCharsets.UTF_8)),
                out, "feat", fakePhases());
        r.confirm("Spec", fake, big.toString());
        var evs = events(out);
        String draftEvent = evs.get(0);
        // 4096 chars of 'x' + the ellipsis "…" → exactly 4097 x's
        // get emitted; the "…" itself is JSON-escaped as "…".
        long xCount = draftEvent.chars().filter(c -> c == 'x').count();
        assertEquals(4096, xCount, "preview should have exactly 4096 x chars");
        // The full 8192-byte content is NOT in the event (truncated).
        assertTrue(draftEvent.length() < big.length() * 2 + 100,
                "draft event size suggests no truncation");
        // bytes field reports the full 8192 (UI knows the real length)
        assertTrue(draftEvent.contains("\"bytes\":8192"), draftEvent);
        Files.deleteIfExists(fake);
    }

    @Test
    void emitComplete_emitsOneCompleteEventWithAllResults() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl r = new InteractiveRepl(new ByteArrayInputStream(new byte[0]), out, "feat", fakePhases());
        r.emitComplete(List.of(
                new SsdRunner.PhaseResult("spec", Path.of("/tmp/spec.md"), 0),
                new SsdRunner.PhaseResult("design", Path.of("/tmp/design.md"), 2)
        ));
        var evs = events(out);
        assertEquals(1, evs.size());
        String s = evs.get(0);
        assertTrue(s.contains("\"event\":\"complete\""), s);
        assertTrue(s.contains("\"spec\""), s);
        assertTrue(s.contains("\"design\""), s);
        assertTrue(s.contains("\"revisions\":2"), s);
    }
}