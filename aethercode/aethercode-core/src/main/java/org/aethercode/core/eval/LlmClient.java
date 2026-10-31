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

package org.aethercode.core.eval;

/**
 * Minimal LLM completion interface used by the eval module's
 * {@link JudgeClient}.
 *
 * <p>This is intentionally a separate interface from the real
 * {@code org.aethercode.core.llm.ChatClient} because:
 * <ul>
 *   <li>The judge only needs a one-shot string-in/string-out call,
 *       not the streaming protocol ChatClient provides.</li>
 *   <li>Mock test fixtures can implement this trivially without
 *       pulling in the full LLM stack.</li>
 *   <li>Production code bridges via a tiny adapter that reads
 *       from a stream and joins the text blocks.</li>
 * </ul>
 *
 * <p>Implementations MUST be thread-safe; the harness may invoke
 * the judge from multiple worker threads.
 */
public interface LlmClient {

    /**
     * Run a one-shot completion and return the joined text.
     *
     * @param prompt   the full prompt (system + user concatenated)
     * @param maxTokens soft cap on output length; implementations
     *                 should truncate gracefully if hit
     * @return the model's text response; never null
     */
    String complete(String prompt, int maxTokens);
}