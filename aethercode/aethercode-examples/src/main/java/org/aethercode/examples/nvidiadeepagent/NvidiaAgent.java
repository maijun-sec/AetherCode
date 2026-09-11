package org.aethercode.examples.nvidiadeepagent;

import org.aethercode.backends.BackendProtocol;
import org.aethercode.graph.CreateDeepAgent;
import org.aethercode.graph.DeepAgent;
import org.aethercode.middleware.SkillSource;
import org.aethercode.middleware.SubAgent;
import org.aethercode.middleware.SubAgentModel;
import org.aethercode.middleware.SubAgentTool;
import org.aethercode.tools.Tool;

import java.time.LocalDate;
import java.util.List;

/**
 * NVIDIA Deep Agent Skills &mdash; multi-model orchestrator.
 *
 * <p>Java port of
 * {@code deepagents-main/examples/nvidia_deep_agent/src/agent.py}.
 * Assembles a frontier orchestrator with two sub-agents:
 * <ul>
 *   <li>{@code researcher-agent} &mdash; delegated research, backed
 *       by a separate model (the Python port uses NVIDIA Nemotron
 *       Super; the Java port keeps the same model-spec shape so a
 *       caller can plug in any chat model).</li>
 *   <li>{@code data-processor-agent} &mdash; data analysis, ML,
 *       visualization, document processing. Uses GPU skills loaded
 *       from {@code /skills/}.</li>
 * </ul>
 *
 * <p>Sandbox selection (CPU vs GPU) is controlled by the
 * {@link NvidiaBackend.RuntimeContext RuntimeContext} at invoke
 * time; the default is GPU.</p>
 */
public final class NvidiaAgent {
    private NvidiaAgent() {}

    /**
     * Build the orchestrator + sub-agents.
     *
     * @param backend sandbox backend (use {@link NvidiaBackend#createBackend}
     *                to get a stub).
     * @param frontierModel model used by the orchestrator and the
     *                      data-processor sub-agent. Either a model
     *                      spec string or a chat-model object.
     * @param researchModel model used by the researcher sub-agent.
     */
    public static DeepAgent build(BackendProtocol backend,
                                  Object frontierModel,
                                  Object researchModel) {
        String today = LocalDate.now().toString();
        List<Tool> tools = List.of(NvidiaTools.tavilySearch());

        SubAgent researcher = SubAgent.builder(
                "researcher-agent",
                "Delegate research to this agent. Conducts web searches and gathers "
                        + "information on a topic. Give one focused research topic at a time.",
                NvidiaPrompts.RESEARCHER_INSTRUCTIONS.replace("{date}", today))
                .tools(tools.stream().map(SubAgentTool::of).toList())
                .model(researchModel == null ? null : SubAgentModel.fromModel(researchModel))
                .build();

        SubAgent dataProcessor = SubAgent.builder(
                "data-processor-agent",
                "Delegate data analysis, ML, visualization, and document processing tasks. "
                        + "Handles large datasets (CSV analysis, statistical profiling, anomaly detection), "
                        + "ML model training (classification, regression, clustering), chart creation, "
                        + "and bulk document extraction using GPU-accelerated NVIDIA tools.",
                NvidiaPrompts.DATA_PROCESSOR_INSTRUCTIONS.replace("{date}", today))
                .tools(tools.stream().map(SubAgentTool::of).toList())
                .model(frontierModel == null ? null : SubAgentModel.fromModel(frontierModel))
                .skills(List.of("/skills/"))
                .build();

        return CreateDeepAgent.create(
                frontierModel,
                tools,
                NvidiaPrompts.ORCHESTRATOR_INSTRUCTIONS.replace("{date}", today),
                null,
                List.of(researcher, dataProcessor),
                List.of(SkillSource.of("/skills/")),
                List.of("/memory/AGENTS.md"),
                null,
                backend,
                null,
                null,
                null,
                null,
                "nvidia_deep_agent");
    }

    /**
     * Convenience main entry point &mdash; assembles the agent and
     * prints its summary.
     */
    public static void main(String[] args) {
        DeepAgent agent = build(
                NvidiaBackend.createBackend(new NvidiaBackend.RuntimeContext(NvidiaBackend.SandboxType.GPU)),
                "anthropic:claude-sonnet-4-6",
                "nvidia:nemotron-3-super-120b-a12b");
        System.out.println("Assembled NVIDIA deep agent: " + agent.name());
        System.out.println("Tools: " + agent.tools().size());
        System.out.println("Middleware: " + agent.middlewareNames());
    }
}
