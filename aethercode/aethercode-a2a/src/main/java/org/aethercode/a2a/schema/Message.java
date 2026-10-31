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

package org.aethercode.a2a.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * a single communication turn within an A2A {@link Task}.
 * Mirrors the v0.3 spec: {@code role} is "user" or "agent";
 * the body is an array of {@link Part}s.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
        @JsonProperty("role") String role,
        @JsonProperty("parts") List<Part> parts,
        @JsonProperty("messageId") String messageId) {

    public Message {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(parts, "parts");
        if (!"user".equals(role) && !"agent".equals(role)) {
            throw new IllegalArgumentException("role must be 'user' or 'agent', got " + role);
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("message must have at least one part");
        }
        if (messageId == null || messageId.isEmpty()) {
            messageId = UUID.randomUUID().toString();
        }
    }

    public static Message user(Part... parts) {
        return new Message("user", List.of(parts), null);
    }
    public static Message agent(Part... parts) {
        return new Message("agent", List.of(parts), null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("parts", parts.stream().map(Part::toMap).toList());
        m.put("messageId", messageId);
        return m;
    }
}
