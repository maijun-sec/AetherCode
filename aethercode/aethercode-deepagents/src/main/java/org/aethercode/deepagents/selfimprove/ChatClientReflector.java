package org.aethercode.deepagents.selfimprove;

import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.stream.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * R241.2 (O-3): a {@link Reflector} backed by the project's
 * existing {@link ChatClient}. The reflection is a single
 * chat round (no tools, no history beyond the prompt pair);
 * the client returns a {@link Stream} of
 * {@link StreamEvent}s which this class drains into a string.
 *
 * <p>This keeps the reflection step provider-agnostic. The
 * default wiring uses {@code SpringAiChatClient}, which
 * defaults to {@code MiniMax-M3} (the project's primary
 * chat model). Switching to GPT-4o or Claude is a matter of
 * swapping the {@code ChatClient} passed to the constructor
 * — no other change is needed.
 *
 * <p>Why "drain the stream" and not a one-shot
 * {@code complete()}? Because {@code ChatClient} has the
 * single {@code stream()} contract; adding a blocking
 * {@code complete()} would create a parallel API. The
 * reflection prompt is small (≤ 2K tokens) so the streaming
 * overhead is negligible.
 */
public class ChatClientReflector implements Reflector {

    private static final Logger LOG = LoggerFactory.getLogger(ChatClientReflector.class);

    private final ChatClient client;
    private final int maxTokens;

    public ChatClientReflector(ChatClient client) {
        this(client, 512);
    }

    public ChatClientReflector(ChatClient client, int maxTokens) {
        this.client = Objects.requireNonNull(client, "client");
        if (maxTokens < 64) {
            throw new IllegalArgumentException("maxTokens must be >= 64, got " + maxTokens);
        }
        this.maxTokens = maxTokens;
    }

    @Override
    public String reflect(String systemPrompt, String userPrompt) {
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userPrompt, "userPrompt");
        List<Message> messages = new ArrayList<>(1);
        // Message.userText is the canonical one-arg builder.
        messages.add(Message.userText(userPrompt));
        // Reflection is a no-tool call: an empty tool list.
        StringBuilder sb = new StringBuilder();
        try (Stream<StreamEvent> stream = client.stream(messages, systemPrompt, List.of())) {
            for (var it = stream.iterator(); it.hasNext(); ) {
                StreamEvent ev = it.next();
                if (ev == null) continue;
                if (ev instanceof StreamEvent.TextDelta td) {
                    sb.append(td.text());
                } else if (ev instanceof StreamEvent.RunEnd re
                        && re.finalBlocks() != null) {
                    for (var block : re.finalBlocks()) {
                        if (block instanceof org.aethercode.core.message.ContentBlock.TextBlock tb) {
                            sb.append(tb.text());
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("chat-client reflector failed: {}", e.getMessage());
            throw e instanceof RuntimeException re ? re
                    : new RuntimeException("reflector call failed", e);
        }
        if (sb.length() == 0) {
            LOG.debug("chat-client reflector: empty response (model={})", client.modelId());
            return "";
        }
        return sb.toString();
    }
}
