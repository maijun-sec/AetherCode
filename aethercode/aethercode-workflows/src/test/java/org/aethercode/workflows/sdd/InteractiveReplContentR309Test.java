package org.aethercode.workflows.sdd;

import org.aethercode.workflows.sdd.SddConfig.PhaseId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R309 wire-protocol tests for the new {@code phase-need-content}
 * outbound event + {@code phase-content} inbound command that
 * InteractiveRepl uses as the {@link SddRunner.ContentProvider}
 * seam.
 *
 * <p>Pre-R309 the runner held a hard {@code llm.generate} reference
 * and called it for every phase. The LLM it pointed at
 * (AetherCodeEngine.query) returned a preamble-laden,
 * template-underspecified reply that left every
 * {@code [NEEDS CLARIFICATION: ...]} slot unfilled, so the user
 * got back the spec-kit template verbatim with placeholders
 * intact. R308 user feedback was unambiguous: "生成的 spec.md
 * 明显有问题，都是占位符，没有实际的 spec 内容. 已经没必要
 * 往后测试了".
 *
 * <p>R309 replaces the daemon's hard LLM call with a wire seam:
 * the daemon emits {@code phase-need-content} carrying the full
 * prompts + maxTokens hint, and the driver / agent answers with
 * a {@code phase-content} command carrying the rendered markdown
 * body. This test pins the wire shape so a future refactor
 * doesn't silently break the desktop ↔ daemon contract.
 *
 * <h2>Tests</h2>
 * <ol>
 *   <li>{@code requestContent_emits_phase_need_content_with_full_prompts}
 *       — the outbound event carries {@code systemPrompt},
 *       {@code userPrompt} and {@code maxTokens} verbatim so the
 *       provider can reproduce the daemon's prompt shaping
 *       exactly.</li>
 *   <li>{@code requestContent_returns_phase_content_body}
 *       — the inbound {@code {"action":"phase-content","content":"..."}}
 *       command's {@code content} field is returned as the
 *       rendered body.</li>
 *   <li>{@code requestContent_ignores_out_of_order_actions}
 *       — flushing earlier {@code accept} / {@code log} lines
 *       during the request window doesn't break the provider.</li>
 *   <li>{@code requestContent_quit_command_aborts_cleanly}
 *       — if the driver emits {@code quit} mid-request, the
 *       REPL surfaces an IOException so the runner aborts the
 *       whole run rather than hanging on stdin.</li>
 *   <li>{@code contentProvider_interface_is_distinct_from_repl_fn}
 *       — source-pin: the runner's {@code ContentProvider} seam
 *       is wired through InteractiveRepl's {@code requestContent}
 *       method, not piggy-backed on the legacy ReplFn
 *       {@code confirm}.</li>
 * </ol>
 */
class InteractiveReplContentR309Test {

