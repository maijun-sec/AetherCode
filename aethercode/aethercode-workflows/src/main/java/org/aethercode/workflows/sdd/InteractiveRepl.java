package org.aethercode.workflows.sdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.workflows.sdd.SddConfig.PhaseId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * R292 — interactive REPL for the SDD runner. Mirrors the wire
 * shape introduced by the R281 SSD REPL
 * (newline-delimited JSON events + commands) but reworks it for
 * Spec Kit's 6-phase pipeline + 2 optional quality gates +
 * converge loop.
 *
 * <h2>Outbound events (one JSON object per line)</h2>
 * <pre>
 *   {"event": "phase-list", "feature": "001-photo-albums",
 *    "phases": [{"id": "constitution", "order": 0, "title": "Constitution", "optional": false},
 *               {"id": "specify",       "order": 1, "title": "Specify",       "optional": false},
 *               {"id": "clarify",       "order": 2, "title": "Clarify",       "optional": true},
 *               {"id": "plan",          "order": 3, "title": "Plan",          "optional": false},
 *               {"id": "analyze",       "order": 4, "title": "Analyze",       "optional": true},
 *               {"id": "tasks",         "order": 5, "title": "Tasks",         "optional": false},
 *               {"id": "implement",     "order": 6, "title": "Implement",     "optional": false},
 *               {"id": "converge",      "order": 7, "title": "Converge",      "optional": true}]}
 *
 *   {"event": "phase-start", "phase": "specify", "order": 1, "title": "Specify"}
 *
 *   {"event": "phase-draft", "phase": "specify", "path": ".../spec.md",
 *    "bytes": 4321, "preview": "..."}
 *
 *   {"event": "phase-accepted", "phase": "specify", "revisionCount": 1}
 *   {"event": "phase-skipped", "phase": "specify", "reason": "already-accepted"}
 *   {"event": "phase-error", "phase": "specify", "message": "..."}
 *
 *   {"event": "clarify-question", "id": "q1", "header": "Auth method",
 *    "question": "Which auth method should we use?"}
 *
 *   {"event": "analysis", "phase": "analyze", "report": "..."}
 *
 *   {"event": "converge-check", "phase": "converge", "iteration": 1,
 *    "converged": false, "report": "..."}
 *
 *   {"event": "complete", "results": [{"phaseId": "specify", "path": ".../spec.md", "revisions": 1}, ...]}
 *   {"event": "abort", "reason": "user-quit"}
 *   {"event": "log", "level": "info", "message": "..."}
 * </pre>
 *
 * <h2>Inbound commands (one JSON object per line)</h2>
 * <pre>
 *   {"action": "accept"}                                  // accept current phase, advance
 *   {"action": "revise", "text": "add NFR-3"}              // queue revision, re-draft, re-ask
 *   {"action": "skip"}                                      // skip current phase
 *   {"action": "quit"}                                      // abort the whole run
 *   {"action": "clarify-answer", "id": "q1", "answer": "..."}  // R292 new
 *   {"action": "converge-iterate", "text": "..."}          // R292 new (empty = accept non-converge)
 * </pre>
 *
 * <h2>Threading</h2>
 * <p>{@link #confirm} is called from the orchestrator's main
 * thread. {@link #writeOut} blocks on {@code out.flush()}. The
 * driver (desktop / TUI / test) reads events from {@code out}
 * asynchronously; we never block waiting for input on the read
 * side here — the driver is responsible for sending a command
 * before the next phase starts.
 *
 * <h2>Why JSON instead of text REPL</h2>
 * Same rationale as R281: stable wire contract, rich metadata,
 * recoverability, no terminal assumptions.
 */
public final class InteractiveRepl implements SddRunner.ReplFn {

    private static final Logger LOG = LoggerFactory.getLogger(InteractiveRepl.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BufferedWriter out;
    private final BufferedReader in;
    private final String slug;
    private final List<PhaseId> phaseList;
    /** R293: bounded-time readReply. The default
     *  {@code System.in.readLine()} blocks forever, which is
     *  fine for terminal REPLs but wrong for the desktop
     *  driver — if the Tauri shell plugin's stdin pipe ever
     *  silently breaks (EOF without an event, a mis-wired
     *  Write call returning prematurely) the SDD run hangs
     *  with no UI affordance to recover. We submit the read
     *  to a single-thread executor and {@code .get(5 min)} on
     *  the future; on timeout we auto-accept the current
     *  phase and emit a {@code log} event so the driver can
     *  surface a banner. */
    private final ExecutorService readExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sdd-readline");
        t.setDaemon(true);
        return t;
    });
    /** 5 minutes per phase is comfortably above any realistic
     *  desktop interaction (the user has to read a 1-3 KB
     *  draft, decide on a revision, and type it). */
    private static final long READ_TIMEOUT_SECONDS =
            Long.getLong("aethercode.sdd.readTimeoutSeconds", 300L);
    /** last inbound command; set by {@link #readReply}, consumed by
     *  {@link #confirm}. Held in a one-element array so the inner
     *  reader thread can publish without a second mutex. */
    private final Object[] replySlot = new Object[1];
    /** set true once the driver has signaled quit / abort. */
    private volatile boolean aborted = false;

    /**
     * Wire-format InteractiveRepl backed by arbitrary streams. Tests
     * inject in-memory streams; the CLI wiring uses
     * {@code System.in}/{@code System.out}.
     */
    public InteractiveRepl(InputStream in,
                           OutputStream out,
                           String slug,
                           List<PhaseId> phaseList) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        this.slug = slug;
        this.phaseList = List.copyOf(phaseList);
    }

    /** Convenience constructor that wires {@code System.in}/{@code
     * System.out}. Same protocol as the explicit-args overload;
     * identical behaviour at the wire. The CLI's {@code --interactive}
     * flag uses this overload. */
    public static InteractiveRepl stdio(String slug, List<PhaseId> phaseList) {
        return new InteractiveRepl(System.in, System.out, slug, phaseList);
    }

    /** R298: {@code --auto} factory. The CLI's {@code --auto} flag
     *  historically wired a non-Interactive REPL, which meant the
     *  daemon never emitted NDJSON {@code phase-list} /
     *  {@code phase-start} / {@code phase-draft} / {@code phase-accepted}
     *  / {@code complete} events on stdout. The desktop UI therefore
     *  received a {@code phase-draft} card per phase but no chip
     *  state transitions, looking identical to the user to a
     *  "flash past" mock-fallback run. This factory wires the
     *  InteractiveRepl with a synthetic InputStream that supplies
     *  {@code {"action":"accept"}} on demand, so the run auto-
     *  progresses while still emitting every event the renderer
     *  needs. Output goes to {@code System.out}; the REPL's
     *  {@code log()} helper funnels {@code [sdd]} lines through
     *  the {@code log} event kind. */
    public static InteractiveRepl autoAccept(String slug, List<PhaseId> phaseList) {
        return autoAccept(slug, System.out, phaseList);
    }

    /** R298 test-friendly overload: same as {@link #autoAccept(String, java.util.List)}
     *  but writes events to the given {@link OutputStream} instead
     *  of {@code System.out}. Lets the unit tests assert on the
     *  exact bytes the renderer would see without juggling
     *  System.setOut(). */
    public static InteractiveRepl autoAccept(String slug, OutputStream out, List<PhaseId> phaseList) {
        return new InteractiveRepl(new AutoAcceptInputStream(), out, slug, phaseList);
    }

    /** Streams a JSONL accept action every time {@link #readReply}
     *  tries to read. Implementation: hold a single byte[] action
     *  and a position cursor. When the consumer reads past the
     *  end of the buffer, reset the cursor to 0 so the next read
     *  replays the action. This works with BufferedReader's
     *  chunked-read pattern (which broke on the naive
     *  ByteArrayInputStream + reset because reset-to-mark-0 plus
     *  mark-not-set threw InvalidMarkException in some Java
     *  versions; markSupported() always returns true for BAIS so
     *  we explicitly mark before reset). */
    private static final class AutoAcceptInputStream extends InputStream {
        private static final byte[] ACTION =
            "{\"action\":\"accept\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        private int pos = 0;

        AutoAcceptInputStream() {
            super();
        }

        @Override
        public synchronized int read() throws IOException {
            if (pos >= ACTION.length) {
                pos = 0;
                if (ACTION.length == 0) return -1;
            }
            return ACTION[pos++] & 0xff;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            if (len <= 0) return 0;
            if (pos >= ACTION.length) {
                pos = 0;
                if (ACTION.length == 0) return -1;
            }
            int n = Math.min(len, ACTION.length - pos);
            System.arraycopy(ACTION, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public synchronized int available() {
            return ACTION.length - pos;
        }
    }

    /** Emit the initial phase-list so the UI can render the TODO
     *  chips before the first LLM call. Idempotent — the runner
     *  may be constructed multiple times across phases. Each
     *  phase entry carries {@code optional} so the UI can render
     *  quality gates as half-opacity chips. */
    public void emitPhaseList() throws IOException {
        List<Map<String, Object>> phases = new ArrayList<>(phaseList.size());
        for (PhaseId p : phaseList) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.specKitId());
            m.put("order", p.order());
            m.put("title", p.title());
            m.put("optional", p.isOptional());
            phases.add(m);
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event", "phase-list");
        ev.put("feature", slug);
        ev.put("phases", phases);
        writeOut(ev);
    }

    @Override
    public Optional<String> confirm(PhaseId phase, Path artefactPath, String content) throws Exception {
        // Emit the draft first, then the confirm-required event.
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("event", "phase-draft");
        draft.put("phase", phase.specKitId());
        draft.put("path", artefactPath.toString());
        draft.put("bytes", content.length());
        draft.put("preview", content.length() > 4096 ? content.substring(0, 4096) + "…" : content);
        writeOut(draft);

        // Block until the driver replies.
        InboundCommand cmd = readReply();
        if (cmd.action.equals("quit")) {
            aborted = true;
            emitAbort("user-quit");
            return null;
        }
        if (cmd.action.equals("accept")) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("event", "phase-accepted");
            ev.put("phase", phase.specKitId());
            ev.put("revisionCount", revisionCountFor(phase));
            writeOut(ev);
            return Optional.empty();
        }
        if (cmd.action.equals("revise")) {
            Map<String, Object> rev = new LinkedHashMap<>();
            rev.put("event", "phase-revising");
            rev.put("phase", phase.specKitId());
            rev.put("revision", cmd.text);
            writeOut(rev);
            return Optional.of(cmd.text);
        }
        if (cmd.action.equals("skip")) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("event", "phase-skipped");
            ev.put("phase", phase.specKitId());
            ev.put("reason", "driver-skip");
            writeOut(ev);
            return Optional.empty();
        }
        throw new IOException("unknown action from driver: " + cmd.action);
    }

    /** Emit a single {@code complete} event after the runner has
     *  finished successfully. Each result is the path + revision
     *  count for one phase. */
    public void emitComplete(List<SddRunner.PhaseResult> results) throws IOException {
        List<Map<String, Object>> outList = new ArrayList<>();
        for (SddRunner.PhaseResult r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("phaseId", r.phaseId());
            m.put("path", r.artefactPath() == null ? null : r.artefactPath().toString());
            m.put("revisions", r.revisions());
            outList.add(m);
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event", "complete");
        ev.put("results", outList);
        writeOut(ev);
    }

    /** Emit a phase-start event when the runner enters a phase. */
    public void emitPhaseStart(PhaseId phase) throws IOException {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event", "phase-start");
        ev.put("phase", phase.specKitId());
        ev.put("order", phase.order());
        ev.put("title", phase.title());
        ev.put("optional", phase.isOptional());
        writeOut(ev);
    }

    /** Free-form log message — best-effort, never throws. */
    public void log(String level, String message) {
        try {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("event", "log");
            ev.put("level", level);
            ev.put("message", message);
            writeOut(ev);
        } catch (IOException ignore) {
            LOG.debug("InteractiveRepl.log swallowed: {}", ignore.getMessage());
        }
    }

    /** Emit a {@code clarify-question} event and block until the
     *  driver answers with a {@code clarify-answer} command
     *  carrying the same id. Returns {@code null} if the driver
     *  asked to skip or quit. The {@code id} is a short stable
     *  token (e.g. {@code "q1"}, {@code "task-T001"}) so the
     *  driver can match the answer to the right question across
     *  reorders. */
    public String askClarify(String id, String header, String question) throws IOException {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event", "clarify-question");
        ev.put("id", id);
        ev.put("header", header);
        ev.put("question", question);
        writeOut(ev);

        // Wait for the matching answer. Skip / quit terminate the
        // clarify round; revise / accept fall through to "empty
        // answer" (same as the driver pressing Enter on the
        // question).
        InboundCommand cmd;
        while (true) {
            cmd = readReply();
            if (cmd.action.equals("clarify-answer") && id.equals(cmd.id)) {
                return cmd.text;
            }
            if (cmd.action.equals("quit")) {
                aborted = true;
                emitAbort("user-quit");
                return null;
            }
            if (cmd.action.equals("skip")) {
                return null;
            }
            // Unknown / out-of-order command; log and keep reading.
            LOG.warn("InteractiveRepl: ignoring out-of-order action {} while waiting for clarify-answer({})",
                    cmd.action, id);
        }
    }

    /** Emit a {@code converge-check} event after the model has
     *  produced its review. The driver uses this to render the
     *  "convergence review" pane. */
    public void emitConvergeCheck(PhaseId phase, int iteration, boolean converged, String report) throws IOException {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event", "converge-check");
        ev.put("phase", phase.specKitId());
        ev.put("iteration", iteration);
        ev.put("converged", converged);
        ev.put("report", report);
        writeOut(ev);
    }

    /** Emit an {@code analysis} event carrying the cross-artifact
     *  review. The driver renders this as a passive banner — we
     *  don't gate on the findings. */
    public void emitAnalysis(PhaseId phase, String report) throws IOException {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event", "analysis");
        ev.put("phase", phase.specKitId());
        ev.put("report", report);
        writeOut(ev);
    }

    /** Emit a {@code converge-check} waiting for the driver's
     *  iterate / skip / quit decision. Returns:
     *  <ul>
     *    <li>{@code null} if the driver quits (caller should
     *        throw {@code AbortException});</li>
     *    <li>{@code ""} if the driver accepts the non-converged
     *        verdict (caller should stop the loop);</li>
     *    <li>feedback text if the driver wants another iteration.</li>
     *  </ul>
     */
    public String askConvergeIterate(int iteration, String report) throws IOException {
        InboundCommand cmd;
        while (true) {
            cmd = readReply();
            if (cmd.action.equals("converge-iterate")) {
                return cmd.text == null ? "" : cmd.text;
            }
            if (cmd.action.equals("quit")) {
                aborted = true;
                emitAbort("user-quit");
                return null;
            }
            if (cmd.action.equals("skip") || cmd.action.equals("accept")) {
                return "";  // user accepts not-converged
            }
            LOG.warn("InteractiveRepl: ignoring out-of-order action {} while waiting for converge-iterate",
                    cmd.action);
        }
    }

    public boolean isAborted() { return aborted; }

    /** R293: shut down the bounded-read executor. Called by
     *  {@link SddRunner} after the orchestrator finishes so
     *  the JVM is free to exit (otherwise the
     *  {@code Executors.newSingleThreadExecutor} keeps a
     *  non-daemon worker alive — see R293 lesson 619). */
    public void close() {
        readExecutor.shutdownNow();
    }

    // ------------------------------------------------------------
    // internal: wire I/O + reply parsing
    // ------------------------------------------------------------

    private final Map<PhaseId, Integer> revisionCounter = new LinkedHashMap<>();

    private int revisionCountFor(PhaseId phase) {
        return revisionCounter.getOrDefault(phase, 0);
    }

    /** Bump the revision counter after a phase accepts a revise
     *  reply. Used so the next {@code phase-accepted} event
     *  reports the right {@code revisionCount}. */
    public void recordRevision(PhaseId phase) {
        revisionCounter.merge(phase, 1, Integer::sum);
    }

    /** Synchronously write one event to {@code out} and flush. */
    private synchronized void writeOut(Map<String, Object> ev) throws IOException {
        String json = MAPPER.writeValueAsString(ev);
        out.write(json);
        out.write("\n");
        out.flush();
    }

    private void emitAbort(String reason) {
        try {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("event", "abort");
            ev.put("reason", reason);
            writeOut(ev);
        } catch (IOException ignore) {}
    }

    /** Block until a JSON command arrives on {@code in}, parse it,
     *  return the typed command. EOF on {@code in} means the driver
     *  closed the pipe → treat as {@code quit}. Bounded by
     *  {@link #READ_TIMEOUT_SECONDS} — see the executor field for
     *  why we don't trust raw {@code readLine()} to ever return
     *  when the driver subprocess is healthy. */
    private InboundCommand readReply() throws IOException {
        while (true) {
            String line;
            try {
                Future<String> f = readExecutor.submit(() -> in.readLine());
                line = f.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                LOG.warn("InteractiveRepl: readReply timed out after {} s — auto-accepting the current phase", READ_TIMEOUT_SECONDS);
                log("warn", "InteractiveRepl: readReply timed out — driver likely stuck; auto-accepting");
                return new InboundCommand("accept", "", "");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("readReply interrupted", ie);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof IOException) throw (IOException) cause;
                throw new IOException("readReply execution failed", cause);
            }
            if (line == null) {
                // EOF
                aborted = true;
                return new InboundCommand("quit", "", "");
            }
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = MAPPER.readValue(line, Map.class);
                String action = (String) m.getOrDefault("action", "");
                // accept either {text: "..."} (revise / converge-iterate)
                // or {answer: "..."} (clarify-answer). The two field
                // names mean the same thing to the runner; pick the
                // first non-empty one.
                String text = (String) m.getOrDefault("text", "");
                if (text == null || text.isEmpty()) {
                    text = (String) m.getOrDefault("answer", "");
                }
                String id = (String) m.getOrDefault("id", "");
                return new InboundCommand(action, text == null ? "" : text, id);
            } catch (Exception e) {
                // Log and keep reading. The driver may emit log lines
                // or partial JSONL during debugging — never crash the
                // runner on a malformed input.
                LOG.warn("InteractiveRepl: bad inbound JSON, ignoring: {}", e.getMessage());
            }
        }
    }

    /** Test/CLI plumbing: load a draft from disk so the driver
     *  can fetch the full content via the {@code path} emitted
     *  in {@code phase-draft}. The runner already writes the
     *  artefact to disk before {@link #confirm} is called, so
     *  this is just a convenience for tests + the desktop driver. */
    public static String readDraft(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private record InboundCommand(String action, String text, String id) {}
}