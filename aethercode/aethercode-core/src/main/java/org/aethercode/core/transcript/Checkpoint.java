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

package org.aethercode.core.transcript;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.aethercode.core.message.Message;

/**
 * a snapshot of a conversation transcript at a point in time.
 * Immutable — restore is by creating a new message list and replacing the
 * live one. The {@code label} is a free-form user-facing hint, e.g.
 * {@code "before-edit"} or {@code "checkpoint-3"}.
 */
public record Checkpoint(
        String id,
        String label,
        Instant createdAt,
        List<Message> messages
) {
    public Checkpoint {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (label == null) label = "";
        if (createdAt == null) createdAt = Instant.now();
        Objects.requireNonNull(messages, "messages");
        messages = List.copyOf(messages);
    }

    public int messageCount() { return messages.size(); }

    public String shortId() {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
