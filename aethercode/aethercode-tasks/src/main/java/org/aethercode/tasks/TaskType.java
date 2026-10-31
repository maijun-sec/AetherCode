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

package org.aethercode.tasks;

/**
 * Type of a {@link Task}. Modelled after Claude Code's {@code TaskType}
 * (local_bash / local_agent / remote_agent / teammate / workflow / monitor /
 * dream). We collapse to a smaller set for the Java runtime: every long-lived
 * unit of work is either a {@code USER} task (a top-level query from the
 * human), an {@code AGENT} task (a subagent spawned by AgentTool), or a
 * {@code TOOL_BATCH} (one tool-call batch inside a turn).
 */
public enum TaskType {
    /** A top-level query from the user. Has no parent. */
    USER,
    /** A subagent spawned via AgentTool. Has a parent task. */
    AGENT,
    /** A batch of tool calls inside a turn. Used for grouping / progress. */
    TOOL_BATCH,
    /** A workflow — a multi-step automated procedure. */
    WORKFLOW;

    /** ID prefix used in {@link Task#id()}. */
    public String idPrefix() {
        return switch (this) {
            case USER       -> "u";
            case AGENT      -> "a";
            case TOOL_BATCH -> "b";
            case WORKFLOW   -> "w";
        };
    }
}
