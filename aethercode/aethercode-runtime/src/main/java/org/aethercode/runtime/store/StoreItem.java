package org.aethercode.runtime.store;

import java.util.Map;
import java.util.Optional;

/**
 * A single entry in a {@link Store}.
 *
 * <p>Mirror of langgraph's <code>Item</code>:
 * <code>{key, namespace, value, createdAt, updatedAt}</code>.</p>
 */
public record StoreItem(
        String namespace,
        String key,
        Map<String, Object> value,
        Optional<String> createdAt,
        Optional<String> updatedAt
) {
    public StoreItem {
        if (namespace == null || key == null) {
            throw new IllegalArgumentException("StoreItem.namespace and key are required");
        }
        value = value == null ? Map.of() : Map.copyOf(value);
    }

    public static StoreItem of(String namespace, String key, Map<String, Object> value) {
        return new StoreItem(namespace, key, value, Optional.empty(), Optional.empty());
    }
}
