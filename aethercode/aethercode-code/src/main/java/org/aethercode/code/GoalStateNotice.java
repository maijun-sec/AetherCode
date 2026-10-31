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

import java.util.Map;

/**
 * Helpers for goal-lifecycle control messages embedded in the
 * conversation transcript.
 *
 * <p>Java-native port of the Python {@code deepagents_code.goal_state_notice}
 * module. The Java port exposes the predicates the rubric grader uses to
 * decide which messages are part of the user/agent exchange vs. internal
 * control turns.</p>
 */
public final class GoalStateNotice {
    private GoalStateNotice() {}

    private static final Logger LOG = LoggerFactory.getLogger(GoalStateNotice.class);

    /** Marker that identifies a control message vs. a regular transcript turn. */
    public static final String CONTROL_MESSAGE_MARKER = "[GOAL_CONTROL]";

    /** Whether a message is a synthetic control turn (filtered out of the grader's view). */
    public static boolean isConversationControlMessage(Object message) {
        if (message == null) return false;
        // The Java port doesn't have a single message type; the rubric code
        // accepts any object and looks for the marker in known locations.
        try {
            java.lang.reflect.Method m = message.getClass().getMethod("additionalKwargs");
            if (m != null) {
                Object kwargs = m.invoke(message);
                if (kwargs instanceof Map<?, ?> map) {
                    Object marker = map.get("__dcode_goal_control__");
                    if (Boolean.TRUE.equals(marker)) return true;
                }
            }
        } catch (Exception ignored) {
            // not a message-shaped object
        }
        return false;
    }

    /** Stamp a message as a conversation-control turn. */
    public static Map<String, Object> controlMessageMetadata() {
        return Map.of("__dcode_goal_control__", Boolean.TRUE);
    }

    /** Log a notice about a goal state change. */
    public static void logGoalStateChange(String threadId, ResumeState.GoalStatus from,
                                          ResumeState.GoalStatus to, String note) {
        LOG.info("Thread {} goal state change: {} -> {} (note: {})",
                threadId, from, to, note);
    }
}
