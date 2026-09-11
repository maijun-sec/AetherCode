/**
 * Java port of {@code deepagents-main/examples/deep_research/}.
 *
 * <p>Four files:
 * <ul>
 *   <li>{@link org.aethercode.examples.deepresearch.DeepResearchAgent}
 *       &mdash; assembles the orchestrator + subagent for a research
 *       workflow using {@code createDeepAgent}.</li>
 *   <li>{@link org.aethercode.examples.deepresearch.ResearchUtils}
 *       &mdash; message-formatting helpers that mirror
 *       {@code rich}-based pretty-printing.</li>
 *   <li>{@link org.aethercode.examples.deepresearch.ResearchPrompts}
 *       &mdash; prompt templates (workflow, subagent delegation,
 *       researcher instructions).</li>
 *   <li>{@link org.aethercode.examples.deepresearch.ResearchTools}
 *       &mdash; web search and think-tool implementations backed by
 *       {@link java.net.http.HttpClient}.</li>
 * </ul>
 */
package org.aethercode.examples.deepresearch;
