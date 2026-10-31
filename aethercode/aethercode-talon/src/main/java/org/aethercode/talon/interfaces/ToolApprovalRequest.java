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

package org.aethercode.talon.interfaces;

import java.util.List;
import java.util.Map;

/**
 * Tool approval request surfaced to a channel operator.
 *
 * <p>Java-native port of {@code deepagents_talon.interfaces.ToolApprovalRequest}.</p>
 */
public record ToolApprovalRequest(
        String conversationId,
        String interruptId,
        List<Map<String, Object>> actionRequests) {

    public ToolApprovalRequest {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        if (interruptId == null) {
            throw new IllegalArgumentException("interruptId must not be null");
        }
        actionRequests = actionRequests == null ? List.of() : List.copyOf(actionRequests);
    }
}
