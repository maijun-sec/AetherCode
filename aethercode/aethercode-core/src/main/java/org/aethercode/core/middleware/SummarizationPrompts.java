package org.aethercode.core.middleware;

import java.util.List;
import org.aethercode.core.runtime.Message.SystemMessage;

/**
 * Constants and shared prompts for the summarization middleware.
 *
 * <p>Java-native port of the constants and prompt templates at the
 * top of {@code deepagents.middleware.summarization}. The strings
 * mirror the Python port one-for-one so the model receives the
 * same guidance.</p>
 */
public final class SummarizationPrompts {
    private SummarizationPrompts() {}

    /** Default messages-to-keep cap (mirrors langchain's
     *  {@code _DEFAULT_MESSAGES_TO_KEEP}). */
    public static final int DEFAULT_MESSAGES_TO_KEEP = 20;
    /** Default trim-token limit (mirrors langchain's
     *  {@code _DEFAULT_TRIM_TOKEN_LIMIT}). */
    public static final int DEFAULT_TRIM_TOKEN_LIMIT = 4_000;

    /** State keys used by the summarization middleware. */
    public static final String SUMMARY_KEY = "summary";
    /** Marker tag the summarization middleware adds to the
     *  SystemMessage it emits after a compaction pass. Mirrors the
     *  Python port's {@code lc_source="summarization"} convention. */
    public static final String SUMMARIZATION_MESSAGE_SOURCE = "summarization";

    /** Where evicted messages are stored in the backend. */
    public static final String CONVERSATION_HISTORY_DIR = "/conversation_history";
    /** Subdirectory for offloaded media (base64 chunks etc.). */
    public static final String CONVERSATION_HISTORY_MEDIA_DIR =
            CONVERSATION_HISTORY_DIR + "/media";

    /** Default summary-prompt addendum explaining the
     *  media-reference tags. The full default is the upstream
     *  LangChain {@code DEFAULT_SUMMARY_PROMPT} with this
     *  addendum prepended. */
    public static final String MEDIA_REFERENCE_SUMMARY_PROMPT = """
            <media_reference_information>

            When the conversation contains media (images, PDFs, audio) that the offload helper has stored on the filesystem, it is referenced inline via the tag `<media src="..."/>` where `src` is the path inside the conversation_history/media directory. When summarizing, preserve these tags verbatim — the model that reads the summary needs them to know which media files are still part of the conversation.""";

    /** Default deepagents-augmented summary prompt. */
    public static final String DEEPAGENTS_DEFAULT_SUMMARY_PROMPT = MEDIA_REFERENCE_SUMMARY_PROMPT;
}
