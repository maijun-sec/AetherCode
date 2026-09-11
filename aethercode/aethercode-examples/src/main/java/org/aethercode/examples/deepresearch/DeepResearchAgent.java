package org.aethercode.examples.deepresearch;

import org.aethercode.graph.CreateDeepAgent;
import org.aethercode.graph.DeepAgent;
import org.aethercode.middleware.SubAgent;
import org.aethercode.middleware.SubAgentPrompts;
import org.aethercode.tools.Tool;

import java.time.LocalDate;
import java.util.List;

/**
 * Deep research agent entry point.
 *
 * <p>Java port of {@code deepagents-main/examples/deep_research/agent.py}.
 * Assembles a frontier orchestrator + research sub-agent using the
 * Java-native {@link CreateDeepAgent} factory.</p>
 *
 * <p>The orchestrator's system prompt is composed of
 * {@link ResearchPrompts#RESEARCH_WORKFLOW_INSTRUCTIONS} +
 * {@link ResearchPrompts#SUBAGENT_DELEGATION_INSTRUCTIONS}; the
 * sub-agent uses {@link ResearchPrompts#RESEARCHER_INSTRUCTIONS}
 * formatted with today's date.</p>
 *
 * <p>Limits default to 3 concurrent research units and 3 researcher
 * iterations, matching the Python port.</p>
 */
public final class DeepResearchAgent {
    private DeepResearchAgent() {}

    /** Default maximum concurrent research sub-agents per iteration. */
    public static final int DEFAULT_MAX_CONCURRENT_RESEARCH_UNITS = 3;
    /** Default maximum number of researcher delegation rounds. */
    public static final int DEFAULT_MAX_RESEARCHER_ITERATIONS = 3;

    /** Compiled orchestrator instruction string (callers can format / override). */
    public static String composeInstructions(int maxConcurrent, int maxIterations) {
        return ResearchPrompts.RESEARCH_WORKFLOW_INSTRUCTIONS
                + "\n\n"
                + "=".repeat(80)
                + "\n\n"
                + ResearchPrompts.SUBAGENT_DELEGATION_INSTRUCTIONS
                        .replace("{max_concurrent_research_units}", String.valueOf(maxConcurrent))
                        .replace("{max_researcher_iterations}", String.valueOf(maxIterations));
    }

    /**
     * Build the researcher sub-agent spec.
     */
    public static SubAgent buildResearcherSubAgent(String currentDate, List<Tool> tools) {
        return SubAgent.builder(
                "research-agent",
                "Delegate research to the sub-agent researcher. Only give this researcher one topic at a time.",
                ResearchPrompts.RESEARCHER_INSTRUCTIONS.replace("{date}", currentDate))
                .tools(tools.stream().map(t -> org.aethercode.middleware.SubAgentTool.of(t)).toList())
                .build();
    }

    /**
     * Build a research agent with the default tools (tavily + think).
     *
     * <p>The model is a model-spec string. Callers that need a
     * pre-resolved chat model can pass it via
     * {@link #build(Object, int, int, List, SubAgent)}.</p>
     */
    public static DeepAgent build(String modelSpec) {
        return build(modelSpec,
                DEFAULT_MAX_CONCURRENT_RESEARCH_UNITS,
                DEFAULT_MAX_RESEARCHER_ITERATIONS,
                List.of(ResearchTools.tavilySearch(), ResearchTools.thinkTool()),
                null);
    }

    /**
     * Build a research agent with full control over limits, tools,
     * and sub-agents.
     *
     * <p>Pass {@code customSubagent = null} to use the default
     * researcher; pass a non-null spec to override (e.g. for a
     * domain-specific researcher).</p>
     */
    public static DeepAgent build(Object model,
                                   int maxConcurrent,
                                   int maxIterations,
                                   List<Tool> tools,
                                   SubAgent customSubagent) {
        String instructions = composeInstructions(maxConcurrent, maxIterations);
        String today = LocalDate.now().toString();
        List<SubAgent> subagents = customSubagent == null
                ? List.of(buildResearcherSubAgent(today, tools))
                : List.of(customSubagent);
        return CreateDeepAgent.create(
                model,
                tools,
                instructions,
                null,
                subagents,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "deep_research_agent");
    }

    /**
     * Convenience {@code main} method showing how to wire up a
     * research agent for interactive use.
     */
    public static void main(String[] args) {
        // Without a real model this example prints a summary of the
        // assembled agent rather than running it.
        DeepAgent agent = build("anthropic:claude-sonnet-4-5");
        System.out.println("Assembled research agent: " + agent.name());
        System.out.println("Tools: " + agent.tools().size());
        System.out.println("Middleware: " + agent.middlewareNames());
        // Reference SubAgentPrompts so the static constants are kept
        // in the compiled example; the constant documents the default
        // general-purpose sub-agent that CreateDeepAgent may add.
        System.out.println("Default general-purpose sub-agent: "
                + SubAgentPrompts.GENERAL_PURPOSE_SUBAGENT.name());
    }
}
