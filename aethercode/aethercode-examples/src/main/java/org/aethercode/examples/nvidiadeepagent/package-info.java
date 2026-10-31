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
