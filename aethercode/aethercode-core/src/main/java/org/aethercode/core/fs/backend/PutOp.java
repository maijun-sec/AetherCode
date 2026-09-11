package org.aethercode.core.fs.backend;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A single put/delete op passed to {@link Store#batch}.
 *
 * <p>Mirror of the deepagents / langgraph <code>PutOp</code> type. A
 * {@code null} {@code value} is a <strong>delete</strong>; a non-null
 * value is an upsert. The store applies the op to the (namespace, key)
 * pair.</p>
 */
public record PutOp(
        List<String> namespace,
        String key,
        Map<String, Object> value
) {
    public PutOp {
        Objects.requireNonNull(namespace, "PutOp.namespace");
        Objects.requireNonNull(key, "PutOp.key");
    }

    public boolean isDelete() {
        return value == null;
    }
}
