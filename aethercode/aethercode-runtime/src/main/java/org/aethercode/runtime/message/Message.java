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
