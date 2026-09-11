package org.aethercode.deepagents.middleware;

import java.util.Set;

/**
 * Constants and shared prompts for the subagent middleware.
 *
 * <p>Java-native port of the prompt templates and excluded-state-key
 * set at the top of
 * {@code deepagents.middleware.subagents}. The strings mirror the
 * Python port one-for-one so the model receives the same guidance
 * the Python port emits.</p>
 */
public final class SubAgentPrompts {
    private SubAgentPrompts() {}

    public static final String DEFAULT_SUBAGENT_PROMPT = """
            In order to complete the objective that the user asks of you, you have access to a number of standard tools.

            The calling agent only sees your final assistant message, not your intermediate work, tool results, or status tracking. Ensure your final response contains the complete answer.""";

    public static final String DEFAULT_GENERAL_PURPOSE_DESCRIPTION =
            "General-purpose agent for researching complex questions, searching for files and content, "
                    + "and executing multi-step tasks. When you are searching for a keyword or file and are "
                    + "not confident that you will find the right match in the first few tries use this agent "
                    + "to perform the search for you. This agent has access to all tools as the main agent.";

    public static final SubAgent GENERAL_PURPOSE_SUBAGENT = SubAgent.builder(
            "general-purpose",
            DEFAULT_GENERAL_PURPOSE_DESCRIPTION,
            DEFAULT_SUBAGENT_PROMPT).build();

    public static final String TASK_TOOL_DESCRIPTION = """
            Launch an ephemeral subagent to handle a complex, multi-step task in an isolated context window.

            Available agent types and the tools they have access to:
            {available_agents}

            Specify subagent_type to select the agent. Usage notes:
            - Launch multiple agents concurrently when their tasks are independent, using a single message with multiple tool calls.
            - Each invocation is stateless: the agent sees only the prompt you give it and returns a single final report. Put full detail in the prompt and state exactly what it should return.
            - The agent's report is not shown to the user; relay a summary yourself.
            - Tell the agent whether to create content, analyze, or only research, since it cannot see the user's intent.
            - If an agent's description says to use it proactively, do so without waiting to be asked.
            - When only general-purpose is available, use it for any complex, context-heavy task; it has the same capabilities as the main agent.""";

    public static final Set<String> EXCLUDED_STATE_KEYS = Set.of(
            "messages", "todos", "structured_response");

    public static final String SUBAGENT_RESPONSE_FORMAT_CONFIG_KEY =
            "__deepagents_subagent_response_format";
}
