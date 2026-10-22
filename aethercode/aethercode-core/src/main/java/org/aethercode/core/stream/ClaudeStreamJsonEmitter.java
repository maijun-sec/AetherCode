package org.aethercode.core.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.core.message.ContentBlock;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R747: emits AetherCode's {@link StreamEvent} family as Claude Code's
 * {@code stream-json} NDJSON wire format, so that external schedulers
 * which already speak the Claude Code protocol can drive AetherCode
 * without any change on their side.
 *
 * <h2>Why this exists</h2>
 * <p>Multica (github.com/multica-ai/multica) dispatches work to local AI
 * coding CLIs. It has no plugin mechanism for a new agent backend — adding
 * one means forking the Go server, editing a hard-coded switch, and writing
 * a SQL migration for the {@code runtime_profile.protocol_family} CHECK
 * constraint. Its documented escape hatch is a <em>custom runtime profile</em>:
 * declare a command that is compatible with one of the protocol families
 * Multica already supports. This class is the AetherCode side of that
 * declaration for the {@code claude} family.
 *
 * <h2>Why the {@code claude} family specifically</h2>
 * <ul>
 *   <li><b>Explicit terminal event.</b> Claude Code emits {@code type:"result"}.
 *       OpenCode has no terminal event at all, which is why Multica needs
 *       ~140 lines of heuristics to infer one. This engine's R735→R746 work
 *       already guarantees a {@link StreamEvent.RunEnd} on every termination
 *       path, so the mapping is direct and needs no guessing.</li>
 *   <li><b>No version fork.</b> OpenCode's 2.x contract is gated on
 *       {@code cfg.BuiltinRuntime}, so a custom profile is permanently locked
 *       to 1.x semantics. Claude Code has no such fork.</li>
 *   <li><b>MCP travels as a flag.</b> Claude Code takes {@code --mcp-config
 *       <tempfile>}; OpenCode 1.x needs an env var and 2.x refuses outright.</li>
 * </ul>
 *
 * <h2>Fail-closed terminal contract</h2>
 * <p>The consumer classifies a run by the {@code result} frame's
 * {@code is_error} / {@code terminal_reason}. A clean process exit with no
 * {@code result} frame is <em>not</em> success. {@link #finish} therefore
 * always emits a terminal frame, including when the stream was cut short
 * (stalled, cancelled, or threw) — that frame carries {@code is_error:true}
 * plus a {@code terminal_reason} naming the cause.
 *
 * <h2>What this class deliberately does NOT do</h2>
 * <ul>
 *   <li><b>No {@code control_request} / {@code control_response}.</b> Multica
 *       force-injects {@code --permission-mode bypassPermissions}, so the CLI
 *       never asks for tool approval mid-run. Implementing the reverse channel
 *       would be dead code today. If AetherCode later wants interactive
 *       prompts under a scheduler, this is the seam to extend.</li>
 *   <li><b>No usage accounting.</b> Claude Code populates both {@code usage}
 *       and {@code modelUsage}; when both are absent Multica simply reports
 *       zero tokens rather than failing. Populating them needs a per-model
 *       accumulator wired to the engine's metrics — deliberately a separate
 *       change.</li>
 * </ul>
 *
 * <p>Marker strings ({@code R747: …}) are stable substrings the deployment
 * check greps for in the packaged jar; comments are stripped by javac so they
 * cannot serve that purpose.
 */
public final class ClaudeStreamJsonEmitter {

    /** Marker greppable in the compiled class / daemon log. */
    public static final String MARKER = "R747: claude stream-json emitter";

    // R750d: there is deliberately NO local "which stopReasons are fine"
    // set in this class any more. It used to be an `OK_KINDS` allowlist
    // driving `!OK_KINDS.contains(kind)`, and it disagreed with the
    // protocol layer's classification of the SAME event — `cancelled` came
    // out as `is_error: true` here, though the user asked for it. The
    // single definition now lives on StreamEvent.isErrorOutcome(); every
    // layer asks that instead of re-deriving one.

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PrintStream out;
    /** runId doubles as the Claude Code {@code session_id}. */
    private String sessionId = "aethercode";
    /** Accumulated assistant text, replayed as the result frame's payload. */
    private final StringBuilder finalText = new StringBuilder();
    /** Monotonic per-run message id; Claude Code groups blocks under one. */
    private long msgSeq;
    private boolean terminalEmitted;

    public ClaudeStreamJsonEmitter(PrintStream out) {
        this.out = out;
    }

    /** The {@code session_id} reported in the {@code system} / {@code result} frames. */
    public String sessionId() {
        return sessionId;
    }

    /** True once a {@code type:"result"} frame has been written. */
    public boolean terminalEmitted() {
        return terminalEmitted;
    }

    /**
     * Feed one engine event. Returns true if a frame was written.
     *
     * <p>Unknown event types are ignored rather than throwing: the emitter
     * sits in the middle of a run, and a new {@code StreamEvent} variant
     * must not be able to kill a scheduled task.
     */
    public boolean accept(StreamEvent ev) {
        if (ev == null || terminalEmitted) return false;
        if (ev instanceof StreamEvent.RunStart rs) {
            sessionId = (rs.runId() == null || rs.runId().isBlank()) ? "aethercode" : rs.runId();
            return emitFrame(Map.of(
                    "type", "system",
                    "subtype", "init",
                    "session_id", sessionId,
                    "model", rs.model() == null ? "" : rs.model()));
        }
        if (ev instanceof StreamEvent.TextDelta td) {
            if (td.text() == null || td.text().isEmpty()) return false;
            finalText.append(td.text());
            return emitAssistantText(td.text());
        }
        if (ev instanceof StreamEvent.ToolUseStart tu) {
            return emitAssistantToolUse(tu.id(), tu.name(), tu.input());
        }
        if (ev instanceof StreamEvent.ToolResult tr) {
            return emitUserToolResult(tr.id(), tr.content(), tr.isError());
        }
        if (ev instanceof StreamEvent.RunEnd re) {
            // R750d: delegate to StreamEvent's ONE error taxonomy.
            //
            // This used to be `!OK_KINDS.contains(kind)` — an ALLOWLIST.
            // Any stopReason the list did not name was reported as
            // `is_error: true`, which mis-classified `cancelled` (the user
            // asked for it; Claude Code reports a cancelled run as a normal
            // exit) and would mis-classify every reason added later until
            // somebody remembered to update this set.
            //
            // An allowlist fails open toward "error" and a denylist fails
            // open toward "ok"; R743's lesson is that only ONE of them may
            // exist, so both the protocol's `eventToMap` and this emitter
            // now ask StreamEvent.isErrorOutcome().
            return emitResult(
                    collectText(re.finalBlocks()),
                    re.isErrorOutcome(),
                    null,
                    re.stopReason());
        }
        // SideNote / SubTask* / metrics and friends carry no Claude Code
        // equivalent. Skipping them keeps the wire protocol clean; a
        // scheduler still sees the plan change through the result frame.
        return false;
    }

    /**
     * Emit the terminal {@code type:"result"} frame if the stream ended
     * without one. This is the fail-closed guarantee: the scheduler decides
     * success from this frame, so a truncated run must not look like a clean
     * exit.
     *
     * @param reason human-readable cause; mapped into {@code terminal_reason}
     * @param isError whether to mark the run failed
     */
    public boolean finish(boolean isError, String reason) {
        if (terminalEmitted) return false;
        return emitResult(finalText.toString(), isError, reason, reason);
    }

    /** Flush stdout so a scheduler reading the pipe sees frames promptly. */
    public void flush() {
        out.flush();
    }

    // ---- frame builders -------------------------------------------------

    private boolean emitAssistantText(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return emitAssistant(List.of(block));
    }

    private boolean emitAssistantToolUse(String id, String name, Map<String, Object> input) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_use");
        block.put("id", id == null ? "" : id);
        block.put("name", name == null ? "" : name);
        block.put("input", input == null ? Map.of() : input);
        return emitAssistant(List.of(block));
    }

    private boolean emitAssistant(List<Map<String, Object>> blocks) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "msg_" + (++msgSeq));
        message.put("role", "assistant");
        message.put("content", blocks);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "assistant");
        frame.put("session_id", sessionId);
        frame.put("message", message);
        return write(frame);
    }

    private boolean emitUserToolResult(String toolUseId, Object content, boolean isError) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId == null ? "" : toolUseId);
        block.put("content", content == null ? "" : stringify(content));
        block.put("is_error", isError);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(block));
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "user");
        frame.put("session_id", sessionId);
        frame.put("message", message);
        return write(frame);
    }

    private boolean emitResult(String result, boolean isError, String terminalReason, String stopReason) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "result");
        frame.put("subtype", isError ? "error" : "success");
        frame.put("session_id", sessionId);
        frame.put("is_error", isError);
        frame.put("result", result == null ? "" : result);
        frame.put("num_turns", msgSeq);
        // Only prompt_too_long is a terminal_reason the consumer recognises
        // as its own input-size rejection; everything else is reported via
        // is_error so the classification stays honest.
        if (terminalReason != null) {
            frame.put("terminal_reason", terminalReason);
        }
        if (stopReason != null) {
            frame.put("aethercode_stop_reason", stopReason);
        }
        terminalEmitted = true;
        return write(frame);
    }

    // ---- helpers --------------------------------------------------------

    private boolean emitFrame(Map<String, Object> frame) {
        return write(frame);
    }

    private boolean write(Map<String, Object> frame) {
        try {
            out.println(MAPPER.writeValueAsString(frame));
            out.flush();
            return true;
        } catch (Exception e) {
            // Serialising one frame must not abort the run: the consumer is
            // fail-closed, so a dropped frame becomes a failed task rather
            // than a silent hang. Surface it on stderr, which the scheduler
            // captures separately from the protocol stream.
            System.err.println(MARKER + " frame write failed: " + e);
            return false;
        }
    }

    /** Flatten the engine's final content blocks into the result payload. */
    private static String collectText(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : blocks) {
            if (b instanceof ContentBlock.TextBlock tb) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(tb.text());
            }
        }
        return sb.toString();
    }

    private static String stringify(Object o) {
        if (o instanceof String s) return s;
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    /** Test seam: the frames written so far, as parsed JSON. */
    public List<Map<String, Object>> drainForTest(java.io.ByteArrayOutputStream sink, ObjectMapper reader) {
        List<Map<String, Object>> parsed = new ArrayList<>();
        for (String line : sink.toString(java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = reader.readValue(line, Map.class);
                parsed.add(m);
            } catch (Exception ignored) {
                // A non-JSON line means a bug in the emitter; skip it so the
                // assertion below reports the real mismatch.
            }
        }
        return parsed;
    }
}