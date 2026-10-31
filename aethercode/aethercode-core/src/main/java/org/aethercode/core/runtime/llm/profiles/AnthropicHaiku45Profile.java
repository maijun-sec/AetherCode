/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.runtime.llm.profiles;

import org.aethercode.core.runtime.llm.HarnessProfile;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in Claude Haiku 4.5 harness profile.
 *
 * <p>Java-native port of
 * {@code deepagents.profiles.harness._anthropic_haiku_4_5}.
 * Layers Anthropic's universal Claude guidance onto
 * {@code anthropic:claude-haiku-4-5} &mdash; parallel tool calls,
 * grounded (non-speculative) answers, and post-tool-result
 * reflection. No Haiku-4.5-specific overlays; the module exists as
 * the audit anchor documenting the review.</p>
 */
public final class AnthropicHaiku45Profile {
    private AnthropicHaiku45Profile() {}

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
            + "</tool_result_reflection>";

    /** Register the built-in Claude Haiku 4.5 harness profile. */
    public static void register() {
        HarnessProfile.registerHarnessProfile("anthropic:claude-haiku-4-5",
                new HarnessProfile(
                        null, SYSTEM_PROMPT_SUFFIX,
                        Map.of(), Set.of(), Set.of(), List.of(), null));
    }
}
