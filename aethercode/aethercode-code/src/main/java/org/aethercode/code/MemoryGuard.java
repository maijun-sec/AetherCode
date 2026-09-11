package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Memory-pressure guard for the TUI.
 *
 * <p>Java-native port of the Python {@code deepagents_code.memory_guard}
 * module. The Java port tracks per-stream memory budgets and surfaces
 * notifications when an offload threshold is crossed. The actual
 * offload to the conversation-history backend is performed by the
 * {@code offload_middleware} port.</p>
 */
public final class MemoryGuard {
    private MemoryGuard() {}

    /** A budget label. */
    public record Budget(String label, long maxBytes, long warnBytes) {}

    private final Map<String, Budget> budgets = new LinkedHashMap<>();
    private final Map<String, Long> usages = new LinkedHashMap<>();

    /** Register a budget. */
    public void register(Budget budget) {
        if (budget == null) return;
        budgets.put(budget.label(), budget);
        usages.putIfAbsent(budget.label(), 0L);
    }

    /** Record additional bytes used by a budget. */
    public void record(String label, long bytes) {
        usages.merge(label, bytes, Long::sum);
        Budget b = budgets.get(label);
        if (b == null) return;
        long used = usages.getOrDefault(label, 0L);
        if (used >= b.maxBytes()) {
            Notifications.emit(Notifications.ActionId.TOOL_CANCELLED,
                    "Memory threshold reached",
                    "Budget " + label + " exceeded " + b.maxBytes() + " bytes; offload recommended.");
        } else if (used >= b.warnBytes()) {
            Notifications.emit(Notifications.ActionId.TOOL_CANCELLED,
                    "Memory warning",
                    "Budget " + label + " above warn threshold " + b.warnBytes() + " bytes.");
        }
    }

    /** Get the current usage for a budget. */
    public long usage(String label) {
        return usages.getOrDefault(label, 0L);
    }

    /** Reset usage for a budget. */
    public void reset(String label) {
        usages.put(label, 0L);
    }
}
