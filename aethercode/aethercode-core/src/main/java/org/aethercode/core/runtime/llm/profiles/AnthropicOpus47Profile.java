package org.aethercode.core.runtime.llm.profiles;

import org.aethercode.core.runtime.llm.HarnessProfile;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in Claude Opus 4.7 harness profile.
 *
 * <p>Java-native port of
 * {@code deepagents.profiles.harness._anthropic_opus_4_7}. Layers
 * a system-prompt suffix onto {@code anthropic:claude-opus-4-7}
 * tuned to Claude Opus 4.7's documented behaviors:
 *
 * <ul>
 *   <li>Universal Claude guidance: parallel tool calls, grounded
 *       (non-speculative) answers, post-tool-result reflection.</li>
 *   <li>Claude Opus 4.7-specific overlays that counter the model's
 *       documented tendency to use tools and spawn subagents less
 *       aggressively than prior Opus generations when not prompted
 *       otherwise.</li>
 * </ul>
 */
public final class AnthropicOpus47Profile {
    private AnthropicOpus47Profile() {}

    public static final String SYSTEM_PROMPT_SUFFIX = ""
            + "<use_parallel_tool_calls>\n"
            + "If you intend to call multiple tools and there are no dependencies between the tool calls, make all of the independent tool calls in parallel. Prioritize calling tools simultaneously whenever the actions can be done in parallel rather than sequentially. For example, when reading 3 files, run 3 tool calls in parallel to read all 3 files into context at the same time. Maximize use of parallel tool calls where possible to increase speed and efficiency. However, if some tool calls depend on previous calls to inform dependent values like the parameters, do NOT call these tools in parallel and instead call them sequentially. Never use placeholders or guess missing parameters in tool calls.\n"
            + "</use_parallel_tool_calls>\n"
            + "\n"
            + "<investigate_before_answering>\n"
            + "Never speculate about code you have not opened. If the user references a specific file, you MUST read the file before answering. Make sure to investigate and read relevant files BEFORE answering questions about the codebase. Never make any claims about code before investigating unless you are certain of the correct answer - give grounded and hallucination-free answers.\n"
            + "</investigate_before_answering>\n"
            + "\n"
            + "<tool_result_reflection>\n"
            + "After receiving tool results, carefully reflect on their quality and determine optimal next steps before proceeding. Use your thinking to plan and iterate based on this new information, and then take the best next action.\n"
            + "</tool_result_reflection>\n"
            + "\n"
            + "<tool_usage>\n"
            + "When a task depends on the state of files, tests, or system output, use tools to observe that state directly rather than reasoning from memory about what it probably contains. Read files before describing them. Run tests before claiming they pass. Search the codebase before asserting a symbol does or does not exist. Active investigation with tools is the default mode of working, not a fallback.\n"
            + "</tool_usage>\n"
            + "\n"
            + "<subagent_usage>\n"
            + "Do not spawn a subagent for work you can complete directly in a single response (e.g. refactoring a function you can already see).\n"
            + "\n"
            + "Spawn multiple subagents in the same turn when fanning out across items or reading multiple files.\n"
            + "</subagent_usage>";

    /** Register the built-in Claude Opus 4.7 harness profile. */
    public static void register() {
        HarnessProfile.registerHarnessProfile("anthropic:claude-opus-4-7",
                new HarnessProfile(
                        null, SYSTEM_PROMPT_SUFFIX,
                        Map.of(), Set.of(), Set.of(), List.of(), null));
    }
}
