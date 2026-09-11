package org.aethercode.core.middleware;

import org.aethercode.core.runtime.Message;

import java.util.List;

/**
 * SPI for the LLM-driven summarization step.
 *
 * <p>Java-native port of the summarization engine used by
 * {@code deepagents.middleware.summarization.SummarizationMiddleware}.
 * The default no-op implementation throws
 * {@link SummarizerUnavailableError}; the R3 graph runtime wires
 * a real chat-model-backed implementation via
 * {@link SummarizerRegistry#register}.</p>
 */
public interface Summarizer {
    /** Identifier for the {@link SummarizerRegistry}. */
    String name();
    /** Produce a summary of {@code messages}. */
    String summarize(List<Message> messages, java.util.Map<String, Object> context);
}
