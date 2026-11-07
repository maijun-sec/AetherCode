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

package org.aethercode.core.permission;

import java.util.Map;

/**
 * The result of asking the permission system whether a tool invocation should be allowed,
 * denied, or surfaced to the user.
 *
 * <p>Mirrors the TS {@code PermissionResult} discriminated union. The {@code ask} variant
 * carries user-facing question text; the {@code deny} variant carries a {@code decisionReason}
 * that explains why (which the model receives as a tool_result error).
 */
public sealed interface PermissionResult {

    /**
     * Always allow. {@code updatedInput} may rewrite the input before the tool runs.
     *
     * <p><b>R388l — the optional {@code note}.</b> A user who approves a tool
     * call can attach a free-text comment ("yes, but write to /tmp first").
     * Before R388l the approval path had NO place to put it: the prompter
     * received a reason and <em>discarded</em> it on the allow branch, so the
     * comment would have reached the daemon and then evaporated — the exact
     * "silent degradation" shape (the user believes they said something; the
     * model never sees it).
     *
     * <p>The note is optional and the single-argument constructor is kept, so
     * every existing {@code new Allow(input)} call site keeps compiling and
     * keeps its meaning ("approved, nothing to say").
     *
     * <p>The note is model-facing context: the executor appends it to the
     * tool_result so the model reads the comment on the SAME turn it reads
     * the tool's output.
     */
    record Allow(Map<String, Object> updatedInput, String note) implements PermissionResult {
        /** Approved with no comment — the pre-R388l shape, kept intact. */
        public Allow(Map<String, Object> updatedInput) {
            this(updatedInput, null);
        }

        /** The user's comment, or {@code null} when they gave none. */
        public String note() {
            return note == null || note.isBlank() ? null : note;
        }
    }

    /** Always deny. {@code message} is sent to the model; {@code decisionReason} is for logs. */
    record Deny(String message, String decisionReason) implements PermissionResult {
        public static Deny of(String message) { return new Deny(message, message); }
    }

    /** Surface to the user. The UI / TUI is responsible for rendering the question. */
    record Ask(String question, Map<String, Object> options) implements PermissionResult {
        public Ask(String question) { this(question, Map.of()); }
    }
}