    /** A tiny InputStream that wraps a fixed byte buffer with a
     *  read pointer — used to push canned reply lines into the
     *  REPL's readReply. */
    private static final class ByteBackedInput extends InputStream {
        private final byte[] buf;
        private int pos = 0;
        ByteBackedInput(String s) { this.buf = s.getBytes(StandardCharsets.UTF_8); }
        @Override public synchronized int read() {
            if (pos >= buf.length) return -1;
            return buf[pos++] & 0xff;
        }
        @Override public synchronized int read(byte[] b, int off, int len) {
            if (pos >= buf.length) return -1;
            int n = Math.min(len, buf.length - pos);
            System.arraycopy(buf, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    private static InteractiveRepl replWith(String stdin, OutputStream sink) {
        return new InteractiveRepl(new ByteBackedInput(stdin), sink,
                "test-slug",
                List.of(PhaseId.CONSTITUTION, PhaseId.SPECIFY, PhaseId.PLAN, PhaseId.TASKS));
    }

    @Test
    void requestContent_emits_phase_need_content_with_full_prompts() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        // The driver never gets to reply (we abort before readReply),
        // but AutoAcceptInputStream is the canonical way to keep
        // the REPL from hanging on an empty stdin. We use a custom
        // empty stream instead so the request genuinely blocks until
        // we close the run — the test inspects what the REPL has
        // emitted to the sink so far.
        InputStream empty = new ByteBackedInput("");
        InteractiveRepl repl = new InteractiveRepl(empty, sink,
                "test-slug",
                List.of(PhaseId.CONSTITUTION, PhaseId.SPECIFY));

        // We call requestContent on a separate thread so the
        // main test can keep going and inspect the sink after
        // the event lands. The reader will hang on an empty
        // stdin (5-min readReply timeout), but we never wait
        // that long — we read the sink after a brief settle.
        Thread t = new Thread(() -> {
            try { repl.requestContent("SYS-PROMPT-FIXTURE", "USR-PROMPT-FIXTURE", 4096); }
            catch (Exception ignore) { /* expected to fail / hang */ }
        });
        t.setDaemon(true);
        t.start();
        try { Thread.sleep(150); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        String emitted = sink.toString(StandardCharsets.UTF_8);
        assertTrue(emitted.contains("\"event\":\"phase-need-content\""),
                "InteractiveRepl must emit phase-need-content when a content request is asked, got: " + emitted);
        assertTrue(emitted.contains("\"systemPrompt\":\"SYS-PROMPT-FIXTURE\""),
                "phase-need-content must carry the verbatim systemPrompt so the provider can reproduce shaping, got: " + emitted);
        assertTrue(emitted.contains("\"userPrompt\":\"USR-PROMPT-FIXTURE\""),
                "phase-need-content must carry the verbatim userPrompt, got: " + emitted);
        assertTrue(emitted.contains("\"maxTokens\":4096"),
                "phase-need-content must carry the maxTokens hint so the provider can size its reply, got: " + emitted);

        // Don't wait for the 5-min readReply timeout — the test
        // inspects what the REPL emitted to the sink. The daemon
        // thread will eventually fail or hit timeout, both of
        // which are fine for this unit test.
        t.interrupt();
    }

    @Test
    void requestContent_returns_phase_content_body() throws Exception {
        // The driver pretends to be an LLM provider: it sees
        // the phase-need-content event, ignores the prompts
        // (we don't assert on the prompt here — that's covered
        // by the first test), and replies with a
        // phase-content command carrying the rendered body.
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        String stdin = "{\"action\":\"phase-content\",\"content\":\"# Hello\\n\\nbody\"}\n";
        InteractiveRepl repl = replWith(stdin, sink);

        String body = repl.requestContent("sys", "usr", 4096);
        assertEquals("# Hello\n\nbody", body,
                "ContentProvider.requestContent must return the phase-content command's content field verbatim");
    }

    @Test
    void requestContent_ignores_out_of_order_actions() throws Exception {
        // The driver may flush earlier accept / log lines that
        // crossed the request boundary. Those must NOT abort the
        // request — the provider should skip them and keep
        // reading until phase-content lands.
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        String stdin =
                "{\"action\":\"accept\",\"phase\":\"constitution\"}\n" +
                "{\"action\":\"log\",\"level\":\"info\",\"message\":\"flushed\"}\n" +
                "{\"action\":\"phase-content\",\"content\":\"# Real body\"}\n";
        InteractiveRepl repl = replWith(stdin, sink);

        String body = repl.requestContent("sys", "usr", 4096);
        assertEquals("# Real body", body,
                "requestContent must skip out-of-order actions and return the phase-content body");
    }

    @Test
    void requestContent_quit_command_aborts_cleanly() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        String stdin = "{\"action\":\"quit\"}\n";
        InteractiveRepl repl = replWith(stdin, sink);

        // quit mid-request must surface as an IOException so
        // SddRunner.runDraftPhase aborts the whole run rather
        // than hang on stdin forever.
        try {
            repl.requestContent("sys", "usr", 4096);
            org.junit.jupiter.api.Assertions.fail("expected IOException when driver emits quit during phase-need-content");
        } catch (IOException ioe) {
            assertTrue(ioe.getMessage().toLowerCase().contains("quit"),
                    "aborting exception must mention quit so logs are diagnosable, got: " + ioe.getMessage());
        }
        assertTrue(repl.isAborted(),
                "InteractiveRepl must flip its aborted flag when quit arrives mid-request");
    }

    @Test
    void contentProvider_interface_is_distinct_from_repl_fn() {
        // Source-pin: InteractiveRepl implements BOTH ReplFn
        // (the user-confirm seam) and ContentProvider (the new
        // R309 LLM-content seam). The two are distinct methods
        // with distinct wire shapes — confirm emits a draft
        // and blocks on accept/revise, requestContent emits
        // a need-content event and blocks on phase-content.
        // This test pins both seams so a future refactor that
        // collapses them into one (which would silently break
        // the desktop ↔ daemon contract) can't go unnoticed.
        InteractiveRepl repl = replWith("", new ByteArrayOutputStream());
        assertTrue(repl instanceof SddRunner.ReplFn,
                "InteractiveRepl must continue implementing ReplFn for the per-phase user confirm seam");
        assertTrue(repl instanceof SddRunner.ContentProvider,
                "InteractiveRepl must also implement ContentProvider (R309) for the per-phase content seam");
    }
}