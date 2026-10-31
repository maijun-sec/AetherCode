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

package org.aethercode.runtime.message;

import java.util.Optional;

/**
 * A control message used to delete a previously-stored message by id.
 *
 * <p>Mirror of langchain's <code>RemoveMessage(id=...)</code>. When a
 * reducer sees a {@code RemoveMessage} with id <em>X</em> it drops the
 * message with id <em>X</em> from the state. The message itself is
 * consumed and does not stay in the conversation.</p>
 */
public record RemoveMessage(
        String id,
        Optional<String> name
) implements Message {

    public RemoveMessage {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("RemoveMessage.id is required");
        }
    }

    public RemoveMessage(String id) {
        this(id, Optional.empty());
    }

    @Override
    public String role() {
        return "remove";
    }
}
