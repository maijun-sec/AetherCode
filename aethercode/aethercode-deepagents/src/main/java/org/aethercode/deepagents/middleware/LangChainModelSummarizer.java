package org.aethercode.deepagents.middleware;

import org.aethercode.core.middleware.SummarizationHistory;
import org.aethercode.core.middleware.SummarizationPrompts;
import org.aethercode.core.middleware.Summarizer;

import org.aethercode.deepagents.langchain_compat.language_models.BaseChatModel;
import org.aethercode.deepagents.langchain_compat.messages.LangChainMessage;
import org.aethercode.deepagents.langchain_compat.runnables.RunnableConfig;
import org.aethercode.core.runtime.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Default {@link Summarizer} backed by a {@link BaseChatModel}.
 *
 * <p>Mirrors Python's
 * {@code _DeepAgentsSummarizationMiddleware._create_summary} which
 * delegates to the configured chat model. The model is invoked
 * with the system summary prompt (carrying the
 * {@code {messages}} placeholder) plus the messages to
 * summarise. The response is unwrapped to its text content.</p>
 */
public final class LangChainModelSummarizer implements Summarizer {

    private final String name;
    private final BaseChatModel model;
    private final String summaryPrompt;

    public LangChainModelSummarizer(String name, BaseChatModel model, String summaryPrompt) {
        this.name = name;
        this.model = model;
        this.summaryPrompt = summaryPrompt == null ? "" : summaryPrompt;
    }

    @Override
    public String name() { return name; }

    @Override
    public String summarize(List<Message> messages, Map<String, Object> context) {
        // Build the prompt: the configured summary
        // template (which usually contains a {messages}
        // placeholder) with the messages rendered as
        // XML-style blocks.
        String rendered = renderSummaryPrompt(messages);
        LangChainMessage userMsg = new LangChainMessage() {
            @Override public String role() { return "user"; }
            @Override public List<?> content() { return List.of(rendered); }
            @Override public String id() { return "summary-prompt"; }
        };
        Object result;
        try {
            result = model.invoke(List.of(userMsg), RunnableConfig.empty());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Summarizer model call failed", e);
        }
        return extractText(result);
    }

    /** Render the summary prompt by substituting the
     *  {@code {messages}} placeholder with an XML block
     *  list of the messages, or appending them when the
     *  template does not contain the placeholder. */
    private String renderSummaryPrompt(List<Message> messages) {
        String blocks = SummarizationHistory.formatSection(
                messages, java.time.Instant.now());
        if (summaryPrompt.contains("{messages}")) {
            return summaryPrompt.replace("{messages}", blocks);
        }
        return summaryPrompt + "\n\n" + blocks;
    }

    /** Pull the text out of a model response. The model
     *  may return a {@link LangChainMessage} (most
     *  common) or a richer response object; both are
     *  tolerated. */
    @SuppressWarnings("unchecked")
    private static String extractText(Object result) {
        if (result == null) return "";
        if (result instanceof LangChainMessage m) {
            return flatten(m.content());
        }
        if (result instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                if (o instanceof LangChainMessage m) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(flatten(m.content()));
                }
            }
            return sb.toString();
        }
        return result.toString();
    }

    private static String flatten(List<?> content) {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Object o : content) {
            if (o == null) continue;
            if (o instanceof String s) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(s);
            } else if (o instanceof org.aethercode.core.runtime.ContentBlock.TextBlock t) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(t.text());
            } else {
                // Fallback: toString for other content
                // block shapes.
                if (sb.length() > 0) sb.append("\n");
                sb.append(o.toString());
            }
        }
        return sb.toString();
    }

    /**
     * Convenience factory: produce a {@link LangChainModelSummarizer}
     * for the given model using the project's default
     * {@code DEFAULT_SUMMARY_PROMPT} template. Mirrors Python's
     * {@code _create_summarization_middleware()} factory's
     * default prompt.
     */
    public static LangChainModelSummarizer defaultFor(BaseChatModel model) {
        return new LangChainModelSummarizer("default", model,
                SummarizationPrompts.DEEPAGENTS_DEFAULT_SUMMARY_PROMPT);
    }
}
