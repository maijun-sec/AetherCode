package org.aethercode.a2a.bridge;

import org.aethercode.a2a.A2AServer;
import org.aethercode.a2a.schema.Artifact;
import org.aethercode.a2a.schema.Message;
import org.aethercode.a2a.schema.Part;
import org.aethercode.core.runtime.AgentState;
import org.aethercode.core.runtime.ContentBlock;
import org.aethercode.deepagents.graph.DeepAgent;
import org.aethercode.deepagents.graph.DeepAgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Bridge between {@link DeepAgent} and {@link A2AServer}. Wraps a deep-agent invocation as an
 * A2A handler so the same agent supports both {@code message/send} (synchronous) and
 * {@code message/sendSubscribe} (streaming) entry points.
 *
 * <p>Two handler shapes:</p>
 * <ul>
 *   <li>{@code message/send} — synchronous, returns a single {@link Artifact} per call. Obtained via {@link #syncHandler()}.</li>
 *   <li>{@code message/sendSubscribe} — streaming, pushes each runtime event as an A2A streaming update and emits a terminal status at the end. Obtained via {@link #streamingHandler()}.</li>
 * </ul>
 *
 * <p>Both shapes share {@code DeepAgent#stream} underneath: the sync handler discards the event
 * stream after collecting the final text, while the streaming handler maps each event to the SSE
 * sink.</p>
 *
 * <p>This lives in its own module so the protocol layer (aethercode-a2a) does not depend on the
 * deepagent runtime directly. Consumers who want to plug a different agent into A2A can supply
 * their own {@code Function<Message, Artifact>}.</p>
 */
public final class DeepAgentA2AHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DeepAgentA2AHandler.class);

    private final DeepAgent deepAgent;
    private final Function<List<org.aethercode.core.runtime.Message>,
            org.aethercode.core.runtime.Message.AIMessage> chatModel;
    private final int maxIterations;

    /**
     * @param deepAgent    the deep agent invoked once per request.
     * @param chatModel    optional chat model; when {@code null}, the agent's stub path is used
     *                     (returns {@code "Graph runtime not yet compiled"}).
     * @param maxIterations cap on tool-dispatch rounds. When {@code <= 0}, defaults to
     *                     {@link DeepAgent#DEFAULT_MAX_ITERATIONS}.
     */
    public DeepAgentA2AHandler(DeepAgent deepAgent,
                               Function<List<org.aethercode.core.runtime.Message>,
                                       org.aethercode.core.runtime.Message.AIMessage> chatModel,
                               int maxIterations) {
        this.deepAgent = Objects.requireNonNull(deepAgent, "deepAgent");
        this.chatModel = chatModel;
        this.maxIterations = maxIterations > 0 ? maxIterations
                : DeepAgent.DEFAULT_MAX_ITERATIONS;
    }

    public DeepAgentA2AHandler(DeepAgent deepAgent) {
        this(deepAgent, null, DeepAgent.DEFAULT_MAX_ITERATIONS);
    }

    public DeepAgentA2AHandler(DeepAgent deepAgent,
                               Function<List<org.aethercode.core.runtime.Message>,
                                       org.aethercode.core.runtime.Message.AIMessage> chatModel) {
        this(deepAgent, chatModel, DeepAgent.DEFAULT_MAX_ITERATIONS);
    }

    public DeepAgent deepAgent() { return deepAgent; }

    /**
     * Build the {@code A2AServer} synchronous handler
     * ({@code Function<Message, Artifact>}) that runs the
     * deep agent and returns the final text reply as a single
     * text artifact.
     */
    public Function<Message, Artifact> syncHandler() {
        return this::handleSync;
    }

    /**
     * Returns the streaming handler, which pushes an A2A update for every {@link DeepAgentEvent}
     * it sees. Runtime events map to the A2A stream as follows:
     * <ul>
     *   <li>{@link DeepAgentEvent.BeforeModel} → {@code status("working", "iter N")}</li>
     *   <li>{@link DeepAgentEvent.AfterModel} → if the AI message contains non-empty text, push {@code artifact(text reply)}</li>
     *   <li>{@link DeepAgentEvent.ToolDispatch} → {@code status("working", "tool: name")}</li>
     *   <li>{@link DeepAgentEvent.Final} → push the final artifact, then close with {@code status("completed", "done")}</li>
     * </ul>
     */
    public A2AServer.StreamingHandler streamingHandler() {
        return this::handleStream;
    }

    // sync path

    private Artifact handleSync(Message userMessage) {
        String input = extractUserText(userMessage);
        DeepAgent.DeepAgentResult result = invoke(input);
        String text = result.text();
        if (text == null || text.isEmpty()) {
            // Even on empty text, return a named artifact — some A2A clients reject artifacts with no parts.
            return Artifact.of("response", Part.TextPart.of(""));
        }
        return Artifact.of("response", Part.TextPart.of(text));
    }

    // streaming path

    private void handleStream(Message userMessage, Consumer<A2AServer.TaskUpdate> emit) {
        String input = extractUserText(userMessage);
        // Emit a "working" status unconditionally first so the client immediately sees the agent
        // spinning up; subsequent BeforeModel events provide finer-grained iter progress.
        emit.accept(A2AServer.TaskUpdate.status("working", "deepagent starting"));
        try {
            DeepAgent.StreamResult result = stream(input);
            int iter = 0;
            for (DeepAgentEvent ev : result.events()) {
                if (ev instanceof DeepAgentEvent.BeforeModel bm) {
                    iter++;
                    emit.accept(A2AServer.TaskUpdate.status("working",
                            "iter " + iter));
                } else if (ev instanceof DeepAgentEvent.AfterModel am) {
                    String text = ContentBlock.flattenText(am.message().content());
                    if (!text.isEmpty()) {
                        emit.accept(A2AServer.TaskUpdate.artifact(
                                Artifact.of("model-text", Part.TextPart.of(text))));
                    }
                } else if (ev instanceof DeepAgentEvent.ToolDispatch td) {
                    String toolName = td.toolMessage().name().orElse("?");
                    emit.accept(A2AServer.TaskUpdate.status("working",
                            "tool: " + toolName));
                } else if (ev instanceof DeepAgentEvent.Final f) {
                    // The Final text matches what the sync handler returns; emit it as a canonical
                    // artifact named "response", then close with a terminal status.
                    String text = f.finalText();
                    if (text != null && !text.isEmpty()) {
                        emit.accept(A2AServer.TaskUpdate.artifact(
                                Artifact.of("response", Part.TextPart.of(text))));
                    }
                    emit.accept(A2AServer.TaskUpdate.status("completed", "done"));
                }
            }
        } catch (Exception e) {
            LOG.error("deepagent streaming failed: {}", e.getMessage(), e);
            emit.accept(A2AServer.TaskUpdate.status("failed", e.getMessage()));
        }
    }

    // helpers

    private DeepAgent.DeepAgentResult invoke(String input) {
        if (chatModel == null) {
            return deepAgent.invoke(AgentState.empty(), input);
        }
        return deepAgent.invoke(AgentState.empty(), input, chatModel, maxIterations);
    }

    private DeepAgent.StreamResult stream(String input) {
        if (chatModel == null) {
            // Without a chat model, fall back to the stub: it produces a single Final event, so the
            // handler still emits working + final, keeping the wire shape correct.
            DeepAgent.DeepAgentResult r = deepAgent.invoke(AgentState.empty(), input);
            DeepAgentEvent.Final fin = new DeepAgentEvent.Final(r.state(), r.text());
            return new DeepAgent.StreamResult(r.state(), r.text(), List.of(fin));
        }
        return deepAgent.stream(AgentState.empty(), input, chatModel, maxIterations);
    }

    /** Joins the text parts of a user message into a single newline-delimited string. Other part
     *  types are not handled yet. */
    private static String extractUserText(Message msg) {
        if (msg == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Part p : msg.parts()) {
            if (p instanceof Part.TextPart tp) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(tp.text());
            }
            // FilePart / DataPart are not expanded here; we can extend this once deepagent tool
            // registration gains file-aware support.
        }
        return sb.toString();
    }
}
