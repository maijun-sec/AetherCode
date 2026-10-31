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

package org.aethercode.deepagents.selfimprove;

import java.util.List;
import java.util.Objects;

/**
 * R241.2 (O-3): the single contract for "ask an LLM what went
 * wrong" — the moment-of-failure reflection step the deep-agent
 * loop uses to convert a failed tool call into a structured
 * {@link ReasoningUnit}.
 *
 * <p>Modelled on the <em>Reflexion</em> pattern (Shinn et al.,
 * 2023, paper 4 §11.1): the agent verbalises what it did wrong
 * and stores the verbalisation for future runs.
 *
 * <h2>Why a separate interface?</h2>
 *
 * <p>Tests can inject a {@link StubReflector} that returns
 * canned reflections; production wires a
 * {@link ChatClientReflector} that uses the existing
 * {@link org.aethercode.core.llm.ChatClient} abstraction. The
 * deep-agent graph never imports a concrete chat model — same
 * pattern as {@code aethercode-acp}'s {@code Client} SPI.
 *
 * <h2>Inputs and outputs</h2>
 *
 * <p>The reflector is intentionally a simple text-in / text-out
 * contract. The caller (typically {@link SelfReflectMiddleware})
 * is responsible for assembling a {@code systemPrompt} that
 * instructs the model on the desired reflection shape and
 * passing a {@code userPrompt} that contains the trajectory
 * excerpts. The reflector returns the raw text the model
 * produced; the caller parses it.
 */
public interface Reflector {

    /**
     * Run a single reflection.
     *
     * @param systemPrompt instructions that shape the
     *                      reflection (e.g. "you are a coding
     *                      agent; respond with a JSON object
     *                      with keys error_pattern and
     *                      fix_strategy").
     * @param userPrompt   the actual question (typically a
     *                      transcript excerpt or a
     *                      failure-description prompt).
     * @return              the model's raw text response.
     *                      Never null. Throws on transport
     *                      errors; the caller decides whether
     *                      to retry or surface.
     */
    String reflect(String systemPrompt, String userPrompt) throws Exception;

    /** Convenience overload for batched reflection. */
    default List<String> reflectAll(String systemPrompt, List<String> userPrompts) throws Exception {
        Objects.requireNonNull(userPrompts, "userPrompts");
        java.util.ArrayList<String> out = new java.util.ArrayList<>(userPrompts.size());
        for (String p : userPrompts) {
            out.add(reflect(systemPrompt, p));
        }
        return out;
    }
}
