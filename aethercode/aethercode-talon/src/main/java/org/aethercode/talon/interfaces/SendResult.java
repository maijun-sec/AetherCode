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

import java.util.Optional;

/**
 * Result of a channel send operation.
 *
 * <p>Java-native port of {@code deepagents_talon.interfaces.SendResult}.</p>
 */
public record SendResult(
        boolean success,
        Optional<String> messageId,
        Optional<String> error,
        boolean retryable) {

    public SendResult {
        messageId = messageId == null ? Optional.empty() : messageId;
        error = error == null ? Optional.empty() : error;
    }

    /** Success result with no message id. */
    public static SendResult ok() {
        return new SendResult(true, Optional.empty(), Optional.empty(), false);
    }

    /** Success result with a known message id. */
    public static SendResult ok(String messageId) {
        return new SendResult(true, Optional.ofNullable(messageId), Optional.empty(), false);
    }

    /** Failed result. */
    public static SendResult fail(String error, boolean retryable) {
        return new SendResult(false, Optional.empty(), Optional.ofNullable(error), retryable);
    }

    /** Failed result with a known message id. */
    public static SendResult fail(String messageId, String error, boolean retryable) {
        return new SendResult(false, Optional.ofNullable(messageId),
                Optional.ofNullable(error), retryable);
    }
}
