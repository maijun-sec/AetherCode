package org.aethercode.runtime.store;

import java.util.Map;
import java.util.Optional;

/**
 * A pending put operation against a {@link Store}.
 *
 * <p>Mirror of langgraph's <code>PutOp</code>; collected by batch
 * methods and committed transactionally by concrete stores.</p>
 */
public record PutOp(
        String namespace,
        String key,
        Map<String, Object> value,
        Optional<String> createdAt
) {
    public PutOp {
        if (namespace == null || key == null) {
            throw new IllegalArgumentException("PutOp.namespace and key are required");
        }
        value = value == null ? Map.of() : Map.copyOf(value);
    }

    public static PutOp of(String namespace, String key, Map<String, Object> value) {
        return new PutOp(namespace, key, value, Optional.empty());
    }
}
