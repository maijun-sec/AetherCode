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

package org.aethercode.orchestration.runtime;

import org.aethercode.orchestration.selfcorrect.SelfCorrectionLoop;
import org.aethercode.orchestration.verifier.Verifier.VerificationResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Append-only trace of one {@link AgentRuntime} invocation.
 *
 * <p>Carries every (action, verifier result) pair the runtime
 * observed, so an audit log can answer "what did the agent try,
 * which verifiers failed, how did self-correction react?" without
 * re-running anything.</p>
 *
 * <p>The trace is intentionally lightweight — just a list of
 * {@link Entry} records. A real audit pipeline would enrich each
 * entry with timestamps / token counts / model ids, but those are
 * outside the runtime's scope.</p>
 */
public class RuntimeTrace {

    /** A single action + verifier-result pair (or ensemble marker). */
    public record Entry(String phase, Object action, VerificationResult result) {
        public Entry {
            if (phase == null || phase.isBlank()) {
                throw new IllegalArgumentException("phase must be non-blank");
            }
            if (action == null) {
                throw new IllegalArgumentException("action must be non-null");
            }
            if (result == null) {
                throw new IllegalArgumentException("result must be non-null");
            }
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public void add(Entry e) { entries.add(e); }

    /**
     * Fold a {@link SelfCorrectionLoop.LoopResult} into the trace so
     * the audit log shows every self-correction attempt. The loop
     * result is preserved as a whole; the trace just lists the
     * per-attempt entries for inspection.
     */
    public void absorb(SelfCorrectionLoop.LoopResult<?> lr) {
        if (lr == null) return;
        for (SelfCorrectionLoop.Attempt a : lr.attempts()) {
            entries.add(new Entry("self-correct", a.action(), a.result()));
        }
    }

    public List<Entry> entries() { return List.copyOf(entries); }

    public int size() { return entries.size(); }

    /** Last entry, or null if the trace is empty. */
    public Entry last() { return entries.isEmpty() ? null : entries.get(entries.size() - 1); }
}
