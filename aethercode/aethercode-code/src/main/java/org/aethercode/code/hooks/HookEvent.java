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

package org.aethercode.code.hooks;

/**
 * Lifecycle event the Hooks v2 system can fire.
 *
 * <p>Java-native port of the Python
 * {@code deepagents_code.hooks.models.domain.HookEvent} {@code StrEnum}.
 * Values are kept lowercase to match the canonical
 * <code>hook_event_name</code> strings used on the wire.</p>
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

    HookEvent(String wireName) {
        this.wireName = wireName;
    }

    /** Canonical PascalCase wire name used by the
     *  <code>hook_event_name</code> JSON discriminator. */
    public String wireName() {
        return wireName;
    }

    /**
     * Look up a hook event from its canonical wire name.
     *
     * @param wireName PascalCase wire name such as {@code "PreToolUse"}.
     * @return Matching {@link HookEvent}.
     * @throws IllegalArgumentException when {@code wireName} does not match
     *     a known event.
     */
    public static HookEvent fromWireName(String wireName) {
        for (HookEvent e : values()) {
            if (e.wireName.equals(wireName)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown hook event wire name: " + wireName);
    }

    @Override
    public String toString() {
        return wireName;
    }
}
