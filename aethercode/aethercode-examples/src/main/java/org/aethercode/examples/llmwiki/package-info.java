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
