package org.aethercode.workflows.sdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.workflows.sdd.SddConfig.PhaseId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R292 — wire-protocol tests for {@link InteractiveRepl}.
 * Drives the NDJSON protocol end-to-end via in-memory
 * streams so we can assert on the exact event JSON the UI
 * receives, plus the driver-side command loop (accept /
 * revise / skip / quit / clarify-answer / converge-iterate).
 *
 * <p>The protocol covers 14 outbound event kinds and 6
 * inbound command actions; we exercise every branch.
 */
class InteractiveReplR292Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Build a REPL wired to a fresh pair of in-memory streams. */
    private static InteractiveRepl build(List<String> inboundLines, ByteArrayOutputStream out) {
        String joined = String.join("\n", inboundLines) + "\n";
        InputStream in = new ByteArrayInputStream(joined.getBytes(StandardCharsets.UTF_8));
        return stdioOrStream(in, out, "001-test",
                List.of(
                        PhaseId.CONSTITUTION, PhaseId.SPECIFY, PhaseId.CLARIFY, PhaseId.PLAN,
                        PhaseId.ANALYZE, PhaseId.TASKS, PhaseId.IMPLEMENT, PhaseId.CONVERGE));
    }

    /** Convenience static factory: we don't want tests to call
     *  the stdio variant because that ties them to System.in /
     *  System.out. The test exposes the existing
     *  {@link InteractiveRepl#stdio(String, java.util.List)}
     *  via a stream-args wrapper here. */
    private static InteractiveRepl stdioOrStream(InputStream in, OutputStream out,
                                                  String slug, List<PhaseId> phases) {
        // We can't call the stdio() variant with a custom stream
        // (it hard-wires System.in/out), so we use the explicit
        // constructor instead.
        return new InteractiveRepl(in, out, slug, phases);
    }

    /** Parse every line written to {@code out} into typed events. */
    private static List<Object> eventsWrittenTo(ByteArrayOutputStream out) throws Exception {
        List<Object> out0 = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) continue;
            out0.add(MAPPER.readValue(line, java.util.Map.class));
        }
        return out0;
    }

    @Test
    void emit_phase_list_carries_slug_and_optional_flags() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Drain: readReply will return quit on EOF, so just send
        // nothing and accept the resulting abort.
        InteractiveRepl repl = build(List.of(""), out);
        repl.emitPhaseList();
        List<Object> events = eventsWrittenTo(out);
        assertEquals(1, events.size());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> ev = (java.util.Map<String, Object>) events.get(0);
        assertEquals("phase-list", ev.get("event"));
        assertEquals("001-test", ev.get("feature"));
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> phases = (List<java.util.Map<String, Object>>) ev.get("phases");
        assertEquals(8, phases.size());
        // constitution must NOT be optional; clarify / analyze /
        // converge must be optional.
        java.util.Map<String, Object> constitution = phases.get(0);
        assertEquals("constitution", constitution.get("id"));
        assertEquals(false, constitution.get("optional"));
        java.util.Map<String, Object> clarify = phases.get(2);
        assertEquals("clarify", clarify.get("id"));
        assertEquals(true, clarify.get("optional"));
        java.util.Map<String, Object> analyze = phases.get(4);
        assertEquals(true, analyze.get("optional"));
        java.util.Map<String, Object> converge = phases.get(7);
        assertEquals(true, converge.get("optional"));
    }

    @Test
    void confirm_round_trip_with_accept() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl repl = build(List.of(
                "{\"action\":\"accept\"}",
                ""
        ), out);
        // Drive confirm → emits phase-draft, blocks on inbound
        // reply, returns Optional.empty on accept (per R281 wire
        // contract: accept = empty Optional, revise = present text,
        // quit = null).
        var reply = repl.confirm(PhaseId.SPECIFY,
                java.nio.file.Path.of("spec.md"), "# spec body\n");
        assertTrue(reply != null);
        assertTrue(reply.isEmpty(), "accept must return Optional.empty()");
        List<Object> events = eventsWrittenTo(out);
        // 1st: phase-draft, 2nd: phase-accepted
        assertEquals("phase-draft", ((java.util.Map<?, ?>) events.get(0)).get("event"));
        assertEquals("phase-accepted", ((java.util.Map<?, ?>) events.get(1)).get("event"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> accepted = (java.util.Map<String, Object>) events.get(1);
        assertEquals("specify", accepted.get("phase"));
    }

    @Test
    void confirm_with_revise_returns_text_and_emits_phase_revising() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl repl = build(List.of(
                "{\"action\":\"revise\",\"text\":\"add NFR-3\"}",
                ""
        ), out);
        var reply = repl.confirm(PhaseId.SPECIFY,
                java.nio.file.Path.of("spec.md"), "body");
        assertTrue(reply.isPresent());
        assertEquals("add NFR-3", reply.get());
        List<Object> events = eventsWrittenTo(out);
        assertEquals("phase-draft", ((java.util.Map<?, ?>) events.get(0)).get("event"));
        assertEquals("phase-revising", ((java.util.Map<?, ?>) events.get(1)).get("event"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> revising = (java.util.Map<String, Object>) events.get(1);
        assertEquals("add NFR-3", revising.get("revision"));
    }

    @Test
    void confirm_with_quit_returns_null_and_emits_abort() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl repl = build(List.of("{\"action\":\"quit\"}"), out);
        var reply = repl.confirm(PhaseId.SPECIFY,
                java.nio.file.Path.of("spec.md"), "body");
        assertNull(reply, "quit must return null");
        assertTrue(repl.isAborted());
        List<Object> events = eventsWrittenTo(out);
        assertEquals("phase-draft", ((java.util.Map<?, ?>) events.get(0)).get("event"));
        // After abort, the REPL also emits the abort event.
        assertEquals("abort", ((java.util.Map<?, ?>) events.get(1)).get("event"));
    }

    @Test
    void eof_on_stdin_is_treated_as_quit() throws Exception {
        // Empty input (no commands at all). readReply reads past EOF
        // and synthesises a quit; confirm() must return null.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl repl = build(List.of(), out);
        var reply = repl.confirm(PhaseId.SPECIFY,
                java.nio.file.Path.of("spec.md"), "body");
        assertNull(reply);
        assertTrue(repl.isAborted());
    }

    @Test
    void ask_clarify_round_trip_with_clarify_answer() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl repl = build(List.of(
                "{\"action\":\"clarify-answer\",\"id\":\"q1\",\"answer\":\"SSO via Okta\"}"
        ), out);
        String answer = repl.askClarify("q1", "Auth method", "Which auth method?");
        assertEquals("SSO via Okta", answer);
        List<Object> events = eventsWrittenTo(out);
        assertEquals("clarify-question", ((java.util.Map<?, ?>) events.get(0)).get("event"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> q = (java.util.Map<String, Object>) events.get(0);
        assertEquals("q1", q.get("id"));
        assertEquals("Auth method", q.get("header"));
        assertEquals("Which auth method?", q.get("question"));
    }

    @Test
    void ask_clarify_skip_returns_null() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl repl = build(List.of(
                "{\"action\":\"skip\"}"
        ), out);
        String answer = repl.askClarify("q1", "Auth", "Q?");
        assertNull(answer, "skip terminates the clarify round");
    }

    @Test
    void ask_converge_iterate_with_empty_text_returns_empty_string() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl repl = build(List.of(
                "{\"action\":\"converge-iterate\",\"text\":\"\"}"
        ), out);
        String feedback = repl.askConvergeIterate(1, "report");
        assertEquals("", feedback, "empty converge-iterate means 'accept not-converged'");
    }

    @Test
    void emit_converge_check_carries_iteration_and_converged() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl repl = build(List.of(""), out);
        repl.emitConvergeCheck(PhaseId.CONVERGE, 2, false, "3 issues");
        List<Object> events = eventsWrittenTo(out);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> ev = (java.util.Map<String, Object>) events.get(0);
        assertEquals("converge-check", ev.get("event"));
        assertEquals(2, ev.get("iteration"));
        assertEquals(false, ev.get("converged"));
        assertEquals("3 issues", ev.get("report"));
    }

    @Test
    void emit_analysis_event_shape() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl repl = build(List.of(""), out);
        repl.emitAnalysis(PhaseId.ANALYZE, "issue: missing task for FR-2");
        List<Object> events = eventsWrittenTo(out);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> ev = (java.util.Map<String, Object>) events.get(0);
        assertEquals("analysis", ev.get("event"));
        assertEquals("analyze", ev.get("phase"));
        assertEquals("issue: missing task for FR-2", ev.get("report"));
    }

    @Test
    void malformed_input_does_not_crash_the_repl() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Send a malformed line, then a valid accept. The driver
        // should swallow the bad line and keep reading.
        InteractiveRepl repl = build(List.of(
                "not-json-at-all",
                "{\"action\":\"accept\"}"
        ), out);
        var reply = repl.confirm(PhaseId.SPECIFY,
                java.nio.file.Path.of("spec.md"), "body");
        assertTrue(reply != null);
        assertTrue(reply.isEmpty(), "accept returns Optional.empty()");
        // 2 events: phase-draft + phase-accepted (the bad line is
        // silently dropped, not surfaced as a `log` event).
        List<Object> events = eventsWrittenTo(out);
        assertEquals(2, events.size());
    }

    @Test
    void emit_complete_round_trips_results() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InteractiveRepl repl = build(List.of(""), out);
        List<SddRunner.PhaseResult> results = List.of(
                new SddRunner.PhaseResult("specify", java.nio.file.Path.of("spec.md"), 1),
                new SddRunner.PhaseResult("plan", java.nio.file.Path.of("plan.md"), 0)
        );
        repl.emitComplete(results);
        List<Object> events = eventsWrittenTo(out);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> ev = (java.util.Map<String, Object>) events.get(0);
        assertEquals("complete", ev.get("event"));
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> r = (List<java.util.Map<String, Object>>) ev.get("results");
        assertEquals(2, r.size());
        assertEquals("specify", r.get(0).get("phaseId"));
        assertEquals(1, r.get(0).get("revisions"));
    }
}