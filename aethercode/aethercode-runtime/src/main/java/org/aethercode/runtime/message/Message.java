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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Sealed union of every message kind an LLM agent can see on the wire.
 *
 * <p>The port re-shapes the langchain hierarchy as a flat sealed
 * interface so that the Java 21 pattern matcher can do an exhaustive
 * switch. Variants that langchain only expresses via attributes
 * (e.g. <code>RemoveMessage</code>) are first-class variants here.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "role")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SystemMessage.class, name = "system"),
        @JsonSubTypes.Type(value = HumanMessage.class,  name = "human"),
        @JsonSubTypes.Type(value = AIMessage.class,     name = "ai"),
        @JsonSubTypes.Type(value = ToolMessage.class,   name = "tool"),
        @JsonSubTypes.Type(value = RemoveMessage.class, name = "remove")
})
public sealed interface Message
        permits SystemMessage, HumanMessage, AIMessage, ToolMessage, RemoveMessage {

    /** Unique identifier for the message; matches langchain's <code>id</code>. */
    String id();

    /** Optional human-readable name; matches langchain's <code>name</code>. */
    java.util.Optional<String> name();

    /** Discriminator role used for JSON serialization and Python parity. */
    String role();

    /** True when this message is just a control message (remove / tombstone). */
    default boolean isControl() {
        return this instanceof RemoveMessage;
    }
}
