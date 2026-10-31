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

package org.aethercode.code.hooks.models;

/**
 * Hook lifecycle event types.
 *
 * <p>Java 21 port of the Python
 * {@code deepagents_code.hooks.models.domain.HookEvent} enum.</p>
 */
public enum HookEvent {
    SESSION_START("SessionStart"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    SESSION_END("SessionEnd"),
    PERMISSION_REQUEST("PermissionRequest"),
    NOTIFICATION("Notification"),
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    POST_TOOL_USE_FAILURE("PostToolUseFailure"),
    PRE_COMPACT("PreCompact"),
    STOP("Stop"),
    SUBAGENT_START("SubagentStart"),
    SUBAGENT_STOP("SubagentStop");

    private final String wireName;

    HookEvent(String wireName) { this.wireName = wireName; }

    /** PascalCase wire name. */
    public String wireName() { return wireName; }

    /** Look up by wire name; case-sensitive. */
    public static HookEvent fromWireName(String wireName) {
        for (HookEvent e : values()) {
            if (e.wireName.equals(wireName)) return e;
        }
        throw new IllegalArgumentException("Unknown hook event wire name: " + wireName);
    }
}
