/**
 * Java port of {@code deepagents-main/examples/nvidia_deep_agent/}.
 *
 * <p>Four files:
 * <ul>
 *   <li>{@link org.aethercode.examples.nvidiadeepagent.NvidiaAgent}
 *       &mdash; assembles the frontier + Nemotron multi-model
 *       orchestrator with researcher and data-processor subagents.</li>
 *   <li>{@link org.aethercode.examples.nvidiadeepagent.NvidiaBackend}
 *       &mdash; backend factory that creates a sandbox with skills
 *       and memory pre-loaded; the Java port is illustrative and does
 *       not depend on {@code modal} or {@code langchain-modal}.</li>
 *   <li>{@link org.aethercode.examples.nvidiadeepagent.NvidiaPrompts}
 *       &mdash; orchestrator, researcher, and data-processor prompt
 *       templates.</li>
 *   <li>{@link org.aethercode.examples.nvidiadeepagent.NvidiaTools}
 *       &mdash; research tools (Tavily + webpage fetch).</li>
 * </ul>
 */
package org.aethercode.examples.nvidiadeepagent;
