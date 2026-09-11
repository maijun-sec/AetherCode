/**
 * Java port of {@code deepagents-main/examples/llm-wiki/}.
 *
 * <p>Nine files. The Java port is illustrative: the LangSmith CLI
 * subprocess surface is stubbed out (no external {@code langsmith}
 * binary is invoked). Callers can plug in their own hub client
 * through {@link org.aethercode.examples.llmwiki.CliDeps}.</p>
 *
 * <p>Files:
 * <ul>
 *   <li>{@link org.aethercode.examples.llmwiki.LlmWikiModels} &mdash; shared
 *       data classes (RunnerConfig, CliDeps, RunResult).</li>
 *   <li>{@link org.aethercode.examples.llmwiki.LlmWikiLog} &mdash;
 *       append-only log helpers ({@code /log.md} entries).</li>
 *   <li>{@link org.aethercode.examples.llmwiki.LlmWikiIndex} &mdash;
 *       index page generation from {@code /wiki/} markdown.</li>
 *   <li>{@link org.aethercode.examples.llmwiki.LlmWikiQuery} &mdash;
 *       query-mode workflow and decision parser.</li>
 *   <li>{@link org.aethercode.examples.llmwiki.LlmWikiLint} &mdash;
 *       lint-mode single-pass workflow.</li>
 *   <li>{@link org.aethercode.examples.llmwiki.LlmWikiIngest} &mdash;
 *       ingest-mode (review + apply) workflow.</li>
 *   <li>{@link org.aethercode.examples.llmwiki.LlmWikiInit} &mdash;
 *       init-mode workflow with hub source flag handling.</li>
 *   <li>{@link org.aethercode.examples.llmwiki.LlmWikiHelpers} &mdash;
 *       shared helpers, CLI parser, sandbox backend, agent-mode
 *       runner, and the main {@code run(...)} entry point.</li>
 *   <li>{@link org.aethercode.examples.llmwiki.LlmWikiRunner} &mdash;
 *       CLI entry point that wires everything together.</li>
 * </ul>
 */
package org.aethercode.examples.llmwiki;
