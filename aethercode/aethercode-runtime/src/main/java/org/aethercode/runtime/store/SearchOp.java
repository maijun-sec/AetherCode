package org.aethercode.runtime.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A search query against a {@link Store}.
 *
 * <p>Mirror of langgraph's <code>SearchOp</code>. Filters by namespace
 * prefix, optional key prefix, an optional filter map, and a per-query
 * limit.</p>
 */
public record SearchOp(
        String namespacePrefix,
        Optional<String> keyPrefix,
        Map<String, Object> filter,
        Optional<Integer> limit,
        Optional<Integer> offset
) {
    public SearchOp {
        if (namespacePrefix == null) {
            throw new IllegalArgumentException("SearchOp.namespacePrefix is required");
        }
        filter = filter == null ? Map.of() : Map.copyOf(filter);
    }

    public static SearchOp of(String namespacePrefix) {
        return new SearchOp(namespacePrefix, Optional.empty(), Map.of(), Optional.empty(), Optional.empty());
    }

    public static SearchOp of(String namespacePrefix, int limit) {
        return new SearchOp(namespacePrefix, Optional.empty(), Map.of(), Optional.of(limit), Optional.empty());
    }
}
