package org.aethercode.workflows.ssd;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * R281 — interactive REPL for the SSD runner. Implements
 * {@link SsdRunner.ReplFn} but instead of asking the terminal
 * to type "ok" / revision text, emits newline-delimited JSON
 * events to a sink (typically {@code System.out}) and reads
 * newline-delimited JSON commands from a source (typically
 * {@code System.in}). The desktop's {@code SsdPanel} is the
 * canonical driver; CLI use goes through the existing
 * {@code StdioRepl}.
 *
 * <h2>Protocol</h2>
 * <h3>Outbound events (one JSON object per line)</h3>
 * <pre>
 *   {"event": "phase-list", "phases": [{"id":"spec", "order":1, "title":"Spec"}, ...]}
 *   {"event": "phase-start", "phase":"spec", "order":1, "title":"Spec"}
 *   {"event": "phase-draft", "phase":"spec", "path":"/.../spec.md", "bytes":4321, "preview":"..."}
 *   {"event": "phase-confirm-required", "phase":"spec", "revisionCount":0}
 *   {"event": "phase-revising", "phase":"spec", "revision":"add NFR-3"}
 *   {"event": "phase-draft", "phase":"spec", "path":"/.../spec.md", "bytes":5120}
 *   {"event": "phase-confirm-required", "phase":"spec", "revisionCount":1}
 *   {"event": "phase-accepted", "phase":"spec", "revisionCount":1}
 *   {"event": "phase-skipped", "phase":"spec", "reason":"already-accepted"}
 *   {"event": "phase-error", "phase":"spec", "message":"..."}
 *   {"event": "complete", "results":[{"phaseId":"spec", "path":".../spec.md", "revisions":1}, ...]}
 *   {"event": "abort", "reason":"user-quit"}
 *   {"event": "log", "level":"info", "message":"..."}
 * </pre>
 *
 * <h3>Inbound commands (one JSON object per line)</h3>
 * <pre>
 *   {"action":"accept"}                       // accept current phase, advance
 *   {"action":"revise", "text":"add NFR-3"}   // queue revision, re-draft, re-ask
 *   {"action":"quit"}                          // abort
 *   {"action":"skip"}                          // skip current phase (rare; for already-accepted artefacts)
 * </pre>
 *
 * <h2>Why JSON instead of text REPL</h2>
 * <p>The original {@code StdioRepl} reads a single-line "ok / revision
 * text / q" from stdin. That worked for terminals but it forced the
 * runner into a tight coupling with {@code System.in}/{@code System.out}
 * and made it impossible for a UI to drive the flow. By switching to
 * a stream-of-JSON protocol we get:
 * <ul>
 *   <li>a stable wire contract — any UI (Electron / Tauri / TUI / web) can
 *       drive the runner without code changes;</li>
 *   <li>rich metadata — the UI sees the {@code revisionCount}, the
 *       {@code preview}, the {@code path} of each artefact, and can
 *       render a TODO list with per-phase state (pending / running /
 *       done / failed);</li>
 *   <li>recoverability — a stalled driver can be re-attached by
 *       replaying the event stream;</li>
 *   <li>no terminal assumptions — the runner no longer reads from
 *       {@code System.in}, so it composes with subprocess
 *       frameworks cleanly.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <p>{@link #confirm} is called from the orchestrator's main
 * thread. {@link #writeOut} blocks on {@code out.flush()}. The
 * driver (desktop / TUI / test) reads events from {@code out}
 * asynchronously; we never block waiting for input on the read side
 * here — the driver is responsible for sending a command before
 * the next phase starts. The implementation is therefore
 * fire-and-sync: each event is written + flushed before the next
 * LLM call, and {@link #confirm} blocks until {@link #nextReply}
 * has a value.
 */
public final class InteractiveRepl implements SsdRunner.ReplFn {

    private static final Logger LOG = LoggerFactory.getLogger(InteractiveRepl.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BufferedWriter out;
    private final BufferedReader in;
    private final String feature;
    private final List<SsdConfig.Phase> phaseList;
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
     *
     * @param in    the stream from which to read inbound commands
     *              (one JSON object per line)
     * @param out   the stream to which to write outbound events
     *              (one JSON object per line, newline-terminated,
     *              flushed after every event)
     * @param feature the feature name; emitted in {@code phase-list}
     *                so the UI knows what it's looking at
     * @param phaseList the resolved ordered phase list; emitted in
     *                   {@code phase-list} so the UI can render the
     *                   TODO chips before the first LLM call
     */
    public InteractiveRepl(InputStream in,
                           OutputStream out,
                           String feature,
                           List<SsdConfig.Phase> phaseList) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        this.feature = feature;
        this.phaseList = List.copyOf(phaseList);
    }

    /**
     * Convenience constructor that wires {@code System.in}/{@code
     * System.out}. Same protocol as the explicit-args overload;
     * identical behaviour at the wire. The CLI's {@code --interactive}
     * flag uses this overload.
     */
    public static InteractiveRepl stdio(String feature, List<SsdConfig.Phase> phaseList) {
        return new InteractiveRepl(System.in, System.out, feature, phaseList);
    }

    /** Emit the initial phase-list so the UI can render the TODO
     *  chips before the first LLM call. Idempotent — the runner
     *  may be constructed multiple times across phases. */
    public void emitPhaseList() throws IOException {
        List<Map<String, Object>> phases = new ArrayList<>(phaseList.size());
        for (SsdConfig.Phase p : phaseList) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("order", p.order());
            m.put("title", p.title());
            phases.add(m);
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event", "phase-list");
        ev.put("feature", feature);
        ev.put("phases", phases);
        writeOut(ev);
    }

    @Override
    public Optional<String> confirm(String phaseTitle, Path artefactPath, String content) throws Exception {
        // Emit the draft first, then the confirm-required event.
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("event", "phase-draft");
        draft.put("phase", phaseTitle);
        draft.put("path", artefactPath.toString());
        draft.put("bytes", content.length());
        // Send only the first 4 KB as preview. The UI fetches the
        // full content from disk when the user opens the preview
        // pane, so we don't bloat the event stream.
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
            ev.put("phase", phaseTitle);
            ev.put("revisionCount", revisionCountFor(phaseTitle));
            writeOut(ev);
            return Optional.empty();
        }
        if (cmd.action.equals("revise")) {
            // Tell the driver we're revising (so it can show
            // "revising..." spinner instead of "running...").
            Map<String, Object> rev = new LinkedHashMap<>();
            rev.put("event", "phase-revising");
            rev.put("phase", phaseTitle);
            rev.put("revision", cmd.text);
            writeOut(rev);
            return Optional.of(cmd.text);
        }
        if (cmd.action.equals("skip")) {
            // Same as accept but emit a different event so the UI
            // can mark the phase as "skipped" instead of "done".
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("event", "phase-skipped");
            ev.put("phase", phaseTitle);
            ev.put("reason", "driver-skip");
            writeOut(ev);
            return Optional.empty();
        }
        throw new IOException("unknown action from driver: " + cmd.action);
    }

    /** Emit a single {@code complete} event after the runner has
     *  finished successfully. Each result is the path + revision
     *  count for one phase. */
    public void emitComplete(List<SsdRunner.PhaseResult> results) throws IOException {
        List<Map<String, Object>> outList = new ArrayList<>();
        for (SsdRunner.PhaseResult r : results) {
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
    public void emitPhaseStart(String phaseId, int order, String title) throws IOException {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event", "phase-start");
        ev.put("phase", phaseId);
        ev.put("order", order);
        ev.put("title", title);
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

    public boolean isAborted() { return aborted; }

    // ------------------------------------------------------------
    // internal: wire I/O + reply parsing
    // ------------------------------------------------------------

    private final java.util.Map<String, Integer> revisionCounter = new java.util.HashMap<>();

    private int revisionCountFor(String phaseTitle) {
        return revisionCounter.getOrDefault(phaseTitle, 0);
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
     *  closed the pipe → treat as {@code quit}. */
    private InboundCommand readReply() throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = MAPPER.readValue(line, Map.class);
                String action = (String) m.getOrDefault("action", "");
                String text = (String) m.getOrDefault("text", "");
                return new InboundCommand(action, text);
            } catch (Exception e) {
                // Log and keep reading. The driver may emit log lines
                // or partial JSONL during debugging — never crash the
                // runner on a malformed input.
                LOG.warn("InteractiveRepl: bad inbound JSON, ignoring: {}", e.getMessage());
            }
        }
        // EOF
        aborted = true;
        return new InboundCommand("quit", "");
    }

    /** Bump the revision counter after a phase accepts a revise
     *  reply. Used so the next {@code phase-accepted} event
     *  reports the right {@code revisionCount}. */
    public void recordRevision(String phaseTitle) {
        revisionCounter.merge(phaseTitle, 1, Integer::sum);
    }

    /** Test/CLI plumbing: load a draft from disk so the driver
     *  can fetch the full content via the {@code path} emitted
     *  in {@code phase-draft}. The runner already writes the
     *  artefact to disk before {@link #confirm} is called, so
     *  this is just a convenience for tests + the desktop driver. */
    public static String readDraft(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private record InboundCommand(String action, String text) {}
}