package org.aethercode.deepagents.graph;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.Message;

import java.util.List;
import java.util.Map;

/**
 * Agent state for a deep agent.
 *
 * <p>Java-native port of
 * {@code deepagents.graph.DeepAgentState}. Mirrors
 * {@link AgentState} but lets consumers tag the class for the
 * chat-model adapter to recognise. The
 * {@code DeltaChannel}-on-messages behavior of the Python port
 * is implemented by the runtime in R3 (graph execution); the
 * Java port uses the same {@link AgentState} record so the
 * reducer is hooked up at graph-assemble time.</p>
 */
public record DeepAgentState(
        List<Message> messages,
        Map<String, org.aethercode.core.fs.backend.FileData> files,
        Map<String, Object> extensions) {

    public DeepAgentState {
        messages = messages == null ? List.of() : List.copyOf(messages);
        files = files == null ? Map.of() : Map.copyOf(files);
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public static DeepAgentState empty() {
        return new DeepAgentState(List.of(), Map.of(), Map.of());
    }

    public DeepAgentState withMessages(List<Message> newMessages) {
        return new DeepAgentState(newMessages, files, extensions);
    }
    public DeepAgentState withFiles(Map<String, org.aethercode.core.fs.backend.FileData> newFiles) {
        return new DeepAgentState(messages, newFiles, extensions);
    }
    public DeepAgentState withExtensions(Map<String, Object> newExt) {
        return new DeepAgentState(messages, files, newExt);
    }
    public DeepAgentState withExtension(String key, Object value) {
        java.util.Map<String, Object> next = new java.util.LinkedHashMap<>(extensions);
        next.put(key, value);
        return new DeepAgentState(messages, files, next);
    }

    /** Convert to the runtime-neutral {@link AgentState} used
     *  internally by the middleware. */
    @SuppressWarnings("unchecked")
    public AgentState toAgentState() {
        Map<String, Object> filesObj = (Map<String, Object>) (Map<?, ?>) files;
        return new AgentState(messages, filesObj, extensions);
    }

    /** Build a {@link DeepAgentState} from the runtime-neutral
     *  {@link AgentState}. */
    @SuppressWarnings("unchecked")
    public static DeepAgentState fromAgentState(AgentState state) {
        Map<String, org.aethercode.core.fs.backend.FileData> files =
                (Map<String, org.aethercode.core.fs.backend.FileData>) (Map<?, ?>) state.files();
        return new DeepAgentState(state.messages(), files, state.extensions());
    }
}
