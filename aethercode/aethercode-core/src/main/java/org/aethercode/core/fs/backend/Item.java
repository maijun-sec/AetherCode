package org.aethercode.core.fs.backend;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A single record stored in a {@link Store}.
 *
 * <p>Mirror of the deepagents / langgraph <code>Item</code> dataclass.
 * Carries the namespace tuple, the item's key, and a value map whose
 * shape is dictated by the caller (the deepagents store contract
 * uses a {@code content} string plus an {@code encoding} and optional
 * {@code created_at} / {@code modified_at} timestamps).</p>
 */
public record Item(
        List<String> namespace,
        String key,
        Map<String, Object> value
) {
    public Item {
        Objects.requireNonNull(namespace, "Item.namespace");
        Objects.requireNonNull(key, "Item.key");
        Objects.requireNonNull(value, "Item.value");
    }
}
