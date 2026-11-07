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

package org.aethercode.core.compact;

import org.aethercode.core.message.Message;

import java.util.List;

/**
 * Strategy the engine calls between LLM turns to decide whether to compact. The default
 * implementation lives in {@code aethercode-compact}; tests can plug fakes in.
 *
 * <p>Interface inversion: this interface lives in {@code aethercode-core} so the engine
 * can depend on the abstraction without dragging the compact module into its compile path.
 */
public interface Compactor {

    /**
     * @return true if the supplied transcript should be compacted before the next LLM call
     */
    boolean shouldCompact(List<Message> messages);

    /**
     * @return the new transcript (caller is responsible for installing it on the AppState),
     *         or {@code null} if compaction was skipped or failed
     */
    List<Message> compact(List<Message> messages);

    /**
     * R388n: summarise a transcript NOW, unconditionally.
     *
     * <p>{@link #compact(List)} is GATED — it returns {@code null}
     * unless the transcript is over the context window. That gate is
     * right for the automatic compaction (there is no point compacting
     * a small transcript) and wrong for the USER's explicit
     * "summarize from here": the user asked for a summary of a
     * conversation that may be perfectly small.
     *
     * <p>So the user-initiated path gets its own method, and the
     * default implementation delegates to {@code compact} — so an
     * implementation that does not support unconditional summarisation
     * still behaves (it just refuses, and the caller reports the
     * refusal).
     *
     * @param messages the transcript to summarise
     * @return the summary transcript, or {@code null} when the
     *   summarisation was not possible
     */
    default List<Message> summarizeNow(List<Message> messages) {
        return compact(messages);
    }
}
