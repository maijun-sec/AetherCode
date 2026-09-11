package org.aethercode.code.hooks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.code.hooks.HookDomainEvents.Decision;
import org.aethercode.code.hooks.HookDomainEvents.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical boundary between the hook domain and the wire models.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.envelope.HookEnvelopeAdapter}. Java has
 * no Pydantic {@code TypeAdapter}, so projection and serialization
 * are simple, type-checked translations using Jackson.</p>
 */
public final class HookEnvelopeAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(HookEnvelopeAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Projection projection;
    private final Reducer reducer;

    public HookEnvelopeAdapter() {
        this(new Projection(), new Reducer());
    }

    public HookEnvelopeAdapter(Projection projection, Reducer reducer) {
        this.projection = projection;
        this.reducer = reducer;
    }

    /** Project a domain invocation into its wire input. */
    public WireEnvelope project(HookInvocation invocation, Path transcriptPath,
                                Path agentTranscriptPath) {
        return projection.project(invocation, transcriptPath, agentTranscriptPath);
    }

    /** Serialize a domain invocation to compact wire JSON. */
    public byte[] serialize(HookInvocation invocation, Path transcriptPath,
                            Path agentTranscriptPath) {
        WireEnvelope envelope = project(invocation, transcriptPath, agentTranscriptPath);
        try {
            return MAPPER.writeValueAsBytes(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize hook envelope", ex);
        }
    }

    /** Normalize ordered handler results into a domain decision. */
    public Decision reduce(HookInvocation invocation, List<HandlerResult> results,
                           List<HookDiagnostic> diagnostics) {
        if (diagnostics == null) diagnostics = List.of();
        return reducer.reduce(invocation, results, diagnostics);
    }

    /**
     * Wire envelope produced by {@link #project(HookInvocation, Path, Path)}
     * — a Jackson-friendly view of {@link WireTypes.BaseWireFields} plus
     * the per-event-specific input.
     */
    public record WireEnvelope(
            BaseWireFields base,
            String hookEventName,
            Event event,
            Object specific) {

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            if (base != null) {
                if (base.sessionId() != null) out.put("session_id", base.sessionId());
                if (base.transcriptPath() != null) out.put("transcript_path", base.transcriptPath());
                if (base.cwd() != null) out.put("cwd", base.cwd());
                if (base.permissionMode() != null) out.put("permission_mode", base.permissionMode().name().toLowerCase());
                if (base.promptId() != null) out.put("prompt_id", base.promptId());
                if (base.effort() != null) out.put("effort",
                        Map.of("level", base.effort().level()));
                if (base.agentId() != null) out.put("agent_id", base.agentId());
                if (base.agentType() != null) out.put("agent_type", base.agentType());
            }
            if (hookEventName != null) out.put("hook_event_name", hookEventName);
            if (specific instanceof Map<?, ?> m) {
                out.putAll(toStringKeyed(m));
            } else if (specific != null) {
                out.put("specific", specific);
            }
            return out;
        }
    }

    /** Common wire field set; reused by every event-specific input. */
    public record BaseWireFields(
            String sessionId,
            String transcriptPath,
            String cwd,
            WireTypes.WirePermissionMode permissionMode,
            String promptId,
            WireTypes.Effort effort,
            String agentId,
            String agentType) {
    }

    /** Thin DTO so {@link WireEnvelope} can hold the original event. */
    public record HandlerResult(String handlerId, WireTypes.HookWireOutput output,
                                List<HookDiagnostic> diagnostics, String plainOutput) {
        public HandlerResult {
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    private static Map<String, Object> toStringKeyed(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : source.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    static List<String> appendList(List<String> base, List<String> extra) {
        List<String> out = new ArrayList<>(base == null ? List.of() : base);
        if (extra != null) out.addAll(extra);
        return List.copyOf(out);
    }
}
