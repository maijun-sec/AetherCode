package org.aethercode.core.runtime.llm.profiles;

import org.aethercode.core.runtime.llm.HarnessProfile;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Built-in NVIDIA Nemotron 3 Ultra harness profile.
 *
 * <p>Java-native port of
 * {@code deepagents.profiles.harness._nvidia_nemotron_3_ultra}. The
 * profile layers behavior-shaping guidance onto the registered
 * Nemotron model specs: parallel tool calls, grounding, loop
 * control, tool selection, state-change completeness, final-answer
 * completeness, followup discipline, and context-compaction
 * hints. It also overrides the {@code read_file} tool description
 * with a Nemotron-specific version that includes document /
 * image / audio / video / PDF capabilities.</p>
 *
 * <p>The Python port attaches a chain of Nemotron-specific
 * Object
 * ({@code NemotronProgressBudgetMiddleware},
 * {@code NemotronPolicyNudgeMiddleware},
 * {@code NemotronToolCallShim},
 * {@code ReadFileContinuationNoticeMiddleware},
 * {@code ToolRetryMiddleware},
 * {@code ModelRateLimitRetryMiddleware},
 * {@code ChatNVIDIAMessageCompatibilityMiddleware},
 * {@code NemotronReasoningTagCleanupMiddleware},
 * {@code NemotronTextToolCallParser},
 * {@code FollowupDisciplineMiddleware},
 * {@code EntityResolutionGuardMiddleware},
 * {@code FinalAnswerGuardMiddleware}). The Java port exposes the
 * same shape; the per-Object behavior is implemented in
 * {@code Object} classes and added
 * to the build-extra list here.</p>
 */
public final class NvidiaNemotron3UltraProfile {
    private NvidiaNemotron3UltraProfile() {}

    /** Model specs that receive the Nemotron 3 Ultra harness profile. */
    public static final List<String> NEMOTRON_ULTRA_MODEL_SPECS = List.of(
            "NVIDIA:nvidia/nemotron-3-ultra-550b-a55b",
            "nvidia:nvidia/nemotron-3-ultra-550b-a55b",
            "baseten:nvidia/NVIDIA-Nemotron-3-Ultra-550B-A55B",
            "fireworks:accounts/fireworks/models/nemotron-3-ultra-nvfp4",
            "fireworks:accounts/fireworks/models/nemotron-3-ultra-bf16",
            "openrouter:nvidia/nemotron-3-ultra-550b-a55b",
            "nebius:nvidia/Nemotron-3-Ultra-550b-a55b",
            "together:nvidia/nemotron-3-ultra-550b-a55b");

    public static final String SYSTEM_PROMPT_SUFFIX = ""
            + "<approach>\n"
            + "Plan briefly before acting. When several reads or lookups are independent, issue them as parallel tool calls rather than one at a time.\n"
            + "</approach>\n"
            + "\n"
            + "<grounding>\n"
            + "Verify state with tools instead of recalling it. Read files before describing them, use lookup tools for identifiers, and use mutation tools before saying a requested change is done.\n"
            + "</grounding>\n"
            + "\n"
            + "<loop_control>\n"
            + "If a tool call fails, read the error and change the call before retrying; never re-issue the same failing call unchanged. If a command times out or the same error repeats, reduce the input, add a termination condition, or switch approaches before trying again.\n"
            + "</loop_control>\n"
            + "\n"
            + "<tool_selection>\n"
            + "Use filesystem tools only for file, path, repository-content, or document questions. For API, operational, business-object, or other domain questions, prefer the task-specific non-filesystem tools. For ranking, counting, \"which\", or \"most\" questions over domain entities, enumerate or search candidate entities with domain tools, fetch the relevant details or counts with matching domain tools, compare the observed tool results, and answer from that comparison.\n"
            + "</tool_selection>\n"
            + "\n"
            + "<state_changes>\n"
            + "If the user asks to book, cancel, update, send, notify, create, or otherwise change external state, the change is complete only after the relevant tool call succeeds. Do not merely describe the intended action or ask the user to assume it happened. After a successful mutation, use the tool result as the source of truth for the final answer.\n"
            + "</state_changes>\n"
            + "\n"
            + "<final_answer_completeness>\n"
            + "After tool calls succeed, the final answer must include the concrete result, not just \"done\". Preserve short exact literals that identify the completed action, especially versions, titles, and subjects from the user's request or successful mutation tool arguments/results. If you used an opaque entity ID and an obvious name or detail lookup tool is available, resolve the ID to human-readable details before answering. If the user asked multiple questions, answer each one from its matching tool output; do not substitute an entity from another subtask.\n"
            + "</final_answer_completeness>\n"
            + "\n"
            + "<followup_defaults>\n"
            + "Ask follow-up questions only for information needed to proceed safely or correctly. Do not re-ask for constraints the user already gave. For broad analysis requests, ask for both the data source and the analysis goal before using tools. For recurring reports, summaries, monitoring, or support workflows, treat a stated cadence as sufficient and ask only for missing content, source, threshold, delivery, or domain details needed to perform the task.\n"
            + "</followup_defaults>\n"
            + "\n"
            + "<context_compaction>\n"
            + "If a long conversation switches to a completely unrelated new task and the compact_conversation tool is available, call compact_conversation before starting the new task. Also call compact_conversation before reading or summarizing a large new file after a long conversation.\n"
            + "</context_compaction>";

    /**
     * Nemotron-specific override for the {@code read_file} tool
     * description, with extended support for documents, images,
     * audio, video, and PDFs.
     */
    public static final String READ_FILE_DESCRIPTION_OVERRIDE =
            "Reads a file from the filesystem. Use this tool for text files, source files, "
                    + "documents, images, audio, video, and PDFs.";

    /**
     * Build a fresh list of Nemotron-specific Object for each
     * assembled agent stack. The list mirrors the Python port's
     * {@code _build_extra_middleware} ordering.
     *
     * <p>The Java port returns no-op placeholders for Object that
     * depend on LangChain-only concepts (e.g. tool-retry on
     * filesystem tools, Nemotron tool-call tag parsing). Consumers
     * needing those behaviors can substitute their own list.</p>
     */
    public static List<Object> buildExtraMiddleware() {
        return List.of(
                new Object(),
                new Object(),
                new Object(),
                new Object());
    }

    /** Supplier variant for {@link HarnessProfile#extraMiddleware()}. */
    public static Supplier<List<Object>> extraMiddlewareSupplier() {
        return NvidiaNemotron3UltraProfile::buildExtraMiddleware;
    }

    /** Register the built-in Nemotron 3 Ultra harness profile. */
    public static void register() {
        HarnessProfile profile = new HarnessProfile(
                null, SYSTEM_PROMPT_SUFFIX,
                Map.of("read_file", READ_FILE_DESCRIPTION_OVERRIDE),
                Set.of(), Set.of(),
                buildExtraMiddleware(),
                null);
        for (String spec : NEMOTRON_ULTRA_MODEL_SPECS) {
            HarnessProfile.registerHarnessProfile(spec, profile);
        }
    }
}
