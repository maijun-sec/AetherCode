package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live panel showing subagents fanned out from within {@code js_eval} calls.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.subagent_panel}. The
 * Python module is a {@code Widget} that consumes a custom stream of
 * subagent lifecycle events and renders a docked, live-updating fan-out
 * panel with one row per subagent. The Java port preserves the public
 * data shape ({@link SubagentRecord}) and the lifecycle hooks.</p>
 */
public class SubagentPanel extends Widget {

    /** Subagent lifecycle state. */
    public enum Status { RUNNING, DONE, ERROR, CANCELLED }

    /** One subagent's live state within a phase. */
    public static final class SubagentRecord {
        public final String id;
        public String label;
        public Status status = Status.RUNNING;
        public long startedMonotonicMs;
        public Long durationMs;
        public String error;

        public SubagentRecord(String id, String label, long startedMonotonicMs) {
            this.id = id;
            this.label = label;
            this.startedMonotonicMs = startedMonotonicMs;
        }

        public double elapsedSeconds(long nowMs) {
            if (durationMs != null) return durationMs / 1000.0;
            return Math.max(0.0, (nowMs - startedMonotonicMs) / 1000.0);
        }
    }

    /** Per-subagent metadata for one tick of the panel. */
    public record SubagentRow(String id, String label, Status status,
                              double elapsedSeconds, String model, String error) {}

    private final Map<String, SubagentRecord> records = new HashMap<>();

    public SubagentPanel() {
        super("", "subagent-panel");
    }

    public Map<String, SubagentRecord> records() { return records; }

    /** Mark a subagent as started. */
    public void onStart(String id, String label, long nowMs) {
        records.put(id, new SubagentRecord(id, label, nowMs));
    }

    /** Mark a subagent as done. */
    public void onDone(String id, long durationMs) {
        SubagentRecord r = records.get(id);
        if (r == null) return;
        r.status = Status.DONE;
        r.durationMs = durationMs;
    }

    /** Mark a subagent as errored. */
    public void onError(String id, String error) {
        SubagentRecord r = records.get(id);
        if (r == null) return;
        r.status = Status.ERROR;
        r.error = error;
    }

    /** Mark a subagent as cancelled. */
    public void onCancelled(String id) {
        SubagentRecord r = records.get(id);
        if (r == null) return;
        r.status = Status.CANCELLED;
    }

    @Override
    public WidgetNode render() {
        List<WidgetNode> rows = new ArrayList<>();
        rows.add(new WidgetNode.Static("Subagents",
                WidgetNode.Role.PRIMARY, false, true, false));
        long now = System.currentTimeMillis();
        for (SubagentRecord r : records.values()) {
            String status = switch (r.status) {
                case RUNNING -> "running";
                case DONE -> "done";
                case ERROR -> "error";
                case CANCELLED -> "cancelled";
            };
            String row = String.format("%s  %s  %.1fs", r.label, status,
                    r.elapsedSeconds(now));
            WidgetNode.Role role = switch (r.status) {
                case RUNNING -> WidgetNode.Role.PRIMARY;
                case DONE -> WidgetNode.Role.SUCCESS;
                case ERROR -> WidgetNode.Role.ERROR;
                case CANCELLED -> WidgetNode.Role.MUTED;
            };
            rows.add(new WidgetNode.Static(row, role));
        }
        return new WidgetNode.Container(WidgetNode.Layout.VERTICAL, rows,
                "subagent-panel");
    }
}
