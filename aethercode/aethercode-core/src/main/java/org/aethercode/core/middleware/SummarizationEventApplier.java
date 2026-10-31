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

package org.aethercode.core.middleware;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Apply a prior {@link SummarizationEvent} to a raw message
 * list, producing the effective transcript the model would
 * see.
 *
 * <p>Java-native port of the static helper
 * {@code SummarizationMiddleware._apply_event_to_messages} in
 * {@code deepagents.middleware.summarization}. When a prior
 * summarization event exists, the effective conversation is
 * the summary message followed by all messages from
 * {@code cutoff_index} onward. When no event exists (or the
 * event is malformed), the raw list is returned unchanged.</p>
 */
public final class SummarizationEventApplier {
    private static final Logger log = Logger.getLogger(SummarizationEventApplier.class.getName());

    private SummarizationEventApplier() {}

    /**
     * Translate an effective-list cutoff index to an absolute state
     * index. When a prior summarization event exists, the effective
     * message list starts with the summary message at index 0; the
     * -1 accounts for the summary message at effective index 0,
     * which does not correspond to a real state message.
     *
     * <p>Mirrors the Python port's
     * {@code SummarizationMiddleware._compute_state_cutoff}.</p>
     */
    public static int computeStateCutoff(java.util.Map<String, Object> event, int effectiveCutoff) {
        if (event == null) return effectiveCutoff;
        Object priorCutoff = event.get("cutoff_index");
        if (!(priorCutoff instanceof Integer)) {
            // Malformed event: fall back to the effective cutoff.
            return effectiveCutoff;
        }
        return (Integer) priorCutoff + effectiveCutoff - 1;
    }

    /**
     * Compute the effective message list. If {@code event} is
     * {@code null} or malformed, the raw {@code messages} are
     * returned unchanged. Otherwise the result is
     * {@code [summary_message] + messages.subList(cutoff_index, ...)}.
     */
    public static <T> List<T> apply(List<T> messages, Map<String, Object> event) {
        if (event == null) {
            return messages == null ? List.of() : new ArrayList<>(messages);
        }
        Object summaryMsg;
        Integer cutoffIdx;
        try {
            summaryMsg = event.get("summary_message");
            Object cutoff = event.get("cutoff_index");
            if (!(cutoff instanceof Integer)) {
                log.log(Level.WARNING, "Malformed _summarization_event: cutoff_index is not int: {0}",
                        cutoff);
                return messages == null ? List.of() : new ArrayList<>(messages);
            }
            cutoffIdx = (Integer) cutoff;
        } catch (NullPointerException | ClassCastException exc) {
            log.log(Level.WARNING, "Malformed _summarization_event (missing keys): {0}", exc.toString());
            return messages == null ? List.of() : new ArrayList<>(messages);
        }
        if (summaryMsg == null) {
            log.warning("Malformed _summarization_event: summary_message is null");
            return messages == null ? List.of() : new ArrayList<>(messages);
        }
        // Defensive copy: the test inputs may be immutable
        // Lists (List.of(...)) which would throw on subList.
        List<T> base = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        if (cutoffIdx > base.size()) {
            log.log(Level.WARNING,
                    "Summarization cutoff_index {0} exceeds message count {1}; remaining slice will be empty",
                    new Object[]{cutoffIdx, base.size()});
            List<T> out = new ArrayList<>();
            out.add((T) summaryMsg);
            return out;
        }
        List<T> out = new ArrayList<>();
        out.add((T) summaryMsg);
        out.addAll(base.subList(cutoffIdx, base.size()));
        return out;
    }
}
