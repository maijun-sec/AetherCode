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

package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Auto-mode state and counters.
 *
 * <p>Java-native port of the Python {@code deepagents_code.auto_mode}
 * module. The Java port carries the public surface used by the
 * approval-mode HITL middleware and the classifier pipeline; the full
 * classifier implementation lands with the agent graph port.</p>
 */
public final class AutoMode {
    private AutoMode() {}

    private static final Logger LOG = LoggerFactory.getLogger(AutoMode.class);

    /** Per-thread Auto-mode counters. */
    public record AutoModeCounters(
            int totalDecisions,
            int approved,
            int denied,
            int errors) {
    }

    /** Per-call Auto decision. */
    public record AutoDecision(
            String toolCallId,
            ApprovalMode.Mode mode,
            String verdict,
            String reason) {
    }

    private static final Map<String, AutoModeCounters> COUNTERS = new LinkedHashMap<>();

    /** Read the counters for a thread. */
    public static AutoModeCounters counters(String threadId) {
        return COUNTERS.getOrDefault(threadId, new AutoModeCounters(0, 0, 0, 0));
    }

    /** Increment the counters for a thread. */
    public static void record(String threadId, boolean approved, boolean errored) {
        AutoModeCounters prev = counters(threadId);
        COUNTERS.put(threadId, new AutoModeCounters(
                prev.totalDecisions() + 1,
                prev.approved() + (approved ? 1 : 0),
                prev.denied() + (!approved && !errored ? 1 : 0),
                prev.errors() + (errored ? 1 : 0)));
    }

    /** Reset the counters for a thread. */
    public static void reset(String threadId) {
        COUNTERS.remove(threadId);
    }
}
